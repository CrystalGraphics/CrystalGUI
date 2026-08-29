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

`UIInputHandler` decides this via `ui/input/FocusSource` (`KEYBOARD` / `POINTER` / `PROGRAMMATIC`), and
applies the text-input carve-out through `UIElement.consumesTextInput()` — the same predicate the
keyboard handler already uses. `UIElement.setFocused(boolean)` treats forced focus as *visible*; the
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
priority over `margin-all` the moment it was ever set — regardless of actual cascade order. That
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

`core/src/main/java/com/crystalgui/ui/UIWindow.java`

```
UIWindow.paintFrame()
  advanceFrame()                               // shared with updateWithoutPainting() — see below
    styleEngine.calculateStyle(deltaSeconds)   // drainDirtyMatch() (selector rematch) + transitionEngine.tick()
    tickAnimations(deltaSeconds)               // smooth scrolls + every registered UIFrameTicker
    calculateLayout()                          // Taffy computeLayout(), while dirty
  CgUiPaintContext.getInstance()               // a SINGLETON — not owned per-UIWindow
  paintContext.beginFrame(actualScreenW, actualScreenH)  // GL save, ortho, bind gui_quad, reset scissor
    pose.pushPose(); pose.mulPoseMatrix(rootTransform)   // rootTransform = the ONE definition of uiScale
      ui.rootElement.drawSubtree(paintContext) // paintSelf → children (z-sorted) → paintOverlay → paintOutline
    pose.popPose()
  paintContext.endFrame()                      // GL state restore
  inputHandler.beginFrame()/endFrame()         // hover cache invalidation + hit-test + event dispatch
```

Style resolution happens **before** layout on purpose — a stylesheet/transition change to a layout
property (width, padding, ...) must be visible to Taffy in the same frame it changes. Note also that
`drainDirtyMatch` runs *only* inside `calculateStyle`, so a window that is never painted never matches
a selector at all.

The `rootTransform` push is deliberate and load-bearing: `RuntimeCache.localToWorld` falls back to the
same matrix for the root, so hit-testing is correct *before* the first paint. Don't inline a
`pose.scale(...)` here — that is exactly how the two definitions of `uiScale` drifted apart before.

**`updateWithoutPainting()`** runs `advanceFrame()` alone — style, animations and layout with no GL and
no draw, and deliberately no input handling (no frame was presented, so hover has nothing to be
relative to). It exists for headless tests and for benchmarks isolating layout/shaping cost from render
cost, since per-element material binds at draw time can dwarf everything else.

---

## 5. Drawable System & Compositing Channels

`core/src/main/java/com/crystalgui/render/texture/`, `core/src/main/java/com/crystalgui/render/CgUiPaintContext.java`

`CgUiDrawable` is the "paint yourself into a rect" interface — `CgUiQuad` (flat color), `CgUiSprite`
(textured, optional 9-slice border), `CgUiRoundedRect` (SDF), `CgUiCrossFade` (generic two-drawable
blend). Each `draw(ctx, mouseX, mouseY, x, y, w, h)` call issues its own GPU draw(s) immediately —
no batching, no deferred submission.

**Two distinct compositing channels exist, deliberately kept separate:**

| Channel | Set via | Meaning | Consumed by |
|---|---|---|---|
| **Tint** | `ctx.setColor(argb)` | Per-drawable multiplicative color, baked per-vertex | `CgUiQuad`/`CgUiSprite`/`CgUiRoundedRect`'s own fragment output `*= i.color` |
| **Layer opacity** | `ctx.withLayerOpacity(t, drawBody)` | Whole-draw compositing weight | `_LayerOpacity` material property, multiplied into `fragColor.a` |

They're separate because conflating them was an earlier bug: an initial `CgUiCrossFade` draft scaled
the ambient tint's alpha channel to blend `from`/`to`, which corrupted the RGB tint channel used for
things like `background-color`. Layer opacity was added specifically so cross-fades (and any future
whole-subtree opacity feature) have a dedicated, tint-independent channel — see `CgUiPaintContext.withLayerOpacity`.

