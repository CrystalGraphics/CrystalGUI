# TextEditor — full review against IntelliJ, VS Code and Monaco

Scope: `core/src/main/java/com/crystalgui/ui/elements/editor/` — `TextEditor.java` (5,338 lines) and its
25 companions (11,520 lines in the package), plus the model it sits on in `com.crystalgui.text`. Read in
full on 2026-08-17; every "dead" claim below was confirmed with a reference search, not assumed. The
quick-fix layer has its own file, `plan_cleanup.md`.

The reference frame is the three editors this codebase already ports from: **Monaco/VS Code**
(`src/vs/editor/`), **IntelliJ** (`EditorImpl` + its handlers) and, where they disagree, whichever one
this project has already chosen for the feature in question.

---

## 0a. Where this stands (2026-08-17)

**All four harness-reported symptoms are fixed**, and six of R7's features are in. What is left is the
large structural work (R1, R3) and the four heaviest features.

| Item | State | Commit |
|---|---|---|
| §3.5 — the four reported symptoms | **done** | `f15e608`, `b93243d`, `56a4623` |
| §3.2 — dead members, the per-frame shapings | **four of nine** | `0950cc1` |
| §3.1 — five orphaned javadocs · §3.3 — three whole-document copies · §3.4 — eight nits | **open** | — |
| R2 — the view-line formula, and the NaN guard | **done** (not the whole object — see below) | `7192ee0` |
| R7.1 — occurrence highlight | **done**, and found a dead one | `352994f` |
| R7.3 — tabs/tab-stop Tab, Enter-between-braces, paste re-indent | **done** | `f15e608`, `bddcaa4` |
| R7.4 — column selection | **done** | `42c9caa` |
| R7.6 — selection highlight, relative line numbers, caret styles | **done** | `042656d`, `bbe3559`† |
| R1 — extract find / folding / language features / diagnostics | not started | — |
| R3 — finish the view-part contract | not started | — |
| R5 — an `em` unit · R6 — split the 5,036-line test | not started | — |
| R7.2 — IME · R7.5 — signature help · R7.7 — rename · R7.8 — sticky scroll, minimap | not started | — |

† Swept into a concurrent session's commit by a broad `git add` in this shared worktree; the code is
correct and only the attribution is wrong. It happened four times over this run.

**Three things were found by doing the work rather than by the review:**

- **`::highlight(search)` was never painted.** The editor has published its find matches under that name
  since find went in and no stylesheet defines it — the ranges resolved, the count was right, the arrows
  stepped, and not one character ever changed colour. Fixed alongside R7.1.
- **The NaN scroll-top of §3.4 had ten unguarded call sites.** Exactly one of the eleven copies of the
  view-line formula wrapped it in `finiteOrZero`; consolidating gave that guard one home.
- **`Language.PLAIN` declares no brackets**, so making the Enter rule language-driven silently stopped a
  plain-text editor indenting after `{`. There is now an explicit fallback for indentation *only* —
  auto-closing gets none, because it puts a character into the document and must never guess.

**Deviations, recorded rather than skipped:**

- **R2 is the rule, not the object.** The plan asks for a `ViewGeometry` computed once per `updateWindow`
  and read by every part. The duplication and the per-part scroll subtraction are what hurt, and both are
  gone; a cached geometry object adds a staleness question to a widget that already has an open bug about
  a value going non-finite mid-layout.
- **Selection mapping stayed in `applyEdit`** rather than moving wholesale into the change listener. The
  listener already clamps and the undo path now answers separately, so moving the rest would have been a
  refactor with no symptom behind it.
- **R7.2 (IME) needs a composition seam in `CgSystemInput`**, which is CrystalGraphics' SPI rather than
  this project's, and cannot be verified without a real IME. Left for a decision rather than guessed at.

---

## 0. Verdict in one paragraph

