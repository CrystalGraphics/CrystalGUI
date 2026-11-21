# Shipped assets — `assets/crystalgui/`

> Read before touching anything in `core/src/main/resources/assets/crystalgui/`: everything under `resources/` ships,
> so no guide can sit beside it.

## Shipped assets

> **CrystalGUI ships no shader.** Everything the UI draws is a CrystalGraphics canvas material (`CgCanvas`,
> `CgCanvasPrimitives`: element, curve, text, glass), precached by the engine. A surface the canvas cannot draw is a
> canvas feature added to CrystalGraphics, never a shader here (`CrystalGraphics/docs/PRECACHE.md`).

| Path | Notes |
|---|---|
| `ui/styles/ua/*.css` | **The user-agent sheet, in fifteen parts**, joined in `StyleSheetRegistry.DEFAULT_SHEET_PARTS` order into `StyleSheet.DEFAULT`: one sheet, one parse, one variable scope, so order across parts matters as much as within one. Geometry for every widget with no theme loaded; every colour is `var(--token, #fallback)`. |
| `ui/themes/base.css`, `crystal-dark.css`, `crystal-light.css` | The token tables: component→system derivations, and the two themes. See `CGUI_THEMING.md`. |
| `ui/schemes/*.css` | Editor colour schemes, the second, independent axis. `islands-dark` is the default. |
| `ui/styles/ore.css` | The Ore theme, ported from LDLib2's `ore.lss`; sprites in `ui/sprites/ore.json`, atlas `textures/gui/ore_styles.png`. |
| `ui/styles/graph.css` | The node graph's theme, including the per-type port palette every wire reads its colour from. |
| `ui/styles/filetypes.css`, `decorations.css` | Colour palettes keyed on the `.filetype-*` class `FileIconTheme.classFor` returns and the `decoration-*` class a `FileDecoration` names. Not in the user-agent sheet, which carries geometry only. |
| `ui/icons/*.svg` | Feather icons (MIT), stroked `currentColor` chrome marks: `icon("crystalgui:folder")` in CSS. |
| `ui/icons/filetypes/*.svg` | 50 IntelliJ Platform icons (Apache 2.0), filled, 16 px, with their own palette: what the file tree draws. |
| `ui/icons/default.json` | The default file-icon theme: extension or name → icon. Colour is not in it. |
| `ui/icons/ATTRIBUTION.md` | **An obligation**: MIT and Apache 2.0 require the notices to travel with the jar. Indexed from `THIRD-PARTY.md`. |
| `sources/**` | Injected into the jar by `tasks.jar`, not in `src/main/resources`: the API's `.java` files, so the documentation popup quotes real declarations (`SourceArchives.ResourceArchive`). `BundledSources` scans the classpath for `assets/<namespace>/sources/`, so any mod makes its API quotable by shipping its sources there. |
| `textures/gui/gdp_styles.png`, `Spritesheet_UI_Flat.png` | Referenced by nothing today. |
| `ui/fonts/Minecraft.otf`, `MinecraftRegular.otf` | Public-domain Minecraft fonts. |
| `ui/fonts/IBMPlexSans-Regular.ttf`, `JetBrainsMono-Regular.ttf` | The UI face and the code face, SIL OFL 1.1, each licence beside it. A glyph neither covers comes from the installed fonts (`CgSystemFonts`). |
| (boxes, curves, gradients, grids, glass, the blur) | **Not here**: CrystalGraphics' canvas (`CgCanvas`, `crystalgraphics:shaders/ui/`, `UI.md`) and its blur (`CgGpuOps.blurScaled`). Every element antialiases its own edges when rotated or sheared, with no MSAA; `cgui-gallery`'s `edges` page shows each kind. |

## Icons

**A UI names an icon; CrystalGraphics draws it.** An icon is `"namespace:name"`, a file at
`assets/{ns}/ui/icons/{name}.svg`. `CgUiImage.icon(name)` holds the name and picks the file when it draws, so a
light/dark swap reaches every icon with no restyle. Drawing is `CgSvg` (`CrystalGraphics/docs/UI.md` § 8).

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

- `FileIconTheme` ports VS Code's file-icon-theme JSON: extension and exact name to icon, longest extension first;
  the colour comes from the `.filetype-*` class it returns.
- `overlay-tint` multiplies an icon's own colours.
- `CgSvg.usesCurrentColor()` tells a chrome mark (drawn in `color`) from artwork (its own palette): `WindowIcon` puts a
  tile under a mark and none under artwork.

### The file-type set: IntelliJ Platform

Fifty IntelliJ Platform icons (Apache 2.0): 47 from `platform/icons/src/fileTypes/` plus `folder`, `package` and
`moduleGroup` from `nodes/`, verbatim (`ATTRIBUTION.md`, `THIRD-PARTY.md`).

- Kotlin, Python, TypeScript, Rust, Go, C/C++, Ruby, PHP, shell, SQL, Markdown and GLSL have no icon in the set: they
  show the plain document, and `default.json` still lists each so it keeps its `.filetype-*` class.
- `filetypes.css` colours the row's label, not the icon, which carries its own fills. For one colour, write
  `icon("...", monochrome)`.

### The brand mark: `crystalgui:logo`

`ui/icons/logo.svg`, original artwork: a crystal cluster of three hexagonal prisms fanning from one base. Its rules:

1. Filled polygons and gradients only, no strokes.
2. No `currentColor`: that is what tells `WindowIcon` it is artwork and needs no tile.
3. One shard placed three times through `<g transform="translate() rotate() scale()">`, composed rightmost first
   (`CgSvgTransformOrderTest`).
4. Judged by the renderer's own tessellation, never a browser's (`SvgTruthRasterTest`).

### Application icons: `crystalgui:frame-profiler`, `crystalgui:vfx-lab`, `harness:cues`

Declared on each `ApplicationKind` with `.icon(...)`. A window with no icon of its own takes its application's
(`WindowFrame.iconName`), so the taskbar, previews, switcher and caption agree. One recipe for all three: a 64-unit
tile, `rx` 14, a `#33373F` to `#0E1013` gradient with a soft light at the top-left, and a saturated picture of the
tool on it.

| Icon | What it draws |
|---|---|
| `frame-profiler` | Frame times as eleven bars over faint rules and a dashed budget line |
| `vfx-lab` | A white-cyan core in a blue halo, ringed by two tilted ellipses |
| `harness:cues` | A hit spark: a white-hot core, twenty tapered streaks, a faint shock ring. Ships with the harness |

**No `stroke-dasharray` is read**: a dashed line is drawn as separate segments.
