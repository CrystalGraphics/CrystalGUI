# P6.1.12 — Chrome

> **Status: brainstorming. Nothing here is decided.** This document exists to work out what "chrome"
> should mean for *this* application before any of it is built. The roadmap line it replaces —
> *"Toolbar, status bar, breadcrumbs, command palette"* — is questioned below rather than assumed.

---

## Contents

- [What "chrome" means](#what-chrome-means)
- [The constraint that reframes everything](#the-constraint-that-reframes-everything)
- [IntelliJ, classified](#intellij-classified)
- [The finding — the roadmap line lists the decorative half](#the-finding--the-roadmap-line-lists-the-decorative-half)
- [What chrome is actually for here](#what-chrome-is-actually-for-here)
- [Candidates, ranked](#candidates-ranked)
- [What makes IntelliJ *look* like IntelliJ](#what-makes-intellij-look-like-intellij)
- [Open questions](#open-questions)
- [**The Project Explorer**](#the-project-explorer)

---

## What "chrome" means

Browser term. Firefox's UI layer is literally the "chrome" — everything that is not the page. VS Code
calls the same thing the *workbench*; IntelliJ calls the pieces *tool windows*, the *navigation bar* and
the *status bar*. It is the frame around the content: always present, never the thing you came for, and
the whole difference between a text box and a tool.

The dock (6.1.11) is already the largest piece of it. 6.1.12 is what is left.

---

## The constraint that reframes everything

> *"its not really a full blown IDE just a script engine editor for js nashorn and java janino scripts
> or css stylesheets or stuff of that sort. Theres no processes or daemons that run, no gradle builds or
> debugging processes, its all part of the games runtime."*

That is not a minor scoping note — it deletes roughly half of IntelliJ's chrome outright, and it
**transforms** several of the remaining pieces into something with the same shape and a different
meaning. An editor whose artifacts are live objects in a running host is a different machine from one
that spawns compilers and attaches debuggers.

Three consequences worth stating before the inventory:

1. **There is no "run".** A script is not launched; it is *recompiled and swapped into a process that is
   already running*. The nearest IntelliJ analogue is not the green play button — it is **Apply Code
   Changes** (hot reload), which is a much less prominent button doing a much more important job here.
2. **There is no build output, but there is absolutely compiler output.** GLSL fails to compile, Janino
   throws at compile time, Nashorn throws at parse time, a stylesheet has a bad declaration. Those
   messages have to go *somewhere* and right now there is nowhere for them to go. The screenshot
   provided shows exactly this case in IntelliJ's console:
   `0(278) : error C1503: undefined variable "cg_ShadowParams"`.
3. **The host is remote.** The workspace (6.1.10) is server-hosted over RPC even in singleplayer. So
   "connected / disconnected / saving" is real state a user needs to see — a status concern that a
   local-disk IDE does not have and therefore does not model.

---

## IntelliJ, classified

Walking the provided screenshot top to bottom, and marking each piece **KEEP** / **TRANSFORM** / **DROP**
against the constraint above.

### Top bar

| Piece | Verdict | Reasoning |
|---|---|---|
| Hamburger → main menu | **KEEP** | The discoverable home for every command. Cheap: `Menu`/`MenuItem` exist |
| Project switcher | **KEEP (thin)** | 6.1.10 has a project identity (D12). One dropdown |
| VCS branch widget | **DROP** | No git in the game runtime |
| Run configuration selector | **TRANSFORM** | Becomes *what am I editing against* — the target entity/material/scene a script is bound to. See [open questions](#open-questions) |
| Run / Debug / Stop | **TRANSFORM → one button** | Collapses to **Apply**. No process to stop, nothing to attach to |
| Search Everywhere (magnifier) | **KEEP** | The command palette, plus file/symbol search |
| Settings gear | **KEEP** | 6.1.8's configurator already is this |

### Left edge — tool window stripe

| Piece | Verdict | Reasoning |
|---|---|---|
| Icon rail, click to toggle a tool window | **KEEP — and it is already scoped** | This is 6.1.11's deferred **D9** (stripe rails + auto-hide). The 6.1.11 open questions flagged it as *possibly load-bearing rather than a nicety* because screen space in Minecraft is scarcer than on a desktop. That guess now looks correct |

### Editor area

| Piece | Verdict | Reasoning |
|---|---|---|
| Tab bar, splits | **DONE** | 6.1.11 |
| Gutter — line numbers, fold arrows | **DONE** | 6.1.6 / 6.1.7b |
| Gutter — breakpoints | **DROP** | No debugger |
| **Inspection widget** (top-right, `✓ 282` / `⚠ 1`, next/prev arrows) | **KEEP — high value** | Nothing like it exists |
| **Error stripe** in the scrollbar | **KEEP — high value** | Every problem in a 3000-line file, visible without scrolling. Arguably IntelliJ's single best affordance |
| Inline squiggles | **KEEP — high value** | 6.1.1's `::highlight()` is *exactly* the mechanism, already shipped and unused for this |
| Sticky lines / breadcrumbs in editor | **KEEP (later)** | Needs a symbol provider; see the grammar question |

### Right and bottom panels

| Piece | Verdict | Reasoning |
|---|---|---|
| Gradle tool window | **DROP** | No build system |
| Debug tool window — frames, threads, variables | **DROP** | No debugger |
| Debug tool window — **the console pane** | **TRANSFORM → Output panel** | Host logs, compile output, `print()` from a script. The one part of the debugger surface that survives, and it survives as the most-used panel in the app |

### Status bar

| Piece | Verdict | Reasoning |
|---|---|---|
| Navigation bar (`CrystalGUI > gl-debug-harness > .settings`) | **KEEP** | Cheap. File-path segments are free today |
| Caret `133:52` | **KEEP** | `TextEditor` already knows this |
| `LF`, `UTF-8` | **KEEP** | 6.1.10 resolved both (G1, G2) and currently shows neither |
| Memory / background-task progress | **TRANSFORM (thin)** | No background *tasks*, but FS operations are async RPC — "saving…", "reconnecting…" is real |
| Notification bell / event log | **KEEP** | Where an error goes when no panel is open |

---

## The finding — the roadmap line lists the decorative half

The roadmap says: *toolbar, status bar, breadcrumbs, command palette*.

Every one of those is **orientation** chrome — it tells you where you are and how to reach a command.
Not one of them is **feedback** chrome — none of them tells you whether the thing you just wrote works.

That omission is an artifact of when the line was written. 6.1.12 was drafted before there was an editor
to *have* problems in and before the workspace existed to *load* anything. Now that both exist, the gap
is visible: **there is no `Diagnostic` type anywhere in the codebase.** Verified — nothing under
`com.crystalgui` models a severity, a problem range, or a compiler message.

So the honest version of this item is larger than the roadmap line and differently weighted. That is the
main thing this document is for.

---

## What chrome is actually for here

One loop, and everything should be ranked by how much it shortens or clarifies it:

```
        ┌──────────────────────────────────────────┐
        ▼                                          │
    edit  ──►  apply  ──►  the host does something │
                              │                    │
                              ├── it worked ───────┤
                              └── it failed ───────┘
                                    │
                              WHERE DO I SEE WHY?
```

The last box is currently unanswered, and it is where nearly all the value is. A person writing GLSL
inside Minecraft does not need a VCS widget; they need to know that line 278 references a uniform that
does not exist, and they need to get to line 278 in one click.

Everything else — palette, breadcrumbs, menus — is genuinely useful and genuinely secondary.

---

## Candidates, ranked

Value is judged against the loop above. Cost is judged against what already exists in the repo.

### Tier 1 — the feedback loop

| # | Item | Value | Cost | Notes |
|---|---|---|---|---|
| C1 | **`Diagnostic` model** — severity, range, message, source, code | ★★★★★ | Low | A record plus a per-document registry. Everything below depends on it. **Not editor-specific**: a shader-graph node can carry one too |
| C2 | **Inline squiggles** | ★★★★★ | Low | `::highlight()` (6.1.1) is the mechanism and it already ships. Styling lives in CSS, ranges in Java — exactly what that API was built for |
| C3 | **Error stripe in the scrollbar** | ★★★★★ | Medium | A new `EditorViewPart`. Needs a model→stripe projection that survives folding (`ProjectedLines` already solves the equivalent problem for view lines) |
| C4 | **Inspection widget** — count + next/prev | ★★★★☆ | Low | Two commands and a small readout. Rides on C1 |
| C5 | **Output / console panel** | ★★★★★ | Medium | A dock panel over an append-only buffer. Wants ANSI-free styled runs, click-to-navigate on `file:line`, and a cap on retained lines |
| C6 | **Problems panel** | ★★★★☆ | Low–Medium | `TableView` (6.1.5) over the diagnostic set, grouped by file. Mostly wiring once C1 exists |
| C7 | **Apply action + result feedback** | ★★★★★ | ? | The single most important button in the app. Cost unknown — depends entirely on what the host exposes. **See open questions** |

### Tier 2 — orientation

| # | Item | Value | Cost | Notes |
|---|---|---|---|---|
| C8 | **Status bar** with a contribution API | ★★★★☆ | Low | Alignment + priority, like VS Code's `StatusBarItem`. Without a contribution API every feature that wants a segment has to reach into the layout |
| C9 | **Command palette** | ★★★★☆ | **Very low** | Every part exists: `SearchMatcher` (ported from VS Code `filters.ts`), `SearchField`, `ListView`, `Popover`, `Keymap.acceleratorsFrom`, `::highlight()`. This is assembly, not construction |
| C10 | **Navigation bar / breadcrumbs** | ★★★☆☆ | Low (paths) / Blocked (symbols) | File-path segments are free. The symbol tail needs a grammar — 6.1.7's open question |
| C11 | **Main menu** | ★★★☆☆ | Low | `Menu`/`MenuItem` exist. Discoverability for everything the palette hides |

### Tier 3 — space and structure

| # | Item | Value | Cost | Notes |
|---|---|---|---|---|
| C12 | **Tool window stripes + auto-hide** (6.1.11 D9) | ★★★★☆ | Medium | Screen space in-game is scarce. This may matter more here than it does in IntelliJ |
| C13 | **Toolbar** with overflow-to-menu | ★★★☆☆ | Medium | The overflow measurement pass is the only non-trivial part. Could ship without it |
| C14 | **Notifications / toasts** | ★★★☆☆ | Low | Where a failure goes when the Problems panel is closed |

### Tier 4 — deliberately noted as *not* now

Recently-used ordering in the palette (needs a preference store), symbol breadcrumbs (needs the grammar),
multi-row tab wrapping, floating tool windows.

---

## What makes IntelliJ *look* like IntelliJ

Worth separating, because the stated preference is about **looks**, and almost none of what produces that
look is a missing widget. It is restraint plus one repeated idiom.

1. **The tool-window frame.** Every panel is: a thin header bar with a title on the left, small action
   icons on the right, and a 1px separator — then content. Same frame for Project, Gradle, Debug. That
   single repeated shape is most of the visual identity, and it is a CSS rule over an element we already
   have.
2. **The editor dominates; everything else recedes.** Panels are one or two shades off the editor
   background, never bordered heavily, never accented. Contrast is spent on *code*, not on frames.
3. **One accent colour, used sparingly** — selection, the active tab underline, focus. Not on buttons,
   not on headers.
4. **Content density.** Small type, tight row heights, thin scrollbars. The status bar is ~22px and quiet.
5. **Icons carry the rails; text carries the content.** The left stripe is icon-only with tooltips.

None of that needs new engine capability. It is a stylesheet — an `ide.css` sitting beside `ore.css` and
`graph.css` — plus the discipline to keep pixel values out of Java, which the widget conventions already
enforce.

> **The one real widget gap for the look** is the tool-window header frame, and it is small: a titled,
> collapsible container with an action slot. Everything else is theming.

---

## Open questions

These change what gets built, so they are worth answering before anything is.

**Q1 — What does "Apply" actually call?**
The highest-value item (C7) is entirely shaped by this. Is there a host-side entry point that takes a
script/stylesheet/shader source and returns success-or-diagnostics? Is it synchronous, or async over the
same RPC as 6.1.10? Does it apply one file or the whole workspace? Without this the Apply button is a
placeholder.

**Q2 — Where do diagnostics come from, and in what format?**
GLSL comes back from the driver as `0(278) : error C1503: …`. Janino and Nashorn throw Java exceptions
with line info. A stylesheet warning currently just goes to `CrystalGuiCore.LOGGER`. Do these get
normalised server-side into one shape, or does the client parse each? **The `Diagnostic` record's fields
are a one-way door** — worth settling before C1 is written.

**Q3 — Is there an "active target"?**
IntelliJ's run-configuration slot is prime real estate in the top bar. Its analogue here would be *what
this script is attached to* — an entity, a material, a scene. If that concept exists, it belongs there.
If it does not, the top bar gets simpler and shorter.

**Q4 — One document concept or several?**
A `.glsl` file, a `.css` file, a Janino script and a shader **graph** all want to open as tabs in the same
dock. The dock already takes an opaque `typeId` + state, so it does not care — but the status bar,
breadcrumbs and Problems panel all want to ask a document "what are you?" Does a `Document` abstraction
appear here, or does each panel type answer for itself?

**Q5 — How much screen is this?**
Fullscreen-ish, or a windowed panel inside the game? It decides whether C12 (auto-hide stripes) is
essential or optional, and it decides the density targets for the theme.

**Q6 — Is the console one stream or several?**
Host log, script `print()`, and compile output are three different things. IntelliJ gives each a tab.
One merged stream is simpler and much worse to read once anything is noisy.

---

## Nothing is decided

The tiers above are a proposal to argue with, not an order to execute. The two things this document
asserts rather than proposes:

- **The roadmap line is incomplete** — it lists orientation chrome and omits feedback chrome entirely,
  and feedback chrome is where the value is.
- **The palette is nearly free** — every component exists, which makes it a good early win regardless of
  how the rest is prioritised.

---

# The Project Explorer

> **Status: design proposal, sourced.** Every behavioural rule below is attributed. Where a default is
> quoted it was read out of the source named, not recalled; where something is recalled and *not* yet
> verified it says so. The rule for this section is the repo's own: **port, do not reinvent.**

## Contents

- [Why this is the second-largest piece of chrome](#why-this-is-the-second-largest-piece-of-chrome)
- [IntelliJ's menu, item by item](#intellijs-menu-item-by-item)
- [The explorer itself, beyond the menu](#the-explorer-itself-beyond-the-menu)
- [What already exists here](#what-already-exists-here)
- [The gaps — new frameworks and stack extensions](#the-gaps--new-frameworks-and-stack-extensions)
- [The ported rules, with sources](#the-ported-rules-with-sources)
- [Ranked](#ranked)
- [Decisions (Q7–Q11)](#decisions-q7q11)

---

## Why this is the second-largest piece of chrome

The dock is the largest. This is next, and it is worth saying why before the inventory: **the explorer is
the only place in the application where a file can be created, renamed, moved or destroyed.** Everything
else — editors, the graph, the palette — operates on files that already exist. That makes it the one
panel whose bugs are not cosmetic. A misaligned label is a nuisance; a delete that takes the wrong path,
or a rename that strands an open editor on a file that no longer exists, is data loss in an environment
with **no version control to fall back on**.

That last clause is the whole design constraint. IntelliJ can be relaxed about deletion because Git is
underneath it. Here there is no Git, no OS trash, and no filesystem the user can go poke at with another
tool — the workspace is server-hosted over RPC (6.1.10) and may not even be on the same machine.
**Whatever safety net exists, we build.**

---

## IntelliJ's menu, item by item

Every entry in the provided screenshot, against the constraint from
[the section above](#the-constraint-that-reframes-everything) — a script/asset editor inside a running
game, no build system, no processes, no debugger, no VCS.

### Take, essentially as-is

| Item | Notes |
|---|---|
| **New ▸ File** | With a name prompt. The submenu's *content* is ours — see below |
| **New ▸ Directory** | |
| **New ▸ Scratch File** | IntelliJ's scratch buffers are a genuinely good fit: somewhere to try a snippet that is not part of the project |
| **Cut / Copy / Paste** | The three that make an explorer feel like a file manager. Cut+Paste is a *move*; Copy+Paste is a *copy* |
| **Copy Path/Reference…** | A submenu, not one item — see [Copy Path is a submenu](#copy-path-is-a-submenu) |
| **Rename…** | `Shift+F6`. Inline in the row, not a modal |
| **Delete** | With confirmation, and with an undo path |
| **Find in Files…** | `Ctrl+Shift+F`. **The highest-value item on the whole menu** and the one with the largest cost |
| **Replace in Files…** | The other half of the same machine. Cheap *once* search exists; dangerous without undo |
| **Reload from Disk** | Non-negotiable here in a way it is not in IntelliJ: the workspace is remote and another actor can change it underneath us |
| **Open In ▸** | Reduced to "Open" and "Open to the Side" — our dock already does splits |
| **Compare With…** | Wants a diff view. Defer, but it is the natural consumer of Local History |
| **Local History ▸** | See [the safety net](#g4--the-safety-net-is-not-optional). Promoted from "nice" to structural by the absence of VCS |

### Transform — same shape, different meaning

| Item | Becomes |
|---|---|
| **Reformat Code** | A per-language formatter hook. `LanguageRegistry` exists; nothing behind it formats yet. Keep the slot, defer the work |
| **Analyze ▸** | Collapses into the existing `Diagnostic` model (C1). "Analyze" here means "recompile this and show me the problems", which is the Apply loop (C7) pointed at one file |
| **Mark Directory As ▸** | No modules, but there *is* a real question of which folders are script roots, which are assets, which are excluded. Probably a workspace config file rather than a menu |
| **Bookmarks ▸** | Worth keeping as a concept, but it belongs to the editor rather than the explorer |

### Drop outright

| Item | Why |
|---|---|
| **New ▸ Module**, **Open Module Settings**, **Load/Unload Modules**, **Remove Module** | No module system |
| **Build / Rebuild Module** | No build |
| **Run / Debug Tests**, **More Run/Debug** | No processes, no debugger — the constraint's first casualty |
| **Find Usages** | Needs a symbol index and a grammar. That is 6.1.7's open question, not this one |
| **Refactor ▸** | Same. Rename-file is not a refactor without a resolver |
| **Optimize Imports** | Language-specific and index-dependent |
| **Git ▸**, **Create Gist…** | No VCS |
| **External Tools**, **Repair IDE on File**, **Analyze Dependencies…** | Desktop-IDE concerns with no in-game analogue |
| **Paste from History…** | A clipboard ring is an editor feature, and a separate one |

### New ▸ is ours, not IntelliJ's

IntelliJ's submenu lists *its* file types — Kotlin Script, Dockerfile, HTTP Request, Version Catalog.
Ours should be driven by **`LanguageRegistry`**, which already maps extension → language + tokenizer, so
the submenu is generated rather than hand-maintained and a new language gets an entry for free.

Proposed content, all of which the registry already knows or trivially could:

- File… (free-form name; language inferred from the extension typed)
- Directory…
- **GLSL Shader** — `.glsl` / `.frag` / `.vert`
- **Shader Graph** — a `.shadergraph` document, which is the thing this whole project exists for
- **Script** — JS (Nashorn) / Java (Janino), per whatever the host registers
- **Stylesheet** — `.css`
- Scratch File

Each entry is a **template**: a name pattern plus starting content. IntelliJ backs these with Velocity
templates, which is far more machinery than is wanted. A string with a couple of `{name}`-style
substitutions covers every case above and can grow later.

---

## The explorer itself, beyond the menu

The menu is the visible half. These are what make a tree feel like a file explorer, and none of them
appear on a right-click menu:

| # | Behaviour | Source |
|---|---|---|
| 1 | **File-type icons** and per-language colour | IntelliJ and VS Code both |
| 2 | **Folders-first sort**, then lexicographic — configurable | `explorer.sortOrder` |
| 3 | **Multi-select** with Ctrl (toggle) and Shift (range) | Every file manager ever shipped |
| 4 | **Type-to-filter** in the tree | IntelliJ speed search; VS Code list keyboard navigation |
| 5 | **Drag to move, modifier-drag to copy**; dropping on a file targets its parent folder | VS Code `ExplorerDragAndDropController` |
| 6 | **Inline rename** — an editor in the row, not a modal | Both |
| 7 | **Reveal active file** — "scroll from source" | `explorer.autoReveal` |
| 8 | **Dirty badge** on files with unsaved changes | VS Code decorations |
| 9 | **Read-only badge** where `CgFileCapability.READONLY` | Ours — the capability exists and nothing surfaces it |
| 10 | **Compact folders** — `a/b/c` on one row when each has a single child | `explorer.compactFolders`, default `true` |
| 11 | **Live refresh** on external change | `WATCH`/`UNWATCH` + `onFileChanged` exist; nothing consumes them |
| 12 | **Empty / error / disconnected states** | Ours. The workspace is remote, so "not connected yet" is a real state a local IDE never has to draw |

---

## What already exists here

Being honest about this is what makes the cost column mean anything.

| Need | Status |
|---|---|
| Multi-select, range select, `onSelectionChanged`, `onRowActivated` | **Yes** — `ListView` / `SelectionMode.MULTIPLE`, and `TreeView` extends it. *Configuration, not code* |
| Menus with submenus, checkable items, separators | **Yes** — `Menu` / `MenuItem` |
| Light dismiss, Escape, anchored placement | **Yes** — `Popover` + `AnchoredPlacement` |
| Commands, keymap, accelerators, palette entries | **Yes** — every menu item should be a `Command`, free |
| Fuzzy matching for a filter | **Yes** — `SearchMatcher`, already ported from VS Code `filters.ts` |
| Drag machinery — capture, payload, ghost, threshold, Escape-cancel | **Yes** — `UIDragController`. But no *row* drag in `ListView`/`TreeView` |
| Undo primitives | **Yes** — `Edit`, `CompositeEdit`, `UndoStack`. But per-**document**, which is the crux |
| Confirmation dialogs | **Yes** — `Dialog` + `DialogManager` |
| Extension → language | **Yes** — `LanguageRegistry` |
| Watch / change notification | **Yes** — `WorkspaceProtocol.WATCH`/`UNWATCH`, `WorkspaceClient.onFileChanged` |
| `delete` and `rename` on the **server** | **Yes** — `WorkspaceService.delete/rename` are implemented |
| `delete` and `rename` over the **wire** | **No** — absent from `WorkspaceProtocol`, `WorkspaceRpc` and `WorkspaceClient` |

That last row is the headline finding. The service can already do it; the protocol simply never grew the
two methods. **Rename, Delete, Cut+Paste and drag-to-move are all blocked on the same small gap.**

---

## The gaps — new frameworks and stack extensions

### G1 · Filesystem protocol completion

`WorkspaceRpc` wires `PROJECTS, MANIFEST, READ, WRITE, CREATE, MKDIR, WATCH, UNWATCH`. Add:

| Method | Server side | Notes |
|---|---|---|
| `DELETE` | exists | `(path, recursive)`. Wants an etag for the same reason `WRITE` does — deleting a file that changed underneath you is the same class of mistake as overwriting one |
| `RENAME` | exists | `(from, to, overwrite)`. Covers move; within a workspace a rename *is* a move |
| `COPY` | **missing** | `CgFileSystem` has no `copy`, though `CgFileCapability.FILE_FOLDER_COPY` is declared. For a file, client-side read+create works; for a **directory** it must be server-side or it is N round trips |
| `SEARCH` | **missing** | See G6 |
| `STAT` | partial | `CgFileSystem.stat` exists but is not exposed. Wanted for "does this already exist" before an overwrite |

Every one of these is a `WRITE` under `WorkspacePermission`, which already models exactly the right
distinction (`READ` / `WRITE`) and needs no change.

### G2 · A working-copy-aware file service — *this is the important one*

**Port: VS Code's `IWorkingCopyFileService`.**

The problem it exists for, in its own words: *"any operation that would leave a stale dirty working copy
behind will make sure to revert the working copy first."* `IFileService` moves bytes; it knows nothing
about editors holding unsaved changes to those bytes.

The three rules it enforces, which we would otherwise find one bug at a time:

- **Move** — soft-revert dirty copies at *both* source and target before touching disk
- **Copy** — revert dirty copies at the target only
- **Delete** — revert every dirty copy under the deleted resource first

It also defines the event triple `onWillRun` / `onDidRun` / `onDidFail`, and a **participant** mechanism
letting other subsystems join an operation before it commits.

We have the same hazard in a smaller frame: `Workbench.editors` is a `Map<CgPath, TextEditor>` and
*nothing* updates it when a path changes. Rename a file with its editor open today and the map keys on a
path that no longer exists — the tab keeps its old title, Ctrl+S writes to the old name, and opening the
new name produces a second editor for the same file.

**Not optional, and not big.** A `WorkspaceFileService` sitting between `Workbench` and `WorkspaceClient`,
owning the editor map, is the whole of it.

### G3 · Undo for file operations

**Port: VS Code's explorer undo.** `explorer.enableUndo` defaults to **`true`** and `explorer.confirmUndo`
to `'Default'` — so undoing a file operation is a shipped, on-by-default behaviour in the editor most
people reading this have open, not an exotic idea.

It collides head-on with a load-bearing invariant in `AGENTS.md`:

> *An `UndoStack` belongs to a **document**, not a `UIWindow`.*

That invariant is right and must not be weakened. A file operation is not a document edit, so it does not
belong on any document's stack. VS Code's resolution is a **second, workspace-scoped** undo source: file
edits go on a workspace stack, text edits stay on their document's, and Ctrl+Z resolves to whichever
scope has focus. That is precisely what `UndoScope.nearest` already does, walking outward from the focused
element — **the mechanism is in place.** What is needed is a `WorkspaceUndoScope` at the explorer and
`Edit` implementations for create / delete / move / copy.

The honest caveat: undoing a *delete* requires the bytes back. Which is G4.

### G4 · The safety net is not optional

With no VCS and no OS trash, a delete is final. Two battle-tested answers, and they compose.

**(a) Trash.** VS Code's `files.enableTrash` defaults to **`true`** — delete means *move to trash*, and
Shift+Delete destroys. There is no OS trash on a game server, so the server owns a store **outside every
project root**, keyed by project id, reached through `DELETE` / `RESTORE` / `PURGE` / `TRASH_LIST`. The
client never sees a trash path. See [Q8](#q8--trash-lives-outside-the-project-server-side) for why not
`.trash/` inside the project, and for the manifest fields.

**(b) Local History.** IntelliJ's, which is explicitly *not* a VCS: revisions recorded automatically on
edit / save / refactor events, kept for **5 working days by default** (`localHistory.daysToKeep`),
existing precisely so you can *"restore deleted files… even if no version control is enabled for your
project yet."* That sentence describes our situation exactly.

Local History is the larger build, and the one that also unlocks **Compare With…**. Trash is small and
buys most of the safety. **Recommend: trash now, Local History as its own line item.**

### G5 · A context-menu framework

There is no right-click → menu path anywhere in the engine. `Menu` knows how to be a popover with
submenus; nothing opens one at the pointer on a secondary press, and nothing builds one from commands.

Wanted, and small:

- `ContextMenuController` — secondary press → build → show at pointer, through `AnchoredPlacement`
- A **command-driven** builder, so every item is a `Command` with an `enabledWhen` and a rendered
  accelerator. This is what stops the menu, the palette and the keystroke disagreeing — the argument
  `UndoCommands` already makes in its own javadoc
- **Dim, do not hide**, disabled items — the lesson already learned here when the palette listed 1 of 9
  commands because every `enabledWhen` walks up from focus

### G6 · Search

**Port the query shape from VS Code's `ISearchService`.** `ITextQuery` carries `folderQueries`,
`includePattern` / `excludePattern`, `maxResults`, `maxFileSize`, and a `contentPattern` (`IPatternInfo`)
with `isRegExp` / `isCaseSensitive` / `isWordMatch` / `wordSeparators` / `isMultiline`. Results are
`ITextSearchMatch` carrying preview ranges, plus `ITextSearchContext` for surrounding lines; completion
reports **`limitHit`** and `stats`.

Two details worth copying deliberately:

- **Results stream** through `onProgress` rather than arriving as one array. A workspace search that
  blocks until complete is a workspace search that appears hung
- **`limitHit` is part of the result.** A truncated search that does not say so is worse than one that
  refuses — the rule this repo already applies to silently-capped work

Search runs **server-side**, since the client has no filesystem. So this is a genuinely new RPC with a
streaming response, and the largest single item in this document.

### G7 · Tree and list capabilities

| Gap | Port from |
|---|---|
| Row **drag & drop** in `ListView` / `TreeView` | VS Code `ExplorerDragAndDropController`: modifier means copy, dropping on a file targets its parent, `explorer.confirmDragAndDrop` defaults `true` |
| **Inline rename** editor in a row | Both IDEs. Escape cancels, Enter commits, invalid names rejected before the RPC goes out |
| **Type-to-filter** | `SearchMatcher` is ported already; the tree needs to consume it |
| **Decorations** — badge and colour per row | VS Code `IDecorationsProvider`. A provider API, so "dirty", "read-only" and "has errors" are three independent contributors rather than three special cases inside the row renderer |

### G8 · A preferences store

Everything in [the sourced table](#the-ported-rules-with-sources) is a *setting* in VS Code, and we have
nowhere to put one. Even a small typed store unblocks this section, the palette's recently-used ordering
(already noted as blocked in Tier 4) and the keymap.

---

## The ported rules, with sources

The second pass the brief asked for: the specific behaviours, and where each comes from.

### From VS Code — read out of `files.contribution.ts`

Exact ids and exact defaults, not paraphrase.

| Setting | Default | Why we want it |
|---|---|---|
| `explorer.confirmDelete` | `true` | Destructive, and irreversible here |
| `explorer.confirmDragAndDrop` | `true` | A drag is easy to do by accident |
| `explorer.enableDragAndDrop` | `true` | |
| `explorer.enableUndo` | `true` | Settles G3 — file operations **are** undoable |
| `explorer.confirmUndo` | `'Default'` | Three levels: `Verbose` / `Default` / `Light` |
| `explorer.autoReveal` | `true` | The third value, `focusNoScroll`, is the nice one: reveal without yanking the viewport |
| `explorer.autoRevealExclude` | `{'**/node_modules': true, …}` | Reveal has to be suppressible per glob |
| `explorer.sortOrder` | `'default'` | Full set: `default`, `mixed`, `filesFirst`, `type`, `modified`, `foldersNestsFiles` |
| `explorer.sortOrderLexicographicOptions` | `'default'` | `default` / `upper` / `lower` / `unicode` — case handling is a real argument, so it is a setting |
| `explorer.sortOrderReverse` | `false` | |
| `explorer.incrementalNaming` | `'simple'` | Paste-collision naming. `simple` appends "copy"; `smart` increments a trailing number; `disabled` |
| `explorer.compactFolders` | `true` | The `a/b/c`-on-one-row collapse |
| `explorer.copyRelativePathSeparator` | `'auto'` | `/`, `\`, `auto` |
| `explorer.copyPathSeparator` | `'auto'` | |
| `explorer.decorations.colors` | `true` | The provider API in G7 |
| `explorer.decorations.badges` | `true` | |
| `explorer.autoOpenDroppedFile` | `true` | |
| `explorer.fileNesting.enabled` | `false` | Off by default even in VS Code — worth knowing before building it |
| `files.enableTrash` | `true` | Settles G4(a) |

#### Copy Path is a submenu

Two separate settings exist for the two separators, which is the tell. IntelliJ's *Copy Path/Reference…*
offers absolute path, path from content root, filename, and a reference form. Ours wants at minimum
**absolute** (`project:dir/file.ext`) and **relative to project root** — and the separator belongs in a
setting, because a path pasted into a script and a path pasted into a chat message want different ones.

### From VS Code — architecture

| Rule | Source |
|---|---|
| Revert dirty copies *before* a move or delete, at both source and target | `IWorkingCopyFileService` |
| `onWillRun` / `onDidRun` / `onDidFail` around every file operation | same |
| Participants may join an operation before it commits | same |
| Search streams through `onProgress`; completion reports `limitHit` | `ISearchService` / `ISearchComplete` |
| Fuzzy filtering tiers | `filters.ts` — **already ported** as `SearchMatcher` |

### From IntelliJ

| Rule | Detail |
|---|---|
| **Local History** is not a VCS and does not pretend to be | Automatic revisions on edit / save / refactor; **5 working days** default retention (`localHistory.daysToKeep`); documented as able to *"restore deleted files… even if no version control is enabled"* |
| **Scratch files** live outside the project tree | A separate root, so a snippet is not an accidental asset |
| **Speed search** — type to filter, no search box | The tree filters as you type, with matched runs highlighted |
| **Shift+F6 = Rename**, inline and in place | |
| **Safe Delete** — refuse when something still references it | **We cannot do this**, and should not pretend to: it needs a usage index. Worth recording as exactly why plain Delete here needs a *stronger* safety net than IntelliJ's, not a weaker one |

### Recalled but *not* verified — check before building

Flagged rather than asserted, because the value of the rest is that it is sourced:

- `workbench.list.openMode` (`singleClick` vs `doubleClick`) and preview/italic tabs — the
  single-versus-double-click question is real and the defaults are not confirmed here
- VS Code's exact drag modifier per platform for copy-on-drop
- Unity's Project-window filter syntax (`t:Texture` and similar) — relevant only if the tree ever becomes
  asset-oriented rather than file-oriented

---

## Ranked

Same scale as the tiers above. Cost is judged against what the repo already has.

### Tier 1 — the explorer stops being read-only

| # | Item | Value | Cost | Notes |
|---|---|---|---|---|
| E1 | **`DELETE` + `RENAME` over RPC** | ★★★★★ | **Very low** | The server implements both already. Unblocks E2–E5 |
| E2 | **`WorkspaceFileService`** (G2) | ★★★★★ | Low | Owns the editor map. Without it, rename strands open editors — silently |
| E3 | **Context-menu framework** (G5) | ★★★★★ | Low | `Menu` exists; this is a controller plus a command-driven builder |
| E4 | **New ▸ / Rename / Delete / Copy Path** | ★★★★★ | Low | The four that make it usable. Rename inline, delete confirmed |
| E5 | **Trash + file-operation undo** (G3, G4a) | ★★★★★ | Medium | `UndoScope.nearest` already resolves scopes; trash makes undo-delete a move |

### Tier 2 — it starts to feel like an explorer

| # | Item | Value | Cost |
|---|---|---|---|
| E6 | Multi-select + Cut / Copy / Paste | ★★★★☆ | Low (selection is config; paste needs `COPY`) |
| E7 | Icons + decorations provider — dirty, read-only, errors | ★★★★☆ | Low–Medium |
| E8 | Type-to-filter | ★★★★☆ | Low — `SearchMatcher` is ported |
| E9 | Reveal active file + live refresh from `onFileChanged` | ★★★★☆ | Low — both halves exist, nothing consumes them |
| E10 | Drag to move / modifier-drag to copy | ★★★☆☆ | Medium |
| E11 | Sort order, folders-first, compact folders | ★★★☆☆ | Low (needs G8) |

### Tier 3 — the big ones

| # | Item | Value | Cost |
|---|---|---|---|
| E12 | **Find in Files** (G6) | ★★★★★ | **High** — new streaming RPC, server-side matcher, results panel |
| E13 | Replace in Files | ★★★★☆ | Medium *on top of* E12, and must not ship before undo |
| E14 | **Local History** (G4b) | ★★★★☆ | High — but it is the only real safety net, and it unlocks Compare With… |
| E15 | Preferences store (G8) | ★★★☆☆ | Low, and it unblocks far more than this panel |

### Tier 4 — noted, not now

File nesting (off by default even in VS Code), bookmarks, formatter hooks, "Mark Directory As", a
standalone diff viewer, a scratch-file root.

---

## Decisions (Q7–Q11)

Answered 2026-08-04. These are settled; the reasoning is kept because the *why* is what stops each one
being re-litigated by the next person who finds the tradeoff non-obvious.

### Q7 — Double-click opens. **Decided.**

Single click selects. Double click opens. `Enter` is the keyboard equivalent, and `Space` is free for a
preview later if one is ever wanted.

**This deletes a question rather than answering it.** Preview tabs — VS Code's italic, replace-in-place
tab — exist to disambiguate *single*-click-to-open: without them, walking the tree with arrow keys buries
you in tabs. IntelliJ has no preview tabs because it opens on double-click and does not need them. Taking
the double-click rule means the dock does not need preview tabs either, and Q7 stops being a dock
question.

Two consequences to implement:

- `ProjectFileTree` currently opens on **single** click (`onMouseDown` → `onFileChosen`). That becomes
  select-only, with activation on `onRowActivated`, which `ListView` already emits for double-click.
- Single click must still *select*, and selection must be visible, because it becomes the thing every
  context-menu command acts on.

### Q8 — Trash lives **outside** the project, server-side. **Decided.**

The alternative — `.trash/` under the project root — fails on four counts, and each one is enough on its
own:

1. **Every other actor sees it.** The workspace is shared; a deleted file reappearing as
   `.trash/foo.glsl` in someone else's tree is worse than it being gone
2. **It ships.** A project directory in a game is a resource pack, an asset folder, a datapack. Deleted
   work would end up distributed
3. **It changes the project's content.** Anything hashing or manifesting a project now has to special-case
   a directory that is not part of it
4. **The project may be `READONLY`.** A trash inside a read-only project cannot exist, and that is exactly
   a case where you still want deletes from *other* writable projects to be recoverable

The precedent is unanimous: **VS Code** delegates to the OS trash (`files.enableTrash`, default `true`),
**IntelliJ** keeps Local History in the IDE's own system directory, **Unity** uses the OS trash. Not one
of them puts it in the project.

So the server owns a store outside every project root, keyed by project id, and **the client never sees a
trash path.** That is what makes the protocol clean:

| Method | Meaning |
|---|---|
| `DELETE(path, etag)` | **Move to trash.** The default, and what the Delete key does |
| `RESTORE(trashId)` | Put it back at its recorded original path. This is what undo calls |
| `PURGE(trashId)` | Destroy permanently. What `Shift+Delete` does, behind a harder confirmation |
| `TRASH_LIST(project)` | For a "Recently Deleted" view |

Each entry records **original path, deleted-at, actor, etag, size**. Actor matters because the workspace
is multi-user: "who deleted this" is a question that will be asked.

**Retention** is by age *and* total size, swept at server start and periodically — IntelliJ's Local
History bounds itself the same way (5 working days by default) precisely because an unbounded history is
a disk leak nobody notices until it is large. Defaults to argue about, not to settle here.

> **Why `RESTORE` rather than letting the client compose a `RENAME`.** The client would have to know the
> trash path, which is exactly the thing it must not know — it would then be able to write *into* the
> trash, list it as an ordinary directory, and race another client's sweep. One opaque id keeps the store
> the server's business.

### Q9 — Already decided in the filesystem plan. **Cross-reference, not a new answer.**

`CrystalGUI_P6.1.10_FILESYSTEM_PLAN.md` § *Conflict handling* settles it, and matches the flow described:
save → server → `fs.changed` to every other client → the receiving client compares the new revision
against its base.

- **No unsaved local edits** → reload silently. No dialog for the common case
- **Unsaved local edits** → IntelliJ's *File Cache Conflict* dialog, wording included:
  `Load File System Changes` · `Keep Memory Changes` (`Show Difference` deferred until a diff exists)

Two things the explorer adds on top, which that section does not cover because they are not *file*
conflicts:

- **`DELETE` and `RENAME` need the same etag guard as `WRITE`.** Deleting a file that changed underneath
  you is the same class of mistake as overwriting one, and the plan's own `FILE_MODIFIED_SINCE` layering
  applies unchanged
- **"Someone else deleted this while you had it open"** has no UI at all. It is not the cache-conflict
  dialog — there is nothing to load — so it wants its own answer: keep the editor open and dirty, mark the
  tab, and offer *Save As* to recreate the file. That is what VS Code does for a deleted-on-disk dirty
  editor

### Q10 — Both. Ported as IntelliJ's **view-mode selector**. **Decided.**

The Project tool window's header dropdown — *Project* / *Packages* / *Project Files* / *Scratches* — is
exactly this, and it is the reason "both" is cheap rather than a fork: one tree widget, one selection
model, one context menu, **different `TreeDataSource` behind them**.

| Mode | Shows |
|---|---|
| **Files** | The raw tree. Every file, nothing hidden, nothing grouped. The truth, and what you want when something has gone wrong |
| **Assets** | Asset-oriented: sidecars hidden, related files grouped under their primary (Unity's Project window; VS Code's `explorer.fileNesting` is the same idea in half measure) |

Make it a small SPI — `ProjectViewMode` with a name and a source factory — so a host can register its own
without editing the widget. `Scratches` then becomes a third mode rather than a special case, which
settles where scratch files live.

**Default: Assets**, on the same reasoning Unity uses — the common task is finding a thing you are working
on, not auditing the directory. Files mode is one click away and should be sticky per project.

### Q11 — Event-sourced pessimism, with per-row pending state. **Recommended.**

Not "optimistic local update" and not "disable the panel". The rule, in one line:

> **The explorer renders the server's tree, updated by change events — never by the local call's
> optimism.**

This is VS Code's architecture and it is worth being precise about why it is the right one *here*
specifically. The explorer does not update itself when it issues an operation; it updates when the
resulting event arrives (`onDidRunWorkingCopyFileOperation`, `onDidFilesChange`). The call site and the
model are decoupled.

The payoff is that **another user's change and your own arrive through the same path.** Given Q9 — a
genuinely multi-user workspace — an optimistic explorer would need two update mechanisms that must agree,
and they would diverge. One mechanism cannot.

Concretely:

- **Reads** (expand, list) — never block. Show the stale subtree with a pending indicator; replace it when
  the manifest lands
- **Writes** (create, rename, delete, move) — pessimistic. The row shows a pending state; the tree changes
  when the event arrives. A rejection (permission, collision, conflict) therefore needs *no rollback*,
  because nothing was ever applied
- **Never disable the panel.** Pending state is per-row. A slow delete in one folder must not freeze
  navigation everywhere else
- **Inline rename is the one visible exception**, and a bounded one: the row shows the typed name while
  the RPC is in flight, dimmed, and reverts with a message on failure. One row, one operation, and the
  user is looking straight at it

Two things this needs that do not exist yet:

- **A timeout and a visible failure surface.** An RPC that never answers currently leaves a pending row
  forever. This is the strongest argument for C14 (toasts) in the Tier 2 list above
- **Disconnected is a first-class state.** Not an error — the workspace is remote by design. The tree
  should say so and keep showing the last-known contents read-only, rather than emptying itself

> **Why not optimistic.** It is the right answer for a single-user local editor and the wrong one here.
> Rolling back an optimistic tree change means rebuilding rows the user may be mid-click on — the trap
> `DockArea.syncGroups` already exists to avoid, and the one that cost a session on the table header.
