# `render` — recorded, executed once

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../AGENTS.md), which keeps the rules every session needs.

## Stack 5: Render — recorded on CrystalGraphics' canvas

CrystalGUI paints through CrystalGraphics' `CgCanvas` (`api.canvas`, `CrystalGraphics/docs/UI.md`): state as
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

CgRenderStage.SCREEN.register(CgRenderStage.SCREEN_WIDGETS, stage ->
        view.presenter().present(stage, frame));  // render thread: included in the firing, composited
view.close();                                     // render thread, with the document
```

A host never writes that by hand: `CgUiViewport.frame(stage, content)` records into its view and presents it, and
`CgUiViewport.close` closes the view; `CgUiScreen` paints the desktop on both GUI stages.

| Group | Methods |
|---|---|
| Frame | `recordFrame(w, h)` / `seal()`, `abortFrame()` for a paint that threw, `frameId()` |
| Canvas | `canvas()`: the frame's `CgCanvas`. Placing, clipping and fading are its `save`/`setTransform`/`translate`/`clip`/`opacity`/`restore`; a group is `layer(...)` |
| Draw | nothing of its own: every item is the canvas's (`fill`, `box(...)`, `image`, `spline()`, `textDraw()`), and every item, text included, takes the canvas's opacity |
| Pixels | the canvas's too: `deviceScale()`, `rectilinear()`, `snapX`/`snapY`, `outsideClip(...)`, `degradedDraws()` |
| Retention | `beginSegment`/`endSegment`/`replay`, `segmentDrawn`, `replayEpoch`; `surface(key, x0, y0, x1, y1)`, `withoutRetention(body)` |
| Textures | `requestTexture(name, w, h)` / `releaseTexture`, `update(texture, x, y, w, h, rgba)`, `passes(recording -> ...)` for a caller's own passes |

> **Placing is the canvas's state, saved and restored.** A painter that moves, clips or fades brackets it:
> `ctx.canvas().save().opacity(a).clip(x, y, w, h)` ... `restore()`. Nothing in the context pushes or pops.
> `BoxPainter` sets each box's transform as `base × localToWorld` under a save, so a box's pose is never a stack.

> **A rounded or bordered rect is a canvas box**: `ctx.canvas().box(x, y, w, h).radius(6f).border(1f, edge).fill(bg)
> .draw()`, built and drawn in one expression. A `CgCornerRadii` a painter draws every frame is kept while it holds
> (`BoxPainter` keeps a box's on the `Box`), so a shaped element allocates nothing.

> **A view's textures are freed by its `close()`, and by `CgUiRenderer.contextDestroyed()` for one left open**
> (`CgUiClientLifecycle.onContextDestroy` calls it): the recorder's picture, kept surfaces and requested textures.
> A frame recording on a document's own thread is waited for, two seconds at most, and the image painter's resolved
> pictures, sheets and icons are forgotten with the textures under them.
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
> back to a path mask. **Opacity over overlapping children and a `mask` image are a group layer**
> (`layer(ink).opacity(a)`, `maskWith`); a lone draw's opacity is a flat multiply. The tint-vs-layer-opacity
> distinction is the thing most likely to be got wrong here: `docs/CGUI_STYLE_RENDER_PIPELINE.md` §5 and §8.

> **Glass captures once a turn.** `backdrop-filter` is a `CgBackdropFilter` on a canvas box, whose capture serves every
> pane after it until something is painted over what a pane samples or a layer opens (CrystalGraphics' retake rule),
> captured over the host's frame (`CgCanvasRecorder` sets it as the canvas's `behind`).

### Supporting classes

- **`PaintSegments`**: the context's segments over `CgPaintChunk`, and their replay.
- **`Surface`**: a box's kept picture over `CgKeptLayer`, its key and its `Owner`.
- **`InkOverflow`**: ink bounds past a box, for layers and surfaces sized to what they hold.
- **`UiPaintWarmup`** — the default font, the stylesheets' glyphs and the shipped icons, loaded off the render
  thread for the loading screen (`UiPaintWarmup.preload`).
- **`FontFamilyCache`** — `(font-family stack, target px)` → `CgFontFamily`, cached. Reference
  equality on the result is therefore meaningful and is relied on by `UIText`. An entry is a resource
  path (`crystalgui:ui/fonts/x.ttf` — it holds a `:` or `/`, or ends in a font extension), an installed
  family name, or a generic family (`monospace`, `system-ui`); whatever the stack cannot draw comes from
  the installed fonts, per script, through CrystalGraphics' `CgSystemFonts` — as in a browser.
  **The tests run with `-Dcrystalgui.font.systemFonts=false`** (`core/build.gradle.kts`), so no result
  depends on the machine's fonts; `FontFamilyCache.useSystemFonts` hands a test its own. A Han
  character takes the language its own text shows (kana → Japanese, Hangul → Korean), else the
  player's: `CgI18n.locale()`, pushed into `FontFamilyCache.useLocale` by the desktop's screen when it changes.

### Images — `render/image/`

**`CgUiImage` is what `background`, `overlay`, `mask` and `outline` hold**: CSS's `<image>`, a sealed set of
records, immutable and equal by value, each writing its own CSS (`css()`). **`CgUiImagePainter` is the one place an
image becomes draws**; `BoxPainter` owns one.

```java
CgUiImage.color(0xFF2B2D30);                         // a flat colour
CgUiImage.icon("crystalgui:folder");                  // a named icon: its light or dark drawing chosen as it draws
CgUiImage.findIcon(name);                             // the same, or null when the file will not draw
CgUiImage.Picture.of("mymod:textures/gui/panel.png").reference(256, 256).region(29, 1, 13, 13).slices(1, 1, 11, 11);
CgUiImage.mark(CgUiMark.CHEVRON_DOWN);                // a mark, in the element's `color`
ColorField.ramp(from, to).corners(2f).image();        // a hand-drawn painter: CgUiPainter
element.generalStyle(g -> g.overlay(CgUiImage.findIcon("crystalgui:save")));

