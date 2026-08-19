# Quick fixes — problems that offer to fix themselves

A hover over a warning shows what is wrong and what to do about it, and Alt+Enter offers the same list
at the caret. IntelliJ's shape: the message, one primary fix with its accelerator, `More actions…`, and
the declaration underneath.

> **This file decides the mechanism. `plan_quickfix_catalog.md` decides the content** — every
> correction worth having, keyed to the `IProblem` constant that triggers it, ranked by what it costs
> here, with the work queue at the end. Read that one when adding a fix; read this one when changing
> how fixes are carried, ranked or applied.

---

## 0. The question this answers first: how does an error map to an action?

The three references answer it in three different places, and the difference is not cosmetic.

| Reference | Where the mapping lives | Shape |
|---|---|---|
| **LSP / Monaco** | On the producer, computed **lazily on request** | Client sends `textDocument/codeAction` with a range **and the diagnostics in it**; server returns `CodeAction[]`. The client never maps anything — it hands the diagnostics back to whoever made them |
| **IntelliJ (inspections)** | On the producer, computed **eagerly at report time** | `ProblemDescriptor` carries `LocalQuickFix[]`. The inspection that found the problem has the PSI in hand, so it builds the fix there. Compiler errors take the same route via `QuickFixAction.registerQuickFixAction(highlightInfo, fix)`. Separately, `IntentionAction` offers things not tied to any problem (`isAvailable(project, editor, file)`) |
| **Eclipse JDT** | In a **keyed table**, computed lazily | `IQuickFixProcessor.getCorrections(context, IProblemLocation[])` — a `switch` on `IProblem.getID()`. The compiler reports a number; the fix table decides what that number affords |

**The recommendation is JDT's keying inside an LSP-shaped request**, and the reason is that we already
store the key:

```java
// EcjSourceAnalyzer.diagnostics(), today
found.add(new Diagnostic(start, end, severity, problem.getMessage(),
        "java", Integer.toString(problem.getID())));
```

`source` says who complained, `code` says which complaint. `Diagnostic`'s own javadoc already anticipates
this — *"a Problems panel groups by the first and a future 'suppress this warning' acts on the second"*.
So the mapping is `(source, code) → fix builder`, and nothing new has to be threaded through the
analyzer to make it possible.

**Why not IntelliJ's eager attachment.** It would mean building fix objects for every problem on every
300 ms compile, nearly all of them never shown. Worse, the fix would have to live *on* `Diagnostic`,
which is deliberately a plain record — it crosses to the Problems panel, it is compared by value, and
`code`/`source` exist precisely so that behaviour does not have to travel with it. Putting a closure in
there ends that.

**Why lazy also fixes staleness.** A fix computed at report time is computed against the parse that
reported it; by the time it is clicked the buffer has moved on. Computing it at request time against the
current analysis, and gating the result on `Versioned.isFresh`, is the same discipline
`onDiagnostics` already applies.

---

## 1. Deciding the whole action set — and why nothing decides it centrally

The obvious next question is "so what is the complete list of actions for error X?", and the obvious
answer — a table from each error to its full action set — is the thing that turns this feature into a
mess. It is `O(errors × contributors)`, every new fix edits one shared file, and the table is never
finished because ECJ alone has on the order of a thousand problem ids.

**All three references refuse to centralise it.** The list is a *union over self-selecting
contributors*, merged and ranked at the point of display:

| Reference | How the set is assembled |
|---|---|
| **Monaco** | N `CodeActionProvider`s registered per language; results **concatenated**. No provider knows what the others returned |
| **IntelliJ** | `ShowIntentionActionsHandler` merges three independent sources — fixes attached to the `HighlightInfo`s under the caret, every `IntentionAction` whose `isAvailable(...)` says yes, and gutter intentions — then groups them (error fixes, then intentions, then low priority) |
| **JDT** | `IQuickFixProcessor` is an **extension point**: each processor is asked `hasCorrections(unit, problemId)` and then `getCorrections(...)`, and the results are merged and sorted by an int `relevance` |

So the shape is: **each contributor answers only for itself, and nobody is asked to enumerate
everything.** Three kinds of contributor, and the distinction is what keeps the table small:

1. **Keyed on `code`** — the closed part, and the only part that needs a `switch`. Only the producer of
   an error knows what it means, so only it can say what fixes it. JDT's own `QuickFixProcessor` is the
   reference enumeration of what each `IProblem` affords; we port entries by demand, not exhaustively.
2. **Keyed on the diagnostic's *shape*, not its identity** — these apply to every diagnostic and need no
   table at all: *Suppress with `@SuppressWarnings`* (available whenever `code` is non-null and the
   language has a suppression syntax), *Disable this rule*, *Go to related* (whenever `related` is
   non-empty), *Copy message*. A large share of IntelliJ's Alt+Enter list is exactly this.
