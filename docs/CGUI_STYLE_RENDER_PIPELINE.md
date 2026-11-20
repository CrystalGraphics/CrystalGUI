# CrystalGUI Style & Render Pipeline

> **Current-state reference** for the style/cascade/paint pipeline, end to end.
>
> Background, in one line: CrystalGraphics owns all rendering infrastructure and CrystalGUI is a thin
> paint surface on top of it. An earlier design had CrystalGUI own a render queue; that was abandoned
> in favour of the immediate-mode pipeline described here.
>
> Companions: `CGUI_WIDGETS.md` (the twelve widgets and their CSS surface) and
> `CGUI_SERVER_AND_SERIALIZATION.md` (how styles travel to a client).
>
> Re-verify against the code before trusting a specific line number.

---

## 1. The Cascade

`core/src/main/java/com/crystalgui/style/`

Every element owns one `ElementStyle`, which holds `candidates: Map<StyleProperty<?>, List<StyleSlot<?>>>`
— every value ever set for that property, from every source, tagged with where it came from.

**`StyleOrigin`** (priority, low → high): `DEFAULT(0) < USER_AGENT(1) < STYLESHEET(2) < INLINE(3) <
IMPORTANT(4) < ANIMATION(5)`. Two of these are deliberate and easy to get backwards:

- **`ANIMATION` outranks everything**, including `IMPORTANT` — an in-flight transition must visually win
  regardless of what set the underlying target value, matching CSS Cascade L4/5. Without it, a transition
  triggered by an `!important` change would tick every frame producing values `computeCandidateSlot`
  never selects.
- **`USER_AGENT` sits below author sheets.** `StyleSheet.DEFAULT` (`default.css`) is loaded at this
  origin, so an author rule beats it at *any* specificity — the browser UA-sheet behaviour. Sharing
  `STYLESHEET` would lose twice: `sourceOrder` restarts per sheet, and specificity is weighed before
  source order, so a specific UA rule would beat a general theme rule.

**`StyleSlot<T>`** — `record(property, origin, specificity, sourceOrder, value)`. Cascade winner is
picked by `StyleSlot.compareTo`: origin first, then specificity, then source order — the same
tie-break order real CSS uses.

**Resolution** (`ElementStyle.resolveTouched`/`resolveOne`) is two-phase, and the phase split matters:

1. **Phase 1** recomputes, for every touched property, both `realSlots` (winner **excluding** any
   `ANIMATION` candidate) and `computedSlots` (winner **including** it — what actually gets displayed).
2. **Phase 2** diffs each property's *old* `realSlots` value against its *new* one and either notifies
   listeners directly or offers the change to `TransitionEngine` first.

The `realSlots`/`computedSlots` split exists because diffing against `computedSlots` would compare a
transition's own last tick against itself — an `ANIMATION` candidate always wins the priority
comparison, so if the diff read the displayed value, a genuinely-changed STYLESHEET/INLINE target
underneath an in-flight transition would look unchanged, silently defeating retargeting and cleanup.

**A reverted property with no candidate left at all** resolves its `realSlots` entry to `null` — but
`resolveOne` substitutes `p.initialValue` before offering that to the transition engine (not the raw
`null`), because that's what `StyleGroup.getValueSave` will actually display once resolved. Skipping
this substitution is what caused an early bug: reverting `background-color` away from an explicit
value fell straight through `TransitionEngine.tryStart`'s null-guard and snapped instead of animating.

**Inheritance** is lazy/pull-based: `ElementStyle.getComputed` walks to the parent's `getComputed` when
there's no local candidate and the property `isInheritable()`. Not push-invalidated — an inherited value
changing does **not** fire the inheriting element's own `StyleChangeListener`s or make it
transition-eligible, only a genuine local candidate change does that. Inheritable today: `color`,
`font-size`, `font-family`, `line-height`, `caret-width`, `selection-color`, `text-shadow`,
`text-offset-x`/`-y`.

`moveInlineAsDefault()` bulk-reclassifies every `INLINE`-origin candidate on an element down to
`DEFAULT` in one atomic pass — meant for widget authors: write baseline styling with ordinary
`.layout()`/`.generalStyle()` calls (INLINE by default), call this once at the end of construction, and
a stylesheet (or the widget's actual user calling `.layout()` again) can freely override it.

---

## 2. Selectors & Stylesheets

`core/src/main/java/com/crystalgui/style/selector/`, `core/src/main/java/com/crystalgui/style/sheet/`

**Supported**: type, class, id, universal (`*`) and pseudo-class selectors, the `>` child combinator,
descendant combinators, comma-separated selector lists, `!important`. Specificity follows real CSS
weights (id=100, class/pseudo-class=10, type=1, universal=0).

**Not supported** (see §9 for the full gap list): `:nth-child`/attribute selectors, `~`/`+` sibling
combinators, `@media`/`@import`.

### `::part()` — reaching into a widget

A widget's structure lives in a shadow tree, and an ordinary selector cannot reach in. `::part(name)`
can: it selects a **real element** inside one and contributes to that element's own cascade.

```css
checkbox::part(mark)          { background: #1E1E1E; }
checkbox:checked::part(mark)  { overlay: shape("checkmark"); }
::part(v-scroller)            { width: 6px; }        /* hostless: whoever exposes it */
```

Three rules decide whether a declaration can be written at all:

- **A pseudo-class BEFORE `::part()` describes the host; AFTER it describes the part.**
  `dropdown::part(menu):open` asks whether the MENU is open — a node that is never open. Collapsing the
  two made a dropdown's menu keep the closed-popup `opacity: 0` and match nothing that lifted it: it
  opened, promoted, placed itself correctly, and drew at zero alpha.
- **A `::part` rule is indexed under the HOST**, so styling a shadow descendant costs a second lookup —
  and a host's re-match must re-match its exposed parts, or `checkbox:checked::part(mark)` keeps the
  styles it matched under the host's PREVIOUS state.
- **A hostless `::part(x)` is legal** and means "whoever exposes this". It is the right translation of a
  bare class rule that deliberately avoided listing tags — a subclass reporting its own tag matches no
  list, and its bars laid out at zero width while the wheel still scrolled perfectly.

**What `::part()` CANNOT express**, and this is measured rather than feared — **401 of 1,048 shipped
part selectors select a part under a part, and 99 more reach through a part into a tag**:

| Wanted | Why it cannot be written |
|---|---|
| `::part(a)::part(b)` | invalid CSS — a part is a LEAF |
| `dropdown::part(menu) menuitem` | nothing descends from a part |
| `colorselector .__channel-row__ slider::part(thumb)` | a NESTED widget's part is inside ITS shadow tree |

Two mechanisms answer these and only one exists: a sheet **scoped** to the composite's shadow root
(below), and `exportparts`, which does not. Until then such rules reach nothing — silently, since a
`::part` rule that matches nothing is indistinguishable from one that was never written.

### `@scope` — a sheet that only applies inside one subtree

`StyleEngine.addStylesheet(sheet, root)`: only nodes at or under `root` can match, and
`StyleSlot.proximity` ranks **between specificity and source order** — so a composite's own sheet beats
an outer rule of equal specificity and loses to a more specific one.

**Many roots wearing the same sheets take a `ScopeGroup`, not one install each.** Every install re-matches
the whole window, so installing per root costs a window re-match per root — the Library's preview cards,
one shadow root each, would pay it on every scroll. Install once against the group and add roots to it:

```java
ScopeGroup cards = new ScopeGroup();
window.styles().addStylesheet(sheet, cards);
cards.add(card.attachShadow());   // before its content is attached
```

`StyleEngine.sheetsRevision()` moves on every install and removal, so a mirror of the sheet list checks it
per frame for free.

This replaced a textual rewrite that prefixed every selector with a scoping class, and the difference is
worth knowing because the prefix version had a bug no amount of care removes: CSS has no "this element
or below", so `.scope S` cannot match the element WEARING `.scope`. Every rule aimed at the scoped root
itself silently stopped applying — a panel opened as an unstyled sliver in the corner with nothing
failing anywhere.

**Pseudo-class names are resolved through `PseudoClasses.lookup(String)`**, which case-folds and swaps
`-` for `_` — so `:focus-visible` finds `FOCUS_VISIBLE`. Both the eager parse-time validation and the
match-time lookup go through it. That validation is why the mapping matters more than it looks: an
unknown pseudo-class throws out of `StyleSheet.parse`, taking the **entire sheet** with it rather than
skipping one rule, which surfaces as a silently unstyled theme.

### `:focus` vs `:focus-visible`

Both exist and mean different things, matching the web:

| | true when |
|---|---|
| `:focus` | the element holds focus, however it got there |
| `:focus-visible` | focus arrived by keyboard (Tab) or programmatically — **not** from a pointer click, unless the element takes text input |

`Input` decides this via `ui/input/FocusSource` (`KEYBOARD` / `POINTER` / `PROGRAMMATIC`), and
applies the text-input carve-out through `UINode.consumesTextInput()` — the same predicate the
keyboard handler already uses. `UINode.setFocused(boolean)` treats forced focus as *visible*; the
two-arg `setFocused(boolean, boolean)` is what the handler calls to say otherwise.

`default.css` hangs its ring off `:focus-visible`, so tabbing to a slider rings it and clicking it does
not, while a text field rings either way. Theme opt-outs should stay on the broader `:focus` — see the
comment on Ore's `outline: none` block.

**Two ways in.** `StyleSheet.parse(String)` for inline CSS text; `StyleSheetRegistry.of("crystalgui:ore")`
for an external file at `assets/{namespace}/ui/styles/{path}.css`. The registry is lazy and
`ConcurrentHashMap`-cached, so repeated calls return the *same* `StyleSheet` instance — which is what
makes `StyleEngine.removeStylesheet` usable for a runtime theme switch. A missing file logs a warning and
yields an empty sheet, and is deliberately **not** cached, so it is retried once the owning resource pack
loads. Two sheets ship today: `default.css` (user-agent) and `ore.css` (theme).

`StyleSheet.parse` buckets rules by id/class/type/universal for fast candidate lookup and delegates
declaration-level parsing to **`sheet/DeclarationParser`**, which also implements CSS custom properties —
`collectVariables` gathers `--name: value` declarations and `substituteVariables` resolves `var(--name)`
references. `StyleEngine.rematch(element)` re-evaluates only the buckets relevant to that element on
every dirty pass.

**Sheet order is registration order**, and `sourceOrder` packs the sheet index above the rule index
(`SHEET_ORDER_STRIDE`). So re-adding a previously removed sheet puts it back at the **highest** priority,
not its original position. Anything that toggles sheets at runtime must re-add in the order it wants.

**Box-model shorthands (`margin`/`padding`/`border-width`) expand into their real longhands at parse
time**, not at cascade time. `margin`/`padding`/`border-width` themselves, and their `-all`/
`-horizontal`/`-vertical` aliases, are **not** registered `StyleProperty` instances — only the four
per-edge longhands (`margin-left`/`-top`/`-right`/`-bottom`, same for padding/border-width) are. This
matches how a real browser's cascade actually works internally (`getComputedStyle` only ever exposes
resolved longhands — a shorthand is sugar over them, never a separately-cascading concept). Recognized
and expanded in `StyleSheet.parseDeclarations` via the shared table in `BoxEdgeShorthands`, which is
also consulted by `TransitionEngine.findApplicableSpec` so `transition: margin ...` still animates all
four edges together. Declarations within one rule get a fine-grained index folded into their
`sourceOrder` (`StyleEngine.DECLARATION_ORDER_MULTIPLIER`) specifically so a later `margin-left:` in
the same rule correctly outranks an earlier `margin:` shorthand's expansion, instead of tying.