images.image(ctx, image, x, y, w, h).color(color).radii(radii).draw();      // what BoxPainter does
images.fill(ctx, argb, 0, 0, w, h).border(1, 1, 1, 1, l, t, r, b).draw();    // a background colour, bordered
images.fit(image, x, y, w, h, DrawableFit.CONTAIN, align, out);            // where a sized image lands
```

| Kind | CSS | Draws as |
|---|---|---|
| `Color`, `Paint`, `Gradient`, `Grid` | a colour, `linear-`/`radial-gradient(...)`, `grid(...)` | one canvas box filled with the paint, the element's radii and border on it |
| `Picture` | `image(...)`, `sprite(...)` | a box filled with its `CgBrush` (region, `CgNineSlice`, `CgTiling`); its texture loaded nearest |
| `Sheet` | `asset("sheet", "element")` | `CgSpriteSheet`'s brush, re-read when a reload moves `CgSpriteSheet.generation()` |
| `Icon` | `icon("name", ...)` | `canvas.image(CgBrush.svg(...))`, its document re-read on a variant or `CgSvg.generation()` change; snapped where a hinted icon is |
| `Mark` | `shape("checkmark")` | `CgUiMark`'s path, in `color` |
| `Painted` | `paint(name)` | `CgUiPainter.paint(canvas, w, h, color)`; `CgUiPainter.register` names one for CSS |
| `CrossFade`, `Layers` | `cross-fade(...)`, a comma list | both added in a layer at `1 - t` and `t`, the border once; each layer, the first on top |

- **Everything resolved is cached by value, shared by every painter**: a picture's texture and brush, a sheet's
  brush, an icon's document. A frame allocates nothing.
- **Transitions interpolate**: two colours mix premultiplied, two gradients of one shape slide; anything else is a
  `CrossFade`. Equal values never retarget a transition.
- **A colour with no image is `background-color`**; with an image it tints it. `overlay-tint` multiplies an icon's or
  a picture's own colours.
- **Every layer is clipped to the element's corners and the border is drawn once over them**, as in CSS.
- An image built in Java with no CSS spelling (a painter, a `Paint`) throws from `css()`; the builder's copy
  (`ImageParts`) copies it whole.
- CSS functions are `ImageKinds`, which a mod extends (`ImageKinds.register`); the value is `ImageValue`, the property
  `ImageProperty`. `ArgbMath` is the colour maths; `render/texture/asset/FileIconTheme` the file-icon theme.

> **Every static collection in `core` or `language` names its thread rule** (`// Threads:`, or is concurrent/synchronized):
> `SharedStateTest` fails the build otherwise. Several documents run style, layout and paint at once.

> **`currentColor` is bound when an icon draws, never in its document**: one parsed icon backs a selected row and an
> unselected one in the same frame, each passing its own colour to `CgBrush.svg(...).color(argb)`.

---

Also loaded with this folder:

@../../../../../../../CrystalGraphics/AGENTS.md
