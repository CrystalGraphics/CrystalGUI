# `render` — recorded, executed once

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../AGENTS.md), which keeps the rules every session needs.

## Stack 5: Render — recorded on CrystalGraphics' canvas

CrystalGUI paints through CrystalGraphics' `CgCanvas` (`api.canvas`, `CrystalGraphics/docs/CANVAS.md`): state as
`save`/`restore`, clips, layers, kept layers, chunks and glass are the canvas's. What stays here is the box tree's
side: which box paints when, segment keys, surfaces and their owners.

### `CgUiRenderer`, `CgUiRenderer.View`, `CgUiPaintContext`

**`CgUiRenderer` is the one global part**: the UI's pipelines named to the precache, a `CgUiRenderer.View` handed out
per place a document is drawn (`openView`), and every open view's textures freed when the graphics context goes
(`contextDestroyed`). **A view is per document**: its `CgUiPaintContext`, made at the first `paintContext()`, and its
`CgCanvasPresenter`, closed together (`close()`, render thread). **A `CgUiViewport` owns its document's view**; the
document owns none, since it is the DOM and runs on a dedicated server. A view is never shared: each document records
on its own thread, and its segments and surfaces are keyed to its tree. The icon atlas (`CgPathCache`) is the
exception: one for the process, shared by every canvas.

**The context records a frame on a `CgCanvasRecorder`**: `recordFrame` begins it, `seal()` answers a `CgCanvasFrame`
that refers back to nothing, and the view's presenter shows it at a CrystalGraphics GUI stage (`SCREEN` or `HUD`),
included in the firing and composited over the host's frame. **CrystalGUI holds no GL state and makes no GL call**: a
texture paint keeps across frames is `requestTexture`'s, written by `update`, freed by `releaseTexture`.

```java
CgUiRenderer.View view = CgUiRenderer.openView();
CgUiPaintContext ctx = view.paintContext();       // first call on the render thread
ctx.recordFrame(w, h);                            // no GPU from here: any thread the document is framed on...
document.paint(ctx);
CgCanvasFrame frame = ctx.seal();                 // ...to here

CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, stage ->
        view.presenter().present(stage, frame));  // render thread: included in the firing, composited
view.close();                                     // render thread, with the document
```

A host never writes that by hand: `CgUiViewport.frame(stage, content)` records into its view and presents it, and
`CgUiViewport.close` closes the view; `CgUiScreen` paints the desktop on both GUI stages.

| Group | Methods |
|---|---|
| Frame | `recordFrame(w, h)` / `seal()`, `abortFrame()` for a paint that threw, `frameId()` |
| Canvas | `canvas()`: the frame's `CgCanvas`. Placing, clipping and fading are its `save`/`setTransform`/`translate`/`clip`/`opacity`/`restore`; a group is `layer(...)` |
| Draw | `fillRect`, `drawImage`, `rect()` (a rounded or bordered rect), `text()` (`canvas().textDraw()`); everything else draws on the canvas |
| Pixels | `deviceScale`, `isPoseAxisAligned`, `snapXToDevicePixel`/`snapYToDevicePixel`, `isVisible`, `outsideClip`, `targetWidth`/`targetHeight`, all read from the canvas's transform and clip |
| Retention | `beginSegment`/`endSegment`/`replay`, `segmentDrawn`, `replayEpoch`; `surface(key, x0, y0, x1, y1)`, `withoutRetention(body)` |
| Textures | `requestTexture(name, w, h)` / `releaseTexture`, `update(texture, x, y, w, h, rgba)`, `beginPasses`/`endPasses` for a caller's own passes |

> **Placing is the canvas's state, saved and restored.** A painter that moves, clips or fades brackets it:
> `ctx.canvas().save().opacity(a).clip(x, y, w, h)` ... `restore()`. Nothing in the context pushes or pops.
> `BoxPainter` sets each box's transform as `base × localToWorld` under a save, so a box's pose is never a stack.

> **`rect()` is the only place a rounded or bordered rect is drawn.**
> `ctx.rect().at(x,y).size(w,h).radius(6f,6f).border(1f,edge).fillColor(bg).submit()`: build and `submit()` in one
> expression, never hold it. **`CgUiRect` is a VALUE and drawing one must not build one**: its `with` methods answer
> copies, right for what the cascade shares and ruinous per element per frame. `BoxPainter` composes the element's
> radii, border and fill straight into this scratch, so a shaped element allocates nothing.

