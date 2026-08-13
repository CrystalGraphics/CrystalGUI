# M11 — Resolver affordances and the query-family tail

Detail for the M11 row in `plan_syntax.md` §20. Six deliverables, two of them user-facing
(documentation popup, go-to-definition) and four of them query-family work that has been deferred
since M3 with a stated reason.

## Status

| § | Item | State |
|---|---|---|
| 24.7 | Navigation primitive | **done** — `Workbench.openAndReveal` + `TextEditor.revealAt` |
| 24.8 | Go To Line | **done** — `Mod+G` |
| 24.9 | Clicking a problem | **done** — reveal is the continuation of the open |
| 24.2 | Go-to-definition | **done** — `Mod+B`, Ctrl+Click, 6 tests |
| 24.1 | Quick Documentation | **done** — `Mod+Q` + hover, `DocumentationPopup`, engine-rendered `Signature`, 14 + 8 tests |

### The declaration seam — decided against both references, deliberately

`SymbolInfo.signature()` carries a `Signature(String text, List<SyntaxToken> tokens)`, populated by
`resolveAt` and never by `membersOf`. Both reference IDEs ship a *string* and re-highlight it, and
neither reason applies here:

- **LSP** has a process boundary and a JSON wire shared by a hundred servers in a dozen languages, so
  markdown-in-a-string is the lowest common denominator. Nothing here crosses a process.
- **IntelliJ**'s render surface *is* an HTML component. Ours is `UIText`, which takes ranges natively —
  a string would have to be turned back into ranges before anything could be drawn.

And IntelliJ's definition line is **not** lexed: the `↗` arrows beside `@Nullable` are navigable links to
those types, which no lexer can produce and only a binding can. `HtmlSyntaxInfoUtil` is for code samples
inside a doc *body*.

So a string would mean the engine flattens structure it already has and the widget re-derives it with a
lexer worse than the parser colouring the same code two pixels away — lossy, visibly inferior, and no
path to links ever. Reusing `SyntaxToken` means the popup's rendering is the same operation as
`TextEditor.ensureRowSyntax`, and the forty `::highlight()` rules moved from `texteditor text` to a
`.__syntax__` capability class so they are no longer editor-private.

`CgMarkupParser` is the right tool for the javadoc **body** and the wrong one for the signature: it
produces a `CgStyledText` directly, entering the pipeline *downstream of the cascade*, so its colours are
baked and a scheme switch would not reach them.
| 24.6 | GLSL diagnostics | **blocked, and the plan's premise below is wrong** |
| 24.5 | `locals.scm` | not started — vendoring question below |
| 24.3 | `folds.scm` | not started — vendoring question below |
| 24.4 | `indents.scm` | not started — vendoring question below |

### Follow-up: half of `JavaSignatures` is waiting to be deleted

`JavaSignatures` renders a declaration two ways, and they are not peers:

| Path | When | Who chooses the layout |
|---|---|---|
| **Quoted** — `quotedDeclaration` | the symbol is declared in the file being analysed | the author |
| **Assembled** — `render` | anything else, i.e. the classpath | us |

The assembled path exists **only because a classpath symbol has no source to quote**. Everything
layout-shaped in it — `MAX_SIGNATURE_LINE`, the break before a long `=`, one parameter per line, the
hanging indent under `implements`, `spaces()` — is there to invent a wrapping that the quoted path gets
for free. Roughly 250 lines whose entire justification is the absence of a file.

**So it becomes deletable the moment quoting can reach classpath sources**, which is not far-fetched:
JDT resolves against jars that frequently ship a `-sources.jar` beside them, and IntelliJ's own popup
shows parameter names (`println(String x)`) precisely because it has those attached. If we ever attach
them — for parameter names, which is already a known gap — quoting comes with it and this whole path,
plus the two `broken` flags threaded through it, goes.

Worth doing deliberately rather than discovering: until then the assembled path is load-bearing for every
JDK symbol anybody hovers, and the break rules are the only thing keeping those readable.

### Correction: 24.6 is not the cheapest item, it is the most expensive

