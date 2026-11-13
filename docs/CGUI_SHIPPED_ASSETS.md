# Shipped assets — `assets/crystalgui/`

> Not loaded automatically: nothing may sit beside the assets, since everything under `resources/` ships. Read it before touching anything in `core/src/main/resources/assets/crystalgui/`. Moved verbatim from [`AGENTS.md`](../AGENTS.md).

## Shipped assets

> **CrystalGUI ships no shader.** Everything the UI draws is a CrystalGraphics canvas material (`CgCanvasMaterials`:
> box, curve, text, image, gradient, grid, glass), precached by the engine. A surface the canvas cannot draw is a canvas
> feature added to CrystalGraphics, as the colour picker's conic ring and checkerboard were, never a shader here
> (`CrystalGraphics/docs/PRECACHE.md`).

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
| (boxes, curves, gradients, grids, glass, coverage, the blur) | **Not shipped here: CrystalGraphics' canvas** (`CgCanvasMaterials`, `crystalgraphics:shaders/canvas/`, `CANVAS.md` § 3) and its blur (`CgGpuOps.blurScaled`, compute). The paint context draws every box through `SCREEN.box()`, every stroke and cell through `SCREEN.curve()`, gradients, grids and glass through `CgCanvasGradient`, `CgCanvasGrid` and `CgCanvasGlass`, and an icon's coverage through `SCREEN.coverage()`; a thumbnail is `CgGpuOps.shrink`, on compute. A composite of a layer is a box quad stamped `CgShapeTable.PREMULTIPLIED`, its texture on the quad. **Every quad material here antialiases its own edges when rotated or sheared, with no MSAA** — the `CG_QUAD_EDGE_*` helpers in `canvas/primitives/quad.glsl`; the `edges` page of `cgui-gallery` shows every material rotated and skewed. |