> **A view's textures are freed by its `close()`, and by `CgUiRenderer.contextDestroyed()` for one left open**
> (`CgUiClientLifecycle.onContextDestroy` calls it): the recorder's picture, kept surfaces and requested textures.
> Not what is borrowed from CrystalGraphics' registries (materials, font atlases): `destroyContext()` sweeps those, and
> freeing them here would be a double free.

> **What a compositor moves is recorded under a node.** A scrolling box's content and a `will-change: transform` box
> (every desktop window) draw under the canvas's `movable(...)` node, a translation by whole device pixels; a box
> fading under the compositor under a `fader`. A frame's values move them, so a scroll or a window drag redraws the
> last frame with nothing recorded. Scroll-exempt children re-enter their parent's node (`enter(node)`).
> `-Dcrystalgui.paint.nodes=false` records everything in the target's pixels.

> **A box's own paint is a segment, kept as a `CgPaintChunk`.** `BoxPainter` brackets what a box draws under its
> children and over them with `beginSegment`/`endSegment`. **A segment whose key holds is replayed, not painted**: the
> key is the box's content revision, its transform (six floats), its clipped ink and its opacity, under
> `replayEpoch()` (text gamma, a glyph eviction). The canvas refuses to keep a chunk whose draws cannot outlive the
> frame (text drawn while its glyphs were generating, a one-frame texture), and the segment is painted again until
> they land. **A widget whose picture changes while its box does not calls `repaint()` or answers
> `paintsDynamically()`**; one that does neither shows its last picture, and `-Dcrystalgui.paint.replayCheck=true`
> finds it: every keyed box painted anyway, compared with what it kept (`CgPaintChunk.differs`), the one that drew
> otherwise named in the log. `-Dcrystalgui.paint.replay=false` paints every box; `-Dcrystalgui.paint.segments=false`
> turns the edges off.

> **A box the compositor moves draws into a surface: a canvas `CgKeptLayer`.** A `will-change: transform` box (every
> window), one following the pointer and one the compositor animates paints its subtree once into a kept picture,
> composited under its node with its opacity and fade, so a move or a fade leaves the picture as it is. **A surface
> nothing changed inside is composited and not walked**: kept against the box's `innerRevision`, its region in its
> node and the replay and stacking epochs. **A surface walked again executes only its damage**: each segment's
> placement (`CgKeptLayer.Placement`) damages its old and new places when it drew anew, moved or vanished. **A surface
> holds the whole window at rest**, not cut to the screen and without the box's own `transform`, which is drawn over
> its node, so a window off the edge, a minimise flight and a turn leave the picture as it is. A `Surface.Owner`
> keeps it while unpainted: a minimised `WindowFrame`'s taskbar preview draws `Surface.drawThumbnail`, painting
> nothing of the window. A surface past 4096 px a side, or under `withoutRetention`, is not kept.
> `-Dcrystalgui.paint.damage=false` executes all of it; `-Dcrystalgui.paint.surfaces=false` draws into the frame.

> **Every arm of the desktop draws at CrystalGraphics' GUI stages**: `CgRenderStage.HUD` over the in-game HUD and
> `CgRenderStage.SCREEN` over a screen. `CgUiScreen.install` puts the compositor on both at `CgUiScreen.ORDER`: the HUD
> arm on `HUD`; on `SCREEN` whichever arm is current -- our own screen's desktop, the overlay over somebody else's, or
> the HUD arm on the frame a screen closed itself while it drew. One arm a frame. While the loading screen is up,
> `SCREEN` rehearses a throwaway desktop (`DesktopRehearsal`, `docs/CGUI_BUILDING_UIS.md` § 11b). In a gauntlet run
> `DesktopTest` draws a mark on both the way a mod does, and its `desktop.stage-screen` check fails a client whose
> screen stage never drew.

> **A rounded `overflow: hidden` is a canvas clip, not a layer** (`clip(rrect)`, the box's radii cached on the
> `Box`): geometric, as in CSS, so the background never masks the children. Past the canvas's clip depth it falls
> back to a path mask. **Opacity over overlapping children and a `mask` drawable are a group layer**
> (`layer(ink).opacity(a)`, `maskWith`); a lone draw's opacity is a flat multiply. The tint-vs-layer-opacity
> distinction is the thing most likely to be got wrong here: `docs/CGUI_STYLE_RENDER_PIPELINE.md` §5 and §8.