**`background` vs `background-color` vs `color`** — three separate, independently-cascading
properties, matching real CSS's separation:
- `background` (`TextureProperty`) — the drawable itself (color/texture/9-slice/SDF).
- `background-color` (`ColorProperty`, default `0xFFFFFFFF` — opaque white, i.e. a no-op tint) — set as
  the ambient tint (`ctx.setColor`) before the `background` drawable paints, so it visibly recolors
  whatever's there rather than being invisibly hidden behind an opaque drawable the way a literal
  underlay-fill layer would be. If `background` is `CgUiDrawable.EMPTY` (nothing to tint), it instead
  paints as a flat fill directly — gated on **candidate existence**
  (`ElementStyle.containsCandidate`), not on the resolved value, since the resolved value defaults to
  opaque white either way.
- `color` — inheritable, used for text glyph color; must never tint `background`.

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

**Both renderers implement this**, and must stay in agreement: `CgUiSprite`'s CPU quad loop, and the
`WITH_9SLICE_FILL` shader branch used whenever the element has a `border-radius`/`border-width`.
Since routing depends on an unrelated style property, a divergence would show up as "adding a
border-radius changed my tiling". They agree by construction — `CgUiRepeat.tileCount` runs **once in
Java** and the count is handed to the shader as a uniform rather than recomputed there.

Two deliberate deviations, both verified: `space` with no room for a whole tile falls back to
`stretch` (CSS draws nothing), and tile *positions* can differ between the two paths by up to 1
logical px under `round`, because the CPU path snaps vertex positions to integers while the shader
is continuous. `repeat` matches exactly, since its tile size is the integer source size.

**Texture resolution is lazy.** `CgUiSprite.setTexture(String)` records the path and only resolves it
to a real GL texture on first `getTexture()`/`draw()`. Creating a texture is a GL operation, but
style values are computed whenever a stylesheet is parsed — potentially before a GL context exists,
and always without one under unit test. Eager resolution made a `sprite(...)` value silently compute
to `null` in those cases, because `StyleValue.compute()` catches and swallows the failure. An
author-supplied `"refW refH"` survives the later resolution (tracked separately), since overriding
the real texture's dimensions is exactly what that argument is for.

**Three drawable layers**, all `TextureProperty` and all costing zero layout nodes — decoration is a
paint concern here, not a tree concern (contrast LDLib2, which nests a real child element per
decorative visual because its style system lacks background geometry controls):

| Layer | Painted | Clipped by own mask/scissor? | Geometry longhands |
|---|---|---|---|
| `background` | before children | no | *(none — see §9)* |
| `overlay` | after children | no | `overlay-origin`, `overlay-fit`, `overlay-position` |
| `outline` | after overlay, last | no | `outline-offset`, `outline-width`, `outline-color` |

- **`overlay-origin`** (`border-box`\|`padding-box`\|`content-box`, default `border-box`) — CSS
  `background-origin`. Which box the layer is laid into.
- **`overlay-fit`** (`fill`\|`contain`\|`cover`\|`none`, default `fill`) — CSS `object-fit`, the
  honest analogue since this engine fits *one* drawable into a box rather than tiling. `contain`/
  `cover`/`none` need the drawable's natural size (`CgUiDrawable.intrinsicWidth()/intrinsicHeight()`,
  `-1` when it has none); every mode degrades to `fill` when unknown, so solid colours and SDF shapes
  keep filling as before. `CgUiSprite` reports its source-rect size, interpreted 1:1 as logical px.
