# Styling Overhaul — Themes, Tokens, Governance, and the Editor's Face

*The dedicated plan for CrystalGUI's appearance layer: the audit of where styling stands, the theme
architecture we are porting (IntelliJ's, deliberately, with VS Code and Material 3 supplying the
token discipline), the governance machinery that keeps it from rotting again, and the migration that
gets there without breaking the ~2,900 tests standing on the current sheets. Sibling of `plan.md`,
scoped to styling only.*

*Research grounding: JetBrains Platform theme docs (structure of `*.theme.json`, editor schemes,
icon palettes), VS Code's theme-color reference (token taxonomy and user overrides), Material 3's
token tiering. Links in §9.*

---

## 0. The three asks, and the verdict on each

1. **"default.css is Chernobyl."** Correct, and now quantified: 6,241 lines, 353 hex literal uses,
   **161 distinct colours** where a designed system needs ~40. Among them five near-identical
   mid-greys (`#8a8a8a`, `#8c8c8c`, `#8a8d94`, `#9a9a9a`, `#9da0a8`) — visibly the same *intent*
   minted five separate times, which is the rot mechanism in one line: **there was no palette to
   reach for, so every rule minted its own hex.** The caveat that shapes the migration: the file is
   not *wrong*, it is *unthemeable*. The cascade math works — USER_AGENT origin loses to any author
   sheet at any specificity, so a theme can already override all 161 colours. It just cannot do so
   **by name**, because there are no names. The fix is tokenization plus governance, not demolition.

2. **Swappable/downloadable appearance themes, IntelliJ-style.** The substrate is closer than it
   looks. `var(--x)` exists and is used 139 times; the engine has identity-stable sheet hot-reload
   (`StyleSheet.replaceRules` + `StyleEngine.invalidateAllMatches`) — *exactly* the mechanism a live
   theme swap needs. Three genuine engine gaps (per-sheet-sealed variables, no var-in-var, no
   fallback form), all small (§3.4). The larger gap is organizational: nothing owns the sheet stack,
   and nothing *enforces* any styling rule — which is why §4 (governance) exists and is the part of
   this plan that answers "doesn't turn into Chernobyl 2.0 over time".

3. **CrystalEditor looks rough.** Decomposed into a punch list (§6). Half of it is palette
   incoherence — at least four unrelated darks with no shared ancestry — which falls out of
   tokenization for free: once every surface reads `var(--surface-*)`, coherence is a property of
   one small theme table, not of 6,000 scattered declarations. The rest is a dozen structural
   offenders, each itemized.

---

## 1. Where we are — the audit

### 1.1 The sheets

| Sheet | Lines | Hex uses | Distinct | `var()` refs | Role today |
|---|---|---|---|---|---|
| `default.css` | 6,241 | 353 | 161 | 106 | UA sheet **and** the entire workbench appearance |
| `graph.css` | 868 | 105 | — | 33 | Node-graph theme (Unity Shader Graph look) |
| `ore.css` | 759 | 47 | — | 0 | Minecraft Ore sprite theme — proof asset themes work |
| `filetypes.css` | 121 | 69 | — | 0 | Per-file-type colour palette (`.filetype-*`) |
| `decorations.css` | 48 | 12 | — | 0 | VCS/problem decoration palette |

586 hex uses total; default.css carries 60% of them and ~78% of all rules in the engine.

### 1.2 What already works in our favour

- **Origin math.** `USER_AGENT(1) < STYLESHEET(2)` is weighed before specificity (StyleSlot
  compareTo), so author sheets beat default.css without selector fights, and UA-vs-author
  registration order is irrelevant (StyleSheet.java:51). Theming never needs `!important`.
- **`var(--x)` substitution.** `DeclarationParser.collectVariables` gathers every `--name: value`
  from every rule in a sheet; `parseBlock(text, vars)` substitutes textually at parse time.
  `graph.css` and default.css's config-kit block prove the idiom carries both geometry and colour.
- **Identity-stable hot reload.** `StyleSheetRegistry.reloadAll()` re-reads sources and calls
  `sheet.replaceRules(...)` **in place** — every window holding the sheet updates with no
  add/remove, then `invalidateAllMatches()` re-matches. A theme swap is this same operation with a
  different variable table. The machinery is built; it doesn't know about themes yet.
- **Editor colours are already CSS.** The big one. Syntax tokens flow through the CSS Custom
  Highlight API: `SyntaxToken(start, end, name)` → ranges published under `::highlight(<name>)` →
  rules at default.css:617–639 colour the six general names (currently VS Code Dark+ values), with
  `SyntaxToken.generalName()` giving the `function.builtin → function` fallback that VS Code's
  semantic-token layering needs a mechanism for. Bracket match, search, exclusions, mnemonics — all
  `::highlight()` too. **An "editor colour scheme" is already expressible as one CSS file.**
  IntelliJ needed a whole `.icls` XML format for this; we get it as a corollary of the highlight
  system.
- **Settings + Preferences exist.** `com.crystalgui.core.settings` is a full substrate (declared
  settings, registry, layers, scopes, codec persistence); `Preferences` builds pages lazily from
  `SettingsCategory`. A Theme dropdown is a declared setting plus one page.
- **Sprite themes already swap live** (`CgUiGalleryScene` toggles ore.css). Asset themes shipped by
  resource pack with zero code are an existing capability — that *is* our theme-distribution story.

### 1.3 The genuine gaps

1. **Variables are sealed inside their sheet.** Substitution happens in `StyleSheet.parse` against
   the sheet's own table only. A theme declaring `--surface-panel: #1A1B1E` changes nothing in any
   other sheet. *The* architectural blocker; everything else is decoration.
2. **No `var()` inside a variable's value** (DeclarationParser.java:146–148, deliberate). Tiered
   tokens need exactly this: ~40 semantic colours driving ~200 component tokens, so a theme author
   overrides 40 values, not 400.
3. **No `var(--x, fallback)`** two-arg form. Wanted so sparse themes degrade sensibly.
4. **Nobody owns the sheet stack.** Every harness scene hand-assembles sheets; `ShaderGraphEditor`
   adds its own. No object answers "what sheets does this window run, in what order" — the object a
   theme manager has to *be*.
5. **Nothing is enforced.** default.css's own three rules (no `!important`, no `asset()`,
   `background:` not `background-color:`) live in a comment. The "geometry only" contract died
   silently because breaking it broke no build. This is the root cause of Chernobyl and gets its own
   section (§4).
6. **Colour hardcodes in Java** — small but real: the `icon(..., #3e3e3e, monochrome)` workaround
   family (pending the SVG `currentColor` fix, with another agent). Audit sweep in §5 step 4.
7. **default.css is unreadable as one artifact** — orthogonal to theming, real for maintenance.

