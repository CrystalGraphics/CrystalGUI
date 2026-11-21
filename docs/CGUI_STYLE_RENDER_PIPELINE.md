# CrystalGUI Style & Render Pipeline

> Reference for the cascade and the paint path. CrystalGraphics owns the rendering; CrystalGUI records onto its
> canvas. Companions: `CGUI_WIDGETS.md` (widgets and their CSS surface), `CGUI_SERVER_AND_SERIALIZATION.md` (how
> styles travel).

## 1. The Cascade

`style/`. Every element owns one `ElementStyle`: `candidates`, every value set for each property, tagged with where it
came from.

**`StyleOrigin`** (low → high): `DEFAULT(0) < USER_AGENT(1) < STYLESHEET(2) < INLINE(3) < IMPORTANT(4) < ANIMATION(5)`.

- **`ANIMATION` outranks everything**, `IMPORTANT` included: an in-flight transition must win (CSS Cascade 4/5).
- **`USER_AGENT` sits below author sheets**: `StyleSheet.DEFAULT` loads there, so an author rule beats it at any
  specificity.

**`StyleSlot<T>`**: `record(property, origin, specificity, sourceOrder, value)`, compared by origin, then
specificity, then source order.

**Resolution** (`ElementStyle.resolveTouched`/`resolveOne`) has two phases:

1. Recompute each touched property's `realSlots` (winner **excluding** `ANIMATION`) and `computedSlots` (including
   it: what is displayed).
2. Diff old against new `realSlots` and notify listeners, or offer the change to `TransitionEngine` first. Diffing
   `computedSlots` would compare a transition with itself and defeat retargeting.

A reverted property with no candidate left offers `p.initialValue`, not `null`, to the transition engine, so the
revert animates.

**Inheritance** is pulled: `getComputed` walks to the parent when there is no local candidate and the property
`isInheritable()`. An inherited change fires no listener on the child and starts no transition. Inheritable:
`color`, `font-size`, `font-family`, `line-height`, `caret-width`, `selection-color`, `text-shadow`, `text-offset`,
the stroke and fill colours.

`moveInlineAsDefault()` moves every `INLINE` candidate to `DEFAULT` at once: a widget writes its baseline styling
with ordinary calls, then calls this at the end of construction so any sheet overrides it.

## 2. Selectors & Stylesheets

`style/selector/`, `style/sheet/`

**Supported**: type, class, id, universal and pseudo-class selectors, `>` and descendant combinators, selector lists,
`!important`. Specificity: id 100, class/pseudo-class 10, type 1, universal 0. **Not supported** (§9):
`:nth-child`, attribute selectors, `~`/`+`, `@media`, `@import`.

### `::part()` — reaching into a widget

A widget's structure lives in a shadow tree; `::part(name)` selects a real element inside one.

```css
checkbox::part(mark)          { background: #1E1E1E; }
checkbox:checked::part(mark)  { overlay: shape("checkmark"); }
::part(v-scroller)            { width: 6px; }        /* hostless: whoever exposes it */
```

- **A pseudo-class before `::part()` describes the host; after it, the part.** `dropdown::part(menu):open` asks
  whether the menu is open.
- **A `::part` rule is indexed under the host**, and a host's re-match re-matches its exposed parts.
- **A hostless `::part(x)` is legal**: whoever exposes it.

What `::part()` cannot express:

| Wanted | Why it cannot be written |
|---|---|
| `::part(a)::part(b)` | a part is a leaf |
| `dropdown::part(menu) menuitem` | nothing descends from a part |
| `colorselector .__channel-row__ slider::part(thumb)` | a nested widget's part is in its own shadow tree |

A sheet scoped to the composite's shadow root (below) answers these; `exportparts` does not exist. A `::part` rule
that matches nothing fails silently.

### `@scope` — a sheet that only applies inside one subtree

`StyleEngine.addStylesheet(sheet, root)`: only nodes at or under `root` match, and `StyleSlot.proximity` ranks
between specificity and source order. **Many roots sharing sheets take a `ScopeGroup`**: every install re-matches
the window.