This replaced an earlier design where `margin`/`padding`/`-all`/`-horizontal`/`-vertical` *were*
independently-cascading properties, reconciled by a hand-rolled, **non-CSS-accurate** resolver
(`TaffyBridge`'s old `LPARectData`/`LPRectData`) that gave `margin-left` **permanent, sticky**
priority over `margin` the moment it was ever set — regardless of actual cascade order. That
resolver duplication also hid a real bug (`gap`'s height read `this.horizontal` instead of
`this.vertical`, copy-pasted from the width line above it) — fixed as part of the same cleanup.

---

## 3. Transitions

`core/src/main/java/com/crystalgui/style/transition/`

`transition: <prop|all> <duration>[ms|s] [<delay>] [<timing-function>]`, comma-separated multiple
entries. `TransitionSpec.parse` splits entries on top-level commas only (`CssParsingUtil.splitTopLevelCommas`
— paren-aware, so `cubic-bezier(a,b,c,d)`'s internal commas don't split entries), then tokenises each
entry with `CssParsingUtil.splitFunctionList`, which keeps a whole `name(...)` call as one token.

`TransitionEngine.tryStart(element, property, fromValue, toValue)` is offered first refusal on every
transition-eligible cascade change (from `ElementStyle.resolveOne`). If accepted, it shadows the real
value with an `ANIMATION`-origin `StyleSlot` (`ElementStyle.startAnimationSlot`/`tickAnimationSlot`),
ticked once per frame (`TransitionEngine.tick`, called from `StyleEngine.calculateStyle`), and cleared
(`endAnimationSlot`) when finished — at which point the real (non-animated) winner takes back over.

**Interrupt-and-retarget**: if a property already has an in-flight transition when a new target
arrives, the new transition starts from `inFlight.currentValue(now)` — wherever it currently visually
is — not from the old resting value. Matches real CSS transition behavior.

**Null-guard**: `tryStart` declines (and cancels any stale in-flight transition) if either endpoint is
`null` — this exists to avoid NPEs on a genuine first-ever resolution, not to skip legitimate reverts
(see §1's `initialValue` substitution, which is what keeps a legitimate revert-to-unset out of this path).

---

## 4. Frame Lifecycle

`core/src/main/java/com/crystalgui/ui/UIDocument.java`

```
UIDocument.paintFrame()
  advanceFrame()                               // shared with updateWithoutPainting() — see below
    styleEngine.calculateStyle(deltaSeconds)   // drainDirtyMatch() (selector rematch) + transitionEngine.tick()
    animation().tick(deltaSeconds)               // smooth scrolls + every registered UIFrameTicker
    calculateLayout()                          // Taffy computeLayout(), while dirty
  driver.view().paintContext()                     // the document's own CgUiPaintContext, its driver's view's
  paintContext.recordFrame(actualScreenW, actualScreenH)  // the frame target, its ortho, the scissor reset
    pose.pushPose(); pose.mulPoseMatrix(rootTransform)   // rootTransform = the ONE definition of uiScale
      ui.rootElement.the paint walk(paintContext) // paintContent → children (z-sorted) → paintDecoration → paintOutline
    pose.popPose()
  paintContext.seal()                          // built: a UiFrame its presenter shows at a GUI stage
  inputHandler.beginFrame()/endFrame()         // hover cache invalidation + hit-test + event dispatch
```

Style resolution happens **before** layout on purpose — a stylesheet/transition change to a layout
property (width, padding, ...) must be visible to Taffy in the same frame it changes. Note also that
`drainDirtyMatch` runs *only* inside `calculateStyle`, so a window that is never painted never matches
a selector at all.

The `rootTransform` push is deliberate and load-bearing: `Box.localToWorld` falls back to the
same matrix for the root, so hit-testing is correct *before* the first paint. Don't inline a
`pose.scale(...)` here — that is exactly how the two definitions of `uiScale` drifted apart before.

**`updateWithoutPainting()`** runs `advanceFrame()` alone — style, animations and layout with no GL and
no draw, and deliberately no input handling (no frame was presented, so hover has nothing to be
relative to). It exists for headless tests and for benchmarks isolating layout/shaping cost from render
cost, since per-element material binds at draw time can dwarf everything else.

---

## 5. Images & Compositing Channels

`core/src/main/java/com/crystalgui/render/image/`, `core/src/main/java/com/crystalgui/ui/box/BoxPainter.java`

`background`, `overlay`, `mask` and `outline` hold a **`CgUiImage`**: CSS's `<image>`, an immutable value equal by
value — a colour, a gradient, a grid, a picture (`image`/`sprite`), a sprite-sheet entry (`asset`), an icon, a mark
(`shape`), a painter (`paint(name)`), a cross-fade, or a stack of them. **`CgUiImagePainter` turns one into canvas
draws**; nothing else draws an image. `render/CLAUDE.md` § *Images* has the kinds and how each draws.

### A declaration can hold several images

A value is a comma-separated **list of layers**, exactly as CSS writes a background:

```css
background: grid(16, #6EDCD024), shape("checkmark"), linear-gradient(to bottom, #3574F0FF, #2E436EFF);
```

**The first layer is the TOP one** — CSS's order, so the painter walks the list from the end. Every image property
takes a stack. The split is paren-aware, so `linear-gradient(to bottom, a, b)` is one layer and not three; one
unreadable layer fails the whole declaration rather than being dropped, since a stack quietly missing a layer
renders something nobody wrote.

**Every layer is clipped to the element's corners, and the element's border is drawn once over them**, as CSS
paints a background. Per-layer `background-size`/`-position`/`-origin` are not there yet; see
`plan/crystalgui/style-overhaul/css-background-layers.md`.

Copy Attributes (`ImageParts`) offers **one part per layer**, each filed under its own kind, so a three-layer
background is three tickable rows under Grid, Shape and Gradient. An image built in Java with no CSS spelling is
copied whole.

**Two distinct compositing channels exist, deliberately kept separate:**

| Channel | Set via | Meaning |
|---|---|---|
| **Tint** | the painter's `color` (`background-color`, or `color` for a mark or a `currentColor` icon) | Multiplies the image's own colours: the canvas box's `tint`, an icon brush's `color` |
| **Layer opacity** | `canvas.save().opacity(a)` or `canvas.layer(...).opacity(a)` | Whole-draw compositing weight, a flat multiply for a lone draw and a group layer over overlapping ones |

They're separate because conflating them was an earlier bug: a cross-fade that scaled the tint's alpha to blend its
two sides corrupted the tint itself. A cross-fade draws each side under its own opacity.

**`background` vs `background-color` vs `color`** — three separate, independently-cascading properties:
- `background` (`ImageProperty`) — the image.
- `background-color` (`ColorProperty`, default `0xFFFFFFFF` — opaque white, i.e. a no-op tint) — tints the
  `background` image. With no image it paints as a flat fill — gated on **candidate existence**
  (`ElementStyle.containsCandidate`), not on the resolved value, since that defaults to opaque white either way.
  (CSS paints `background-color` under the image instead; UI15 decides the move.)
- `color` — inheritable, the text's colour and a mark's; never tints `background`.

### 9-slice tiling

A `sprite(...)` with a non-zero border is drawn as a 9-slice. By default every region **stretches**;
CSS `border-image-repeat`'s modes are available as a trailing keyword (one value, or two for
per-axis). Corners never tile, matching CSS.

| mode | behaviour |
|---|---|
| `stretch` | one copy smeared across the span (default) |
| `repeat` | whole tiles at natural size; the last one is clipped |
| `round` | tile size adjusted so a whole number fits exactly — no clipped tile |
| `space` | whole tiles at natural size, leftover split into equal gaps (not drawn) |

```css
background: sprite("tex.png", "0 0 16 16", "4 4 4 4", "round");        /* both axes */
background: sprite("tex.png", "0 0 16 16", "4 4 4 4", "repeat space"); /* x, then y */
```

Trailing args are type-sniffed and order-independent, so the optional `"refW refH"` size reference
and the tiling keyword can appear in either order.

Sprite-pack JSON additionally supports two Unity-derived options that have no `sprite(...)` syntax
(a bare number or extra keyword there would be too obscure to read):

- **`fillCenter`** (bool, default `true`) — Unity's *Fill Center*; `false` draws the frame with a
  see-through middle.
- **`borderScale`** (number, default `1`) — Unity's *Pixels Per Unit Multiplier*; scales the slice
  widths *and* tile sizes, so a 4px source border can render chunky at 8px.

**One renderer implements this**: the picture's `CgBrush` carries a `CgNineSlice` and a `CgTiling`, and
CrystalGraphics' canvas lays the nine regions out per pixel, with or without a `border-radius`. `space` with no room
for a whole tile falls back to `stretch` (CSS draws nothing).

**Texture resolution is lazy.** A `sprite(...)` value holds its path; the painter loads the texture the first time
it draws, nearest-sampled, and keeps it with the value's brush. A style value is computed whenever a stylesheet is
parsed — before a GPU device exists, and always without one under unit test — so it never touches one.

**Three image layers**, all `ImageProperty` and all costing zero layout nodes — decoration is a
paint concern here, not a tree concern (contrast LDLib2, which nests a real child element per
decorative visual because its style system lacks background geometry controls):

| Layer | Painted | Clipped by own mask/scissor? | Geometry longhands |
|---|---|---|---|
| `background` | before children | no | *(none — see §9)* |
| `overlay` | after children | no | `overlay-origin`, `overlay-size`, `overlay-position`, `overlay-tint` |
| `outline` | after overlay, last | no | `outline-offset`, `outline-width`, `outline-color` |

- **`overlay-origin`** (`border-box`\|`padding-box`\|`content-box`, default `border-box`) — CSS
  `background-origin`. Which box the layer is laid into.
- **`overlay-size`** (`fill`\|`contain`\|`cover`\|`none`, default `fill`) — CSS `object-fit`, the
  honest analogue since this engine fits *one* image into a box rather than tiling. `contain`/
  `cover`/`none` need the image's natural size (`CgUiImagePainter.intrinsicWidth/intrinsicHeight`,
  `-1` when it has none); every mode degrades to `fill` when unknown, so colours, gradients and marks
  keep filling. A picture reports its region's size, an icon its document's, as logical px.
