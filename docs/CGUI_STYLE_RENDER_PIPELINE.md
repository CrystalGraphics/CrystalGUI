# CrystalGUI Style & Render Pipeline

> **Current-state reference** for the style/cascade/paint pipeline, end to end.
>
> Supplements `CRYSTALGUI_OVERHAUL_V4.md`, which is **historical**: it records the *decision* that
> CrystalGraphics owns rendering infrastructure and CrystalGUI is a thin paint surface — but the
> render-queue design it goes on to specify was abandoned in favour of immediate-mode. Read that one
> for the *why*; this one for the *what*.
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

**Inheritance** (`color` only, so far) is lazy/pull-based: `ElementStyle.getComputed` walks to the
parent's `getComputed` when there's no local candidate and the property `isInheritable()`. Not
push-invalidated — an inherited value changing does **not** fire the inheriting element's own
`StyleChangeListener`s or make it transition-eligible, only a genuine local candidate change does that.

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

**Two ways in.** `StyleSheet.parse(String)` for inline CSS text; `StyleSheetRegistry.of("crystalgui:ore")`
for an external file at `assets/{namespace}/ui/styles/{path}.css`. The registry is lazy and
`ConcurrentHashMap`-cached, so repeated calls return the *same* `StyleSheet` instance — which is what
makes `StyleEngine.removeStylesheet` usable for a runtime theme switch. A missing file logs a warning and
yields an empty sheet, and is deliberately **not** cached, so it is retried once the owning resource pack
loads. Two sheets ship today: `default.css` (user-agent) and `ore.css` (theme).

`StyleSheet.parse` buckets rules by id/class/type/universal for fast candidate lookup and delegates
declaration-level parsing to **`sheet/DeclarationParser`**, which also implements CSS custom properties —
`collectVariables` gathers `--name: value` declarations and `substituteVars` resolves `var(--name)`
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
— paren-aware, so `cubic-bezier(a,b,c,d)`'s internal commas don't split entries).

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
  styleEngine.calculateStyle(deltaSeconds)   // drainDirtyMatch() (selector rematch) + transitionEngine.tick()
  calculateLayout()                          // Taffy computeLayout(), while dirty
  paintContext.beginFrame(screenW, screenH)  // GL state save, ortho projection, bind gui_quad material
    ui.rootElement.drawSubtree(paintContext) // paintSelf → children (z-sorted) → paintOverlay, per element
  paintContext.endFrame()                    // GL state restore
  inputHandler.beginFrame()/endFrame()       // hover cache invalidation + hit-test + event dispatch
```

Style resolution happens **before** layout on purpose — a stylesheet/transition change to a layout
property (width, padding, ...) must be visible to Taffy in the same frame it changes.

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
  a selected tab sit raised, so `tab:checked:focus { outline-offset: -2px 0 0 0; }` tightens only that
  edge. On the SDF path the corner-radius expansion takes one amount per axis, so asymmetric offsets
  use the mean of the two edges on that axis — a rounded corner joining two differently-offset edges
  has no single correct radius, and the 9-slice case that motivates per-edge never reaches that branch.

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
  percentage radii into a visibly over-curved ring.

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
layout growth, just without visual clipping of the sprite — see the known gap in §8).

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

## 9. Known Gaps vs. the Web

- **No `@import` or media queries.** External stylesheets *are* supported now —
  `StyleSheetRegistry.of("ns:path")` loads `assets/{ns}/ui/styles/{path}.css` through `CgIO`, so a
  resource pack ships a theme just by placing a file at that path. CSS custom properties
  (`--var`/`var()`) are implemented too.
- **No `:nth-child`, attribute selectors, or `~`/`+` sibling combinators** — only `>` and descendant.
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
  specificity. A theme still opts into the focus *ring* explicitly (see `ore.css`), but the mechanism
  it would need is no longer missing.
- **Partial text styling** — `color`, `font-size`, `font-family`, `line-height`, `caret-width` and
  `selection-color` are wired and inheritable. `UIText` consumes the first three; `TextField` consumes
  all six (`line-height` drives its caret height, selection rect and vertical centring). Still missing:
  `text-align`; `text-shadow` parses/cascades but is a registered **no-op** (nothing renders a shadow
  yet — see its `TODO` in `StylePropertyRegistry`); `line-height` takes only a unitless multiplier, not
  CSS's font-derived `normal`, which would need a `normal | <number>` union value type and a codec for
  it; and `UIText` still wraps at the font's own metrics rather than honouring `line-height`.
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
| Stylesheets | `core/src/main/java/com/crystalgui/style/sheet/` (`StyleSheet`, `StyleRule`, `DeclarationParser`, `StyleSheetRegistry`), `.../style/StyleEngine.java` |
| Shipped stylesheets | `core/src/main/resources/assets/crystalgui/ui/styles/default.css` (user-agent), `ore.css` (theme) |
| Transitions / easing | `core/src/main/java/com/crystalgui/style/transition/`, `.../style/easing/` |
| Property registry | `core/src/main/java/com/crystalgui/style/property/StylePropertyRegistry.java` |
| Box-model shorthand expansion | `core/src/main/java/com/crystalgui/style/property/layout/BoxEdgeShorthands.java` |
| Border-radius shorthand expansion + value type | `core/src/main/java/com/crystalgui/style/property/visual/border/` (`BorderRadiusShorthand`, `BorderRadiusProperties`, `LengthPercent`) |
| Frame lifecycle | `core/src/main/java/com/crystalgui/ui/UIWindow.java` |
| Paint entry points | `core/src/main/java/com/crystalgui/ui/UIElement.java` (`paintSelf`/`paintOverlay`/`drawSubtree`) |
| Paint context | `core/src/main/java/com/crystalgui/render/CgUiPaintContext.java` |
| Visual layers (opacity isolation + masking) | `CgUiPaintContext` (`beginLayerFbo`/`endLayerFbo`/`blitLayer`/`compositeMask`), `UIElement.drawSubtree`/`buildDefaultMask`, `CrystalGraphics/.../gl/framebuffer/CgFrameBuffer.java` (`createOwned`), `CrystalGraphics/.../api/state/CgBlendState.java` (`MASK_ALPHA_MULTIPLY`) |
| Drawables | `core/src/main/java/com/crystalgui/render/texture/` |
| `background:` parsing | `core/src/main/java/com/crystalgui/style/property/visual/texture/TextureValue.java` |
| SDF shader lib | `CrystalGraphics/core/src/main/resources/assets/crystalgraphics/shaders/lib/sdf.glsl` |
| SDF material | `core/src/main/resources/assets/crystalgui/shaders/gui_rounded_rect.shader` |
| Named 9-slice assets | `core/src/main/java/com/crystalgui/render/texture/asset/CgUiSpriteRegistry.java` |
| Demo scenes | `gl-debug-harness/src/main/java/io/github/somehussar/crystalgraphics/harness/scene/ui/` — `CgUiStylingScene` (selectors/cascade/transitions), `CgUiVisualLayersScene` (opacity isolation + masking), `CgUiNineSliceScene` (tiling modes, CPU vs SDF path), `CgUiOreThemeScene` (the theme + forced-state matrices), `CgUiGalleryScene` (everything, with a live theme toggle). Full list in `CGUI_WIDGETS.md`. |