```java
ScopeGroup cards = new ScopeGroup();
window.styles().addStylesheet(sheet, cards);
cards.add(card.attachShadow());   // before its content is attached
```

`StyleEngine.sheetsRevision()` moves on every install and removal.

**Pseudo-class names resolve through `PseudoClasses.lookup(String)`** (case-folded, `-` to `_`). An unknown
pseudo-class throws out of `StyleSheet.parse` and takes the **whole sheet** with it.

### `:focus` vs `:focus-visible`

| | true when |
|---|---|
| `:focus` | the element holds focus |
| `:focus-visible` | focus arrived by keyboard or programmatically, not a pointer click, unless the element takes text input |

`Input` decides through `ui/input/FocusSource` (`KEYBOARD` / `POINTER` / `PROGRAMMATIC`) and
`UINode.consumesTextInput()`. The user-agent ring hangs off `:focus-visible`; a theme's opt-out uses `:focus`.

### Loading, ordering, shorthands, lengths

- `StyleSheet.parse(String)` for inline text; `StyleSheetRegistry.of("crystalgui:ore")` for
  `assets/{ns}/ui/styles/{path}.css`, cached so the same instance comes back (what makes `removeStylesheet` work). A
  missing file logs, yields an empty sheet, and is not cached, so it is retried after its resource pack loads.
- `StyleSheet.parse` buckets rules by id/class/type/universal; `sheet/DeclarationParser` parses declarations and
  custom properties (`collectVariables`, `substituteVariables`). `StyleEngine.rematch(element)` re-evaluates only
  relevant buckets.
- **Sheet order is registration order** (`SHEET_ORDER_STRIDE`): a re-added sheet goes to the top.
- **Shorthands expand at parse time** (`Shorthands`): `margin`, `padding`, `border-width`, `border-style`,
  `border-color`, `margin-inline`/`-block`, `padding-inline`/`-block`, `border`, `border-<side>`, `border-radius`,
  `border-<corner>-radius`, `outline`, `outline-offset`, `background`, `overflow`, `flex`, `transform-origin`,
  `text-offset`, `text-stroke`. A shorthand sets every longhand it names, omitted parts at their initial value, and a
  later longhand in the same rule outranks it (`DECLARATION_ORDER_MULTIPLIER`). `transition: margin` animates all
  four edges.
- A longhand CSS lacks (`text-offset-x`, `transform-origin-y`, `border-top-left-radius-x`, `outline-offset-top`) is
  `setAuthoredThrough(name)`: only the shorthand may be written.
- **Lengths carry a unit** (`px`, `%`, `em`); only `0` is bare. `gap: 6` is dropped with a warning
  (`ShippedSheetsParseTest`).

## 3. Transitions

`style/transition/`. `transition: <prop|all> <duration>[ms|s] [<delay>] [<timing-function>]`, comma-separated;
`TransitionSpec.parse` splits on top-level commas only.

`TransitionEngine.tryStart(element, property, from, to)` gets first refusal on every eligible cascade change. It
shadows the value with an `ANIMATION` slot, ticked in `StyleEngine.calculateStyle` and cleared when done.

- **Interrupt and retarget**: a new target starts from where the transition currently is.
- **Null guard**: `tryStart` declines, and cancels a stale transition, when an endpoint is `null`.

## 4. Frame Lifecycle

The frame is `UIDocument.frame(delta, w, h)`: jobs, input begin, animation, style, layout, settle, input end
(`AGENTS.md` § *Frame lifecycle*). Style runs before layout so a style change to a layout property reaches Taffy the
same frame; matching runs only inside `calculateStyle`. `update(w, h)` runs style then layout with no paint, for
headless tests; `layout(w, h)` is the box tree alone.

## 5. Images & Compositing Channels

`render/image/`, `ui/box/BoxPainter.java`. `background`, `overlay`, `mask` and `outline` hold a **`CgUiImage`**:
CSS's `<image>`, immutable and equal by value: a colour, a gradient, a grid, a picture (`image`/`sprite`), a sheet
entry (`asset`), an icon, a mark (`shape`), a painter (`paint(name)`), a cross-fade, or a stack. **`CgUiImagePainter`
is the one place an image becomes canvas draws** (`render/CLAUDE.md` § *Images*).

