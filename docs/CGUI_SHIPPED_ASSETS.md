# Shipped assets — `assets/crystalgui/`

> Not loaded automatically: nothing may sit beside the assets, since everything under `resources/` ships. Read it before touching anything in `core/src/main/resources/assets/crystalgui/`. Moved verbatim from [`AGENTS.md`](../AGENTS.md).

## Shipped assets

> **CrystalGUI ships no shader.** Everything the UI draws is a CrystalGraphics canvas material (`CgCanvas`, `CgCanvasPrimitives`:
> element, curve, text, glass), precached by the engine. A surface the canvas cannot draw is a canvas
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
| (boxes, curves, gradients, grids, glass, coverage, the blur) | **Not shipped here: CrystalGraphics' canvas** (`CgCanvas`, `CgCanvasPrimitives`, `crystalgraphics:shaders/ui/`, `UI_CANVAS.md`) and its blur (`CgGpuOps.blurScaled`, compute). The paint context draws every box, image, gradient, grid and glass pane as a canvas element, every path and icon as exact coverage through the shared path atlas; a thumbnail is `CgGpuOps.shrink`, on compute. **Every element antialiases its own edges when rotated or sheared, with no MSAA**; the `edges` page of `cgui-gallery` shows every kind rotated and skewed. |

## Icons

**A UI names an icon; CrystalGraphics draws it.** An icon is `"namespace:name"`, a file at
`assets/{ns}/ui/icons/{name}.svg`; `CgUiImage.icon(name)` holds the name, and its file is chosen when it draws
(`Icon.path()`), so a light/dark theme swap reaches every icon with no restyle. Drawing is CrystalGraphics' `CgSvg`
(`CrystalGraphics/docs/UI_CANVAS.md` § *SVG*: snapping, tiers, the shared atlas, reload).

```css
.save { overlay: icon("crystalgui:save"); }                     /* follows `color` (currentColor) */
.warn { overlay: icon("crystalgui:warning", #E0A040); }          /* a fixed tint */
.flat { overlay: icon("crystalgui:filetypes/java", monochrome); } /* every fill forced to the tint */
```

```java
CgUiImage.Icon save = CgUiImage.findIcon("crystalgui:save");     // null when the file will not draw: say so
element.generalStyle(g -> g.overlay(save == null ? CgUiImage.NONE : save));
CgUiImage dark = save.variant(FileIconTheme.Variant.DARK);       // one element pinned to the dark drawing
CgUiImage.Icon fileIcon = FileIconTheme.getDefault().imageFor("Main.java", false, false);
```

- `FileIconTheme` ports VS Code's file-icon-theme JSON (`ui/icons/default.json`): extension and exact name to icon,
  longest extension first, the colour left to the `.filetype-*` class it hands back.
- `overlay-tint` multiplies an icon's own colours; the Start button's mark goes blue on hover through it.
- `CgSvg.usesCurrentColor()` tells a chrome mark (drawn in `color`) from artwork (its own palette):
  `WindowIcon` puts a tile under a mark and none under artwork.

### The file-type set: IntelliJ Platform

Fifty **IntelliJ Platform** icons (Apache 2.0, JetBrains) back the file tree — 47 from
`platform/icons/src/fileTypes/` plus `folder`, `package` and `moduleGroup` from `nodes/`. Verbatim; see
`ui/icons/ATTRIBUTION.md` and `THIRD-PARTY.md`. Chosen over Material Icon Theme because this workbench ports
IntelliJ's, because they are drawn for 16 px (a tree row) rather than 32 seen at 16, and for their muted palette.
VS Code's own default (Seti) is a font, not SVG.

- **Kotlin, Python, TypeScript, Rust, Go, C/C++, Ruby, PHP, shell, SQL, Markdown and GLSL have no icon in the
  Platform set**: they resolve to the plain text document, still listed in `default.json` so each keeps its own
  `.filetype-*` class.
- **`filetypes.css` does not tint the file icon**: the set carries its own fills, so those rules colour the row's
  label and any monochrome mark. A theme wanting one colour says `icon("...", monochrome)`.

### The brand mark: `crystalgui:logo`

`ui/icons/logo.svg` is the CrystalGraphics / CrystalGUI mark, original artwork. **A crystal cluster**: three
hexagonal prisms fanning from one base, each drawn as its three visible faces — a cyan→blue main shard, a
fuchsia→violet one leaning left, a small pink one at the right. Flat and hard-edged, no tile: the silhouette is the
icon. Authored under four rules:

1. **Filled polygons and gradients only**: every face a `<polygon>` with its own `linearGradient`, no strokes.
2. **No `currentColor` anywhere**: it is what tells `WindowIcon` this is artwork that *is* a tile.
3. **One shard placed three times** through `<g transform="translate() rotate() scale()">`, composed rightmost
   first as the spec says (`CgSvgTransformOrderTest`).
4. **Judged by the renderer's own tessellation**, never a browser's: `SvgTruthRasterTest` rasterises `CgSvg`'s ops
   at the tier drawn at each size.

### Application icons: `crystalgui:frame-profiler`, `crystalgui:vfx-lab`, `harness:cues`

Declared on each `ApplicationKind` with `.icon(...)`; a window with no icon of its own is drawn with its
application's (`WindowFrame.iconName`), so the taskbar, the previews, the switcher and the caption agree. One
recipe, so the three read as a set beside the logo: a 64-unit tile, `rx` 14, on a `#33373F` to `#0E1013` gradient
with one soft light at the top-left, and on it a picture of what the tool does in saturated colour.

| Icon | What it draws |
|---|---|
| `frame-profiler` | Frame times as eleven bars over faint vertical rules and a dashed budget line |
| `vfx-lab` | An energy orb: a white-cyan core in a blue halo, ringed by two tilted ellipses |
| `harness:cues` | A hit spark: a white-hot core, twenty tapered streaks and a faint shock ring. Ships with the harness |

**Dashes are drawn as separate lines**: no `stroke-dasharray` is read, so the profiler's budget line is twelve
short segments.