### 1.4 What the current file does *right* (do not lose these)

The essays in default.css encode ~30 hard-won invariants — `background:` vs `background-color:`
(drawable vs multiplicative tint), per-widget border-radius (never on `*`, the FBO-mask reason),
`:focus-visible` semantics, min-height-beats-height, `:disabled` after `:hover`. Any file move
carries the comments **with** their rules; they are why the same bug hasn't shipped five times.

---

## 2. The references — what they actually do

### 2.1 IntelliJ UI themes (`*.theme.json`)

A theme is JSON over a base look-and-feel, with these load-bearing parts:

```jsonc
{
  "name": "Islands Dark", "dark": true, "author": "...",
  "parentTheme": "SomeThemeId",          // real single-parent inheritance
  "editorScheme": "/IslandsDark.xml",    // the SECOND axis, bundled by reference
  "colors": {                            // named palette — defined once...
    "primaryBackground": "#2B2D30",
    "accent": "#3574F0"
  },
  "ui": {                                // ...referenced by name from component keys
    "*": { "background": "primaryBackground" },   // wildcard defaults by PROPERTY
    "Button.startBackground": "accent",
    "EditorTabs.underlinedTabBackground": "#000000",
    "Component.focusColor": "accent",
    "Button.arc": 6                      // NOT only colours: arcs, borders, insets
  },
  "icons": {
    "ColorPalette": { "Actions.Blue": "#5BC0DE" },  // recolour stock icons by named slot
    "/actions/compile.svg": "/my/replacement.svg"    // or replace outright
  }
}
```

What to take: **(a)** a theme is a *named-key table over a base*, never a full restatement — unstated
keys fall through; **(b)** the palette block exists so component keys reference names, not hex;
**(c)** `parentTheme` — themes extend themes (GitHub Dark Dimmed style); **(d)** the wildcard —
property-level defaults with specific keys overriding; **(e)** themes may adjust *geometry-adjacent*
values (arcs, borders), not only colours; **(f)** icon recoloring is a named-slot mapping, not
per-icon hand-editing. What to leave: the LaF machinery (we have a cascade), Swing key-path
addressing (ours is selectors), JSON as the authoring format (ours is CSS — our parser exists and
themes sometimes need real override *rules*, which JSON cannot carry).

### 2.2 IntelliJ editor colour schemes (`.icls` XML)

The second, independently-selectable axis (both dropdowns sit side by side in Settings ▸
Appearance). A scheme controls: general editor surfaces (background, caret, caret row, selection,
line numbers, gutter), syntax attribute keys **with inheritance to base keys** (a language key falls
back to `DefaultLanguageHighlighterColors`), VCS file-status colours, console and diff colours. A UI
theme *bundles/suggests* a scheme via `editorScheme` but does not own it — Islands Dark + Eclipse
Dark scheme is a legal pair. → We port: the two-axis split, the independent setting, the attribute
inheritance (already ours via `generalName()`), and the bundling hint (§3.5).

### 2.3 VS Code workbench colours

Hundreds of tokens named `component.property[.state]` (`editorGroupHeader.tabsBackground`,
`list.focusBackground`, `statusBar.debuggingBackground`), each registered with **per-base defaults
and derivation fallbacks** — a token a theme doesn't set derives from a more general one
(`…tabsBackground` ← `editor.background`), so sparse themes stay coherent. On top of any theme,
users override individual tokens via `workbench.colorCustomizations`. → We port: the naming
discipline, the *complete base table with derivations* (our `base.css`, §3.1), and the user override
layer (§3.5) — at maybe 150–250 tokens, not 800.

### 2.4 Material 3 token tiers — the naming precedent

Material names three tiers: **reference** (`md.ref.palette.primary40` — raw ramps, no opinions),
**system** (`md.sys.color.primary` — semantic roles; where light/dark is decided), **component**
(`md.comp.fab.container.color` — points at a system token). The tiering is what makes wholesale
re-theming (their Dynamic Color) feasible at scale. → We adopt the sys/comp split as mandatory and
ref as optional (§3.1) — and we adopt the *vocabulary* ("system token", "component token") so our
docs and IntelliJ/VS Code/Material discussions line up.

---

## 3. Target architecture

### 3.1 The token vocabulary — three tiers, two mandatory

**Reference tokens (optional, per theme).** Raw ramps a theme *may* define for internal consistency
— `--ref-grey-10 … --ref-grey-90`, `--ref-blue-40` — and reference from its system tokens.
Supported for free once var-in-var lands; never referenced by any shipped sheet. A theme that wants
to write hex directly into system tokens simply does.

