# `text.cursor` — VS Code's boundaries

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../../AGENTS.md), which keeps the rules every session needs.

#### Port the module boundaries too

`com.crystalgui.text.cursor` takes its boundaries from VS Code, though **not file-for-file** — the
mapping is worth stating because two of the five come from elsewhere in that tree:

| Ours | VS Code |
|---|---|
| `CursorColumns` | `common/core/cursorColumns.ts` |
| `MoveOperations` | `common/cursor/cursorMoveOperations.ts` |
| `TypeOperations` | `common/cursor/cursorTypeOperations.ts` |
| `LineOperations` | `contrib/linesOperations/browser/linesOperations.ts` — **not** `common/cursor/` |
| `MouseSelection` | `browser/controller/mouseHandler.ts` — **not** `common/cursor/` |
| `ColumnSelection` | `common/cursor/cursorColumnSelection.ts` |

Monaco's `common/cursor/` holds twelve files; the six we have no counterpart for are its orchestration
layer (`cursor.ts`, `cursorCollection.ts`, `oneCursor.ts`, `cursorContext.ts`, `cursorMoveCommands.ts`)
plus **one** remaining feature gap: `cursorAtomicMoveOperations.ts` — `editor.useTabStops` for *arrows*,
where a left or right arrow steps a whole indent unit through leading whitespace. Backspace already does
(`TypeOperations.backspaceFrom` counts visual columns), which is the half that was reported; the arrows
still move by one character.

> **Every column question in this package is a VISUAL one**, and that is the single most portable thing
> about it. A box selection computed from character offsets is not a box — two rows whose indentation
> differs in tabs have the same character column at different places, so the "rectangle" comes out as a
> ragged edge following the text. The same rule is why Backspace takes one tab rather than an
> indent's worth of characters, and why a paste is re-indented by columns rather than by string length.

> This is not tidiness. The same logic first went in as private methods on `TextEditor`, which reached
> **2556 lines, larger than the entire `com.crystalgui.text` package combined**, and could only be
> reached through a `UIDocument` with fonts, a style engine and an input handler. Extracting it exposed a
> real bug within minutes — deleting the *last* line left a blank line, because the last row has no
> trailing newline to take and must swallow the one before it instead. The widget test never caught it:
> it only ever deleted a middle line. **Porting the algorithms without the boundaries keeps the
> algorithms and throws away the testability that keeps them correct.**