- **`overlay-position`** (9 keywords, default `center`) — CSS `object-position`, keyword subset.
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

  It comes in **two forms**, with the drawable winning when both are set (the precedence CSS gives
  `border-image` over `border`):

  1. **Drawable** — `outline: asset("crystalgui:ore", "focus-ring")`. A 9-slice ring texture.
  2. **SDF stroke** — `outline: 2px #4488ff`, i.e. `outline-width` + `outline-color`. Rendered as a
     `CgUiRoundedRect` with a transparent fill, so it **follows `border-radius` for free** and needs
     no texture. The fill is `outlineColor & 0x00FFFFFF` (same RGB, zero alpha) rather than
     `0x00000000`: the shader mixes border→fill on straight alpha, so a black-transparent fill
     drags the inner AA edge toward black and leaves a visible dark fringe.

  `outline` is therefore **polymorphic at parse time** (`OutlineShorthand`), dispatching on the
  value's shape: a non-color function call (`asset(…)`/`image(…)`/`sprite(…)`) → the drawable slot;
  `none` → `outline-width: 0`; otherwise `<length>` and/or `<color>` tokens, order-independent, →
  the two longhands. A **bare color means `outline-color`**, not a solid-fill drawable — a solid
  drawable outline would just cover the element. `rgb()`/`rgba()` are recognised as colors, not
  drawable functions, despite having parens.

  Geometry note: the SDF shader strokes *inward* from a shape's outer edge, while a CSS outline
  grows *outward*, so `paintOutline` inflates by `offset + width` and lets the inward stroke land in
  the band between them. Corner radii are resolved against the element's **own** box and then
  expanded (`CornerRadii.expand`) — re-resolving them against the inflated box would re-scale
  percentage radii into a visibly over-curved ring. Note this means a `border-radius` shapes an
  element's focus ring even when the element paints no background at all, which is why `default.css`
  gives the slider root a radius it has nothing to fill.

Defaults across all three reproduce pre-longhand behaviour exactly (stretch to the full border box,
no outline), so none of this changes rendering until a stylesheet opts in. The fit math lives in the
reusable `CgUiLayerBox.resolve(...)`, deliberately standalone so `background` can adopt it if the
`border-radius` coupling above is ever resolved.

---

## 6. `background:` Grammar Reference

Every form is an explicit function call (no implicit/bare-path form — parsing an unrecognized value
returns `null`, same as any malformed CSS value).

| Form | Produces | Notes |
|---|---|---|
| `#RRGGBB` / `#RGB` / `#RRGGBBAA` / `rgb(...)` / `rgba(...)` | `CgUiQuad` | 8-hex form is CSS-standard `#RRGGBBAA` (alpha last), not the engine's internal `0xAARRGGBB` int packing |
| `image("path")` | `CgUiSprite`, unsliced | Optional trailing args, type-sniffed, order-independent: quoted `"x y w h"` crop rect, quoted `"refW refH"` texture-size-reference override, or a color literal (tint) |
| `sprite("path", "sx sy sw sh", "bl bt br bb")` | `CgUiSprite`, 9-slice | Optional 4th `"refW refH"` arg, same override as `image(...)` |
| `asset("ns:path", "element")` | `CgUiSprite`, fresh instance per lookup | Named 9-slice element from a pack at `assets/{ns}/ui/sprites/{path}.json`, via `CgUiSpriteRegistry`. The parsed pack JSON is cached, but each `get()` call rebuilds a new `CgUiSprite` from it (not a `.copy()` of a cached template) — safer against cross-call mutation. One pack file holds multiple named elements; each may override the pack's own `texture`/`textureSize`. On a missing pack/element, returns a visible fallback drawable rather than silently rendering nothing |
| `linear-gradient(direction?, stop, stop, …)` | `CgUiGradient` | CSS's: an `<angle>` in any unit, `to <side>` or `to <corner>` (resolved per box); a stop is a colour with an optional `%` position, and missing positions spread evenly. One draw per eight stops, **premultiplied** interpolation, dithered, and it masks itself under `border-radius` (`CornerRadiusAware`, so no `border-width` stroke). No colour hints, no `repeating-` |
| `glass(blur, tint)` / `glass(blur 12, tint …, saturation …, …)` | `CgUiGlass` | A backdrop material: captures what is behind the element, blurs, saturates and tints it. Keyword form takes any subset; the keys are in `TextureValue.parseGlass`. `CornerRadiusAware`, same gap |

`CssParsingUtil.splitTopLevelCommas` (paren-aware comma split) backs every multi-arg form here.

There is no `roundedrect(...)` background function — rounding/border is a separate, universal
wrapping layer (§7 below), applied on top of whatever `background:` resolves to, not a background
value type of its own.

