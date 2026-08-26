# CrystalGUI — Icon Rendering

**Status**: written 2026-08-05 as research + plan while scoping E7 (icons + decorations for the Project
Explorer). **Approach E was built on 2026-08-06** and is what shipped — §2b, §3 and §9 are the current
record; §1 and §2A–D are kept because they are why E won, not because any of them is the plan.

**The short version**: we needed less than it looked like, and then less again. The recommended path adds
**no new dependency, no new GPU path, no atlas and no bake** — an SVG is parsed once into geometry and
drawn through the instanced vector renderer that already exists for graph wires. The MSDF converter §1.4
found in the jar turned out not to be needed either.

---

## 1. What exists today

Surveyed rather than recalled; every claim below was read out of the source.

### 1.1 `CgUiDrawable` — the SPI everything plugs into

`core/render/texture/CgUiDrawable.java`

```java
void draw(CgUiPaintContext ctx, int mouseX, int mouseY, float x, float y, float w, float h);
float intrinsicWidth();     // -1 when the drawable has no inherent size
float intrinsicHeight();
```

Anything that can paint itself into a rect is a drawable, and CSS already routes `background:`,
`overlay:` and `mask:` through it. **An icon system does not need a new concept — it needs a new
`CgUiDrawable` implementation and a way to name one from CSS.** That is the single most important fact
in this document.

### 1.2 The four drawables that already exist

| Class | What it is | Fits icons? |
|---|---|---|
| `CgUiShape` | **Parametric vector marks** — chevron, checkmark, cross, plus, triangles — drawn with `ctx.curve()` strokes and `ctx.triangle()` fills. No texture, no glyph. CSS: `overlay: shape("chevron-down")` | For **chrome** marks, yes, and it is already doing that job. Not for artwork: every kind is hand-coded Java geometry |
| `CgUiSprite` | Full 9-slice textured sprite, lazy UV cache | Works today. Fixed resolution |
| `CgUiRoundedRect` | SDF path, per-corner radii, morphing | Not an icon, but proof the SDF fragment path is live |
| `CgUiQuad` / `CgUiLayerBox` / `CgUiCrossFade` / `CgUiRepeat` | Fill, compositing, transitions, tiling | Composition, not artwork |

### 1.3 Sprite packs already ship a theming story

`core/render/texture/asset/CgUiSpriteRegistry.java` resolves `"namespace:name"` lazily from
`assets/{ns}/ui/sprites/{file}.json` against a PNG atlas. **A resource pack ships a theme by shipping
JSON + PNG — no registration call.** `background: asset("crystalgui:ore", "button")` goes through this.

This is a working atlas pipeline. Its limitation is not the plumbing, it is that a bitmap is one
resolution.

### 1.4 The finding that changes the plan

CrystalGraphics bundles `freetype-msdfgen-harfbuzz-bindings`, and the msdfgen half is **not tied to
FreeType**:

```java
MSDFShape shape = MSDFShape.create();
MSDFContour contour = shape.addContour();
contour.addEdge(MSDFSegment.createLinear());     // also createQuadratic(), createCubic()
shape.orientContours();
MSDFGenerator.generateMsdf(bitmap, shape, transform);   // also Sdf, Psdf, Mtsdf
```

`MSDFGenerator.generateMsdf` takes an **arbitrary shape**, not a glyph. Contours of linear, quadratic
and cubic segments is *exactly* an SVG path's vocabulary (`L`, `Q`, `C`).

So the pipeline **SVG path → `MSDFShape` → MSDF bitmap → atlas** needs no new library. The vector
rasteriser we would otherwise have to find, vendor and maintain is already in the jar, shipped for text.

`CgMsdfGenerator` (CrystalGraphics `text/msdf/`) is the existing consumer. It is glyph-shaped —
`prepareGlyph(CgGlyphKey …)`, `cellSizeForFontPx(int)`, a `MAX_PER_FRAME` budget — but the machinery
below it is general.

---

## 2. The four candidate approaches

### A. Extend `CgUiShape` with more kinds

Hand-code each icon as Java geometry.

**For**: zero new infrastructure; already works; crisp at any size; tints from the cascade.
**Against**: does not scale past a dozen marks. A file-type icon set is 40+ shapes, and each one becomes
Java that has to be edited to change a curve. Artwork does not belong in a compilation unit.

**Verdict**: keep it for what it already does — chrome marks (chevrons, checks, arrows). Do not grow it
into an icon library.

