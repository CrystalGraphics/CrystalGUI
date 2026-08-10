# CGUI Theming — Themes, Schemes, and the Token Vocabulary

*The reference for CrystalGUI's appearance layer. Architecture and rationale live in
`plan_styling.md`; this is the working guide: how to author a theme, how to author an editor
colour scheme, what every token means, and the rules that keep the system from rotting. The token
table at the bottom is GENERATED and machine-checked — see §6.*

---

## 1. The model in one page

Two independently-selectable axes, IntelliJ's split exactly:

| Axis | File | Governs | Setting |
|---|---|---|---|
| **UI theme** | `assets/{ns}/ui/themes/*.css` | Chrome: panels, buttons, trees, tabs — every surface but the editor's text | `appearance.theme` |
| **Editor colour scheme** | `assets/{ns}/ui/schemes/*.css` | Inside the editor: syntax, caret, selection, gutter, guides | `appearance.editorScheme` |

Three tiers of tokens (Material 3's ref/sys/comp, by their names):

- **System tokens** (~29, pinned in `StyleGovernanceTest.SYSTEM_VOCABULARY`) — the theme author's
  entire surface: `--surface-panel`, `--fg-secondary`, `--accent`, `--hover-bg`, …
- **Component tokens** (~430) — what engine sheets actually reference: `--button-bg`,
  `--tab-active-fg`, `--editor-caret`. Defined once, in `themes/base.css` as derivations into the
  system vocabulary, or pinned in `crystal-dark.css` / `dark-plus.css` while the migration debt is
  paid down.
- **Reference tokens** (optional, per theme) — raw ramps a theme may define for its own
  consistency (`--ref-grey-20`) and point its system tokens at. No shipped sheet references them.

Every colour in an engine sheet is spelled `var(--token, #fallback)` — the fallback is the
themeless resting value, so `StyleSheet.DEFAULT` alone still renders a complete, functional UI.
A theme swap rebinds every sheet **in place** (`UiThemeManager` → `StyleSheetRegistry
.bindVariables`), so no engine's sheet list ever changes.

## 2. How a theme reaches a widget — three depths

1. **Tokens only (the 99% path).** The theme never names a widget: it redefines system tokens —
   or fine-tunes a component token — and `look` rules in the engine's own sheets follow.
2. **Override rules.** Real CSS at STYLESHEET origin (beats the user-agent sheet at any
   specificity) for what tokens cannot say. Targets are public tags and the documented
   `__part__` classes only.
3. **Full skin.** Rules-only, `asset()` sprites — ore.css is the shipped example.

The corollary rule: a theme may never depend on a hook the engine's sheets do not document. If
depth 2 needs a part class or token that does not exist, it is added to the engine first.

## 3. Authoring a theme

A theme is one CSS file:

```css
/* @theme  My Theme
 * @id     mymod:my-theme
 * @kind   dark                          (dark | light | high-contrast)
 * @extends crystalgui:crystal-dark      (optional -- inherit everything, override some)
 * @editor-scheme crystalgui:dark-plus   (optional -- the scheme you suggest, never force)
 * @author me */

theme {
    --accent: #E05070;
    --surface-panel: #26262B;
    /* ...any system or component token... */
}

/* optional depth-2 override rules follow */
```

- The first header tag (`@theme` vs `@scheme`) declares which artifact the file is. `@id` and
  `@kind` are required; malformed files are **refused at registration** with a log, never
  half-applied.
- `@extends` gives single-parent inheritance: parent tables merge first, parent override rules
  parse first (so yours win ties by source order). A sparse theme over `crystal-dark` is the
  intended shape.
- Register with `ThemeRegistry.registerTheme("mymod:my-theme")` (reads
  `assets/mymod/ui/themes/my-theme.css`), or ship it in a resource pack and register from your
  entry point. Activate via the Preferences dropdown or `UiThemeManager.setTheme(id)`.

## 4. Authoring an editor colour scheme

Same file shape, opening with `@scheme <name>`, living in `ui/schemes/`. A scheme may define
**only** `--editor-*`, `--syntax-*`, `--find-match-*` and `--search-excluded-*` tokens — and a
theme may define none of them. Both directions are build-enforced; the split is what makes
"Crystal Dark + any scheme" a legal pair. The six general syntax names (`keyword`, `type`,
`function`, `string`, `number`, `comment`) cover every tokenizer — specialised names like
`function.builtin` fall back to their general form.

## 5. Process rules (the anti-rot half)

- **A new colour is an existing token until proven otherwise.** The review question for a new
  system token: "which existing surface/fg/accent is this, really?" 161 unrelated greys is how
  the old default.css died.
- **Adding a widget** = its component tokens in base.css + function rules + look rules + this
  doc regenerated, in the same commit. The governance tests fail on a missed step.
- **The vocabulary is a public API.** Append-mostly; renames ship as manager-resolved aliases
  for a full release cycle before the old name dies.
- **No `!important`, no `asset()`, no bare hex** in the engine's `ua/` parts — a hex may appear
  only as a `var()` fallback. `StyleGovernanceTest` enforces all of it.
- **The user-agent sheet is nine files, one sheet** (`StyleSheetRegistry.DEFAULT_SHEET_PARTS`).
  Order across the parts is as load-bearing as order within one — files split at section
  boundaries and never reorder.

## 6. Token reference — GENERATED

*Between the markers below the table is generated from `themes/base.css`,
`themes/crystal-dark.css` and `schemes/dark-plus.css` (first definition wins, sorted by name) and
checked by `StyleGovernanceTest.theDocumentedTokenTableIsCurrent`. Regenerate it with the failing
test's own output rather than editing by hand.*

A value of `var(--sys)` means the token derives from the system vocabulary in base.css; a hex in
crystal-dark.css is a migration-era pin awaiting the Islands pass; dark-plus.css rows are the
editor scheme's.

<!-- TOKENS:BEGIN -->
| Token | Value | Defined in |
|---|---|---|
| `--accent` | `#3574F0` | crystal-dark.css |
| `--accent-hover` | `#4A82F2` | crystal-dark.css |
| `--accent-soft` | `#2F5C8F` | crystal-dark.css |
| `--activitybar-badge-fg` | `var(--fg-on-accent)` | base.css |
| `--activitybar-bg` | `var(--surface-panel)` | base.css |
| `--activitybar-item-checked-bg` | `#4E5157` | crystal-dark.css |
| `--activitybar-item-hover-bg` | `var(--accent)` | base.css |
| `--activitybar-separator` | `#4E5157` | crystal-dark.css |
| `--balloon-bg` | `var(--surface-raised)` | base.css |
| `--balloon-border` | `#4E5254` | crystal-dark.css |
| `--balloon-hover-bg` | `#4A4E50` | crystal-dark.css |
| `--banner-border` | `#00000040` | crystal-dark.css |
| `--banner-error-bg` | `#4A2D2D` | crystal-dark.css |
| `--banner-error-fg` | `#E5B4B4` | crystal-dark.css |
| `--banner-info-bg` | `#2D3A4A` | crystal-dark.css |
| `--banner-info-fg` | `#C8D6E5` | crystal-dark.css |
| `--banner-warning-bg` | `#4A3F2D` | crystal-dark.css |
| `--banner-warning-fg` | `#E5D6A8` | crystal-dark.css |
| `--blackboard-arrow-fg` | `#676767` | crystal-dark.css |
| `--blackboard-arrow-hover-fg` | `var(--fg-on-accent)` | base.css |
| `--blackboard-drop-line` | `#44C0FF` | crystal-dark.css |
| `--blackboard-empty-fg` | `#7A7A7A` | crystal-dark.css |
| `--blackboard-subtitle-fg` | `#676767` | crystal-dark.css |
| `--blackboard-type-fg` | `#676767` | crystal-dark.css |
| `--border-base` | `#4A4A4A` | crystal-dark.css |
| `--border-field` | `#3C3C3C` | crystal-dark.css |
| `--border-strong` | `#8C8C8C` | crystal-dark.css |
| `--breadcrumb-current-fg` | `#DDDDDD` | crystal-dark.css |
| `--breadcrumb-link-fg` | `#7CB7E8` | crystal-dark.css |
| `--breadcrumb-sep-fg` | `#7A7A7A` | crystal-dark.css |
| `--button-bg` | `#9A9A9A` | crystal-dark.css |
| `--button-disabled-bg` | `#6E6E6E` | crystal-dark.css |
| `--button-disabled-fg` | `#4A4A4A` | crystal-dark.css |
| `--button-fg` | `#222222` | crystal-dark.css |
| `--button-hover-bg` | `#B4B4B4` | crystal-dark.css |
| `--button-pressed-bg` | `#7A7A7A` | crystal-dark.css |
| `--checkbox-checked-bg` | `var(--success)` | base.css |
| `--checkbox-disabled-bg` | `#6E6E6E` | crystal-dark.css |
| `--checkbox-mark-bg` | `#9A9A9A` | crystal-dark.css |
| `--checkbox-mark-hover-bg` | `#B4B4B4` | crystal-dark.css |
| `--colorpicker-bg` | `#1E1E1EDD` | crystal-dark.css |
| `--colorpicker-channel-fg` | `#9A9A9A` | crystal-dark.css |
| `--colorpicker-handle-ring` | `#FFFFFF` | crystal-dark.css |
| `--colorpicker-handle-shadow` | `#00000080` | crystal-dark.css |
| `--colorpicker-thumb` | `#FFFFFF` | crystal-dark.css |
| `--colorpicker-thumb-ring` | `#C1C2C2` | crystal-dark.css |
| `--configkit-alpha-bg` | `#14140F` | crystal-dark.css |
| `--configkit-alpha-fill` | `#FFFFFF` | crystal-dark.css |
| `--configkit-band-bg` | `#383838` | crystal-dark.css |
| `--configkit-check-hover-bg` | `#3A3A3A` | crystal-dark.css |
| `--configkit-dropdown-hover-bg` | `#5A5A5A` | crystal-dark.css |
| `--configkit-dropdown-pressed-bg` | `#3D3D3D` | crystal-dark.css |
| `--configkit-field-bg` | `var(--surface-base)` | base.css |
| `--configkit-field-border` | `#343434` | crystal-dark.css |
| `--configkit-field-border-bottom` | `#545454` | crystal-dark.css |
| `--configkit-field-border-top` | `#121212` | crystal-dark.css |
| `--configkit-label-fg` | `#D2D2D2` | crystal-dark.css |
| `--configkit-list-body-bg` | `#414141` | crystal-dark.css |
| `--configkit-list-head-bg` | `#353535` | crystal-dark.css |
| `--configkit-panel-bg` | `#2E2E2E` | crystal-dark.css |
| `--configkit-popup-bg` | `var(--surface-overlay)` | base.css |
| `--configkit-slider-fill` | `#6A6A6A` | crystal-dark.css |
| `--configkit-swatch-border` | `#1A1A1A` | crystal-dark.css |
| `--configkit-value-fg` | `#E4E4E4` | crystal-dark.css |
| `--decoration-added` | `#81B88B` | crystal-dark.css |
| `--decoration-conflict` | `#E4676B` | crystal-dark.css |
| `--decoration-deleted` | `#C74E39` | crystal-dark.css |
| `--decoration-dirty` | `#E2C08D` | crystal-dark.css |
| `--decoration-error` | `var(--error)` | base.css |
| `--decoration-ignored` | `#6B6B6B` | crystal-dark.css |
| `--decoration-info` | `var(--info)` | base.css |
| `--decoration-modified` | `var(--modified)` | base.css |
| `--decoration-readonly` | `#8A9199` | crystal-dark.css |
| `--decoration-renamed` | `#73C991` | crystal-dark.css |
| `--decoration-untracked` | `#73C991` | crystal-dark.css |
| `--decoration-warning` | `var(--warning)` | base.css |
| `--dialog-backdrop` | `#00000080` | crystal-dark.css |
| `--dialog-bg` | `#2E2E2E` | crystal-dark.css |
| `--dialog-border` | `#1A1A1A` | crystal-dark.css |
| `--dialog-close-bg` | `var(--surface-overlay)` | base.css |
| `--dialog-close-fg` | `#DDDDDD` | crystal-dark.css |
| `--dialog-close-hover-bg` | `#C05050` | crystal-dark.css |
| `--dialog-fg` | `#DDDDDD` | crystal-dark.css |
| `--dialog-picker-bg` | `#2E2E2E76` | crystal-dark.css |
| `--dialog-title-bg` | `#393939` | crystal-dark.css |
| `--divider` | `#3C3F41` | crystal-dark.css |
| `--dock-active-border` | `var(--link)` | base.css |
| `--dock-bg` | `var(--surface-panel)` | base.css |
| `--dock-drop-overlay` | `#4A88C766` | crystal-dark.css |
| `--dock-empty-bg` | `#232323` | crystal-dark.css |
| `--dock-floating-bg` | `#1E1E1EF0` | crystal-dark.css |
| `--dock-insertion-bg` | `#7EB6FF30` | crystal-dark.css |
| `--dock-insertion-border` | `#7EB6FF` | crystal-dark.css |
| `--dock-missing-bg` | `#3A2B2B` | crystal-dark.css |
| `--editor-caret` | `#FF0000` | dark-plus.css |
| `--editor-current-line` | `#232830` | dark-plus.css |
| `--editor-fold` | `#4A515C` | dark-plus.css |
| `--editor-fold-active` | `#A9B2BF` | dark-plus.css |
| `--editor-fold-placeholder-bg` | `#2A303A` | dark-plus.css |
| `--editor-fold-placeholder-fg` | `#8A94A3` | dark-plus.css |
| `--editor-fold-placeholder-hover-bg` | `#3C4553` | dark-plus.css |
| `--editor-fold-placeholder-hover-fg` | `#DCE1E8` | dark-plus.css |
| `--editor-gutter-bg` | `#1A1D23` | dark-plus.css |
| `--editor-gutter-edge` | `#2F3540` | dark-plus.css |
| `--editor-indent-guide` | `#2F3540` | dark-plus.css |
| `--editor-indent-guide-active` | `#6E7A8A` | dark-plus.css |
| `--editor-line-number` | `#5C6570` | dark-plus.css |
| `--editor-ruler` | `#2F3540` | dark-plus.css |
| `--editor-selection-bg` | `#2C5A8C` | dark-plus.css |
| `--editor-whitespace` | `#454C57` | dark-plus.css |
| `--editor-zoom-bg` | `#2B303B` | dark-plus.css |
| `--editor-zoom-fg` | `#C8CDD4` | dark-plus.css |
| `--editor-zoom-link` | `#4A9EFF` | dark-plus.css |
| `--editor-zoom-link-pressed` | `#2F7FD8` | dark-plus.css |
| `--editorfind-bg` | `#303235` | crystal-dark.css |
| `--editorfind-border` | `#4A4D50` | crystal-dark.css |
| `--error` | `#F14C4C` | crystal-dark.css |
| `--fg` | `#DFE1E5` | crystal-dark.css |
| `--fg-disabled` | `#6E6E6E` | crystal-dark.css |
| `--fg-hint` | `#8A8A8A` | crystal-dark.css |
| `--fg-on-accent` | `#FFFFFF` | crystal-dark.css |
| `--fg-secondary` | `#9DA0A8` | crystal-dark.css |
| `--field-bg` | `#2A2A2A` | crystal-dark.css |
| `--field-disabled-bg` | `#232323` | crystal-dark.css |
| `--field-disabled-fg` | `var(--fg-disabled)` | base.css |
| `--field-fg` | `#EEEEEE` | crystal-dark.css |
| `--field-invalid-bg` | `#4A2A2A` | crystal-dark.css |
| `--field-invalid-fg` | `#FF9A9A` | crystal-dark.css |
| `--field-placeholder` | `#7A7A7A` | crystal-dark.css |
| `--filetype-7z` | `#ECC48D` | crystal-dark.css |
| `--filetype-bat` | `#89E051` | crystal-dark.css |
| `--filetype-bmp` | `#A074C4` | crystal-dark.css |
| `--filetype-build-gradle` | `#02303A` | crystal-dark.css |
| `--filetype-build-gradle-kts` | `#A97BFF` | crystal-dark.css |
| `--filetype-c` | `#A8B9CC` | crystal-dark.css |
| `--filetype-cargo-toml` | `#DEA584` | crystal-dark.css |
| `--filetype-cfg` | `#9DA5B4` | crystal-dark.css |
| `--filetype-cpp` | `#6098CC` | crystal-dark.css |
| `--filetype-cs` | `#A45FBE` | crystal-dark.css |
| `--filetype-css` | `#563D7C` | crystal-dark.css |
| `--filetype-csv` | `#9DA5B4` | crystal-dark.css |
| `--filetype-d-ts` | `#3178C6` | crystal-dark.css |
| `--filetype-dockerfile` | `#2496ED` | crystal-dark.css |
| `--filetype-file` | `#9DA5B4` | crystal-dark.css |
| `--filetype-folder` | `#8A9199` | crystal-dark.css |
| `--filetype-frag` | `#5686A5` | crystal-dark.css |
| `--filetype-gif` | `#A074C4` | crystal-dark.css |
| `--filetype-glsl` | `#5686A5` | crystal-dark.css |
| `--filetype-go` | `#00ADD8` | crystal-dark.css |
| `--filetype-gz` | `#ECC48D` | crystal-dark.css |
| `--filetype-h` | `#A8B9CC` | crystal-dark.css |
| `--filetype-hpp` | `#6098CC` | crystal-dark.css |
| `--filetype-html` | `#E34C26` | crystal-dark.css |
| `--filetype-ico` | `#A074C4` | crystal-dark.css |
| `--filetype-ini` | `#9DA5B4` | crystal-dark.css |
| `--filetype-jar` | `#ECC48D` | crystal-dark.css |
| `--filetype-java` | `#E76F00` | crystal-dark.css |
| `--filetype-jpeg` | `#A074C4` | crystal-dark.css |
| `--filetype-jpg` | `#A074C4` | crystal-dark.css |
| `--filetype-js` | `#F0DB4F` | crystal-dark.css |
| `--filetype-json` | `#CBCB41` | crystal-dark.css |
| `--filetype-jsx` | `#F0DB4F` | crystal-dark.css |
| `--filetype-kt` | `#A97BFF` | crystal-dark.css |
| `--filetype-kts` | `#A97BFF` | crystal-dark.css |
| `--filetype-license` | `#D9D9D9` | crystal-dark.css |
| `--filetype-log` | `#9DA5B4` | crystal-dark.css |
| `--filetype-lua` | `#51A0CF` | crystal-dark.css |
| `--filetype-md` | `#9DA5B4` | crystal-dark.css |
| `--filetype-mjs` | `#F0DB4F` | crystal-dark.css |
| `--filetype-package-json` | `#CBCB41` | crystal-dark.css |
| `--filetype-php` | `#777BB4` | crystal-dark.css |
| `--filetype-png` | `#A074C4` | crystal-dark.css |
| `--filetype-pom-xml` | `#E37933` | crystal-dark.css |
| `--filetype-properties` | `#9DA5B4` | crystal-dark.css |
| `--filetype-ps1` | `#89E051` | crystal-dark.css |
| `--filetype-py` | `#4B8BBE` | crystal-dark.css |
| `--filetype-rar` | `#ECC48D` | crystal-dark.css |
| `--filetype-rb` | `#CC342D` | crystal-dark.css |
| `--filetype-readme-md` | `#519ABA` | crystal-dark.css |
| `--filetype-rs` | `#DEA584` | crystal-dark.css |
| `--filetype-settings-gradle-kts` | `#A97BFF` | crystal-dark.css |
| `--filetype-sh` | `#89E051` | crystal-dark.css |
| `--filetype-shader` | `#5686A5` | crystal-dark.css |
| `--filetype-sql` | `#E38C00` | crystal-dark.css |
| `--filetype-svg` | `#FFB13B` | crystal-dark.css |
| `--filetype-tar` | `#ECC48D` | crystal-dark.css |
| `--filetype-tar-gz` | `#ECC48D` | crystal-dark.css |
| `--filetype-tga` | `#A074C4` | crystal-dark.css |
| `--filetype-toml` | `#9C6644` | crystal-dark.css |
| `--filetype-ts` | `#3178C6` | crystal-dark.css |
| `--filetype-tsx` | `#3178C6` | crystal-dark.css |
| `--filetype-txt` | `#9DA5B4` | crystal-dark.css |
| `--filetype-vert` | `#5686A5` | crystal-dark.css |
| `--filetype-webp` | `#A074C4` | crystal-dark.css |
| `--filetype-xml` | `#E37933` | crystal-dark.css |
| `--filetype-yaml` | `#CB171E` | crystal-dark.css |
| `--filetype-yml` | `#CB171E` | crystal-dark.css |
| `--filetype-zip` | `#ECC48D` | crystal-dark.css |
| `--find-match-bg` | `#C8873C` | dark-plus.css |
| `--find-match-fg` | `#1B1B1B` | dark-plus.css |
| `--findbar-action-bg` | `var(--surface-raised)` | base.css |
| `--findbar-action-disabled-bg` | `#2E3033` | crystal-dark.css |
| `--findbar-action-disabled-fg` | `#5A5D63` | crystal-dark.css |
| `--findbar-action-fg` | `#C8C8C8` | crystal-dark.css |
| `--findbar-arrow-fg` | `#9A9A9A` | crystal-dark.css |
| `--findbar-bg` | `#2F3033` | crystal-dark.css |
| `--findbar-border` | `var(--divider)` | base.css |
| `--findbar-button-fg` | `var(--fg-hint)` | base.css |
| `--findbar-button-hover-bg` | `#4E5157` | crystal-dark.css |
| `--findbar-button-hover-fg` | `#E0E0E0` | crystal-dark.css |
| `--findbar-close-hover-bg` | `#4B4D50` | crystal-dark.css |
| `--findbar-count-fg` | `var(--fg-hint)` | base.css |
| `--findbar-error-fg` | `#E06C6C` | crystal-dark.css |
| `--findbar-fg` | `#D0D2D6` | crystal-dark.css |
| `--findbar-icon` | `#9A9A9A` | crystal-dark.css |
| `--findbar-icon-hover` | `#E0E0E0` | crystal-dark.css |
| `--findbar-icon-off` | `#5A5D63` | crystal-dark.css |
| `--findbar-toggle-fg` | `#9A9A9A` | crystal-dark.css |
| `--findbar-toggle-hover-fg` | `#E0E0E0` | crystal-dark.css |
| `--findbar-toggle-on-bg` | `var(--accent)` | base.css |
| `--findbar-toggle-on-fg` | `var(--fg-on-accent)` | base.css |
| `--focus-ring` | `#0060DF` | crystal-dark.css |
| `--ghost-bg` | `#2F5F9EC0` | crystal-dark.css |
| `--ghost-label-bg` | `#3C3F41F0` | crystal-dark.css |
| `--ghost-label-fg` | `var(--fg-on-accent)` | base.css |
| `--graph-canvas` | `#202020` | crystal-dark.css |
| `--graph-collapse-fg` | `#9A9A9A` | crystal-dark.css |
| `--graph-collapse-hover-fg` | `#E0E0E0` | crystal-dark.css |
| `--graph-control-fg` | `#B4B4B4` | crystal-dark.css |
| `--graph-control-row-bg` | `#56565676` | crystal-dark.css |
| `--graph-dot-bg` | `#212121` | crystal-dark.css |
| `--graph-edge` | `#1A1A1A` | crystal-dark.css |
| `--graph-editor-dot-ring-bg` | `var(--surface-panel)` | base.css |
| `--graph-exposed-dot` | `#7BD64B` | crystal-dark.css |
| `--graph-exposed-ring` | `#7BD64B` | crystal-dark.css |
| `--graph-field` | `#1E1E1E` | crystal-dark.css |
| `--graph-hairline` | `#282828` | crystal-dark.css |
| `--graph-header` | `#393939` | crystal-dark.css |
| `--graph-inline-editor-bg` | `#56565676` | crystal-dark.css |
| `--graph-inputs-bg` | `#53535376` | crystal-dark.css |
| `--graph-marquee-bg` | `#2C79C433` | crystal-dark.css |
| `--graph-marquee-border` | `#44C0FF` | crystal-dark.css |
| `--graph-node-hover-ring` | `#327090` | crystal-dark.css |
| `--graph-outputs-bg` | `#35353576` | crystal-dark.css |
| `--graph-panel` | `#2B2B2B` | crystal-dark.css |
| `--graph-port-bool` | `#9C4FFF` | crystal-dark.css |
| `--graph-port-bool-hover` | `#CEA7FF` | crystal-dark.css |
| `--graph-port-cubemap` | `#FF8B8B` | crystal-dark.css |
| `--graph-port-cubemap-hover` | `#FFC5C5` | crystal-dark.css |
| `--graph-port-dynamic` | `#B4B4B4` | crystal-dark.css |
| `--graph-port-dynamic-hover` | `#DADADA` | crystal-dark.css |
| `--graph-port-label-hover-fg` | `var(--fg-on-accent)` | base.css |
| `--graph-port-mat4` | `#8FC1DF` | crystal-dark.css |
| `--graph-port-mat4-hover` | `#C7E0EF` | crystal-dark.css |
| `--graph-port-sampler` | `#C1C1C1` | crystal-dark.css |
| `--graph-port-sampler-hover` | `#E0E0E0` | crystal-dark.css |
| `--graph-port-vec1` | `#84E4E7` | crystal-dark.css |
| `--graph-port-vec1-hover` | `#C2F2F3` | crystal-dark.css |
| `--graph-port-vec2` | `#9AEF92` | crystal-dark.css |
| `--graph-port-vec2-hover` | `#CDF7C9` | crystal-dark.css |
| `--graph-port-vec3` | `#F6FF9A` | crystal-dark.css |
| `--graph-port-vec3-hover` | `#FBFFCD` | crystal-dark.css |
| `--graph-port-vec4` | `#FBCBF4` | crystal-dark.css |
| `--graph-port-vec4-hover` | `#FDE5FA` | crystal-dark.css |
| `--graph-ports-bg` | `#26262676` | crystal-dark.css |
| `--graph-preview-bg` | `#313131` | crystal-dark.css |
| `--graph-property-node-bg` | `#565656` | crystal-dark.css |
| `--graph-seam` | `#232323` | crystal-dark.css |
| `--graph-selection-ring` | `#44C0FF` | crystal-dark.css |
| `--graph-text` | `#C4C4C4` | crystal-dark.css |
| `--graph-text-bright` | `#D4D4D4` | crystal-dark.css |
| `--graph-title-bg` | `#56565676` | crystal-dark.css |
| `--hover-bg` | `#2A2D2E` | crystal-dark.css |
| `--info` | `#3794FF` | crystal-dark.css |
| `--inspection-bg` | `#2D2D2DE0` | crystal-dark.css |
| `--inspection-clean-fg` | `#6A9955` | crystal-dark.css |
| `--inspection-error-fg` | `var(--error)` | base.css |
| `--inspection-warning-fg` | `var(--warning)` | base.css |
| `--label-fg` | `var(--fg-on-accent)` | base.css |
| `--link` | `#4A88C7` | crystal-dark.css |
| `--list-hover-bg` | `var(--hover-bg)` | base.css |
| `--list-selected-bg` | `var(--selection-bg)` | base.css |
| `--menu-accelerator` | `#8A8A8A` | crystal-dark.css |
| `--menu-arrow` | `#A8B0B8` | crystal-dark.css |
| `--menu-bg` | `var(--surface-overlay)` | base.css |
| `--menu-border` | `var(--border-strong)` | base.css |
| `--menu-fg` | `#DDDDDD` | crystal-dark.css |
| `--menu-item-disabled-fg` | `var(--fg-disabled)` | base.css |
| `--menu-item-focus-bg` | `#606060` | crystal-dark.css |
| `--menu-mark` | `#DDDDDD` | crystal-dark.css |
| `--menu-separator` | `#6E6E6E` | crystal-dark.css |
| `--menubar-bg` | `var(--surface-raised)` | base.css |
| `--menubar-fg` | `#BBBBBB` | crystal-dark.css |
| `--menubar-hover-bg` | `#4C5052` | crystal-dark.css |
| `--menubar-open-fg` | `var(--fg-on-accent)` | base.css |
| `--modified` | `#E2C08D` | crystal-dark.css |
| `--nav-arrow-fg` | `#B0B0B0` | crystal-dark.css |
| `--nav-label-fg` | `#CCCCCC` | crystal-dark.css |
| `--nav-sidebar-bg` | `#252526` | crystal-dark.css |
| `--nodemenu-bg` | `var(--surface-panel)` | base.css |
| `--nodemenu-border` | `var(--border-base)` | base.css |
| `--nodemenu-category-fg` | `#7A7A7A` | crystal-dark.css |
| `--nodemenu-category-label-fg` | `#9D9D9D` | crystal-dark.css |
| `--nodemenu-empty-fg` | `#7A7A7A` | crystal-dark.css |
| `--nodemenu-hover-bg` | `#3C4A55` | crystal-dark.css |
| `--nodemenu-selected-bg` | `#2C5D87` | crystal-dark.css |
| `--nodemenu-selected-fg` | `var(--fg-on-accent)` | base.css |
| `--nodemenu-separator-fg` | `var(--fg-disabled)` | base.css |
| `--nodemenu-title-bg` | `#383838` | crystal-dark.css |
| `--nodemenu-twisty-fg` | `var(--fg-hint)` | base.css |
| `--notification-bg` | `var(--surface-panel)` | base.css |
| `--notification-close-fg` | `#787A80` | crystal-dark.css |
| `--notification-close-hover-bg` | `#5A5F62` | crystal-dark.css |
| `--notification-close-hover-fg` | `var(--fg-on-accent)` | base.css |
| `--notification-detail-fg` | `var(--fg-secondary)` | base.css |
| `--notification-error-fg` | `#C75450` | crystal-dark.css |
| `--notification-hover-bg` | `#323232` | crystal-dark.css |
| `--notification-message-fg` | `var(--fg)` | base.css |
| `--notification-secondary-fg` | `#8A8D94` | crystal-dark.css |
| `--notification-secondary-hover-fg` | `#B4B7BE` | crystal-dark.css |
| `--notification-time-fg` | `#787A80` | crystal-dark.css |
| `--notifications-bg` | `var(--surface-base)` | base.css |
| `--notifications-empty-fg` | `#6E7076` | crystal-dark.css |
| `--notifications-link-fg` | `var(--link)` | base.css |
| `--notifications-link-hover-fg` | `#6BA5DE` | crystal-dark.css |
| `--notifications-title-fg` | `var(--fg-secondary)` | base.css |
| `--pagestack-empty-fg` | `var(--fg-hint)` | base.css |
| `--palette-bg` | `#252526` | crystal-dark.css |
| `--palette-border` | `#454545` | crystal-dark.css |
| `--palette-category-fg` | `#9D9D9D` | crystal-dark.css |
| `--palette-field-bg` | `var(--surface-base)` | base.css |
| `--palette-field-border` | `var(--border-field)` | base.css |
| `--palette-field-focus-border` | `#0078D4` | crystal-dark.css |
| `--palette-hover-bg` | `#2A3B2A` | crystal-dark.css |
| `--palette-key-bg` | `#3C3C3C` | crystal-dark.css |
| `--palette-key-border` | `var(--border-base)` | base.css |
| `--palette-key-fg` | `#CCCCCC` | crystal-dark.css |
| `--palette-key-hover-border` | `#9BB8CC` | crystal-dark.css |
| `--palette-key-hover-fg` | `var(--fg-on-accent)` | base.css |
| `--palette-key-sep-fg` | `var(--fg-hint)` | base.css |
| `--palette-label-fg` | `#CCCCCC` | crystal-dark.css |
| `--palette-match-fg` | `#2AAAFF` | crystal-dark.css |
| `--palette-selected-bg` | `var(--selection-bg)` | base.css |
| `--panel-header-fg` | `#A0A0A0` | crystal-dark.css |
| `--panel-header-icon` | `#A0A0A0` | crystal-dark.css |
| `--panel-header-icon-hover` | `#E0E0E0` | crystal-dark.css |
| `--pill-name-fg` | `#E4E4E4` | crystal-dark.css |
| `--pill-type-fg` | `#8C8C8C` | crystal-dark.css |
| `--pressed-bg` | `#26282A` | crystal-dark.css |
| `--preview-surface-bg` | `#1A1A1A` | crystal-dark.css |
| `--problems-bg` | `var(--surface-base)` | base.css |
| `--problems-count-fg` | `#6B6E76` | crystal-dark.css |
| `--problems-empty-fg` | `var(--fg-hint)` | base.css |
| `--problems-line-fg` | `#6E7076` | crystal-dark.css |
| `--problems-message-fg` | `var(--fg)` | base.css |
| `--problems-options-fg` | `#8A8D94` | crystal-dark.css |
| `--problems-options-hover-fg` | `#D5D7DB` | crystal-dark.css |
| `--problems-tab-active-bg` | `var(--accent-soft)` | base.css |
| `--problems-tab-active-count-fg` | `#B6C6DA` | crystal-dark.css |
| `--problems-tab-active-fg` | `var(--fg)` | base.css |
| `--problems-tab-count-fg` | `#7A7D85` | crystal-dark.css |
| `--problems-tab-fg` | `var(--fg-secondary)` | base.css |
| `--problems-tab-hover-bg` | `#2F3133` | crystal-dark.css |
| `--problems-tree-line-fg` | `#6B6E76` | crystal-dark.css |
| `--problems-unnecessary-fg` | `#6B6E76` | crystal-dark.css |
| `--prompt-bg` | `#2E2E2E` | crystal-dark.css |
| `--prompt-border` | `var(--border-base)` | base.css |
| `--prompt-caption-fg` | `#C8C8C8` | crystal-dark.css |
| `--prompt-field-bg` | `#2E2E2E` | crystal-dark.css |
| `--prompt-field-fg` | `#DDDDDD` | crystal-dark.css |
| `--region-drop-bg` | `#3574F055` | crystal-dark.css |
| `--region-drop-border` | `var(--accent)` | base.css |
| `--resizer-grip` | `#FFFFFF40` | crystal-dark.css |
| `--scrollbar-corner` | `#2A2A2A` | crystal-dark.css |
| `--scrollbar-tail` | `#4A4A4A` | crystal-dark.css |
| `--scrollbar-tail-hover` | `#6E6E6E` | crystal-dark.css |
| `--scrollbar-thumb` | `#6E6E6E` | crystal-dark.css |
| `--scrollbar-thumb-hover` | `#8C8C8C` | crystal-dark.css |
| `--scrollbar-thumb-pressed` | `#B4B4B4` | crystal-dark.css |
| `--scrollbar-track` | `#2A2A2A` | crystal-dark.css |
| `--search-excluded-fg` | `#7A7A7A` | dark-plus.css |
| `--search-match-fg` | `#E0A040` | crystal-dark.css |
| `--searchfield-bg` | `var(--surface-recessed)` | base.css |
| `--searchfield-border` | `var(--border-field)` | base.css |
| `--searchfield-caret` | `#D0D2D6` | crystal-dark.css |
| `--searchfield-clear-fg` | `#5F6265` | crystal-dark.css |
| `--searchfield-clear-hover-bg` | `#393B40` | crystal-dark.css |
| `--searchfield-clear-hover-fg` | `#E0E0E0` | crystal-dark.css |
| `--searchfield-error-fg` | `#E06C6C` | crystal-dark.css |
| `--searchfield-fg` | `#D0D2D6` | crystal-dark.css |
| `--searchfield-focus-border` | `var(--link)` | base.css |
| `--searchfield-icon-fg` | `var(--fg-hint)` | base.css |
| `--searchfield-option-fg` | `#9A9A9A` | crystal-dark.css |
| `--searchfield-option-hover-bg` | `#393B40` | crystal-dark.css |
| `--searchfield-option-hover-fg` | `#393B40` | crystal-dark.css |
| `--searchfield-option-on-bg` | `#375FAD` | crystal-dark.css |
| `--searchfield-option-on-fg` | `var(--fg-on-accent)` | base.css |
| `--searchfield-option-on-hover-bg` | `#4A82F2` | crystal-dark.css |
| `--selection-bg` | `#04395E` | crystal-dark.css |
| `--selection-inactive-bg` | `#37373D` | crystal-dark.css |
| `--slider-disabled-fill` | `#5A6E5A` | crystal-dark.css |
| `--slider-disabled-thumb` | `#8C8C8C` | crystal-dark.css |
| `--slider-fill` | `#3C8527` | crystal-dark.css |
| `--slider-thumb` | `#D0D1D4` | crystal-dark.css |
| `--slider-thumb-hover` | `#E4E5E8` | crystal-dark.css |
| `--slider-thumb-ring` | `#FFFFFF` | crystal-dark.css |
| `--slider-track` | `#6E6E6E` | crystal-dark.css |
| `--splitter-bg` | `#777777` | crystal-dark.css |
| `--splitter-hover-bg` | `#AAAAAA` | crystal-dark.css |
| `--splitter-pressed-bg` | `#DDDDDD` | crystal-dark.css |
| `--squiggle-error` | `var(--error)` | base.css |
| `--squiggle-info` | `var(--info)` | base.css |
| `--squiggle-warning` | `var(--warning)` | base.css |
| `--statusbar-bg` | `var(--surface-panel)` | base.css |
| `--statusbar-border` | `#1E1E1E` | crystal-dark.css |
| `--statusbar-crumb-fg` | `#8A8D94` | crystal-dark.css |
| `--statusbar-error-fg` | `#C75450` | crystal-dark.css |
| `--statusbar-fg` | `var(--fg-secondary)` | base.css |
| `--statusbar-hover-fg` | `#D5D7DB` | crystal-dark.css |
| `--statusbar-item-fg` | `var(--fg-secondary)` | base.css |
| `--statusbar-sep` | `var(--divider)` | base.css |
| `--statusbar-warning-fg` | `#C7A54A` | crystal-dark.css |
| `--stripe-error` | `var(--error)` | base.css |
| `--stripe-info` | `var(--info)` | base.css |
| `--stripe-warning` | `var(--warning)` | base.css |
| `--success` | `#3C8527` | crystal-dark.css |
| `--surface-base` | `#1E1E1E` | crystal-dark.css |
| `--surface-editor` | `#1E1E1E` | crystal-dark.css |
| `--surface-overlay` | `#4B4B4B` | crystal-dark.css |
| `--surface-panel` | `#2B2B2B` | crystal-dark.css |
| `--surface-raised` | `#3C3F41` | crystal-dark.css |
| `--surface-recessed` | `#1B1B1B` | crystal-dark.css |
| `--switch-bg` | `#6E6E6E` | crystal-dark.css |
| `--switch-checked-bg` | `var(--success)` | base.css |
| `--switch-disabled-knob` | `#8C8C8C` | crystal-dark.css |
| `--switch-knob` | `#D0D1D4` | crystal-dark.css |
| `--syntax-bracket` | `#4EC9A0` | dark-plus.css |
| `--syntax-comment` | `#6A9955` | dark-plus.css |
| `--syntax-function` | `#DCDCAA` | dark-plus.css |
| `--syntax-keyword` | `#569CD6` | dark-plus.css |
| `--syntax-number` | `#B5CEA8` | dark-plus.css |
| `--syntax-string` | `#CE9178` | dark-plus.css |
| `--syntax-type` | `#4EC9A0` | dark-plus.css |
| `--tab-active-bg` | `#3A3A3A` | crystal-dark.css |
| `--tab-active-fg` | `var(--fg-on-accent)` | base.css |
| `--tab-bg` | `#6E6E6E` | crystal-dark.css |
| `--tab-disabled-bg` | `#4A4A4A` | crystal-dark.css |
| `--tab-disabled-fg` | `var(--fg-disabled)` | base.css |
| `--tab-fg` | `#DDDDDD` | crystal-dark.css |
| `--tab-hover-bg` | `#8C8C8C` | crystal-dark.css |
| `--tab-pane-bg` | `#3A3A3A` | crystal-dark.css |
| `--tab-strip-bg` | `#2A2A2A` | crystal-dark.css |
| `--tooltip-bg` | `#1E1E1EF0` | crystal-dark.css |
| `--tooltip-border` | `#00000080` | crystal-dark.css |
| `--tooltip-fg` | `var(--fg-on-accent)` | base.css |
| `--tree-bg` | `#252526` | crystal-dark.css |
| `--tree-dimmed-fg` | `#6A6A6A` | crystal-dark.css |
| `--tree-drop-border` | `#4A90D9` | crystal-dark.css |
| `--tree-editor-bg` | `#1E1F22` | crystal-dark.css |
| `--tree-editor-border` | `var(--link)` | base.css |
| `--tree-match-fg` | `var(--fg-on-accent)` | base.css |
| `--tree-selected-bg` | `#2F5F9E` | crystal-dark.css |
| `--tree-selected-fg` | `var(--fg-on-accent)` | base.css |
| `--warning` | `#CCA700` | crystal-dark.css |
<!-- TOKENS:END -->