---

## 7. Universal Border-Radius/Border-Width/Border-Color Layer

`CrystalGraphics/core/src/main/resources/assets/crystalgraphics/shaders/lib/sdf.glsl`,
`core/src/main/resources/assets/crystalgui/shaders/gui_rounded_rect.shader`,
`core/src/main/java/com/crystalgui/render/texture/CgUiRoundedRect.java`,
`core/src/main/java/com/crystalgui/style/property/visual/border/`, `UIElement.paintSelf`

`border-radius`/`border-width`/`border-color` apply on top of *whatever* `background:` produces —
matching real CSS (rounding/border is orthogonal to what the background *is*, not tied to one special
drawable). `UIElement.paintSelf` resolves all three once per paint; if any are set, it branches on the
resolved `background` drawable's concrete type: a flat color or a non-9-slice `CgUiSprite` gets wrapped
in a freshly-built `CgUiRoundedRect` (clipped + stroked by the shared SDF shader); a 9-slice sprite
falls through to the plain unclipped path (border-radius/border-width still resolve for hit-testing and
layout growth, just without visual clipping of the sprite — see the known gap in §9).

**An absent background is not a colour.** When `background` is `CgUiDrawable.EMPTY` the rounded layer
has nothing to fill with, and `resolveRoundedFill` returns `null` rather than inventing a fill.
`paintRoundedBackground` then makes the same three-way distinction the plain path makes: an explicit
`background-color` becomes the fill (with a white vertex tint, since the shader multiplies
`fillColor *= i.color` and tinting by the colour again would square it); a border with no fill gets a
transparent interior carrying the *border's* rgb at zero alpha (a mismatched rgb fringes along
`mix(_BorderColor, fillColor, innerCoverage)`); and an element with neither falls through and paints
nothing.

That `EMPTY → null` used to be `EMPTY → opaque white`, which was the bug behind "any `border-radius`
turns the whole UI white": most containers set no `background:` at all, so a single universal radius
painted a white slab over the root and everything structural under it. The correct guard already existed
in `paintSelf` but sat *after* the rounded branch's early return, so the rounded path never reached it.
Note `paintDefaultMaskShape` still falls back to opaque white on a `null` fill, and must — it builds a
**mask**, where white means "fully reveal".

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
codebase's existing SDF approximation style. `UIElement`'s Java-side hit-test (`isMouseOverElement`)
uses the identical technique against the same resolved per-corner values, so rendering and hit-testing
never disagree about the element's shape. `sdf_coverage` turns a signed distance into an antialiased
0–1 mask via `fwidth`.

> **`sdf_coverage` is wrapped in `#ifndef CG_VERTEX_STAGE`, and that guard is load-bearing.**
> `gui_rounded_rect.shader` includes `sdf.glsl` at *material* scope, and CrystalGraphics' compiler
> hoists every material-scope `#`-line into **both** generated stages — so without the guard,
> `fwidth`, a fragment-only derivative builtin, lands in the vertex shader. NVIDIA compiles that
> anyway. AMD refuses, and the whole material fails to compile: an AMD tester could not launch
> `cgui-gallery` at all while it ran flawlessly here. The three `sdf_rounded_box` overloads are pure
> maths and stay available to both stages; only the coverage helper is fragment-restricted.
>
> Two things keep it that way: `ShippedShaderStagePurityTest` (GL-free, fails on any machine if a
> fragment-only builtin becomes reachable in a generated vertex source) and the harness's
> `--mode=shader-compile-audit` (real driver, every shipped shader and keyword variant, one report).
> The full contract is in `CrystalGraphics/AGENTS.md` § *Stage defines*.

The shader is a genuine "canvas": interior filled by `_FillColor` or a sampled `_MainTex`
(`WITH_TEXTURE_FILL` keyword), an optional `_BorderColor` stroke band (`WITH_BORDER` keyword) along the
outer edge, both masked by the same distance field so corners clip fill and border consistently.

