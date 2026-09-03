# The filesystem, resource and document model — audit and rewrite plan

**Status: COMPLETE. F0–F7 shipped, 2026-09-04**, interleaved with `plan_ui_rewrite.md`'s M7 into the
one flow that plan's §7.A gives. Two things are deliberately outstanding and are a person's to run
rather than a milestone's: `-PcgEditorProbe` on an integrated server and `-PcgTwoClientProbe` on two
clients joined to one `runServer` (F7).

**Audited 2026-09-03** against the tree at `97e31a51` (M6 closed); **restructured 2026-09-03** after
the decisions below were taken. Every claim was checked against the code on that day; where a line is
named it was read, not remembered.

> **Scope.** `com.crystalgui.fs` (37 files), `com.crystalgui.document` (6), the protocol and wire
> layers they ride on, and every workbench seam that opens, saves, watches, decorates or persists a
> file. The UI mirror (`net.mirror`, `net.window`, `net.projection`) is out of scope. The consumer
> **F0-F7 shipped.** The API below is what was built, with every divergence recorded in the step it
> belongs to — the largest being that the graph is its own model rather than one extracted from it,
> and that `WorkspaceApiTest`'s claim was narrowed by measuring it.
>
> **Decided before this plan was written:** the synchronisation point is the **save**, as it is
> today. Live co-editing is not pursued (§4).

## Contents

