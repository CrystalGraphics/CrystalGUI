# plan_syntax.md — Syntax highlighting: the Islands scheme, and four more languages

**Status**: live plan. Companion to `plan_styling.md` (which owns the *chrome*'s colour) and `plan.md`.
**Goal**: an editor whose highlighting matches IntelliJ's **Islands Dark**, across `java`, `glsl`,
`css`, `js` and `html`.

---

## 1. Where we actually are

Not a green field. Most of the machinery exists and is already modelled on Zed; what is missing is
narrower and more specific than "add syntax highlighting".

| Piece | State |
|---|---|
| `core/text/syntax/SyntaxToken` | ✅ `(start, end, name)`, capture-name strings, `generalName()` dotted fallback |
| `core/text/syntax/SyntaxTokenizer` | ✅ SPI. **Range-scoped** query + separate `edited()` announcement |
| `core/text/syntax/Language` | ✅ comments + bracket pairs. `PLAIN`, `JAVA`, `GLSL` |
| `core/text/syntax/LanguageRegistry` | ✅ extension/name/glob → entry, longest-match |
| `core/text/syntax/KeywordTokenizer` | ✅ lexer fallback: `comment` `string` `number` `keyword` `type` `function` |
| `syntax-treesitter/` | ✅ real module, `TreeSitterTokenizer` + `Queries`, jars checked in at `lib/tree-sitter/` |
| Grammars | ⚠️ **java only** (`tree-sitter-java-0.23.5.jar`) |
| `highlights.scm` | ⚠️ **java only**, 16 capture names |
| Schemes | ⚠️ `dark-plus.css` / `light-plus.css` — VS Code's palette, **7** syntax colours |
| Paint path | ✅ captures → `::highlight(name)` → `CgStyleSpan` |

### 1.1 The three real gaps

**A. The scheme is narrower than the grammar.** The Java query emits 16 capture names; the scheme
defines 7 `--syntax-*` colours. The nine without one are not errors — `generalName()` folds
`function.builtin` onto `function` — but `@operator`, `@attribute`, `@variable` and
`@constant` have no general form that is coloured either, so they render as plain body text.
This is the *"resolves but paints nothing"* class that `plan_styling.md` §8 keeps re-finding, and it
is invisible: an unstyled capture looks exactly like a capture the grammar never produced.

**B. The palette is Dark+, not Islands.** `dark-plus.css` says so in its own header. Its colours are
VS Code's and are what a VS Code user recognises; they are *not* what sits beside an IntelliJ frame,
which is the frame `crystal-dark.css` now draws.

**C. One grammar.** `css`, `js`, `html` have none. `glsl` has a *keyword lexer* only — the
`LanguageRegistry` maps eight shader extensions onto `KeywordTokenizer.glsl()`, which is a fixed word
list and cannot tell a swizzle from a field or a builtin from a user function.

---

## 2. What the three references actually do

Worth writing down because the codebase has already chosen one of them, and the choice constrains
everything below.

### 2.1 IntelliJ — lex, then *resolve*

Two layers, and the second is the one people notice:

1. **Lexer highlighting** — a JFlex lexer produces token types; a `SyntaxHighlighter` maps each to a
   `TextAttributesKey`. Fast, per-line, no parse.
2. **Annotators / semantic highlighting** — run over the resolved PSI, and colour things a grammar
   *cannot* know: an instance field vs a local vs a parameter, a method's declaration vs its call, an
   unused symbol, a deprecated one.

Its colour scheme is a graph of `TextAttributesKey`s with **explicit fallback keys**:
`JAVA_KEYWORD` falls back to `DEFAULT_KEYWORD`, so a scheme that never heard of Java still colours
Java keywords. That is the same idea as our `generalName()` — theirs is a declared key graph, ours is
string prefixes.

Schemes are separate files from the UI theme (`.icls`), selectable independently. **We already
copied this**: `plan_styling.md` §3.6's two-axis split exists precisely because IntelliJ has it.

### 2.2 VS Code — TextMate scopes, plus semantic tokens

- **TextMate grammars** (regex, `.tmLanguage.json`) produce dotted **scopes**:
  `keyword.control.flow.java`. A theme's `tokenColors` match by scope *prefix*, most-specific-wins.
- **Semantic tokens** arrive from a language server and are layered on top via
  `semanticTokenColors`. This is what distinguishes `parameter` from `variable` — the grammar cannot.
- **Embedded languages** are handled by grammar injection: the HTML grammar declares that the
  contents of `<style>` are `source.css`.

The dotted-scope-with-prefix-fallback idea is where our `generalName()` comes from, even though our
tokens come from tree-sitter rather than TextMate.

### 2.3 Zed — tree-sitter captures, straight through

- `highlights.scm` per grammar; capture names (`@keyword`, `@function.method`) are the vocabulary.
- The theme's `syntax` map is keyed by exactly those names, with dotted fallback.
- `injections.scm` handles embedded languages: a capture marks a node as being another language, and
  the injected grammar is parsed into the same tree.
- Highlight queries are **range-scoped and capped** (~16KB per query) so cost tracks the viewport.

### 2.4 Nobody in §2.1–2.2 uses tree-sitter, and we do

Worth stating plainly, because it is the first question anyone reading this will ask and the answer
is not "we picked the same thing as our references".

| | Grammar | Resolve layer |
|---|---|---|
| **IntelliJ** | hand-written / JFlex lexer per language | PSI + annotators (full front end) |
| **VS Code** | TextMate `.tmLanguage.json` (regex + rule stack, via Oniguruma-WASM) | LSP semantic tokens |
| **Monaco** | **Monarch** — a simpler state machine; TextMate is opt-in, not the default | — |
| **Zed** | tree-sitter | LSP semantic tokens |
| **us** | tree-sitter | none, and none planned (§7) |

**Why tree-sitter anyway**, given the two editors we copy visually use neither:

- **TextMate is not really available.** Its value is the published grammars, and those need
  Oniguruma — a native dependency *and* a specific regex dialect — to produce a worse structure
  (a line-oriented token stream, no tree). We would take on a native library either way, so we may
  as well take the one that yields a parse tree.
- **IntelliJ's approach is not a choice, it is a decade of per-language work.** A JFlex lexer plus a
  PSI parser plus annotators, per language, is the whole reason its highlighting is better than
  everyone's. It is not a thing to start.
- Tree-sitter gives an incremental, error-tolerant real tree, with grammars already written and
  shared across Zed/Neovim/Helix/GitHub. It is the only one of the three a project this size can
  staff.

What we give up is precisely §3.3's ❌ rows. Both VS Code and Zed hit the same wall and answer it the
same way — semantic tokens over LSP, layered on the grammar's output. **That is the upgrade path if
it is ever wanted, and it does not change the shape of anything in this plan**: semantic tokens would
arrive as more `SyntaxToken`s with the same capture-name vocabulary.

### 2.5 Which one we are on

**Zed's, already, and deliberately.** `SyntaxToken`'s javadoc names tree-sitter's convention outright;
`SyntaxTokenizer`'s javadoc cites Zed's range-scoping and its edit/reparse split, 16KB cap included.
The `syntax-treesitter` module exists and works for Java.

**This plan does not re-litigate that** (§2.4 is the standing answer to "why not TextMate"). It fills in the palette and the grammars, and answers the
three questions the model does not answer by itself (§4).

The one thing to take from IntelliJ *besides* colours is the honest boundary: **everything IntelliJ
colours by resolve, we cannot colour at all.** No PSI, no language server, no symbol table. That is
not a gap to close in this plan — it is a line to write down so the scheme is designed against what
a grammar can actually produce (§3.3).

---

## 3. The vocabulary

### 3.1 Capture names are the contract

The `@capture` names in `highlights.scm` are the interface between a grammar and a scheme. They must
be **shared across grammars**: `@keyword` in `css/highlights.scm` and in `glsl/highlights.scm` have to
mean the same thing, or a scheme has to know every language, which is the failure `generalName()`
exists to prevent.

Standardise on the set the existing Java query already uses, since it is Zed/nvim-treesitter's
conventional set and every published `highlights.scm` is written against it:

```
comment  comment.doc
string   string.escape  string.special
number   boolean  constant  constant.builtin
keyword  keyword.control  keyword.operator
function function.builtin  function.method
type     type.builtin
variable variable.builtin  variable.parameter  variable.member
property attribute  tag
operator punctuation  punctuation.bracket  punctuation.delimiter
```

**Every name here needs a colour or a documented fallback**, which is gap A. See §6 governance.

### 3.2 Scheme tokens

`--syntax-<capture-with-dots-as-dashes>`, so `@function.builtin` → `--syntax-function-builtin`.
Mechanical, and it means a scheme author can read a `highlights.scm` and know the token name.

The `StyleGovernanceTest` scheme-scope rule (`--editor-`, `--syntax-`, `--find-match-`,
`--search-excluded-`) already permits this with no change.

### 3.3 What a grammar cannot give us

Write this into the scheme's header, because it is the difference people will notice against IntelliJ
and it will otherwise be reported as a bug repeatedly:

| IntelliJ colours | We can | Why |
|---|---|---|
| keyword, comment, string, number | ✅ | grammar |
| method *declaration* vs *call* | ✅ | distinct grammar nodes |
| builtin vs user function | ⚠️ partly | only where the query lists builtins by name |
| **instance field vs local vs parameter** | ❌ | needs resolve; the grammar sees an identifier |
| **unused / deprecated symbol** | ❌ | needs a compiler |
| **the same identifier's declaration and its uses agreeing** | ❌ | needs a symbol table |

A scheme designed as "IntelliJ's palette minus the resolve-driven half" is achievable and honest. A
scheme that *looks* like it is trying and misses those four is the one that reads as broken.

---

## 4. The three decisions — DECIDED

### 4.1 Bold and italic — the blocking one

**Islands Dark italicises comments and bolds some keywords. We currently cannot.**

`HighlightStyle.ALLOWED` is `{color, background-color, text-decoration-line}`. That is not an
oversight: CSS Pseudo-Elements 4 restricts highlight pseudo-elements to properties that cannot reflow
the highlighted text, and the class enforces it deliberately (`NOT_YET_PAINTABLE` exists so a dropped
property is never silent).

The backend is not the blocker — `CgStyleSpan` carries `bold`/`italic`, and `font-weight`/`font-style`
now exist as real properties (`34353fd`).

**Options:**

1. **Leave it.** Islands' comments render upright. Simplest, and visibly not-IntelliJ.
2. **Allow `font-weight`/`font-style` on `::highlight()` generally.** Violates the spec's reason:
   synthetic bold is wider, so a highlight would reflow wrapped text — exactly what the restriction
   protects.
3. **Allow it only where reflow is harmless, and say so.** The editor lays out one row per line with
   `white-space: nowrap`; a wider row changes `getScrollWidth` and nothing else. A wrapping `UIText`
   is where the restriction bites.

**DECIDED: (3)**, as a named carve-out — e.g. `HighlightStyle.ALLOWED_IN_EDITOR` — with the
spec's reasoning and the reflow argument written at the definition. Decide this *first*: it changes
what the scheme can express, and a palette authored against option 1 has to be redone under 3.

> Note the interaction with `34353fd`: the editor draws via `Draw.text(String)` with a bare family, so
> it does **not** currently support element-level `font-weight` either. Per-range weight in the editor
> is the consumer that would justify moving `TextEditor` onto styled paragraphs — the work explicitly
> deferred in that commit. **This plan is that consumer.**

### 4.2 Injections — HTML forces the issue

HTML is not one language. `<style>` is CSS and `<script>` is JS, and a highlighter that colours them
as HTML text is worse than one that leaves them plain.

- Zed: `injections.scm`, injected grammar parsed into the same tree.
- VS Code: grammar injection by scope selector.
- IntelliJ: `PsiLanguageInjectionHost`, a whole subsystem.

`SyntaxTokenizer.tokenize` returns a flat `List<SyntaxToken>` over the *whole document's* offsets,
which is already the right shape for this — an injected range simply contributes tokens in the same
list. The work is in `TreeSitterTokenizer`: parse the host, run `injections.scm`, parse each injected
range with its own grammar, and merge. No `core/` API change.

**DECIDED: `css`/`js` first; `html` ships only once injection works.** An HTML file is mostly *not*
markup in practice, so the interim version — tags coloured, `<style>` and `<script>` bodies grey —
would ship the impression that the feature is broken rather than incomplete. Injection is a
`TreeSitterTokenizer` change with no `core/` API impact, so nothing about this ordering is structural;
it is only about what is worth putting on screen.

### 4.3 Grammar sourcing

**DECIDED, and this is the expensive one — it applies to all four languages, not just GLSL.**

`lib/tree-sitter/README.md` is the authority and it is unambiguous: these jars are vendored *because*
nothing usable is on Maven Central. The official binding (`io.github.tree-sitter:jtreesitter`) needs
**JDK 23+** and the Foreign Function & Memory API; this project compiles to **Java 8 bytecode**, so
that is not a version bump away, it is impossible. The vendored jars come from a **fork of
`tree-sitter-ng`**, which is JNI-based and compiles to Java 8.

So there is no "just add a dependency" path for `css`, `javascript`, `html` **or** `glsl`. Each needs:

1. a subproject added to the `tree-sitter-ng` fork,
2. its native cross-compiled with **Zig** for the five platforms the existing jars cover (x86_64
   Windows/Linux/macOS, aarch64 Linux/macOS),
3. the jar vendored into `lib/tree-sitter/` with its licence,
4. the grammar author's own `highlights.scm` vendored separately into
   `syntax-treesitter/src/main/resources/assets/crystalgui/syntax/<lang>/` — **not** a hand-written
   approximation, because the capture names in it are what a theme styles and an approximation
   produces highlighting subtly unlike every other editor's.

This is already a tracked task: `lib/tree-sitter/README.md` names `tree-sitter-glsl` as *"the
outstanding one, tracked in `CrystalGUI_P6_TODO.md` under 6.1.7 step 8"*.

**Upstream sources.** For GLSL the grammar is
`github.com/tree-sitter-grammars/tree-sitter-glsl` — the C-generated parser, which is the input to
step 2. The `lib.rs/crates/tree-sitter-glsl` crate is the **Rust** packaging of the same grammar and
is not usable from the JVM; it is worth naming only so nobody reaches for it twice.

**The Zig cross-compile is confirmed reproducible locally** (decided 2026-08-11), which is what turns
this from a risk into a cost. All four languages get real grammars; there is no fallback branch.

**Consequence for the migration.** Steps 3–5 each carry a grammar-jar build, and that is the bulk of
their cost — not the `highlights.scm`, not the registry entry. Sequence them so one jar is built and
proven end-to-end (`css` is the smallest grammar and has no injections) before committing to four:
the recipe being reproducible is not the same as it being *written down*, and the second jar should
cost an hour rather than a day.

> The abandoned alternative, recorded so it is not re-proposed: keep `KeywordTokenizer.glsl()` and
> widen its word lists. GLSL's builtins are a genuinely closed set, so this would have worked for
> GLSL alone — and for nothing else, since CSS, JS and HTML all have nesting a word list cannot see.
> Moot now, and it was never the general answer.

---

## 5. Migration

Ordered so each step is separately verifiable, and so the blocking decision lands first.

| # | Step | Done when |
|---|---|---|
| 0 | **The §4.1 carve-out**, then move `TextEditor` onto styled paragraphs — including its measurement caches, or the caret drifts | a highlight can set `font-style: italic` in the editor and nowhere else |
| 1 | **Widen the vocabulary**: give every capture name in §3.1 a `--syntax-*` token in both schemes, plus the governance test in §6.1 | no capture in any shipped `highlights.scm` lacks a colour or a coloured general form |
| 2 | **Author Islands Dark** as `schemes/islands-dark.css` (+ light pair), mapped from IntelliJ's scheme against §3.3's honest subset | side-by-side with IntelliJ on the same Java file |
| 3 | **`css`** — first grammar jar through the Zig build end-to-end (§4.3), then `javascript` | a `.css` and a `.js` file highlight, and the jar recipe is proven |
| 4 | **`glsl`** — jar via the same route (§4.3) | `.glsl`/`.vert`/`.frag`/`.shader` distinguish builtins from user symbols, swizzles from fields |
| 5 | **`html` + injections** in `TreeSitterTokenizer` | `<style>`/`<script>` bodies highlight as CSS/JS |
| 6 | **Keep `KeywordTokenizer`**, and say why in its javadoc | it is named as the no-natives path, not left looking like dead code |

Steps 3–5 are independent of each other once 1–2 land.

---

## 6. Governance

The styling work's lesson: a rule that can be broken silently will be. Two tests, both cheap.

### 6.1 Every capture has a colour

Scan every shipped `highlights.scm` for `@capture` names; assert each has a `--syntax-*` token in
every shipped scheme, **or** a dotted general form that does. Catches gap A permanently, and catches a
new grammar introducing a name no scheme knows — which is otherwise invisible.

### 6.2 Schemes stay in scope, and paired

Already exists (`theSchemeAndThemeAxesStayApart`, `eachThemeAndSchemePairDefinesTheSameKeys`). New
schemes must be added to the pair check, or a light scheme silently drifts — the exact failure
`--editorfind-bg` produced in `cd21708`.

> Consider extending `nothingIsDrawnInTheColourOfWhatItSitsOn` (`d46a95a`) to `--syntax-*` against
> `--editor-bg`. A syntax colour equal to the paper is the same bug wearing a different hat, and this
> is the one place in the app where a dozen new colours are about to be authored at once.

---

## 7. Non-goals

- **Anything needing resolve** — §3.3's ❌ rows. No PSI, no LSP, no symbol table.
- **A language server.** Out of scope for this plan and probably for this project.
- **Bracket-pair colourisation, error squiggles, inlay hints.** Separate features that happen to live
  near highlighting.
- **User-authored grammars at runtime.** Grammars are checked-in jars; a resource-pack grammar would
  mean loading native code from a pack.
- **Re-litigating tree-sitter vs a lexer** (§2.4).

> **`KeywordTokenizer` is not a non-goal and does not get retired.** Once every shipped language has a
> grammar it looks like dead code, and deleting it would break the case it exists for: `core/` must
> load with no natives at all — a dedicated server builds and edits documents without `syntax-treesitter`
> on the classpath. It is also what an unregistered extension falls back to. Step 6 is to write that
> down where someone tidying up will read it.

---

## 8. Traps

Recorded up front because most are already documented elsewhere in this repo and would otherwise be
rediscovered.

1. **An unstyled capture is invisible, not obviously wrong.** It renders as body text — the same as a
   capture that was never produced. §6.1 is the only thing that will catch it.
2. **`::highlight()` cannot set bold/italic today** and the refusal is deliberate. §4.1.
3. **A highlight re-shapes here; on the web it overlays.** `UIText`'s javadoc: a span boundary is a
   shaping-run boundary, so highlighting can shift measured width by a fraction of a pixel. In a code
   editor with per-row layout this is harmless; do not "fix" it by routing plain rows through spans.
4. **A `::highlight()` band must be cleared on the no-styles path.** `AGENTS.md` records a recycled
   row banding the wrong text entirely. Every path out of `toCgSpans` must clear `highlightPerChar`.
5. **Tokens may extend outside the queried range**, by contract — a block comment spanning the
   viewport starts off-screen. Clipping them reports it as starting at the top of the screen.
6. **`edited()` is separate from `tokenize()` on purpose.** Applying the edit is cheap and
   synchronous; reparsing is expensive and may lag. Collapsing them gives that up.
7. **Native memory.** `TreeSitterTokenizer` holds a parser and a tree. Adding four grammars multiplies
   whatever the current lifecycle does — check it disposes per document, not per editor.
8. **There is no "just add a dependency" for a grammar** (§4.3). The official binding needs JDK 23+
   and FFM; this project compiles to Java 8 bytecode, so it is not a version bump away. Every grammar
   is a jar built from the `tree-sitter-ng` fork and vendored — budget for that, not for a Gradle line.
9. **Vendor the grammar author's `highlights.scm`, never write one.** The capture names in it are what
   a theme styles, so an approximation highlights subtly unlike every other editor and the difference
   surfaces as "our Java looks wrong" long after anyone remembers why.
10. **`core/` may not gain a native dependency.** The module split exists because a dedicated server
   loads `core/` with no GL and no `.so`. Every grammar goes in `syntax-treesitter/`.
11. **Scheme ≠ theme.** A syntax colour is the scheme's; the gutter, the caret and the current-line
   band are also the scheme's; everything else is the theme's. The split is machine-checked.

---

## 9. Open questions

1. ~~§4.1 bold/italic~~, ~~§4.2 HTML ordering~~, ~~§4.3 grammar sourcing~~ — **all decided**, see §4.
2. ~~Is the Zig cross-compile reproducible here?~~ **Yes** — confirmed 2026-08-11. Step 3 is now about
   *documenting* the recipe, not discovering whether there is one.
3. Does Islands Dark ship a light counterpart we should match, or is the light scheme ours to author?
4. Should `islands-dark` become the *default* scheme, replacing `dark-plus` as `crystal-dark`'s
   `@editor-scheme`, or ship alongside it?