**`CgUiPaintContext.withMaterial(material, drawBody)`** — used because an SDF rect needs its own
shader/program, not the shared box-model batch. **`bind()` must run after `drawBody`, not before** —
`applyProperties(...)` is CPU-only (marks a dirty flag; the GPU upload only happens inside `bind()`'s
own dirty-check), and `drawBody` is exactly where the caller sets its per-instance properties. Binding
first uploads whatever was dirty from the *previous* draw call — one draw stale, invisible for a static
shape re-drawing identical values every frame, badly broken for two different instances alternating
every frame (a fixed bug from an earlier session).

**Transitions, not morphing**: `CgUiRoundedRect` is built fresh every frame by `paintSelf` from
whatever the currently-interpolated style values are — it is never itself held inside the `background`
cascade (there's no `roundedrect(...)` background value anymore), so `TransitionEngine` never
interpolates between two `CgUiRoundedRect` instances directly. Instead, each of the 8 radius longhands,
the border-width longhands, and border-color animate independently as ordinary scalar/color
`StyleProperty` transitions — `TextureProperty.interpolate` (for `background` itself) always falls
through to `CgUiCrossFade` now, since `background` can only ever hold a `CgUiQuad`/`CgUiSprite`.

---

## 8. Visual Layers (Opacity Isolation + Masking)

`opacity < 1` and `overflow: hidden` (when auto-detected to `OverflowClip.MASK` — see
`UIElement.resolveOverflowClip()`) both route through an offscreen "visual layer" — a screen-sized
FBO from a small pool `CgUiPaintContext` owns (`beginLayerFbo`/`endLayerFbo`/`blitLayer`/
`compositeMask`, `core/src/main/java/com/crystalgui/render/CgUiPaintContext.java`). Ordinary elements
(opacity 1, no mask) skip this entirely — same direct-draw path as always, zero overhead.

**Why an offscreen layer at all**: without one, overlapping translucent children blend against
whatever's already drawn one at a time, then each gets faded independently — the classic
double-blend seam at the overlap. Isolating the whole subtree in its own buffer first, then fading
the *result* as one unit, avoids that (the same reason real browsers isolate `opacity`-bearing
stacking contexts).

**Why the layer is screen-sized, not element-sized**: background/children/overlay all draw using
the same absolute screen coordinates (`runtimeCache.getX()/getY()`) they always do — no translation
math needed — because the layer FBO spans the whole screen and starts fully transparent; only the
element's own footprint ends up with real pixels in it. Matches LDLib2's own `PictureInPictureState`-based
visual-layers implementation (sibling checkout at `../LDLib2`, under
`src/main/java/.../gui/ui/rendering/`), which uses the same technique for the same reason on top of
Minecraft's `PictureInPictureRenderer`.

**`OverflowClip.MASK`** (`UIElement.drawSubtree` — reached via `overflow: hidden` auto-detecting
to mask, not an author-chosen `clip:` value anymore; see `UIElement.resolveOverflowClip()`)
composites a mask onto the subtree's own layer via
`CgBlendState.MASK_ALPHA_MULTIPLY` (`(ZERO, SRC_ALPHA)` blend func for both RGB and alpha) — **not**
a stencil test. The mask is rendered into its *own* offscreen layer first, then blended onto the
subtree layer, multiplying the subtree's existing color+alpha by the mask's alpha; wherever the
mask's alpha is 0, the subtree's output is zeroed too. This mirrors LDLib2's `VisualLayerPipRenderer`
exactly (`renderMaskAndComposite`) — no `CgStencilState`/stencil buffer involved anywhere.

**Default mask shape** (`UIElement.buildDefaultMask`) is the element's own resolved `CgUiRoundedRect`
shape (same radii/border-width resolution `paintRoundedBackground` already does) with the border
band's *color* forced to `#00000000` instead of its real color — since the shader already computes
`color = mix(borderColor, fillColor, innerCoverage)` then multiplies the whole shape by the outer
`coverage`, a transparent border color alone already zeroes alpha across the border band while
staying opaque across the inner region. No shader changes needed for this — the exact "border color
to `#00000000`" framing the feature was originally specified with.

**Ordering** (background → children → mask composite → overlay → outline → blit-with-opacity): the
mask composites *after* children but *before* overlay. It clips **only the children** — `paintSelf`
draws the background straight into `subtreeFbo` while the mask multiplies a separate nested
`childrenFbo`, so the background is never masked (see the explicit comment on `paintSelf`'s call in
`UIElement.drawSubtree`). Overlay and outline likewise draw over full, unclipped content — matching
how a 9-slice frame graphic typically sits on top of whatever it frames.

**Not built**: masking with a *custom* (non-self) drawable — today the mask is always the element's
own shape; a `mask-source:` (or similar) property to point at an arbitrary drawable is a natural
follow-up but wasn't requested for this round. Per-element FBO pooling is currently unbounded (grows
to the deepest nesting ever seen, never shrinks) — fine for typical UI depths, a real concern only if
something creates very deep transient nesting.

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
`UITransform` therefore stores `List<Op>` and `applyTo` walks it in order; each JOML call
post-multiplies, so declaration order *is* the composition order with no reversal.