### A declaration can hold several images

A value is a comma-separated list of layers, as CSS writes a background:

```css
background: grid(16, #6EDCD024), shape("checkmark"), linear-gradient(to bottom, #3574F0FF, #2E436EFF);
```

- **The first layer is the top one.** The split is paren-aware; one unreadable layer fails the whole declaration.
- **Every layer is clipped to the element's corners, and the border is drawn once over them.** Per-layer
  `background-size`/`-position`/`-origin` do not exist yet (`plan/crystalgui/style-overhaul/css-background-layers.md`).
- Copy Attributes (`ImageParts`) offers one part per layer, filed under its kind.

**Two compositing channels, kept separate:**

| Channel | Set via | Meaning |
|---|---|---|
| **Tint** | `background-tint`, `overlay-tint`, or `color` for a mark or a `currentColor` icon | multiplies the image's own colours |
| **Layer opacity** | `canvas.save().opacity(a)` or `canvas.layer(...).opacity(a)` | the whole draw's compositing weight |

A cross-fade draws each side under its own opacity, never by scaling the tint's alpha.

**`background` is CSS's shorthand** over `background-image` (`ImageProperty`), `background-color` (initially
transparent, painted **under** the image) and `background-tint` (ours, initially white, multiplying the image's
colours; Ore's pressed buttons are their sprite under `background-tint: #DDDDDD`). `color` is inheritable, and is
what `currentcolor` resolves to.

### 9-slice tiling

A `sprite(...)` with a non-zero border is a 9-slice. Regions stretch by default; CSS `border-image-repeat`'s modes are
a trailing keyword (one value, or two per axis). Corners never tile.

| mode | behaviour |
|---|---|
| `stretch` | one copy across the span (default) |
| `repeat` | whole tiles at natural size; the last is clipped |
| `round` | tile size adjusted so a whole number fits |
| `space` | whole tiles, the leftover split into gaps |

```css
background: sprite("tex.png", "0 0 16 16", "4 4 4 4", "round");        /* both axes */
background: sprite("tex.png", "0 0 16 16", "4 4 4 4", "repeat space"); /* x, then y */
```

- Trailing arguments are type-sniffed and order-independent (the `"refW refH"` size reference and the keyword).
- Sprite-pack JSON adds Unity's `fillCenter` (default `true`) and `borderScale` (Pixels Per Unit Multiplier, default
  `1`).
- One renderer: the picture's `CgBrush` carries a `CgNineSlice` and a `CgTiling`, laid out per pixel by the canvas,
  with or without `border-radius`. `space` with no room for a tile falls back to `stretch`.
- **Textures resolve lazily**, nearest-sampled, the first time a value draws: style values are computed with no GPU.

### The three image layers

All `ImageProperty`, none costing a layout node:

| Layer | Painted | Geometry longhands |
|---|---|---|
| `background` | before children | none (§9) |
| `overlay` | after children | `overlay-origin`, `overlay-size`, `overlay-position`, `overlay-tint` |
| `outline` | last | `outline-offset`, `outline-width`, `outline-color` |

- **`overlay-origin`** (`border-box`\|`padding-box`\|`content-box`): CSS `background-origin`.
- **`overlay-size`** (`fill`\|`contain`\|`cover`\|`none`): CSS `object-fit`. Needs the natural size
  (`CgUiImagePainter.intrinsicWidth/Height`, `-1` for none); every mode degrades to `fill` without one.
- **`overlay-position`** (nine keywords, default `center`): CSS `object-position`.
- **`overlay-tint`**: multiplies an icon's or picture's own colours; transitions as a colour, with no re-raster.

  ```css
  taskbar .__start-button__::part(pre-icon)       { transition: overlay-tint 140ms ease-out; }
  taskbar .__start-button__:hover::part(pre-icon) { overlay-tint: #D4E2FF; }
  ```
