# CrystalGUI — Agent Knowledge Base

**Project type**: Platform-agnostic retained-mode UI engine, shaped like a lightweight web browser
(DOM + CSS cascade + Taffy layout + immediate-mode painting).
**Authored in**: Java 21 (Jabel-desugared toward Java 8 bytecode) · **Layout**: Taffy · **Backend**: CrystalGraphics
**Targets**: MC 1.7.10 (Forge/LWJGL2) · MC 1.20.1/1.20.4 (Forge/NeoForge/Fabric, LWJGL3) — *neither is in the build today; see [Module layout](#module-layout--what-actually-compiles)*

---

# ⚠️ AGENT EXECUTION RULES — READ BEFORE ANYTHING ELSE

**These rules apply to ALL agents operating in this repository, including subagents.**

## Required reading

Read the files relevant to your task scope **before** doing any work.

| When | Read |
|---|---|
| Always | `CrystalGraphics/docs/CRYSTALSHADER_MANIFESTO.md` — grand goal, rendering philosophy |
| Touching style, CSS, painting, drawables, compositing | `docs/CGUI_STYLE_RENDER_PIPELINE.md` |
| Writing or modifying a widget | `docs/CGUI_WIDGETS.md` |
| Touching `serialization/` or `net/` | `docs/CGUI_SERVER_AND_SERIALIZATION.md` |
| Touching any rendering/buffer/shader/VAO/mesh code | `CrystalGraphics/AGENTS.md` |
| Working inside a package | any `AGENTS.md` found in that package (none exist under `core/` today) |

## NO RE-DELEGATION

**Subagents MUST NOT delegate their assigned work to another agent.**

When you are assigned a task — by an orchestrator, a plan, or a user — you execute it yourself with
the tools you have (Read, Edit, Write, Bash, Glob, Grep). You do not spawn a child agent, fire a
background task, or hand off via `task()`.

**Absolute prohibition. No exceptions.** Complexity is not a reason to re-delegate. The only
cross-agent tool use permitted is asking the orchestrator a clarifying question, inline.

**If you are a subagent and find yourself writing a `task()` call: STOP. Do the work yourself.**

---

# THE GRAND GOAL

> Every line of code in this repository serves one end: a **node-based shader graph for Minecraft**
> (cross-version 1.7.10 and 1.20.1) — like Unity's Shader Graph, but true to GLSL, on a modern GL 3.x+
> pipeline, with instancing as the default draw path.
>
> CrystalGUI is the UI engine that graph will be built in.
>
> 📄 **[CrystalShader Manifesto](CrystalGraphics/docs/CRYSTALSHADER_MANIFESTO.md)**

---

# TO BUILD

```bash
./gradlew :core:compileJava       # the engine — enforces the MC/Forge/LWJGL import guard
./gradlew :core:test              # unit tests, CrystalGraphics ON the classpath
./gradlew :core:headlessTest      # server-side tests, CrystalGraphics DELIBERATELY absent
./gradlew :core:check             # both test tasks
```

There is **no in-game Minecraft integration reachable from this build.** The two things you can
compile and run end-to-end today are `core/` and the harness. Do not claim otherwise.

## Render testing — the GL debug harness

For anything visual, **do not test via Minecraft** — it isn't wired up. Use the harness: it boots in
seconds, needs no Minecraft context, and gives you a real GL surface.

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery"   # start here
./gradlew :gl-debug-harness:runHarness --args="--list"                # all scenes
```

| Mode | Scene class | Covers |
|---|---|---|
| `cgui-gallery` | `CgUiGalleryScene` | Every widget at once — the default smoke test |
| `cgui-test` | `CgUiTestScene` | General engine scratch scene |
| `cgui-button` | `CgUiButtonScene` | `Button`, activation semantics |
| `cgui-checkbox` | `CgUiCheckboxScene` | `Checkbox`, `CheckboxGroup` |
| `cgui-switch` | `CgUiSwitchScene` | `Switch` (CSS-driven knob transition) |
| `cgui-slider` | `CgUiSliderScene` | `Slider`, drag |
| `cgui-textfield` | `CgUiTextFieldScene` | `TextField`, caret, selection |
| `cgui-text` | `CgUiTextScene` | `UIText` wrapping/measurement |
| `cgui-text-stress` | `CgUiTextStressScene` | Many text nodes — shaping/layout cost |
| `cgui-scroller` | `CgUiScrollerScene` | `Scroller`, `ScrollerView`, overflow |
| `cgui-splitview` | `CgUiSplitViewScene` | `SplitView`, divider drag |
| `cgui-tabview` | `CgUiTabViewScene` | `TabView`, `Tab` |
| `cgui-styling` | `CgUiStylingScene` | Cascade, selectors, transitions |
| `cgui-nineslice` | `CgUiNineSliceScene` | `CgUiSprite` 9-slice |
| `cgui-ore-theme` | `CgUiOreThemeScene` | `ore.css` + sprite registry end-to-end |
| `cgui-visual-layers` | `CgUiVisualLayersScene` | FBO layer opacity + masking |

Harness scenes live in `gl-debug-harness/src/main/java/.../harness/scene/ui/`; register new ones in
`SceneRegistry`. Harness authoring rules are in `gl-debug-harness/AGENTS.md` — never call raw GL.

---

# Start Here By Task

| I need to… | Section | Deep reference |
|---|---|---|
| Add or change a widget | [Widgets](#widgets) | `docs/CGUI_WIDGETS.md` |
| Add a CSS property | [Adding a CSS property](#adding-a-css-property) | `docs/CGUI_STYLE_RENDER_PIPELINE.md` |
| Change how something paints | [Render stack](#stack-4-render--immediate-mode) | `docs/CGUI_STYLE_RENDER_PIPELINE.md` §5–§8 |
| Work on layout / Taffy | [Style stack](#taffybridge--the-layout-seam) | — |
| Work on events, focus, hover, drag | [Input stack](#stack-3-events-input-focus) | — |
| Serialize a tree / send UI over a wire | [Server layer](#server-layer--serialization--net) | `docs/CGUI_SERVER_AND_SERIALIZATION.md` |
| Understand a frame | [Frame lifecycle](#frame-lifecycle) | — |
| Debug "my selector doesn't match" | [Load-bearing invariants](#load-bearing-invariants) | — |
| Debug "my layout is wrong by default" | [Taffy default divergences](#taffy-defaults-diverge-from-css-deliberately) | — |
| Add a rendering backend capability | [CrystalGraphics boundary](#crystalgraphics-ownership-boundary) | `CrystalGraphics/AGENTS.md` |

---

# Module layout — what actually compiles

`settings.gradle.kts` includes **only** `core` and `gl-debug-harness`. CrystalGraphics is an
`includeBuild` composite with three `dependencySubstitution` entries, which is how the
`compileOnly("com.crystalgraphics:core:1.0.0")` coordinates resolve to local source.

| Module | In build? | State |
|---|---|---|
| `core/` | ✅ | The engine. Java 21 → Java 8 bytecode. Everything below lives here. |
| `gl-debug-harness/` | ✅ | Git submodule (branch `crystalgui`). 16 CrystalGUI scenes. The only way to run the UI. |
| `CrystalGraphics/` | ✅ (composite) | The rendering backend. Consumed, never reimplemented. |
| `mc1710/` | ❌ commented out | Bare `@Mod` stub. No CrystalGUI integration at all. Scaffolding. |
| `mc1201/` | ❌ commented out | Has *real* code (`CgPlatformService1201`, per-loader entrypoints, event bridges, mixins) but does not compile from this build. |

`core/build.gradle.kts` runs an **import guard** as a `doLast` on `compileJava`: any source line
importing `net.minecraft.*`, `cpw.mods.fml.*`, `net.minecraftforge.*`, or `org.lwjgl.*` fails the
build. There are currently **no exemptions** — the guard is clean.

## Three test source sets, and they are not interchangeable

| Source set | CrystalGraphics on classpath? | What belongs there |
|---|---|---|
| `core/src/test/` | ✅ `testImplementation` | Anything needing `CgIO`, fonts, `StyleSheet`, sprites, drawables |
| `core/src/headlessTest/` | ❌ **deliberately absent** | Everything a dedicated server must run: `serialization/`, `net/`, tree/state logic |
| harness scenes | ✅ full GL | Anything visual |

**The absence is the assertion.** On a dedicated Minecraft server there is no GL context, no fonts,
and no CrystalGraphics jar. Anything in `core/` that reaches a CG type *outside a paint-method body*
fails in `headlessTest` with `NoClassDefFoundError` rather than in production.

JOML and Taffy **must stay** on the headless classpath: `UIElement` and `ElementStyle` have *fields*
of those types (`Matrix4f`, `NodeId`, `TaffyStyle`), and field descriptors resolve at class load —
unlike method-body references, which don't. Someone will eventually try to strip them; don't.

> **The trap, found the hard way:** `StyleSheet.DEFAULT` is a `static final` that reads `default.css`
> through `CgIO` at class-init, so the entire `StyleSheet` class is unloadable headlessly — even
> `StyleSheet.parse()`. **If a test needs CSS text, it belongs in `test`, not `headlessTest`.**

---

# Frame lifecycle

```
UIWindow.paintFrame():
  advanceFrame():
    styleEngine.calculateStyle(delta)     // 1. drain dirty-match, re-run selectors, cascade, tick transitions
    tickAnimations(delta)                 // 2. smooth scrolls + every registered UIFrameTicker
    calculateLayout()                     // 3. taffyTree.computeLayout() while dirty; fires onLayoutChanged
  CgUiPaintContext.getInstance()
  paintContext.beginFrame(actualScreenW, actualScreenH)   // GL save, ortho, bind gui_quad, reset scissor
    pose.pushPose(); pose.mulPoseMatrix(rootTransform)     // rootTransform = the ONE definition of uiScale
    ui.rootElement.drawSubtree(paintContext)               // recursive, immediate, painter's order
    pose.popPose()
  paintContext.endFrame()                 // GL restore
  inputHandler.beginFrame()               // invalidate hover cache
  inputHandler.endFrame()                 // hover diff + dispatch of accumulated mouse events
```

**`calculateStyle` running first is load-bearing.** A style change this frame must reach Taffy
*before* layout, or it lands one frame late. `drainDirtyMatch` only runs inside `calculateStyle`, so
a window that is never painted never matches any selector.

**`updateWithoutPainting()`** runs `advanceFrame()` alone — style + animations + layout, no GL, no
draw, and deliberately no input handling (no frame was presented, so hover has nothing to be relative
to). Use it for headless tests and for benchmarks that need to isolate layout/shaping cost from
render cost, since per-element material binds at draw time can dwarf everything else.

---

# Stack 1: DOM — `ui/`

## There is no `UIContainer`

`UIElement` is both leaf and container, exactly like a real DOM `Element` — "a general-purpose,
styleable, extensible container, conceptually like an HTML `<div>`". It owns:

| Concern | Surface |
|---|---|
| Tree | `addChild`/`addChildAt`/`addChildren`/`removeChild`/`removeSelf`/`clearAllChildren`/`hasChild`/`getSiblingIndex` |
| Identity | `setId`, `addClass`/`removeClass`/`hasClass`, `tagName()` (via `ElementRegistry`) |
| State flags | `setEnabled`/`setPressed`/`setFocused`/`setHovered`; overridable `isChecked`/`isBlank`/`isInvalid`/`consumesTextInput` |
| Focus | `setFocusPolicy`, `focusable()`, `invalidateFocusableChain()` |
| Hit testing | `setHitTest`, `containsScreenPoint`, `screenToLocal` |
| Transform | `getTransform`/`setTransform` (`UITransform`), `invalidatePoseCachesRecursively()` |
| Scrolling | `setScrollTop`/`setScrollLeft`/`setScroll`/`setScrollImmediate`/`scrollIntoView`/`clampScroll`/`getMaxScroll*`/`getClientWidth`/`getScrollWidth`/`setScrollExempt` |
| Querying | `querySelector`/`querySelectorAll`/`getElementById`/`getElementsByClassName` — same selector engine the stylesheets use |
| Internal children | `markAsInternal`/`addInternalChild`/`insertInternalChildAt`/`removeInternalChild`/`acceptsPublicChildren` |
| Styling | one owned `ElementStyle`; `style(...)`, `layout(...)`, `generalStyle(...)`, `moveInlineAsDefault()` |
| Events | pre-bound `EventListenerGroup<T>` fields (`onMouseDown`/`onMouseUp`/`onMouseMove`/`onMouseEnter`/`onMouseLeave`/`onMouseScroll`/`onFocus`/`onBlur`) |
| Serialization | `writeState`/`readState` (protected) + `writeStateTo`/`readStateFrom` (final), `networkId`, `addReportedEvent`, `setObserver` |
| Painting | `drawSubtree` is **final**; override `paintSelf` / `paintOverlay` / `paintOutline` |
| Layout hooks | `onLayoutChanged()`, `getTaffyLayout()`, `measureFunc()`, `markTreeDirty()`, `clearLayoutCache()` |

**Scrolling is an ordinary element capability** driven by `overflow`, not a widget feature.
`ScrollerView` only adds visible bars on top of it.

## `RuntimeCache` — five dirty-flag memo cells

Backed by `core/data/CacheCell` / `IntCacheCell`:

| Cell | Holds |
|---|---|
| `sortedChildren` | Children in paint order, z-index descending |
| `localToWorld` | This element's world matrix (falls back to `UIWindow.getRootTransform()` at the root) |
| `worldToLocal` | The inverse — `localToWorld.get().invert(old)` |
| `depth` | Tree depth |
| `hasFocusableDescendant` | Tab-order pruning |

> `sortedChildren` orders **equal z-index later-inserted-first**. Painting walks it in reverse
> (lowest z first) so the highest-z child ends up visually on top, matching which child hit-testing
> prioritizes — these two used to disagree, which is why the order is spelled out here.

## `UIWindow` — the runtime engine

Owns the live Taffy tree, a `StyleEngine`, a `UIInputHandler`, screen/layout dimensions, the
scroll-animation set, and the `UIFrameTicker` set. It **does not own a paint context** — that is a
singleton (see [Render stack](#stack-4-render--immediate-mode)).

- `init(w, h)` attaches the root element — **required.** `invalidateStyleMatch()` early-returns on a
  detached element, so without `init` no selector ever matches anything.
- `registerElement`/`unregisterElement` create and destroy the Taffy node (and install a
  `measureFunc()` if the element supplies one).
- `getRootTransform()` is the **single definition of what `uiScale` means** — `paintFrame` seeds the
  `PoseStack` from it and `RuntimeCache.localToWorld` falls back to it, so hit-testing is correct
  *before* the first paint. The scale lives here and in the `PoseStack`, deliberately **not** in the
  ortho projection: `CgTextRenderer` picks glyph raster size from the pose scale, so moving it would
  rasterize glyphs at logical size and let the projection magnify them — blurry text.
- `setUiScale(f)` rescales and invalidates every cached transform.
- `getHoveredElement(x, y)` is the z-order- and clip-aware hit test.

`UIWindow` deliberately implements **no** platform Screen/widget interface — loader modules own that.

## `Ui`

Trivial immutable `{ rootElement }` holder (`Ui.of(root)` / `Ui.of()`). No runtime, layout, or GL
state. The declarative description layer it seeded now lives in `serialization/UIDescriptionCodec`.

## `ElementRegistry`

Bidirectional `tag ↔ class` map with a factory per tag; `bootstrapBuiltins()` registers fourteen:
`element`, `button`, `checkbox`, `scroller`, `scrollerview`, `slider`, `splitview`, `switch`, `tab`,
`tabview`, `textfield`, `text`, `tooltip`, `dialog`. Unknown tags **throw** on decode — a typo must not silently become a
styleless div.

## `UITreeObserver`

Four callbacks — `onAttached`, `onDetached`, `onStateDirty`, `onIdentityDirty` — covering the only
things a networked peer cannot reconstruct: tree shape, element identity, and authored widget state.

Deliberately **not** hooked to `onStyleChanged()`: that fires on every transition tick and every
`IMPORTANT`-origin write a widget makes about itself, so the dirty set would be pure churn — and what
it reports is *computed styles*, which a session never sends. `onStateDirty` carries no value; state
is re-read at flush time so ten mutations in a tick collapse to one entry. Cost when nobody is
watching: one nullable field and one null check per mutation.

---

# Stack 2: Style — the cascade

> **Full reference: `docs/CGUI_STYLE_RENDER_PIPELINE.md`.** This is the map, not the territory.
> 86 files — the largest stack in the engine.

## Origins

`StyleOrigin` is priority-ordered: `DEFAULT(0) < USER_AGENT(1) < STYLESHEET(2) < INLINE(3) <
IMPORTANT(4) < ANIMATION(5)`. Two are easy to get backwards and both are deliberate:

- **`USER_AGENT`** is `default.css`, sitting *below* author sheets so a theme always wins at any
  specificity — exactly how a browser's UA sheet behaves.
- **`ANIMATION`** sits *above* `IMPORTANT` because a transition must be able to override an
  `!important` value mid-flight. Matches CSS Cascade L4/5.

## `ElementStyle` — two winner maps, not one

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

## Writing styles — the origin pipelines

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

## `StyleProperty<T>`

Carries `name`, `type`, `initialValue`, a `ValueParser`, plus three configuration flags that decide
its cascade behaviour:

| Flag | Meaning |
|---|---|
| `inheritable` | No candidate at any origin → fall back to the **parent's computed value** instead of `initialValue` (e.g. `color`). Anything box-model/layout leaves this false. |
| `allowTransition` | Whether `transition:` may animate it |
| `interpolator` | `IValueInterpolator<T>`; defaults to `BINARY` (snap) |

Properties also carry change listeners — this is how `LayoutProperties.init()` wires every layout
property straight through to `TaffyBridge`.

**Registered CSS properties** (`StylePropertyRegistry`): `background`, `background-color`,
`border-color`, `caret-width`, `color`, `font-family`, `font-size`, `line-height`, `mask`,
`mask-fit`, `mask-offset`, `mask-origin`, `mask-position`, `opacity`, `outline`, `outline-color`,
`outline-offset-{top,right,bottom,left}`, `outline-width`, `overflow`, `overlay`, `overlay-fit`,
`overlay-origin`, `overlay-position`, `resize`, `scroll-behavior`, `scroll-duration`, `selection-color`,
`text-offset-x`, `text-offset-y`, `text-shadow`, `transform`, `transform-origin-x`,
`transform-origin-y`, `transition`, `z-index` — plus the whole layout set from `LayoutProperties`.

## Adding a CSS property

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
| `DimensionProperty`, `LPAProperty`, `LPSizeProperty`, `LPARectProperty` | Taffy-shaped length/percent/auto types |
| `LengthPercentProperty` | `LengthPercent` (used by offsets, radii, transform origin) |
| `GridProperty`, `GridTemplateProperty`, `GridAutoProperty`, `GridTemplateAreasProperty` | grid types |
| `TextureProperty`, `TransformProperty` | drawable / `UITransform` |

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

## `TaffyBridge` — the layout seam

Mutates the live Taffy `TaffyStyle` and marks the tree dirty. `LayoutProperties.init()` attaches a
`TaffyBridge`-calling listener to every layout `StyleProperty`, so `LayoutGroup.width(100)` flows
straight into the live layout with no intermediate step.

### Taffy defaults diverge from CSS, deliberately

`TaffyBridge.DEFAULT_TAFFY_STYLE` is **not** the CSS initial value set. Expect these:

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

## Selectors

`style/selector/` — `Selector`, `CompoundSelector`, `SelectorType` with real CSS specificity weights:
`UNIVERSAL(0)`, `TYPE(1)`, `CLASS(10)`, `PSEUDO_CLASS(10)`, `ID(100)`. Descendant and child
combinators are supported.

**Not supported:** `:nth-child`, attribute selectors, `~`/`+` sibling combinators, `@media`, `@import`.

`PseudoClasses` — `ENABLED`, `DISABLED`, `CHECKED`, `BLANK`, `INVALID`, `HOVER`, `ACTIVE`, `FOCUS` —
each bound to a real `UIElement` getter. **A widget gets a pseudo-class for free by overriding the
getter**; `Tab.isChecked()` is the whole implementation of `tab:checked`.

## Stylesheets

- `StyleSheet.parse(String)` — inline CSS text.
- `StyleSheetRegistry.of("crystalgui:ore")` — loads `assets/{ns}/ui/styles/{path}.css`, lazily parsed
  and `ConcurrentHashMap`-cached, so repeated calls return the same instance.
- `DeclarationParser` — declaration-level parsing including `var(--x)` custom-property substitution.
- `StyleSheet.DEFAULT` — the user-agent sheet (see the headless trap above).

## `StyleEngine` — the per-window driver

A flat ordered sheet list (`addStylesheet`/`removeStylesheet`/`getSheets` — there is **no**
`clearStylesheets`), a dirty-match set, and `calculateStyle(delta)` which re-matches, cascades, and
ticks transitions.

> Sheet order is registration order, and re-adding a sheet **appends** it — i.e. at the *highest*
> priority. This matters for any runtime theme switch.

## Transitions

`transition: <prop> <dur> <easing>` on any property with `allowTransition`. `TransitionEngine` writes
at `ANIMATION` origin via `startAnimationSlot`/`tickAnimationSlot`/`endAnimationSlot`. Easings:
`Linear`, `CubicBezier`, `LinearPiecewise`, `ConstantEasing`, plus `ProgressFunctions`.

## `transform`

`UITransform` is an **ordered list of ops**, not translate/scale/rotate fields — because CSS composes
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

---

# Stack 3: Events, input, focus

## Three-phase dispatch

`ui/event/` — `UIEvent` (base: `target`, `bubbles`, `phase`, `stopPropagation` /
`stopPhasePropagation` / `preventDefault`), `PropagationPhase` (`CAPTURE`/`TARGET`/`BUBBLE`), and the
concrete types:

| Type | Bubbles? |
|---|---|
| `DOMEvent` — `ElementAdded`, `ElementRemoved` | no |
| `FocusEvent` — `Focus`, `Blur` | yes |
| `KeyboardEvent` — `Down`, `Up` | yes |
| `MouseEvent` — `Click`→`Down`/`Up`, `Scroll`, `Move` | yes |
| `MouseEvent` — `Enter`, `Leave` | no — but see below |
| `DragEvent` — `Enter`, `Leave` | no — chain-dispatched, like the mouse pair |
| `DragEvent` — `Over`, `Drop` | yes |
| `DragEvent` — `Cancel` | no — goes to the drag source |

> **`Enter`/`Leave` don't bubble, yet one is dispatched to *every* element in the entered/left chain**
> — outermost-first on entry, innermost-first on exit, exactly as the DOM does. Firing only on the
> precise hit target means a container with children never hears about the pointer at all: hovering a
> row's own label left the row with nothing, and only the bare gaps between children worked. The
> `:hover` pseudo-class always walked this chain, so the two used to disagree about what "hovered"
> meant.

`EventListenerGroup<T>` bundles four signals per (element, event-type): `capture`, `target`, `bubble`,
and `defaultEvents` for built-in behavior. `defaultEvents` fires only in the TARGET phase and only if
`!isDefaultPrevented()` — that is what `preventDefault()` actually suppresses.

> **`attachListener(l, capture, bubble)` always subscribes the target phase.** The two booleans are
> *additive*, not a mode selector.

## `UIInputHandler`

One class, merging what older notes split into "input manager" + "focus manager". It implements
`SystemInput.Mouse` and `SystemInput.Keyboard` directly as the raw-event sink, and owns:

- **The three-phase walk** (`sendInputEvent`): build `UITreeTraversal.pathToRoot`, walk root→target
  for CAPTURE, fire once for TARGET, walk target→root for BUBBLE if `bubbles`.
- **Hover**, via a `CacheCell<UIElement>` diffed once per frame inside `endFrame()`.
- **Focus** (`requestFocus`, `blurIfFocused`, `getFocusedElement`) and Tab traversal via
  `UITreeTraversal.{firstFocusableIn, lastFocusableIn, previousFocusable, nextFocusable}`.
- **Press/click state** per button (`ButtonState`, multi-click `detail` counting), and
  `MouseEvent.Up.isWasPressTarget()`.
- **Pointer capture** (`setPointerCapture`/`releasePointerCapture`) — Pointer Events L3. While
  captured, hit testing is *substituted*: every pointer event targets the capturing element "as if the
  pointer is always over" it. Boundary events fall out of that for free — the hover diff sees no
  change, so nothing enters or leaves and `:hover` stays pinned. Released implicitly on button-up,
  *after* the up is delivered, which is what lets a drag end anywhere on screen.
- **Drag**, via `UIDragController` — capture plus an optional payload, drop targeting, an activation
  threshold, a ghost, and an Escape cancel path. **Not HTML5 drag-and-drop**; the web moved to
  pointer events, so this does too.
  - Drop targets get `DragEvent` `Enter`/`Leave`/`Over`/`Drop`/`Cancel`, dispatched to what is
    *geometrically* under the pointer — which is why `UIWindow.getHoveredElement` is deliberately
    left free of capture substitution. Conflating the two makes every drop land on the dragged thing.
  - **Rejection is the default**: a target accepts by calling `preventDefault()` on `DragOver`,
    re-read every frame and never latched. HTML5 DnD's one good idea, kept.
- **Keyboard activation** — Space/Enter on a focused element synthesizes the same `MouseEvent.Down`/
  `Up` a real click would. This is why `Button` contains **zero** keyboard code.

### The accumulate-then-dispatch model

`consumeMouseEvent`/`consumeKeyboardEvent` accumulate raw input during the frame. Click, focus and
keyboard events dispatch **immediately**; hover/enter/leave/move/scroll are **synthesized once per
frame** from `paintFrame()`'s `beginFrame()`/`endFrame()` pair.

> `beginFrame()` **only** invalidates the hover cache — forcing a fresh hit-test against this frame's
> layout, so a reflow under a stationary cursor doesn't leave hover stale until the next real mouse
> move. It must **not** also read or snapshot that cache: mouse-move events already invalidated it
> before `beginFrame()` ran, so reading there is really an eager recompute against the *new* position
> mislabeled as the *old* one. That was the original stuck-hover bug. The "last frame's hover"
> baseline is a plain field (`lastFrameHover`) snapshotted at the end of `fireAccumulatedMouseEvents()`.

`FocusPolicy` is `NONE` / `FOCUSABLE` / `CLICK`.

---

# GL context lifecycle — `lifecycle/`

CrystalGUI owns GL resources and caches of GL-derived objects, but is not part of CrystalGraphics and
so cannot be enumerated in `CgGraphicsLifecycle`'s own teardown. The seam is
`CgLifecycleListener` (CrystalGraphics, `gl/lifecycle/`) — `onInit(w,h)` / `onFrame(frame)` /
`onDestroy()`, all default no-ops, registered via `CgGraphicsLifecycle.addListener(...)`.

**CrystalGUI registers exactly one**: `CgUiLifecycle`.

| Moment | What CrystalGUI does |
|---|---|
| `onInit` | Nothing — every GL resource is lazily built on first paint, and forcing them here would defeat `CgUiPaintContext`'s deliberate laziness. It *does* fire though (see below), so work added here will run |
| `onFrame` | Nothing — per-frame work is per-`UIWindow` (`paintFrame`, `UIFrameTicker`), not global |
| `onDestroy` | `CgUiPaintContext.destroy()`, and nothing else |

> **`onInit` reaches late registrants.** CrystalGUI registers from a class initializer on the *first
> paint*, which is always after `CgGraphicsLifecycle.initContext()` has run — so a fire-only-during-init
> design would mean `onInit` never fires for CrystalGUI at all. `addListener` therefore delivers
> `onInit` immediately when a context is already live. The guarantee is **exactly once per context,
> regardless of registration time**. Without it the miss would be invisible while the hook is empty
> and appear as silently-skipped setup the moment it isn't.

> **`destroyContext()` fires only at game shutdown.** There is no destroy-then-init cycle in a running
> process, so there is no "next context" to protect, and **nothing needs invalidating merely because
> CrystalGraphics is about to free it** — font families, cached stylesheets, sprite packs and shared
> materials all die with the process. Do not add cache-clearing or material-invalidation to
> `onDestroy`; it is ceremony, not correctness.

What `onDestroy` legitimately does is release the one thing nobody else frees: `CgUiPaintContext`'s
layer FBO pool is built with `CgFrameBuffer.createOwned`, which bypasses `CgFrameBufferRegistry`, so
`deleteAll()` never reaches it.

**`onDestroy` fires before CrystalGraphics frees anything** — the only window in which a listener can
release its own FBOs/renderers while the context is whole. Listeners dispatch in *reverse*
registration order.

> **Registration is automatic** — a `static` initializer in `CgUiPaintContext` calls
> `CgUiLifecycle.register()`, so CrystalGUI wires itself as soon as that class comes into play. Class
> init runs once per classloader, so registration cannot repeat across a destroy/recreate cycle. A
> process that never paints never touches the class, which keeps a dedicated server free of
> CrystalGraphics. `register()` is idempotent and public for explicit use.

---

# Stack 4: Render — immediate-mode

**The V3.1 draw-list design is gone.** `CgUiDrawList`, `CgUiDrawListExecutor`, `CgUiDrawState`,
`CgUiBatchSlots`, and `CgScissorRect` do not exist. Do not reference them.

## `CgUiPaintContext` — a singleton

Obtained via `CgUiPaintContext.getInstance()`, **not** owned per-`UIWindow`. Every `fillRect`/
`drawImage` call draws immediately; there is no recording phase and nothing to flush.

| Group | Methods |
|---|---|
| Frame | `beginFrame(w,h)` / `endFrame()` — save/restore GL via `CgGlScope`, ortho projection, bind `crystalgui:shaders/gui_quad.shader`, reset `ScissorStack` |
| Draw | `fillRect`, `drawImage`, `quad()` + `flush`, `bindTexture` (elides redundant rebinds), `text()` → a `CgTextRenderer` wired to this context's `PoseStack` |

> `quad()` returns `CgQuadRenderer.Quad` — `ctx.quad().at(x,y).size(w,h).uv(...).color(argb).submit()`,
> then `flush()` to draw (`submit()` only queues). **Never call `.pose(...)` on it**: `CgUiRenderer.quad()`
> is the single place the `PoseStack` is applied, and overwriting it silently drops `uiScale` and the
> element transform. It's re-applied per call because `CgQuadRenderer.quad()` resets the scratch
> instance's pose to null. The returned object is that shared scratch — build and `submit()` in one
> expression, never hold it.
| Clip | `pushScissor` / `popScissor` |
| Material | `withMaterial(material, body)` |
| Layers | `withLayerOpacity(opacity, body)`, `beginLayerFbo()` / `endLayerFbo()`, `blitLayer(fbo, opacity)`, `compositeMask(subtreeFbo, maskFbo)` |
| Lifecycle | `hasInstance()`, `destroy()` |

> **`destroy()` must be called on GL-context destruction.** The instance is `static`, so it outlives
> the context it was built against; without this the next context is handed an object whose material,
> VAO/VBO and FBO handles are all dead — which draws nothing or draws garbage rather than failing
> loudly. It frees only what the context genuinely owns (the layer FBO pool, the `CgUiRenderer`, the
> `CgTextRenderer`) and deliberately not what it borrows from CrystalGraphics registries (materials,
> the fallback white pixel, font atlases), since those are swept by `CgGraphicsLifecycle.destroyContext()`
> and freeing them here would be a double free. Use `hasInstance()` to check without *causing*
> construction.

> **Opacity isolation and masking go through an FBO layer pass, not a flat multiply.** The
> tint-vs-layer-opacity distinction is the thing most likely to be got wrong here — read
> `docs/CGUI_STYLE_RENDER_PIPELINE.md` §5 and §8 before touching it.

## Supporting classes

- **`CgUiRenderer`** — thin wrapper over CrystalGraphics' `CgQuadRenderer`: instanced unit quads whose
  per-instance record (`origin` + `right`/`up` edge vectors, UVs, colour) lives in a class-wide
  SSBO/TBO. The `PoseStack` matrix is baked in at `submit()` time by `Quad.pose(...)` — three
  transforms per quad rather than four corners, and affine-correct under `transform:`. **Material
  bind/unbind is owned by `CgQuadRenderer.useMaterial()`**, which must be called before any `submit()`
  and again every frame; never call `material.bind()` yourself. Text goes through the same renderer
  (CrystalGraphics' `CgTextRenderer` owns its own `CgQuadRenderer` instance).
- **`ScissorStack`** — allocation-free nested clip stack (`int[64]`, 16 levels × 4 ints), applied via
  CrystalGraphics' `CgGL` facade. **No LWJGL imports** — the old "V3.x legacy, raw GL11, scheduled for
  deletion" note is obsolete.
- **`FontFamilyCache`** — `(font-family stack, target px)` → `CgFontFamily`, cached. Reference
  equality on the result is therefore meaningful and is relied on by `UIText`.

## Drawables — `render/texture/`

`CgUiDrawable` is the pluggable "paint yourself into a rect" SPI:
`draw(ctx, mouseX, mouseY, x, y, w, h)`, plus `intrinsicWidth()`/`intrinsicHeight()` returning `-1`
when the drawable has no inherent size (solid colours, SDF shapes). One `draw` call is expected to
issue exactly one GPU draw call, or zero for a fully transparent tint.

| Class | Role |
|---|---|
| `CgUiQuad` | Flat solid-colour fill; `CgUiDrawable.EMPTY` is one |
| `CgUiSprite` | Full 9-slice textured sprite (`setTexture`/`setSprite`/`setBorder`, lazy UV cache) |
| `CgUiRoundedRect` | SDF path — per-corner radii, morphing |
| `CgUiCrossFade` | Blends two drawables, for `background` transitions |
| `CgUiLayerBox` | Composites a stack; resolves `overlay-fit` via `intrinsicWidth()` |
| `CgUiRepeat` | Tiling modes |
| `ArgbMath` | Shared colour maths |
| `CgUiTransformDrawable` | Empty marker class — not implemented |

`render/texture/asset/CgUiSpriteRegistry` resolves `"namespace:name"` → sprite lazily from
`assets/{ns}/ui/sprites/{file}.json`. **A resource pack ships a theme by shipping JSON + PNG** — no
registration call. This is what `background: asset("crystalgui:ore", "button")` goes through.

`render/texture/geometry/` holds `Position` and `Size`, small Lombok `@Data(staticConstructor="of")`
int value types.

---

# Widgets

> **Full reference: `docs/CGUI_WIDGETS.md`** — per-widget API, internal-child class hooks,
> pseudo-classes, and covering harness scene. Read it before writing a new widget.

| Widget | Tag | Harness scene |
|---|---|---|
| `Button` | `button` | `cgui-button` |
| `Checkbox` | `checkbox` | `cgui-checkbox` |
| `CheckboxGroup` | — (not a `UIElement`) | `cgui-checkbox` |
| `DialogManager` | — (not a `UIElement`) | `cgui-gallery` (Dialog page) |
| `Switch` | `switch` | `cgui-switch` |
| `Slider` | `slider` | `cgui-slider` |
| `TextField` | `textfield` | `cgui-textfield` |
| `UIText` | `text` | `cgui-text`, `cgui-text-stress` |
| `Tooltip` | `tooltip` | `cgui-gallery` (Tooltip page) |
| `Dialog` | `dialog` | `cgui-gallery` (Dialog page) |
| `Scroller` | `scroller` | `cgui-scroller` |
| `ScrollerView` | `scrollerview` | `cgui-scroller` |
| `SplitView` | `splitview` | `cgui-splitview` |
| `TabView` | `tabview` | `cgui-tabview` |
| `Tab` | `tab` | `cgui-tabview` |

## Conventions — all enforced in code

- **Composite widgets refuse public children.** `acceptsPublicChildren()` returns `false` on `Button`,
  `TabView`, `Switch` and friends — `addChild` throws. Only elements *designed* to hold children accept
  them (`UIElement`, `ScrollerView`, `SplitView` panes, `Tab.content()`). Give a new widget a named
  accessor for its content instead of opening the tree.
- **Structure is internal children.** `markAsInternal()`/`addInternalChild()` build a widget's parts.
  They're skipped by public traversal and by `UIDescriptionCodec`, and each carries a
  `__double-underscore__` class themes target. There are **no CSS pseudo-elements** in the style
  engine — this is the substitute. The full set currently in use:

  ```
  __bottom__  __corner__  __divider__  __fill__      __first__   __h-scroller__  __head__
  __knob__    __left__    __mark__     __pane__      __panes__   __post-icon__   __pre-icon__
  __rail__    __right__   __second__   __spacer__    __strip__   __strip-bar__   __tail__
  __close__   __content__ __label__    __resizer__  __thumb__       __title-bar__
  __top__     __track__   __v-scroller__  __vertical__
  ```
- **No sizes, no timings, no colours in Java.** Widgets write structure and state; `default.css` gives
  functional geometry, `ore.css` gives appearance. `Switch`'s knob animation is a CSS `transition` on
  `flex-grow`, not a Java tween. **If you are typing a pixel value into a widget, it belongs in
  `default.css`.**
- **`UIFrameTicker`** — implement it and call `registerTicker(this)`; return `false` from `tickFrame`
  to drop it. Registration is `HashSet`-backed so re-registering is idempotent, and there is no
  unregister by design.
- **New pseudo-class = override a getter.** See `PseudoClasses` above.

## `UIText` — the one widget with a non-obvious design

It does **not** use a Taffy `MeasureFunc`. Taffy 1.1.4's flex-wrap cross-size algorithm passes `NaN`
instead of an item's resolved column width under `flex-wrap: wrap` (the `nowrap` path is correct), so
a measured leaf wraps at the wrong width whenever any ancestor has wrapping enabled — unfixable
without forking a Maven dependency.

Instead it is an ordinary Taffy leaf that recomputes *after* layout: `onLayoutChanged()` →
`recompute()` re-wraps against the box's just-settled `contentBoxWidth()` and pushes the resulting
height (and width, when self-sizing) back as `IMPORTANT` candidates via `StyleGroup.importantPipeline`.
Because `replaceOrPutCandidate` no-ops on an unchanged value, this **settles** — typically in 2–3
passes, all inside the same `calculateLayout()` `while (isLayoutDirty())` loop.

It retains a `CgShapedParagraph`, rebuilt only when the text or the resolved `CgFontFamily` instance
actually changes — never on a resize. Reference equality on the family is correct because
`FontFamilyCache.resolve` caches by `(paths, targetPx)`.

---

# Server layer — `serialization/` + `net/`

> **Full reference: `docs/CGUI_SERVER_AND_SERIALIZATION.md`.** Don't reverse-engineer it from classes.

A dedicated MC server builds a UI tree with **no CrystalGraphics present**, ships a description, and
talks to the client over RPC and bindings.

- **`serialization/`** — `Codec<A>`/`DynamicOps<T>`/`Codecs` (DFU-shaped), `JsonOps`, `PlainOps`,
  `StateMap` (widget state), `UIDescriptionCodec`, `ContentHash`; `serialization/style/` holds
  `StyleValueCodecs` and `InlineStyleCodec`.
- **`net/`** — `UIPacket`, `UIPacketCodec`, `UITransport`, `InMemoryTransport`, `ServerUiSession`,
  `ClientUiSession`, `RpcRegistry`, `NetworkIds`, `SheetRef`, `UiEventKinds`.

Three design facts worth knowing before you touch it:

1. **`ServerUiSession` holds no `UIWindow`.** That absence *is* the headless story, structurally
   rather than by flag: no window → no Taffy tree, no style engine, no layout → no path into text
   measurement, the one thing that genuinely needs a font stack.
2. **Descriptions are content-addressed.** `UIDescriptionCodec` output must be byte-identical for the
   same tree, so field order is fixed, maps are insertion-ordered, and absent optionals are omitted
   rather than written null. `OpenWindow` carries the *hash*, not the description — re-opening a UI
   costs one small packet however large the tree.
3. **Every packet carries a window id.** Resolving against "whatever menu is open" lets a packet in
   flight when a GUI closes land on the *next* one; four bytes makes that impossible.

`UIDescriptionCodec` encodes `{ tag, id?, class[]?, style{}?, flags?, focus?, state{}?, children[]? }`,
skips internal children (the constructor rebuilds them), and **throws on an unknown tag**.

---

# Load-bearing invariants

The things that are invisible from any single class and expensive to rediscover.

| Invariant | Consequence if violated |
|---|---|
| `UIWindow.init(w,h)` must be called | `invalidateStyleMatch()` early-returns on detached elements → **no selector ever matches** |
| `calculateStyle` runs before `calculateLayout` | Style changes land one frame late |
| A window that is never painted never matches selectors | `drainDirtyMatch` only runs inside `calculateStyle` |
| `getRootTransform()` is the only definition of `uiScale` | Pose and hit-test caches drift apart by exactly `uiScale` until first paint |
| `uiScale` lives in the `PoseStack`, not the ortho projection | Glyphs rasterize at logical size and get magnified — blurry text |
| `sortedChildren` = z-descending, equal-z later-inserted-first; paint walks it reversed | Hit-testing and visual stacking disagree about which child is on top |
| A promoted element diverges from its DOM parent in **four** places (Taffy parent, `getX()/getY()`, `localToWorld`, paint+hit entry) — only the cascade stays | Fix three and it draws correctly but clicks land elsewhere, or the reverse |
| Top-layer stacking is insertion order; `z-index` is irrelevant there (per spec) | Promoted elements stack unpredictably against each other |
| `Enter`/`Leave` dispatch to every element in the entered/left chain | A container with children never receives hover events at all |
| `setHitTest(false)` applies to the whole **subtree**, like CSS `pointer-events: none` | A transparent container is transparent everywhere except where its content is — hit testing looks random |
| While a pointer is captured, no boundary events reach anything else | `:hover` flickers across every element a drag crosses |
| `beginFrame()` only *invalidates* the hover cache, never reads it | Stuck hover (recompute against the new position labelled as the old) |
| `replaceOrPutCandidate` no-ops on unchanged values | Widget geometry feedback loops oscillate forever instead of settling |
| The cascade diff compares `realSlots`, not `computedSlots` | In-flight transitions can't be retargeted or cleaned up |
| Re-adding a stylesheet appends it at highest priority | Runtime theme switches apply in the wrong order |
| Taffy defaults are **not** CSS defaults | Silently wrong layout — see the table above |
| CSS text belongs in `test`, never `headlessTest` | `StyleSheet` class-init reads `default.css` via `CgIO` → unloadable headlessly |
| JOML + Taffy must stay on the headless classpath | Field descriptors resolve at class load; `UIElement`/`ElementStyle` have fields of those types |
| Composites return `acceptsPublicChildren() == false` | `addChild` throws; widgets need named content accessors |
| `attachListener`'s two booleans are additive | Target phase is *always* subscribed |
| `Property.set()` silently drops re-entrant sets from inside its own emit | A listener cannot fight the value it's being notified about |

---

# Global coding rules

## CrystalGraphics ownership boundary

**CrystalGraphics owns the rendering backend. CrystalGUI consumes it.**

- CrystalGUI may define renderer-facing abstractions and UI draw orchestration.
- Fonts, shaders, framebuffers, VAO/VBO, draw submission, GPU resource ownership, and modern GL
  pipeline capability belong in **CrystalGraphics**.
- Never write raw GL, raw `float[]` vertex packing, or a hand-rolled buffer in `core/`.

Because CrystalGraphics lives in this repo and is directly writable, if CrystalGUI needs a new backend
capability we **add it to CrystalGraphics** and integrate against the new API — we do not reimplement
the backend here.

> The full infrastructure ownership map (`CgStreamBuffer`, `CgStagingBuffer`, `CgVertexWriter`,
> `CgInstanceWriter`, `CgBufferWriter`, `CgShaderBuffer`, `CgMesh`, `CgVertexArray`,
> `CgAbstractShaderProgram`, and the decision tree for picking between them) lives in
> **`CrystalGraphics/AGENTS.md`**, which `CLAUDE.md` already loads every session. Read it there;
> it is not duplicated here.

## Platform-agnostic core

`core/` must stay fully cross-platform — the import guard enforces no `net.minecraft.*`,
`cpw.mods.fml.*`, `net.minecraftforge.*`, `org.lwjgl.*`. The platform seam is the **interface side
only**: `core/input/CgUiInputAdapter`, `core/input/UIClipboard`, `core/sound/UISoundSystem`, with
`CrystalGuiCore.setAdapter()/setClipboard()/setSoundSystem()` where a loader registers concrete
implementations. `UISoundSystem` and `UIClipboard` default to `NOOP`.

## Lombok

Prioritize Lombok to eliminate handwritten accessor boilerplate. It generates Java 8-compatible
bytecode and is `compileOnly` — no runtime dependency.

| Annotation | Use when |
|---|---|
| `@Data` | Simple POJOs, all fields in equals/hashCode/toString |
| `@Getter` / `@Setter` | Selective access — apply at field level when only some fields need accessors |
| `@RequiredArgsConstructor` | Immutable classes — pairs with `@Getter` only |
| `@Builder` | 4+ constructor parameters, or many optional ones |
| `@Value` | Fully immutable data carriers |
| `@ToString` / `@EqualsAndHashCode` | When you need one without full `@Data` |
| `@Accessors(chain = true)` | Fluent setters — used widely here |

Do **not** use `@Data` on classes with inheritance — use explicit annotations and always
`@EqualsAndHashCode(callSuper = true)` on subclasses.

---

# Package map

```
com.crystalgui.core            CrystalGuiCore — global LOGGER + adapter/clipboard/soundSystem registry
  .data                        CacheCell / IntCacheCell / LongCacheCell (dirty-flag memoization),
                               ReadOnlyVec2f (immutable view over a mutable JOML Vector2f), Transform2D
  .input                       CgUiInputAdapter (SPI), SystemInput (raw Mouse/Keyboard event records),
                               UIClipboard (SPI)
    .keyboard                  CgUiKeyCodes (LWJGL2-shaped constants, no LWJGL import), Modifiers (bitmask)
    .mouse                     CgUiMouseCodes
  .property                    Property<T> (binding, equality-suppressing set), ObservableList<T>
  .signal                      Signal.Action/Value/Pair, SignalBase, Connection, ConnectionGroup
  .sound                       UISoundSystem (SPI — widgets ask for a sound, the platform decides how)

com.crystalgui.lifecycle       CgUiLifecycle — the ONE CgLifecycleListener CrystalGUI registers with
                               CrystalGraphics; drives paint-context teardown + cache invalidation

com.crystalgui.render          CgUiPaintContext (singleton), CgUiRenderer, ScissorStack
  .text                        FontFamilyCache — (font stack, px) -> CgFontFamily
  .texture                     CgUiDrawable (SPI), CgUiQuad, CgUiSprite (9-slice), CgUiRoundedRect (SDF),
                               CgUiCrossFade, CgUiLayerBox, CgUiRepeat, ArgbMath,
                               CgUiTransformDrawable (stub)
    .asset                     CgUiSpriteRegistry — lazy "ns:name" -> sprite from ui/sprites/*.json
    .geometry                  Position, Size

com.crystalgui.style           ElementStyle, StyleGroup, GeneralGroup, LayoutGroup, StyleOrigin,
                               TaffyBridge, PseudoClasses, StyleEngine, CssParsingUtil, CssAngle
  .sheet                       StyleSheet (+DEFAULT), StyleRule, DeclarationParser (var(--x)),
                               StyleSheetRegistry
  .selector                    Selector, CompoundSelector, SelectorType
  .transition                  TransitionEngine, TransitionSpec, ActiveTransition, TransitionValue
  .easing                      Easing, Linear, CubicBezier, LinearPiecewise, ConstantEasing,
                               ProgressFunctions
  .property                    StyleProperty<T>, StylePropertyRegistry, StyleSlot, StyleValue,
                               IValueInterpolator
    .general.{bools,enums,floats,ints,strings}   scalar StyleValue/StyleProperty flavors
    .layout                    LayoutProperties, BoxEdgeShorthands
    .layout.{dimension,grid,length}              Taffy-shaped value types (LPA*, LPSize, Grid*)
    .visual                    Overflow, OverflowClip, ScrollBehavior, BoxOrigin, DrawableAlign,
                               DrawableFit, OutlineShorthand, OutlineOffsetShorthand (per-edge,
                               unlike CSS)
    .visual.border             BorderRadiusProperties, BorderRadiusShorthand, LengthPercent(+Property/Value)
    .visual.color              ColorProperty, ColorValue
    .visual.text               FontFamilyValue
    .visual.texture            TextureProperty, TextureValue
    .visual.transform          TransformProperty, TransformValue, TransformOriginShorthand

com.crystalgui.ui              UIElement, UIWindow, Ui, UITransform, EventListenerGroup,
                               ElementRegistry, UIFrameTicker (SPI), UITreeObserver, TopLayer, UIResizer
  .tree                        UITreeTraversal — stateless ancestor/tab-order queries
  .event                       UIEvent, PropagationPhase, DOMEvent, DragEvent, FocusEvent, KeyboardEvent,
                               MouseEvent
  .input                       UIInputHandler, UIDragController, FocusPolicy, ButtonState
  .elements                    Button, Checkbox, CheckboxGroup, Dialog, DialogManager, Scroller,
                               ScrollerView, Slider,
                               SplitView, Switch, Tab, TabView, TextField, Tooltip, UIText

com.crystalgui.serialization   Codec<A>, DynamicOps<T>, Codecs, CodecException, JsonOps, PlainOps,
                               StateMap, UIDescriptionCodec, ContentHash
  .style                       StyleValueCodecs, InlineStyleCodec

com.crystalgui.net             UIPacket, UIPacketCodec, UITransport, InMemoryTransport,
                               ServerUiSession, ClientUiSession, RpcRegistry, NetworkIds, SheetRef,
                               UiEventKinds
```

**Naming corrections vs. older notes:** `render/` is top-level (`com.crystalgui.render`), *not* nested
under `core/`. `core/input/` is the *raw platform I/O* layer only; dispatch and focus live in
`ui/input/`. The three-phase event types are in `ui/event/` — there is no `core/event/` package.

---

# Shipped assets

`core/src/main/resources/assets/crystalgui/`

| Path | Notes |
|---|---|
| `ui/styles/default.css` | **User-agent sheet.** Functional geometry for every widget with no theme loaded. |
| `ui/styles/ore.css` | Minecraft Ore UI theme, ported from LDLib2's `ore.lss`. |
| `ui/sprites/ore.json` | Sprite definitions backing `ore.css`. |
| `textures/gui/ore_styles.png` | Ore theme atlas. |
| `textures/gui/gdp_styles.png` | **Unreferenced by any code today.** |
| `textures/gui/Spritesheet_UI_Flat.png` | Unreferenced by any stylesheet today. |
| `ui/fonts/Minecraft.otf`, `MinecraftRegular.otf` | Public-domain MC fonts. |
| `shaders/gui_quad.shader` | Default material bound by `beginFrame`. |
| `shaders/gui_rounded_rect.shader` | SDF rounded rects. |
| `shaders/gui_layer_blit.shader` | Visual-layer FBO composite. |

> **All three declare `#pragma cg_use quad`, and any new CrystalGUI shader must too.** Everything
> here draws through `CgQuadRenderer`, whose per-instance buffer supplies `CG_QUAD_WORLD_POS` /
> `CG_QUAD_UV` / `CG_QUAD_COLOR` — the pragma is what wires it, during parsing, before anything can
> compile. Omitting it is a parse error naming the missing line (it used to be a GLSL error about an
> undefined `QUAD_DATA`, reported four layers up as an unrelated `#pragma cg_feature` complaint).
> Never attach the buffer from Java. See `CrystalGraphics/AGENTS.md` § *Engine Buffers*.

---

# Documentation index

| Doc | Status | Contents |
|---|---|---|
| `docs/CGUI_STYLE_RENDER_PIPELINE.md` | **current** | Cascade, selectors, stylesheets, transitions, frame lifecycle, drawables & compositing channels, `background:` grammar, border-radius layer, visual layers (opacity + masking), `transform`/`transform-origin`, known gaps vs. the web, file map |
| `docs/CGUI_WIDGETS.md` | **current** | All thirteen widgets: API, internal-child class hooks, pseudo-classes, covering harness scene |
| `docs/CGUI_SERVER_AND_SERIALIZATION.md` | **current** | Codecs, `StateMap`, descriptions, content hashing, network ids, `SheetRef`, packets/sessions/RPC, known gaps, the headless contract |

These three are the only docs under `docs/` — audited against the code on 2026-07-29 and accurate as
of that pass. `CRYSTALGUI_OVERHAUL_V4.md` (the historical decision record for why CrystalGUI stopped
owning rendering infrastructure) was deleted; its one durable conclusion — CrystalGraphics owns the
backend, CrystalGUI is a thin immediate-mode paint surface — is recorded in
[CrystalGraphics ownership boundary](#crystalgraphics-ownership-boundary) above.

## External references

- **LDLib2** — pattern prior art for widgets and the Ore theme. An **in-repo checkout** at
  `research_repos/LDLib2`, never a dependency. Stylesheets at
  `research_repos/LDLib2/src/main/resources/assets/ldlib2/lss/` (`gdp.lss`, `mc.lss`, `modern.lss`).
  Java sources under `src/main/java/com/lowdragmc/lowdraglib2/`; note `bin/` also holds compiled
  `.class` files, so search `src/` explicitly. *(Was documented as a sibling checkout at `../LDLib2`,
  which does not exist.)*
- **Taffy** — consumed as the Gradle artifact `dev.vfyjxf:taffy` (version in `gradle.properties`), but
  **extracted Java sources are checked in** at `research_repos/taffy/dev/vfyjxf/taffy/`. Read them
  directly — there is no need to decompile through the IDE, and no need to guess at layout semantics
  (containing blocks, absolute positioning, flex-wrap cross-sizing) that the engine's own behaviour
  depends on. *(Previously documented as "no source checkout"; it exists.)*
- **Minecraft sources** — not extracted at the paths the MC modules would produce
  (`mc1201/*/build/mc-src/`, `build/rfg/minecraft-src/java`), since neither MC module is in the build.
  **But an extracted 1.20.1 tree is checked in** at `research_repos/mc1201_sources/`
  (`com/`, `mcp/`, `net/`). Cite that path, not the build ones.

---

# For future reference

- **Cg** → CrystalGraphics
- **Cgui** → CrystalGUI