**`transform-origin` is two real longhands** (`transform-origin-x`/`-y`, both `LengthPercent`), with
`transform-origin` as 1–2 value shorthand syntax over them — the same architecture as `outline-offset`.
Keywords (`left`/`center`/`right`/`top`/`bottom`) resolve to percentages, and the reversed keyword pair
(`top left`) is accepted as CSS allows.

**Hit-testing follows automatically and this is the load-bearing invariant.** `RuntimeCache.localToWorld`
and the paint `PoseStack` both call the *same* `UITransform.applyTo`. If they ever diverged, a click
would land somewhere other than what is drawn and nothing about the rendering would look wrong —
`UITransformTest` exists to pin exactly that.

Non-inheritable, matching CSS. It already reaches descendants through the matrix chain, and inheritance
here is pull-based — an inherited change does not fire the inheriting element's `StyleChangeListener`s,
which is the very mechanism that dirties the subtree's matrices.

**Set from Java** with `element.setTransform(UITransform…)` (sugar writing `transform` at `INLINE`
origin) or `style(s -> s.general(g -> g.transformOrigin(x, y)))`.

---

## 8c. `line-height: normal`, and why the caret ignores it

`line-height` takes CSS's `normal | <number>`, and **`normal` is the default**, as in CSS: the line box
comes from the font's own `ascender + descender + lineGap` via `CgFontFamily.getLayoutMetrics()`.

`normal` is carried as **`Float.NaN`** rather than a union value type. That keeps the property a plain
`Float`, so it still has a codec and inline `line-height` still crosses the wire — a union type would
return `null` from `StyleValueCodecs.forProperty` and make `InlineStyleCodec` throw. The interpolator is
guarded so a transition into or out of `normal` snaps instead of blending `NaN` through every frame.
`AutoFloatProperty` established the same idiom for `flex`/`aspect-rate`.

**The sentinel becomes pixels in exactly one place — `TextField.paintOverlay`.** Resolving it in
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

Both are only observable in `cgui-textfield`, which forces two rows into states that cannot coexist
through real input — one focused with a selection, one focused without and with the blink disabled for
a deterministic capture. Nothing else in the harness shows either, which is why both being 2px too tall
went unnoticed.

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
  paint layers (§5) or a real internal child with a fixed class, not generated content.
- **No `background-position`/`-size`/`-origin`** as independent properties — the analog is
  baked-in crop rects on `image()`/`sprite()` at parse time. **`overlay` and `outline` are not
  subject to this** (see §5): `overlay` has real `-origin`/`-fit`/`-position` longhands, and
  `outline` has `-offset`. `background` can't get equivalents without design work, because its rect
  doubles as `CgUiRoundedRect`'s `_BoxSize` *and* as the basis percentage `border-radius` resolves
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
  (`pushScissor` drives real `GL_SCISSOR_TEST` and survives into nested layer FBOs), so a positive
  `outline-offset` inside a scroll view gets cut — real CSS behaves the same way.