3. **Not tied to an error at all** — IntelliJ's intentions, LSP's `refactor.*`. These answer a different
   question: "what can I do at this caret", asked of the AST rather than of a problem. Same `CodeAction`
   type, same popup, different trigger — which is why `CodeActionKind` exists in the record from the
   start rather than being retrofitted when the first refactoring lands.

> **An unknown `code` returns nothing, and that is the correct answer.** Coverage of a thousand problem
> ids is not a goal and never will be; the popup still shows the message and the shape-derived actions,
> which is strictly better than today. Anything that treats "no fix" as a gap to be filled ends up with
> the exhaustive table this section exists to avoid.

### Ranking

Merged, deduplicated by title, then ordered by a **declared tier**, never by a computed score:

```
QUICK_FIX & preferred  ->  QUICK_FIX  ->  REFACTOR  ->  SOURCE  ->  suppress/disable
```

Only the first is shown inline in the hover popup; everything else is behind *More actions…*. Tiers
rather than a JDT-style int `relevance` because this codebase has already paid for the alternative once
— completion ranks by match tier precisely because a folded numeric score let brevity outrank
proximity, and a single number nobody can explain is exactly how that happened. A contributor that
wants to be first says which tier it is in, and ties break on insertion order.

---

## 2. What already exists (do not rebuild any of this)

| Need | Already there |
|---|---|
| A key to map on | `Diagnostic.source` + `Diagnostic.code`, populated for Java |
| "Which problems are at this offset **now**" | `buffer.decorations().overlapping(from, to)` over `TextEditor.DIAGNOSTIC_LANE`, payload is the `Diagnostic`. Also `trackedRangeFor(problem)` for the reverse |
| Live offsets across edits | `DecorationSet` / `TrackedRange` / `Stickiness` — already installed for diagnostics |
| Staleness gate | `Versioned<T>` + `isFresh(currentVersion)` |
| An async provider shape to copy | `CompletionProvider.complete(Request, Consumer<Versioned<CompletionList>>)` |
| Absent-engine tiering | `LanguageServices` `default` accessors returning `NONE` constants |
| Applying a multi-part edit as **one undo step** | `buffer.edit(ChangeSet.of(buffer.length(), changes))` — exactly what `CompletionSession` line 387 does |
| A list UI with keyboard driving | `CompletionPopup` / `QuickPick` / `MenuBuilder` |
| "Unused" as a first-class idea | `DiagnosticTag.UNNECESSARY` |

The gap is narrow: a provider SPI, a Java-side table, and a section in the popup.

---

## 3. The contract — `core/src/main/java/com/crystalgui/text/lang/`

Three new types, mirroring the completion trio.

```java
public record CodeAction(String title, CodeActionKind kind, @Nullable ChangeSet edit,
                         @Nullable String commandId, boolean preferred) { }

public enum CodeActionKind { QUICK_FIX, REFACTOR, SOURCE }

public interface CodeActionProvider {
    CodeActionProvider NONE = (request, answer) -> answer.accept(Versioned.of(0, List.of()));
    void actionsAt(Request request, Consumer<Versioned<List<CodeAction>>> answer);

    record Request(int from, int to, List<Diagnostic> diagnostics, long version) { }
}
```

and `LanguageServices.codeActions()` defaulting to `NONE`, so an adapter that offers none says so by
saying nothing — the same three-tier absence the other providers use.

**`CodeAction` is data, with no behaviour.** That is what lets `core/` render and apply one without
knowing what Java is. Two carriers rather than one because there are genuinely two kinds:

- an **edit** — the overwhelming majority, and the only kind that is undoable for free;
- a **command id** — for a fix that is not a text edit (add a classpath entry, open a setting). Resolved
  through `CommandRegistry`, which already exists and already carries enablement.

`preferred` is LSP's `isPreferred`, and it is what the popup shows inline with an accelerator while
everything else hides behind *More actions…*.

> **The edit is expressed in offsets against `request.version`.** Applying it when the buffer has moved
> on is the one way this feature can corrupt a file, and it is silent — the offsets still resolve, they
> just name different text. The apply path therefore re-checks `isFresh` and re-requests rather than
> mapping the edit forward: a fix built from an AST that no longer matches the text is not worth
> salvaging.

---

## 4. The Java side — `language/.../java/JavaCodeActions.java`

One class implementing the SPI, and inside it the table:

```java
switch (problemId) {
    case IProblem.UnusedImport            -> removeImport(...);
    case IProblem.LocalVariableIsNeverUsed -> removeLocal(...);
    case IProblem.UnusedPrivateField       -> ...;
    case IProblem.UndefinedType            -> importCandidates(...);   // "Import X"
}
```

Notes that matter:

- **Named constants, never numeric literals or ranges.** `IProblem` is a published interface in the jar
  we already ship (`EcjSourceAnalyzer` imports it). AGENTS.md's warning is about the *ID ranges* being
  internal — individual constants are not.