### B. Bitmap sprite atlas (`CgUiSprite`, already working)

**For**: works *today*, zero new code; multi-colour; a resource pack can override it with no Java.
**Against**: one resolution. `uiScale` is 2 by default here and user-settable, so an icon authored at
16px is soft or blocky at 32. The mitigation is shipping 1x/2x/3x, which is three sets of artwork to keep
in step.

**Verdict**: the correct fallback and the right escape hatch for genuinely pictorial, multi-colour art.
Not the primary path.

### C. MSDF icon atlas (recommended)

Author as SVG, convert to `MSDFShape`, bake into a distance-field atlas, draw as a quad with the existing
SDF fragment path.

**For**:
- **Crisp at any size and any `uiScale`** — the entire reason the text pipeline uses MSDF.
- **Tintable**, so per-file-type colour comes from the cascade the way `NodePort`'s wire colours already
  do (`graph.css` owns the palette; Java reads `border-color` back out). That precedent is directly
  applicable.
- Reuses the atlas, the packer, the background generation executor and the shader that already exist.
- One asset scales to every size, so no 1x/2x/3x sets.

**Against**:
- **Monochrome.** An MSDF channel triple encodes one shape's distance, not colour. Multi-colour icons
  need either layered shapes (one atlas entry per colour, composited — `CgUiLayerBox` already does the
  compositing) or approach B.
- Needs an SVG **path parser**. That is a real piece of work but a small and well-specified one — see
  §5 for exactly how much.
- Baking cost. Mitigated the way glyphs already are: generate off the GL thread, budget per frame.

### D. Full SVG rasterisation at runtime

Parse and rasterise arbitrary SVG — gradients, strokes, transforms, clips, filters.

**For**: authors hand over any SVG and it just works.
**Against**: enormous. Batik-class scope, no dependency present, and every frame of it is CPU work the
GPU path already does better. Nothing in the roadmap needs gradients in an icon.

**Verdict**: no. If a specific icon genuinely needs it, it becomes a PNG (approach B).

---

## 2b. Approach E — direct GPU vector strokes (**built, and what we chose**)

**This was not in the list above, and it should have been.** §8 asked, as an aside, whether the existing
`ctx.curve()` path could bypass atlasing entirely. It can, and the spike proves it — so the analysis was
missing its own answer.

