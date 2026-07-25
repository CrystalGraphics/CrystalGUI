# CrystalGUI Style & Render Pipeline

> Supplements `CRYSTALGUI_OVERHAUL_V4.md` — that document covers the render-architecture *rewrite
> decision* (CrystalGraphics owns rendering infrastructure, CrystalGUI is a thin paint surface). This
> document covers how the style/cascade/paint pipeline actually works end to end, as built on top of
> that decision. Ground truth as of the session that built the stylesheet/transition/SDF system —
> re-verify against the code before trusting a specific line number.

---

## 1. The Cascade

`core/src/main/java/com/crystalgui/style/`

Every element owns one `ElementStyle`, which holds `candidates: Map<StyleProperty<?>, List<StyleSlot<?>>>`
— every value ever set for that property, from every source, tagged with where it came from.

**`StyleOrigin`** (priority, low → high): `DEFAULT(0) < STYLESHEET(2) < INLINE(3) < IMPORTANT(4) <
ANIMATION(5)`. `ANIMATION` outranks everything deliberately — an in-flight transition must visually
win regardless of what set the underlying target value.

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
combinators, `@media`/`@import`, external `.css` files, CSS custom properties (`--var`/`var()`).

`StyleSheet.parse(String)` regex-parses declarations, buckets rules by id/class/type/universal for fast
candidate lookup, and `StyleEngine.rematch(element)` re-evaluates only the buckets relevant to that
element on every dirty pass.

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
- `color` — inheritable, reserved for text once text elements exist; must never tint `background`.

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
visual-layers implementation (`research_repos/LDLib2/.../gui/ui/rendering/`), which uses the same
technique for the same reason on top of Minecraft's `PictureInPictureRenderer`.

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

**Ordering** (background → children → mask composite → overlay → blit-with-opacity): the mask
composites *after* children (so it clips both background and children together) but *before*
overlay, so the overlay always draws over full, unclipped content — matching how a 9-slice frame
graphic typically sits on top of whatever it frames.

**Not built**: masking with a *custom* (non-self) drawable — today the mask is always the element's
own shape; a `mask-source:` (or similar) property to point at an arbitrary drawable is a natural
follow-up but wasn't requested for this round. Per-element FBO pooling is currently unbounded (grows
to the deepest nesting ever seen, never shrinks) — fine for typical UI depths, a real concern only if
something creates very deep transient nesting.

---

## 9. Known Gaps vs. the Web

- **No general `opacity` property.** `_LayerOpacity` only exists as cross-fade/morph plumbing, not a
  real cascading `opacity` on arbitrary elements/subtrees.
- **No external stylesheets** — `StyleSheet.parse(String)` only; no file loading, `@import`, media
  queries, or CSS custom properties (`--var`/`var()`).
- **No `:nth-child`, attribute selectors, or `~`/`+` sibling combinators** — only `>` and descendant.
- **No `background-position`/`-size`/`-repeat`** as independent, cascadable/animatable properties —
  the engine's analog is baked-in crop rects on `image()`/`sprite()` at parse time.
- **No text styling** — `color` is wired and inheritable in anticipation, but no text elements exist
  yet, so `font-*`, `text-align`, etc. don't either.
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
| Selectors | `core/src/main/java/com/crystalgui/style/selector/` |
| Stylesheets | `core/src/main/java/com/crystalgui/style/sheet/`, `.../style/StyleEngine.java` |
| Transitions | `core/src/main/java/com/crystalgui/style/transition/` |
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
| Demo scene | `gl-debug-harness/src/main/java/io/github/somehussar/crystalgraphics/harness/scene/ui/CgUiStylingScene.java` |