> **Glass captures once a turn.** `CgUiBackdropFilter` draws through the canvas's glass, whose capture serves every
> pane after it until something is painted over what a pane samples or a layer opens (CrystalGraphics' retake rule),
> captured over the host's frame (`CgCanvasRecorder` sets it as the canvas's `behind`).

### Supporting classes

- **`PaintSegments`**: the context's segments over `CgPaintChunk`, and their replay.
- **`Surface`**: a box's kept picture over `CgKeptLayer`, its key and its `Owner`.
- **`InkOverflow`**: ink bounds past a box, for layers and surfaces sized to what they hold.
- **`UiPaintWarmup`** — the default font, the stylesheets' glyphs and the shipped icons, loaded off the render
  thread for `CgUiPaintContext.Warm`.
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
| `CgUiRect` | **The one drawable behind every `background`** — a rectangle with a `Fill` (flat colour, one stretched texture, or a 9-slice sprite), optional per-corner elliptical radii and an optional border, drawn as one canvas `box` with that fill as its brush. `CgUiDrawable.EMPTY` is one, filled with colour 0. **Equality is by value for a flat fill and by identity otherwise**: the cascade discards a pushed candidate equal to the one present (so a repeated `background` write cannot retarget a live transition), while `TextureValue.sourceOf` is a weak map keyed by equality, where a value-equal drawable that cannot describe itself loses its CSS |
| `CgUiSprite` | **A FILL, not a drawable** — `sprite.toRect()` is what a caller owing a `CgUiDrawable` wants. A 9-slice textured sprite (`setTexture`/`setSprite`/`setBorder`) whose `brush()` is a `CgBrush` with a `CgNineSlice`: **one draw, not nine**, the nine-region remap done per pixel, so no interior seam can staircase or soften. A borderless sprite is the same brush with zero borders, its atlas sub-rect kept; an unnamed repeat pair takes the horizontal mode |
| `CgUiCrossFade` | Blends two drawables, for `background` transitions |
| `CgUiLayerBox` | Composites a stack; resolves `overlay-size` via `intrinsicWidth()` |
| `CgUiRepeat` | Tiling modes |
| `ArgbMath` | Shared colour maths |
| `CgUiSvg` | Draws an SVG into a rect — fitted and centred, never stretched. See below |
| `CgUiGradient` | `linear-gradient(direction, stops…)` and `radial-gradient(shape at position, stops…)` (the `RADIAL` keyword; circle or ellipse, CSS's extents resolved per box by `radiiFor`) — CSS's, at **any angle** (`deg`/`turn`/`rad`, `to <side>`, `to <corner>` resolved per box): ONE draw through `canvas/gradient.shader` evaluating up to eight stops per fragment along CSS's gradient line (Skia's unrolled shape; more stops are more draws, each owning a window of *t*), interpolated **premultiplied** (CSS Images 3 — `transparent` is transparent black, and a straight lerp toward it passes through a dark half-colour), **dithered** ±0.5/255 (per-fragment mixing removes the strip edges the first version had, not the level edges), and `CornerRadiusAware`, so it masks itself under a `border-radius` (the self-clipping gap applies: no `border-width` stroke). Measured on `cgui-gradient-probe`: a 16-level ramp's column means step 0.23 levels at most; the fade to transparent matches the premultiplied prediction to 0.1 level. First consumer: the taskbar's accent glow |
| `CgUiTransformDrawable` | Empty marker class — not implemented |

`render/texture/asset/CgUiSpriteRegistry` resolves `"namespace:name"` → sprite lazily from
`assets/{ns}/ui/sprites/{file}.json`. **A resource pack ships a theme by shipping JSON + PNG** — no
registration call. This is what `background: asset("crystalgui:ore", "button")` goes through.

`CgUiSvg` draws through the canvas: `CgBrush.svg(CgSvg.of(path))`, its colour the tint times the context's (its
`currentColor`), a monochrome icon as `flat(width)`. CrystalGraphics rasterises an icon at most 128 device px across
into the shared path atlas (`CgPathCache`) and draws it as textured quads; a drop shadow on the pixel grid is filtered
once into a cell there too. Larger, turned or between-pixel draws are rasterised that frame.
`render/texture/svg/` still parses `.svg` for CrystalGUI's own users; its drawing moved to CrystalGraphics (`ICONS.md`).

> **Every static collection in `core` or `language` names its thread rule** (`// Threads:`, or is concurrent/synchronized):
> `SharedStateTest` fails the build otherwise. Several documents run style, layout and paint at once.

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
