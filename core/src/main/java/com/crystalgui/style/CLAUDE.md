# `style` — the cascade

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../AGENTS.md), which keeps the rules every session needs.

## Stack 2: Style — the cascade

> **Full reference: `docs/CGUI_STYLE_RENDER_PIPELINE.md`.** This is the map, not the territory.
> 86 files — the largest stack in the engine.

### Origins

`StyleOrigin` is priority-ordered: `DEFAULT(0) < USER_AGENT(1) < STYLESHEET(2) < INLINE(3) <
IMPORTANT(4) < ANIMATION(5)`. Two are easy to get backwards and both are deliberate:

- **`USER_AGENT`** is `default.css`, sitting *below* author sheets so a theme always wins at any
  specificity — exactly how a browser's UA sheet behaves.
- **`ANIMATION`** sits *above* `IMPORTANT` because a transition must be able to override an
  `!important` value mid-flight. Matches CSS Cascade L4/5.

### `ElementStyle` — two winner maps, not one

```
candidates: Map<StyleProperty, List<StyleSlot>>   // every value ever set, at any origin
    ↓ computeCandidateSlot  (origin → specificity → source order)
computedSlots  — the DISPLAYED winner, INCLUDING any ANIMATION candidate  → getComputed()
realSlots      — the REAL winner, IGNORING ANIMATION  → what we're settling toward
```

**Why two.** An `ANIMATION` candidate always wins the priority comparison. If the per-pass diff
compared against the *displayed* value, an in-flight transition would always look unchanged (it'd see
its own last tick), silently defeating both mid-flight retargeting and cleanup. So the diff runs
against `realSlots`.

`StyleSlot<T>` is `record(property, origin, specificity, sourceOrder, value)` with full CSS-cascade
`compareTo`. `replaceOrPutCandidate` **no-ops when the pushed value is unchanged** — which is what
makes widget-driven geometry feedback loops settle instead of oscillating.

### Writing styles — the origin pipelines

`StyleGroup` is the fluent write surface, and every write carries an origin. The static pipelines are
the idiom widgets use:

```java
StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> l.height(measured));
StyleGroup.inlinePipeline(group, g -> ...);
StyleGroup.defaultPipeline(group, g -> ...);
StyleGroup.pipeline(origin, group, g -> ...);
```

Two concrete groups:
- **`GeneralGroup`** — visual: `background`, `overlay` (+`-fit`/`-origin`/`-position`), `outline`
  (+per-edge offsets), `opacity`, `color`, `backgroundColor`, `fontSize`, `fontFamily`, `lineHeight`,
  `caretWidth`, `selectionColor`, `textOffsetX/Y`, `transform`, `transformOriginX/Y`, `zIndex`,
  `overflow`, `scrollBehavior`, `scrollDuration`, `mask`.
- **`LayoutGroup`** — ~150-method fluent CSS box-model/flex/grid API, feeding Taffy.

### `StyleProperty<T>`

Carries `name`, `type`, `initialValue`, a `ValueParser`, plus three configuration flags that decide
its cascade behaviour:

| Flag | Meaning |
|---|---|
| `inheritable` | No candidate at any origin → fall back to the **parent's computed value** instead of `initialValue` (e.g. `color`). Anything box-model/layout leaves this false. |
| `allowTransition` | Whether `transition:` may animate it |
| `interpolator` | `IValueInterpolator<T>`; defaults to `BINARY` (snap) |

Properties also carry change listeners — this is how `LayoutProperties.init()` wires every layout
property straight through to `TaffyBridge`.

