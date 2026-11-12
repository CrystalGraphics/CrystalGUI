# `render` — recorded, executed once

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../AGENTS.md), which keeps the rules every session needs.

## Stack 5: Render — recorded, executed once

**The V3.1 draw-list design is gone.** `CgUiDrawList`, `CgUiDrawListExecutor`, `CgUiDrawState`,
`CgUiBatchSlots`, and `CgScissorRect` do not exist. Do not reference them.

### `CgUiRenderer`, `CgUiRenderer.View`, `CgUiPaintContext` and `UiPresenter`

**`CgUiRenderer` is the one global part**: the UI's pipelines named to the precache (`precache`, `parseAhead`), a
`CgUiRenderer.View` handed out per place a document is drawn (`openView`), and every open view's textures freed when the
graphics context goes (`contextDestroyed`). **A `CgUiRenderer.View` is per document**: its `CgUiPaintContext`, made at the first
`paintContext()`, and its `UiPresenter`, which shows the context's target, so the two are closed together
(`close()`, render thread). **A `CgUiViewport` owns its document's view** and opens its paint context's frame for its `Content`; the document
owns none, since it is the DOM and runs on a dedicated server. A view is never shared: each document records on its
own thread into a target of its own size, and its replay segments, surfaces and icon raster are keyed to its tree.
Anything drawing without a driver (a rehearsal, a scene with no document) opens a view and closes it when done; one
left open holds its textures until the context goes.

**The paint context records** (`render-graph` G4): every draw of a frame is a
chunk in one `CgRecording`; `seal()` builds it into a `UiFrame` that refers back to nothing. **A `UiPresenter` shows it
at a CrystalGraphics GUI stage** (`SCREEN` or `HUD`), from a renderer's turn: the frame executes at its place in the
firing (`CgStageFrame.include`, at depth 0, timed as `gpu:ui`), and its picture is composited with the stage's canvas
under the stage's camera. The stage gives the size, the target, the host section and the clock; nothing paints a UI
outside a firing. A layer is a pass on a texture
the executor lends for the frame; the target around it ends its pass at the layer and continues in another after, so
passes run in the order they were made and a read follows the write it sees. `flush()` lands what is drawn into the
recording and executes nothing; order never needs it. **CrystalGUI holds no GL state and makes no GL call** (`rhi` H3): a texture paint keeps across
frames is `requestLayer`'s — made when the frame executes, freed by `releaseTexture` — its pixels written by
`ctx.update`, and the scene behind the UI is `CgGraphTexture.current()`, copied (`recordCopy`). The executor takes
and gives back the host's state round every execution, inside the stage's host section. Drawing outside a frame throws.

```java
CgUiRenderer.View view = CgUiRenderer.openView();
CgUiPaintContext ctx = view.paintContext();       // first call on the render thread
ctx.recordFrame(w, h);                            // no GPU from here: any thread the document is framed on...
document.paint(ctx);
UiFrame frame = ctx.seal();                       // ...to here

CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, stage ->
        view.presenter().present(stage, frame));  // render thread: included in the firing, composited
view.close();                                     // render thread, with the document
```

A host never writes that by hand: `CgUiViewport.frame(stage, content)` records into its view (on the document's
sequence, or at the firing inline) and shows through its `presenter()`, and `CgUiViewport.close` closes the view; `CgUiScreen` paints the desktop on both GUI stages.