The claim below — *"`CgShaderParseException` names the offending line"* — **is false.** It names the
**file** and nothing else: `"[" + resourcePath + "] 'void fragment(' function not found"`. There is no
line field on the exception and none of its 52 throw sites carries one, so an adapter written against it
today can only report every problem at line 1, which points a squiggle at innocent text. That is worse
than no diagnostic, and it is the exact failure mode §24.6 was written to avoid.

Two further corrections to the same paragraph:

- **The parser throws on the first error**, so there is no multi-error collection to adapt.
  `--mode=shader-compile-audit` collects failures across *files*, not within one — the two were conflated.
- **Real GLSL errors need a driver round-trip**, so they arrive on the GL thread and are not available to
  a background compile at all.

So 24.6 needs a **CrystalGraphics change first**: a position on `CgShaderParseException` (and a
collecting mode on the parser). That is a genuine new backend capability rather than a reimplementation,
so it is allowed — but it is a decision to take deliberately, not a side effect of this milestone.

### The three query items share one blocking question

`locals.scm`, `folds.scm` and `indents.scm` are all **vendored files**, and `Queries.java` records the
standing rule: query files come from *the grammar author's own* `queries/` directory, "which is the whole
point". That rule answers 24.5 and does **not** answer 24.3 or 24.4, because upstream grammars generally
ship neither — those live in editor runtime repos. So the fork §24.4 names is real and gates all three:

| Source | Licence | Cost |
|---|---|---|
| `nvim-treesitter` | Apache-2.0 | licence + NOTICE + statement of modifications — terms the IntelliJ icons already ship under |
| Helix runtime | MPL-2.0 | file-level copyleft; the `.scm` stays MPL and carries notice |
| Write our own | — | six files per family, ours, no notice |

Whichever is chosen goes in `THIRD-PARTY.md` in the same commit.

---

## 24.1 The documentation popup

**It is called the Quick Documentation popup.** IntelliJ's own wording, `View | Quick Documentation`,
`Ctrl+Q`. Not "hover", not "tooltip" — the hover trigger is a *setting on it*
(`Show on Mouse Move`, under `Settings ▸ Editor ▸ Code Editing ▸ Quick Documentation`), and pressing
`Ctrl+Q` a second time promotes the same content into the Documentation **tool window**. Naming it
after its trigger is what would make the tool-window half look like a second feature later.

### What is actually in the box

From the reference and from JetBrains' own docs, top to bottom:

| Band | Content | Source |
|---|---|---|
| Owner | `com.crystalgui.language.run.ScriptHost.Running`, with the kind icon of the **declaring type** | `SymbolInfo.container()` |
| Definition | `private final Method entryPoint` — modifiers, type, name, syntax-coloured | `modifiers()` + `type()` + `name()` (+ `parameters()`) |
| Body | rendered Javadoc | `SymbolInfo.documentation()` |
| Footer | `CrystalGUI.language.main` with a module icon, then an **edit-source** pencil and a **kebab** | `declaration()` |

The kebab is the options menu — font size, show the toolbar, `Show on Mouse Move`, and
`Download documentation` for a library with no sources attached.

**The body is empty for us today, and that is fine.** `EcjSourceAnalyzer` never populates
`documentation` — grep confirms it: the field is declared on the seam and no engine fills it.
The reference image has no Javadoc in it either; what it shows is the *definition* and the
*location*, both of which we can render from what `SymbolInfo` already carries. So the popup ships
useful on day one and gets a body when the ECJ side learns to read `Javadoc` nodes off the AST.
Order the work that way round: the widget does not block on the engine.

`SymbolInfo`'s own javadoc already names Hover as one of three consumers and lists exactly these
fields, so the seam was designed for this and needs no change.

### Substrate

**`Popover`, in `Mode.AUTO` — not `Tooltip`.** A tooltip is transient and unfocusable; this box is
scrollable, clickable, has controls in its footer, and must survive the pointer leaving the word.
`AUTO` buys light dismiss and Escape from the existing two stacks with no new machinery.

Three traps already documented in `AGENTS.md` that this walks straight into:

- **Position from `getWindowX/Y`, never `localToWorld`.** The transform chain is surface pixels with
  the root transform baked in, and is only populated during `drawSubtree` — a popup anchored before
  the caret has painted lands in the window's corner, multiplied by `uiScale`.
- **Only `AnchoredPlacement` writes `left`/`top`** on an anchored popup. Flip on the main axis, clamp
  on the cross axis.
- **`Tooltip.attach` adds a listener pair rather than replacing**, so nothing here may re-attach to
  update text. Retain and call a setter.

### Async, and the two gates

`Resolver.resolveAt(int offset, Consumer<Versioned<SymbolInfo>> answer)` is asynchronous and
version-stamped, exactly like diagnostics. Two independent discards, and missing either produces a
box that is confidently wrong rather than absent:

1. **Version** — an answer for a document that has since been edited describes text that is no longer
   there. Same gate `installDiagnostics` already applies.
2. **Identity of the request** — the pointer moves, so an answer that arrives for the *previous*
   offset must not fill a popup now anchored somewhere else. A request id compared on arrival, which
   is what `CompletionSession` already does for a superseded query.

Hover also needs a **delay** before it fires (IntelliJ's is settings-driven) and a **keyed,
superseding** job so that dragging the pointer across a line does not queue forty resolves. The
`JobScheduler` from M0 already has the lane and the keying; this is a consumer, not new machinery.

### Structure and styling

Internal children, `__double-underscore__` classes, per the widget conventions — there are no
pseudo-elements:

```
documentationpopup
  .__doc-owner__      (icon slot + UIText)
  .__doc-definition__ (UIText — syntax-coloured via ::highlight(), see below)
  .__doc-body__       (UIText, wrapped; hidden while documentation() is null)
  .__doc-footer__     (icon slot + UIText + edit-source button + kebab)
```

Register the tag in `ElementRegistry.bootstrapBuiltins()` — a widget's cascade identity is its **tag**,
never its Java supertype, and `Dropdown extends Button` laid out at zero height for exactly this
reason.

**The definition line is coloured with `::highlight()`, not with nested elements.** The engine's
Custom Highlight API takes ranges without wrapping them in Taffy nodes, which is what it exists for;
`--syntax-keyword` for the modifiers and `--syntax-type` for the type read straight from the active
scheme, so the box matches the editor it is describing without a second palette. Note the
already-paid-for trap: a `::highlight()` band must be **cleared on the no-styles path**, or a pooled
row keeps the previous occupant's band.

CSS goes in `ua/overlays.css` (geometry) with every colour as a `var()` fallback; the theme tokens
are new and must be added to `docs/CGUI_THEMING.md`'s generated table in the same commit —
`StyleGovernanceTest` fails the build otherwise.

### Commands

`editor.quickDoc`, registered in `EditorCommands.declare` and bound in `bindDefaults`. IntelliJ's
binding is `Ctrl+Q`; ours should be the same. A command rather than a keystroke because it is a
modified chord, which is where `KeymapResolver`'s own guard draws the line, and because a command is
what a menu row and the palette can both point at.

### Exit criteria

- `Ctrl+Q` on a resolvable symbol shows owner, definition and footer, positioned at the caret and
  flipped when it would leave the window.
- An answer stamped at an older document version is discarded and the popup does not open.
- An answer for a superseded request does not fill a popup anchored elsewhere.
- Escape closes it; a press outside closes it; a press *inside* does not.
- With no engine loaded, `Ctrl+Q` does nothing and throws nothing — the three-tier absence rule.

---

## 24.2 Go-to-definition

`SymbolInfo.declaration()` is a `DeclarationSite`, already populated by the ECJ side, and already
documented as null for "a member of a compiled class with no source attached — the ordinary case,
not a failure". So the command must have a well-defined nothing-to-do path from the start.

**The editor must not open documents itself.** `ProblemsPanel` already sets this precedent and states
it: it "deliberately cannot navigate", because the target is routinely a file that is not open, and
opening one is a workspace-level act. So `editor.goToDefinition` resolves and then *emits* — the
workbench opens the document and reveals the range. Same shape as `onProblemChosen`.

Bindings: `Ctrl+B`, and Ctrl+Click. The click path is the one with a trap — click-focus now walks to
the nearest focusable ancestor, so a modifier-click in the editor must be read before the press
settles focus.

The footer's pencil in 24.1 is this command with a different affordance, so build 24.2 first and let
the popup call it.

---

## 24.3 `folds.scm`

Folding today is `IndentRangeProvider` — indentation-based, which is Monaco's default too and was
deliberately not brackets. The SPI it implements is one method:

```java
FoldingRegions compute(Rope document, int tabSize);
```

so a tree-sitter provider is a second implementation and touches nothing else. That is the whole
reason the SPI exists.

**`folds.scm` is not part of tree-sitter proper.** Only `highlights.scm`, `locals.scm` and
`injections.scm` are — the capture names for those are fixed by the library. `folds.scm` and
`indents.scm` are *editor* conventions (Neovim, Helix, Pulsar), which means we are choosing a dialect
rather than implementing a spec. The fold convention is the simple one and is agreed across all three:
a single `@fold` capture on a multiline node, and the region is that node's start and end.

Two things fall out of the existing model rather than the query:

- A collapsed region keeps its **first row visible** — `hiddenRows()` starts at `startRow + 1`, since
  that row carries the arrow and is the only handle left.
- `ProjectedLines.modelAt` must not use `Arrays.binarySearch`, because a hidden row projects onto zero
  view lines and adjacent prefix-sum entries are equal.

Both already hold; a query-driven provider changes where the ranges come from and nothing about how
they are shown.

**Exit:** a Java and a GLSL fixture fold at their real block boundaries rather than at their
indentation, with the two providers selectable and the indent one still the fallback when a language
ships no `folds.scm`.

---

## 24.4 `indents.scm`

`TextEditor.insertNewlineWithIndent` currently copies the previous line's leading whitespace and adds
one level when the line ends in `{`. It already names its successor in a comment.

**The dialect decision, and it is the one real fork in M11.** The two live vocabularies are
incompatible:

| | Helix | Neovim |
|---|---|---|
| more | `@indent` | `@indent.begin` |
| less | `@outdent` | `@indent.end`, `@indent.dedent` |
| stacking | `@indent.always` / `@outdent.always` | — |
| alignment | `@align` + `@anchor` | `@indent.align` |
| whitespace-sensitive | `@extend`, `@extend.prevent-once` | `@indent.branch`, `@indent.zero`, `@indent.ignore` |

Helix's is the smaller and is written down as a specification, with clear rules — `@indent` and
`@outdent` on the same line cancel; multiple `@indent` on one line do not stack, multiple
`@indent.always` do.

**The deciding fact is provenance, and it is already settled — in a direction that makes this easier
and the licence question harder.** `lib/tree-sitter/README.md` records the rule: queries are copied
from *the grammar author's own* `queries/` directory in the grammar tarball, "which is the whole
point". We take `highlights.scm` and `injections.scm` from upstream and nothing else.

Upstream grammar repos generally **do not ship `indents.scm` or `folds.scm`** — those live in editor
runtime repos, which is why nvim-treesitter carries a standing request to upstream them. So there is
nothing to match: for these two files we are not vendoring the author's work, we are either borrowing
an editor's or writing our own.

That turns the dialect question into a **licence** question, and licences are load-bearing here:

| Source | Licence | Cost |
|---|---|---|
| `nvim-treesitter` | Apache-2.0 | licence + NOTICE + a statement of modifications — the same terms the IntelliJ file icons already ship under |
| Helix runtime | MPL-2.0 | file-level copyleft: the `.scm` stays MPL and must carry notice |
| Write our own | — | six files, ours, no notice |

**Recommendation: Neovim's dialect, files borrowed from `nvim-treesitter` under Apache-2.0.** Not
because the vocabulary is nicer — Helix's is smaller and better specified — but because it is the only
one of the three where we can take working, maintained files for all six languages under terms we
already satisfy elsewhere, and a hand-written query family for six grammars is a maintenance line item
nobody will keep up. Whichever is chosen, it goes in `THIRD-PARTY.md` in the same commit: naming a
source in a class comment satisfies neither licence.

Same rider as `folds.scm`: languages with no `indents.scm` keep the current rule, which stays as the
fallback rather than being deleted.

---

## 24.5 `locals.scm`

Deferred at M3 **with a reason**, and the reason is still the point: `islands-dark.css` already names
`--syntax-variable`, `--syntax-variable-parameter` and `--syntax-variable-member`, and leaves all three
grey, because a grammar sees an identifier and cannot tell a parameter from a field. The scheme says
so in a comment so that landing this "changes a colour rather than a scheme".

Unlike the two above, **the capture names here are fixed by tree-sitter itself**: `@local.scope`,
`@local.definition`, `@local.reference`. No dialect question.

This is the tier that matters for the **engineless** languages. Java gets parameter-vs-field from ECJ
already, through `SemanticTokenProvider`; JS, GLSL, CSS and HTML have no engine and never will, so
`locals.scm` is the only thing that will ever separate those three colours for them.

**Where it lands is the subtlety.** Semantic tokens *replace* grammar tokens where they overlap — they
do not layer, and both names resolve to real colours, so getting the precedence wrong reads as a scheme
bug rather than an ordering one. `locals.scm` output is grammar-tier, so it must be merged into the
grammar bucket in `ensureRowSyntax` and stay *below* anything an engine reports.

The corollary for the test: a dotted capture is also published under its general form, so
grammar-`variable` versus engine-`variable.parameter` cannot demonstrate precedence. Assert on a pair
where neither is the other's general form.

---

## 24.6 GLSL diagnostics

The smallest of the six and the one with no new machinery at all. The shader compiler already reports
errors — `CgShaderParseException` names the offending line, and the material compiler collects failures
rather than throwing on the first (that is what `--mode=shader-compile-audit` exists to drive).

So this is an adapter: compiler output → `Diagnostic(range, severity, message, source, code?)` published
into the document's `DiagnosticSet` under its own owner. Everything downstream is already built and
already generic over the producer — the squiggles, the Problems panel, the status bar count, and the
decoration tracking, which deliberately hangs off `DiagnosticSet.onChanged` rather than off any one
engine's push precisely so that a second producer needs no wiring.

**The version gate is the one thing to get right.** A diagnostic is row/column and a squiggle is
offsets, and the conversion is only legal against the document the analysis saw. GLSL compiles are
driver-round-trips, so they are slower than ECJ's and the window for a stale answer is wider, not
narrower.

**Exit:** a deliberate error in a `.glsl` fixture appears as a squiggle and as a Problems row, with no
new machinery named in the diff.

---

## 24.7 One navigation primitive, three callers

Go-to-definition (24.2), Go To Line (24.8) and clicking a problem (24.9) are the same act with three
different ways of naming the target: a `DeclarationSite`, a typed line number, a `Diagnostic`'s start.
All three mean **open the document if it is not open, then put the caret at a position and reveal it**.

Build that once, as a workbench-level method, and let the three callers differ only in how they
produce a `(resource, TextPoint)`. This is the `ClipboardActions` lesson in a different costume — one
command asking the position, never one command per widget. Three navigation paths that each open
files their own way will disagree about focus, about scroll, and about the async case below, and the
disagreements will be found one at a time.

**The primitive must be asynchronous, because opening is** — see 24.9. Signature shape:

```java
void reveal(Resource resource, TextPoint at, Runnable... /* or a callback */);
```

## 24.8 The Go To Line popup

Built on `InputDialog`, which is already the Delete/New File popup and already has the two halves this
needs: `ask(from, title, label, initial, onAccept)` runs `onAccept` **only** for a non-blank value
confirmed with Enter, and Escape cancels. Its javadoc states the reason a cancelled prompt reports
nothing rather than an empty string — every caller would otherwise re-check.

- Title: `Go To Line`.
- Placeholder in the field: `[Line][:column]` — IntelliJ's own wording for the same dialog.
- Accepts `120`, `120:8`, and `:8` (column on the current line). Anything unparseable is a no-op
  rather than an error dialog: the field is the error message, exactly as the rename prompt treats a
  blank name.
- Clamp rather than refuse — a line past the end goes to the last line. Every editor does this, and
  refusing means retyping.

Two things `InputDialog` does not do yet and this needs:

1. **A placeholder.** `ask` takes an `initial` value, which is not the same thing — an initial value is
   text the user must delete, and this field wants to be empty with a hint. Either `TextField` already
   carries a placeholder (it does — the editor's find bar uses one, and `TextField` has the
   "do not draw the placeholder unless focused" guard that once cost an unbalanced scissor stack) and
   `ask` grows an overload, or a variant that takes both.
2. **Nothing else.** Deliberately not a new dialog class. A second prompt widget is how the two drift.

Command: `editor.goToLine`, bound to `Mod+G` (IntelliJ's binding for Navigate ▸ Line/Column), declared
in `EditorCommands` beside the rest. Routes through 24.7's primitive with the *current* resource, so
the same code path serves it as serves the other two.

**Exit:** `Mod+G`, type `40`, Enter — caret on line 40, scrolled into view, editor focused. `40:8`
puts it at column 8. Escape leaves the caret where it was. A number past the end lands on the last
line rather than doing nothing.

## 24.9 Clicking a problem — already wired, and broken in one specific case

This is **not** new work. `Workbench` already connects `onProblemChosen` and its comment already
states the intent — "OPEN FIRST, THEN REVEAL. The panel is workspace-wide now, so the problem you
clicked is routinely in a file that is not on screen".

The defect is that **opening is asynchronous and the reveal is written as though it were not**:

```java
if (node.resource() != null && node.resource().isProject()) openFile(node.resource().asPath());
TextEditor editor = activeEditor();          // ← the OLD one, or null
editor.setCaret(...);
```

`Workbench.openFile` has two paths. If the file is already open it activates the existing tab and
returns **synchronously**, so the next lines find the right editor and it works. If it is not open it
falls through to `client.read(path, read -> adoptInto(...))` — a callback — and returns before any
document exists. So the reveal runs against whatever was active *before* the click: the caret lands in
an unrelated file, or nothing happens at all.

That is exactly the half the user sees failing, and it is the half the comment above it claims to
handle. It also explains why it would look intermittent: click a problem in the file you are already
looking at and it is correct.

**Fix shape:** the reveal must be the continuation of the open, not the statement after it — which is
what 24.7's primitive is for. `openFile` grows a completion callback (or the workbench keeps a
pending-reveal keyed by path, drained by `adoptInto`), and both branches then run the same reveal.

Two riders:

- **Reveal is not just `setCaret`.** It has to scroll the line into view and focus the editor; a caret
  set on a line the viewport is nowhere near reads as nothing having happened.
- **The row is a `Diagnostic`, so its position is row/column** — and by the time the file opens, that
  position is only meaningful against the version the analysis saw. For a file being opened fresh they
  agree; for one already open and edited since, this is the same conversion problem `DecorationSet`
  exists to solve, and the answer is to reveal the **tracked** range rather than the reported one when
  the document has one.

**Exit:** clicking a problem in a file that is **not** open opens it and lands on the line; clicking
one in a file already open moves the caret there; both leave the editor focused and the line on
screen; a problem whose file has been edited since the compile lands on the word as it is *now*.

## Order

**`24.7` first** — the navigation primitive, since three of the items below are callers of it and
building it second means writing the async open-and-reveal twice. `24.9` falls out of it immediately
and is a bug fix rather than a feature, so it is the cheapest proof the primitive is right. Then
`24.8` (one more caller, a day's work on an existing dialog), then `24.2` (the third caller), then
`24.1` (whose footer calls 24.2).

After that the query family, in cost order: `24.6` (cheapest, and it proves the multi-producer claim
the decoration tracking was built for), `24.5` (visible, and unblocks a scheme comment that has been
promising it), then `24.3` and `24.4` together once the licence question is answered, since they are
the same vendoring decision made twice.
