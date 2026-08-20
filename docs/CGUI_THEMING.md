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
| `--accent-soft` | `#2E436E` | crystal-dark.css |
| `--activitybar-badge-fg` | `var(--fg-on-accent)` | base.css |
| `--activitybar-bg` | `var(--surface-base)` | base.css |
| `--activitybar-dot-fg` | `var(--accent)` | base.css |
| `--activitybar-item-checked-bg` | `#46484b` | crystal-dark.css |
| `--activitybar-item-fg` | `var(--fg-secondary)` | base.css |
| `--activitybar-item-focused-bg` | `var(--accent)` | base.css |
| `--activitybar-item-focused-fg` | `var(--fg-on-accent)` | base.css |
| `--activitybar-item-hover-bg` | `#46484b` | crystal-dark.css |
| `--activitybar-separator` | `var(--divider)` | base.css |
| `--balloon-bg` | `var(--surface-raised)` | base.css |
| `--balloon-border` | `var(--border-base)` | base.css |
| `--balloon-hover-bg` | `var(--hover-bg)` | base.css |
| `--banner-border` | `var(--divider)` | base.css |
| `--banner-error-bg` | `#4A2D2D` | crystal-dark.css |
| `--banner-error-fg` | `#E5B4B4` | crystal-dark.css |
| `--banner-info-bg` | `#2D3A4A` | crystal-dark.css |
| `--banner-info-fg` | `#C8D6E5` | crystal-dark.css |
| `--banner-warning-bg` | `#4A3F2D` | crystal-dark.css |
| `--banner-warning-fg` | `#E5D6A8` | crystal-dark.css |
| `--blackboard-arrow-fg` | `var(--fg-hint)` | base.css |
| `--blackboard-arrow-hover-fg` | `var(--fg)` | base.css |
| `--blackboard-drop-line` | `#44C0FF` | crystal-dark.css |
| `--blackboard-empty-fg` | `var(--fg-hint)` | base.css |
| `--blackboard-subtitle-fg` | `var(--fg-hint)` | base.css |
| `--blackboard-type-fg` | `var(--fg-hint)` | base.css |
| `--border-base` | `#393B40` | crystal-dark.css |
| `--border-field` | `#393B40` | crystal-dark.css |
| `--border-strong` | `#5A5D63` | crystal-dark.css |
| `--breadcrumb-current-fg` | `var(--fg)` | base.css |
| `--breadcrumb-link-fg` | `var(--link)` | base.css |
| `--breadcrumb-sep-fg` | `var(--fg-hint)` | base.css |
| `--button-bg` | `var(--surface-raised)` | base.css |
| `--button-disabled-bg` | `var(--surface-raised)` | base.css |
| `--button-disabled-fg` | `var(--fg-disabled)` | base.css |
| `--button-fg` | `var(--fg)` | base.css |
| `--button-hover-bg` | `var(--hover-bg)` | base.css |
| `--button-pressed-bg` | `var(--pressed-bg)` | base.css |
| `--checkbox-checked-bg` | `var(--success)` | base.css |
| `--checkbox-disabled-bg` | `var(--surface-raised)` | base.css |
| `--checkbox-mark-bg` | `var(--surface-raised)` | base.css |
| `--checkbox-mark-hover-bg` | `var(--hover-bg)` | base.css |
| `--colorpicker-bg` | `#1E1E1EDD` | crystal-dark.css |
| `--colorpicker-channel-fg` | `var(--fg-secondary)` | base.css |
| `--colorpicker-handle-ring` | `var(--fg-on-accent)` | base.css |
| `--colorpicker-handle-shadow` | `#00000080` | crystal-dark.css |
| `--colorpicker-thumb` | `var(--fg-on-accent)` | base.css |
| `--colorpicker-thumb-ring` | `var(--border-strong)` | base.css |
| `--completion-deprecated` | `var(--fg-hint)` | base.css |
| `--completion-detail` | `var(--fg-hint)` | base.css |
| `--completion-grip` | `var(--border-strong)` | base.css |
| `--completion-hint` | `var(--fg-hint)` | base.css |
| `--completion-icon` | `var(--fg-hint)` | base.css |
| `--completion-label` | `var(--fg)` | base.css |
| `--completion-match` | `var(--link)` | base.css |
| `--completion-options-hover-bg` | `var(--hover-bg)` | base.css |
| `--completion-params` | `var(--fg-hint)` | base.css |
| `--completion-selected-bg` | `var(--accent-soft)` | base.css |
| `--configkit-alpha-bg` | `#14140F` | crystal-dark.css |
| `--configkit-alpha-fill` | `var(--fg-on-accent)` | base.css |
| `--configkit-band-bg` | `var(--surface-raised)` | base.css |
| `--configkit-check-hover-bg` | `var(--hover-bg)` | base.css |
| `--configkit-dropdown-hover-bg` | `var(--hover-bg)` | base.css |
| `--configkit-dropdown-pressed-bg` | `var(--pressed-bg)` | base.css |
| `--configkit-field-bg` | `var(--surface-recessed)` | base.css |
| `--configkit-field-border` | `var(--border-field)` | base.css |
| `--configkit-field-border-bottom` | `var(--border-base)` | base.css |
| `--configkit-field-border-top` | `var(--border-field)` | base.css |
| `--configkit-label-fg` | `var(--fg)` | base.css |
| `--configkit-list-body-bg` | `var(--surface-panel)` | base.css |
| `--configkit-list-head-bg` | `var(--surface-raised)` | base.css |
| `--configkit-panel-bg` | `var(--surface-panel)` | base.css |
| `--configkit-popup-bg` | `var(--surface-overlay)` | base.css |
| `--configkit-slider-fill` | `var(--border-strong)` | base.css |
| `--configkit-swatch-border` | `var(--border-base)` | base.css |
| `--configkit-value-fg` | `var(--fg)` | base.css |
| `--decoration-dirty` | `var(--modified)` | base.css |
| `--decoration-error` | `var(--error)` | base.css |
| `--decoration-ignored` | `var(--fg-disabled)` | base.css |
| `--decoration-info` | `var(--info)` | base.css |
| `--decoration-modified` | `var(--modified)` | base.css |
| `--decoration-readonly` | `var(--fg-hint)` | base.css |
| `--decoration-warning` | `var(--warning)` | base.css |
| `--dialog-backdrop` | `#00000080` | crystal-dark.css |
| `--dialog-bg` | `var(--surface-panel)` | base.css |
| `--dialog-border` | `var(--border-base)` | base.css |
| `--dialog-close-bg` | `var(--surface-overlay)` | base.css |
| `--dialog-close-fg` | `var(--fg)` | base.css |
| `--dialog-close-hover-bg` | `var(--error)` | base.css |
| `--dialog-fg` | `var(--fg)` | base.css |
| `--dialog-picker-bg` | `#2E2E2E76` | crystal-dark.css |
| `--dialog-title-bg` | `var(--surface-raised)` | base.css |
| `--divider` | `#2B2D30` | crystal-dark.css |
| `--doc-bg` | `var(--surface-panel)` | base.css |
| `--doc-body-fg` | `var(--fg)` | base.css |
| `--doc-border` | `var(--border-base)` | base.css |
| `--doc-footer-fg` | `var(--fg-hint)` | base.css |
| `--doc-owner-fg` | `var(--fg-hint)` | base.css |
| `--doc-owner-note-fg` | `var(--fg-disabled)` | base.css |
| `--doc-problem-bg` | `var(--surface-base)` | base.css |
| `--dock-active-border` | `var(--accent-soft)` | base.css |
| `--dock-bg` | `var(--surface-panel)` | base.css |
| `--dock-drop-overlay` | `#4A88C766` | crystal-dark.css |
| `--dock-empty-bg` | `var(--surface-base)` | base.css |
| `--dock-floating-bg` | `#1E1E1EF0` | crystal-dark.css |
| `--dock-insertion-bg` | `#7EB6FF30` | crystal-dark.css |
| `--dock-insertion-border` | `var(--accent)` | base.css |
| `--dock-missing-bg` | `var(--surface-raised)` | base.css |
| `--editor-bg` | `#00000000` | dark-plus.css |
| `--editor-caret` | `#FF0000` | dark-plus.css |
| `--editor-current-line` | `#1F2124` | dark-plus.css |
| `--editor-fg` | `#D4D4D4` | dark-plus.css |
| `--editor-fold` | `#4A515C` | dark-plus.css |
| `--editor-fold-active` | `#A9B2BF` | dark-plus.css |
| `--editor-fold-placeholder-bg` | `#2A303A` | dark-plus.css |
| `--editor-fold-placeholder-fg` | `#8A94A3` | dark-plus.css |
| `--editor-fold-placeholder-hover-bg` | `#3C4553` | dark-plus.css |
| `--editor-fold-placeholder-hover-fg` | `#DCE1E8` | dark-plus.css |
| `--editor-gutter-bg` | `#191A1C` | dark-plus.css |
| `--editor-gutter-edge` | `#2B2D30` | dark-plus.css |
| `--editor-indent-guide` | `#2B2D30` | dark-plus.css |
| `--editor-indent-guide-active` | `#6E7A8A` | dark-plus.css |
| `--editor-line-number` | `#6B6E76` | dark-plus.css |
| `--editor-occurrence-bg` | `#3A3D41` | dark-plus.css |
| `--editor-ruler` | `#2B2D30` | dark-plus.css |
| `--editor-selection-bg` | `#2C5A8C` | dark-plus.css |
| `--editor-selection-occurrence-bg` | `#2E3236` | dark-plus.css |
| `--editor-whitespace` | `#454C57` | dark-plus.css |
| `--editor-zoom-bg` | `#2B303B` | dark-plus.css |
| `--editor-zoom-fg` | `#C8CDD4` | dark-plus.css |
| `--editor-zoom-link` | `#4A9EFF` | dark-plus.css |
| `--editor-zoom-link-pressed` | `#2F7FD8` | dark-plus.css |
| `--editorfind-bg` | `var(--surface-panel)` | base.css |
| `--editorfind-border` | `var(--surface-base)` | base.css |
| `--error` | `#F14C4C` | crystal-dark.css |
| `--error-icon` | `#E55765` | crystal-dark.css |
| `--fg` | `#DFE1E5` | crystal-dark.css |
| `--fg-disabled` | `#5A5D63` | crystal-dark.css |
| `--fg-hint` | `#7A7A7A` | crystal-dark.css |
| `--fg-on-accent` | `#FFFFFF` | crystal-dark.css |
| `--fg-secondary` | `#9DA0A8` | crystal-dark.css |
| `--field-bg` | `var(--surface-recessed)` | base.css |
| `--field-disabled-bg` | `var(--surface-base)` | base.css |
| `--field-disabled-fg` | `var(--fg-disabled)` | base.css |
| `--field-fg` | `var(--fg)` | base.css |
| `--field-invalid-bg` | `#4A2A2A` | crystal-dark.css |
| `--field-invalid-fg` | `var(--error)` | base.css |
| `--field-placeholder` | `var(--fg-hint)` | base.css |
| `--find-match-bg` | `#C8873C` | dark-plus.css |
| `--find-match-fg` | `#1B1B1B` | dark-plus.css |
| `--findbar-action-bg` | `#00000000` | base.css |
| `--findbar-action-border` | `var(--border-base)` | base.css |
| `--findbar-action-disabled-bg` | `#00000000` | base.css |
| `--findbar-action-disabled-fg` | `var(--fg-disabled)` | base.css |
| `--findbar-action-fg` | `var(--fg)` | base.css |
| `--findbar-action-focus-border` | `var(--accent)` | base.css |
| `--findbar-arrow-fg` | `var(--fg-secondary)` | base.css |
| `--findbar-bg` | `var(--surface-panel)` | base.css |
| `--findbar-border` | `var(--divider)` | base.css |
| `--findbar-button-fg` | `var(--fg-secondary)` | base.css |
| `--findbar-button-hover-bg` | `var(--hover-bg)` | base.css |
| `--findbar-button-hover-fg` | `var(--fg)` | base.css |
| `--findbar-close-hover-bg` | `var(--hover-bg)` | base.css |
| `--findbar-count-fg` | `var(--fg-hint)` | base.css |
| `--findbar-error-fg` | `var(--error)` | base.css |
| `--findbar-fg` | `var(--fg)` | base.css |
| `--findbar-icon` | `var(--fg-secondary)` | base.css |
| `--findbar-icon-hover` | `var(--fg)` | base.css |
| `--findbar-icon-off` | `var(--fg-disabled)` | base.css |
| `--findbar-toggle-fg` | `var(--fg-secondary)` | base.css |
| `--findbar-toggle-hover-fg` | `var(--fg)` | base.css |
| `--findbar-toggle-on-bg` | `var(--accent)` | base.css |
| `--findbar-toggle-on-fg` | `var(--fg-on-accent)` | base.css |
| `--focus-ring` | `#0060DF` | crystal-dark.css |
| `--ghost-bg` | `#2F5F9EC0` | crystal-dark.css |
| `--ghost-label-bg` | `#3C3F41F0` | crystal-dark.css |
| `--ghost-label-fg` | `var(--fg-on-accent)` | base.css |
| `--graph-canvas` | `var(--surface-editor)` | base.css |
| `--graph-collapse-fg` | `var(--fg-secondary)` | base.css |
| `--graph-collapse-hover-fg` | `var(--fg)` | base.css |
| `--graph-control-fg` | `var(--fg-secondary)` | base.css |
| `--graph-control-row-bg` | `#56565676` | crystal-dark.css |
| `--graph-dot-bg` | `var(--surface-base)` | base.css |
| `--graph-edge` | `var(--border-base)` | base.css |
| `--graph-editor-dot-ring-bg` | `var(--surface-panel)` | base.css |
| `--graph-exposed-dot` | `#7BD64B` | crystal-dark.css |
| `--graph-exposed-ring` | `#7BD64B` | crystal-dark.css |
| `--graph-field` | `var(--surface-recessed)` | base.css |
| `--graph-hairline` | `var(--divider)` | base.css |
| `--graph-header` | `var(--surface-raised)` | base.css |
| `--graph-inline-editor-bg` | `#56565676` | crystal-dark.css |
| `--graph-inputs-bg` | `#53535376` | crystal-dark.css |
| `--graph-marquee-bg` | `#2C79C433` | crystal-dark.css |
| `--graph-marquee-border` | `#44C0FF` | crystal-dark.css |
| `--graph-node-hover-ring` | `#327090` | crystal-dark.css |
| `--graph-outputs-bg` | `#35353576` | crystal-dark.css |
| `--graph-panel` | `var(--surface-panel)` | base.css |
| `--graph-port-label-hover-fg` | `var(--fg)` | base.css |
| `--graph-ports-bg` | `#26262676` | crystal-dark.css |
| `--graph-preview-bg` | `var(--surface-raised)` | base.css |
| `--graph-property-node-bg` | `var(--surface-raised)` | base.css |
| `--graph-seam` | `var(--surface-base)` | base.css |
| `--graph-selection-ring` | `#44C0FF` | crystal-dark.css |
| `--graph-text` | `var(--fg-secondary)` | base.css |
| `--graph-text-bright` | `var(--fg)` | base.css |
| `--graph-title-bg` | `#56565676` | crystal-dark.css |
| `--hover-bg` | `#2E3033` | crystal-dark.css |
| `--info` | `#3794FF` | crystal-dark.css |
| `--info-icon` | `#548AF7` | crystal-dark.css |
| `--inspection-arrow-fg` | `var(--fg-secondary)` | base.css |
| `--inspection-arrow-hover-bg` | `var(--border-base)` | base.css |
| `--inspection-arrow-hover-fg` | `var(--fg)` | base.css |
| `--inspection-clean-fg` | `var(--success)` | base.css |
| `--inspection-error-fg` | `var(--error)` | base.css |
| `--inspection-info-fg` | `var(--info)` | base.css |
| `--inspection-warning-fg` | `var(--warning)` | base.css |
| `--label-fg` | `var(--fg)` | base.css |
| `--link` | `#4A88C7` | crystal-dark.css |
| `--list-hover-bg` | `var(--hover-bg)` | base.css |
| `--list-selected-bg` | `var(--selection-bg)` | base.css |
| `--list-selected-inactive-bg` | `var(--selection-inactive-bg)` | base.css |
| `--markup-bullet-fg` | `var(--fg-hint)` | base.css |
| `--markup-code-bg` | `var(--hover-bg)` | base.css |
| `--markup-code-fg` | `var(--fg)` | base.css |
| `--markup-heading-fg` | `var(--fg)` | base.css |
| `--markup-link-fg` | `var(--link)` | base.css |
| `--markup-pre-bg` | `var(--surface-raised)` | base.css |
| `--markup-pre-border` | `var(--border-base)` | base.css |
| `--markup-pre-fg` | `var(--fg)` | base.css |
| `--markup-quote-rule` | `var(--divider)` | base.css |
| `--markup-term-fg` | `var(--fg-secondary)` | base.css |
| `--menu-accelerator` | `var(--fg-hint)` | base.css |
| `--menu-arrow` | `var(--fg-secondary)` | base.css |
| `--menu-bg` | `var(--surface-base)` | base.css |
| `--menu-border` | `var(--border-base)` | base.css |
| `--menu-fg` | `var(--fg)` | base.css |
| `--menu-item-disabled-fg` | `var(--fg-disabled)` | base.css |
| `--menu-item-focus-bg` | `var(--hover-bg)` | base.css |
| `--menu-mark` | `var(--fg)` | base.css |
| `--menu-separator` | `var(--border-base)` | base.css |
| `--menubar-bg` | `var(--surface-base)` | base.css |
| `--menubar-fg` | `var(--fg-secondary)` | base.css |
| `--menubar-hover-bg` | `var(--hover-bg)` | base.css |
| `--menubar-open-fg` | `var(--fg)` | base.css |
| `--modified` | `#E2C08D` | crystal-dark.css |
| `--nav-arrow-fg` | `var(--fg-secondary)` | base.css |
| `--nav-label-fg` | `var(--fg-secondary)` | base.css |
| `--nav-sidebar-bg` | `var(--surface-panel)` | base.css |
| `--nodemenu-bg` | `var(--surface-panel)` | base.css |
| `--nodemenu-border` | `var(--border-base)` | base.css |
| `--nodemenu-category-fg` | `var(--fg-hint)` | base.css |
| `--nodemenu-category-label-fg` | `var(--fg-secondary)` | base.css |
| `--nodemenu-empty-fg` | `var(--fg-hint)` | base.css |
| `--nodemenu-hover-bg` | `var(--hover-bg)` | base.css |
| `--nodemenu-selected-bg` | `var(--accent-soft)` | base.css |
| `--nodemenu-selected-fg` | `var(--fg-on-accent)` | base.css |
| `--nodemenu-separator-fg` | `var(--fg-disabled)` | base.css |
| `--nodemenu-title-bg` | `var(--surface-raised)` | base.css |
| `--nodemenu-twisty-fg` | `var(--fg-secondary)` | base.css |
| `--notification-bg` | `var(--surface-raised)` | base.css |
| `--notification-close-fg` | `var(--fg-hint)` | base.css |
| `--notification-close-hover-bg` | `var(--hover-bg)` | base.css |
| `--notification-close-hover-fg` | `var(--fg)` | base.css |
| `--notification-detail-fg` | `var(--fg)` | base.css |
| `--notification-error-fg` | `var(--error-icon)` | base.css |
| `--notification-hover-bg` | `var(--hover-bg)` | base.css |
| `--notification-info-fg` | `var(--info-icon)` | base.css |
| `--notification-message-fg` | `var(--fg)` | base.css |
| `--notification-secondary-fg` | `var(--fg-secondary)` | base.css |
| `--notification-secondary-hover-fg` | `var(--fg)` | base.css |
| `--notification-time-fg` | `var(--fg-hint)` | base.css |
| `--notification-warning-fg` | `var(--warning-icon)` | base.css |
| `--notifications-bg` | `#00000000` | base.css |
| `--notifications-empty-fg` | `var(--fg)` | base.css |
| `--notifications-link-fg` | `var(--link)` | base.css |
| `--notifications-link-hover-fg` | `var(--accent-hover)` | base.css |
| `--notifications-title-fg` | `var(--fg-secondary)` | base.css |
| `--pagestack-empty-fg` | `var(--fg-hint)` | base.css |
| `--palette-bg` | `var(--surface-panel)` | base.css |
| `--palette-border` | `var(--border-base)` | base.css |
| `--palette-category-fg` | `var(--fg-secondary)` | base.css |
| `--palette-field-bg` | `var(--surface-recessed)` | base.css |
| `--palette-field-border` | `var(--border-field)` | base.css |
| `--palette-field-focus-border` | `var(--accent)` | base.css |
| `--palette-hover-bg` | `var(--hover-bg)` | base.css |
| `--palette-key-bg` | `var(--surface-raised)` | base.css |
| `--palette-key-border` | `var(--border-base)` | base.css |
| `--palette-key-fg` | `var(--fg-secondary)` | base.css |
| `--palette-key-hover-border` | `var(--border-strong)` | base.css |
| `--palette-key-hover-fg` | `var(--fg-on-accent)` | base.css |
| `--palette-key-sep-fg` | `var(--fg-hint)` | base.css |
| `--palette-label-fg` | `var(--fg)` | base.css |
| `--palette-match-fg` | `var(--accent)` | base.css |
| `--palette-selected-bg` | `var(--selection-bg)` | base.css |
| `--panel-bg` | `var(--surface-panel)` | base.css |
| `--panel-gap` | `3px` | crystal-dark.css |
| `--panel-header-fg` | `var(--fg)` | base.css |
| `--panel-header-icon` | `var(--fg-secondary)` | base.css |
| `--panel-header-icon-hover` | `var(--fg)` | base.css |
| `--panel-header-icon-hover-bg` | `var(--border-base)` | base.css |
| `--panel-header-sep` | `var(--surface-base)` | base.css |
| `--pill-name-fg` | `var(--fg)` | base.css |
| `--pill-type-fg` | `var(--fg-secondary)` | base.css |
| `--pressed-bg` | `#35373B` | crystal-dark.css |
| `--preview-surface-bg` | `var(--surface-editor)` | base.css |
| `--problems-bg` | `#00000000` | base.css |
| `--problems-count-fg` | `var(--fg-hint)` | base.css |
| `--problems-empty-fg` | `var(--fg)` | base.css |
| `--problems-error-fg` | `var(--error-icon)` | base.css |
| `--problems-info-fg` | `var(--info-icon)` | base.css |
| `--problems-line-fg` | `var(--fg-hint)` | base.css |
| `--problems-message-fg` | `var(--fg)` | base.css |
| `--problems-options-fg` | `var(--fg-secondary)` | base.css |
| `--problems-options-hover-fg` | `var(--fg)` | base.css |
| `--problems-tab-active-count-fg` | `var(--fg-secondary)` | base.css |
| `--problems-tab-count-fg` | `var(--fg-hint)` | base.css |
| `--problems-tree-line-fg` | `var(--fg-hint)` | base.css |
| `--problems-unnecessary-fg` | `var(--fg-hint)` | base.css |
| `--problems-warning-fg` | `var(--warning-icon)` | base.css |
| `--prompt-bg` | `var(--surface-panel)` | base.css |
| `--prompt-border` | `var(--border-base)` | base.css |
| `--prompt-caption-fg` | `var(--fg)` | base.css |
| `--prompt-field-bg` | `var(--surface-recessed)` | base.css |
| `--prompt-field-fg` | `var(--fg)` | base.css |
| `--radius-control` | `4px` | crystal-dark.css |
| `--radius-panel` | `8px` | crystal-dark.css |
| `--region-drop-bg` | `#3574F055` | crystal-dark.css |
| `--region-drop-border` | `var(--accent)` | base.css |
| `--resizer-grip` | `var(--border-strong)` | base.css |
| `--run-action-disabled-fg` | `var(--fg-disabled)` | base.css |
| `--run-action-fg` | `var(--fg-secondary)` | base.css |
| `--run-action-hover-bg` | `var(--hover-bg)` | base.css |
| `--run-action-hover-fg` | `var(--fg)` | base.css |
| `--run-boundary-fg` | `var(--accent)` | base.css |
| `--run-empty-fg` | `var(--fg-hint)` | base.css |
| `--run-input-fg` | `var(--success)` | base.css |
| `--run-link-fg` | `var(--link)` | base.css |
| `--run-live-fg` | `var(--success-icon)` | base.css |
| `--run-notice-fg` | `var(--fg-hint)` | base.css |
| `--run-rail-bg` | `var(--surface-panel)` | base.css |
| `--run-rail-border` | `var(--divider)` | base.css |
| `--run-rail-failed` | `var(--error-icon)` | base.css |
| `--run-rail-fg` | `var(--fg)` | base.css |
| `--run-rail-hover-bg` | `var(--hover-bg)` | base.css |
| `--run-rail-idle` | `var(--fg-disabled)` | base.css |
| `--run-rail-selected-bg` | `var(--selection-bg)` | base.css |
| `--run-rail-stopped` | `var(--warning-icon)` | base.css |
| `--run-rail-time-fg` | `var(--fg-hint)` | base.css |
| `--run-stripe-bg` | `var(--surface-panel)` | base.css |
| `--scrollbar-corner` | `#00000000` | base.css |
| `--scrollbar-tail` | `#00000000` | crystal-dark.css |
| `--scrollbar-tail-hover` | `#8C8C8C33` | crystal-dark.css |
| `--scrollbar-thumb` | `#8C8C8C55` | crystal-dark.css |
| `--scrollbar-thumb-hover` | `#8C8C8C99` | crystal-dark.css |
| `--scrollbar-thumb-pressed` | `#8C8C8CCC` | crystal-dark.css |
| `--scrollbar-track` | `#00000000` | base.css |
| `--search-excluded-fg` | `#7A7A7A` | dark-plus.css |
| `--search-match-fg` | `var(--modified)` | base.css |
| `--searchfield-bg` | `var(--surface-recessed)` | base.css |
| `--searchfield-border` | `var(--border-field)` | base.css |
| `--searchfield-caret` | `var(--fg)` | base.css |
| `--searchfield-clear-fg` | `var(--fg-disabled)` | base.css |
| `--searchfield-clear-hover-bg` | `var(--hover-bg)` | base.css |
| `--searchfield-clear-hover-fg` | `var(--fg)` | base.css |
| `--searchfield-error-fg` | `var(--error)` | base.css |
| `--searchfield-fg` | `var(--fg)` | base.css |
| `--searchfield-focus-border` | `var(--accent)` | base.css |
| `--searchfield-icon-fg` | `var(--fg-secondary)` | base.css |
| `--searchfield-option-fg` | `var(--fg-secondary)` | base.css |
| `--searchfield-option-hover-bg` | `var(--hover-bg)` | base.css |
| `--searchfield-option-hover-fg` | `var(--fg)` | base.css |
| `--searchfield-option-on-bg` | `var(--accent)` | base.css |
| `--searchfield-option-on-fg` | `var(--fg-on-accent)` | base.css |
| `--searchfield-option-on-hover-bg` | `var(--accent-hover)` | base.css |
| `--selection-bg` | `#04395E` | crystal-dark.css |
| `--selection-inactive-bg` | `#2E3033` | crystal-dark.css |
| `--slider-disabled-fill` | `var(--fg-disabled)` | base.css |
| `--slider-disabled-thumb` | `var(--fg-disabled)` | base.css |
| `--slider-fill` | `var(--success)` | base.css |
| `--slider-thumb` | `var(--fg)` | base.css |
| `--slider-thumb-hover` | `var(--fg)` | base.css |
| `--slider-thumb-ring` | `var(--fg-on-accent)` | base.css |
| `--slider-track` | `var(--surface-raised)` | base.css |
| `--splitter-bg` | `var(--divider)` | base.css |
| `--splitter-hover-bg` | `var(--border-strong)` | base.css |
| `--splitter-pressed-bg` | `var(--fg)` | base.css |
| `--squiggle-error` | `var(--error)` | base.css |
| `--squiggle-info` | `var(--info)` | base.css |
| `--squiggle-warning` | `var(--warning)` | base.css |
| `--statusbar-bg` | `var(--surface-base)` | base.css |
| `--statusbar-border` | `var(--divider)` | base.css |
| `--statusbar-crumb-fg` | `var(--fg-hint)` | base.css |
| `--statusbar-error-fg` | `var(--error)` | base.css |
| `--statusbar-fg` | `var(--fg-secondary)` | base.css |
| `--statusbar-hover-fg` | `var(--fg)` | base.css |
| `--statusbar-item-fg` | `var(--fg-secondary)` | base.css |
| `--statusbar-sep` | `var(--border-base)` | base.css |
| `--statusbar-warning-fg` | `var(--warning)` | base.css |
| `--stripe-error` | `var(--error)` | base.css |
| `--stripe-info` | `var(--info)` | base.css |
| `--stripe-warning` | `var(--warning)` | base.css |
| `--success` | `#3C8527` | crystal-dark.css |
| `--success-icon` | `#5FAD65` | crystal-dark.css |
| `--surface-base` | `#26282B` | crystal-dark.css |
| `--surface-editor` | `#191A1C` | crystal-dark.css |
| `--surface-overlay` | `#2B2D30` | crystal-dark.css |
| `--surface-panel` | `#191A1C` | crystal-dark.css |
| `--surface-raised` | `#2B2B2B` | crystal-dark.css |
| `--surface-recessed` | `#131416` | crystal-dark.css |
| `--switch-bg` | `var(--surface-raised)` | base.css |
| `--switch-checked-bg` | `var(--success)` | base.css |
| `--switch-disabled-knob` | `var(--fg-disabled)` | base.css |
| `--switch-knob` | `var(--fg)` | base.css |
| `--syntax-attribute` | `#9CDCFE` | dark-plus.css |
| `--syntax-boolean` | `#569CD6` | dark-plus.css |
| `--syntax-bracket` | `#4EC9A0` | dark-plus.css |
| `--syntax-bracket-weight` | `normal` | dark-plus.css |
| `--syntax-captured-underline` | `#569CD6` | dark-plus.css |
| `--syntax-comment` | `#6A9955` | dark-plus.css |
| `--syntax-comment-doc` | `#6A9955` | dark-plus.css |
| `--syntax-comment-style` | `normal` | dark-plus.css |
| `--syntax-constant` | `#4FC1FF` | dark-plus.css |
| `--syntax-constant-builtin` | `#569CD6` | dark-plus.css |
| `--syntax-constant-style` | `normal` | dark-plus.css |
| `--syntax-constant-weight` | `normal` | dark-plus.css |
| `--syntax-constructor` | `#4EC9A0` | dark-plus.css |
| `--syntax-deprecated` | `#808080` | dark-plus.css |
| `--syntax-doc-markup` | `#569CD6` | dark-plus.css |
| `--syntax-doc-tag` | `#569CD6` | dark-plus.css |
| `--syntax-doc-tag-style` | `normal` | dark-plus.css |
| `--syntax-doc-tag-weight` | `normal` | dark-plus.css |
| `--syntax-doc-value` | `#9CDCFE` | dark-plus.css |
| `--syntax-doc-value-style` | `normal` | dark-plus.css |
| `--syntax-embedded` | `#D4D4D4` | dark-plus.css |
| `--syntax-error` | `#F44747` | dark-plus.css |
| `--syntax-function` | `#DCDCAA` | dark-plus.css |
| `--syntax-function-builtin` | `#DCDCAA` | dark-plus.css |
| `--syntax-function-call` | `#DCDCAA` | dark-plus.css |
| `--syntax-function-method` | `#DCDCAA` | dark-plus.css |
| `--syntax-function-special` | `#DCDCAA` | dark-plus.css |
| `--syntax-function-static` | `#DCDCAA` | dark-plus.css |
| `--syntax-function-static-style` | `normal` | dark-plus.css |
| `--syntax-keyword` | `#569CD6` | dark-plus.css |
| `--syntax-keyword-control` | `#C586C0` | dark-plus.css |
| `--syntax-keyword-operator` | `#569CD6` | dark-plus.css |
| `--syntax-keyword-weight` | `normal` | dark-plus.css |
| `--syntax-label` | `#C586C0` | dark-plus.css |
| `--syntax-markup` | `#D4D4D4` | dark-plus.css |
| `--syntax-module` | `#D4D4D4` | dark-plus.css |
| `--syntax-number` | `#B5CEA8` | dark-plus.css |
| `--syntax-operator` | `#D4D4D4` | dark-plus.css |
| `--syntax-parameter-reassigned-underline` | `#808080` | dark-plus.css |
| `--syntax-property` | `#9CDCFE` | dark-plus.css |
| `--syntax-punctuation` | `#808080` | dark-plus.css |
| `--syntax-punctuation-bracket` | `#808080` | dark-plus.css |
| `--syntax-punctuation-delimiter` | `#808080` | dark-plus.css |
| `--syntax-reassigned-underline` | `#808080` | dark-plus.css |
| `--syntax-string` | `#CE9178` | dark-plus.css |
| `--syntax-string-escape` | `#D7BA7D` | dark-plus.css |
| `--syntax-string-special` | `#D7BA7D` | dark-plus.css |
| `--syntax-tag` | `#569CD6` | dark-plus.css |
| `--syntax-type` | `#4EC9A0` | dark-plus.css |
| `--syntax-type-builtin` | `#569CD6` | dark-plus.css |
| `--syntax-type-enum` | `#4EC9A0` | dark-plus.css |
| `--syntax-type-enum-style` | `normal` | dark-plus.css |
| `--syntax-type-interface` | `#4EC9A0` | dark-plus.css |
| `--syntax-type-parameter` | `#4EC9A0` | dark-plus.css |
| `--syntax-type-qualifier` | `#569CD6` | dark-plus.css |
| `--syntax-unnecessary` | `#6A6A6A` | dark-plus.css |
| `--syntax-unresolved` | `#F44747` | dark-plus.css |
| `--syntax-variable` | `#9CDCFE` | dark-plus.css |
| `--syntax-variable-builtin` | `#569CD6` | dark-plus.css |
| `--syntax-variable-captured` | `#9CDCFE` | dark-plus.css |
| `--syntax-variable-member` | `#9CDCFE` | dark-plus.css |
| `--syntax-variable-parameter` | `#9CDCFE` | dark-plus.css |
| `--tab-active-bg` | `var(--surface-raised)` | base.css |
| `--tab-active-border` | `var(--border-base)` | base.css |
| `--tab-active-fg` | `var(--fg)` | base.css |
| `--tab-bg` | `#00000000` | base.css |
| `--tab-close-fg` | `var(--fg-disabled)` | base.css |
| `--tab-close-hover-bg` | `var(--hover-bg)` | base.css |
| `--tab-close-hover-fg` | `var(--fg)` | base.css |
| `--tab-disabled-bg` | `var(--surface-raised)` | base.css |
| `--tab-disabled-fg` | `var(--fg-disabled)` | base.css |
| `--tab-fg` | `var(--fg-secondary)` | base.css |
| `--tab-focused-bg` | `var(--accent-soft)` | base.css |
| `--tab-focused-border` | `var(--accent)` | base.css |
| `--tab-focused-fg` | `var(--fg)` | base.css |
| `--tab-hover-bg` | `var(--hover-bg)` | base.css |
| `--tab-pane-bg` | `#00000000` | base.css |
| `--tab-strip-bg` | `#00000000` | base.css |
| `--tooltip-bg` | `#1E1E1EF0` | crystal-dark.css |
| `--tooltip-border` | `#00000080` | crystal-dark.css |
| `--tooltip-fg` | `var(--fg)` | base.css |
| `--tree-dimmed-fg` | `var(--fg-hint)` | base.css |
| `--tree-drop-border` | `var(--accent)` | base.css |
| `--tree-editor-bg` | `var(--surface-recessed)` | base.css |
| `--tree-editor-border` | `var(--accent)` | base.css |
| `--tree-fg` | `var(--fg)` | base.css |
| `--tree-match-fg` | `var(--fg)` | base.css |
| `--tree-selected-bg` | `var(--accent-soft)` | base.css |
| `--tree-selected-fg` | `var(--fg-on-accent)` | base.css |
| `--tree-selected-inactive-bg` | `var(--surface-raised)` | base.css |
| `--tree-selected-inactive-fg` | `var(--fg-secondary)` | base.css |
| `--tree-twisty-fg` | `var(--fg-secondary)` | base.css |
| `--warning` | `#CCA700` | crystal-dark.css |
| `--warning-icon` | `#FFAF0F` | crystal-dark.css |
| `--workbench-bg` | `var(--surface-base)` | base.css |

<!-- TOKENS:END -->