Parse and flatten the SVG once on the CPU; draw each segment as an instanced quad whose fragment shader
evaluates an **analytic stroke SDF** from the control points (`stroke.glsl`'s `stroke_coverage`).

**Nothing is rasterised on the CPU, and nothing is baked.** The distance field is real but computed
per-pixel, live, from geometry — the same mechanism that keeps graph wires and `CgUiShape` chevrons crisp.

| | |
|---|---|
| CPU, once at load | parse → flatten curves and arcs → pack segments into a `float[]` |
| GPU, per frame | one instanced quad per segment; analytic SDF fragment |

**For**
- Resolution-independent, with no atlas, no bake, no texture memory, no shader work.
- Tint is a per-instance colour, so a theme recolours the set for free.
- Editing the `.svg` hot-reloads; there is nothing to regenerate.
- Reuses `CgVectorRenderer`, which already exists and already batches.

**Against**
- Cost scales with **segments × icons on screen**, which is the one axis where an atlas wins outright.
- Overdraw where segments meet; invisible at icon sizes, real at very heavy zoom.

### It fills too, which removes the one thing §3 held back for MSDF

**"Stroked only" was listed here as E's defining limit, and it is not one.** `CgVectorRenderer` already has
`triangle()` — the fill twin of `curve()`, sharing its material and its instance buffer — so a filled
interior needs no new GPU path at all, only a decomposition on the CPU. `SvgTriangulator` does it as a
**scanline trapezoid decomposition**: cut the shape into horizontal bands at every vertex `y`, sort the edge
crossings in each band, and apply the fill rule. Two triangles per inside span.

That choice over ear clipping is the load-bearing one. Ear clipping triangulates a *single simple polygon*,
and real artwork is neither — a logo is several contours at once, its counters and windows are holes that
must be **subtracted**, and exported paths self-intersect routinely. Ear clipping fills a hole solid, which
turns a ring into a disc. The scanline version cuts holes correctly under both `nonzero` and `evenodd`, and
because the bands are cut at every vertex it is **exact rather than stair-stepped** — no vertex can fall
inside a band, so each trapezoid's slanted sides lie exactly on the edges they came from.

### Gradients: cut ALONG the ramp, not across it

The first three attempts all failed the same way, and the failure is worth recording because it looks like
banding and is not.

The scanline cuts in `y`. A gradient running any other direction therefore has each band spanning a *range*
of ramp positions, so one flat colour per band is right only down the band's middle and wrong at both edges
— and the error reverses at the next band. On screen that is a smooth fade inside each strip with a visible
seam between them. **Making the cells smaller never fixes it**; it only shrinks the jump. Chasing it that
way cost two rounds: adaptive density (which ran to 30,406 triangles for one 16px icon) and a cell budget
(which traded one region's smoothness for another's).

The fix is to **rotate the shape so the ramp points along `+y`**, triangulate there, and rotate the triangles
back. Every band is then an iso-line strip — constant ramp position end to end — so a flat colour per band
is not an approximation at all. It is also strictly cheaper: chasing a diagonal ramp with axis-aligned cuts
costs N² cells for N bands of quality and only O(N) carry new colour.

Radial gradients keep the old path. Their iso-lines are circles, and no rotation makes a circle straight.

### The overdraw that came with it

Rotated back, a band is a long thin **diagonal** strip — and an instance rasterises the *axis-aligned*
bounding quad of its triangle, which for a diagonal strip is nearly the whole icon. Measured as fragment
work against icon area: the JetBrains mark asked for **55.7×** while covering 1.44×. That is a GPU at 90%
and audible fans on a zoomed harness grid.

Cells are now capped at **2:1** length-to-depth. A band is one ramp position end to end, so cutting it
changes nothing visible — it only makes each triangle compact enough that its box hugs it. Logo 55.7× → 4.2×,
htaccess 11.7× → 2.3×, and the GPU went back to idle.

> **The optimisation deliberately NOT taken, and why it is written down.** Both the aspect slicing and the
> rotated frame exist to work around two properties of the shared curve instance record: a fill's colour is
> flat, and its bounding quad is axis-aligned. Fixing either at source would collapse the workarounds —
> a per-pixel gradient (a `vec4` axis in the record, `t` computed in `stroke.glsl`'s fill branch instead of
> forced to `0`, `mix(color0, color1, t)` already present) removes all smoothness subdivision, and mapping
> the unit-quad mesh onto the triangle's own corners in `CG_CURVE_WORLD_POS` removes the overdraw entirely.
> Together they would take htaccess from 22,531 triangles to roughly 600 with ~1× overdraw and exact colour.
>
> It is not built because **the problem it would solve is already solved**: quality is good and the GPU is
> idle. The cost is a wider record for every stroke in the engine and a branch in a macro that every shader
> in both projects includes. Build it when something needs the triangles back — a large gradient-heavy
> canvas, or icons drawn per-frame in bulk — not on the strength of the numbers alone.

### Gradients come for free out of the same subdivision

A `Triangle` takes one flat colour, so the obvious move is to flatten each gradient to one — and that is
wrong in a way worth recording. `gradientUnits="userSpaceOnUse"` states the axis in the *document's*
coordinates, and exported artwork routinely gives a shape an axis several times its own size; the shape then
occupies a narrow slice of the ramp, so its real colours have nothing to do with the ramp's middle. The
JetBrains gradients each carry a deliberately desaturated stop near `0.5` to make orange→blue read smoothly,
so sampling the middle picks exactly those: the logo comes out brown and purple.

Instead the fill is **cut fine enough along the gradient's own direction that a flat colour per triangle is
indistinguishable from a ramp** — 32 bands across the range, evaluated at each triangle's centroid. Cutting
along the direction rather than uniformly is what keeps it affordable: a horizontal gradient needs no
horizontal cuts, so a tall shape stays at its handful of natural bands. No shader, no paint-server material,
no per-shape uniform upload.

The JetBrains mark is the proof: nested `<g>`, four `userSpaceOnUse` gradients, `style="fill:url(#…)"` on
every shape, and geometry that is 100% filled polygons with no stroke anywhere. It comes out as 8 fill ops
and ~1900 triangles, in its real colours, from the same `render()` call that draws the Feather set.

---

## 3. Recommendation

**Direct GPU vector (E) for everything vector; the two existing systems keep the jobs they already do;
MSDF is not needed.**

| Kind of mark | Path | Why |
|---|---|---|
| Chrome — chevrons, checks, arrows | `CgUiShape` (exists) | Parametric, already crisp, already themed |
| **Stroked icon sets — file types, decorations** | **Direct vector (E)** | Feather, Lucide and Tabler are all stroked. No atlas, no bake, crisp at any `uiScale` |
| **Filled and multi-colour artwork — logos, Material** | **Direct vector (E)** | Fills, gradients-as-flat-colour and per-shape paint all land through the same path |
| Photographic or genuinely raster art | `CgUiSprite` (exists) | Not vector; nothing here helps |

This is a **cheaper answer than the original recommendation**, and it came from building the spike rather
than from more analysis. The MSDF pipeline in §5 is now **speculative rather than planned**: it was held in
reserve for filled artwork, E fills, and the only thing left that would justify a bake is a throughput
ceiling — which is a number to measure, not a design to commit to in advance.

### The number that decides it

E's only real risk is throughput, and it is measurable rather than arguable. The harness scene reports
segment count live and extrapolates to a 50-row tree.

**Measured.** The five Feather icons come to **341 stroke segments** — 68 each, so a fifty-row file tree is
about **3,400 instances a frame**. The JetBrains mark, which is the pathological case rather than the
typical one, is **1,832 fill triangles** on its own.

All of it goes out in **one instanced draw call**. `ctx.curve()` and `ctx.triangle()` share a single
`CgVectorRenderer` and a single `gui_curve.shader` binding, so alternating strokes and fills costs nothing;
`beginCurvePath()` early-returns once the path is current, `useMaterial` re-flushes only when the material
*reference* changes, and `submit()` only appends to a CPU buffer that grows rather than draws. One
`flush()` at the end issues one `drawInstanced`. The six icons in the harness scene — 2,173 instances,
strokes and fills mixed — are one call.

What would break it is interleaving: a `fillRect`, `drawImage` or `text()` between two icons switches to
the quad path and forces a flush each way. Draw the icons together. (The scene's overlay text is after the
flush and goes through `CgTextRenderer`'s own quad renderer, so it is separate regardless.)

So throughput is not the risk it was billed as, and approach C stays unneeded.

## 4. Proposed architecture

Nothing here is a new *concept* — each piece is the icon-shaped sibling of something that exists.

```
assets/{ns}/ui/icons/{name}.svg          authored artwork (path data only)
        │
        │  offline or first-use
        ▼
IconShapeParser        SVG path 'd' → MSDFShape          (new, small)
        │
        ▼
CgIconAtlas            MSDFShape → distance field → atlas page   (new; mirrors the glyph atlas)
        │
        ▼
CgUiIcon               a CgUiDrawable that draws one atlas entry, tinted   (new, small)
        │
        ▼
CSS:  overlay: icon("crystalgui:file-java");             (new TextureValue keyword)
```

### 4.1 `CgUiIcon` — the drawable

Mirrors `CgUiSprite`: holds an atlas texture + UV rect, draws one quad. Differences:

- Samples the **distance field** with the SDF material rather than a colour texture, so it is sharp at any
  size. `gui_rounded_rect.shader` already proves that path.
- Takes its colour from the cascade, not the asset. **`intrinsicWidth()`/`intrinsicHeight()` return the
  authored size**, so `overlay-fit` works exactly as it does for a sprite.

### 4.2 `IconRegistry` — resolution

Copy `CgUiSpriteRegistry` deliberately: `"namespace:name"` → icon, resolved lazily from
`assets/{ns}/ui/icons/`, `ConcurrentHashMap`-cached. **A resource pack then ships an icon theme by
shipping files**, with no registration call, exactly as a sprite theme does today. That property is worth
preserving precisely.

### 4.3 CSS surface

```css
.__file-row__ .__icon__          { overlay: icon("crystalgui:file"); }
.__file-row__[ext="java"] .__icon__ { overlay: icon("crystalgui:file-java"); color: #C9744A; }
```

`icon(...)` sits beside the existing `asset(...)`, `sprite(...)` and `shape(...)` in `TextureValue`. **The
tint is `color`, from the cascade** — so a theme recolours the whole icon set without touching artwork,
and the per-type palette lives in CSS next to the port-colour palette that already works this way.

---

## 5. What has to be built — **SUPERSEDED, and msdfgen is not needed**

> **Read this box, not the plan below it.** Everything in §5 is the MSDF-bake route: parse SVG into an
> `MSDFShape`, generate a distance field, pack it into an atlas, draw it as a textured quad. **None of it
> was built and none of it should be.** Approach E (§2b) draws SVG geometry directly through
> `CgVectorRenderer` — resolution-independent, full colour, one draw call, no atlas, no texture memory, and
> a `.svg` edit hot-reloads with nothing to regenerate. The bake exists to buy crispness at scale, and E
> already has that by construction.
>
> **msdfgen keeps its real job and loses this one.** It is the font pipeline's distance-field generator and
> stays exactly that. §1.4's finding — that `MSDFGenerator.generateMsdf` takes an arbitrary shape, not a
> glyph — was the right observation and it turned out not to be needed: it was the answer to "how do we
> rasterise vectors without a new dependency", and the better answer was to not rasterise them at all.
>
> The one thing that would revive this is a **throughput ceiling**, and that is measured rather than
> feared: see §3. A fifty-row tree is ~3,400 instances in one draw call. If some future screen genuinely
> cannot afford that, the parser in `render/texture/svg/` already produces exactly the contours
> `MSDFShape` wants, so §5 becomes a bake step over existing geometry rather than a rewrite.

Ordered so each step is independently testable, and the risky part is proven before anything depends on
it.

### Step 0 — Spike: is the shape API usable? *(half a day, and it de-risks everything)*

Build one `MSDFShape` by hand — a triangle, three linear segments — run `generateMsdf`, dump the bitmap to
PNG from a scratch JUnit test. **If this does not work, approaches C and the whole plan collapse to B**,
and we want to know that before writing a parser.

This follows the project's own rule about rendering bugs: an isolated test that dumps an artifact beats
booting the harness.

### Step 1 — `IconShapeParser`: SVG path → `MSDFShape`

Scope it to **path data only**: the `d` attribute of `<path>`, commands `M m L l H h V v C c S s Q q T t
Z z`. Deliberately **not** supported: `A` (arc — convertible to cubics if ever needed), strokes,
gradients, transforms, groups, text.

That is a well-specified grammar of about a dozen commands with a documented conversion for the smooth
variants (`S`/`T` reflect the previous control point). It is a day of work and it is completely testable
headlessly — parse a path, assert the contour count and segment types. No GL, no window.

**Authoring rule that falls out**: icons must be supplied as *filled paths*, with strokes already
converted to outlines. Every vector editor does this on export. State it loudly, because a stroked path
silently produces a hairline-thin distance field and looks like a bug in the renderer.

### Step 2 — `CgIconAtlas`

Bake shapes into a distance-field atlas. **Check first whether `text/atlas/packing/` is reusable** — if
the packer is glyph-agnostic this is mostly wiring; if not, a shelf packer for a fixed cell size is not
much code, since icons are square and few.

Follow the glyph pipeline's two proven decisions: generate **off the GL thread**, and cap work per frame
(`CgMsdfGenerator.MAX_PER_FRAME` is 4). An icon set is small enough to bake at load, but the budget keeps
a resource-pack reload from stalling a frame.

### Step 3 — `CgUiIcon` + `IconRegistry` + the `icon()` CSS keyword

The smallest step. `CgUiSprite` and `CgUiSpriteRegistry` are the templates; follow them closely rather
than inventing a second idea of what an asset registry is.

### Step 4 — E7 proper: the decoration provider

Only now is E7 unblocked. Per the chrome plan, port VS Code's `IDecorationsProvider`: **a provider API, so
"dirty", "read-only" and "has errors" are three independent contributors** rather than three special
cases inside the row renderer. The file-type icon is then just another contributor.

---

## 6. Decisions to make before building

**Monochrome or multi-colour for file types?** Recommended: **monochrome + cascade tint**, VS Code's
model. It is cheaper, it themes properly, and it matches how this codebase already handles the graph's
per-type port palette. If multi-colour is wanted later, `CgUiLayerBox` can composite two tinted layers
without changing the pipeline.

**Bake offline or at runtime?** Recommended: **runtime, cached**. Offline means a build step and a
generated binary in the repo; runtime keeps "ship a folder of SVGs" true for resource packs, which is the
property that makes the sprite system pleasant today. Revisit only if load time measures badly.

**Icon size?** One authored size (recommend a 24×24 viewBox), scaled by CSS. That is the point of MSDF —
authoring several sizes would forfeit the reason for choosing it.

**Where do icons live?** `assets/{ns}/ui/icons/` beside `ui/sprites/` and `ui/styles/`, so the resource
convention stays uniform.

---

## 7. Risks

| Risk | Mitigation |
|---|---|
| `MSDFShape` unusable outside FreeType | **Step 0 spike proves it before anything depends on it.** Fallback is approach B, which already works |
| Distance-field artefacts at very small sizes | The glyph pipeline hit this and has `CgMsdfQualityProbe` plus `shouldUseMsdf(shape, px)`; the same escape (fall back to a bitmap below a threshold) applies |
| Icons needing colour | Layered composition via `CgUiLayerBox`, or approach B per-icon. The two can coexist — the CSS keyword differs, nothing else does |
| Atlas packer is glyph-specific | Worst case a shelf packer for fixed square cells, which is small |

---

## 8. What I have *not* verified

Stated so nobody treats this document as more settled than it is:

- Whether `text/atlas/packing/` accepts non-glyph entries. **Read it before Step 2.**
- The exact `MSDFTransform` setup for a non-glyph shape (scale, translate, px range).
- Whether the SDF material can be pointed at an arbitrary atlas texture, or is wired to the font atlas.
- Whether `CgUiShape`'s `triangle()` path could bypass atlasing entirely for simple icons — a *direct
  vector* path with no bake at all. Worth ten minutes' thought before Step 2; it may make simple
  monochrome icons free.

---

## 9. Summary

The blocker was smaller than it appeared, and smaller again than the first draft of this document
concluded.

There is a working bitmap atlas with a theming story, a working parametric mark system, a general
vector-to-distance-field converter already in the jar — and, the thing the spike found, **an existing GPU
vector path that draws SVG geometry directly with no atlas and no bake at all**.

What is built — a genuine SVG renderer, not a path reader:

| Class | Does |
|---|---|
| `SvgScanner` | Nested tag scanner. Replaced the regex the moment `<g>` mattered: a pattern cannot see a closing tag, so it cannot know when a group's transform stops applying |
| `SvgPath` | The whole `d` grammar — `M L H V C S Q T A Z`, absolute and relative, arcs converted through the spec's endpoint→centre formula |
| `SvgTransform` | `translate`/`scale`/`rotate`/`matrix`/`skewX`/`skewY`, composed down the tree, in SVG's own `matrix(a b c d e f)` order |
| `SvgColor` | Hex (3/4/6/8), `rgb()`/`rgba()`, named colours, `none`, `currentColor`, `url(#…)` |
| `SvgStyle` | Inheritance as a value type, with `style=""` outranking presentation attributes as CSS requires |
| `SvgTriangulator` | Scanline trapezoid decomposition — fills, with holes cut, under both fill rules, subdivided along a gradient's direction |
| `SvgGradient` | Linear and radial, both unit systems, `gradientTransform`, `spreadMethod`, `href` stop inheritance |
| `SvgDocument` | The walk, the gradient table, the `<use>`/`<defs>` resolution, and the cached ops |

Seven shape kinds, arbitrary nesting, `<defs>`/`<symbol>`/`<use>`, real linear and radial gradients, and
`currentColor` left **late-bound** so one cached document draws in two tints in one frame. Icons
ship as `.svg` files under `assets/{ns}/ui/icons/` and resolve through `CgIO` like every other asset.

Not done, in rough order of how likely you are to hit them:

| Gap | Effect |
|---|---|
| **`<style>` blocks and `class=` selectors** | Illustrator's "Style Elements" export mode puts every fill in a CSS rule and references it by class. Those shapes fall back to the initial black fill. **The most likely thing to bite** — it is a whole export mode, not an exotic feature |
| `stroke-linejoin`, and `stroke-linecap: butt` with it | Every segment is stroked independently, so joins are whatever cap the segment ends carry. `drawStroke` promotes `butt` to round for exactly that reason — most segment ends are interior joints, not real path ends. Correct for a round-join set and wrong for a `butt` + `miter` one, which comes out round everywhere |
| `stroke-dasharray` | Ignored; a dashed stroke draws solid |
| `<text>` | Not drawn. Icon sets convert text to paths; hand-written SVG often does not |
| Clip paths, masks, filters | Ignored — a clipped shape draws unclipped, a blur draws sharp |
| Patterns | Draw flat grey |
| Gradient on a *stroke* | Collapses to one colour; strokes are not subdivided |
| Group `opacity` | Multiplied into children rather than compositing the group as a unit |
| Nested `<svg>`, `preserveAspectRatio`, `<switch>` | Treated as plain groups |

Every one of them fails in the direction that leaves a *visible* approximation rather than a hole.

**None of them block the icon renderer**, which is checked rather than assumed: the shipped set touches
exactly one, `stroke-linejoin="round"` — and stroking each segment with round caps *is* a round join, so
that one is already exact. Feather, Lucide and Tabler all declare round joins; Material converts text to
paths; Material's `<g clip-path>` wrapper clips to the full viewBox, so ignoring it is a no-op. The
`<style>`/`class=` gap is the one worth closing, and not for icon *sets* — none use it — but because a
resource pack ships a theme by dropping in `.svg` files, and half of Illustrator's export modes produce it.
Silent failure: those shapes go black rather than erroring.

## 9b. Where this stack lives, and when to move it

**It stays in `com.crystalgui.render.texture.svg`.** The question is real, because CrystalGraphics owns
every other asset-format reader in the project — `CgTextureIO` decodes PNG, `CgMeshLoader` parses OBJ and
glTF, the whole font stack parses and shapes. An SVG reader is the same shape of thing.

What settles it the other way is that the boundary in `AGENTS.md` is about **GL and backend capability** —
raw GL, vertex packing, buffers, GPU resource ownership — and this stack does none of it. It consumes
`ctx.curve()` and `ctx.triangle()` like any other drawable. Its actual neighbours are `CgUiSprite` and
`CgUiRoundedRect`: artwork that knows how to paint itself, which is what `render/texture/` is for.
Splitting a coherent eight-class stack across two repositories to satisfy a rule it is not breaking is
worse than leaving it.

**The move is cheap whenever it is warranted, and worth knowing that in advance.** Six of the eight classes
— `SvgPath`, `SvgScanner`, `SvgTransform`, `SvgColor`, `SvgGradient`, `SvgTriangulator` — import nothing
outside `java.util`. `SvgStyle` reaches CrystalGraphics only for the `CAP_*` constants. Only `SvgDocument`
is genuinely bound to CrystalGUI, and only in its `render` half.

Two triggers to actually do it, either of which makes the geometry half CrystalGraphics' business:

1. **A second consumer outside CrystalGUI** — shader-graph node artwork drawn through `CgVectorRenderer`
   without a paint context, say.
2. **The MSDF bake path in §5.** `MSDFShape` and the msdfgen bindings live in CrystalGraphics, and
   SVG→`MSDFShape` is a conversion between two of its own types. If that is ever built, the parser has to
   be there.

Until one of those lands, moving buys nothing and costs a cross-project split.

**Done since:** throughput measured (§3), `CgUiSvg` wraps a document as a `CgUiDrawable`, `icon("ns:name")`
is a `TextureValue` form, and `FileIconTheme` ports VS Code's file-icon-theme JSON — extension and exact
name to icon, longest-extension-first, with the colour deliberately left to the `.filetype-*` class it hands
back so a dozen languages can share one glyph and still differ.

### The icon set: IntelliJ Platform, not Feather, and not Material

Fifty **IntelliJ Platform** icons (Apache 2.0, JetBrains) now back the file tree — 47 from
`platform/icons/src/fileTypes/` plus `folder`, `package` and `moduleGroup` from `nodes/`. Verbatim; see
`ui/icons/ATTRIBUTION.md` and `THIRD-PARTY.md`.

Chosen over Material Icon Theme (MIT, ~1000 types) for three reasons that are not taste:

1. **This workbench is an IntelliJ port.** The Preferences window is a direct port of theirs. Coherence with
   the thing being imitated is a design argument.
2. **16px-native**, which is exactly a tree row. Material's are 32 designed to be *seen* at 16.
3. **Muted palette** that sits inside IDE chrome rather than competing with it.

Worth recording: **VS Code's own default file icon theme (Seti) is a font, not SVG**, so "the VS Code
equivalent" in SVG means Material or vscode-icons, not anything VS Code ships.

**Every one of the 47 was scanned before adoption and none uses a feature we lack** — no `<style>`, no
`class=`, no `clipPath`, no `<use>`, no strokes at all. What they do use, we implement: 36 nest `<g>`, 43
carry `fill-rule`, 40 `fill-opacity`, 19 a per-shape `transform`, and one has a gradient.

**Both light and dark variants are checked in and neither is wired.** The Platform ships one icon per type
in the general case; only four have a `_dark` twin (`Csharp`, `binaryData`, `json`, `jsonSchema`). Wiring
them means a `darkSuffix` key in the theme plus a way to ask which chrome is current — a small change,
better done once than discovered per icon.

**The gap, stated rather than hidden:** Kotlin, Python, TypeScript, Rust, Go, C/C++, Ruby, PHP, shell, SQL,
Markdown and GLSL have no icon in the *Platform* set — theirs live in per-language plugin modules, several
product-specific. They resolve to the plain text document, and are still listed individually in
`default.json` so each keeps its own `.filetype-*` class. Closing it means hunting plugin paths or filling
in from Material, which is licence-compatible and a visibly different drawing style.

> **Consequence worth knowing before writing a theme:** `filetypes.css` no longer tints the file icon.
> `color` reaches an icon through `currentColor`, and the IntelliJ set carries its own fills. Those rules
> now colour the row label and any monochrome mark. A theme wanting the icons flattened to one colour says
> `icon("...", monochrome)`, which forces every fill to the tint — both paths are supported, and the class
> is the hook for both.

**E7 is done.** The Project panel row is now four slots — twisty, icon, label, badge — built in
`createTemplate` and written in `bind`. `TreeView` already supplied the indent and the
`__expanded__`/`__collapsed__`/`__leaf__` classes, so the old row was indenting twice and spelling its
twisty in text; both are gone.

`workbench/decoration/` ports VS Code's `IDecorationsProvider`: dirty, read-only, errors and version
control as independent contributors, merged **per field** rather than winner-takes-all — a modified file
that also has an error is red *and* keeps its `M`, where taking the heaviest wholesale drops one of the two
facts the row was asked to show. Decorations bubble to ancestor folders, which is what makes a collapsed
tree useful, and a bubbled one keeps the colour and drops the badge.

### The brand mark: `crystalgui:logo`

`ui/icons/logo.svg` is the CrystalGraphics / CrystalGUI mark — original artwork, nothing owed. **A
crystal cluster**: three hexagonal prisms fanning from one base, each drawn as its three visible faces
(a bright front, two darker sides, every face pointed top and bottom) — a cyan→blue main shard, a
fuchsia→violet one leaning left, a small pink one at the right. Flat and hard-edged, no tile: the
silhouette is the icon, as it is for every product icon in the taskbars this sits beside.

It took four rounds to get there, and the rejects are worth a line each because they say what the mark
is *not*: a bevelled, ten-facet C read as blocky; a plain round gradient C read as generic; a black
square with a mono C over rotated gradient shards was a good icon and unmistakably JetBrains'. What
survived every round was the palette (cyan/blue, fuchsia/violet, a pink accent) and the first-round
observation that the literal crystals were the part that looked like *us* — so the final mark is that
palette on that object, and no letter.

Four rules it is authored under, each because the renderer made it so:

1. **Filled polygons and gradients only.** Every face is a `<polygon>` with its own two- or three-stop
   `linearGradient`. No strokes — a stroke has no gradient here and its joins are whatever cap the
   segments carry.
2. **No `currentColor` anywhere**, and that is load-bearing rather than incidental: it is what
   `SvgDocument.usesCurrentColor()` reads to tell `WindowIcon` this is artwork that *is* a tile, not a
   chrome mark that needs one put under it.
3. **One shard, placed three times through `<g transform="translate() rotate() scale()">`.** That
   depends on `SvgTransform.parse` composing a list rightmost-first, as the spec says — it composed
   leftmost-first for as long as it existed, and no shipped icon had chained two functions to notice. An
   earlier draft placed a part with `translate scale translate` and it vanished off-canvas. Fixed and
   pinned by `SvgTransformOrderTest`; the cluster is the first shipped artwork to rely on it.
4. **Arcs are sampled per quarter turn.** Found while a round C was a candidate: `SvgPath.arc` gave
   every arc the same `steps` regardless of sweep, so a rounded corner and a 296° letterform got eight
   segments each at tile size — one smooth, one an octagon. Normalised on the quarter (a corner is
   unchanged; a long arc gets what its length needs), which also fixes every `<circle>` drawn small.

**The renderer's own tessellation is the reference, not a browser's.** Every candidate was judged from
PNGs rasterised over `SvgDocument.ops()` — per-triangle ramps evaluated exactly as the fragment stage
does — at 12, 14, 18, 24, 28 and 36 device pixels, the taskbar, preview, switcher and title-bar slots at
both `uiScale`s, each parsed at the LOD tier the engine draws that size at. The IntelliJ icon itself
rendered through the same path as a control, and came out right.

Nothing registers a provider yet, so the feature costs nothing until something does. **E16 (dirty state)
is the first real consumer** — it is a `FileDecorationProvider` returning `decoration-dirty` for whatever
`WorkingCopies` reports modified, which is now a class rather than a change to the row renderer. That is
the whole point of the port.