- **`outline`**: a ring above everything at the border box grown by `outline-offset`, without touching layout (unlike
  `border-width`). `outline-offset` takes 1–4 values, clockwise from the top: a 9-slice ring's transparent padding
  need not be symmetric (`tab:checked:focus-visible { outline-offset: -2px 0 0 0; }`). Two forms, the image winning:
  1. **Image**: `outline: asset("crystalgui:ore", "focus-ring")`, a 9-slice ring.
  2. **Stroke**: `outline: 2px #4488ff`: a canvas box with a transparent fill and a border, following
     `border-radius`. The fill is the outline's rgb at zero alpha, or the inner edge fringes dark.

  `OutlineShorthand` dispatches on shape: a non-colour function → the image; `none` → `outline-width: 0`; otherwise
  lengths and colours in any order. A bare colour means `outline-color`. Radii resolve against the element's own box,
  then expand (`CornerRadii.expand`), so an element's radius shapes its focus ring even with no background.

The fit maths is `CgUiImagePainter.fit(...)`.

## 6. `background:` Grammar Reference

Every form is an explicit function; an unrecognised value parses to `null`. `CssParsingUtil.splitTopLevelCommas`
backs every multi-argument form.

| Form | Produces | Notes |
|---|---|---|
| `#RRGGBB` / `#RGB` / `#RRGGBBAA` / `rgb(...)` / `rgba(...)` | `CgUiImage.Color` | 8-digit hex is `#RRGGBBAA`, alpha last; the engine's int is `0xAARRGGBB` |
| `image("path")` | `CgUiImage.Picture`, unsliced | optional trailing args, any order: `"x y w h"` crop, `"refW refH"` size reference, a colour (tint) |
| `sprite("path", "sx sy sw sh", "bl bt br bb")` | `CgUiImage.Picture`, 9-slice | optional `"refW refH"` |
| `asset("ns:path", "element")` | `CgUiImage.Sheet` | a named 9-slice element of `assets/{ns}/ui/sprites/{path}.json`, drawn by CrystalGraphics' `CgSpriteSheet` brush, nearest-sampled. A missing sheet or element is logged and draws nothing. F3+T re-reads |
| `linear-gradient(direction?, stop, …)` | `CgUiImage.Gradient` | an `<angle>`, `to <side>` or `to <corner>`; stops with optional `%`, missing ones spread evenly. Premultiplied, dithered, clipped to the corners, under the border. No colour hints, no `repeating-` |
| `radial-gradient(shape? size? (at position)?, stop, …)` | `CgUiImage.Gradient` | `circle` or `ellipse` (default); an extent keyword (default `farthest-corner`) or one or two lengths (a circle's radius is never `%`); a position, centred by default |
| `icon("ns:name", #tint?, monochrome?)` | `CgUiImage.Icon` | light or dark drawing chosen as it draws; untinted, it follows `color` |
| `shape("checkmark")` | `CgUiImage.Mark` | one of `CgUiMark`'s sixteen marks, in `color` |
| `grid(cell, #colour, width?)` | `CgUiImage.Grid` | lines every `cell` px (`cell` or `cellX cellY`) |
| `paint(ns:name)` | `CgUiImage.Painted` | a painter registered with `CgUiPainter.register` (the CSS Paint API's spelling) |
| `cross-fade(a p%, b)` | `CgUiImage.CrossFade` | what a transition between unlike images writes |

**Glass is `backdrop-filter`**: `backdrop-filter: blur(8px) saturate(1.2) tint(#1C1D21CC)` is a `CgBackdropFilter`
painted **under** the element's background (`NONE` when unset; `blur()` and `saturate()` are CSS's, the other twelve
functions ours, `BackdropFilterValue`). **An opaque `background` hides it completely**: clear the background or make it
translucent.

Rounding and the border apply to whatever `background:` resolves to (§7); there is no rounded-rectangle background
function.

## 7. Universal Border-Radius/Border-Width/Border-Color Layer

CrystalGraphics' `shaders/lib/sdf.glsl` and `shaders/ui/primitives/Element.primitive`; `ui/box/BoxPainter.java`,
`render/image/`, `style/property/visual/border/`.

`border-radius`/`border-width`/`border-color` apply to whatever `background:` produces. `BoxPainter` resolves the
corners (kept on the `Box` while they hold) and the four borders and hands both to `CgUiImagePainter`: a fill,
gradient, grid or picture is one canvas box with corners and border; an icon, mark or painter draws and the border
goes over it; a stack draws each layer clipped to the corners, then the border once.

**An absent background is not a colour**: a border with no background draws alone, its middle transparent in the
border's rgb; an element with neither paints nothing.

### Theme anti-bleed

The user-agent sheet paints flat colours so a themeless UI works; anything it sets and a theme does not override stays
under the theme's art. Ore's policy, one labelled block at the top of `ore.css`: **the only user-agent declaration
allowed to reach a themed element is `:focus-visible`'s outline**; the rest is neutralised per property.

- **`border-width` is not just paint**: it feeds Taffy and moves the element onto the rounded-rect path, where the
  border replaces a sprite's outer pixels and drops its tint.
- **Reset element-specific, never `*`**, which also squashes values a consumer set.
- **Generic helper classes are not the theme's** (`.label` is exempt).

**Rounding is applied per widget, never on `*`**: a `*` radius puts every clipping element on the mask path (§8) for
corners nobody sees. `ore.css` resets it only on the widgets it has sprites for.

`border-radius` is elliptical per corner: `border-radius: <h-list> [ / <v-list> ]`, expanded at parse time into eight
longhands (`BorderRadiusProperties`, `BorderRadiusShorthand`); one corner is `border-top-left-radius: h [v]`.
Percentages resolve against the element's own width (rx) and height (ry) at paint and hit-test time. The per-edge
`border-width-*` longhands come from Taffy's resolved `border()`, so paint never disagrees with layout. The SDF stroke
width is one scalar: per-edge visual stroke widths are not drawn.

`sdf.glsl` has three `sdf_rounded_box` overloads: uniform radius, per-corner circular (`vec4 radii`), per-corner
elliptical (`vec4 radiiX, vec4 radiiY`), all TL/TR/BR/BL, in Y-down local space. The elliptical one is approximate
(exact when rx == ry). `UINode`'s hit test (`isMouseOverElement`) uses the same technique on the same values.
`sdf_coverage` turns a distance into an antialiased mask through `fwidth`.

> **`sdf_coverage` is guarded out of the vertex and compute stages**, since the compiler hoists the primitive's
> declarations into both: `fwidth` in a vertex shader compiles on NVIDIA and fails on AMD.
> `ShippedShaderStagePurityTest` and the harness's `--mode=shader-compile-audit` keep it so
> (`CrystalGraphics/docs/SHADERS.md` § *Stage defines*).

The element shader fills with `_FillColor` or `_MainTex` (`WITH_TEXTURE_FILL`) and strokes `_BorderColor`
(`WITH_BORDER`) along the outer edge, both masked by one distance field.

**A painter with a shader of its own draws `ctx.quad(material)`** after setting its properties: the quad takes the
values as it is submitted (`CGUI_INVARIANTS.md`).

**Transitions, not morphing**: each radius longhand, border width and border colour animates as an ordinary property.
`background` interpolates through `CgUiImage.interpolate`: two colours mix, two gradients of one shape slide, anything
else cross-fades.

## 8. Visual Layers (Opacity Isolation + Masking)

`opacity < 1` over children that can overlap, and a `mask`, paint into a **group layer**: the canvas's
`layer(x, y, w, h)` (CrystalGraphics `api.canvas`, `CrystalGraphics/docs/UI.md`), opened by `BoxPainter`. An ordinary
element skips it, as does a rounded `overflow: hidden`, which is a clip.

```java
try (CgCanvasLayer layer = canvas.layer(box.inkX0(), box.inkY0(), inkW, inkH)) {
    layer.opacity(0.6f);                 // the subtree composited as one picture
    paintChildren(...);
}
```

- **A layer is the size of what goes in it**: the subtree's ink bounds, cut to the clip and grown by a filter's
  reach; a transient texture for the frame.
- **Ink bounds** are Blink's visual overflow: `Box.inkX0..inkY1`, composed in `BoxTree` from each border box grown by
  its outline, by `UIElement.inkOverflow()`, and by its children, truncated where `overflow` clips. **A widget that
  paints outside its box without declaring it is clipped inside a layer.**
- **Two layers are never opened**: a mask over a childless box (`masks-elided`), and an `opacity` over a box painting
  at most one primitive, folded into the draw (`layers-elided`, Skia's rule; refused for a node that overrides a
  paint hook).
- **A layer lives one frame; what is kept is a surface**: a box the compositor moves (a window, a pointer follower, a
  compositor animation) keeps a `CgKeptLayer`, repainted only where something changed (`render/CLAUDE.md`). A layer
  inside a surface places its composite every walk (`Box.layerPlacement`). `UIElement.repaint()` is the door for a
  picture that changes without a box moving.
- **`clip-path: inset()` clips the box itself** (`BoxPainter.clipPath`), corners `round <radius>`, before anything of
  the box paints; an empty inset draws nothing. It eases between insets (`ClipPathProperty`): a flyout slides out of
  an edge as `translate(0, 50%)` with `inset(0 0 50% 0)`, both easing to zero. Paint only; hit testing sees the whole
  box.
- **A rounded `overflow: hidden` is a clip, not a layer**: children clipped to the padding box with the inner
  corners, whatever the background (alpha masking is `mask`'s job). The canvas's `clip(x, y, w, h, radii)`, exact
  under any transform; clips nest to `CgClipTree.MAX_DEPTH` (4), then a path mask.
- **A subtree wholly outside the clip is not walked** (`CgUiPaintContext.outsideClip`, Blink's cull rect).
  `-Dcrystalgui.paint.cull=false` paints everything.
- **`mask`** clips the children to the box's shape, then masks them through a layer: a colour by its alpha, a picture
  or sheet entry by its texels' alpha. A mask revealing nothing reveals the whole shape. It masks **only the
  children**; background, overlay and outline draw unmasked.
- **Order**: background → children → overlay → outline, all inside the layer when there is one, so the outline fades
  with the box.

## 8b. `transform` / `transform-origin`

A paint-time affine over an element and its subtree, on top of layout. **Taffy never sees it**, so a transform
reflows nothing: the right tool for a zoomable canvas.

```css
transform: translate(10px, 5px) scale(2) rotate(45deg);
transform-origin: left top;              /* default: 50% 50% */
transition: transform 160ms ease;        /* interpolatable */
```

- Functions: `translate`/`translateX`/`translateY` (lengths or percentages of the element's own box),
  `scale`/`scaleX`/`scaleY`, `rotate`, `skew`/`skewX`/`skewY`, `none`. Angles take `deg`/`rad`/`turn`/`grad`; a
  unitless non-zero angle is rejected.
- **The value is an ordered function list** (`Transform`, `List<Op>`): `translate(10px) scale(2)` and
  `scale(2) translate(10px)` differ. `applyTo` walks it in order.
- **`transform-origin`** is a 1–2 value shorthand over `transform-origin-x`/`-y`; keywords resolve to percentages,
  and `top left` is accepted.
- **Hit testing follows**: `Box.localToWorld` and paint use the same `Transform.applyTo`, and the matrices come from
  layout, so a click is right before any paint (`HitTestBeforePaintTest`).
- Not inheritable; it reaches descendants through the matrix chain.
- From Java: `element.setTransform(Transform…)` (INLINE) or `style(s -> s.general(g -> g.transformOrigin(x, y)))`.

## 8c. `line-height: normal`, and why the caret ignores it

`line-height` takes `normal | <number>`, default `normal`: the font's `ascender + descender + lineGap`
(`CgFontFamily.getLayoutMetrics()`).

- `normal` is carried as **`Float.NaN`**, so the property stays a `Float` with a codec; the interpolator snaps into and
  out of it.
- **It becomes pixels only in `TextField.paintDecoration`**: resolving it in the cascade would need fonts on a
  dedicated server.
- **The caret and selection band are `ascender + descender` tall**, without `lineGap`: `TextField` is single-line,
  so the gap would only overhang. `line-height` places the line box vertically.
- **The selection paints only while focused**; blur keeps the range, as browsers do.

## 8d. Text outlines — `text-stroke` and friends

Four authorable properties, all inheritable:

```css
text-stroke: 2px #0B5D8F;      /* width and/or colour, order-independent */
text-stroke: 0.04em;           /* width only -- the colour falls back to the text's `color` */
text-stroke: 4%;               /* the same width: a percentage is of the font size */
text-stroke: #0B5D8F;          /* colour only -- the width is left alone */
text-stroke: none;             /* width 0; the colour is untouched */
stroke-align: outset | center | inset;   /* initial: outset */
paint-order: normal | stroke;  /* initial: normal -- the fill paints over the stroke */
```

- `text-stroke` is `-webkit-text-stroke` without the prefix; `stroke-align` and `paint-order` are separate.
- **`px` is absolute; `em` and `%` are font-relative and equal** (`FontRelativeLengthValue`). The shared
  `LengthPercent.parse` must never learn `em`: its percentages are of the box.
- `text-stroke-width` and `text-stroke-color` cascade under those names but only the shorthand writes them
  (`getAuthoredThrough()`), so a colour-only declaration leaves the width alone. `transition: text-stroke` reaches
  both (`TextStrokeShorthand.transitionNameMatches`).
- **One class builds the backend style**: `CssTextStyle`, one per widget, turns the `ComputedStyle` into
  CrystalGraphics' `CgTextStyle` (the fill, the stroke as an outline layer, each `text-shadow` as a shadow layer),
  rebuilding only when an input changes.

### Four things that are easy to get wrong

**The distance field caps the width, and clamps rather than complaining.** The range is per face:

| band | who gets it | ceiling | at text size 64 | field tier down to |
|---|---|---|---|---|
| `pxRange 12` | a face with a dense script in it | **0.056em** | 3.6px | 15px |
| `pxRange 24` | everything else | **0.131em** | 8.4px | 7px |

Both share the 80px atlas scale, so one atlas holds both and the range travels per placement. Ask
`CgFontRegistry.maxStrokeWidthEm(family)` or `UIText.maxStrokeWidthEm()`; `CgTextLayer.MAX_FIELD_WIDTH_EM` is the
narrow band. The shader keeps a texel of headroom and is told the field's real range (`pxRange` minus one).

**A transparent colour is not an absent one**: `#00000000` equals the initial `0`, so `UIText` asks
`ComputedStyle.isSet` (`TextStrokeStyleTest`).

**An inward stroke closes the letterform where it is thin**: geometry, not a field defect (`CGUI_INVARIANTS.md`
§ *Rendering, GL and shaders*). `outset` never touches the letterform, which is why it is the initial.

**Bitmap-tier glyphs (below about 8px) take no stroke**; the declaration is inert.

### The composite is a union, not source-over

For `outset`, ring and fill are disjoint regions of one shape: `text.shader` takes the silhouette's coverage as the
union and divides it by how much each occupies. Source-over would leave a quarter-coverage hairline in every letter.

## 8e. Stacking order — what paints over what

Paint and hit order are CSS's (2.1 Appendix E), from Blink: a **stacking context** paints its background, its negative
`z-index` boxes, its normal flow, then its `auto`/`0` and positive boxes; hit testing walks it backwards.
`StackingOrder` holds a context's three lists, read by `BoxPainter` and `Box.hitTest`/`pick`.

| A box is… | when |
|---|---|
| a **stacking context** | the root; hosted by override (a popup, a mirror, the top layer); `z-index` not `auto`; `opacity` < 1; a `transform`; `backdrop-filter`; `mask`; `isolation: isolate` |
| **positioned** | `position: absolute`: painted with its context's `auto` boxes, after its normal flow |
| **normal flow** | everything else, in tree order |

A z-ordered box is painted by the nearest context above it, not inside its parent, and is still clipped by every box
it rose out of.

- **`z-index` applies to every box** (all are flex or grid items); `auto` is the initial and is not `0`.
- **A negative `z-index` sits under its context's whole normal flow**, the parent's background included unless the
  parent is the context: `isolation: isolate` on the parent.
- **An absolute box without a `z-index` paints over later normal-flow siblings**: a wash meant to sit under them takes
  `z-index: -1` inside a context.
- **The top layer paints after everything.** Boxes numbered against each other (windows raised by `z-index`) need a
  context of their own (`desktop .__windows__` isolates).
- **Rounded `overflow: hidden` is not a context.**
- **Deviation**: within one normal flow a box paints whole rather than in CSS's phases; this matters only where
  in-flow boxes overlap.

## 9. Known Gaps vs. the Web

- **No `@import` or media queries.** External sheets (`StyleSheetRegistry.of("ns:path")`) and custom properties exist.
- **No `:nth-child`, attribute selectors, `~`/`+`**, and no pseudo-class arguments (`:not`, `:is`, `:has`).
- **`transform` has no `matrix()`**, and mismatched function lists snap rather than decompose; matching lists
  interpolate component-wise. Axis variants collapse into the two-argument form.
- **Text inside a scaled subtree rasterises at its untransformed size**, so it softens when zoomed well past 1×.
- **No `::before`/`::after`**: use `overlay`/`outline` or a real shadow part.
- **No `background-position`/`-size`/`-origin`**: `image()`/`sprite()` carry a crop at parse time. `overlay` and
  `outline` have their own geometry longhands (§5).
- **Tiling belongs to the sprite value**, not a cascading `background-repeat`, and applies only to 9-slice sprites.
- **No `outline-clip`/`background-clip`**: the outline ignores the element's own radius clip and `overflow`, but an
  ancestor's clip cuts it, as in CSS.
- **No `outline-style`**: solid only.
- **`UIText` measures at the font's own metrics** rather than honouring a non-`normal` `line-height`.
- **The SDF border stroke is one scalar width**, though per-edge `border-width` grows the box correctly.
- **A cross-fade between two pictures is two draws** at crossing opacities.

## 10. File Map

| Concept | Path |
|---|---|
| Cascade / `ElementStyle` | `style/ElementStyle.java` |
| Origins and slots | `style/StyleOrigin.java`, `style/property/StyleSlot.java` |
| Selectors | `style/selector/` (`Selector`, `CompoundSelector`, `SelectorType`) |
| Pseudo-classes | `style/PseudoClasses.java`, `ui/input/FocusSource.java` |
| Stylesheets | `style/sheet/` (`StyleSheet`, `StyleRule`, `DeclarationParser`, `StyleSheetRegistry`), `style/StyleEngine.java` |
| Shipped stylesheets | `assets/crystalgui/ui/styles/ua/*.css` (the user-agent sheet), `ore.css` (theme, anti-bleed block at its top) |
| Transitions | `style/transition/`; easing is CrystalGraphics' `com.crystalgraphics.easing` |
| Property registry | `style/property/StylePropertyRegistry.java` |
| Shorthands | `style/property/Shorthands.java` |
| Border radius | `style/property/visual/border/` (`BorderRadiusShorthand`, `BorderRadiusProperties`, `LengthPercent`) |
| `line-height` | `style/property/visual/text/` (`LineHeightValue`, `LineHeightProperty`) |
| `transform` | `style/property/visual/transform/` (`Transform`, `TransformValue`, `TransformProperty`, `TransformOriginShorthand`) |
| CSS parsing helpers | `style/CssParsingUtil.java`, `style/CssAngle.java` |
| The frame | `ui/dom/UIDocument.java` |
| Paint context | `render/CgUiPaintContext.java` |
| Painting boxes, layers and masks | `ui/box/BoxPainter.java`, over CrystalGraphics' `CgCanvas.layer` / `CgCanvasLayer` |
| Images and their painter | `render/image/` (`CgUiImage`, `CgUiImagePainter`, `CgUiMark`, `CgUiPainter`) |
| Image parsing | `style/property/visual/image/` (`ImageValue`, `ImageKinds`, `ImageProperty`) |
| SDF shader lib | CrystalGraphics' `shaders/lib/sdf.glsl` |
| Rounded and bordered rects | CrystalGraphics' `CgCanvas.box` |
| Named 9-slice assets | CrystalGraphics' `CgSpriteSheet`, sheets at `assets/{ns}/ui/sprites/` |
| Demo scenes | `harness-scenes/.../scene/`: `CgUiStylingScene`, `CgUiVisualLayersScene`, `CgUiGalleryScene` (`harness-scenes/CLAUDE.md`) |
