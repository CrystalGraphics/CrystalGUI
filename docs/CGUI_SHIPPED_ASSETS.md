# Shipped assets — `assets/crystalgui/`

> Not loaded automatically: nothing may sit beside the assets, since everything under `resources/` ships. Read it before touching anything in `core/src/main/resources/assets/crystalgui/`. Moved verbatim from [`AGENTS.md`](../AGENTS.md).

## Shipped assets

> **A new shader or keyword set must be precached.** Every shader the UI draws is listed in `CgUiPaintContext`'s
> `QUAD_SHADERS` or `CURVE_SHADERS`, registered at client init and built behind the loading screen, and every keyword a
> style can switch is in its shader's `#pragma cg_permutation`. One missing shows late on first use and counts
> `pipeline.missed`: `CrystalGraphics/docs/PRECACHE.md`. Editing
> a shipped shader also stales CrystalGraphics' shipped pipeline list, refreshed before a release
> ([`CGUI_BUILD.md`](CGUI_BUILD.md) § *Releasing*).

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
| (boxes, curves, gradients, grids, glass, coverage, the blur) | **Not shipped here: CrystalGraphics' canvas** (`CgCanvasMaterials`, `crystalgraphics:shaders/canvas/`, `CANVAS.md` § 3) and its blur (`CgGpuOps.blurScaled`, compute). The paint context draws every box through `SCREEN.box()`, every stroke and cell through `SCREEN.curve()`, gradients, grids and glass through `CgCanvasGradient`, `CgCanvasGrid` and `CgCanvasGlass`, and an icon's coverage through `SCREEN.coverage()`; a thumbnail is `CgGpuOps.shrink`, on compute. A composite of a layer is a box quad stamped `CgShapeTable.PREMULTIPLIED`, its texture on the quad. **Every quad material here antialiases its own edges when rotated or sheared, with no MSAA** — the `CG_QUAD_EDGE_*` helpers in `env/buffer/quad.glsl`; the `edges` page of `cgui-gallery` shows every material rotated and skewed. |

> **Every quad shader here declares `#pragma cg_use quad`, and any new one must too.** Everything
> here draws through `CgQuadBatch`, whose per-instance buffer supplies `CG_QUAD_WORLD_POS` /
> `CG_QUAD_UV` / `CG_QUAD_COLOR` — the pragma is what wires it, during parsing, before anything can
> compile. Omitting it is a parse error naming the missing line (it used to be a GLSL error about an
> undefined `QUAD_DATA`, reported four layers up as an unrelated `#pragma cg_feature` complaint).
> Never attach the buffer from Java. See `CrystalGraphics/docs/SHADERS.md` § *Engine Buffers*.

> **A `#include` in a `.shader` is compiled into the vertex stage as well as the fragment stage.**
> The material compiler hoists every material-scope `#`-line into both. The canvas box is
> one of several shaders with an include, and its `sdf.glsl` needed a stage guard around
> `sdf_coverage` — `fwidth` is fragment-only, NVIDIA accepted it anyway, and AMD's refusal made the
> whole gallery unlaunchable on that hardware. Guard fragment-only code inside the lib, with
> `#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)` and never `#ifdef CG_FRAGMENT_STAGE`
> (raw `.vert`/`.frag` get no stage define; a kernel is the third stage). `ShippedShaderStagePurityTest` enforces it GL-free; `--mode=shader-compile-audit` checks it
> against a real driver. See `CrystalGraphics/docs/SHADERS.md` § *Stage defines*.