- **No `overlay-color`** — `paintOverlay`/`paintOutline` reset the ambient tint to white, so those
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
  font metrics instead, see §8c. Still missing: `text-align`; `text-shadow` parses/cascades but is a
  registered **no-op** (nothing renders a shadow yet — see its `TODO` in `StylePropertyRegistry`); and
  `UIText` measures at the font's own metrics rather than honouring `line-height` — though with
  `normal` as the default the two now agree unless a sheet says otherwise.
  Note `font-size` takes a bare number: `10`, not `10px` — its parser is `Float.parseFloat` and
  *throws* on a unit suffix, unlike `width`/`height`/`outline-offset`, which do accept `px`.
- **9-slice backgrounds can't be visually rounded/bordered** — `border-radius`/`border-width` still
  resolve for hit-testing and Taffy box growth when `background` is a 9-slice `CgUiSprite`, but
  `UIElement.paintSelf` falls through to the plain unclipped draw for that case rather than clipping
  the sprite's pixels to the SDF shape. 9-slice textures are typically pre-baked with their own rounded
  corners already, so this is a lower-priority gap than it might first appear.
- **SDF border stroke width is a single scalar**, even though real `border-width` is independently
  per-edge — a stylesheet with 4 different edge widths still grows the layout box correctly per edge,
  but the visual SDF stroke uses one representative value. True asymmetric per-edge SDF stroke
  rendering is deliberately deferred (not requested; orthogonal to the box-growth fix).
- **No true per-pixel texture blending** for texture↔texture `CgUiCrossFade`s — a dedicated 2-sampler
  pixel-blend shader, restricted to matching-geometry drawables, is deliberately deferred.

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
| `transform` value type | `core/src/main/java/com/crystalgui/ui/UITransform.java` (ordered `Op` list + `applyTo`) |
| `transform` parsing/property/origin shorthand | `core/src/main/java/com/crystalgui/style/property/visual/transform/` (`TransformValue`, `TransformProperty`, `TransformOriginShorthand`) |
| Shared CSS parsing helpers | `core/src/main/java/com/crystalgui/style/CssParsingUtil.java` (`splitTopLevelCommas`, `splitFunctionList`), `.../style/CssAngle.java` |
| Frame lifecycle | `core/src/main/java/com/crystalgui/ui/UIWindow.java` |
| Paint entry points | `core/src/main/java/com/crystalgui/ui/UIElement.java` (`paintSelf`/`paintOverlay`/`paintOutline`/`drawSubtree`) |
| Paint context | `core/src/main/java/com/crystalgui/render/CgUiPaintContext.java` |
| Visual layers (opacity isolation + masking) | `CgUiPaintContext` (`beginLayerFbo`/`endLayerFbo`/`blitLayer`/`compositeMask`), `UIElement.drawSubtree`/`buildDefaultMask`, `CrystalGraphics/.../gl/framebuffer/CgFrameBuffer.java` (`createOwned`), `CrystalGraphics/.../api/state/CgBlendState.java` (`MASK_ALPHA_MULTIPLY`) |
| Drawables | `core/src/main/java/com/crystalgui/render/texture/` |
| `background:` parsing | `core/src/main/java/com/crystalgui/style/property/visual/texture/TextureValue.java` |
| SDF shader lib | `CrystalGraphics/core/src/main/resources/assets/crystalgraphics/shaders/lib/sdf.glsl` |
| SDF material | `core/src/main/resources/assets/crystalgui/shaders/gui_rounded_rect.shader` |
| Named 9-slice assets | `core/src/main/java/com/crystalgui/render/texture/asset/CgUiSpriteRegistry.java` |
| Demo scenes | `gl-debug-harness/src/main/java/io/github/somehussar/crystalgraphics/harness/scene/ui/` — `CgUiStylingScene` (selectors/cascade/transitions), `CgUiVisualLayersScene` (opacity isolation + masking), `CgUiNineSliceScene` (tiling modes, CPU vs SDF path), `CgUiOreThemeScene` (the theme + forced-state matrices), `CgUiTextFieldScene` (the only visible caret), `CgUiGalleryScene` (everything, with a live theme toggle). Full list in `CGUI_WIDGETS.md`. |