**Registered CSS properties** (`StylePropertyRegistry`) — the full set, alphabetically, so a missing
entry is visible rather than merely absent: `backdrop-filter`, `background`, `background-color`, `border-bottom-color`,
`border-color`, `border-top-color`, `caret-color`, `caret-width`, `color`, `cursor`, `font-family`, `font-size`,
`font-style`, `font-weight`, `isolation`, `line-height`, `mask`, `mask-size`,
`mask-offset`, `mask-origin`, `mask-position`, `opacity`, `outline`, `outline-color`,
`outline-offset-{top,right,bottom,left}`, `outline-width`, `overflow`, `overlay`, `overlay-size`,
`overlay-origin`, `overlay-position`, `overscroll-behavior`, `paint-order`, `resize`, `scroll-behavior`, `scroll-duration`,
`selection-color`, `stroke-align`, `text-align`, `text-decoration-color`, `text-decoration-line`, `text-offset-x`, `text-offset-y`,
`text-overflow`, `text-shadow`, `text-stroke-color`, `text-stroke-width`, `tooltip-delay`, `transform`, `transform-origin-x`, `transform-origin-y`,
`transition`, `white-space`, `will-change`, `z-index` — plus the whole layout set from `LayoutProperties`.

> **Shorthands are NOT in that list and never will be**, because they are not registered: `DeclarationParser` intercepts each one by name before the registry lookup and emits real longhands, so `StylePropertyRegistry.byName` answers null for them. Today they are `margin`/`padding`/`border-width` (`BoxEdgeShorthands`), `border-radius`, `outline-offset`, `transform-origin`, `outline` (polymorphic — a drawable slot OR width+colour) and `text-stroke` (width+colour). Each must be matched with `equals` rather than a prefix test, since every one of those names is a prefix of its own longhands, and each needs a `transitionNameMatches` entry in `TransitionEngine` or `transition: <shorthand>` animates nothing — `outline` had that method for months and was never wired in.
>
> **One pair goes the other way: `text-stroke-width` and `text-stroke-color` ARE in the list above, and a sheet still may not write them.** `DeclarationParser` refuses any property whose `StyleProperty.getAuthoredThrough()` is set, so `text-stroke` is the only spelling — while `byName` keeps resolving both, which `InlineStyleCodec` requires, since it throws on a name it cannot resolve and would otherwise fail on every serialised tree carrying an outline. They remain two properties because a declaration stating only a colour has to leave the width alone, and a single combined value cannot express a partial override.

> **Renaming one is a DATA migration, not a rename.** `InlineStyleCodec` refuses a document naming a
> property it does not know — `CgCodecException: Unknown style property 'gap-all'` — rather than skipping
> the declaration, which is right for a wire message and means a saved `.cgui` written before the rename
> will not open at all. Sweep every `.cgui` in the same pass, and remember that a workspace's own
> documents live outside `src/`: the scratch document at `gl-debug-harness/crystalgui/projects/` was
> missed exactly that way.

> **This list goes stale silently.** Registering a property is a one-line addition in a 300-line file and
> nothing links the two, so three of the entries above (`text-align`, `white-space`, `text-overflow`) were
> missing for a full release cycle after 5.2 shipped them, and `text-decoration-line` nearly repeated it.
> If you add a property, add it here in the same edit. `grep -oE 'create\("[a-z-]+"' StylePropertyRegistry.java`
> regenerates the set in one command.

### Adding a CSS property

Every property is a **triple**: a `StyleValue` (parse), a `StyleProperty` (identity + cascade
behaviour), and a registry constant. This is why `style/property/**` is ~60 files of near-identical
`Foo`/`FooValue`/`FooProperty` sets — the shape is formulaic, so copy the closest existing family
rather than inventing a new one.

**1. `StyleValue<T>` — the parser.** One method, `doCompute(String) -> T`. Computed **lazily and
exactly once**, then cached; a thrown exception is caught, logged as a warning, and yields `null`
rather than propagating — a malformed declaration degrades, it never breaks the cascade.

```java
public class FloatValue extends StyleValue<Float> {
    public FloatValue(String rawValue) { super(rawValue); }
    @Override protected Float doCompute(String raw) { return Float.parseFloat(raw.trim()); }
}
```

**2. `StyleProperty<T>` — identity and cascade behaviour.** Takes `(name, type, initialValue,
ValueParser<T>)`, where `ValueParser<T>` is just `String -> StyleValue<T>` (so `FloatValue::new`).
Configure with `setInheritable`, `setAllowTransition`, `setInterpolator`, `addListener`.

Prefer an existing **specialized subclass** — it presets those flags correctly:

| Subclass | Adds |
|---|---|
| `FloatProperty` | `min`/`max`/`step`, `setRange`, linear interpolator, `allowTransition` **on** by default |
| `AutoFloatProperty` | as above, plus an `auto` keyword |
| `IntProperty` | integer equivalent |
| `ColorProperty` | ARGB parsing + colour interpolation |
| `EnumProperty` | keyword → enum constant |
| `DimensionProperty`, `LPAProperty`, `LPSizeProperty`, `LPARectProperty` | Taffy-shaped length/percent/auto types. `DimensionValue`/`LPAValue` also accept **`em`** — see `FontRelative` |
| `LengthPercentProperty` | `LengthPercent` (used by offsets, radii, transform origin) |
| `GridProperty`, `GridTemplateProperty`, `GridAutoProperty`, `GridTemplateAreasProperty` | grid types |
| `TextureProperty`, `TransformProperty` | drawable / `Transform` |

**3. Register it** as a `public static final` in `StylePropertyRegistry`, via `create(...)`:

```java
public static final StyleProperty<Float> OPACITY   = create("opacity", 1f).setRange(0f, 1f);
public static final StyleProperty<Integer> COLOR   = create(new ColorProperty("color", -1)).setInheritable(true);
public static final StyleProperty<Overflow> OVERFLOW = create("overflow", Overflow.class, Overflow.VISIBLE);
```

The string here **is** the CSS property name — registering it is what makes it parseable in a
stylesheet. A property parsed but not yet acted on should still be registered, so sheets can declare
it without a warning.

**4. Expose a fluent accessor** on `GeneralGroup` (visual) or `LayoutGroup` (layout) — a getter
returning `getValueSave(PROP)` and a setter calling `set(PROP, v)`.

**5. Layout properties only:** `LayoutProperties.init()` attaches a `TaffyBridge`-calling
`addListener` so the computed value reaches the live Taffy style. Without that step a layout property
cascades correctly and changes nothing on screen.

### `BoxStyle` — the layout seam

**Nothing announces itself any more.** `LayoutProperties.init()` used to attach a listener to every
layout property that carried its computed value into the live Taffy style — that mechanism was the old
cascade's only route into layout, and it went with the old engine. `BoxStyle` reads the whole
`ComputedStyle` on every sync instead, so a layout property arrives by being READ. `createSetter`
survives as a no-op because its sixty call sites still say which Taffy setter a property belongs to.

The two changes that genuinely need telling go through `UINode.computedChanged`: a `font-size` that
moves an `em` under it, and `resize` growing grab handles.

#### Both engines' defaults, and they diverge from CSS deliberately

`BoxStyle` states the project's defaults for anything a sheet leaves unset. This was tried the other
way at M5 — CSS's initials, on the reasoning that the divergences are a standing source of surprise —
and the bill came due at M6.1: in a 6,200-line user-agent sheet nearly every rule leaves the direction
unstated, so flipping it turned every unstated column into a row, and the failure is silent. A menu
came out 166px tall with its rows in the top 43 and nothing errored.

| Property | CrystalGUI default | Real CSS |
|---|---|---|
| `flex-direction` | `COLUMN` | `row` |
| `flex-shrink` | `0` | `1` |
| `box-sizing` | `BORDER_BOX` | `content-box` |
| `align-content` | `FLEX_START` | `stretch` |
| `min-size` | `0` both axes | `auto` |

`border-box` is a project choice matching the common UI-framework convention (Bootstrap et al.) where
a declared width already includes padding+border. It happens to match Taffy's own default too, but is
assigned explicitly so it stays self-documented rather than at the mercy of a future Taffy version.

### Selectors

`style/selector/` — `Selector`, `CompoundSelector`, `SelectorType` with real CSS specificity weights:
`UNIVERSAL(0)`, `TYPE(1)`, `PSEUDO_ELEMENT(1)`, `CLASS(10)`, `PSEUDO_CLASS(10)`, `ID(100)`. Descendant
and child combinators are supported.