| Group | Methods |
|---|---|
| Frame | `recordFrame(w,h)` / `seal()`, and `abortFrame()` for a paint that threw; then `UiPresenter.present(stage, frame)`, with `presentAgain(stage)` compositing the last picture again when no new frame was sealed and `redraw(stage, keepRequested)` executing it again under its values now — the whole tree records into the context's frame target, composited onto the stage's once |
| Draw | `fillRect`, `drawImage`, `quad()` (the box material) and `quad(material)` (a drawable's own), `rect()`, `curve()`, `triangle()`, `filledCell()`, `text()`: each a `CgCanvas` item with the context's pose, clip entry, nodes and layer opacity already stamped (text takes its text gamma, and no opacity) |

> **Every item names its material; the context holds no current material, texture or path.** A quad's texture is
> `.texture(0, t)` on the quad (`.texture(0, t, sampler)` to filter it otherwise), a premultiplied picture is
> `.custom2(CgShapeTable.PREMULTIPLIED)`, a different blend is `.state(s)`. A drawable with a shader of its own sets
> its properties, then draws `ctx.quad(material)`: the item takes the material's values as it is submitted. The
> canvas keeps order across quads, curves and text and batches neighbours that match, so nothing here flushes for
> order; a flush ends a batch and is only for a boundary (a segment's edge, a read of the target).
>
> **Never call `.pose`, `.clip`, `.node` or `.opacity` on what `quad()`, `curve()` or `text()` answer**: each replaces
> the context's, and dropping the pose drops `uiScale` and the element transform. Stroke widths scale with the
> pose, so a 2px stroke stays 2 *logical* px at any `uiScale`, as a 2px border does. Each answer is the canvas's one
> scratch builder: build and `submit()` in one expression, never hold it.

> **`rect()` is `quad()`'s shape-aware twin, and the only place a rounded or bordered rect is drawn.**
> `ctx.rect().at(x,y).size(w,h).radius(6f,6f).border(1f,edge).fillColor(bg).submit()` — same scratch rule
> as `quad()` (build and `submit()` in one expression), and `submit()` names a shape only when the rect is
> not plain. **`CgUiRect` is a VALUE and drawing one must not build one**: its
> `with` methods each answer a copy, which is right for something the cascade shares and ruinous for a
> painter that needs a rect per element per frame. `BoxPainter` composes the element's radii, its border
> and the background's own `Fill` straight into this scratch, so a shaped element allocates nothing;
> `CgUiRect.draw` does the same with its own fields. A drawable that writes an instance's values
> holds its writer as a field (`CgUiColorField`), since a lambda over the draw's values is a
> capture per element per frame. Per-draw scratch is not part of a drawable's value: nothing in it is read by
> `equals` and nothing survives the draw.
| Clip | `pushScissor` / `popScissor` — a whole-pixel clip entry every draw is stamped with, no flush (a real scissor inside a snapshot or capture, under a node that is not a whole-pixel translation, or past the clip chain's depth; `-Dcrystalgui.paint.squareClips=false` everywhere) — and `pushRoundedClip` / `popRoundedClip`, a rounded rect stamped the same way (`CgClipTable`), no layer and no flush |
| Opacity | `pushLayerOpacity`/`popLayerOpacity` and `withLayerOpacity(opacity, body)`: composed with the opacity in force and stamped on every quad and curve after (`CG_QUAD_OPACITY`, `CG_CURVE_OPACITY`). No flush, no material written, no batch broken. Distinct from `getColor()`'s alpha, which a box applies to its fill alone; text takes none |
| Layers | `beginLayerFbo(region)` / `endLayerFbo()`, `blitLayer(fbo, opacity, region)`, `requestLayer(name, w, h)` / `releaseTexture(texture)` and `drawLayer(texture, x, y, w, h)` for a picture kept across frames, `compositeMask(subtreeFbo, maskFbo, region)`, `layerRegion(...)`, `surface(key, region)` |
| Pixels | `update(texture, x, y, w, h, rgba)` into a requested texture, recorded |
| Lifecycle | `CgUiRenderer.View.close()` with its document (a driver's `close` does), `CgUiRenderer.contextDestroyed()` at shutdown |

> **A view's textures are freed by its `close()`, and by `CgUiRenderer.contextDestroyed()` for one left open**
> (`CgUiClientLifecycle.onContextDestroy` calls it). Both free what nothing else sweeps: each paint context's frame target, surfaces, backdrop and icon-raster textures —
> requested from the graph, which no registry holds (`CgExecutor.freeStorage`) — and the contexts' renderers. Not
> what is borrowed from CrystalGraphics' registries (materials, the fallback white pixel, font atlases):
> `destroyContext()` sweeps those, and freeing them here would be a double free.

> **The whole tree paints into the context's frame target and is composited onto the host's target once.** So the
> finished picture is a texture this engine owns — the backdrop samples it, the trace's photograph sees the UI and
> not the world behind it (`UiPhotograph`, a graph readback), and the format is the same on every loader — for one
> screen-sized RGBA8 and one full-screen quad a frame. It is a requested texture, remade when the surface changes size;
> each context has its own, so two documents of two sizes never resize one. **It is not multisampled**, and was until the quad materials learned to
> antialias themselves: four samples bought a 33MB renderbuffer at 1920x1080 that had to be *resolved*
> before anything could read it, once in `endFrame` and again on every backdrop capture, which is what
> made that capture expensive. A colour texture is sampleable as it stands, so one buffer does what the
> multisampled pair did. Measured on `cgui-gallery`'s `edges` page: 0.25% of pixels differ, mean 0.031
> levels, all of it a hairline on rotated rims and rotated glyph stems and none of it visible at 14x.
> Frame time 0.1–0.4 ms/frame better; `frame.swap` and `glFlush` do not move, because these scenes are
> CPU-bound in buffer mapping. What it gives up is the case analytic coverage cannot reach: geometry
> finer than one sample, a graph wire zoomed far out.

> **A layer is the size of what goes in it, and lives for one frame.** `BoxPainter` sizes every layer from the
> subtree's ink bounds (`Box.inkX0..inkY1`, composed bottom-up in `BoxTree` — Blink's visual overflow, with
> `UIElement.inkOverflow()` for a widget that paints past its own box), clipped to the live scissor; the allocation,
> the clear and the composite all address that `LayerRegion`, and **the layer's pixel (0,0) is the region's
> corner**. What did not change in it is replayed rather than painted (G6), and a picture kept across frames is a
> surface's (G7, below) -- the retained layers that came before both were deleted in G9. `UIElement.repaint()` is
> the door for a widget whose picture changes without moving a box. Full account in
> `docs/CGUI_STYLE_RENDER_PIPELINE.md` §8.

> **What a compositor moves is recorded under a node** (render-graph G10). A scrolling box's content and a
> `will-change: transform` box (every desktop window) draw in their own space under a spatial node — a translation by
> whole device pixels, with `uiScale` left in the pose so text still snaps to the pixel grid — and a layer composited
> below full opacity under an effect node. `Box.movedNode`/`scrolledNode`/`fadedNode(frameId)` name them;
> `UiFrame.values()` moves them and `UiPresenter.redraw` draws the last frame again with nothing recorded — with
`keepRequested`, leaving every requested texture (a surface, a shader-graph preview) as the first execution
did. Under a node the
> pose stack is node-local: a decision about pixels asks `ctx.targetPose()`, never the stack. What a recording decided
> in the target's pixels stays as recorded (culling, layer regions, backdrop captures).
> `-Dcrystalgui.paint.nodes=false` records everything in the target's pixels.
>
> **A box's own paint is a segment** (render-graph G6): `BoxPainter` brackets what a box draws under its children and
> what it draws over them with `ctx.beginSegment()`/`endSegment()`, which flush at both edges, so a segment is exactly
> the chunks the recorder took between them. **A segment whose key holds is replayed, not painted** (G6.3): the key is
> the box's content revision, its pose in its spatial node, the visible part of its ink, the folded opacity and the
> target's size (text carries its own projection of the target), under `ctx.replayEpoch()` (a glyph page evicted, the
> text gamma changed, the icon atlas cleared, the kept snapshots dropped). A segment whose text drew while its glyphs
> were still generating is not kept, and a surface that drew some is walked again until they land. `endSegment(stretch)`
> keeps the chunks with the clip entries, shapes and snapshots they name (`CgReplay`), and `ctx.replay` adds them again,
> renumbered into the new recording. **A widget whose picture changes while its box does not calls `repaint()` or
> answers `paintsDynamically()`**; one that does neither shows its last picture, and
> `-Dcrystalgui.paint.replayCheck=true` finds it: every keyed box painted anyway, compared with what it kept, and the
> one that drew otherwise under an unchanged key named in the log; run it apart from the damage check, which it would
> defeat by painting every box. `-Dcrystalgui.paint.replay=false` paints every
> box; `-Dcrystalgui.paint.segments=false` turns the edges off.
>
> **A box the compositor moves draws into a surface of its own** (render-graph G7): a `will-change: transform` box
> (every window), one following the pointer and one the compositor animates paints its subtree into a kept texture
> (`Surface`, `ctx.surface`), composited under its node with its opacity and fade -- so a move or a fade leaves the
> texture as it is, and `UiPresenter.redraw` executes none. **A surface nothing changed inside is composited and not
> walked**: kept against the box's `innerRevision` (what changed or moved inside it, never where it is), its region in
> its node and the replay and stacking epochs, and only while nothing in it paints dynamically. **A surface walked
> again executes only its damage**: each segment places what it drew (`ctx.segmentDrawn`, the bounds of its draws)
> in the surface, and one that drew anew, moved or vanished damages its old and new places; a layer composited into it
> damages its region. The surface's passes are cut to the union (`CgRasterPass.damage`) -- an empty one executes
> nothing. **A surface holds the whole window at rest**: not cut to the screen (the clip cuts its composite), and
> without the box's own `transform`, which is drawn over its node with the composite -- so a window hanging off the
> edge, a minimise flight and a turn each leave the picture as it is. It is keyed by the window's node, and a
> `Surface.Owner` keeps it while unpainted: a minimised `WindowFrame` keeps its picture, which is what its taskbar
> preview draws (`WindowFrame.surface()`, `Surface.drawPicture`) -- thumbnails, previews and the switcher draw the
> surface and paint nothing of the window. `-Dcrystalgui.paint.damage=false` executes all of it; `-Dcrystalgui.paint.surfaces=false` draws into the
> frame; `-Dcrystalgui.paint.damageCheck=true` paints each window again, whole, beside its surface and logs
> `[damage-check]` where the two differ.

> **Every arm of the desktop draws at CrystalGraphics' GUI stages**: `CgRenderStage.HUD` over the in-game HUD and
> `CgRenderStage.SCREEN` over a screen, which CrystalGraphics' hosts fire once a frame. `CgUiScreen.install` puts
> the compositor on both at `CgUiScreen.ORDER`, its frame included in the firing at its place: the HUD arm on
> `HUD`; on `SCREEN` whichever arm is current -- our own screen's desktop (which draws nothing itself), the overlay
> over somebody else's, or the HUD arm on the frame a screen closed itself while it drew. One arm a frame. While the
> loading screen is up, `SCREEN` rehearses a throwaway desktop (`DesktopRehearsal`, `docs/CGUI_BUILDING_UIS.md` § 11b).
> In a gauntlet run
> `DesktopTest` draws a mark on both the way a mod does, and its `desktop.stage-screen` check fails a client whose screen stage never drew.

> **A rounded `overflow: hidden` is a per-draw clip, not a layer** (`pushRoundedClip`): geometric, as in CSS,
> so the background never masks the children; nested up to four deep and under any pose. Only a `mask`
> drawable, deeper nesting or a collapsed pose take the mask layers. `-Dcrystalgui.paint.roundedClip=false`
> takes them everywhere.

> **Opacity isolation and masking go through an FBO layer pass, not a flat multiply.** The
> tint-vs-layer-opacity distinction is the thing most likely to be got wrong here — read
> `docs/CGUI_STYLE_RENDER_PIPELINE.md` §5 and §8 before touching it.

### Supporting classes

- **`PaintSegments`**, **`PaintSurfaces`** — the paint context's segments and replay (G6), and its surfaces and
  damage (G7), behind the context's own methods (`beginSegment`, `replay`, `surface`, `beginSurface`...).
- **`CgUiBackdrop`** — `backdropFor`'s capture: the scene, the frame target and every enclosing layer composed
  into CrystalGraphics' `CgCanvasBackdrop` (the web's backdrop root), one capture a frame shared by every glass
  element and retaken where something was painted over it (`notePainted`). Allocates nothing per element; its
  answer is the context's one `Backdrop`, valid until the next call.
- **`UiPaintWarmup`** — the default font, the stylesheets' glyphs and the shipped icons, loaded off the render
  thread for `CgUiPaintContext.Warm`.
- **`ScissorStack`** — the paint context's allocation-free nested clip stack (16 levels), top-left rects
  flipped per target. It touches no GL: each level becomes a whole-pixel clip entry or a link in the recorder's
  scissor chain (`CgCanvas`), recorded in the node it was pushed under, so a moved node's clips move with it.
- **`FontFamilyCache`** — `(font-family stack, target px)` → `CgFontFamily`, cached. Reference
  equality on the result is therefore meaningful and is relied on by `UIText`. An entry is a resource
  path (`crystalgui:ui/fonts/x.ttf` — it holds a `:` or `/`, or ends in a font extension), an installed
  family name, or a generic family (`monospace`, `system-ui`); whatever the stack cannot draw comes from
  the installed fonts, per script, through CrystalGraphics' `CgSystemFonts` — as in a browser.
  **The tests run with `-Dcrystalgui.font.systemFonts=false`** (`core/build.gradle.kts`), so no result
  depends on the machine's fonts; `FontFamilyCache.useSystemFonts` hands a test its own. A Han
  character takes the language its own text shows (kana → Japanese, Hangul → Korean), else the
  player's: `CgI18n.locale()`, pushed into `FontFamilyCache.useLocale` by the desktop's screen when it changes.

### Drawables — `render/texture/`

`CgUiDrawable` is the pluggable "paint yourself into a rect" SPI:
`draw(ctx, mouseX, mouseY, x, y, w, h)`, plus `intrinsicWidth()`/`intrinsicHeight()` returning `-1`
when the drawable has no inherent size (solid colours, SDF shapes). One `draw` call is expected to
issue exactly one GPU draw call, or zero for a fully transparent tint.

| Class | Role |
|---|---|
| `CgUiRect` | **The one drawable behind every `background`** — a rectangle with a `Fill` (flat colour, one stretched texture, or a 9-slice sprite), optional per-corner elliptical radii and an optional border. `CgUiDrawable.EMPTY` is one, filled with colour 0. **Two draw paths, and which runs is not a style choice**: a plain rect (no radius, no border, no 9-slice) goes through the frame's own batch — `fillRect` for a colour, one quad for a texture — and a rect the batch cannot express is a quad naming its shape in the frame's `CgShapeTable`, drawn by the same `canvas/box.shader`, so the two batch together (until G5 it took its own SDF material with sixteen per-draw uniforms). That split is why merging `CgUiQuad` and `CgUiSprite` into it cost nothing: measured on `cgui-gallery`, the material binds, draw calls, flushes and buffer maps per frame are all unchanged (192/76/76/37), and `cgui-ore-theme` (since folded into `cgui-gallery`) was byte-identical. **Equality is by value for a flat fill and by identity otherwise**, which is what the two merged classes each did: the cascade discards a pushed candidate equal to the one present (so a repeated `background` write cannot retarget a live transition), while `TextureValue.sourceOf` is a weak map keyed by equality, where a value-equal drawable that cannot describe itself loses its CSS |
| `CgUiSprite` | **A FILL, not a drawable** — `sprite.toRect()` is what a caller owing a `CgUiDrawable` wants. Full 9-slice textured sprite (`setTexture`/`setSprite`/`setBorder`, lazy UV cache). **It draws as ONE quad, not nine** — through `canvas/box.shader`'s nine-slice shape, the same nine-region remap done per pixel. Nine quads shared eight interior seams, and a seam is either hard (a staircase, once the sprite is off-axis) or softened from both sides (three-quarter coverage, a hairline); neither is fixable per quad, because each would have to know what its neighbour drew. It is also **2.5-3x cheaper**, measured on `cgui-sprite-stress`: the nine-quad path held material binds to 6 a frame against 1506 and still lost, because nine instances per sprite against one is what dominates (`quadRenderer.flush` drops tenfold) — the same shape as `SvgRasterCache`. Verified equal, on scenes since folded into `cgui-gallery`: `cgui-ore-theme` was byte-identical either way, and `cgui-nineslice`'s two columns agreed in all four tiling modes where `round` used to disagree by a pixel. A borderless sprite goes down the same sliced path, where zero borders degenerate to one region stretching the sprite's own sub-rect — the old wrap handed the raw texture to a plain fill and dropped the sub-rect, so an atlas sprite with a `border-radius` sampled the whole sheet. In that shader the seams are **supersampled**, not texel-filtered: a seam is a line in box-local pixels, and the texel filter reconstructs from `fwidth` in TEXEL space, which is meaningless where a stretched centre meets a 1:1 border. **There is no nine-quad path left** — a missing texture draws one stretched copy of the fallback checkerboard, which is what it always effectively was (`submit` drops the UV crop, so the nine pieces were nine copies of the whole checkerboard) and is the one case the sliced path cannot serve: it would sample the sprite's atlas UVs into an 8x8 fallback and read as a flat colour |
| `CgUiCrossFade` | Blends two drawables, for `background` transitions |
| `CgUiLayerBox` | Composites a stack; resolves `overlay-size` via `intrinsicWidth()` |
| `CgUiRepeat` | Tiling modes |
| `ArgbMath` | Shared colour maths |
| `CgUiSvg` | Draws an `SvgDocument` into a rect — fitted and centred, never stretched. See below |
| `CgUiGradient` | `linear-gradient(direction, stops…)` and `radial-gradient(shape at position, stops…)` (the `RADIAL` keyword; circle or ellipse, CSS's extents resolved per box by `radiiFor`) — CSS's, at **any angle** (`deg`/`turn`/`rad`, `to <side>`, `to <corner>` resolved per box): ONE draw through `canvas/gradient.shader` evaluating up to eight stops per fragment along CSS's gradient line (Skia's unrolled shape; more stops are more draws, each owning a window of *t*), interpolated **premultiplied** (CSS Images 3 — `transparent` is transparent black, and a straight lerp toward it passes through a dark half-colour), **dithered** ±0.5/255 (per-fragment mixing removes the strip edges the first version had, not the level edges), and `CornerRadiusAware`, so it masks itself under a `border-radius` (the self-clipping gap applies: no `border-width` stroke). Measured on `cgui-gradient-probe`: a 16-level ramp's column means step 0.23 levels at most; the fade to transparent matches the premultiplied prediction to 0.1 level. First consumer: the taskbar's accent glow |
| `CgUiTransformDrawable` | Empty marker class — not implemented |

`render/texture/asset/CgUiSpriteRegistry` resolves `"namespace:name"` → sprite lazily from
`assets/{ns}/ui/sprites/{file}.json`. **A resource pack ships a theme by shipping JSON + PNG** — no
registration call. This is what `background: asset("crystalgui:ore", "button")` goes through.

`render/texture/svg/` is a **full SVG renderer** — scanner, path grammar, transforms, colour, inheritance,
scanline fills with holes cut, and real linear/radial gradients — parsing an `.svg` once into a cached list
of draw ops: strokes through `ctx.curve()`, fills as one `ctx.filledCell()` per scanline cell with
exact-area coverage on the edges that are on the outline. Full account in `ICONS.md`, including the nine
things it deliberately does not implement and why none of them matters for icons.

> **A fill at icon size is rasterised once and drawn from a texture** — `SvgRasterCache`, for any
> document at most 128 device px tall under an axis-aligned, uniform pose: 1:1 on the pixel grid, and off it
> (an icon mid-animation) filtered from its exact raster or the next of 16 sizes a doubling, so a moving icon
> looks as it does at rest, shadow included, rather than flickering to the direct path. A cell thinner
> than a pixel cannot be composited on its own: two cells each covering half a pixel blend to three
> quarters, so a direct draw has to let one cell claim the whole pixel and guess the rest of its boundary,
> which is wrong wherever the outline bends inside the pixel — every corner of a 16-unit icon at 10 to
> 14 px. Accumulating the cells' exact areas additively into an RGBA16F atlas is exact, and the raster is
> coverage rather than colour, so one serves every tint. Measured on `nodes/java/package` at 8/10/12/14/16
> logical px on a 2x display: within 0.7 levels of a 256-sample CPU raster, which is where IntelliJ's own
> raster of the file sits. Strokes are cached too (max-blended), so a cached icon is textured quads only.
> Larger draws take the direct path, where a cell is bigger than a pixel. Measured on the 48-icon grid:
> 2.1 ms/frame cached against 4.2 ms drawing the cells directly, and no curve instances per frame at all
> against 6,500. The testbed is the strip at the top of `--mode=cgui-svg-icon`; `plan/svg-fix/truth.py`
> scores a capture; `-Dcrystalgui.svg.raster=false` draws everything direct for comparison.
>
> **A drop shadow is drawn there too, under its group**: an element's `filter` that is a drop shadow resolves to
> `SvgScene.DropShadow`, shared by the nodes it covers; `drawShadow` blurs their coverage once with the backdrop's
> passes into a texture of its own (a filter pass clears its whole target, so never an atlas slot) and draws it under
> the flood colour. Above icon size it is skipped, with a log line.

> **Two seams here are easy to get backwards, and both exist for the same reason: a document is shared.**
> `currentColor` is left unresolved in the cached ops and bound at draw time, so one parsed icon backs a
> selected row and an unselected one in the same frame. And `CgUiSvg` is a separate class rather than
> `SvgDocument implements CgUiDrawable`, because `draw()` has no tint parameter — a document implementing
> it would need a mutable tint field, and the two rows above would be writing to the same one.

`render/texture/geometry/` holds `Position` and `Size`, small Lombok `@Data(staticConstructor="of")`
int value types.

---

Also loaded with this folder:

@../../../../../../../CrystalGraphics/AGENTS.md