- **`overlay-position`** (9 keywords, default `center`) — CSS `object-position`, keyword subset.
- **`overlay-tint`** (a colour, default white) — multiplies the colours an icon or picture overlay names itself,
  as `color` resolves an icon's `currentColor`; transitions as a colour, and costs no re-raster at icon size. The start button's
  mark goes blue on hover through it:

  ```css
  taskbar .__start-button__::part(pre-icon)       { transition: overlay-tint 140ms ease-out; }
  taskbar .__start-button__:hover::part(pre-icon) { overlay-tint: #D4E2FF; }
  ```
- **`outline`** — a layout-free ring drawn above everything, at the border box expanded by
  `outline-offset` (a `LengthPercent`, so `2px`/`2`/`%` all parse; default `0`). This exists because
  `border-width` feeds Taffy and therefore *resizes* the element, making it unusable for focus
  indication — exactly why CSS has `outline`. It also frees `overlay` to stay a widget's own
  decoration (e.g. `checkbox:checked .__mark__ { overlay: <check glyph> }`) instead of being fought
  over for focus rings.

  **`outline-offset` is per-edge here, unlike CSS's single scalar.** The real cascading properties
  are `outline-offset-top`/`-right`/`-bottom`/`-left`; `outline-offset` is 1–4 value shorthand over
  them, clockwise from the top exactly like `margin` (see `OutlineOffsetShorthand`, which mirrors
  `BoxEdgeShorthands`). The reason is 9-slice rings: a sprite's transparent padding need not be
  symmetric, and a single scalar can only trade a gap on one edge for a gap on the other three. Ore's
  selected tab is the live case — `tab-on` keeps two transparent texel rows along its top edge to make
  a selected tab sit raised, so `tab:checked:focus-visible { outline-offset: -2px 0 0 0; }` tightens
  only that edge. On the SDF path the corner-radius expansion takes one amount per axis, so asymmetric
  offsets use the mean of the two edges on that axis — a rounded corner joining two differently-offset
  edges has no single correct radius, and the 9-slice case that motivates per-edge never reaches that
  branch.

  It comes in **two forms**, with the image winning when both are set (the precedence CSS gives
  `border-image` over `border`):

  1. **Image** — `outline: asset("crystalgui:ore", "focus-ring")`. A 9-slice ring texture.
  2. **Stroke** — `outline: 2px #4488ff`, i.e. `outline-width` + `outline-color`. Drawn as a canvas
     box with a transparent fill and a border, so it **follows `border-radius` for free** and needs
     no texture. The fill is `outlineColor & 0x00FFFFFF` (same RGB, zero alpha) rather than
     `0x00000000`: the shader mixes border→fill on straight alpha, so a black-transparent fill
     drags the inner AA edge toward black and leaves a visible dark fringe.

  `outline` is therefore **polymorphic at parse time** (`OutlineShorthand`), dispatching on the
  value's shape: a non-color function call (`asset(…)`/`image(…)`/`sprite(…)`) → the image slot;
  `none` → `outline-width: 0`; otherwise `<length>` and/or `<color>` tokens, order-independent, →
  the two longhands. A **bare color means `outline-color`**, not a solid-fill image — a solid
  image outline would just cover the element. `rgb()`/`rgba()` are recognised as colors, not
  image functions, despite having parens.

  Geometry note: the canvas box strokes *inward* from a shape's outer edge, while a CSS outline
  grows *outward*, so `paintOutline` inflates by `offset + width` and lets the inward stroke land in
  the band between them. Corner radii are resolved against the element's **own** box and then
  expanded (`CornerRadii.expand`) — re-resolving them against the inflated box would re-scale
  percentage radii into a visibly over-curved ring. Note this means a `border-radius` shapes an
  element's focus ring even when the element paints no background at all, which is why `default.css`
  gives the slider root a radius it has nothing to fill.