**Two pseudo-elements exist, and they are not the same kind of thing.** `::part(name)` selects a
**real element** inside a shadow tree and contributes to that element's own cascade;
`::highlight(name)` selects a paint-time overlay on the originating element and is collected into a
side table that never touches any element's cascade. `CompoundSelector.selectsShadowPart()` is the
discriminator, and conflating the two is the one way to get this badly wrong — a `::part` rule routed
down the highlight path silently styles nothing, and a `::highlight` rule routed down the part path
paints the whole paragraph. `::part` arrived with spike S2 (`plan/engine-rewrite.md` M0); see
`ui/shadow/`.

**`::highlight(name)`** — the CSS Custom Highlight API, for styling text
ranges without wrapping them in elements. It never matches the originating element (that is what
`matchesOriginating` is for), and `StyleEngine` cascades it into a `HighlightStyle` kept apart from
`ElementStyle`. `::before`/`::after` are rejected at parse time — shadow parts are the substitute.

**Not supported:** `:nth-child`, attribute selectors, `~`/`+` sibling combinators, `@media`, `@import`. Adding any of the first three means extending `StyleEngine.ChainKey`, which style sharing reads to decide that two elements match alike.

`PseudoClasses` — `ENABLED`, `DISABLED`, `CHECKED`, `BLANK`, `INVALID`, `HOVER`, `ACTIVE`, `FOCUS` —
each bound to a real `UINode` getter. **A widget gets a pseudo-class for free by overriding the
getter**; `Tab.isChecked()` is the whole implementation of `tab:checked`.

### Stylesheets

- `StyleSheet.parse(String)` — inline CSS text.
- `StyleSheetRegistry.of("crystalgui:ore")` — loads `assets/{ns}/ui/styles/{path}.css`, lazily parsed
  and `ConcurrentHashMap`-cached, so repeated calls return the same instance.
- `DeclarationParser` — declaration-level parsing including `var(--x)` custom-property substitution.
- `StyleSheet.DEFAULT` — the user-agent sheet (see the headless trap above). **Not applied
  automatically** — `window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT)` is a real call a caller
  has to make. A test that asserts on `default.css` behaviour without it silently exercises no CSS and
  passes for the wrong reason.

### `StyleEngine` — the per-window driver

A flat ordered sheet list (`addStylesheet`/`removeStylesheet`/`getSheets` — there is **no**
`clearStylesheets`), a dirty-match set, and `calculateStyle(delta)` which re-matches, cascades, and
ticks transitions.

> Sheet order is registration order, and re-adding a sheet **appends** it — i.e. at the *highest*
> priority. This matters for any runtime theme switch.

### Transitions

`transition: <prop> <dur> <easing>` on any property with `allowTransition`. `TransitionEngine` writes
at `ANIMATION` origin via `startAnimationSlot`/`tickAnimationSlot`/`endAnimationSlot`. Easings are CrystalGraphics'
shared stack, `com.crystalgraphics.easing`: `CgEasing`, the library `CgEasings` (Penner's families, CSS's keywords,
`cubicBezier`, `linear`, `bake`), `CgCubicBezier`, `CgPiecewiseLinear`, and `CgKeyframes` for eased keyframes.

### `transform`

`Transform` is an **ordered list of ops**, not translate/scale/rotate fields — because CSS composes
left-to-right as matrix multiplication, so `translate(10px) scale(2)` ≠ `scale(2) translate(10px)`,
and a field-per-function decomposition cannot represent the difference at all.

- Immutable; build from `IDENTITY` + `then(...)`, or the `translate`/`scale`/`rotate` shorthands.
- `isIdentity()` is the fast path every caller checks first, so an untransformed tree pays nothing.
- **Layout-free by construction** — Taffy never sees it, so transforming an element cannot reflow its
  siblings. That's what makes a zoomable canvas possible (scale one container, not the window).
- Origin is **not** stored here — `transform-origin-x`/`-y` are separate cascading properties, so they
  theme and transition independently. `applyTo(...)` takes the origin already resolved to pixels.
- `applyTo` is the **single definition** used by both the hit-test transform chain and the render
  `PoseStack`. They must produce an identical matrix or clicks land somewhere other than what the user
  sees.
- Divergences: `matrix()` unsupported; axis variants (`translateX`) **collapse** into their
  two-argument form at parse time, so they interpolate against each other instead of snapping.