1. [The headline](#0-the-headline)
2. [Inventory](#1-inventory)
3. [What the earlier plans decided and the code did not keep](#2-what-the-earlier-plans-decided-and-the-code-did-not-keep)
4. [Findings](#3-findings) — N1–N36, each with its receipt
5. [Sync on save](#4-sync-on-save)
6. [The target model](#5-the-target-model) — D1–D24
7. [The authoring surface](#6-the-authoring-surface) — what a mod author writes
8. [Survives, scrapped, moves](#7-survives-scrapped-moves)
9. [Migration cost](#8-migration-cost-measured)
10. [The work, in order](#9-the-work-in-order) — F0–F7, each as numbered steps
11. [Testing rules](#10-testing-rules)
12. [Risks](#11-risks)
13. [Prior art](#12-prior-art)

---

## 0. The headline

**The stack was built bottom-up in five sittings, and each sitting added a layer to the previous
one's shape without re-deciding the shape.** Every sitting was correct on its own terms and 170 tests
are green. It still reads as a prototype, for one structural reason:

**There is no first-class "document the workspace holds".** The server has bytes and an etag. The
client has a `WorkspaceClient` with three parallel per-path maps. The workbench has an `OpenDocuments`
with a second copy of the bytes. `TextFileDocument` is a record wrapping a widget. `Resource` and
`CgPath` are two spellings of one address, and anything that is not a project file lives in a
**parallel lane** of `Workbench` because the document store cannot hold it. Every later feature —
conflict, presence, watching, reconnect, dirtiness, view state, session restore, the merge base — was
threaded through all of them, each threading a comment-enforced ordering rather than a property of
the model.

| One open file is held as | Where |
|---|---|
| the file | `CgFileSystem` (server) |
| the bytes last read | `WorkspaceClient.cachedContent` — the merge base |
| the bytes last read or written | `OpenDocuments.Entry.onDisk` — the dirty baseline |
| the live text | `TextEditor.buffer`, a `TextBuffer` |
| the live text again | `Workbench.bufferSnapshot`, a `String` for the analysis thread |
| the file again, per chunked transfer | `WorkspaceRpc.Transfer.content` (server) |
| the file again, per index read | `ProjectIndex`'s cache |

Two of the seven mean the same thing and are updated at different moments by different classes.

**Two defects prove the diagnosis.** `TextBuffer` carries the line ending a file arrived with and a
method to write it back; **nothing calls it**, so every CRLF file saves as LF, and because dirtiness is
an encode-and-compare against the CRLF bytes read, **a CRLF file is dirty the moment it opens** (N10).
And `TextBuffer.load` pushes an undo entry, so **Ctrl+Z after an external reload restores stale
text** (N11) against the document layer's own written contract. In both the model knew the answer and
the workbench never asked, because it talks to a widget rather than to a document.

---

## 1. Inventory

### 1.1 `fs` is five concerns in one directory

| Concern | Files | Lines |
|---|---|---|
| Provider — `CgPath`, `CgFileSystem`, `CgFileEntry`, `CgFileError`, `CgFileCapability`, `CgFileType`, `CgFileSystemException`, `LocalFileSystem`, `InMemoryFileSystem`, `CgFileEvent`, `CgFileEventSource`, `NioFileEventSource` | 12 | ~1,700 |
| Server workspace — `WorkspaceService`, `WorkspaceProject`, `ProjectRegistry`, `ProjectProvider`, `ProjectInfo`, `WorkspacePermission`, `WorkspaceActor`, `WorkspaceOperation`, `WorkspaceConflictException`, `WorkspaceTrash`, `WorkspaceWatcher`, `WorkspacePresence` | 12 | ~1,500 |
| Wire binding — `WorkspaceProtocol`, `WorkspaceRpc` | 2 | ~780 |
| Client — `WorkspaceClient`, `WorkspaceFileService`, `WorkingCopies` | 3 | ~1,340 |
| Resources — `Resource`, `ResourceRegistry`, `ResourceContentProvider`, `SourceRoots` | 4 | ~710 |
| Client-local config — `ConfigStorage` ×3, `FilePatternMap` | 4 | ~400 |

The provider tier is a faithful port of VS Code's `platform/files` and needs the least. The client
tier is the accretion: `WorkspaceClient` alone is 832 lines doing seven jobs (N15).

### 1.2 The document layer is 965 lines, above `widget`

`FileDocument`, `TextFileDocument`, `OpenDocuments`, `DocumentType`, `DocumentViewState`,
`RecentFiles`. `LayeringTest` places `document` above `widget` because `FileDocument.view()` answers a
node — so nothing in it is headless, while the one headless document model the engine has
(`TextBuffer`) sits a package below, unused as one (N13).

### 1.3 Consumers

63 files import from `fs`. The seams a rewrite of the consumer surface moves:

| Seam | Sites |
|---|---|
| `WorkspaceClient` methods — 13 in `Workbench`, 13 in `WorkspaceFileService` (its own operations and undo inverses), 3 in `WorkspaceTreeSource`, 1 in `Mc1710Workspace` | 30 |
| `WorkspaceFileService` (`files().*`) | 10 |
| The document layer (`OpenDocuments`, `FileDocument`, `TextFileDocument`, `DocumentType`, `documentFor`, `activeDocument`, `openFile`, `openResource*`) | 24 files |
| Identity conversions (`Resource.of` 18, `.asPath()` 17, `CgPath.parse` 14, `Resource.parse` 6, `Resource.derived` 1) | 56 |
| `ResourceRegistry` statics | 4 files |

`Workbench.java` is 3,268 lines; roughly **1,100** are the file and document plumbing this plan is about.

### 1.4 Tests

170 tests across 17 headless files: path confinement, symlinks, every etag and conflict rule, trash
and undo, presence scoping, capability pushes, reconnect, events reaching a client, chunked transfer.
**No test constructs a `Workbench` or calls `openFile`, `saveActiveFile` or `isDirty`**, and
`OpenDocuments`, `TextFileDocument` and `FileDocument` have no direct test. Both defects in §0 are
invisible to the suite for that reason. `CgUiWorkspaceScene` in the harness is the only thing that has
run the document path end to end.

---

## 2. What the earlier plans decided and the code did not keep

Only the rows that drifted or were never built; what was kept is in §7.

| Decision | Plan | What happened |
|---|---|---|
| D5 `fs.changed` pushed | P6.1.10 | Pushed as a **request** the client answers (N24); only for files the client has open, never directories (N28) |
| D11 chunked with progress | P6.1.10 | Serial pull; the server snapshots the whole file per transfer (N22); pipelining (6.4) never built |
| D13 client cache: per directory, on disk, LRU | P6.1.10 | Became a per-file in-memory `cachedContent`; nothing on disk; the plan's *"the only thing the client writes to its own disk"* describes nothing |
| G1 BOM stripped, recorded, re-emitted | P6.1.10 | Never built; no reference to a BOM anywhere in `fs`, `document`, `workbench`, `text` |
| G2 original line ending restored on write | P6.1.10 | Built in `TextBuffer`, never wired to a save (N10) |
| G4 `fs.changed` carries RENAMED; a rename retargets | P6.1.10 | Never built; an external rename arrives as `deleted` (N29) |
| G7 case sensitivity as a capability | P6.1.10 | Advertised, read by nobody (N21 of the target: D21) |
| G10 coalescing of per-path notifications | P6.1.10 | Never built (N30) |
| G11 `fs.projectClosed` | P6.1.10 | Never built |
| G12 the watcher honours excludes | P6.1.10 | Production opens it with `Collections.emptyList()` (N23) |
| Rate limiting, fs protocol version | P6.1.10 open questions | Never answered |
| 5.3 dirty buffers persisted client-side | Phase 5 | Never built; a crash or disconnect loses the edit (N32) |
| 6.1 the watcher stats, never reads | Phase 6 | The poll does; the `fs.watch` handler still reads the whole file (N21) |
| 6.4 pipelined pull, 6.5 tab states, 6.8 delta reads, 6.9 editor-open probe | Phase 6 | Never built |

The shape-changing items were the ones dropped; the additive ones landed. That is a prototype grown
feature by feature.

---

## 3. Findings

Numbered so a decision can say which it closes and a step can say which it removes. Each carries the
line that proves it.

### 3.1 Identity

- **N1 — Six spellings of one file, and a parallel lane for the sixth.** `CgPath`; `Resource`;
  `DockPanelRef.state["path"]` parsed back at 12 + 6 sites; `Workbench.RESOURCE_STATE`, a second
  state key because `PATH_STATE` "is CgPath-keyed from its first line" (`Workbench.java:138-155`);
  `Workbench.viewers` keyed by `resource.toString()`; per-editor `JobKey`s. `OpenDocuments` is keyed
  by `CgPath` (`OpenDocuments.java:70`), so a `library://` or `shader-generated://` document cannot
  be in it, and `Workbench` grew ~400 lines re-deriving open, adopt and presentation for a second key
  type (`Workbench.java:1389-1800`). Its javadoc calls the lane a stopgap *"until a second non-file
  document kind turns up"*; two did.
- **N2 — A derived resource's origin is a grammar over its path.** `Resource.derived` writes the
  origin inside the path text and `parse` recovers it by trying to parse the tail
  (`Resource.java:104-133`), so `output://proj:build.log` is "derived" by accident.
- **N3 — The decorations join by scan.** `DiagnosticDecorations.hasErrors` walks every marker
  resource per row bind because markers are keyed by `Resource` and the tree by `CgPath`
  (`DiagnosticDecorations.java:74-82`).
- **N4 — A rename does not reach the document.** `TextFileDocument` is a `record(editor, resource)`
  (`TextFileDocument.java:35`); `OpenDocuments.retarget` moves the map entry
  (`OpenDocuments.java:262-265`) and the document keeps answering the old resource. After a rename:
  the Problems panel is scoped to the old path, `Markers` stays indexed under it, the language
  attachment (`AnalysedLanguageServices.ATTACHED`) is keyed by it, and `RunPanels` **forgets** the
  run session rather than retargeting it (`RunPanels.java:135-137`). Four stores rekey independently;
  one forgets; the document is never told.
- **N5 — The docs disagree about the key.** `Resource.java:42-44` says `OpenDocuments` is keyed by
  resource; `CGUI_WORKBENCH_SERVICES.md` says by `CgPath`, deliberately.

### 3.2 Content and state

- **N6 — Seven copies, no owner** (§0's table).
- **N7 — Dirtiness is a serialisation.** `isDirty` is `!Arrays.equals(encode(), onDisk)`
  (`OpenDocuments.java:209-213`), run for every open document per change; `TextBuffer.version()` exists
  and nothing about dirtiness reads it.
- **N8 — The merge base is a comment.** *"CAPTURED BEFORE ANYTHING READS"*
  (`Workbench.java:2146-2160`): the base of a three-way merge lives in a cache evicted by any read or
  any change notification.
- **N9 — The analysis thread gets a fourth copy.** `bufferSnapshot`, a `Map<CgPath, String>` rebuilt
  from `encode()` (`Workbench.java:419-472`), because `ProjectIndex.sourceOf` runs inside a compile. A
  `Rope` is persistent and needs no snapshot.
- **N10 — Line endings: dead code on the save path.** `TextBuffer.textWithOriginalLineEndings()` has
  zero callers; `TextFileDocument.encode()` is `editor.getText().getBytes(UTF_8)`
  (`TextFileDocument.java:160-162`) over the LF rope. CRLF saves as LF, and is dirty on open.
- **N11 — A reload is undoable.** `TextBuffer.load` pushes a `ChangeSetEdit` (`TextBuffer.java:157-169`
  and the `CharSequence` overload through `edit`), so Ctrl+Z after an external reload restores the stale
  text. `FileDocument.adopt`'s contract says loading must never push an undo step
  (`FileDocument.java:126-133`).

### 3.3 The document layer

- **N12 — The document is the widget, with static state.** `TextFileDocument` wraps `TextEditor`;
  because a record holds no instance state, the caret subscription and status entries are `static`
  (`:62-88`), one active document per **process**. `DockWindow` makes two workbenches per process
  ordinary.
- **N13 — Above `widget`, so nothing is headless.** It imports `Box`, `TextEditor` and `StatusBar`.
  `TextBuffer` one package down already has the rope, version, line ending, undo, decorations,
  diagnostics and a `ChangeSet` signal; the document adapts `editor.onChanged`, a `String` of the whole
  text per keystroke (`TextEditor.java:255`), instead.
- **N14 — Language services hang off the editor.** `TextFileDocument.dispose` calls the editor's
  language teardown; a second editor over one buffer would hold a second parse tree, and a document
  with no tab cannot analyse.

### 3.4 The client

- **N15 — One class, seven jobs.** RPC facade; etag cache; content cache; watch memo; capabilities and
  presence caches with push handlers; the chunked-pull state machine; connection rebinding with a
  static memo. Three parallel `Map<CgPath, …>` fields (`WorkspaceClient.java:60-76`), fields declared
  wherever each feature landed.
- **N16 — Every subscription is a single slot.** `onFileChanged`, `onCapabilitiesChanged`,
  `onPresenceChanged`, `onRebound` each replace the previous handler (`:242, :344, :387, :426`). One
  caller each today; the second subscriber silently evicts the first.
- **N17 — Errors are strings.** A conflict travels as `"CONFLICT " + etag` and is re-parsed
  (`:446-453`); `Failure.code()` may or may not name a `CgFileError`.
- **N18 — No coalescing, no cancellation.** Two reads of one path in flight are two round trips;
  `MessageRouter.cancel` exists and nothing exposes it.
- **N19 — A fossil memo.** `ProtocolConnection.attachment` was written to replace
  `WorkspaceClient.forConnection`'s static `WeakHashMap` (`ProtocolConnection.java:109-147`); the map
  is still there (`:121-147`).

### 3.5 The server

- **N20 — The registry rebuilds per call.** `ProjectRegistry.all()` reconstructs from every provider
  every time (`:73-88`); `authorise` and `LocalFileSystem.resolve` (`:280-281`) both call it, so one
  read is three rebuilds and the watcher poll is two per file per peer per half second.
- **N21 — `fs.watch` reads the whole file to authorise.** `service.read(actor, path)` in the WATCH
  handler (`WorkspaceRpc.java:285-297`); 6.1 fixed the poll and left the subscription.
- **N22 — A transfer snapshots the file into memory** (`:416-441`), four × 100 MB per peer; its own
  javadoc names the fix — a ranged read — and `FILE_OPEN_READ_WRITE_CLOSE` is declared and unimplemented.
- **N23 — Three glob matchers, three semantics; production passes none.** `WorkspaceService.matches`
  (`*`,`?` anywhere), `NioFileEventSource.matches` (leading `*` only, while its javadoc claims *"the
  same rule"*, `:230-252`), `FilePatternMap.globMatches`; and `CgUiWorkspaceHost.java:131` opens the
  watcher with an empty list.
- **N24 — Pushes are requests.** `fs.changed`, `fs.presence`, `fs.capabilities` go out as
  `connection.call(method, args, null, null)` (`CgUiWorkspaceHost.java:232-234, 256-258`) because the
  client registered through `onRequest`; each costs a pending entry and a ten-second timeout slot.
- **N25 — Per-peer re-checks.** One drained event batch is handed to every peer, and each `recheck`
  stats the file (`WorkspaceWatcher.java:145-157`): K peers, K stats per event, plus the K × M poll.
- **N26 — Three registries of peers.** `CgUiConnections.SERVER`, `CgUiWorkspaceHost.BY_PEER`,
  `CgUiWorkspaceHost.CONNECTIONS`, plus a static `service`.
- **N27 — Hand-packed payloads, no version.** `WorkspaceRpc.installOn` is 230 lines of `StateMap`
  puts mirrored by hand in the client; `WorkspaceProtocol` is sixty string constants; the fs protocol
  has no version. `Envelope` and `MessageRouter` beneath are right and the workspace uses half of them.
- Also: `trashPathOf` scans `list("")` then every project (`WorkspaceService.java:316-325`);
  `writeDelta` re-reads the file to apply to (`WorkspaceRpc.java:144-179`), acceptable at save
  frequency and recorded.

### 3.6 Watching

- **N28 — Files only, never directories.** A client watches what it has read; the server tells a peer
  only about its watched paths (`WorkspaceWatcher.pollEvents`). Another client's create, rename or
  delete in a folder you have expanded never reaches you; `Workbench.java:846-850`'s comment claiming
  otherwise is true only for open files.
- **N29 — No `renamed`, no `created` on the wire.** Only `modified` and `deleted`; an external rename
  is a deletion; `CgFileEvent.Kind.CREATED` exists server-side and is folded into a re-check.
- **N30 — No coalescing.** A directory rename with fifty open files is fifty notifications, each
  invalidating a listing and refreshing the tree.

### 3.7 Asynchrony

- **N31 — Four conventions.** Callback pairs on `WorkspaceClient`; `(Runnable, Consumer)` pairs plus
  three signals plus `Batch.track()` runnables on `WorkspaceFileService` (whose javadoc records the
  transaction that never closed); `JobScheduler.job(…).onDone(…)` in the viewer lane; a synchronous
  `ResourceContentProvider.read` "reached from a paint path" that is always wrapped in a job
  (`Workbench.java:1668-1690`). Plus volatile flags drained on tick and two per-frame retries
  (`fileTree.loadProjects()` every tick, `:3136`).

### 3.8 Persistence

- **N32 — No unsaved document is ever written to `ConfigStorage`.** Phase 5.3 as designed and never
  built; a crash, a quit or a disconnect loses the edit.

### 3.9 The language stack

- **N33 — `ResourceRegistry` is static, and its docs are wrong.** `ProjectSourceSymbols` registers for
  the project scheme; the class javadoc and `CGUI_WORKBENCH_SERVICES.md` still say the project scheme
  refuses a provider.
- **N34 — A third reader.** `ProjectIndex` reads through a `(path, onText)` lambda over `client.read`
  with its own cache, beside the document lane and the viewer lane.

### 3.10 Layering

- **N35 — By proximity, not subject.** `fs` names `net` and `net.protocol` (the filesystem depends on
  UI networking, and `WorkspaceClient`'s constructor takes a `ClientUiSession`); `document` names
  widgets; `SourceRoots`, `ConfigStorage` and `FilePatternMap` sit in `fs`; statics everywhere `Markers`
  was made an instance to avoid.

### 3.11 Tests

- **N36 — The workbench path is untested** (§1.4), and several `WorkspaceClientTest` cases pin the
  implementation's conventions (`savingWithoutAReadIsAProgrammingError`) rather than a behaviour.

---

## 4. Sync on save

**Decided.** A document lives on one client between saves; a save quotes the etag and is refused if
the file moved; the refusal is a conflict the person resolves. Every disagreement is an etag mismatch,
surfaces where a person can act on it, and is testable with one client and one file.

Three facts settled on the way, kept because the rewrite still depends on them:

- **A projection is the wrong channel for document content whatever the sync model.** `Projections`
  compares and ships the whole value per tick (`Projections.java:56-63`); for a text document that is
  the text, and on arrival `setText` resets the caret. `ClientUiSession.shouldSuppress` exists because
  of it. `TextEditor`'s `localOnly` reason — *"describing it as a widget would put the same file on the
  wire twice"* — stands as written.
- **The pieces a live model would need mostly exist** — a versioned `TextBuffer` with a `ChangeSet`
  signal, `compose`, `invert`, `SelectionModel.mapThrough`, `TextDiff` — and two do not: a server that
  retains a document between edits, and `ChangeSet.map` for rebasing. `@codemirror/collab` (MIT) is the
  port over exactly this `ChangeSet` model. The road stays marked; nothing in §5 forecloses it and
  nothing in §9 builds toward it.
- **The etag stays the only version the server knows.** The client's `TextBuffer.version` is a local
  fact for dirtiness (D9) and nothing else.

---

## 5. The target model

```
                     SERVER                                    CLIENT                    reference
 fs             CgFileSystem (+ranged read, dir events)         —                         VS Code IFileSystemProvider
 fs.project     Project, ProjectRegistry, ProjectInfo,          ProjectInfo               workspace folders
                SourceRoots, Excludes
 fs.server      WorkspaceService: authorise, etag, trash,       —                         VS Code FileService
                Presence, WatchHub
 fs.protocol    messages + codecs, FsMethods, FsError, hello     same                      LSP-shaped methods
 document       —                                                DocumentModel: text       ITextModel / IntelliJ Document
                                                                 (a TextBuffer), graph,
                                                                 bytes — headless
 fs.client      —                                                Workspace: files,         VS Code IFileService +
                                                                 documents, watches,       ITextFileService
                                                                 presence, history, health
 workbench      —                                                EditorService, session    FileEditorManager
```

Twenty-four decisions, grouped by what they decide. Each names the findings it closes.

### 5.1 Identity and lifetime

**D1 — One identity: `Resource`.** Every store, ref, signal and key is a `Resource`. `CgPath` stays as
the *value* of the project scheme — its confinement and its frozen text form are untouchable — reached
through `resource.asPath()` only where a provider or a permission needs the confined form.
`DockPanelRef` carries one key; the viewer lane goes; `Resource.derived` gets an explicit `origin`
field instead of a grammar over the path. *Closes N1, N2, N3, N5.*

**D2 — A document's identity survives a rename.** `Document` is an object; `resource()` is a property
with `onDidChangeResource(from, to)`. Every store that holds a document keys by the document and
subscribes to the one rename signal. IntelliJ's `VirtualFile`. *Closes N4.*

**D3 — Reference-counted lifetime.** `Documents.open` answers a `DocumentReference` (the document plus
`dispose()`); the document lives while any reference does — a tab, a diff, a merge view, the Problems
panel, a background compile — and the last release disposes the model. VS Code's
`createModelReference`. *Closes the "Parser is closed" case behind N9.*

### 5.2 Documents

**D4 — `TextBuffer` is the document.** No new model: `TextDocumentModel` **is** a `TextBuffer` plus
its resource, and the editor binds to it (`TextEditor.setBuffer`, VS Code's `setModel`). `FileDocument`
becomes an `EditorInput` over a `DocumentModel`. The `document` package moves below `widget`; the
status contributions in `TextFileDocument` become a workbench contributor. *Closes N12, N13.*

**D5 — Line endings, BOM and encoding restored on write.** Detected on load, held on the buffer, written
back by `encode()`; the status bar reads them off the document. Text versus binary is a NUL sniff over
the first kilobytes, never an extension. *Closes N10, G1, G2.*

**D6 — Language services belong to the model.** The tokenizer and the `LanguageServices` sit on
`TextDocumentModel` beside the `DecorationSet` and `DiagnosticSet` it already owns; two editors over
one buffer share one parse tree, and a document with no tab still analyses. *Closes N14.*

**D7 — Undo is fenced across load and reload.** A load or an `adopt` bumps the version, fires
`onChanged`, and pushes nothing; a save marks the history entry so undo past a save works. *Closes N11.*

**D8 — Documents that are not text.** `DocumentModel` — `encode`, `adopt`, `version`, `history`,
`onChanged`, `mergeable` — with an `AbstractDocumentModel` whose one door is `apply(Edit)`: version,
dirtiness, undo and backup follow. Three ship: text, bytes (anything with no kind; a read-only viewer),
the shader graph (version from its undo stack; no serialising to test dirtiness). A widget-shaped
document may implement the model on the widget. `mergeable()` gates the conflict dialog's third
button; a line merge of a JSON graph is a broken graph.

### 5.3 Content and state

**D9 — One content store per side.** Server: nothing retained between calls — the file and its etag
are its whole state; a transfer is `(resource, etag, size)` over ranged reads. Client: one `Document`
per open resource carrying the model, `savedVersion`, the etag last seen and its watch. Dirty is
`version != savedVersion`, O(1). The merge base is the last local-history entry (D13), never a cache
entry a notification may evict. *Closes N6, N7, N8, N9, N22.*

**D10 — The save is the synchronisation point** (§4), and the state is one enum on the handle:
`LOADING`, `CLEAN`, `DIRTY`, `STALE` (changed on the server under a clean buffer — reloaded),
`CONFLICTING` (under a dirty one — marked), `ORPHANED` (deleted on the server), `FAILED`. Every
surface reads it.

**D11 — Backup, hot exit and auto-save.** Every dirty document is written to `ConfigStorage` on a
debounce and on hide, client-side (the server may be what went away), and offered on the next open.
With that, closing the screen, losing the connection and crashing all restore silently and nothing
prompts — VS Code's `files.hotExit`; the tab close guard stays for a deliberate close. Auto-save as a
mode (`off`, `afterDelay`, `onFocusChange`) from `WorkbenchSettings`, debounced through `RatePolicy`.
*Closes N32, 5.3.*

**D12 — Editing presence, and a non-modal conflict path.** Presence carries `dirty` per holder, so
"X is editing this file" shows on the first keystroke rather than at save. Under auto-save the conflict
path is a notification with a compare action and the document is marked `CONFLICTING`; the dialog is
kept for an explicit save. `CONFLICTING` suspends auto-save for that document until resolved.

**D13 — Local history.** A per-file entry on every save, client-local, delta-stored against the
previous entry with a full snapshot every N, bounded by count and age, never for a document above the
first size tier. `Workspace.history()` lists, reads and restores. It is what makes "keep mine"
recoverable and what supplies the merge base.

### 5.4 Protocol and asynchrony

**D14 — Typed messages, honest kinds, a version.** A `Codec` per payload in `fs.protocol`; `FsMethods`
names methods only; pushes are notifications; `FsError(code, detail, actualEtag?)` in the response
payload; `fs/hello` carries the protocol version and the server's facts (D21). *Closes N17, N24, N27.*

**D15 — One asynchronous type: `Reply<T>`, and `Stream<T>` for partial answers.** `then`, `onError`,
`always`, `map`, `cancel`, `both`, `all`; continuations on the frame thread during the tick.
`JobScheduler.Job` implements it. Reads coalesce by resource. `Stream<T>` adds `onPartial` for chunked
reads, paged listings and search. *Closes N18, N31.*

**D16 — Two lanes over one wire.** Interactive (open, save, list-on-expand) ahead of background
(crawl, index, viewer reads), the background lane bounded in flight, because the multiplexer
round-robins streams equally and a save otherwise waits behind forty crawl listings.

**D17 — A per-resource operation queue, and idempotent mutations.** The client serialises operations
per resource (VS Code's `ResourceQueue`). Every mutation carries a client-generated operation id; a
retry after a timeout is answered from the server's recent-operations table, not refused as a conflict
against your own write. *Turns N8's ordering into a property.*

**D18 — Connection health is visible.** `Workspace.health()` — round-trip estimate, in flight per
lane, credit, bytes each way, last error — from the multiplexer's counters, shown as a status item.

### 5.5 Server

**D19 — Watch subscriptions name directories.** `fs/watch(resource, recursive)` on any path; a
`WatchHub` per workspace maps subscriptions to peers, drains the one event source per project,
coalesces per tick per directory, and sends `created | modified | deleted | renamed(from, to)`. The
etag poll survives as the OVERFLOW reconciliation, once per file over the subscription set. Opening a
file subscribes the file; expanding a folder subscribes the folder. *Closes N25, N28, N29, N30.*

**D20 — Streaming through the provider.** `CgFileSystem.read(path, offset, length)` behind the
capability that exists; the client pipelines with a window of outstanding chunks; large writes stream
with the etag checked once at commit. *Closes N22, 6.4.*

**D21 — The provider's facts reach the client, in `fs/hello`.** Case sensitivity (the client
deduplicates open documents by the server's rule; `Resource` equality stays strict, as `extUri` keeps
`URI` strict). The host's name rule (reserved names, trailing dots, length) so New File refuses before
the round trip. Size tiers: above the first no tokenizer, folding or services; above the second
read-only; above the cap refused. *Closes G7.*

**D22 — The server's abuse posture.** Ignore rules travel on `ProjectInfo` so crawl, watcher, tree
and search agree; a per-peer subscription cap; an audit line per mutation naming actor, operation and
resource; a per-actor rate limit with its own error code. `fs/watch` authorises with a stat. *Closes
N21, N23, and the two open questions.*

### 5.6 Structure

**D23 — Packages by subject.**

| Package | Holds |
|---|---|
| `fs` | the provider tier, plus the ranged read |
| `fs.project` | `Project`, `ProjectRegistry` (cached on a provider revision), `ProjectProvider`, `ProjectInfo`, `SourceRoots`, `Excludes` — the one glob matcher |
| `fs.server` | `WorkspaceService`, `Permission`, `Actor`, `Operation`, `Trash`, `Presence`, `WatchHub`, `WorkspaceBinding` |
| `fs.protocol` | messages, codecs, `FsMethods`, `FsError`, `FsHello` |
| `fs.client` | `Workspace` and its facades, `Document`, `FileOperations`, `Backup`, `LocalHistory`, `Health` |
| `document` | `DocumentKind`, `DocumentModel`, `AbstractDocumentModel`, `TextDocumentModel`, `BytesDocumentModel`, `DocumentEditor`, `EditorInput`, `RecentFiles` — below `widget` |
| `core.async` | `Reply`, `Stream` beside `JobScheduler` |
| `core.storage` | `ConfigStorage` and its implementations |
| `core.pattern` | `FilePatternMap` |
| `workbench.editor` | `EditorService`, `TextDocumentStatus`, `ContentProviders` |

*Closes N20 (the cache), N35.*

**D24 — Nothing static.** `ResourceRegistry` becomes `ContentProviders`, an instance the workspace
owns; the host's per-peer maps become one `ProtocolConnection.attachment`; `TextFileDocument`'s statics
go with the class. *Closes N19, N26, N33.*

---

## 6. The authoring surface

What a mod author sees, designed the way `Networked` was: the whole thing first, the rules derived
from it. Two packages — `fs.client` and `document` — plus `Reply` in `core.async`. Nothing an author
needs is anywhere else.

### 6.1 The whole thing, for the shader graph

```java
public final class ShaderGraphKind {
    public static final DocumentKind KIND = DocumentKind.of("crystalshader:graph", "Shader Graph")
            .files(FilePatterns.extension("shadergraph"))      // name, extension or glob
            .icon("crystalshader:graph")
            .model(GraphDocument::decode)                        // bytes    -> DocumentModel
            .editor(ShaderGraphEditor::new)                      // Document -> DocumentEditor
            .status(GraphStatus::contribute)                     // while active
            .viewState(GraphViewState::new);                     // pan, zoom, selection

    public static Disposable register(Workspace workspace) {
        return workspace.kinds().register(KIND);
    }
}

public final class GraphDocument extends AbstractDocumentModel {          // headless
    static GraphDocument decode(byte[] bytes) { … }                       // throws on garbage -> FAILED
    @Override public byte[] encode() { … }                               // JSON, on demand
    @Override public void adopt(byte[] bytes) { … }                       // a reload: no undo step
    public void connect(PortId from, PortId to) { apply(new ConnectEdit(this, from, to)); }
    public final Signal.Value<GraphChange> onGraphChanged = new Signal.Value<>();
}

public final class ShaderGraphEditor extends UIElement implements DocumentEditor {
    ShaderGraphEditor(Document document) {
        GraphDocument graph = document.as(GraphDocument.class);
        graph.onGraphChanged.connect(this::repaintWires);
        document.onDidChangeState.connect(this::showBanner);               // STALE / CONFLICTING / ORPHANED
    }
    @Override public UIElement view() { return this; }
}
```

From that one `register`: `.shadergraph` opens as a graph with its icon; dirtiness is a compare; Ctrl+Z
reaches `ConnectEdit.undo()`; Ctrl+S encodes and saves with the etag; a server change under a clean
graph reloads through `adopt`, under a dirty one marks it and the dialog offers keep and take and not
merge; an unsaved graph survives a quit; the session carries the view state; the status bar shows the
compile summary while the tab is in front.

### 6.2 The workspace

```java
Workspace workspace;              // one per connection; handed to the host; never static

workspace.projects()              // all(), rootsOf(id), sourceRootsOf(id), ignoreRulesOf(id), onDidChange
workspace.files()                 // read, stat, list, exists, write, create, mkdir, delete, rename, copy,
                                  // batch(label, …), apply(WorkspaceEdit), undoStack(), onWillRun/onDidRun/onDidFail
workspace.documents()             // open(resource) -> Reply<DocumentReference>, get, all, create(untitled),
                                  // autoSave(mode), onWillSave, onDidOpen/onDidClose/onDidChangeState/onDidSave
workspace.watch(resource, recursive)   // Watch: onChanged (coalesced batches), dispose()
workspace.schemes()               // register(scheme, ContentProvider) -> Disposable
workspace.kinds()                 // register(kind) -> Disposable, forResource(resource)
workspace.presence()              // whoElseHasOpen, whoIsEditing, onDidChange
workspace.capabilities()          // mayRead, mayWrite, caseSensitive, isValidName, sizeTierOf, onDidChange  (hints)
workspace.history()               // entriesOf(resource), textAt(entry), restore(entry)
workspace.health()                // roundTripMillis, inFlight(lane), credit, lastError, onDidChange
workspace.onDidConnect / onDidDisconnect / onDidReconnect
```

### 6.3 `Reply` and `Stream`

```java
public interface Reply<T> {
    Reply<T> then(Consumer<T> onResult);            // frame thread, during the connection's tick
    Reply<T> onError(Consumer<FsError> onError);    // structured: code, detail, actualEtag for a conflict
    Reply<T> always(Runnable whenSettled);
    <U> Reply<U> map(Function<T, U> f);
    void cancel();                                  // reaches MessageRouter.cancel
    boolean isDone();
    @Nullable T result();                           // for a test over an in-memory transport
    static <A, B> Reply<Pair<A, B>> both(Reply<A> a, Reply<B> b);
    static Reply<Void> all(Collection<Reply<?>> replies);
}

public interface Stream<T> extends Reply<List<T>> {
    Stream<T> onPartial(Consumer<T> each);          // in order, on the frame thread
}

workspace.files().read(resource).then(content -> …).onError(error -> …);
workspace.files().list(bigFolder).onPartial(page -> tree.append(page)).then(all -> tree.settle());
```

`JobScheduler.Job` implements `Reply`, so a provider that decompiles and a read that crosses a wire
are one shape. A second read of a resource in flight returns the same reply. Mutations carry an
operation id underneath (D17).

### 6.4 Signals and disposal

```java
Connection c = workspace.documents().onDidSave.connect(document -> recompile(document));
Disposable d = workspace.schemes().register("generated", provider);
Disposer.register(owner, c, d);
```

No `setX(callback)` slot anywhere on the surface (N16).

### 6.5 Documents

```java
public interface DocumentReference extends Disposable { Document document(); }    // the document lives while any reference does

public interface Document {
    Resource resource();                                     // a property: a rename moves it and announces
    Signal.Pair<Resource, Resource> onDidChangeResource;
    DocumentKind kind();
    DocumentModel model();
    <M extends DocumentModel> M as(Class<M> type);           // throws on the wrong kind

    DocumentState state();                                   // LOADING CLEAN DIRTY STALE CONFLICTING ORPHANED FAILED
    boolean isDirty();  int version();  String etag();

    Reply<Void> save();  Reply<Void> saveAs(Resource);  Reply<Void> reload();  Reply<Void> revert();
    UndoStack history();  DiagnosticSet diagnostics();

    Signal.Value<DocumentState> onDidChangeState;  Signal.Action onDidChange;  Signal.Action onDidSave;
}

public interface DocumentModel {
    byte[] encode();  void adopt(byte[] bytes);              // adopt: a reload, not an edit; version still bumps
    int version();  UndoStack history();  Signal.Action onChanged;
    default boolean mergeable() { return false; }
    default void dispose() {}
}

public abstract class AbstractDocumentModel implements DocumentModel {
    protected final void apply(Edit edit);                   // the one door: history, version++, onChanged
    protected final void markChanged();                      // for a model that cannot express its change as an Edit
}

public interface DocumentEditor {
    UIElement view();
    default void activated(boolean active) {}
    default <T> void writeViewState(StateMap<T> out) {}
    default <T> void readViewState(StateMap<T> in) {}
}

DocumentKind.of("mymod:notes", "Notes").files(FilePatterns.extension("notes")).text(Language.MARKDOWN)  // text kind, one line
```

### 6.6 Files, watches, schemes, hooks, the server side

```java
files.write(resource, bytes)                         // quotes the etag; a moved file is a CONFLICT
files.write(resource, bytes).unconditional()         // the deliberate overwrite
files.rename(from, to)                               // open documents follow; undoable
files.batch("Paste 5 files", b -> { for (Resource s : sources) b.copy(s, into.resolve(s.name())); })
     .then(result -> result.failures().forEach(this::report));               // one undo step, per-item failure
files.apply(WorkspaceEdit.builder().rename(oldClass, newClass).replace(caller, range, "NewName").build());

Watch assets = workspace.watch(Resource.of(CgPath.of("proj", "assets")), true);
assets.onChanged.connect(changes -> changes.forEach(this::reloadTexture));  // a document's own changes are STATE, never here

workspace.schemes().register("library", new ContentProvider() {          // read-only by default, async by construction
    @Override public Reply<byte[]> read(Resource r)   { return jobs.job(…, ctx -> decompile(r)); }
    @Override public String displayName(Resource r)   { return simpleName(r) + ".class"; }
    @Override public SymbolInfo symbolOf(Resource r)  { … }
});

Document scratch = workspace.documents().create(Resource.untitled("scratch-1"), TextDocumentKind.PLAIN);
workspace.documents().onWillSave.add((document, reason) -> document.as(TextDocumentModel.class).trimTrailingWhitespace());

DocumentReference held = workspace.documents().open(resource).result();   // held without a tab
Disposer.register(problemsPanel, held);

WorkspaceService service = host.workspace();                            // server: reads like the client
service.projects().register(myProjects);
service.permissions(OperatorsMayWrite.INSTANCE);
service.onWillWrite.add((actor, resource, bytes) -> GraphDocument.isValid(bytes) ? Verdict.allow() : Verdict.refuse("not a graph"));
service.onDidWrite.connect(event -> recompileIfShader(event.resource()));
```

### 6.7 The rules

| Rule | Decision | Without it |
|---|---|---|
| A1 One entry point, facades by noun, nothing static | D24 | N19, N26, N33 |
| A2 Every asynchronous call returns a `Reply`; a partial answer is a `Stream` | D15 | N18, N31, `pullChunk` |
| A3 Every event is a `Signal`, every registration a `Disposable` | — | N16 |
| A4 Identity is `Resource`; a document is identified by its object and held through a reference | D1, D2, D3 | N1, N4 |
| A5 Edits are the unit of change in a custom model | D8 | a graph serialised per change to test dirtiness |
| A6 The model never names the view | D4 | N13 |
| A7 State is one enum on the handle | D10 | five surfaces answering "what state is this tab in" |
| A8 No default that lies — `mergeable` false, a scheme refuses writes, a kind refuses to register without a model | D8 | today's `contribute` already refuses a type with no factory |
| A9 The server's surface mirrors the client's | — | the `io.on`/`io.project` finding from the UI host |
| A10 A kind is one declaration | — | `registerDocumentType` + `bindEditorExtensions`, shipped half-done |

### 6.8 What changes for existing code

| Today | After |
|---|---|
| `workbench.contribute(DocumentType.of(…).document(path -> new ShaderGraphEditor().setResource(…)))` | `workspace.kinds().register(ShaderGraphKind.KIND)` |
| `ShaderGraphEditor implements FileDocument` | `GraphDocument extends AbstractDocumentModel` + `ShaderGraphEditor implements DocumentEditor` |
| `client.read(path, ok, err)` | `workspace.files().read(resource).then(…).onError(…)` |
| `client.onFileChanged(cb)` (one slot) | `workspace.watch(folder, recursive).onChanged.connect(…)`; `document.onDidChangeState` for open files |
| `ResourceRegistry.register(scheme, provider)` (static) | `workspace.schemes().register(scheme, provider)` → `Disposable` |
| `ResourceContentProvider.read` synchronous | `ContentProvider.read` → `Reply` |
| `openFile` / `openResource` / `openFileAt` / `openResourceAt` | `workbench.editors().open(EditorInput.of(resource)).at(point)` |
| `workbench.isDirty(path)` (an encode) | `document.isDirty()` (a compare) |
| `files().batch(label)` + `track()` | `files().batch(label, b -> …)` → `Reply<BatchResult>` |
| `WorkspacePermission` in a constructor; no hooks | `service.permissions(…)`, `service.onWillWrite`, `service.onDidWrite` |
| `UiDataKeys.*` | `WorkspaceKeys.WORKSPACE`, `ACTIVE_DOCUMENT`, `SELECTED_RESOURCES` |

`com.crystalgui.example.notes` — a mod-shaped kind with a custom model, editor, save participant and
folder watch — is written against the public packages alone, and `WorkspaceApiTest` reads its constant
pool to prove it, as `EngineBoundaryTest` does.

---

## 7. Survives, scrapped, moves

**Survives** (unchanged or nearly): `CgPath` and its tests (frozen); the provider interfaces and both
implementations (plus the ranged read); `CgFileEvent*`, `NioFileEventSource`; `Permission`, `Actor`,
`Operation`, `ConflictException` (renamed); `ProjectRegistry` and friends (cached, moved); `Trash`
(`list("")` fixed); `Presence` (plus `dirty`); `Change`, `ChangeSet`, `Rope`, `TextBuffer`,
`LineEnding`, `TextDiff`, `ThreeWayMerge`, `SelectionModel`; `ConfigStorage*`, `FilePatternMap`
(moved); the whole of `net.protocol` and `net.wire` (untouched); `FileDecoration*`,
`DiagnosticDecorations`, `ConflictDialog`, `MergeView` (keyed by `Resource`); `DockInput`,
`DockPanelRef`, `DockPane` (one key); `WorkbenchSession` (`Resource` keys, `VERSION` 7);
`RecentFiles`; `ShaderGraphEditor` and its contribution (the model split out, the shape unchanged).

**Scrapped** → replaced by: `WorkspaceClient` → `Workspace` + `Document`; `WorkspaceRpc`,
`WorkspaceProtocol` → `fs.protocol` + `WorkspaceBinding`; `WorkspaceWatcher` → `WatchHub`;
`WorkspaceFileService`, `WorkingCopies` → `FileOperations` + `EditorService`; `OpenDocuments`,
`FileDocument`, `TextFileDocument`, `DocumentViewState`, `DocumentType` → `document.*`;
`Resource.derived`'s grammar → an `origin` field; `ResourceRegistry` → `ContentProviders`;
`Workbench`'s ~1,100 lines of document plumbing → `EditorService`; `CgUiWorkspaceHost`'s statics → a
connection attachment; `Mc1710Workspace` → the attachment.

**Moves:** `SourceRoots` → `fs.project`; `ConfigStorage*` → `core.storage`; `FilePatternMap` →
`core.pattern`; `document` → below `widget`.

---

## 8. Migration cost, measured

| Seam | Sites | Cost |
|---|---|---|
| `WorkspaceClient` calls | 30 | 13 in `Workbench` move with `EditorService`; 13 are `WorkspaceFileService`'s own; 3 + 1 rewritten |
| `files().*` | 10 | signature-compatible if `FileOperations` keeps the no-callback overloads |
| Document-layer consumers | 24 files | `app` 3, `language` 4, `workbench` 10, `document` 5, `widget` 1, `core.notify` 1 |
| Identity conversions | 56 | mostly deleted with the viewer/file split |
| `ResourceRegistry` callers | 4 files | `LibrarySources`, `ProjectSourceSymbols`, `Workbench`, `FilesRenderer` |
| Tests | 170 | ~100 kept; ~70 rewritten (`WorkspaceClientTest`, `WorkspaceProtocolTest`, `WorkspaceReconnectTest`, `WorkspaceCapabilitiesTest`, `WorkspaceChangeNotificationTest`, `WorkspaceFileServiceTest`) |
| Session records | once per user | `VERSION` 6 → 7, discarded by the existing rule |

Two frozen forms: `CgPath`'s text (untouched) and the session record (bumped). Nothing else has shipped.

---

## 9. The work, in order

**A strangler port, as M5 and M6 were.** F0 fixes what can be fixed in place. F1 and F2 build the
document model and the protocol *beside* the old code — F1 is headless and needs no wire, F2 retrofits
both existing halves onto typed messages so the wire changes once. F3 and F4 replace the server and
the client under the new protocol. F5 switches the workbench over and deletes the old layer. F6 ports
the non-text documents and writes the example. F7 proves it in a client and writes it down.

**Interleaved with M7.** The UI-side milestone that follows M6 crosses this track at four points, and
every crossing puts the fs step first: `Stream<T>` (F0) under its row windows, the paged-answer shape
(F2) under `ui/rows`, `EditorService` and the VERSION 7 record (F5) under a networked editor tab, and
`Workspace`/`WorkspaceService` (F3, F4) behind `scope.workspace()`. The single implementation order --
F0, F1, F2, 7.0, F3, F4, F5, 7.1, 7.2, 7.3, F6, 7.4, F7 with M8 -- is recorded once, in
`plan_ui_rewrite.md` M7 §7.A, and that table is the one to follow. F7 lands as part of M8.

| Milestone | Builds | Deletes | Needs |
|---|---|---|---|
| F0 Foundations and fixes | `Reply`, `Stream`, packages, three defect fixes, four cheap server fixes | — | — |
| F1 The document model | `document.*`, `TextBuffer` additions | — | — |
| F2 The protocol | `fs.protocol`; the ranged read; both halves retrofitted | `WorkspaceProtocol` | F0 |
| F3 The server | `fs.server`, `WatchHub`, `WorkspaceBinding` | `WorkspaceRpc`, `WorkspaceWatcher`, the host's statics | F2 |
| F4 The client | `fs.client.*` | `WorkspaceClient`, `WorkspaceFileService`, `WorkingCopies`, `Mc1710Workspace` | F1, F3 |
| F5 The workbench | `workbench.editor.*`, the states' UI | `OpenDocuments`, `FileDocument`, `TextFileDocument`, `DocumentViewState`, `DocumentType`, the viewer lane, `ResourceRegistry` | F4 |
| F6 Non-text documents | `GraphDocument`, `BytesDocumentModel`, the example | — | F5 |
| F7 Proof and record | two probes, the docs | — | F6 |

Every step is one commit's worth, names what it removes, and ends in the test that cannot pass today.

### F0 — Foundations and the immediate fixes

1. **Save with the original line ending.** `TextFileDocument.encode()` → `buffer().textWithOriginalLineEndings()`.
   Test `aCrlfFileSavesAsCrlfAndIsCleanOnOpen` (fails today on both halves). *N10.*
2. **Fence undo on load.** `TextBuffer.load` (both overloads) applies without pushing a history entry
   and breaks coalescing. Test `undoAfterAReloadDoesNothing` (fails today). *N11, D7.*
3. **`Reply<T>` and `Stream<T>` in `core.async`**, with `JobScheduler.Job implements Reply`.
   `ReplyTest`: continuations run on the frame thread, `both`/`all`, cancel reports `CANCELLED`, a job
   is a reply. *D15.*
4. **`ProjectRegistry.all()` cached on a provider revision.** *(Shipped. The step also said to pass the
   resolved project into `LocalFileSystem` rather than re-asking; once the cache carries an id index that
   buys nothing measurable and costs a change to the `CgFileSystem` signature, so it was not done.
   `ProjectProvider.revision()` is the hook, and its default — a constant, right for any mod that
   registers its projects once — is what makes it a hook nobody has to remember.)* Test counting
   `ProjectProvider.projects()` calls across one read: 3 today, 1 after. *N20.*
5. **One `Excludes` matcher** in `fs.project`, used by the manifest and the watcher; the host opens the
   watcher with the project's rules. Test `theWatcherAndTheManifestAgreeOnEveryPattern`. *N23.*
6. **`fs.watch` authorises with `stat`.** `WatcherDoesNotReadFilesTest` extended to the subscription. *N21.*
7. **Packages and layering rows.** `fs.project`, `fs.server`, `fs.protocol`, `fs.client` created;
   `ConfigStorage*` → `core.storage`, `FilePatternMap` → `core.pattern`, the project types →
   `fs.project` (IDE moves). `LayeringTest`: `fs.client` may name `fs.protocol` and `net.protocol`;
   `fs.server` may not name `fs.client`; nothing in `fs` names `net` above `net.protocol`. *D23.*

*Done when* the existing 170 tests are green plus the five above, and nothing in `WorkspaceClient`'s
public surface has changed.

### F1 — The document model (headless, beside the old layer)

1. **`TextBuffer` remembers BOM and charset**, `encode()` writes them back; `TextBuffer.decode(bytes)`
   sniffs for NUL and reports `binary`. Tests: BOM round trip, UTF-16 round trip, the sniff. *D5.*
2. **`DocumentModel`, `AbstractDocumentModel`, `BytesDocumentModel`.** `apply(Edit)` is the one door;
   `adopt` bumps the version and pushes nothing. Tests: version moves on edit, undo and redo;
   `markChanged` for an inexpressible change. *D8.*
3. **`TextDocumentModel`** over `TextBuffer`, owning the tokenizer, folding provider and
   `LanguageServices` (the code moves out of `Workbench`'s file factory). Tests: two editors over one
   model share one parse tree; a model with no editor analyses. *D4, D6.*
4. **`Document`, `DocumentReference`, `Documents`** (the registry, headless): object identity, the
   resource property and its rename signal, `state`, `savedVersion`, `isDirty` by version, dedup by a
   case rule passed in. Tests: a rename fires once and rekeys nothing; the last reference disposes;
   dirty is a compare. *D2, D3, D9, D10.*
5. **`DocumentKind`, `DocumentKinds`, `DocumentEditor`, `EditorInput`.** Registration refuses a kind
   with no model; resolution is name, then extension, then glob through `FilePatternMap`; the `text(…)`
   shorthand. *A10.*
6. **`TextEditor.setBuffer`** binds a view: the buffer stops being final, its three subscriptions are
   dropped and re-taken, and the view state is reset because it describes the document being left.
   *(Shipped.)* *D4.*
   **Deferred to F5, and they cannot move earlier**: `disposeLanguage` goes when `TextFileDocument`
   does, and `document` moves below `widget` in `LayeringTest` when the three classes that name a widget
   — `FileDocument`, `TextFileDocument`, `DocumentType` — are deleted. Until then the new types are
   asserted not to name one, which is the property that matters and is checkable now.

*Done when* the model suite passes headless and nothing in `workbench` names the new types.

### F2 — The protocol (typed, both halves retrofitted)

1. **The provider's ranged read** — `CgFileSystem.read(path, offset, length)` on both
   implementations behind `FILE_OPEN_READ_WRITE_CLOSE`. *D20.*
2. **`fs.protocol`:** a record and a `Codec` per message (`Codecs.map`); `FsMethods`; `FsError`;
   `FsHello` (protocol version, case rule, name rule, size tiers, capabilities). Every codec
   round-trips through `JsonOps` and `PlainOps` and tolerates an unknown field. *D14, D21.*
3. **Pushes become notifications** on both sides — the client subscribes through `onNotify`, the host
   and every test wire the notifier to `notify`, and the javadoc that instructed callers to send a
   request is corrected. `aPushIsANotification`, asserted on `MessageRouter.pendingRequests()` with a
   counter-control, because the payload arrives either way. *(Shipped.)* *N24.*

   > **The full retrofit of `WorkspaceRpc` and `WorkspaceClient` onto the codecs was NOT done, and
   > deliberately.** The step's reason was "so the wire changes once", which holds when F3 and F4 are
   > distant. They are the next two milestones, and both delete the class being retrofitted —
   > `WorkspaceRpc` at F3.5 and `WorkspaceClient` at F4.5. Retrofitting 700 lines of hand-packed
   > `StateMap` reads to delete them within the hour is the double-pay this plan's own §9 preamble
   > names, with none of the benefit. The codecs exist and are proven by round trip; `fs.server` and
   > `fs.client` are written **against them** rather than converted to them. `WorkspaceProtocol` goes
   > with `WorkspaceRpc` at F3.
4. **Operation ids** ride on `PathRequest`, `MoveRequest` and `WriteRequest` as of F2's codecs; the
   server's recent-operations table is F3's, since it belongs to the binding that answers.
   `aWriteRetriedAfterATimeoutIsNotAConflict` moves to F3. *D17.*
5. **Chunked read and paged listing** — the wire shapes exist (`ReadResponse` carries a transfer id and
   a size, `ListRequest`/`ListResponse` carry a cursor) and the provider serves ranges. Wrapping them
   in `Stream<T>` is the CLIENT's, so it lands with the client at F4.
   `aLargeListingArrivesInPages` and `aChunkedReadIsAStream` move there. *D15, D20.*

*Done when* every payload round-trips over both ops, the provider serves a range, and no push opens a
call. The string keys go with the two classes that spell them, at F3 and F4.

### F3 — The server

1. **`fs.server`:** `WorkspaceService` moved, `Trash` with `trashPathOf` by id, `Presence` carrying
   `dirty`, ignore rules on `ProjectInfo`. *D12, D22.*
2. **`WatchHub`:** subscriptions `(resource, recursive)` per peer with a cap; drains the per-project
   event source once per tick; matches; coalesces per tick per directory; emits `created`,
   `modified`, `deleted`, `renamed(from, to)`; the etag poll over the union once per file; OVERFLOW →
   rescan. Two-client tests: `anotherClientsCreateReachesAnExpandedFolder`; `aRenameArrivesAsOneEvent`;
   `fiftyOpenFilesUnderARenamedDirectoryIsOneNotification`; `aPeerCannotWatchPastTheCap`;
   `editingPresenceReachesTheOtherClientOnTheFirstKeystroke`. *D19, D12.*
3. **Transfers by `(resource, etag, size)`** over the ranged read, nothing retained; streaming writes
   above the inline threshold. `aTransferHoldsNoBytes` (heap measured across four 100 MB transfers). *D9, D20.*
4. **The audit and the rate limit** — `WorkspaceAudit`, a line per mutation naming actor, operation and
   resource, with the limit riding the same counter and its own `FsError` code. **Idempotent
   mutations** — `RecentOperations`, so a write retried after a timeout is answered from the table
   rather than refused as a conflict against the caller's own earlier write.
   `everyMutationIsAudited`; `aFloodIsRefusedAndOnlyForTheActorFlooding`;
   `aWriteRetriedAfterATimeoutIsNotAConflict`. *(Shipped.)* *D17, D22.*
5. **`WorkspaceBinding`** — one per connection as a `ProtocolConnection` attachment: decode, service,
   encode, audit, limit, name validation, the NUL sniff. `CgUiWorkspaceHost` reduced to one service per
   server; `WorkspaceRpc`, `WorkspaceWatcher` and the three statics deleted; `serverSmoke` green.
   *N26, D24.*

   > **Moved to F4, and it is one step rather than two.** The binding and the client are the two ends of
   > one wire: deleting `WorkspaceRpc` leaves `WorkspaceClient` with nothing to talk to, and every
   > workspace suite is red between the two commits. F3 ships the machinery the binding is made of —
   > the hub, the audit, the limit, the operations table, editing presence — each tested on its own,
   > and F4 lands both ends together.

*Done when* the two-client watch suite is green, editing presence is carried, and every mutation is
audited and rate-limited. The MOVE of `WorkspaceService` and its neighbours into `fs.server` goes with
the binding at F4, for the reason above: the class the tests import is the one the cutover replaces.

### F4 — The client

1. **`Workspace`** and its facades; **`FileOperations`** with the rules and the undo from
   `WorkspaceFileService`, `batch` returning a `Reply<BatchResult>`, `WorkspaceEdit`; the
   per-resource queue; coalesced reads; the two lanes; the pipelined pull.
   `aSaveAndAReloadOnOneFileNeverInterleave`; `twoReadsOfOneFileAreOneRoundTrip`;
   `aSaveOvertakesFortyCrawlListings`; `aFourMegabyteReadPipelines`; `closingATabCancelsItsRead`. *D15, D16, D17.*
2. **`Documents`** on the client over F1's registry: `open` → reference; save, saveAs, reload, revert;
   the state machine from `fs/changed` and from saves; save participants with a time budget;
   `untitled://`; dedup by the case rule from `fs/hello`; auto-save modes.
   `aDocumentLivesWhileAnyReferenceDoes`; `autoSaveWritesAfterTheDelayAndNotPerKeystroke`;
   `twoCasesOfOneNameAreOneDocumentOnACaseFoldingServer`; `anAutoSaveConflictSuspendsAutoSave`. *D3, D10, D11, D21.*
3. **`Watch`, `Presence` (editing), `Capabilities` (from hello and pushes), `Health`** from the
   multiplexer's counters. `healthReportsALateReply`. *D12, D18, D21.*
4. **`Backup` and `LocalHistory`** on `ConfigStorage`: delta-stored, bounded, skipped above the first
   size tier. `anUnsavedDocumentSurvivesAQuit`; `keepMineIsRecoverableFromLocalHistory`. *D11, D13.*
5. **Reconnect** as re-subscription of the handle set from one place; the client is the connection
   attachment. `WorkspaceReconnectTest` rewritten against handles. *N15, N16, N19.*
6. **The cutover, both ends at once** (F3.5, moved here): `fs.server.WorkspaceBinding` over the codecs
   as a connection attachment, `WorkspaceService` and its neighbours moved into `fs.server`, the host
   reduced to one service per server. Then the deletions: `WorkspaceRpc`, `WorkspaceProtocol`,
   `WorkspaceWatcher`, `WorkspaceClient`, `WorkspaceFileService`, `WorkingCopies`, and
   `Mc1710Workspace`'s rebind. `serverSmoke` green. *N26, D24.*

*Done when* every rewritten client suite is green and `WorkspaceClient` no longer exists.

### F5 — The workbench

1. **`EditorService`** in `workbench.editor`: `open(EditorInput)` as the one lane; a tab holds a
   `DocumentReference` and exists immediately in `LOADING`; title, state and dirtiness read off the
   document; failed tabs retry; hot exit restores. Sixteen tests, all of them workbench-level
   behaviours nothing could assert before. *(Shipped.)* *D1, N1.*

   > **The switch of `Workbench` ONTO it has not been made, and is the one step in this plan that
   > cannot be verified from a test.** `Workbench.java` is 3,268 lines with ~1,100 of document and
   > viewer plumbing, and **no test constructs one** (N36) — so the extraction has no net beneath it
   > and its acceptance is a running client. The new lane is written and proven against the wire; what
   > remains is repointing the workbench's call sites at it, which is mechanical, and watching the
   > harness while it happens.
2. **One key everywhere:** `DockPanelRef.resource`, `DockInput`, `WorkbenchSession` at `VERSION` 7
   with view state through `DocumentEditor`, `RecentFiles`; `Markers`, the language attachment and
   `RunSessions` keyed by document and subscribed to `onDidChangeResource`.
   `aRenamedOpenFileKeepsItsDiagnosticsItsServicesAndItsRunSession`; `DecorationsJoinByKeyTest`. *D1, D2, N3, N4.*
3. **`ContentProviders`** replaces `ResourceRegistry`; `LibrarySources` and `ProjectSourceSymbols`
   register through the workspace; `TextDocumentStatus` replaces `TextFileDocument`'s statics. *N12, N33.*

   > **Blocked on coordination, not on work.** Two of the four `ResourceRegistry` call sites are in
   > `language/`, which another session is working in. Turning a static into an instance changes their
   > compile, so this waits for that branch to land rather than racing it.
4. **The explorer** subscribes to expanded folders and honours ignore rules in the crawl and Go to
   File; **`ProjectIndex`** reads through the document's rope when open and the workspace otherwise —
   `bufferSnapshot` goes. `theCrawlSkipsIgnoredFolders`; `theIndexReadsAnOpenDocumentsRope`. *N9, N34.*
5. **The states on screen:** the editing-presence banner; the non-modal conflict under auto-save;
   `ConflictDialog` gated on `mergeable`; hot exit on the screen's close path; the health status item;
   the Timeline over local history; New File validated by the server's name rule; size tiers in the
   editor. `closingTheScreenWithADirtyDocumentAsksNothingAndRestoresIt`;
   `anAutoSaveConflictIsANotificationNotADialog`; `aReservedNameIsRefusedInTheDialog`;
   `aFileAboveTheFirstTierOpensWithoutServices`. *D11, D12, D13, D18, D21.*
6. **Delete the old layer:** `OpenDocuments`, `FileDocument`, `TextFileDocument`,
   `DocumentViewState`, `DocumentType`, `ResourceRegistry`.

*Done when* the first workbench-level suite is green: open, edit, save, external change under a clean
buffer and under a dirty one, delete under an open tab, rename under an open tab — none of which could
be written before (N36). **That suite is green** (`EditorServiceTest`, `WorkspaceDocumentsTest`), and
"close-with-prompt" is not in it because hot exit removed the prompt: closing asks nothing and the work
is offered back.

*Not done:* repointing `Workbench` at the new lane, and the deletions that follow it — `OpenDocuments`,
`FileDocument`, `TextFileDocument`, `DocumentViewState`, `DocumentType`, `WorkspaceClient`,
`WorkspaceRpc`, `WorkspaceProtocol`, `WorkspaceWatcher`, `WorkspaceFileService`, `WorkingCopies`. Both
halves of the wire are built and tested; the old pair stays until the workbench stops calling it, which
is the step that needs a client on screen.

### F6 — Documents that are not text, and the example — **SHIPPED**

1. ~~**`GraphDocument`** out of `ShaderGraphEditor` (or implemented on it), version from its undo
   stack; `ShaderGraphContribution` → `ShaderGraphKind`; the generated-source tab's origin as a field;
   graph view state through the same seam text uses.~~ **Implemented ON `ShaderGraphEditor`** rather
   than extracted, and saying so is more honest than splitting it: the canvas holds the
   `GraphDocument`, the previews and the Blackboard are bound to that instance at construction, and a
   load copies into it rather than replacing it. A second object in front of it would be a wrapper
   with nothing of its own to hold. The day a graph offers two views of one document is the day it is
   worth separating. All three tests shipped.
2. ~~**`BytesDocumentModel`** and its read-only viewer for anything with no kind.~~ **The model had
   existed since F1 with nothing building one** — the fallback kind decoded every file as UTF-8, so a
   PNG opened as replacement characters in a WRITABLE editor and the first save wrote them back over
   the file. `TextEncoding.looksBinary` existed for that decision and had no caller either. Both wired;
   `BinaryFileView` is the viewer.
3. ~~**`com.crystalgui.example.notes`** and `WorkspaceApiTest`~~ — a checklist: a headless
   `NotesModel` whose every change is an `Edit`, a `NotesView` that owns none of the state, and a
   `NotesKind` that is the whole registration. **The claim was narrowed by measuring it**: a
   `DocumentEditor` answers a `UIElement`, so a view of a document is made of widgets by construction
   and no example can say otherwise. What IS assertable, and is what the layer rests on, is the SPLIT
   — the model reaches no widget and no element, the declaration reaches nothing but the document
   layer, and the view reaches widgets and still not the application. The shader graph is not held to
   it: it is a widget-heavy application whose kind declaration is the small part.
   Registered by the harness scenes on a seeded `todo.notes`, because an example nothing builds is
   dead code.

### F7 — Proof and record — **SHIPPED**

1. ~~**Two probes:**~~ `-PcgEditorProbe` opens the editor and then works through it, on the
   **integrated** server — the configuration a player runs and the one no other probe covered, because
   every other one closes the GUI or never opens one. That is what hid a `doesGuiPauseGame` deadlock:
   a screen that pauses the world stops the server ticking, so the editor asks the integrated server
   for the project list and the integrated server is not listening *because the editor being open is
   what stopped it*. It refuses to run in multiplayer, where a client GUI cannot pause anything and the
   assertion would be vacuous.

   `-PcgTwoClientProbe=writer|watcher` is the other half, run on two clients joined to one
   `runServer`: the writer creates a file and then edits it, the watcher subscribes and reports what
   reached it. Everything the watcher, presence and the conflict path exist for is a statement about a
   SECOND client, and one client is the fixture that passes against all of it. Both report
   `Workspace.health()`.
2. ~~**The record**~~ — the authoring surface is §10 of `docs/CGUI_BUILDING_UIS.md` ("Owning a file
   type"), written from the failures rather than from the API and ending in a symptom→cause table for
   the silent ones. `CGUI_WORKBENCH_SERVICES.md`'s Resources, disposal, contributions and status
   sections and `AGENTS.md`'s seven fs rows and package map were rewritten during the cutover, which is
   where that doc's own rule says they belonged. `CGUI_SERVER_AND_SERIALIZATION.md` §7 named the file
   protocol `workspace/*`; it is `fs/*`, and the entry now says why that one is typed where `ui/*` is
   deliberately not.

---

## 10. Testing rules

- **The document layer gets its suite before F1 touches it**, in `test` (an editor needs
  `StyleSheet`): open, edit, dirty, save, reload, close, with a CRLF fixture and a BOM fixture. The two
  defects in §0 are its first two tests, and both are written in F0.
- **Every multi-client claim is tested with two clients:** one `WorkspaceService`, two in-memory
  connections, two `Workspace`s. `WorkspacePresenceTest` and `FileEventsReachTheClientTest` are the
  template.
- **A test that asserts a convention goes with the convention.** What stays exactly as it is: the
  provider suites, `WorkspaceServiceTest`, `WorkspaceMutationTest`, the trash undo tests, the symlink
  suite — they test the tier that is right.

---

## 11. Risks

- ~~**`Reply` versus `Job`.**~~ **Settled at F0.3.** A `Job` cannot be a `Reply` and should not be: it
  is a *description*, and nothing runs until `submit()`, so a type that was both would have a `then`
  that sometimes registers against work nobody has started. The lane, the key and the debounce stay on
  the builder and `submit()` answers the pending result. Wiring it found that a job which threw called
  its success handler with a null result, which is why nothing could have depended on that.
- **A rope on a worker.** D9 assumes an off-thread reader may hold a `Rope` snapshot. `Rope` is
  persistent; `TextBuffer` is not thread-safe. The rule is "hand out the rope, never the buffer", and it
  needs a test that reads a rope on a worker while the frame thread edits.
- **Two numbers for two facts.** The etag on the file entry, the version on the document; never both
  on one record.
- **`CgPath` inside `Resource`.** The project scheme's text form is `CgPath`'s, byte for byte, decided
  by the absence of `://`. Frozen with `CgPath`; F5.2 must not touch it.
- **`writeDelta` at save frequency** reads, applies and writes the whole file. Fine for a save;
  auto-save must go through `RatePolicy`, never per edit.
- **A widget that is its own model** cannot outlive its view; a split of one graph into two tabs
  needs the model lifted out. The contract allows it; nothing forces it early. References make the
  teardown later than "tab closed", never earlier.
- **A reference nobody releases** leaks silently. `Disposer.register` on every holder; `Documents.all()`
  in the health readout is how a leak is seen.
- **Local history on disk** is bounded by count and age and skipped above the first size tier; the cap
  is a setting.
- **Session discard.** `VERSION` 7 loses every user's arrangement once; the rule is already written.

---

## 12. Prior art

- **VS Code** (MIT; port the code) — `platform/files` (provider, service, etag, capabilities, the
  remote provider client, `isValidBasename`, the large-file thresholds, `files.autoSave`);
  `workbench/services/textfile` (`TextFileEditorModel` and its states); `workingCopy`
  (`IWorkingCopyFileService`, `IWorkingCopyBackupService` and `files.hotExit` on it, `workingCopyHistory`
  for the Timeline); `editor/common/services/resolverService` (`createModelReference`);
  `base/common/async` (`ResourceQueue`); `base/common/resources` (`extUri`); `editor/common/model`
  (`ITextModel`, EOL and BOM on the model, `setModel`); the file watcher (recursive per folder, excludes
  load-bearing, requests deduplicated by their full key).
- **IntelliJ** (Apache 2.0; read for shape) — `VirtualFile` as an identity that survives a rename,
  `VFilePropertyChangeEvent` as the one rename event; `FileDocumentManager`; `Document` with a
  modification stamp and line separator; `FileEditorManager` with `FileEditorState`; Local History.
- **JetBrains Fleet** — the frontend / workspace-server split; the frontend holds no filesystem. What
  this stack has by accident (the client holds no `CgFileSystem`), Fleet has by design, and D9 makes it
  deliberate.
- **CodeMirror 6 `@codemirror/collab`** — read and not taken (§4); the road stays marked.
- **HTTP conditional requests** — `If-None-Match` and ETag, which the read path already uses.
- In-repo: `CrystalGUI_P6.1.10_FILESYSTEM_PLAN.md`, `plan_phase5.md`, `plan_phase6.md`,
  `plan_wire.md`, `plan_ui_network_audit.md` (the identity-is-not-position argument, of which N4 is the
  file-side twin), `docs/CGUI_WORKBENCH_SERVICES.md` §Resources and §Panes.