Defaults across all three reproduce pre-longhand behaviour exactly (stretch to the full border box,
no outline), so none of this changes rendering until a stylesheet opts in. The fit math is
`CgUiImagePainter.fit(...)`, ready for `background` if the `border-radius` coupling above is ever resolved.

---

## 6. `background:` Grammar Reference

Every form is an explicit function call (no implicit/bare-path form — parsing an unrecognized value
returns `null`, same as any malformed CSS value).

| Form | Produces | Notes |
|---|---|---|
| `#RRGGBB` / `#RGB` / `#RRGGBBAA` / `rgb(...)` / `rgba(...)` | `CgUiImage.Color` | 8-hex form is CSS-standard `#RRGGBBAA` (alpha last), not the engine's internal `0xAARRGGBB` int packing |
| `image("path")` | `CgUiImage.Picture`, unsliced | Optional trailing args, type-sniffed, order-independent: quoted `"x y w h"` crop rect, quoted `"refW refH"` texture-size-reference override, or a color literal (tint) |
| `sprite("path", "sx sy sw sh", "bl bt br bb")` | `CgUiImage.Picture`, 9-slice | Optional 4th `"refW refH"` arg, same override as `image(...)` |
| `asset("ns:path", "element")` | `CgUiImage.Sheet` | Named 9-slice element from a sheet at `assets/{ns}/ui/sprites/{path}.json`, drawn with CrystalGraphics' `CgSpriteSheet` brush (nearest-sampled). One sheet holds many named elements; each may name its own `texture`. A missing sheet or element is logged and draws nothing. F3+T re-reads the sheets |
| `linear-gradient(direction?, stop, stop, …)` | `CgUiImage.Gradient` | CSS's: an `<angle>` in any unit, `to <side>` or `to <corner>` (resolved per box); a stop is a colour with an optional `%` position, and missing positions spread evenly. One canvas box filled with a `CgGradient`: **premultiplied** interpolation, dithered, clipped to the element's corners and under its border. No colour hints, no `repeating-` |
| `radial-gradient(shape? size? (at position)?, stop, stop, …)` | `CgUiImage.Gradient` | CSS's: `circle` or `ellipse` (the default), an extent (`closest-side` … `farthest-corner`, the default) or one or two lengths (one alone is a circle; a circle's radius is never a `%`), and a position of keywords, `%` or `px`, centred by default. The shape is sized per box; stops, drawing and masking are the linear gradient's |
| `icon("ns:name", #tint?, monochrome?)` | `CgUiImage.Icon` | A named icon; its light or dark drawing chosen as it draws, so a theme swap needs no restyle. Untinted, it follows `color` |
| `shape("checkmark")` | `CgUiImage.Mark` | One of `CgUiMark`'s sixteen marks, in `color` |
| `grid(cell, #colour, width?)` | `CgUiImage.Grid` | Lines every `cell` px (`cell` or `cellX cellY`) |
| `paint(ns:name)` | `CgUiImage.Painted` | A painter a mod registered with `CgUiPainter.register`, looked up as it draws (CSS Paint API's spelling) |
| `cross-fade(a p%, b)` | `CgUiImage.CrossFade` | What a transition between unlike images writes |

> **`glass()` is gone, and what it did is `backdrop-filter`.** It was a `background` value, which meant
> an element could have the material or a colour and never both — and CSS already has a name for
> filtering what shows through. `backdrop-filter: blur(24px) saturate(1.2) tint(#1C1D21CC)` is the same
> effect as a property, painted UNDER the element's own background: a `CgBackdropFilter`, `NONE` when unset. `blur()`
> and `saturate()` are CSS's; the other twelve functions are ours. @see `BackdropFilterValue`
>
> **An opaque `background` hides it completely**, which is the one thing to know before it looks like a
> bug. `glass()` OCCUPIED the background, so setting it always replaced whatever fill was there and was
> always visible; a filter sits under that fill. Pasting `backdrop-filter` alone onto an element that has
> a background therefore looks like nothing happened — the filter is drawn, and painted over. Clear the
> background, make it translucent, or carry it along in the same paste.

`CssParsingUtil.splitTopLevelCommas` (paren-aware comma split) backs every multi-arg form here.

There is no `roundedrect(...)` background function — rounding and the border apply to whatever `background:`
resolves to (§7), not a background value of its own.

---

## 7. Universal Border-Radius/Border-Width/Border-Color Layer

`CrystalGraphics/core/src/main/resources/assets/crystalgraphics/shaders/lib/sdf.glsl`,
`CrystalGraphics/core/src/main/resources/assets/crystalgraphics/shaders/canvas/box.shader`,
`core/src/main/java/com/crystalgui/ui/box/BoxPainter.java`, `core/src/main/java/com/crystalgui/render/image/`,
`core/src/main/java/com/crystalgui/style/property/visual/border/`

`border-radius`/`border-width`/`border-color` apply to *whatever* `background:` produces, as in CSS. `BoxPainter`
resolves the corners (kept on the `Box` while they hold) and the four borders, and hands both to
`CgUiImagePainter`: a fill, a gradient, a grid or a picture (nine-slice included) is one canvas box with the corners
and border on it; an icon, a mark or a painter draws, then the border alone goes over it; a stack draws each layer
clipped to the corners, then the border once.

**An absent background is not a colour.** With no image and no `background-color`, a border draws alone, its middle
transparent in the *border's* rgb at zero alpha (a black-transparent middle would fringe the inner edge dark), and
an element with neither paints nothing.

### Theme anti-bleed

`default.css` is a user-agent sheet: it paints flat colours so a themeless UI works. A theme paints art
over the same widgets — so every appearance value the UA sheet sets and the theme does *not* override
stays underneath, showing as a grey rectangle behind a sprite or an SDF stroke carved into one.

The shipped policy, at the top of `ore.css` in one labelled block: **the only user-agent declaration
allowed to reach a themed element is `:focus-visible`'s outline.** Everything else the theme neutralises
explicitly, grouped by property so the block copies wholesale as the starting point for a new theme.

Three traps worth knowing before writing one:

- **`border-width` is not just paint.** It feeds Taffy *and* flips the element onto the SDF rounded-rect
  path, where the border **replaces** the outer pixels of a 9-slice sprite rather than sitting on it —
  and where `background-color` tints only the fill and the sprite's own tint is dropped entirely. A
  stray UA `border-width` over a themed sprite is a different render path, not a stray outline.
- **Reset element-specific, not `*`.** A universal reset also squashes values a *consumer* set on its
  own elements. Name only what the theme actually paints.
- **Generic helper classes are not the theme's to claim.** `.label` is deliberately exempt: a consumer
  puts it on *its* backgrounds, which a theme cannot know, so forcing a colour there is worse than the
  bleed. Widget-internal labels are covered anyway, since `color` inherits from the widget roots.

**Rounding is applied per widget in `default.css`, never on `*`** — see the "Corners" comment there.
A `*` radius drags every clipping element onto the FBO mask path (§8) for corners nobody can see, and
claims the property in every consumer's tree. `ore.css` resets it on exactly the widgets it has sprites
for (corners are drawn into the texture), rather than blanket-resetting `*` and squashing radii an app
set on its own elements.

`border-radius` is elliptical per corner (independent rx/ry, not a single scalar) — real CSS syntax,
`border-radius: <h-list> [ / <v-list> ]`, each list a 1/2/3/4-value TL/TR/BR/BL corner shorthand,
expanded at parse time into 8 real longhands (`BorderRadiusProperties`) by `BorderRadiusShorthand`,
mirroring `BoxEdgeShorthands`'s architecture. Percentages resolve against the element's own width (rx)
/ height (ry) at paint/hit-test time — not via Taffy, since corner radius isn't a layout quantity.
`border-width` is the real per-edge `border-width-*` longhand (already existed, already grows Taffy's
box under `CONTENT_BOX`) — sourced from `getTaffyLayout().border()`'s already-resolved pixels, not
reparsed independently, so it can never drift from what actually grew the layout. The SDF's own stroke
width stays a single scalar for now (asymmetric per-edge visual stroke rendering is an explicitly
deferred gap — not requested, and orthogonal to border-width actually growing the box, which is fixed).

`sdf.glsl` has three `sdf_rounded_box` overloads: uniform `float radius`, per-corner `vec4 radii`
(circular), and per-corner elliptical (`vec4 radiiX, vec4 radiiY`) — all CSS TL/TR/BR/BL order,
quadrant-selected on the fragment's local position (Y-down local space). The elliptical overload
normalizes the corner-region offset by (rx,ry) before a circular distance evaluation, then scales the
result back by `min(rx,ry)` — approximate (exact only when rx==ry) but visually correct, matching this
codebase's existing SDF approximation style. `UINode`'s Java-side hit-test (`isMouseOverElement`)
uses the identical technique against the same resolved per-corner values, so rendering and hit-testing
never disagree about the element's shape. `sdf_coverage` turns a signed distance into an antialiased
0–1 mask via `fwidth`.

> **`sdf_coverage` is guarded out of the vertex and compute stages, and that guard is load-bearing.**
> `canvas/box.shader` includes `sdf.glsl` at *material* scope, and CrystalGraphics' compiler
> hoists every material-scope `#`-line into **both** generated stages — so without the guard,
> `fwidth`, a fragment-only derivative builtin, lands in the vertex shader. NVIDIA compiles that
> anyway. AMD refuses, and the whole material fails to compile: an AMD tester could not launch
> `cgui-gallery` at all while it ran flawlessly here. The three `sdf_rounded_box` overloads are pure
> maths and stay available to both stages; only the coverage helper is fragment-restricted.
>
> Two things keep it that way: `ShippedShaderStagePurityTest` (GL-free, fails on any machine if a
> fragment-only builtin becomes reachable in a generated vertex source) and the harness's
> `--mode=shader-compile-audit` (real driver, every shipped shader and keyword variant, one report).
> The full contract is in `CrystalGraphics/docs/SHADERS.md` § *Stage defines*.

The shader is a genuine "canvas": interior filled by `_FillColor` or a sampled `_MainTex`
(`WITH_TEXTURE_FILL` keyword), an optional `_BorderColor` stroke band (`WITH_BORDER` keyword) along the
outer edge, both masked by the same distance field so corners clip fill and border consistently.

**A painter with a shader of its own draws `ctx.quad(material)`**, having set its properties first: the quad takes the material's values as it is submitted, so a property set after it reaches only the quads after (`docs/CGUI_INVARIANTS.md`).

**Transitions, not morphing**: the box's shape is never part of the `background` value, so each of the 8 radius
longhands, the border-width longhands and border-color animate independently as ordinary scalar/colour
`StyleProperty` transitions. `background` itself interpolates through `CgUiImage.interpolate`: two colours mix, two
gradients of one shape slide, anything else cross-fades.

---

## 8. Visual Layers (Opacity Isolation + Masking)

`opacity < 1` over children that can overlap, and a `mask`, paint into a **group layer**: the canvas's
`layer(x, y, w, h)` (CrystalGraphics `api.canvas`, `CrystalGraphics/docs/CANVAS.md`), opened by `BoxPainter`. Ordinary
elements (opacity 1, no mask) skip this entirely, and so does a rounded `overflow: hidden`, which is a clip (below).

```java
try (CgCanvasLayer layer = canvas.layer(box.inkX0(), box.inkY0(), inkW, inkH)) {
    layer.opacity(0.6f);                 // the subtree composited as one picture
    paintChildren(...);
}
```

**A layer is the size of what goes in it**: the subtree's [ink bounds](#ink-bounds), cut to the clip and grown by a
filter's reach. Its picture is a transient texture for the frame.

**Ink bounds** are Blink's visual overflow: `Box.inkX0..inkY1`, composed bottom-up in `BoxTree` alongside the world
matrices, from each box's border box grown by its outline, by whatever `UIElement.inkOverflow()` declares, and by its
children's, truncated to the padding box wherever `overflow` clips. **A widget that paints outside its own box and
does not declare it is clipped, and only inside a layer**, which is the one failure worth knowing about here.

**Two layers are never opened at all.** A mask over a childless box does nothing (`masks-elided`), and an `opacity`
whose box paints at most one primitive folds into the draw (`canvas.opacity`, `layers-elided`): group opacity differs
from per-primitive opacity only where two primitives cover the same pixel (Skia's rule). The fold is refused for any
node that overrides a paint hook.

**A layer lives for one frame; what is kept is a surface.** What did not change inside a layer is replayed from its
segments' kept chunks rather than painted. A picture kept across frames belongs to a box the compositor moves -- a
window, a pointer follower, a compositor animation -- a canvas `CgKeptLayer` composited again while nothing in it
changed and repainted only where something did (`core/src/main/java/com/crystalgui/render/CLAUDE.md`). A layer inside
a surface places its composite there every walk (`Box.layerPlacement`), since its opacity is not in its segments'
keys. `UIElement.repaint()` is the door for a widget whose picture changes without moving a box.

**`clip-path: inset()` is a clip over the box itself.** `BoxPainter.clipPath` clips the canvas to the inset rect in
the box's own space, its corners `round <radius>` (grown by the box's outline, which rings the clip), before anything
of the box paints, so it cuts the box's background, backdrop and children alike; an inset that leaves nothing draws
nothing. It eases between two insets (`ClipPathProperty`), which is what a flyout's slide-out-of-an-edge open is: a
`translate(0, 50%)` with `inset(0 0 50% 0)`, both easing to zero, keeps the visible edge fixed while the box moves
(the taskbar previews and jump list, `desktop.css`). It clips paint only; hit testing sees the whole box.

**A rounded `overflow: hidden` is a clip, not a layer.** It clips the children to the padding box with the inner
corners, **whatever the background** — CSS's rule; a translucent panel does not fade its own children, and alpha
masking is the `mask` property's job. It is the canvas's `clip(x, y, w, h, radii)`, the radii cached on the `Box`:
geometric, under any transform, so a rotated or skewed box clips exactly. Clips nest to `CgClipTable.MAX_DEPTH` (4);
past that the canvas clips by a path mask. The children cost no target, no clear and no composite.

**A subtree drawn wholly outside the clip is not walked.** `BoxPainter` carries each box's ink bounds through the
transform and asks `CgUiPaintContext.outsideClip` before painting it (Blink's cull rect). It is what lets a list keep
rows realised past its viewport for free. `-Dcrystalgui.paint.cull=false` paints everything, to rule it out.

**Why a layer at all**: without one, overlapping translucent children blend against whatever is already drawn one at
a time, then each gets faded independently — the double-blend seam at the overlap. Isolating the subtree, then fading
the *result* as one unit, avoids that (the same reason browsers isolate `opacity`-bearing stacking contexts).

**`mask`** clips the children to the box's shape and then through a layer masked by the image: a colour by its
alpha (`layer.opacity`), a picture or a sheet entry by its texels' alpha (`layer.mask(CgBrush.texture(...))`). A mask
that would reveal nothing (`mask: none`, a clear colour) reveals the whole shape; a cross-fading mask uses the side it
is nearer. It masks **only the children**: the background, overlay and outline draw unmasked, as a 9-slice frame
sits on whatever it frames.

**Ordering** (background → children → overlay → outline, all inside the group layer when there is one): CSS puts the
outline in the opacity group, so it fades with the box.

---

## 8b. `transform` / `transform-origin`

A paint-time affine over an element and its whole subtree, applied on top of layout without disturbing
it. **Taffy never sees it** — transforming an element cannot reflow its siblings or resize its parent,
which is what makes it the right tool for a zoomable canvas (put one scale on a container and the
subtree zooms with layout frozen underneath — the same trick LDLib2's node graph uses).

```css
transform: translate(10px, 5px) scale(2) rotate(45deg);
transform-origin: left top;              /* default: 50% 50% */
transition: transform 160ms ease;        /* interpolatable */
```

Supported functions: `translate`/`translateX`/`translateY` (lengths or percentages of the element's own
box), `scale`/`scaleX`/`scaleY` (unitless), `rotate`, `skew`/`skewX`/`skewY`, and `none`. Angles take
`deg`/`rad`/`turn`/`grad` — a unitless non-zero angle is rejected, as in CSS.

**The value is an ordered function list, not a decomposition.** CSS composes left-to-right as matrix
multiplication, so `translate(10px) scale(2)` and `scale(2) translate(10px)` are genuinely different —
the first translates then scales the translated space, the second scales first so the same translate
lands at 20. A translate-field-plus-scale-field value type cannot represent that distinction at all.
`Transform` therefore stores `List<Op>` and `applyTo` walks it in order; each JOML call
post-multiplies, so declaration order *is* the composition order with no reversal.

**`transform-origin` is two real longhands** (`transform-origin-x`/`-y`, both `LengthPercent`), with
`transform-origin` as 1–2 value shorthand syntax over them — the same architecture as `outline-offset`.
Keywords (`left`/`center`/`right`/`top`/`bottom`) resolve to percentages, and the reversed keyword pair
(`top left`) is accepted as CSS allows.

**Hit-testing follows automatically and this is the load-bearing invariant.** `Box.localToWorld`
and the paint `PoseStack` both call the *same* `Transform.applyTo`. If they ever diverged, a click
would land somewhere other than what is drawn and nothing about the rendering would look wrong.
`HitTestBeforePaintTest` is what pins it, and it pins the stronger half: a click lands on what layout
put there with **no paint having happened at all**, which is only possible because the matrices come
from the layout pass rather than from something a painter reconciles.

Non-inheritable, matching CSS. It already reaches descendants through the matrix chain, and inheritance
here is pull-based — an inherited change does not fire the inheriting element's `StyleChangeListener`s,
which is the very mechanism that dirties the subtree's matrices.

**Set from Java** with `element.setTransform(Transform…)` (sugar writing `transform` at `INLINE`
origin) or `style(s -> s.general(g -> g.transformOrigin(x, y)))`.

---

## 8c. `line-height: normal`, and why the caret ignores it

`line-height` takes CSS's `normal | <number>`, and **`normal` is the default**, as in CSS: the line box
comes from the font's own `ascender + descender + lineGap` via `CgFontFamily.getLayoutMetrics()`.

`normal` is carried as **`Float.NaN`** rather than a union value type. That keeps the property a plain
`Float`, so it still has a codec and inline `line-height` still crosses the wire — a union type would
return `null` from `StyleValueCodecs.forProperty` and make `InlineStyleCodec` throw. The interpolator is
guarded so a transition into or out of `normal` snaps instead of blending `NaN` through every frame.
`AutoFloatProperty` established the same idiom for `flex`/`aspect-ratio`.

**The sentinel becomes pixels in exactly one place — `TextField.paintDecoration`.** Resolving it in
`GeneralGroup`, in a `StyleValue`, or anywhere in the cascade would drag `CgFontFamily` into style
resolution, which a dedicated server performs with no CrystalGraphics on the classpath at all.
`core/src/headlessTest` exists to catch precisely that.

**The caret and the selection band are sized independently of `line-height`**, from
`ascender + descender` only. A line box also carries `lineGap` — leading *between* lines — which
neither a text cursor nor a selection has any business drawing. Measured on MinecraftRegular at size
10: ascender 8 + descender 2 + lineGap 2 = a 12px line box, which is exactly what the old
`font-size × 1.2` convention produced — so switching to `normal` alone changed nothing for that font.
Dropping the lineGap is what took both from 12px to 10px and stopped them hanging past the descender
into the field sprite's bevel.

A browser's selection *does* span the full line box, but only so consecutive lines leave no gap between
them. **`TextField` is single-line**, so that reason does not apply and the extra lineGap was simply
overhang. `line-height` is therefore left driving one thing only: where the line box sits vertically
inside the field.

**The selection is painted only while the field is focused** (`isFocused() && hasSelection()`).
Blurring does **not** clear the range — browsers keep it so refocusing restores it, and `TextField`'s
Blur listener deliberately does only `commit(); resetBlink();`. The fix for a blurred field showing a
live-looking highlight is in the paint guard, not in the blur handler; reaching for `clearSelection()`
there would lose the range rather than just stop drawing it.

Both show only in a focused field — one with a selection, one without and with the blink disabled for a
deterministic capture — and a screenshot of a page catches neither, which is why both being 2px too
tall went unnoticed.

---

## 8d. Text outlines — `text-stroke` and friends

Four authorable properties, all inheritable:

```css
text-stroke: 2px #0B5D8F;      /* width and/or colour, order-independent */
text-stroke: 0.04em;           /* width only -- the colour falls back to the text's `color` */
text-stroke: 4%;               /* the SAME width: a percentage is of the font size, so 4% == 0.04em */
text-stroke: #0B5D8F;          /* colour only -- the width is left alone */
text-stroke: none;             /* width 0; the colour is untouched */
stroke-align: outset | center | inset;   /* initial: outset */
paint-order: normal | stroke;  /* initial: normal -- the FILL paints over the stroke */
```

`text-stroke` is the web's `-webkit-text-stroke` without the prefix, and like it covers only width
and colour — `stroke-align` and `paint-order` are separate declarations.

**`px` is absolute; `em` and `%` are the same font-relative unit.** A width resolves as
`resolve(fontSize) / fontSize`, so `2px` becomes `2/fontSize` em — a hairline on a 72px heading and
heavy on a 10px label — while `4%` and `0.04em` both become 0.04em and keep their weight at every
size. That equivalence is why `em` can be supported at all: `FontRelativeLengthValue` folds it into a
percentage, since there is no font size at parse time and the consumer supplies one as the resolve
axis. The shared `LengthPercent.parse` must never learn `em` — it also backs `border-radius`,
`transform-origin` and the outline offsets, whose percentages are fractions of the BOX, where `1em`
would have to mean a whole box width.

**`text-stroke-width` and `text-stroke-color` exist but are NOT writable by those names.** They are
registered, so they cascade, inherit, interpolate and serialise under their real names — and
`StylePropertyRegistry.byName` resolves them, which `InlineStyleCodec` requires, since it throws on a
name it cannot resolve. What refuses them is `DeclarationParser`, via
`StyleProperty.getAuthoredThrough()`, so a sheet has one spelling to learn.
**Why keep two properties at all:** a declaration that states only a colour must leave the width
alone, so `text-stroke: #FF0000` in a `:hover` rule does not zero the outline. One combined value
cannot express a partial override — which is exactly why CSS shorthands are shorthands.

The shorthand is intercepted before the registry lookup and matched with `equals`, since its name is
a prefix of both longhands. Its tokenizer respects parentheses, so `rgb(11, 93, 143)` survives as one
token — `outline`'s splits on whitespace and cannot. `transition: text-stroke` reaches both halves
through `TextStrokeShorthand.transitionNameMatches`.

**The route to the backend is one class.** `CssTextStyle`, one per widget, reads the `ComputedStyle` and
builds CrystalGraphics' `CgTextStyle` from it: the text's colour as a fill layer, the stroke as an outline
layer (its width resolved against the font size, `stroke-align` its align, `paint-order` whether it sits
under the fill or over it as a ring, a mitre join), and each `text-shadow` as a shadow layer. It rebuilds
only when one of those inputs changes. Everything below that is CrystalGraphics': each layer is its own
run of glyph quads, in one draw call per layer while they share an atlas page.

### Four things that are easy to get wrong

**The stored distance field caps the width, and it clamps rather than complaining.** The field carries
`(pxRange - 1) / 2` texels of real distance either side of the outline, and the usable reach is a
texel short of that again, because a bilinear tap straddling the saturation shoulder averages a
clipped texel with a live one and the outer edge scallops while the fill stays smooth.

**The range is per FACE, so the cap is too.** A face carrying a dense script keeps `pxRange 12` — eight
bits hold `storedRange / 255` per level, and past 12 that merges strokes a kanji keeps apart. Every
other face is banded at `pxRange 24`. Both share the 80px atlas scale, so one atlas holds both and
the range travels per PLACEMENT rather than as a material property; a narrow-banded and a wide-banded
glyph still batch in one draw.

| band | who gets it | ceiling | at text size 64 | field tier down to |
|---|---|---|---|---|
| `pxRange 12` | a face with a dense script in it | **0.056em** | 3.6px | 15px |
| `pxRange 24` | everything else | **0.131em** | 8.4px | 7px |

The wide band is past the 0.083em Godot allows for the same technique. Widening the range costs no
shape because the atlas SCALE does not move — the glyph is rasterised at the resolution it always
was and only the padding grows, which is why a lower scale was measured and rejected: 48px reached a
similar ceiling for less memory and lost shape on Arabic at every range.

Ask `CgFontRegistry.maxStrokeWidthEm(family)`, or `UIText.maxStrokeWidthEm()`, for the real number.
`CgTextLayer.MAX_FIELD_WIDTH_EM` is the NARROW band — the reach every face has, and less than half
of what most have. A stroke keeps its label on the distance-field tier down to the size below which
msdfgen's own rule says the field cannot antialias, and is dropped under that; the shader clamps to what the field holds, keeping a TEXEL of headroom so the
edge still has a gradient to antialias across — a texel, not a screen pixel, because the shoulder
belongs to the texel grid and one screen pixel is a third of a texel on a zoomed canvas. Asking for more is safe and simply stops getting wider.
The minus one is the generator reserving a texel so the field cannot bleed past its cell — and the
shader must be told that reduced number, not the nominal `pxRange`, or every glyph edge comes out
crisper than intended (it was, by 20%, until it was measured).

**A transparent colour is not an absent one, and by value they are identical.** `text-stroke-color`
has `0` as its initial, and `#00000000` parses to exactly `0`. So
"did the author write this?" cannot be answered by comparing against the initial — `UIText` asks
`ComputedStyle.isSet`, which reads the candidate maps. Get this wrong and a deliberately transparent
outline silently becomes an inherited-colour one. Pinned by `TextStrokeStyleTest`.

**An inward stroke closes the letterform where the letterform is thin, and that is geometry.** Fill
survives only where the ink is thicker than twice the inward reach (`inset` spends the whole width
inward, `center` half), and a type designer *thins the joints* so a merge does not read as a blot — so
a `g`'s link and an `h`'s shoulder close first. Confirmed against the raw outline, not just the field:
see `docs/CGUI_INVARIANTS.md` § *Rendering, GL and shaders*. `outset` never touches the letterform,
which is why it is the initial.

**Bitmap-tier glyphs take no stroke at all.** Below roughly 8px a glyph rasterises as coverage rather
than a distance field, and there is no distance to threshold. The declaration is inert rather than an
error, which is the right degradation for a size at which an outline would be illegible anyway.

### The composite is a union, not source-over

For an `outset` stroke the ring and the fill are **disjoint regions of one shape**, not two layers.
Compositing them source-over gives `a + b(1-a)` where disjoint areas owe `a + b` — 0.75 against 1.0 at
the shared edge, a quarter-coverage hairline tracing every outline and visible even when stroke and
fill are the same colour. `text.shader` takes the silhouette's coverage as the union and divides it
between the two by how much of it each occupies; paint order decides which is measured first only
where they genuinely overlap, which is `center` and `inset` and never `outset`.

## 8e. Stacking order — what paints over what

Paint order and hit order are CSS's (2.1 Appendix E), from Blink's stacking model: a **stacking context**
paints its own background, then its negative `z-index` boxes, then its normal flow, then its `auto`/`0` and
positive boxes; hit-testing walks that exactly backwards. `StackingOrder` holds a context's three lists,
`BoxPainter` and `Box.hitTest`/`pick` both read them.

| A box is… | when |
|---|---|
| a **stacking context** | the root; hosted by override (a popup, a mirror, the top layer); `z-index` not `auto`; `opacity` < 1; a `transform`; `backdrop-filter`; `mask`; `isolation: isolate` |
| **positioned** | `position: absolute` — painted with its context's `auto` boxes, after its normal flow |
| **normal flow** | everything else, painted in tree order where it sits |

A z-ordered box (a context, or positioned) is painted by the nearest context above it, **not** inside its
parent — so a transformed button in one row paints and takes the pointer over the row after it, as in a
browser. It is still clipped by every box it rose out of, rounded corners included.

- **`z-index` applies to every box**, positioned or not: every box here is a flex or grid item, which CSS
  gives a `z-index` whether or not it is positioned. `auto` is the initial value and is not `0`.
- **A negative `z-index` sits under its context's whole normal flow** — under its parent's background too,
  unless the parent is the context. `isolation: isolate` on the parent is the fix that changes nothing else.
- **An absolute box without a `z-index` paints over later normal-flow siblings.** A wash meant to sit under
  its siblings takes `z-index: -1` inside a context (the taskbar's glows).
- **The top layer paints after everything**, outside every context in the document, as CSS's does: a popup
  or dialog is over any `z-index` a window was raised to. And a family of boxes numbered against each other —
  windows raised by an ever-growing `z-index` — needs a context of its own (`desktop .__windows__` isolates),
  or the numbers are ordered against the whole document.
- **Rounded `overflow: hidden` is not a context**, as in CSS; a lifted descendant is masked to the corners
  through its own layer.
- **Deviation:** within one normal flow a box paints whole (background, content, children) rather than in
  CSS's phases (all backgrounds, then all content). It matters only where in-flow boxes overlap.

---

## 9. Known Gaps vs. the Web

- **No `@import` or media queries.** External stylesheets *are* supported now —
  `StyleSheetRegistry.of("ns:path")` loads `assets/{ns}/ui/styles/{path}.css` through `CgIO`, so a
  resource pack ships a theme just by placing a file at that path. CSS custom properties
  (`--var`/`var()`) are implemented too.
- **No `:nth-child`, attribute selectors, or `~`/`+` sibling combinators** — only `>` and descendant.
- **No pseudo-class arguments** — `:focus-visible` and the rest are bare names; `:not(…)`, `:is(…)`
  and `:has(…)` have no equivalent.
- **`transform` has no `matrix()`**, and mismatched function lists **snap** where CSS decomposes both
  ends into matrices and interpolates those. Matching lists (same length, same kinds in the same
  order) interpolate component-wise as CSS does, which covers the case authors are told to write
  anyway. The axis variants also collapse into their two-argument form (`translateX(5px)` →
  `translate(5px, 0)`), which *widens* what can interpolate rather than narrowing it.
- **Clipping is axis-aligned, so `overflow: hidden` on a rotated or skewed element clips against its
  unrotated bounding box.** The scissor is a real `GL_SCISSOR_TEST` rect; browsers do better.
- **Text inside a scaled subtree rasterises at its untransformed size**, so it blurs when zoomed well
  past 1×. That is a CrystalGraphics-side glyph-cache concern, not a style one; LDLib2 has the same
  shape of problem and mitigates it with font oversampling on its TTF.
- **No pseudo-elements** (`::before`/`::after`) — decorative sub-visuals use the `overlay`/`outline`
  paint layers (§5) or a real shadow part with a fixed class, not generated content.
- **No `background-position`/`-size`/`-origin`** as independent properties — the analog is
  baked-in crop rects on `image()`/`sprite()` at parse time. **`overlay` and `outline` are not
  subject to this** (see §5): `overlay` has real `-origin`/`-fit`/`-position` longhands, and
  `outline` has `-offset`. `background` can't get equivalents without design work, because its rect
  doubles as the painted box *and* as the basis percentage `border-radius` resolves
  against — re-boxing it would silently redefine what `border-radius` means.
- **Tiling is a property of the sprite value, not the element.** CSS `border-image-repeat`'s four
  modes exist as a trailing `sprite(…)` keyword / JSON field (see §5), not as a cascading
  `background-repeat` property. Consequently it can't be varied per state (`:hover` etc.) without
  declaring a second sprite value. Tiling also only applies to **9-slice** sprites — a borderless
  `image()` still stretches, since it never enters the slicing path.
- **No Unity-style "adaptive" tiling** (stretch below a size threshold, tile above). `round` covers
  the main motivation, avoiding a clipped final tile.
- **No `outline-clip`/`background-clip`** — the outline is clipped by neither the element's own
  `border-radius` nor its `overflow: hidden`. It *is* clipped by an **ancestor's** scissor/mask
  (the canvas's clip applies to every draw under it, layers included), so a positive
  `outline-offset` inside a scroll view gets cut — real CSS behaves the same way.
- **No `overlay-color`** — `paintDecoration`/`paintOutline` reset the ambient tint to white, so those
  layers can only be tinted via a tint baked into the value (`image(path, #tint)`), unlike
  `background`, which `background-color` multiplies. (The SDF outline stroke is deliberately immune
  to ambient tint — the shader applies the vertex tint to `fillColor` only, so a focus ring can't be
  dimmed by a parent's `background-color`. Its color comes from `outline-color`.)
- **No `outline-style`** — solid only; no `dashed`/`dotted`/`auto`.
- **No `currentColor`** — CSS defaults `outline-color` to `currentColor`; there's no such mechanism
  here, so it defaults to opaque white. Rarely observable, since `outline-width` defaults to 0.
- ~~**No automatic (UA-stylesheet) focus ring**, because there is no origin that loses to author
  CSS.~~ **Resolved.** `StyleOrigin.USER_AGENT(1)` exists and `StyleSheet.DEFAULT` loads `default.css`
  at that origin, so the engine now ships baseline rules that any theme can override at any
  specificity — including the `:focus-visible` ring it now draws by default (§2).
- **Partial text styling** — `color`, `font-size`, `font-family`, `line-height`, `caret-width` and
  `selection-color` are wired and inheritable. `UIText` consumes the first three plus
  `text-offset-x`/`-y` (also inheritable — Ore sets `text-offset-y` once on `*` and relies on it
  reaching every widget's internal label, which no author selector can name); `TextField` consumes all
  six, with `line-height` driving its selection rect and vertical centring — its caret is sized from
  font metrics instead, see §8c. `text-shadow` is CSS Text Decoration 4's list — offset, blur,
  a non-negative spread, `inset`, `currentcolor` — painted by both text widgets through
  `CssTextStyle`, as shadow layers of the text's own style: outer shadows under the text last-first,
  insets over it. A small reach reads the glyph's distance field; a blur or spread past the field's
  range reads a worker-built cell from the bitmap atlas (Skia's mask blur), and a decoration's shadow
  is an analytic rect blur. A `::highlight` may carry its own. Still missing: `text-align`; and
  `UIText` measures at the font's own metrics rather than honouring `line-height` — though with
  `normal` as the default the two now agree unless a sheet says otherwise.
  Note `font-size` takes a bare number: `10`, not `10px` — its parser is `Float.parseFloat` and
  *throws* on a unit suffix, unlike `width`/`height`/`outline-offset`, which do accept `px`.
- **SDF border stroke width is a single scalar**, even though real `border-width` is independently
  per-edge — a stylesheet with 4 different edge widths still grows the layout box correctly per edge,
  but the visual SDF stroke uses one representative value. True asymmetric per-edge SDF stroke
  rendering is deliberately deferred (not requested; orthogonal to the box-growth fix).
- **A cross-fade between two pictures is two draws at crossing opacities**, not one per-pixel blend.

---

## 10. File Map

| Concept | Path |
|---|---|
| Cascade / `ElementStyle` | `core/src/main/java/com/crystalgui/style/ElementStyle.java` |
| Style origins/slots | `core/src/main/java/com/crystalgui/style/StyleOrigin.java`, `.../property/StyleSlot.java` |
| Selectors | `core/src/main/java/com/crystalgui/style/selector/` (`Selector`, `CompoundSelector`, `SelectorType`) |
| Pseudo-classes | `core/src/main/java/com/crystalgui/style/PseudoClasses.java` (`lookup` does the `-`→`_` mapping), `.../ui/input/FocusSource.java` |
| Stylesheets | `core/src/main/java/com/crystalgui/style/sheet/` (`StyleSheet`, `StyleRule`, `DeclarationParser`, `StyleSheetRegistry`), `.../style/StyleEngine.java` |
| Shipped stylesheets | `core/src/main/resources/assets/crystalgui/ui/styles/default.css` (user-agent), `ore.css` (theme, with the anti-bleed block at its top) |
| Transitions / easing | `core/src/main/java/com/crystalgui/style/transition/`, `.../style/easing/` |
| Property registry | `core/src/main/java/com/crystalgui/style/property/StylePropertyRegistry.java` |
| Box-model shorthand expansion | `core/src/main/java/com/crystalgui/style/property/layout/BoxEdgeShorthands.java` |
| Border-radius shorthand expansion + value type | `core/src/main/java/com/crystalgui/style/property/visual/border/` (`BorderRadiusShorthand`, `BorderRadiusProperties`, `LengthPercent`) |
| `line-height` value + property | `core/src/main/java/com/crystalgui/style/property/visual/text/` (`LineHeightValue`, `LineHeightProperty`) |
| `transform` value type | `core/src/main/java/com/crystalgui/ui/Transform.java` (ordered `Op` list + `applyTo`) |
| `transform` parsing/property/origin shorthand | `core/src/main/java/com/crystalgui/style/property/visual/transform/` (`TransformValue`, `TransformProperty`, `TransformOriginShorthand`) |
| Shared CSS parsing helpers | `core/src/main/java/com/crystalgui/style/CssParsingUtil.java` (`splitTopLevelCommas`, `splitFunctionList`), `.../style/CssAngle.java` |
| Frame lifecycle | `core/src/main/java/com/crystalgui/ui/UIDocument.java` |
| Paint entry points | `core/src/main/java/com/crystalgui/ui/UINode.java` (`paintContent`/`paintDecoration`/`paintOutline`/`the paint walk`) |
| Paint context | `core/src/main/java/com/crystalgui/render/CgUiPaintContext.java` |
| Visual layers (opacity isolation + masking) | `core/src/main/java/com/crystalgui/ui/box/BoxPainter.java` over CrystalGraphics' `CgCanvas.layer` and `CgCanvasLayer` |
| Images and their painter | `core/src/main/java/com/crystalgui/render/image/` (`CgUiImage`, `CgUiImagePainter`, `CgUiMark`, `CgUiPainter`) |
| Image parsing | `core/src/main/java/com/crystalgui/style/property/visual/image/` (`ImageValue`, `ImageKinds`, `ImageProperty`) |
| SDF shader lib | `CrystalGraphics/core/src/main/resources/assets/crystalgraphics/shaders/lib/sdf.glsl` |
| Rounded and bordered rects | CrystalGraphics' `CgCanvas.box` (`api.canvas`) |
| Named 9-slice assets | CrystalGraphics' `CgSpriteSheet` (`api.paint.brush`), sheets at `assets/{ns}/ui/sprites/` |
| Demo scenes | `harness-scenes/src/main/java/com/crystalgui/harness/scene/` — `CgUiStylingScene` (selectors/cascade/transitions), `CgUiVisualLayersScene` (opacity isolation + masking), `CgUiGalleryScene` (every widget, a page each, with a live theme toggle). Full list in `CGUI_WIDGETS.md`. |
