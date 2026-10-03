# Shipped assets — `assets/crystalgui/`

> Not loaded automatically: nothing may sit beside the assets, since everything under `resources/` ships. Read it before touching anything in `core/src/main/resources/assets/crystalgui/`. Moved verbatim from [`AGENTS.md`](../AGENTS.md).

## Shipped assets

`core/src/main/resources/assets/crystalgui/`

| Path | Notes |
|---|---|
| `ui/styles/ua/*.css` | **User-agent sheet, in thirteen domain parts** (core, widgets, editor, overlays, config-kit, inspector, workbench, panels, search, samples, uibuilder, profiler, desktop) concatenated in `StyleSheetRegistry.DEFAULT_SHEET_PARTS` order into `StyleSheet.DEFAULT` — one sheet, one parse, one variable scope, and cross-part order is as load-bearing as order within a file. Functional geometry for every widget with no theme loaded; every colour is `var(--token, #fallback)`. Was a single 6,200-line `default.css` until plan/style-overhaul.md step 8. |
| `ui/themes/base.css`, `ui/themes/crystal-dark.css` | The token tables: component→system derivations, and the default theme (pins today's look exactly). See `docs/CGUI_THEMING.md`. |
| `ui/schemes/dark-plus.css` | The default editor colour scheme — the second, independently-selectable axis. |
| `ui/styles/ore.css` | Minecraft Ore UI theme, ported from LDLib2's `ore.lss`. |
| `ui/styles/graph.css` | Node-graph theme — Unity Shader Graph's look, including the per-type port palette every wire reads its colour from. |
| `ui/styles/filetypes.css` | Per-file-type colour palette, keyed on the `.filetype-*` class `FileIconTheme.classFor` returns. **Not** in `default.css` — that is the UA sheet and carries geometry only. |
| `ui/styles/decorations.css` | Decoration palette, keyed on the `decoration-*` class a `FileDecoration` names. Same split, same reason. |
| `ui/icons/*.svg` | Feather icons (MIT), stroked `currentColor` chrome marks. `icon("crystalgui:folder")` in CSS. |
| `ui/icons/filetypes/*.svg` | 50 IntelliJ Platform icons (Apache 2.0), filled, 16px, carrying their own palette. What the file tree draws. |
| `ui/icons/default.json` | The default file-icon theme: extension/name → icon. Colour is deliberately not in it. |
| `ui/icons/ATTRIBUTION.md` | **An obligation, not documentation** — MIT and Apache 2.0 both require notices to travel with the distribution. Indexed from the repo-root `THIRD-PARTY.md`. |
| `sources/**` | **Not in `src/main/resources` — injected by `tasks.jar`** (M13 §25.4), so it exists only in the built jar. 601 `.java` files, 1.84 MB, read by `SourceArchives.ResourceArchive` so the documentation popup quotes an author's real declaration and javadoc instead of reassembling one from the binding. **The prefix is a convention any mod can use and nothing registers**: `BundledSources` SCANS the classpath for `assets/<namespace>/sources/`, so a mod makes its own API quotable by shipping its sources and nothing else. One namespace per project rather than one shared directory, because CrystalGraphics — which ships its own the same way — is used by mods with no CrystalGUI in the pack. |
| `ui/sprites/ore.json` | Sprite definitions backing `ore.css`. |
| `textures/gui/ore_styles.png` | Ore theme atlas. |
| `textures/gui/gdp_styles.png` | **Unreferenced by any code today.** |
| `textures/gui/Spritesheet_UI_Flat.png` | Unreferenced by any stylesheet today. |
| `ui/fonts/Minecraft.otf`, `MinecraftRegular.otf` | Public-domain MC fonts. |
| `ui/fonts/IBMPlexSans-Regular.ttf`, `JetBrainsMono-Regular.ttf` | The UI face and the code face, SIL OFL 1.1, each with its licence beside it (`IBMPlexSans-OFL.txt`, `OFL.txt`). IBM Plex moved here from CrystalGraphics on 2026-09-11, which now ships no fonts: anything neither face covers comes from the installed fonts (`CgSystemFonts`). |
| `shaders/gui_box.shader` | **Every box the UI draws, in one material** (render-graph G5): a fill, a texture, a rounded or bordered rect, a nine-slice sprite, a cached icon and a composited layer are each a quad whose `custom2` names its shape in the frame's `CgShapeTable` (`ctx.shapes()`), so they batch and part only where their textures differ. Premultiplied out. A quad drawn while `layerBlitMaterial` is current is stamped `PREMULTIPLIED`. Bound by `beginFrame`. **Every quad material here antialiases its own edges when rotated or sheared, with no MSAA** — the `CG_QUAD_EDGE_*` helpers in `env/buffer/quad.glsl`, injected by `#pragma cg_use quad` (padded geometry, exact-area coverage per edge), `cg_texel_aa_sample` from `lib/texel.glsl` for pixel art, and a wider `sdf_coverage` ramp for the SDF materials; see `CrystalGraphics/docs/SHADERS.md` § *Engine Buffers*. Axis-aligned content is untouched, measured pixel-identical. The `edges` page of `cgui-gallery` shows every material rotated and skewed. |
| `shaders/gui_curve.shader` | Bézier strokes, via `ctx.curve()`; filled triangles and quads share it. Declares `#pragma cg_use curve`, not `quad`. |
| `shaders/gui_curve_coverage.shader`, `gui_curve_coverage_max.shader`, `gui_curve_accumulate.shader` | `gui_curve.shader` with the blend `SvgRasterCache` needs: cells **summed** into alpha as straight-alpha white coverage (`Blend ONE ZERO, ONE ONE`), stroke segments **maxed** (`BlendEquation MAX` — segments overlap at joints), and a fill with its own colours summed premultiplied (`Blend ONE ONE`). A Pass's `RenderState` cannot vary per keyword, hence three files. |
| `shaders/gui_gradient.shader` | A whole `linear-gradient()` in one draw: eight premultiplied stops as properties, the unrolled ramp per fragment along `_Axis` (CSS's gradient line), a `_Window` of *t* so a longer gradient's extra draws never write a fragment twice, `WITH_MASK` for the rounded-box SDF, and half a level of `hash12` dither as the LAST thing before the target quantises. `Blend ONE ONE_MINUS_SRC_ALPHA` — premultiplied out, like the layer blit and unlike `gui_quad`. |
| `shaders/gui_downsample.shader` | The box prefilter behind `backdrop-filter`: reduces the captured sub-rect 2x or 4x before it is blurred (four bilinear taps cover the block behind each output texel). Without it the Gaussian read a full-resolution source at a stride and was a comb — text came through as vertical streaks. |
| `shaders/gui_blur.shader` | One axis of the separable Gaussian behind `backdrop-filter`, **kernel derived from sigma**: taps one source texel apart, `ceil(3σ)` of them per side, weights by the incremental recurrence and renormalised. **`LINEAR_KERNEL`** (on by default; `-Dcrystalgui.glass.linearKernel=false` for the old loop) reads the same Gaussian as Skia's linear-sampled pairs from `LinearBlurKernel` — one bilinear fetch per two texels. `CgUiBackdrop` picks the working scale (1/2/4) from σ — Skia's scale-then-blur — so the loop stays short. **Helpers go ABOVE `void vertex`** or they never reach the fragment stage. |
| `shaders/gui_backdrop_filter.shader` | Liquid glass: refract → pick blurred/sharp → saturate → **luminosity** (W3C SetLum toward the tint's brightness — the layer WinUI's acrylic and Mica are mostly made of) → tint → specular → noise → SDF mask. Every optional layer is a `#pragma cg_feature`. |

> **`gui_curve.shader` holds no stroke maths** — it `#include`s `crystalgraphics:shaders/lib/stroke.glsl`,
> which is shared verbatim with the engine's own `curve.shader`. The two materials differ in exactly
> three things: `DepthTest ALWAYS` (UI paints in painter's order over whatever the world left in the
> depth buffer — `LEQUAL` is right for a 3D stroke and wrong here), the `_LayerOpacity` property, and the
> one line that multiplies it in. **A Pass's `RenderState` is fixed at author time and cannot vary per
> keyword variant**, which is why this cannot collapse into one material with a `#pragma cg_feature` —
> the same constraint `text.shader` documents about its own depth state.
>
> It was briefly a full copy of the fragment body, which is worth recording because the cap logic in
> there was wrong three separate times: two copies means the fourth fix lands in one file and the other
> keeps the bug, silently, while still rendering something plausible.

> **All three declare `#pragma cg_use quad`, and any new CrystalGUI shader must too.** Everything
> here draws through `CgQuadRenderer`, whose per-instance buffer supplies `CG_QUAD_WORLD_POS` /
> `CG_QUAD_UV` / `CG_QUAD_COLOR` — the pragma is what wires it, during parsing, before anything can
> compile. Omitting it is a parse error naming the missing line (it used to be a GLSL error about an
> undefined `QUAD_DATA`, reported four layers up as an unrelated `#pragma cg_feature` complaint).
> Never attach the buffer from Java. See `CrystalGraphics/docs/SHADERS.md` § *Engine Buffers*.

> **A `#include` in a `.shader` is compiled into the vertex stage as well as the fragment stage.**
> The material compiler hoists every material-scope `#`-line into both. `gui_box.shader` is
> one of several shipped shaders with an include, and its `sdf.glsl` needed a stage guard around
> `sdf_coverage` — `fwidth` is fragment-only, NVIDIA accepted it anyway, and AMD's refusal made the
> whole gallery unlaunchable on that hardware. Guard fragment-only code inside the lib, with
> `#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)` and never `#ifdef CG_FRAGMENT_STAGE`
> (raw `.vert`/`.frag` get no stage define; a kernel is the third stage). `ShippedShaderStagePurityTest` enforces it GL-free; `--mode=shader-compile-audit` checks it
> against a real driver. See `CrystalGraphics/docs/SHADERS.md` § *Stage defines*.