The **model half is genuinely good** and would not embarrass either reference: rope-backed buffer, one
`ChangeSet` per multi-caret edit, per-caret goal columns, VS Code's mouse granularity rules, tracked
diagnostic ranges with explicit stickiness, a version-gated code-action path, and a soft-wrap projection
that is unconditional (no unwrapped fast path — the right call, and Monaco's). Many of the hard-won
behaviours in the comments are the *correct* ones and are correctly attributed. The **view half is a
5,338-line class that has outgrown its own decomposition**: the VS Code view-part port is half finished
(the render-gate protocol is dead, three parts bypass the pool, thirteen copies of the "top of a view
line" formula), four unrelated subsystems (find, folding, language features, popups) still live inline
with their fields scattered through the file, and there are enough orphaned javadocs, dead members and
whole-document `toString()` calls on hot paths to say the file is no longer being read end to end by
anyone. Feature-wise it is a solid **VS Code-minus**: no column selection, no occurrence highlight, no
IME, no paste re-indent, no tab-vs-space setting, no signature help, no rename, no minimap, no sticky
scroll — but everything it *does* have is done with more care than most editors' first version.

---

## 1. What is good — and better than the references in places

| Area | What | Versus the references |
|---|---|---|
| Document model | `Rope`/`TextBuffer` with `ChangeSet` (sorted, non-overlapping) as the only mutation; `SelectionModel.mapThrough(edit)` carries every caret through one edit; undo coalescing on the buffer, broken explicitly around code actions | Same shape as Monaco's `PieceTreeTextBuffer` + `CursorCollection`; cleaner than IntelliJ's `DocumentImpl` + `CaretModel` split |
| Multi-caret | One `ChangeSet` per keystroke for N carets = one undo step; per-caret `goalColumns`; `moveEach`/`deleteEach` make "does this work with several carets" a non-question | Exactly VS Code's `leftoverVisibleColumns`; IntelliJ took years to make this uniform |
| Mouse | Click-count granularity kept for the whole drag; word-drag unions with the anchor word; autoscroll seeded on press | Straight port of `mouseHandler.ts`, with the "pointerInside flag that was permanently true" bug written down |
| Diagnostics | Tracked ranges (`DecorationSet` lane, `ALWAYS_GROWS_WHEN_TYPING_AT_EDGES`), driven from `DiagnosticSet.onChanged` so every producer gets tracking; version gate at the point of entry; `collapsedByEdit` vs born-empty distinguished | Monaco's `IModelDecoration` semantics, ported with the stickiness explicit rather than defaulted — better documented than Monaco's own |
| Code actions | Version re-checked at apply time; serial lanes per *destination* (doc/definition/actions/bulb) rather than one; bulb re-asks on caret move on its own lane | Monaco has one `CancellationToken` chain per feature; the lane-per-destination reasoning here is sharper |
| Soft wrap | Pixel-width breaks measured from the same `RowMetrics` the caret uses; tabs measured against the whole row and rebased; wrapped indent measured not multiplied; per-edit reprojection by row delta with wholesale fallback | Monaco's `ViewModelLinesFromProjectedModel`, including the `breakOffsetsVisibleColumn` trick done without a parallel array |
| Zoom / fold anchoring | `StableViewport` ported (Monaco); fold anchors on the *caret* row (IntelliJ), and the difference is argued | Correct choice per feature, with the reasoning |
| Highlighting | Grammar tokens cached per model row, row-relative offsets; semantic tokens replace grammar tokens by *source*; general capture name published first, specific last; highlights rebuilt in order per line | Monaco's `TokenizationSupport` + semantic-tokens layering; the ordering bug and its cause are recorded |
| Layout traps | Scrollbar latch (`measureScrollbars`, browser algorithm, bounded); NaN-safe `lineHeight`; text viewport as its own clip box; gutter metrics from CSS not Java | These are exactly the classes of bug editors ship for years |
| Find | Anchored on the first visible line for a fresh query, caret for stepping; centre-only-if-off-screen on step; regex/words/case/preserve-case/exclude | IntelliJ's find bar semantics, including Exclude, which VS Code lacks |
| Documentation | Every non-obvious decision carries the reference and the bug that motivated it | Better than either reference's source |

Keep all of this. Nothing below argues against any of it.

---

## 2. Architecture — where the file has outgrown itself

### 2.1 The class by responsibility (measured)

`TextEditor.java` is one class, but reading it end to end it is at least seven:

| Cluster | Approx. lines | Where each reference puts it |
|---|---|---|
| Core view: buffer, selections, movement, editing, input | ~1,300 | Monaco `cursor.ts` + `viewController.ts`; IntelliJ `EditorImpl` + `CaretModelImpl` |
| Virtualised rendering, row metrics, wrap, geometry | ~1,100 | Monaco `viewLines.ts` + `viewLayout.ts` + `viewModelLines`; IntelliJ `EditorView` + `TextLayoutCache` |
| Syntax/semantic highlight cache and publication | ~450 | Monaco `viewModel` tokens + `Colorizer`; IntelliJ `LexerEditorHighlighter` |
| Find & replace | ~330 | Monaco `findModel.ts` / `findController.ts`; IntelliJ `EditorSearchSession` |
| Folding glue (model exists in `text/fold`) | ~330 | Monaco `folding.ts` contribution; IntelliJ `FoldingModelImpl` |
| Language features: services, resolve lanes, hover, doc popup, code actions, completion glue, go-to-def | ~750 | Monaco: `hover/`, `suggest/`, `codeAction/`, `gotoSymbol/` — **four separate contributions**; IntelliJ: `EditorMouseHoverPopupManager`, `CompletionProgressIndicator`, `ShowIntentionActionsHandler`, `GotoDeclarationAction` |
| Diagnostics ownership, tracking, navigation, problem popup | ~350 | Monaco `markerDecorations` (on the *model*); IntelliJ `MarkupModel` on the document |
| Gutter metrics, zoom, decorations settings, clipboard, misc | ~700 | spread |

The point is not the line count. It is that **fields for each cluster are declared next to the code
that uses them, mid-file** — `results`, `reentrantFind`, `searchBar`, `preserveCase`, `lastSearch` sit
between the find methods; `docPopup`, `hover`, `completion`, `completionPopup`, `resolveSerials` sit
between the language methods; `folding`, `foldingProvider`, `foldingDirty` sit after `revealRow`.
That is what a class looks like when it is really several, and it is why four javadocs have become
detached from their subjects (§3.1) — the code moved and the comments stayed.

Monaco's answer is *contributions*: each feature is a class holding its own state and given the editor.
IntelliJ's is handler/manager classes given the `Editor`. This codebase has already done it once, for
`SearchReplaceBar` and `HoverDocumentation` — the pattern exists; it was stopped early.

### 2.2 The view-part port is half done

The `EditorViewPart` decomposition (Monaco's `ViewPart`) is the right move and it was left mid-stride:

- **`shouldRender()` is dead.** `setShouldRender()` has no caller anywhere; every part renders every
  frame. The base class's own comment says so. Either wire it — parts declare what they react to
  (scroll, selection, diagnostics, fold) and `updateWindow` sets the flags — or delete it. A protocol
  that exists and is not honoured is worse than none, because the next author will assume it works.
- **`DecorationPool` exists and three parts do not use it.** `LineNumbersPart` (`numbers` + `numberAt`),
  `SelectionsPart` (`bands` + `bandAt`), `ViewCursorsPart` (`carets` + `caretAt`), plus `SquigglesPart`,
  `ErrorStripePart` and `FoldingDecorationsPart` each hand-roll the same grow-on-demand list and the same
  "hide the tail" loop. Six copies of the pool idiom beside the class that is the pool idiom.
- **The "top of view line n" formula is written 13 times** — `textOriginY() + viewLine * height -
  scrollTop` in nine files. Every part also subtracts `getScrollLeft()` and adds `codeLeftPad()` by
  hand. Monaco has `ViewLayout.getVerticalOffsetForLineNumber` and every part calls it. One
  `ViewGeometry` (or the existing `TextEditor` exposing `topOfViewLine(int)`, `leftOfColumn(viewLine,
  column)`) removes all thirteen and makes the scroll-exempt-viewport arithmetic exist once.
- **A part writes the editor's layout.** `LineNumbersPart.insetHorizontalBarPastGutter` positions the
  editor's horizontal scrollbar. That is the editor's business (it already owns
  `setTopChromeInset` for the vertical bar), and finding it inside the line-numbers renderer is a
  surprise.
- **Pixel constants in Java, in the parts.** `SQUIGGLE_HEIGHT 1f`, `MARK_HEIGHT_PERCENT 1.2`,
  `MIN_SNAP_PX 5`, `CLEARANCE 8f`, `TOP_GAP 2f`, `chipPadding = fontSize*0.28`, `chipHeight = …*1.35`,
  the zoom panel's `chrome*4f`/`chrome*2f`/`chrome*1.6f`, `MAX_GUIDES`, `MAX_MARKS`. The project rule is
  "if you are typing a pixel value into a widget it belongs in `default.css`", and the editor's own
  gutter metrics were moved out for exactly that reason. The parts were written after that rule and
  ignore it.
- **Inconsistent empty-window contract.** `SelectionsPart`/`ViewCursorsPart` return early when
  `lastViewLine < firstViewLine`; the pool-based parts `hideAll()`. Which is right depends on whether a
  stale band may survive a frame; it should be one rule in the base class.
- **Font is pushed at IMPORTANT per element per frame** in `syncLineFonts`, `LineNumbersPart`,
  `WhitespacePart` (keyed) and `FoldingDecorationsPart.pushEditorFont`. Cheap because `replaceOrPut`
  no-ops, but it is four places doing one thing; the parts should read the editor's resolved font
  once per frame from the same seam.

### 2.3 Coordinate spaces are a folk tradition

There are four: screen, editor-local, text-viewport-local (scroll-exempt, so scroll is subtracted by
hand), and document (row/column and offset). Every conversion is correct — the comments prove it was
fought for — but the knowledge lives in comments on `textOriginX`, `textViewportLeft`, `layOutLine`,
`anchorInWindow`, `offsetAtLocal` and each part. Monaco makes this a type (`ViewLayout` +
`CoordinatesConverter`), IntelliJ has `EditorView.visualPositionToXY` / `xyToLogicalPosition`. One
class with `viewLineTop`, `columnX`, `offsetToLocalXY`, `localXYToOffset` and `localToWindow` would be
the single place `uiScale`, padding, gutter and scroll meet.

### 2.4 State the editor owns that belongs to the document

`DiagnosticSet` on the editor is documented as a "known compromise" — agreed, and it is now the thing
blocking two panes onto one file from agreeing about the Problems panel. `LanguageServices` is already
document-owned; the set should move with it. Same for `foldingModel` if folds are ever to be shared
across panes (IntelliJ shares them; VS Code does not — either is defensible, but decide).

### 2.5 Input is split three ways with different rules

- Named actions are commands on the keymap (good — the section header says so and it is true).
- Native keys are in `handleKey` (arrows, Home/End, Backspace/Delete, Enter, Tab, Escape).
- **Ctrl+Space is hard-coded in `handleCompletionKey`** — the one chord that is not a command, and so
  the one that cannot be rebound or listed. It should be `editor.triggerSuggest` like the rest.
- The mouse handler is ~70 lines inside `installInput` with Ctrl-click, Alt-click, Shift-click and
  drag start interleaved. `MouseSelection` (the model half) was ported; the controller half was not —
  Monaco keeps it in `MouseHandler` / `MouseTargetFactory`.

---

## 3. Defects, dead code and smells (all verified)

### 3.1 Orphaned javadocs — comments describing something else
| Line | Comment | Actually sits above |
|---|---|---|
| ~451 | "Which blocks are foldable and which are closed … view state" | the `// View parts` block; the `folding` field is 220 lines later at ~672 |
| ~1902 | The `resolveAt` javadoc ("A serial per DESTINATION…") | `diagnosticsAt()` |
| ~2028 | The `goToDefinition` javadoc ("Three ways this legitimately does nothing…") | `private DocumentationPopup docPopup;` |
| ~3828 | "The vertical equivalent, for the same reason." | a section-divider comment |
| ~5281 | "Routes UiDataKeys.UNDO_STACK …" | the `clipboardActions` field |

### 3.2 Dead or duplicated members — **four of nine done** (`0950cc1`)
- ✅ `lastQuery`, `lastQueryCaseSensitive` — written in `find`, read nowhere.
- ✅ `selectWordAt(int)` — private, unused (`MouseSelection.unitAt` replaced it).
- ✅ `hide(UIElement)` at ~5141 — unused; `DecorationPool.hide` is the live copy.
- ⬜ `searchMatches` + `currentMatch` duplicate `results.matches()` / `results.current()`;
  `selectMatch`'s `while (results.current() != index && results.next()) { if (…) break; }` steps one
  model to keep the other in sync. Six fields for one piece of state.
- 🔸 `OPENERS`/`CLOSERS` in bracket matching, beside a `Language` that already knows
  `closerFor`/`isCloser`. Two definitions of "a bracket". **Half done**: `insertNewlineWithIndent` asks
  the `Language` now; the bracket-MATCH scan still uses the literals.
- ⬜ Empty sections: `// ── Helpers ──` with nothing under it; five blank lines at ~3167.
- ⬜ Import block: `java.util.function.*` first, `com.crystalgraphics` before `com.crystalgui`, `org.joml`
  in the middle, `java.util` at the end — no order at all.
- ✅ `RulersPart`/`IndentGuidesPart` call `editor.spaceAdvance()` **every frame**, and it shapes `" "`
  each time — the same trap `gutterDigitsWidth` documents and fixed for the digit. Cache by font key.
- ✅ `ZoomIndicatorPart.render` shapes both label strings **every frame** for as long as the panel
  exists (it renders while hidden too).

### 3.3 Whole-document materialisation on hot paths
`Rope implements CharSequence` and `TextSearch.findAll` takes a `CharSequence`, yet:
- `find(query)` passes `buffer.toString()` — the whole document allocated **on every keystroke in the
  find box** and again after every edit (via the buffer listener).
- `textIn(range)` calls `buffer.toString()` **per match** inside `replaceAll` → O(n·m).
- `addCaretAtNextOccurrence` / `selectAllOccurrences` build `buffer.toString()` and use `String.indexOf`
  — bypassing `TextSearch` and its whole-word rule.
The class's own comment on word boundaries says materialising the document per keypress was a bug it
already fixed once.

### 3.4 Behavioural nits found while reading
- `moveLines` calls `applyEditKeepingSelection` (which maps selections and emits) then overwrites the
  selections and emits again — two `onSelectionChanged` per Alt+Up, and the mapped selections are
  thrown away.
- `Ctrl+D` on an empty caret selects the word, then matches **raw substrings, case-sensitively** —
  VS Code's `addSelectionToNextFindMatch` uses whole-word + case-sensitive when it *started* from a
  word, so `count` does not later select the `count` inside `counter`. Ours does.
- Bracket matching scans characters with no awareness of strings/comments — a `(` inside `"("` matches
  the wrong partner. Both references skip token types that are not brackets.
- `Tab` with no selection inserts `indentWidth` spaces at the caret regardless of column — no
  tab-stop alignment, no `insertSpaces=false` mode. There is `tabSize` and `indentWidth` but no way to
  say "indent with tabs", so a tab-indented file cannot be edited faithfully.
- `insertNewlineWithIndent` is "dumb by design" and says so, but does not even close: typing Enter
  between `{` and `}` gives one line, not the three every editor gives (`{`, indented caret, `}`).
- `gutterMetric` scales CSS lengths by `fontSize / firstSeenFontSize` — the "baseline" is whatever the
  first frame happened to have, so an editor first refreshed while zoomed gets a permanently wrong
  ratio. It is a workaround for the engine having no `em` unit; the fix is the unit.
- `applyEdit` reveals the caret; `applyEditKeepingSelection` reveals it too but never clears goal
  columns — Tab-indent then Up drifts.
- `handleKey` Home/End under soft wrap ignore Ctrl (Ctrl+Home is caught earlier — fine) but Home
  under wrap has no "smart home" second stop on the *row*: view-line start, then row smart-home,
  never row start.
- `ensureCaretVisible` scrolls vertically only; a caret moved past the right edge (long line,
  End key) is not revealed horizontally. Both references reveal on both axes.
- `showCodeActionsAt` builds `Menu`/`MenuItem` with inline FQNs and hangs `MenuBuilder.present`
  inside a callback — the popup lifecycle logic (`popupActions.disconnectAll`, three near-identical
  `requestCodeActions` blocks in `fillProblemSection`, `showProblemPopupAt`, `showCodeActionsAt`) is
  the same wiring written three times.

### 3.5 Reported from the harness (2026-08-17) — four symptoms, two root causes

Reported: multi-caret "kind of broken"; undo/redo do not put the caret back; Enter between `{}` does
not open a three-line block; Backspace at the indent of a whitespace-only line does not go up to the
previous line. All four confirmed by reading the code paths. They are **not** one bug, but they are
only two families, and each family is a rule that was written once in the wrong place.

**Family A — the view reconciles its selections only for edits it made itself.**

- `TextBuffer.onChanged` is the one signal every edit passes through — typing, paste, undo, redo,
  `setText`, a server push. The editor's listener (constructor, ~L741) does `selections.clampTo(length)`
  and nothing else. Selections are mapped through a change **only** in `applyEdit` /
  `applyEditKeepingSelection`, i.e. only for edits this editor initiated. So after **undo** of "typed
  `abc`", the caret stays at its old offset (three characters right of where the text was) and is
  merely clamped; after undo of a multi-caret edit every secondary caret is wrong by its own delta.
  That is the "undo does not put the caret back" report, and it is half of the multi-caret report.
- On top of that, an undo entry (`TextBuffer.ChangeSetEdit`) carries **no selection state** — by
  design, "the buffer holds no view state" — so even a mapped-through caret lands where the mapping
  puts it, not where the user's caret *was* before the edit. VS Code stores `beforeCursorState` /
  `afterCursorState` on every undo element and restores them; IntelliJ's `UndoManager` records the
  caret/selection in the command. Both keep the *document* stack view-free by attaching the view
  state to the entry from the editor's side, which is exactly what `ChangeSetEdit` cannot do today.
- Independent third bug in the same area: **Alt+click then any pointer movement collapses every
  caret**. The `MouseEvent.Down` handler adds the caret and still sets `selecting = true` with a
  `dragAnchor`; the first `Move` calls `extendDragTo` → `setSelection(anchor, head)`, which is
  documented as "collapses to a single selection". One pixel of drift between press and release
  destroys the multi-caret set — which is what "kind of broken" feels like from the mouse.
- Smaller, same family: `addCaretAtNextOccurrence` searches from the last selection *by position*
  and stops for good once the wrap lands on an existing one; `typeCharacter`'s auto-close path calls
  `moveEach` → `afterSelectionChange` → `breakUndoCoalescing()`, so typing `(` splits the undo run.

*Fix (R4-A):* Monaco's arrangement, ported. (1) All selection mapping happens in the content-change
listener — `selections.mapThrough(change)` for every change, with the editing paths then *setting*
their intended post-edit selections (collapse to head, keep, etc.) instead of mapping themselves.
(2) `TextBuffer.edit` takes an optional view memento (`Object before/after`) that `ChangeSetEdit`
stores and hands back on undo/redo via `onChanged`'s payload (a `ChangeEvent(change, origin, memento)`
rather than the bare `ChangeSet`), so the editor restores the exact selections; the buffer still holds
no view *type*, only an opaque memento, which keeps the history sendable. (3) Alt+click sets no
`dragAnchor` and does not start a drag unless the pointer moves past a threshold — VS Code's
`createCursor` vs `mouseDrag` split. (4) `addCaretAtNextOccurrence` searches from the *newest*
selection through `TextSearch` and continues past existing ones.

**Family B — the typing aids read the LINE, not the caret's neighbours.**

- `insertNewlineWithIndent` (~L2987): `opens = line.trim().endsWith("{")` — a test on the **whole
  line's last character**. With the caret between `{` and `}` the line ends in `}`, so `opens` is
  false, one newline is inserted with the carried indent, and `}` is pushed to the new line beside the
  caret (screenshot 91). VS Code's `TypeOperations._enter` asks `getEnterAction(beforeText,
  afterText)`: text before the caret ends in an opener **and** text after starts with its closer →
  `IndentOutdent` — newline + one level for the caret, then newline + carried indent for the closer.
  The rule needs the two characters around the caret and the language's bracket pairs, both of which
  exist (`Language.closerFor`); it reads neither.
- `TypeOperations.backspaceFrom` (`text/cursor`, ~L92) counts **characters**, not visual columns:
  `column % indentWidth` on a tab-indented line (the screenshots' GLSL file, two tabs displayed as
  eight) is `2 % 4 = 2` → deletes both tabs to column 0 in one press (screenshot 93). And it has no
  rule for the whitespace-only line at all: both references, in their default modes, treat Backspace
  on a line that is only whitespace with the caret at its indent as "delete this line's indent *and*
  the newline" — landing at the end of the previous line (screenshot 92's expectation; IntelliJ's
  *Smart Backspace: Indent*, VS Code's `deleteLeft` after `useTabStops` has emptied the indent). The
  same character-vs-column fault is why `Tab` inserts `spaces(indentWidth)` regardless of column and
  why there is no tabs-mode: nothing in the typing path speaks visual columns even though
  `CursorColumns` (ported for exactly this) sits beside it.

*Fix (R4-B):* port `cursorTypeOperations.ts`'s remaining pieces onto the existing `CursorColumns`:
`_enter` with `IndentAction` (`None`/`Indent`/`IndentOutdent`/`Outdent`) driven by
`Language`'s brackets and the two caret neighbours; `_runAutoIndentType`; `deleteLeft` with
`useTabStops` semantics in visual columns (whitespace-only line at indent → join to previous line);
`tab` that aligns to the next stop and honours `insertSpaces`. All of it lives in
`text.cursor.TypeOperations`, testable headlessly, and the editor calls it per caret exactly as it
already calls `backspaceFrom`.

**Why they felt like one bug.** Both families are places where the plan said "dumb rule now, real
engine later" — the buffer listener that only clamps, the Enter rule that reads `endsWith("{")`, the
Backspace that counts characters — and "later" never came. Each is one rule in one place; each just
needs to be the *right* rule, ported rather than re-derived.

### 3.6 Tests
`TextEditorTest.java` is **5,036 lines** — one file for the whole widget. `EditorFindReplaceTest`,
`SquigglesTest`, `HoverDocumentationTest`, `EditorHighlightCacheTest` show the right shape; the
monolith is where the next regression hides.

---

## 4. Feature matrix against the references

✓ present and right · ~ present, partial or divergent · ✗ absent

| Feature | Ours | VS Code | IntelliJ | Note |
|---|---|---|---|---|
| Multi-caret (add/next/all/above/below) | ✓ | ✓ | ✓ | Ctrl+D semantics diverge (§3.4) |
| Column (box) selection | ✗ | ✓ | ✓ | documented gap in `text.cursor` |
| Word ops / `_` a word char / per-language separators | ✓ | ✓ | ✓ | |
| Smart Home, atomic tab-stop moves | ~ | ✓ | ✓ | atomic tabs absent (documented) |
| Goal column per caret | ✓ | ✓ | ✓ | |
| Occurrence highlight of word under caret | ✗ | ✓ | ✓ | the most visible missing default |
| Selection highlight (other matches of selection) | ✗ | ✓ | ✓ | |
| Auto-close / type-over / surround, allowlist | ✓ | ✓ | ✓ | |
| Enter between braces → three lines | ✗ | ✓ | ✓ | |
| Auto-indent (syntax) / paste re-indent | ✗ | ✓ | ✓ | copy-indent only |
| Tabs vs spaces setting, tab-stop insert | ✗ | ✓ | ✓ | |
| Overtype / insert mode | ✗ | ✓ | ✓ | |
| Undo coalescing, one step per multi-caret edit | ✓ | ✓ | ✓ | |
| Comment toggle line/block | ✓ | ✓ | ✓ | |
| Move/duplicate/join/delete lines | ✓ | ✓ | ✓ | |
| Soft wrap (pixel-measured), wrap indent | ✓ | ✓ | ✓ | |
| Folding (indent), fold commands, chip `{...}` | ✓ | ✓ | ✓ | syntax folding is a seam, not shipped |
| Indent guides + active guide | ✓ | ✓ | ✓ | |
| Render whitespace, rulers, current line, scroll past end | ✓ | ✓ | ✓ | |
| Line numbers relative | ✗ | ✓ | ✓ | |
| Bracket match highlight | ~ | ✓ | ✓ | ignores strings/comments; scan-limited |
| Bracket pair colorisation | ✗ | ✓ | ✗ (plugin) | |
| Sticky scroll / breadcrumbs in editor | ✗ | ✓ | ✓ | |
| Minimap | ✗ | ✓ | ✗ | |
| Error stripe / overview ruler + click-to-jump | ✓ | ✓ | ✓ | |
| Inspection widget (counts + arrows) | ✓ | ✗ | ✓ | |
| Squiggles + faded unused + strikethrough deprecated | ✓ | ✓ | ✓ | |
| Zoom with stable viewport + indicator | ✓ | ✓ | ✓ | |
| Caret styles (block/underline), smooth caret | ✗ | ✓ | ✓ | |
| Syntax + semantic highlighting merged | ✓ | ✓ | ✓ | |
| Completion: autopopup, trigger chars, ranking, docs | ✓ | ✓ | ✓ | no snippets, no commit characters |
| Snippets / tab stops in completion | ✗ | ✓ | ✓ | |
| Signature help / parameter hints | ✗ | ✓ | ✓ | |
| Hover docs + problems in one popup | ✓ | ✓ | ✓ | |
| Go to definition (Ctrl+B / Ctrl+click) | ✓ | ✓ | ✓ | |
| Rename, find usages, references | ✗ | ✓ | ✓ | |
| Inlay hints, code lens | ✗ | ✓ | ✓ | |
| Quick fixes + intentions + bulb | ✓ | ✓ | ✓ | |
| Find/replace: regex, words, case, preserve case | ✓ | ✓ | ✓ | |
| Exclude match from Replace All | ✓ | ✗ | ✓ | |
| Find in selection | ✗ | ✓ | ✓ | |
| Search anchored on viewport for fresh query | ✓ | ✓ | ✓ | |
| IME composition (CJK, dead keys) | ✗ | ✓ | ✓ | `typeCharacter` per char only |
| Bidi / RTL | ✗ | ~ | ✓ | |
| Read-only, enabled/disabled | ✓ | ✓ | ✓ | |
| Large-file: virtualised rows, cached metrics, row-relative tokens | ✓ | ✓ | ✓ | but see §3.3 |
| Drag-and-drop text | ✗ | ✓ | ✓ | |
| Middle-click paste (Linux) | ✗ | ✓ | ✓ | |

---

## 5. The rewrite

Ordered so each step is a pure move covered by the existing tests before any behaviour changes.

### R1 — Extract the four subsystems into contributions
- `EditorFind` (state: `results`, `lastSearch`, `preserveCase`, `reentrantFind`, `searchBar`; methods:
  `find*`, `replace*`, `selectMatch`, `firstVisibleOffset`, `toggleExclude*`). `SearchReplaceBar` talks
  to it. Delete `searchMatches`, `currentMatch`, `lastQuery`, `lastQueryCaseSensitive`.
- `EditorFolding` (`folding`, `foldingProvider`, `foldingDirty`, anchors, every `fold*`, `revealRow`,
  `placeholderTextFor`, `collapsedHeaderCut`).
- `EditorLanguageFeatures` — or split as Monaco does: `EditorHover` (already `HoverDocumentation` +
  `docPopup` + `showDocumentationAt`), `EditorCodeActions` (`requestCodeActions`, `applyCodeAction`,
  `showCodeActionsAt`, the three popup wirings collapsed to one), `EditorSuggest` (`completion*`,
  `handleCompletionKey`, `maybeTriggerCompletion`, anchor), `EditorNavigation` (`goToDefinition`,
  `revealAt`, `resolveAt` + lanes).
- `EditorDiagnostics` (`diagnostics`, `installDiagnostics`, `retrackDiagnostics`, `trackedRangeFor`,
  `diagnosticsAt`, `goTo*Problem`) — and then move it onto the document per §2.4.
Each is a class given the `TextEditor` (as `SearchReplaceBar` and `HoverDocumentation` already are).
Fields go with their methods; the four orphaned javadocs get their subjects back for free.

### R2 — `ViewGeometry` — **DONE as the rule, not the object** (`7192ee0`; see §0a)
One package-private object owning `lineHeight()`, `textOriginX/Y()`, `viewportHeight/Width()`,
`topOfViewLine(int)`, `xOf(viewLine, column)`, `localToOffset`, `offsetToLocal`, `localToWindow`.
`TextEditor` computes it once per `updateWindow`; every part reads it. Kills the 13 formula copies,
the per-part scroll subtraction, and gives `anchorInWindow`/`offsetAtLocal` one home.

### R3 — Finish the view-part contract
- `DecorationPool` in `LineNumbersPart`, `SelectionsPart`, `ViewCursorsPart`, `SquigglesPart`,
  `ErrorStripePart`, `FoldingDecorationsPart`.
- Wire `shouldRender` or delete it (recommend wire: `EditorViewPart.onScroll/onSelection/
  onDiagnostics/onFold` default no-ops that set the flag; `updateWindow` fires them).
- One empty-window rule in the base.
- Move `insetHorizontalBarPastGutter` into the editor beside `setTopChromeInset`.
- Every pixel constant in the parts → `default.css` (`ua/editor.css`), read once per frame like the
  gutter metrics. Cache `spaceAdvance` and the zoom label widths by font key.
- Parts read the editor's resolved font from one seam instead of pushing IMPORTANT writes.

### R4 — Model-side correctness
- ✅ **R4-A / R4-B — the four reported symptoms are fixed**, in `f15e608` (Enter/Backspace/Tab),
  `b93243d` (the undo memento) and `56a4623` (Alt-drag and Ctrl+D). What each turned out to be:
  - **Enter between braces** — the rule asked whether the LINE ended in an opener; with the caret
    between the pair it ends in the closer. Now Monaco's `_enter` over the two characters around the
    caret, with `IndentOutdent` as the shape that had no spelling at all. `insertNewlineWithIndent`
    places its own carets, because mapping an insertion puts the caret after *both* new lines.
  - **Backspace at an indent** — counted characters, so two tabs were `2 % 4` and one press took the
    whole indent to column zero. Counted in visual columns it takes one tab, and reaching column zero
    then falls through to the line join, which is the half that was reported.
  - **Undo/redo carets** — recorded on the undo entry (`beforeCursorState`, as both references do) and
    handed back through a new `TextBuffer.onSelectionsRestored`. Redo is *derived* by carrying those
    carets through the change, so the two cannot drift; a merged typing run keeps the first step's.
  - **Multi-caret** — two bugs. Alt+click armed an ordinary drag, so one pixel of movement collapsed
    every caret via `setSelection`; and Ctrl+D resumed from the last selection *by position*, which
    stops being the newest one the moment the search wraps, after which it refused forever.
- Selection *mapping* stayed in `applyEdit` rather than moving wholesale into the change listener: the
  listener already clamps, the undo path now answers separately, and moving the rest would have been a
  refactor without a symptom behind it. Recorded here so the deviation is not mistaken for an oversight.
- `find`, `textIn`, `addCaretAtNextOccurrence`, `selectAllOccurrences` search the `Rope` (it is a
  `CharSequence`) — no `toString()`; Ctrl+D goes through `TextSearch` with whole-word when started
  from a word (VS Code's rule).
- Bracket matching and Enter-indent ask `Language` for brackets; bracket scan skips string/comment
  tokens (one `isInCommentOrString` already exists).
- `moveLines`: map selections through the edit once; one emit.
- Enter between a pair → three lines; `applyEditKeepingSelection` clears goal columns.
- `ensureCaretVisible` reveals horizontally too.
- Ctrl+Space becomes `editor.triggerSuggest`.
- `insertSpaces` setting + tab-stop insertion (Monaco's `TypeOperations.tab`), which the ported
  `CursorColumns` already makes cheap.

### R5 — `em` unit (engine), retire the `gutterMetric` baseline hack
Add a font-relative length to the style engine (`em`, resolved against the element's computed
`font-size`). Then the gutter metrics, the chip padding/height, the zoom panel and the completion
popup can all be authored in CSS as multiples of the font, and the "first-seen font size" baseline goes.

### R6 — Tests
Split `TextEditorTest` by feature (movement, editing, multi-caret, wrap, fold, zoom, geometry, input),
matching the R1 boundaries so each contribution's tests sit beside it.

### R7 — Feature gaps, in the order they earn their keep
1. ✅ Occurrence highlight under caret (`352994f`) — and `::highlight(search)` turned out to have no
   rule at all, so the editor's find highlight had never painted a single character.
2. IME composition — the editor cannot be used for CJK or dead-key layouts at all; needs a
   composition seam in `CgSystemInput`. **Not started, and it is CrystalGraphics' SPI rather than this
   project's** — a seam nobody can verify without a real IME is a guess with an interface on it.
3. ✅ Tabs-vs-spaces + tab-stop Tab; Enter-between-braces (`f15e608`); paste re-indent (`bddcaa4`).
4. ✅ Column selection (`42c9caa`) — `ColumnSelection`, and AGENTS.md no longer lists it as a gap.
5. Signature help (the resolver already answers `SymbolInfo` with a signature; it is a popup and a
   trigger on `(` and `,`).
6. ✅ Selection highlight (`042656d`) under its own `::highlight()` name — a selection is a request about
   CHARACTERS, not about a symbol, so it must not wear the word highlight's colour. Relative line numbers
   and the three caret styles with it, both keyed off measurements rather than constants.
7. Rename in file (multi-caret already does 90% of it — `selectAllOccurrences` by binding rather than
   text is rename's engine).
8. Sticky scroll (needs the folding regions, which exist); bracket-pair colours; minimap last.

---

## 6. Order

*The order below is the original plan's. What actually happened is in §0a, and it went R4 → §3.2 → R2 →
R7, because the reported symptoms came first and the rest followed what they touched.*

1. R1 + fix §3.1/§3.2 (moves and deletions only; whole editor test set is the net). — §3.2 ✅, R1 open.
2. R2 + R3 (geometry object, parts finished; the harness `cgui-text-stress` and `EditorFrameCostTest`
   guard the per-frame cost). — R2 ✅ as the rule, R3 open.
3. R4 (behaviour changes, each with a fixture). — ✅
4. R5 (engine change; touches `LengthPercent` parsing and the cascade — separate commit, own tests).
5. R6 alongside 1–3 as each cluster moves.
6. R7 as product priorities decide. — 1, 3, 4 and 6 ✅; 2, 5, 7, 8 open.

Two things I would do first regardless: delete the dead members and re-home the four orphaned
javadocs (an hour, no risk) -- the dead members are gone in `0950cc1`, THE JAVADOCS ARE NOT -- and stop materialising the document in `find`,
which is the one item here that gets *worse* with every line the file grows and is **still open**. Three
whole-document `toString()` calls now sit on caret-driven paths rather than one, since the occurrence
scan reads it too; it is bounded by a document-size limit, which is a floor rather than a fix.