**System tokens — the finalized vocabulary (36 names, the theme author's entire surface).**
Decided under one law: **a reader must be able to place a token from its name alone — a name that
needs the doc is a wrong name.** Corollaries: role words, never value words (`--fg-hint`, never
`--fg-light-grey` — the same rule that lets light themes exist); at most `--family-role[-state]`;
and where two names could be confused *for each other*, one is renamed until they can't
(`--fg-dim`/`--fg-muted` failed this test — which is dimmer? — and became
`--fg-secondary`/`--fg-hint`, which answer themselves).

```css
/* SURFACES — what things sit ON, darkest→lightest in a dark theme; a light theme inverts.
   Each name answers "what kind of thing has this background?" */
--surface-base        /* the window ground; what shows through the gaps between islands */
--surface-panel       /* tool windows, sidebars, dialogs — the islands themselves */
--surface-raised      /* things sitting ON a panel: buttons, headers, bands */
--surface-recessed    /* things you type into or that read as wells: fields, list wells */
--surface-editor      /* THE document surface — deliberately its own name, never shared */
--surface-overlay     /* things floating above everything: menus, popovers, tooltips */

/* STROKES */
--border-base         /* the default component outline */
--border-strong       /* emphasized outline: unchecked checkbox, slider-thumb ring */
--border-field        /* text-field borders — typeable surfaces get their own, IntelliJ-style */
--divider             /* hairlines between regions inside a panel */

/* TEXT — role-named so the hierarchy is self-evident */
--fg                  /* primary text */
--fg-secondary        /* labels, titles — de-emphasized but still read */
--fg-hint             /* placeholders, hints, empty-states — read only when looked for */
--fg-disabled
--fg-on-accent        /* text on an accent fill: primary button, selected menu row */

/* THE ACCENT FAMILY */
--accent              /* the one brand colour: focus borders, active underlines, primary fills */
--accent-hover
--accent-soft         /* a washed accent for subtle fills: toggled-on chips, badges, counts */

/* INTERACTION STATE */
--hover-bg            /* any row or control under the pointer */
--pressed-bg          /* …and while pressed */
--selection-bg        /* selected rows/text in the focused component */
--selection-inactive-bg  /* the same selection when the component loses focus */
--focus-ring          /* the keyboard-focus outline colour */

/* SEMANTICS (decorations.css already half-established these) */
--error  --warning  --info  --success  --modified  --link

/* NON-COLOUR, THEMEABLE — IntelliJ themes set arcs and borders too */
--radius-control      /* buttons, fields, checkboxes — Islands: ~4–6px; a flat theme: 0 */
--radius-panel        /* the islands — Islands: ~8px; flat: 0 */
--panel-gap           /* gutters between islands — Islands: ~6px; flat: 0 */
--font-ui  --font-code  --font-size-ui
```

36 names, closed. Anything a widget needs beyond these is a *component* token deriving from them
in base.css — and the pressure to add system token #37 gets one review question: "which existing
name is this, really?" (§4.3).

**Component tokens (~150–250 names, growing with widgets).** Defined **once, complete, in
`base.css`**, each as a var-reference into system tokens — `--tab-active-bg: var(--surface-editor)`,
`--button-bg: var(--surface-raised)`, `--editor-gutter-bg: var(--surface-editor)`. This table *is*
our equivalent of VS Code's derivation registry: it is where `tabsBackground ← editor.background`
lives.

**The two laws that make the tiers real** (both machine-enforced, §4.2):

1. Sheets reference **component tokens only** — never system tokens directly, never hex.
2. `base.css` values are **var-references into system tokens only** — a component token holding a
   hex literal is the five-mid-greys bug being reborn, and the build fails on it.

Naming: `--<component>-<part>-<state?>`, kebab-case, role-named (`--tab-active-underline`, never
`--tab-blue-line`). Spacing/sizing stays structure-owned per domain (graph.css's `--graph-*` model),
*not* in the theme vocabulary — themes change look, not layout, with the narrow themeable-geometry
exceptions listed above (radii, fonts).

### 3.2 File layout, and the DEFAULT split decision

```
assets/crystalgui/ui/styles/ua/       # StyleSheet.DEFAULT — the user-agent sheet, in 9 DOMAIN parts
    core.css  widgets.css  editor.css  overlays.css  config-kit.css
    inspector.css  workbench.css  panels.css  search.css
    # One sheet, one parse, one variable scope — concatenated in the DEFAULT_SHEET_PARTS manifest
    # order, which is load-bearing exactly as order within a file is. Contract, enforced: no
    # !important, no asset(), colours only as var(--token, #fallback).

assets/crystalgui/ui/themes/          # THE TABLES
    base.css                          # component tokens, COMPLETE, derived from system tokens
    crystal-dark.css                  # system tokens (+ optional ref ramps, + fine-tunes). Default.
    crystal-light.css                 # the vocabulary's acceptance test (§5 step 9)
    high-contrast.css                 # later

assets/crystalgui/ui/schemes/         # EDITOR COLOUR SCHEMES — the second axis
    dark-plus.css                     # today's values extracted: ::highlight() + --editor-* tokens
```

**REVISED at step 8 (2026-08-10): the split is by DOMAIN, not by function/look — `BASE_LOOK`
deferred.** The plan's original two-sheet design (`DEFAULT` = function, `BASE_LOOK` = appearance)
predates step 5's fallback-tokenization, which changed the calculus three ways. First, the benefit
shrank: the look is already theme-overridable *in place* and governance already enforces at the
value level (no bare hex, tokens defined), so a per-file function/look contract adds little. Second,
the cost grew: most rules mix both kinds (`button` carries `min-height` *and* `background`), so the
split would be declaration-level surgery over hundreds of rules — scattering each widget across two
files and walking straight into the order-sensitive traps (`:disabled` after `:hover`, the
menu-border outline that is both geometry and colour). Third, the domain split is what maintenance
actually wants ("the tab strip's rules", not "the colour half of everything") and it can be done as
a **contiguous partition** — the parts concatenated in manifest order ARE the old file, so the move
is provably pure (verified declaration-identical against HEAD). `BASE_LOOK` stays deferred with a
named trigger: revisit if a real consumer needs to install function-without-look (nothing does; the
themeless fallbacks serve that today). The earlier `BASE`-origin deferral stands unchanged.

**A theme file is ordinary CSS**: a `theme { }` block of variable definitions (convention — any rule
works, `collectVariables` reads them all, but one uniform container keeps files greppable), plus
*optional real override rules* for what variables can't express — a different border treatment, an
`asset()` sprite skin. ore.css demonstrates the all-rules extreme and stays as-is. Metadata rides in
a fixed-shape header comment, parsed by the registry:

```css
/* @theme  Crystal Dark
 * @id     crystalgui:crystal-dark
 * @kind   dark            (dark | light | high-contrast)
 * @extends —              (optional parent theme id — IntelliJ's parentTheme)
 * @editor-scheme crystalgui:dark-plus   (suggested editor scheme — IntelliJ's editorScheme)
 * @author crystalgui */
```

A **scheme file** is the same shape scoped to the editor: `::highlight()` rules + `--editor-*`
variables, opening with `@scheme <name>` instead of `@theme <name>` — the first tag is what
declares which artifact a file is, which is why the bundled-scheme *suggestion* inside a theme is
spelled `@editor-scheme`, never `@scheme`. No sidecar JSON until richer metadata is actually
needed.

**How a theme interacts with a widget — three depths, and widgets are not special.** The core
widgets (Button, TextField, Slider, Dialog, Menu, …) go through the *same* contract as the
workbench and the editor; there is one vocabulary, and a widget's component tokens sit in the same
`base.css` as the tab strip's. A theme chooses its depth per widget:

1. **Tokens only (the 99% path — a theme never names a widget).** `look/widgets.css` owns the
   rules: `button { background: var(--button-bg); border-radius: var(--radius-control); }`. The
   theme redefines system tokens (`--accent`, `--surface-raised`, `--radius-control`) — or
   fine-tunes a component token (`--button-bg`) — and every widget follows. This is how Islands
   Dark and a flat theme restyle the same Button without either touching a selector.
2. **Override rules, for what tokens cannot say.** A theme may carry real CSS rules (STYLESHEET
   origin, which beats `BASE_LOOK`'s USER_AGENT at any specificity): a different structural
   treatment of a part, a border style, an `asset()` sprite skin. Targets are the public tags and
   the documented `__part__` classes (`__knob__`, `__track__`, `__title-bar__` — the internal-child
   vocabulary **is** the widget theming API, and gets listed as such in `docs/CGUI_THEMING.md`).
3. **Full skin.** The rules-only extreme — ore.css today: 9-slice sprite buttons, zero variables.
   Stays fully supported; it is the proof the origin math works.

**ore.css's fate — promoted, not retired.** It moves from `styles/` to `themes/ore.css` and becomes
a first-class theme (`@theme Ore | dark | @extends crystalgui:crystal-dark`). Three consequences,
all favourable:

- **Its pixel-exact Ore-UI widget skin is untouched.** The sprite rules are depth-2/3 overrides at
  STYLESHEET origin, beating `look/` exactly as they beat default.css today — and once inside
  `themes/`, its 47 raw hexes are *legal* (governance rule 1 exempts theme files). Looking exactly
  like Minecraft's modern UI stack is preserved by construction; nothing about the migration asks
  it to tokenize.
- **It gains everything it never skinned.** Today an Ore-themed window shows raw default.css grey
  for any surface ore.css doesn't cover (trees, panels, the whole workbench). As a theme it
  inherits a complete table via `@extends` — optionally re-tuned to Ore's own dark palette — and
  can set `--font-ui` to the Minecraft fonts, so the *un-skinned* remainder becomes coherent with
  the skin instead of accidental.
- **It satisfies the full-key-set rule through inheritance**, which is precisely what `@extends`
  exists for — a skin theme should not have to restate forty system tokens to be legal.

`graph.css` takes the other road: it stays a **domain sheet** (stack slot 6), because the node
graph is content, not chrome — its *surfaces* get tokenized in step 5 so they follow dark/light,
while the per-port-type palette stays domain-owned (theme-overridable at depth 2), the same split
filetypes.css already makes.

The governance corollary (§4.3): a theme may never depend on a hook the engine's sheets don't
document — if depth 2 needs a part class or a token that doesn't exist, it is added to the engine's
sheets *first*. That rule is what keeps depth-2 themes from shattering on every engine release.

### 3.3 The canonical sheet stack — one owner

`UiThemeManager` is the only assembler. The stack, in order, for any themed window:

| # | Sheet | Origin | Swapped by |
|---|---|---|---|
| 1 | `StyleSheet.DEFAULT` (ua/) | USER_AGENT | never |
| 2 | `StyleSheet.BASE_LOOK` (look/) | USER_AGENT | never (values re-bound on theme change) |
| 3 | palettes: filetypes, decorations | STYLESHEET | never (values re-bound) |
| 4 | active theme's override rules | STYLESHEET | `setTheme` |
| 5 | active scheme's rules | STYLESHEET | `setScheme` |
| 6 | app/domain sheets (graph.css, a scene's sheet) | STYLESHEET | the app |

Variable resolution order (later wins): `base.css` ← parent theme ← theme ← scheme ← **user
overrides** (§3.5). Swaps mutate stack entries via `replaceRules` — the *list* never changes during
a swap, which sidesteps the "re-adding appends at highest priority" trap by construction.

### 3.4 Engine changes — three, all in `style/sheet`, all headless-testable

1. **External variable tables**: `StyleSheet.parse(source, Map<String,String> externalVars)` —
   consulted for refs the sheet doesn't define locally (locals win, so graph.css's `--graph-*`
   never collide). The registry retains each sheet's **raw source** for later re-substitution.
2. **Var-in-var**: resolve the merged definition table to fixed point before substitution (depth-cap
   ~8; on cycle, warn and leave literal — the same degrade-don't-break posture as `StyleValue`).
   A table-preparation change, not a substitution-pass change.
3. **`var(--x, fallback)`**: one regex group, one branch in `substituteVariables`.

Tests (DeclarationParser/StyleSheet level): locals-beat-external; fixed-point resolution; cycle
warns and degrades; two-arg form; re-substitution via `replaceRules` changes a live element's
computed value with sheet identity and order intact.

### 3.5 `UiThemeManager` + `ThemeRegistry`

`com.crystalgui.style.theme` — new package.

- **Registry**: built-ins pre-registered by path; externals via `ThemeRegistry.register(id)` or an
  optional `assets/{ns}/ui/themes/index.json` for zero-Java packs (classpath directory scanning is
  not portable across loaders; an index file is, and CgIO reads it today). **Parse-validation at
  registration**: a theme whose CSS fails to parse, whose rules didn't survive (the
  unknown-pseudo-class poison), or whose header is malformed is *refused with a log*, never
  half-installed — a broken downloaded theme must degrade to "not offered", not to a blank window.
- **Manager**: owns the stack (§3.3), the active theme id, the active scheme id, and a `Signal` for
  change. `setTheme(id)`: resolve inheritance chain (single parent, cycle-refused) → merge variable
  tables in §3.3 order → fixed-point resolve → re-substitute every stack sheet's raw source →
  `replaceRules` each → `invalidateAllMatches` on live engines. `setScheme(id)` same, narrower.
  On startup, applies persisted settings; on failure, falls back to `crystal-dark` and notifies.
- **User override layer** (VS Code's `workbench.colorCustomizations`): a settings-backed
  `Map<String,String>` of token overrides merged **last** into every resolution. Ships after v1
  works (§5 step 10) — the design must accommodate it from day one, which the merge-order table
  above does; the UI for it can wait.
- **Scope**: process-global in v1. Sheets are shared instances (`replaceRules` mutates them), so
  per-window divergence would need per-window copies; nothing needs it.

### 3.6 The editor scheme contract — enumerated

What `schemes/*.css` owns, and nothing else (enforced, §4.2):

- **Surface tokens**: `--editor-bg`, `--editor-fg`, `--editor-gutter-bg`, `--editor-gutter-fg`,
  `--editor-line-number`, `--editor-line-number-current`, `--editor-caret`, `--editor-current-line`,
  `--editor-selection-bg`, `--editor-selection-inactive-bg`, `--editor-indent-guide`,
  `--editor-indent-guide-active`, `--editor-whitespace`, `--editor-ruler`, `--editor-fold-bg`,
  `--editor-error-stripe-*`.
- **Highlight rules**: the syntax names (`keyword`, `type`, `function`, `string`, `number`,
  `comment`, plus any specialization — `generalName()` fallback covers unstated ones), and the
  editor-feature names (`bracket`, `search-match`, `find-match`, `search-excluded`).
- **Reserved for future buckets** (named now so they don't get invented ad hoc later): console
  colours, diff colours, VCS line-annotation colours — the categories IntelliJ's `.icls` carries
  that we don't render yet.

### 3.7 Settings + Preferences

- `appearance.theme` (default `Crystal Dark`) and `appearance.editorScheme` (default `Dark+`)
  declared in `WorkbenchSettings`, persisted by the existing codec. *(Renamed from the draft's
  `ui.theme`/`editor.colorScheme`: a setting's id encodes its Preferences page in this codebase, and
  both belong on Appearance & Behavior. Stored values are display names — `Setting.select`'s parse
  clamps an orphaned name back to the default, which is the §4.4 fallback for free.)*
- **Preferences ▸ Appearance**: *Theme* dropdown and *Editor color scheme* dropdown, populated from
  the registries — mirroring the IntelliJ reference exactly. Selecting a theme whose header names a
  `@scheme` offers (not forces) the bundled scheme — IntelliJ's behaviour.
- Harness: `--theme=` / `--scheme=` flags on the dock scene for capture comparisons.

### 3.8 Icons

- **Chrome icons** (Feather, stroked): the end state is `currentColor` inheriting the themed `color`
  — blocked on the SVG renderer gap (with another agent). Until then, the `icon(..., #hex,
  monochrome)` call sites remain but their hex values become **token-substituted** like any other
  colour, so themes recolour them anyway; when `currentColor` lands, the per-state declarations
  collapse. This is our equivalent of IntelliJ's `ColorPalette` named-slot recoloring — ours is
  just "the slots are tokens".
- **File-type icons** (IntelliJ set, filled, own palette): theme-independent by design, as in
  IntelliJ. Colour stays keyed by `.filetype-*` class in filetypes.css — tokenized, so a theme *may*
  adjust the palette, but the default follows the icon set.
- **Icon replacement** (IntelliJ's `"icons": { path → path }`) = a future *icon theme* axis; the
  FileIconTheme JSON model already points that way. Out of v1, named here so it lands as an axis and
  not a hack.

### 3.9 Theme import — porting VS Code and IntelliJ themes

Worth doing, with one architectural decision that makes it cheap instead of a tar pit: **import is a
converter that emits a native theme file, never a runtime loader of foreign formats.** A runtime
adapter makes `*.theme.json` and VS Code's JSON part of our compatibility surface forever, and has
to emulate their defaulting machinery (IntelliJ's LaF-default fallbacks, VS Code's derivation
chains) live. A converter runs once, does whatever colour math it likes *at conversion time* (the
no-colour-math rule constrains theme files, not tools), and emits an ordinary
`themes/<name>.css` that a human can read, tweak, and that governance validates like any other.

**VS Code first** — the bigger catalog and the simpler format. A theme's `colors` block maps onto
our vocabulary through a curated table (~80 keys covers all 36 system tokens several times over:
`editor.background → --surface-editor`, `sideBar.background → --surface-panel`,
`button.background → --button-bg`, `focusBorder → --focus-ring`,
`list.activeSelectionBackground → --selection-bg`, `list.hoverBackground → --hover-bg`, …), with
their documented fallback chains applied by the converter for keys the theme omits — VS Code themes
are deliberately sparse. The `tokenColors` TextMate scopes map onto our highlight names
(`keyword → keyword`, `string`, `comment`, `constant.numeric → number`,
`entity.name.type → type`, `entity.name.function → function`) and become the emitted *scheme* file
— one conversion yields both axes.

**IntelliJ second**: `.theme.json` `ui` keys map the same way (the named-`colors` palette makes
extraction cleaner); the `.icls` scheme converts from its attribute XML. Theme jars are zips;
extraction is mechanical.

**Honest fidelity statement, put in the doc so nobody is disappointed by design**: an imported
theme is *that theme's palette on CrystalGUI's shapes* — One Dark's colours on our Islands
components, not a replica of VS Code's layout. That is exactly what theme portability means
everywhere (a VS Code theme doesn't restructure the workbench either), and it's what users actually
want from "port my theme".

**Licensing is per-theme and load-bearing** (the existing THIRD-PARTY.md invariant): theme files
are copyrighted works. The popular catalog is safe — Dracula, One Dark, Solarized, GitHub's themes
are MIT — but every *shipped* import carries its notice in THIRD-PARTY.md; the converter stamps the
source, author, and licence into the emitted file's header to make forgetting hard.

Ships as a small dev-side tool (test-source utility or Gradle task — it needs no GL and no
window), after the vocabulary is proven by crystal-light: migration step 12.

---

## 4. Governance — why this doesn't become Chernobyl 2.0

### 4.1 Post-mortem: how default.css rotted

Four causes, each needing a distinct countermeasure:

| Cause | Evidence | Countermeasure |
|---|---|---|
| The contract lived in a comment; breaking it broke no build | "geometry only" died by line 100 | Contracts become **tests** (§4.2) |
| No palette to reach for → every rule minted a hex | 161 distinct colours, 5 mid-greys | Tokens + the no-hex law |
| One file was the only place to put anything | 6,241 lines | ua/ + look/ split, per-file contracts |
| No sheet-stack owner → every consumer improvised | scenes hand-assemble sheets | UiThemeManager is the only assembler |

### 4.2 Enforcement — `StyleGovernanceTest` (headless-runnable, plain text analysis)

One test class over the shipped sheet sources, added *early* in the migration (step 3) so it guards
the migration itself. Each rule is one test, each failure names file/line:

1. **No raw hex outside `themes/` and `schemes/`.** The single strongest anti-rot rule. Starts with
   a shrinking allowlist (the untokenized remainder during migration — the list *is* the migration's
   progress bar, and it reaching zero is step 5's exit criterion); after that, empty forever.
2. **ua/ parts carry no `asset()`, no `!important`** — the original contract with teeth, enforced
   per part. *(Revised with §3.2: the "no colour-valued properties in ua/" phrasing belonged to the
   abandoned function/look split; colours in the ua/ parts are legal as `var(--token, #fallback)`
   references, which rule 1 already polices.)*
3. **Every colour value is `var(--…)`** with the referenced name defined in the shipped tables —
   folded into rules 1 and 5 as implemented.
4. **base.css is derivation-only**: every component token's value is a `var(--sys…)` reference into
   the system vocabulary (tiny explicit-literal allowlist for genuine constants like `transparent`).
5. **No undefined references**: every `var()` in every shipped sheet resolves against
   base + crystal-dark. (Undefined refs only warn at runtime — this makes them a build failure for
   *shipped* files while staying lenient for user themes.)
6. **No dead tokens**: every token defined in base.css is referenced by some shipped sheet, or
   carries an explicit `/* @reserved */` marker. Kills the write-only vocabulary drift.
7. **Naming lint**: token pattern `--[a-z][a-z0-9]*(-[a-z0-9]+)*`; system tokens from the declared
   prefix set (`surface|border|divider|fg|accent|selection|hover|pressed|focus|error|warning|info|success|modified|link|radius|font`).
8. **Theme parity**: crystal-dark and crystal-light define the identical system-token key set. The
   test that keeps light mode from silently rotting the moment attention moves on.
9. **Scheme scope**: schemes/ files' rules match only editor scope (selector prefix check) and
   define only `--editor-*`/`--syntax-*` tokens.
10. **Doc sync**: the token table in `docs/CGUI_THEMING.md` regenerates from base.css and diffs
    clean — the same discipline as the StylePropertyRegistry grep idiom, automated instead of
    remembered.
11. **Contrast floor (warn-tier)**: WCAG contrast computed for declared fg/bg token pairs in shipped
    themes (`--fg`/`--surface-*` ≥ 4.5:1, `--fg-muted` ≥ 3:1). Pure math on the resolved table;
    catches "dim grey on grey" before a screenshot does. Warn, not fail — taste needs room.

Plus one *Java-side* rule folded into an existing pattern: no ARGB colour literals in
`ui/elements/**` outside documented carve-outs — grep-shaped test, same as the import guard.
**Audited 2026-08-10 (step 4).** One offender found and fixed: `NodeWireLayer.SELECTED_WIRE_COLOR`
was `0xFF44C0FF` hardcoded — now read from the cascade (`selection-color` on the layer's
`.__wire-layer__` rule, routing through `--graph-selection-ring`), because theming broke its
documented rationale: a theme moving the node's ring token would have left the wire announcing
selection in a different colour than the ring. The surviving carve-outs, each a non-theming use:
`UIText`'s `0xFFFFFFFF` identity tint (paint mechanics), `ColorSelector`'s channel bit-math and
white default (the value being *edited*, not chrome), `ColorControl.DEFAULT_COLOR` (data default
for a colour-typed setting).

### 4.3 Process rules (documented in `docs/CGUI_THEMING.md`, mirrored into AGENTS.md invariants)

- **Adding a widget** = adding its component tokens to base.css (derivations), its function rules to
  ua/, its look rules to look/ — in the same commit, with the doc regenerated. The governance tests
  make forgetting any of the three a failure, not a review comment.
- **A new colour decision** = a new *system* token only if no existing role fits (the histogram
  says ~40 roles cover 161 colours; pressure to add roll #41 gets one review question: "which
  existing surface/fg/accent is this, really?").
- **Themes never gain load-bearing selectors**: if a theme needs a hook the look/ sheet doesn't
  expose, the hook (class, token) is added to the engine's sheets first — a theme reaching into
  `__internal__` structure it doesn't own is how theme-breaks-on-every-release starts.
- **The vocabulary doc is generated, never hand-edited** (§4.2.10 enforces).
- **The token vocabulary is a public API the moment the first external theme exists** — a
  downloadable-themes story makes every token name a compatibility surface, and a renamed token
  fails *silently* in third-party themes (undefined ref → warn → literal → degrade). Policy:
  **append-mostly**. A rename ships as an alias — `UiThemeManager` keeps a deprecated-alias table,
  resolves old names during table merge, warns once per session naming the replacement — and the
  old name is removed only after a full release cycle on the alias. Removing a token outright
  follows the same cycle. The alias table lives beside base.css and is covered by the doc-sync
  test, so deprecations are visible in `CGUI_THEMING.md` rather than tribal.
- **Server-driven UIs are orthogonal to themes.** A `SheetRef` sheet a server ships rides stack
  slot 6 like any app sheet; the active theme is client preference, never serialized, never sent.

### 4.4 Failure modes

| Failure | Behaviour |
|---|---|
| Theme file unparseable / rules poisoned / bad header | Refused at registration, logged, not offered in UI |
| Persisted theme id no longer exists | Fall back to crystal-dark, notify once |
| Undefined `var()` in a *user* theme | Existing behaviour: warn + leave literal → declaration degrades, cascade survives |
| Cycle in var-in-var or `@extends` | Warn, break the cycle deterministically (drop the back-edge), continue |
| Theme swap mid-frame | Swaps run outside `calculateStyle` (same rule as every sheet mutation); manager asserts it |

---

## 5. Migration — ordered, each step shippable, tests green throughout

Strategy: **govern early, tokenize in place, then relocate.** Tokenized and untokenized rules
coexist freely, so step 5 can land section-by-section.

| # | Step | Size | Risk |
|---|---|---|---|
| 1 | Engine: external var tables + var-in-var + `var(…, fallback)` (§3.4). Pure `style/sheet`, headless tests. | S | Low |
| 2 | `UiThemeManager` + `ThemeRegistry` + swap over `replaceRules` (§3.5). Tests: live swap changes computed colour, order stable, ore.css unaffected, refusal paths. | M | Low |
| 3 | **Vocabulary draft** + `base.css` + `crystal-dark.css` + **`StyleGovernanceTest` with the full current allowlist**. The histogram (161 → ~40) drives the naming session. From here every migration commit shrinks the allowlist and cannot regress. | M | — |
| 4 | Java-side colour audit (`ui/elements/**`) — token-substitute the icon workaround hexes, carve-outs documented. | S | Low |
| 5 | **Tokenize default.css in place**: hex → `var(--token)`, no rule moves, no value changes. Verified by pixel-identical harness captures (the one sanctioned screenshot-diff — asserting *identity*, not appearance). Same pass over graph/filetypes/decorations colours. Exit: allowlist empty. | L | Med |
| 6 | **Extract the scheme**: editor `::highlight()` + surface colours → `schemes/dark-plus.css` (§3.6). First real two-axis swap end-to-end. | M | Med |
| 7 | **Settings + Preferences ▸ Appearance page** (§3.7) + harness `--theme=` flag. | M | Low |
| 8 | **Split the file** *(revised — see §3.2)*: default.css → nine `ua/` DOMAIN parts as a contiguous partition, proven declaration-identical to HEAD; `StyleSheetRegistry` grows the `DEFAULT_SHEET_PARTS` composite manifest (one sheet, one parse, one variable scope); `docs/CGUI_THEMING.md` written with its generated, machine-checked token table. `BASE_LOOK` deferred with a named trigger. Hard rule kept: **no single sheet file over ~1,500 lines** (largest shipped part: workbench.css at 1,475+header). | L | Med |
| 9 | **Ledger paydown + `crystal-light.css`** *(done — see §9 below)*. The plan understated this: light mode is *blocked* by the fine-tune ledger, because a theme redefining only the system tokens leaves every pinned component token on its dark fallback. So step 9 is first and foremost the paydown — 279 component tokens derived into the vocabulary — and light falls out of it. Parity test on. | L | Med |
| 10 | **User override layer** — settings-backed token overrides merged last (§3.5). *(done)* | S | Low |
| 11a | **Islands structural prerequisites** (§6 header): dock `--panel-gap` gaps; rounded-chrome/rectangular-clip strategy validated in harness captures, with the FBO fallback measured before adoption. | M | Med |
| 11b | **Core-widget design pass** (§6.2) — the component language over `look/widgets.css` + tokens, verified in `cgui-gallery`. | M | — |
| 11c | **Workbench/editor polish pass** (§6.1) — now edits to ~40 values in crystal-dark.css plus targeted look/ rules, instead of spelunking 6,241 lines. | M | — |
| 12 | **Theme import converter** (§3.9) — VS Code first, IntelliJ second; emits native theme+scheme files; ship 2–3 MIT classics (One Dark, Dracula, Solarized) as the proof, notices in THIRD-PARTY.md. | M | Low |

Step ordering rationale: governance lands at 3, *before* the long march at 5, so the migration
itself runs under the tests that will guard the end state — the allowlist-as-progress-bar is what
makes a months-long tokenization impossible to half-abandon invisibly.

---

## 6. The design target — Islands, the frame, and the widgets

**Decision (recorded): `crystal-dark` chases the IntelliJ *Islands* look** — panels as rounded
islands separated by visible gutters of `--surface-base`, headers merged into their island, calm
low-contrast seams. Flat is not the flagship; it remains *expressible* — a flat theme is
`--radius-panel: 0; --panel-gap: 0` plus a greyer table, which is exactly why those two are system
tokens — and ore.css stays the sprite extreme. Islands has two **structural** prerequisites that
are engine work, not theme values:

- **Panel gaps**: the dock layout must carry `gap` sourced from `--panel-gap` (a look/ rule), with
  the base surface showing through — today's docks butt seam-to-seam.
- **Rounded clipping, measured before committed**: `resolveOverflowClip()` takes the FBO-mask path
  whenever corner radii are non-zero, so naively rounding every dock panel drags every scroll
  viewport onto FBO compositing. The strategy to validate in the harness first: round the panel
  *chrome* (background + border), keep inner scroll viewports' clip rectangular and inset by the
  radius — the corner difference is invisible at 6–8px radii, and the clip stays on the cheap
  scissor path. If captures show corner artifacts, *then* pay for FBO on the affected panels,
  knowingly, with a frame-cost measurement in `EditorFrameCostTest`'s style.

### 6.1 The workbench frame — screenshot vs. IntelliJ

Ordered by visual payoff:

1. **One dark, not four.** `#2E2E2E` panels, navy gutter, near-black editor, mid-grey chrome — no
   shared ancestry. IntelliJ's frame is 2–3 surface levels with visible lineage. Falls out of the
   palette (step 3) + tokenization (step 5). Half the total roughness on its own.
2. **The editor's blue halo.** The generic `:focus-visible { outline: 1px #0060df }` ringing the
   entire editor pane. IntelliJ marks the active editor by tab and gutter affordances, never by
   outlining the pane. Suppress on pane-sized containers; keep for atomic controls. `--focus-ring`.
3. **Gutter belongs to the editor**: `--editor-gutter-bg: var(--editor-bg)` + a `--divider`
   hairline — not a differently-coloured slab.
4. **Tab strip.** Active tab = editor surface + `--tab-active-underline` (accent, 2px, resting value
   declared so activation can transition). Quiet inactive tabs; full-colour 16px file icons on a
   dark strip are carnival noise — `--tab-inactive-icon-opacity: 0.7`, full opacity on active.
5. **Tool-window headers.** Shorter; `--fg-dim` titles; background = panel surface, not a distinct
   band; close `X` becomes a quiet hover-reveal glyph (created on demand — the gap-all trap).
6. **Scrollbars.** Chunky light rails → thin (6–8px logical) semi-transparent overlay thumbs,
   hover-to-thicken. Values, not code.
7. **Splitters/borders → islands.** Hard light seams replaced by the Islands treatment itself:
   `--panel-gap` gutters of `--surface-base` between rounded (`--radius-panel`) panels, and the
   remaining true dividers (inside a panel) as `--divider` hairlines. The §6-header structural
   prerequisites land first.
8. **Empty states.** "No problems in notes.txt" as dim centred hint (`--fg-muted`), not body text.
9. **Status bar + breadcrumbs.** Shared palette, dimmer separators, semantic tokens for the
   error/warning counts.
10. **Selection & current line.** `--editor-current-line` barely-there, IntelliJ-style; ours reads
    as a highlight.

### 6.2 The core widgets — the component language

The default widgets get the same design pass, to IntelliJ's (new UI) component language, verified
in `cgui-gallery`. Per §3.2 this is all depth-1 work: `look/widgets.css` rules + tokens — no theme
ever names these widgets. The target per widget:

- **Button**: default = `--surface-raised` fill, 1px `--border-base`, `--radius-control`; a
  *primary* variant (new `.primary` class hook) = `--accent` fill with `--accent-fg` text; hover
  lightens via `--hover-bg`, pressed via `--pressed-bg`; disabled dims text, never repaints the
  face to a new colour (the labelled-button rule).
- **Dropdown**: field-shaped, not button-shaped — recessed like TextField, chevron in `--fg-dim`.
- **TextField / SearchField**: `--surface-recessed` fill, 1px `--border-field`,
  `--radius-control`; focus swaps the border to `--accent` (the IntelliJ signature) instead of the
  generic outline ring; placeholder `--fg-muted`; selection `--selection-bg`; caret `--caret-color`
  following `--fg`.
- **Checkbox**: small rounded box (radius ~`--radius-control` − 2), `--border-strong` unchecked,
  `--accent` fill + `--accent-fg` mark when checked.
- **Switch**: pill; track `--surface-raised` off / `--accent` on; knob `--fg-inverse`. The knob's
  CSS transition already exists — only colours change.
- **Slider**: thin track (`--surface-recessed`), filled portion `--accent`, round thumb with
  `--border-strong` ring, focus ring on the thumb only.
- **Dialog**: an island itself — `--surface-panel`, `--radius-panel`, dim scrim behind
  (`--surface-base` at alpha), title in `--fg`, buttons right-aligned with one primary.
- **Menu / ContextMenu / Popover / Dropdown popup**: `--surface-overlay`, `--radius-panel`, inner
  padding so hover rows are inset rounded bars (`--hover-bg`, radius ~4) rather than edge-to-edge
  strips — the single most recognizable IntelliJ-new-UI trait; separators `--divider`;
  accelerators `--fg-muted`; disabled rows dimmed (never hidden — the registry rule).
- **Tooltip**: `--surface-overlay`, `--radius-control`, `--fg-dim` body, accelerator suffix
  `--fg-muted`.
- **TabView (generic)**: quiet strip on the panel surface, active tab underlined `--accent` — same
  language as editor tabs, one set of `--tab-*` tokens serving both.
- **Scrollbars (everywhere, one spelling)**: the §6.1 overlay-thumb treatment is a ScrollerView
  restyle, so it lands once and serves editor, trees, panels alike.

Verification the established way: harness `cgui-gallery` (widgets) and `cgui-dock` (frame) captures
against the IntelliJ references, probes reverted after.

---

## 7. Deliberate non-goals (v1) — each with its trigger to revisit

- **Sync with OS** — needs a `CgPlatform` dark-mode query that doesn't exist. Revisit when a loader
  offers one; the setting's UI slot is designed (§3.7) so it's additive.
- **Theme marketplace** — "downloadable" = *a theme is a file*: drop a CSS in dev, ship a resource
  pack in MC. Same distribution story sprites already use. Revisit never, probably.
- **Per-window themes** — global manager, shared sheet instances. The *likely* trigger is already
  visible: in-game, mod-facing dialogs wanting Ore while the shader workbench wants Crystal Dark in
  the same process. When that lands, the cost is per-window sheet copies, contained in the manager
  — designed-for, not built.
- **Colour math in tables** — no `color-mix()`/lighten/darken functions; a theme spells every value
  (8-digit ARGB hex covers alpha, and is already parsed). VS Code's derivation functions exist to
  serve 800 tokens; at our ~40-system-token scale, explicit values are simpler than a mini-language.
  Revisit only if theme authors demonstrably drown.
- **High-contrast theme** — after light proves the vocabulary (it will stress it differently:
  borders carry meaning contrast can't delegate to surfaces).
- **Animated theme transitions** — swap is a hard cut; the transition engine would tween every
  colour but the invalidation storm isn't worth it.
- **W3C Design Tokens JSON interop** — the industry token-file format. Our authoring format stays
  CSS (the parser exists; themes need real override rules, which token-JSON can't carry). A
  JSON→CSS importer is a contained future utility if a designer-tool handoff ever wants it.
- **Icon themes** (icon-set replacement) — named as an axis (§3.8), not built.

---

## 8. Traps this plan must not walk into (all previously paid for)

- **`background:` vs `background-color:`** — tokenization must never "normalize" one into the
  other: `background:` is the drawable, `background-color:` a multiplicative tint whose identity is
  white. Rewriting a fill into a tint silently darkens every sprite theme (happened once).
- **Re-adding a sheet appends at highest priority** — swaps go through `replaceRules` in place,
  never remove+add; the stack list never changes during a swap (§3.3, by construction).
- **`StyleSheet.DEFAULT` is headless-unloadable** (class-init reads default.css via CgIO) — theme
  classes must stay unreachable from `headlessTest`; theme *setting values* are plain strings so the
  settings layer stays clean.
- **Unknown pseudo-classes poison the whole sheet** — why registration parse-validates and refuses
  (§3.5); the `:focus-within` incident, promoted from bug to policy.
- **Transitions into view need resting values in the sheet** — tab underline, hover-reveal buttons.
- **`:disabled`/`:hover` tie; later rule wins** — steps 5/8 preserve rule order through every move;
  a mechanical splitter that reorders ships the lit-up-disabled-button bug again.
- **`min-height` beats `height` cross-specificity** — polish work clears floors, not just sizes.
- **A hidden child still counts for `gap-all`** — on-demand creation for empty-state/hover elements.
- **`font-size` doesn't actually inherit past the `* { font-size: 10 }` candidate** — any typography
  tokens (`--font-size-ui`) must land on the text-bearing element, not a wrapper.
- **Non-zero corner radii drag clipping onto the FBO-mask path** (`resolveOverflowClip`) — the
  Islands look must round panel *chrome* while keeping scroll-viewport clips rectangular (§6
  header), or every dock panel silently becomes an FBO composite. Any exception is measured first.
- **Tests assert the spine, not the pixels** — swap changes a computed value; locals beat external;
  refusal works; parity holds. Never "the button is `#3D3F41`". The one sanctioned pixel comparison
  is step 5's before/after identity check.

---

## 9. Sources

- JetBrains: [Customizing themes — `*.theme.json` structure, `colors`, `ui`, wildcards, icon
  `ColorPalette`](https://plugins.jetbrains.com/docs/intellij/themes-customize.html)
- JetBrains: [Theme extras — bundled editor schemes (`editorScheme`),
  scheme contents](https://plugins.jetbrains.com/docs/intellij/themes-extras.html)
- VS Code: [Theme Color reference — token taxonomy, defaults/fallbacks,
  `workbench.colorCustomizations`](https://code.visualstudio.com/api/references/theme-color)
- Material 3 token tiering (reference/system/component): [design-tokens
  guides](https://www.themasterly.com/blog/design-tokens),
  [theming architecture](https://sujeet.pro/articles/design-tokens-and-theming)

---

## 9a. What the light theme found (step 9's real output)

The acceptance test worked exactly as intended — a second theme is a machine for finding rules that
bypassed the vocabulary. What it turned up, and what was done:

**Fixed here:**

- **The editor declared neither `color` nor `background`.** Its text was the `color` property's
  *initial* value (white) inherited from nowhere in particular, and its surface was whatever pane
  sat behind it. Invisible in a dark UI, fatal in a light one — white text on a white pane. Both
  are now `--editor-fg` / `--editor-bg`, scheme-owned (a document's ink and paper belong to the
  colour scheme, IntelliJ's split), with fallbacks reproducing the old accident exactly so the
  unthemed editor is unchanged.
- **`light-plus.css`** — a light UI theme needs a light *scheme* or the document stays dark; VS
  Code's Light+ values, key-for-key with `dark-plus` and parity-tested.
- **File-type icons follow the theme's kind.** `FileIconTheme.Variant` existed with a javadoc
  saying it would become a property of the active theme "when editor themes land". They landed;
  `UiThemeManager` drives it, so a light theme gets JetBrains' light drawings. These are *drawings*,
  not tinted glyphs — the one thing a theme changes that no token can express.

**Known, left for the polish pass (§6):**

- **Brand palettes are washed out on light.** The file-type text colours (`--filetype-*`) are
  theme-independent by design — Java's orange is Java's orange — but several were picked against a
  dark background and read faintly on a light one. The honest fix is a light-mode palette override
  in `crystal-light`, which is a design decision per language, not a mechanical one.
- **Some chrome still reads low-contrast on light** (a panel header title, the Problems strip).
  Each is a specific rule to find, and the light theme is what makes them findable — which is the
  whole point of shipping it now rather than after the polish.

## 10. Where this leaves us

After step 7 the IntelliJ screenshot is reproducible in our Preferences: Theme and Editor color
scheme dropdowns, independently swappable, live, persisted. After step 9 a theme is provably a
~40-line file and light/dark parity is machine-checked. After step 11 the dock screenshot stops
being embarrassing. And after step 3, **no commit can add a hex to a sheet without a test naming
it** — which is the one-sentence answer to "how does this not become Chernobyl 2.0": the contract
that used to be a comment is now a build failure.