- Java inlines `static final int` at compile time, so `IProblem.UnusedImport` becomes a literal and is
  safe on the band-8 loader even though we cannot detect the field's absence. Only use constants that
  have existed since JDT 3.x, which covers every fix worth having first.
- The fix builders have the `CompilationUnit` already — `EcjAnalysis` holds it, and `AttachedSources`
  proved the pattern of asking the AST rather than re-deriving from text.
- **Remove-an-import is a range delete including its line terminator**, which is why it must come from
  the AST node's extent and not from the diagnostic's range: the diagnostic covers the *name*, the fix
  covers the *statement*.

Start with the four in the screenshot — unused import, unused local, null-access, and the "compiler
option being ignored" INFO (which is a settings command, not an edit, and is the reason `commandId`
exists in the record at all).

---

## 5. The three surfaces, one model

All three ask the same provider and render the same `CodeAction` list.

1. **Hover popup** — a problem section above the declaration. `showDocumentationAt` resolves a
   `SymbolInfo` today and nothing else; it gains a parallel lookup of the diagnostics overlapping that
   offset, which is one `overlapping()` call. Layout follows IntelliJ: message, primary fix +
   accelerator, *More actions…*, rule, then the existing declaration and doc.
2. **Alt+Enter at the caret** — the full list, driven like `CompletionPopup`.
3. **Problems panel row** — the same list on a row's context menu.

Only the first needs new rendering. The other two are existing widgets pointed at the same data, and
that is the whole reason `CodeAction` carries a title and a kind rather than a widget.

> **A popup that shows the problem must still show the declaration.** Image 17 shows both, and the order
> matters: the problem is why you looked, the declaration is what you were looking at. A popup that
> replaces one with the other regresses hover.

---

## 6. Applying one

```
resolve the action  ->  is buffer still at request.version ?
                          no  -> re-request, do not apply
                          yes -> buffer.breakUndoCoalescing()
                                 buffer.edit(action.edit())
                                 buffer.breakUndoCoalescing()
```

Both breaks are deliberate: a fix must not merge into the typing run before it or the one after, or
Ctrl+Z takes back half a fix and half a sentence.

Nothing else is needed for undo — a `ChangeSet` through `buffer.edit` is already one `Edit` on the
document's `UndoStack`, which is where the history lives (never on the window).

---

## 7. Traps, collected

| Trap | Why it bites |
|---|---|
| A central table of "every action for error X" | `O(errors × contributors)`, never finished, and one shared file every fix has to edit. Contributors self-select; see §1 |
| Treating "no fix for this code" as a gap | ECJ has ~1000 problem ids. Unknown → nothing is the designed answer, not a TODO |
| Ranking by a computed relevance score | The completion list already paid for this: a folded number let brevity outrank proximity. Declared tiers, ties on insertion order |
| Applying an edit built against an older version | Offsets still resolve; they name different text. Silent corruption. Gate on `isFresh` |
| Putting fixes on `Diagnostic` | It is a value record that crosses to the panel and is compared by value; behaviour in it ends that, and costs a fix object per problem per compile |
| Switching on ECJ id *ranges* | Internal and unstable — AGENTS.md records this. Named constants only |
| Taking the fix's range from the diagnostic | The diagnostic covers the name, the fix covers the statement. Deleting the former leaves `import ;` |
| Computing actions on every diagnostic push | The reason LSP made it a request. Compute on hover/Alt+Enter only |
| A second matcher for "which problems are here" | `DecorationSet.overlapping` already answers it in live coordinates. Anything that re-derives from row/column will disagree the moment someone types |
| Losing the declaration from the hover popup | See §5 |

---

## 8. Staging

| Step | Deliverable | Exit |
|---|---|---|
| 1 | The contract: `CodeAction`, `CodeActionKind`, `CodeActionProvider`, `LanguageServices.codeActions()` | Compiles; `headlessTest` still has no engine and still behaves |
| 2 | `JavaCodeActions` with **one** fix (unused import) | A headless test asserts the `ChangeSet` deletes the whole statement |
| 3 | Apply path + version gate | A test proves a stale action is refused rather than applied |
| 4 | The hover popup's problem section | Hovering a warning shows message + primary fix + the declaration |
| 5 | Alt+Enter list, and the **shape-derived** actions (§1.2) | Every diagnostic offers *Suppress* / *Disable* / *Go to related* with no entry in any table |
| 6 | The remaining keyed fixes, one commit each | — |

Step 5 pairs the full list with the actions that need no table on purpose: build them together and the
merge is exercised from the first day, rather than being added later to a popup that has only ever shown
one contributor's answers.

Step 3 before step 4 deliberately: the gate is the part that can damage a file, and it is much easier to
test without a popup in the way.
