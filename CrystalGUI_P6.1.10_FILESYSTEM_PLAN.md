# P6.1.10 — Remote project workspace

**Planned 2026-08-03.** Supersedes the six-line sketch in `CrystalGUI_P6_TODO.md` §6.1.10, and the first
draft of this document, which planned a desktop file browser — the wrong feature. See
[What changed](#what-changed-and-why).

> **The vision, in one paragraph.** A Minecraft server hosts **project directories** — code, scripts,
> assets — exactly as an IDE workspace does. A client opens a project and gets a full editing surface over
> files that live on the *server's* machine. In singleplayer the integrated server is that machine, so the
> path is identical rather than special-cased. This is **VS Code Remote**, not a file browser.

---

## Contents

1. [What changed, and why](#what-changed-and-why)
2. [Settled decisions](#settled-decisions)
3. [MVP scope](#mvp-scope--what-ships-first)
4. [Where we actually stand](#where-we-actually-stand--audited-2026-08-03)
5. [Projects and identity](#projects-and-identity-d12)
6. [The protocol](#the-protocol)
7. [Revisions, hashes, and why both](#revisions-hashes-and-why-both)
8. [Conflict handling](#conflict-handling)
9. [Caching](#caching-d13)
10. [Trust](#trust)
11. [Resolved — G1 to G7](#resolved-2026-08-03--g1-to-g7)
12. [Resolved — G8 to G13](#resolved-2026-08-03--g8-to-g13)
13. [Prior art](#prior-art--read-2026-08-03)
15. [The client side](#the-client-side)
16. [How this stays correct](#how-this-stays-correct)
17. [Work order](#work-order)
18. [Deliberately not doing](#deliberately-not-doing)
19. [Open questions](#open-questions)
20. [References](#references)

---

## What changed, and why

The first draft planned a platform SPI over the **client's** disk, with the security question framed as
"may a server-authored UI read the client's files?" and answered "never".

Wrong feature, wrong threat model. The files live on the server; the client is a frontend.

| First draft assumed | Actually |
|---|---|
| Client reads its own disk | Client reads the **server's** disk, over the wire |
| Risk: a server reading client files | Risk: **a client asking the server for `../../server.properties`** |
| The platform SPI is the hard part | The **protocol** is the hard part |
| Singleplayer is a local special case | Singleplayer is the **same path** |
| A file browser | A **remote workspace** with an editor over it |

Surviving from that draft: the audit, the LDLib2/Minecraft prior art, and the observation that the path
type is a one-way door. One piece of the client-disk SPI also survives — the **cache** (D13) is the only
thing the client writes locally.

---

## Settled decisions

| # | Decision | Settled |
|---|---|---|
| **D1** | What is a path? | **`project:path/within/it`.** A project is the root. Portable between machines, which the delta protocol requires. |
| **D2** | Read-only first? | **No — read and write from the start.** Editing is the point. |
| **D3** | Authorisation | **The implementing mod supplies the check**, consulted server-side on *every* operation with `(player, project, path, operation)`. |
| **D4** | Sync or async? | **Async.** Every operation is a round trip. |
| **D5** | Change notification | **Yes** — the server pushes `fs.changed`. |
| **D6** | Naming rules | **Permissive**, normalised at the edge. `ResourceLocation` restrictions do not apply; these are real files. |
| **D7** | Extend `CgResourceService`? | **No.** That reads *game assets*; this reads *project files*. Same verb, different noun. |
| **D8** | Browsing scope | **The whole project, nothing outside it.** Every file in the tree is listable and viewable; the project root is the sandbox. |
| **D9** | Binary support | **Yes, from the start.** Needs a byte primitive on `DynamicOps`. |
| **D10** | Transfer modes | **One read path, two write paths.** See below. |
| **D11** | Size ceiling | **Chunked with progress; hard cap 100 MB**, refused as *file too large to open*. |
| **D12** | Project identity | **A `ProjectProvider` per mod namespace**, enumerating `(id, displayName, root)`. The namespace owns *ids*; the mod chooses each *location*. |
| **D13** | Client caching | **Yes**, per directory and lazy. ~~Content-addressed~~ → **validated by `etag` (mtime+size)**, see [the port](#the-port--vs-codes-platformfiles-read-2026-08-03). |

### D10, in full

The tempting split — text vs binary by extension — is wrong twice: an extension allowlist is always
incorrect for somebody's file, and it makes *reading* carry a decision it does not need.

**Reading is always the same: bytes, chunked, one path.** An image and a `.java` file are the same
operation.

**Writing branches on what the client is holding, not on what the file is:**

- holds it as a **text document with a matching base revision** → send a `ChangeSet`
- anything else → whole-file, chunked

Knowable locally, unambiguous, and correct for the awkward cases by construction: a binary file cannot
produce a `ChangeSet`, so it takes the whole-file path without anyone remembering a rule.

Whether to *offer* the text editor is separate and later, answered by sniffing for a NUL byte in the
first few KB. Git's heuristic, and right about files whose extension lies.

---

## MVP scope — what ships first

> **Status: shipped, 2026-08-03.** Every "In" row below is implemented and covered by
> `core/src/headlessTest/java/com/crystalgui/headless/Workspace*Test.java`, and driven end to end by the
> harness scene `--mode=cgui-workspace`. `fs.changed` landed last; the "Out" list is unchanged and is now
> the post-MVP backlog.
>
> **What `fs.changed` actually is.** Polling, seeded with each watched path's current etag, over *only*
> what a client says it has open — a real `WatchService` is still deferred. It is **promptness, not
> correctness**: a stale write was already refused by the re-stat in `WorkspaceService.write` before any
> of this existed. This is how a client finds out *before* it tries to save. Two things it must not do,
> both pinned by tests: announce the same change on every poll (an undismissable prompt), and report a
> client's own save back to it (`WorkspaceRpc` calls `watcher.noteWritten` after a successful write —
> without it, saving asks the user whether to reload their own work, and looks exactly like the conflict
> the feature exists to report).

**The goal is a foundation in motion, not a product.** Everything below is judged by one question: *does
getting this wrong later mean a rewrite, or an addition?* Rewrites are in. Additions are out, however
obviously useful.

### In

| | Why it cannot wait |
|---|---|
| `CgPath` (`project:path`) and `ProjectProvider` | D1/D12 — the one-way door. Saved documents embed these. |
| Ported `FileSystemProviderErrorCode` | Every method returns one. Retrofitting changes every signature. |
| UTF-8 wire, LF-normalised documents | G1/G2 — get these wrong and files corrupt silently. |
| Byte payloads on `DynamicOps` | D10's single read path is bytes. A text-only MVP means a second read path later. |
| `etag` on write (dirty-write prevention) | The write path is unsafe without it. Replaces the revision counter. |
| `fs.manifest` carrying **`etag`** (mtime+size) | The door to caching, and free from the directory stat. Hashing contents to list a directory would read every byte of the project. |
| Root confinement + a `PathCaseSensitive` capability | Security and path identity. Neither is retrofittable. |
| Permission callback | The trust seam. |
| Atomic write (temp + rename) | Three lines, prevents data loss. |
| `fs.projects`, `fs.manifest`, `fs.read`, `fs.write`, `fs.create`, `fs.mkdir`, `fs.changed` | The minimum that is an editor. |
| Transfer id + chunk count **in the shape**, even when the count is always 1 | Keeps the protocol stable when chunking lands. |
| Symlink depth cap | Two lines, kills a hang. |

### Out — deferred, and each is purely additive

Client cache · chunked transfer machinery · `fs.writeDelta` · `WatchService` and polling ·
`fs.rename` / `fs.delete` · the conflict *dialog* · image and binary viewers · diff and
`Show Difference` · exclusion defaults · `fs.projectClosed` · notification coalescing · resume ·
multi-user presence

### The one change from an earlier answer

**MVP writes whole files, not deltas.** `fs.writeDelta` was settled earlier and stays the immediate next
item — but a whole-file write guarded by a revision check is *correct*, and it removes G1's hardest part
(byte↔UTF-16 offset conversion) from the critical path. Deltas are an optimisation for large files, and
the protocol already has a place for them.

The revision guard is what makes this safe rather than merely simple: a stale write is refused, not
merged, exactly as a stale delta would be.

### What "conflict handling" means in the MVP

The server refuses a stale write with `CONFLICT`. The client says so and offers **reload**. That is the
whole feature. IntelliJ's three-button dialog, `Show Difference`, and silent reload-when-clean are all
post-MVP polish on top of a refusal that already works.

---

## Where we actually stand — audited 2026-08-03

### Reusable as-is

| Need | Covered by |
|---|---|
| Bidirectional request/response | `Envelope.Request`/`Response`, `MessageRouter` (handlers, correlation, timeouts, cancellation), `ServerUiSession.onCall`/`.call` |
| **Content-addressed caching, already implemented** | `OpenWindow` carries a *hash*; a client holding it rebuilds with no transfer, one that does not sends `RequestDescription`. D13 is this, for files. |
| Canonical hashing | `ContentHash.of(ops, value)` / `canonicalBytes` |
| A transport with no network | `InMemoryTransport` |
| The delta primitive | `Change(from, to, insert)`, `ChangeSet` — what every edit already produces |
| Carrying carets through a foreign edit | `SelectionModel.mapThrough(ChangeSet)` — exactly what applying a remote change needs |
| Editing, syntax, folding, undo | `TextEditor`, `com.crystalgui.text` |
| Tree, detail list, virtualisation | `TreeView`, `TableView`, `ListView` |
| Tabs, splits, dialogs, menus, drag | `TabView`, `SplitView`, `Dialog`, `Menu`, `UIDragController` |
| Line-ending model | `com.crystalgui.text.LineEnding` |

### Genuinely absent — verified

| Missing | Evidence |
|---|---|
| **Bytes in the codec** | `DynamicOps` has string/number/boolean/list/map. No byte primitive. |
| **Listing** | `CgResourceService` has one method; it opens one stream. |
| **Writing** | Nothing in `core/` or `platform/` writes a byte. `core/` contains no `java.io.File` reference at all — this feature must not be what changes that. |
| **A path type** | `CgIO` passes `String` and normalises by convention. |
| **A diff algorithm** | Nothing. `Show Difference` is new work. |
| **Chunked transfer** | One `RpcResult` is one message. |
| **Any way to notice an out-of-band edit** | Resolved by G3 — re-stat guarantees correctness, a watcher only adds promptness. |

---

## Projects and identity (D12)

A mod registers a **provider** under its namespace. The provider enumerates projects; each names its own
root directory.

```
crystalshader:my-first-shader   →  <server>/crystalshader/projects/my-first-shader
crystalshader:server-scripts    →  <server>/config/crystalshader/scripts
```

**Why the namespace does not derive the directory.** If the layout were `<root>/<namespace>/<name>`, the
engine would have decided where every project lives — which forecloses exposing a world's `datapacks/`
folder, or a path an admin configured, or anything that already exists on disk.

**Why the id must be explicit.** D1 makes paths `project:path`, and those are saved *into documents*.
Derive the id from a directory and moving that directory silently breaks every saved reference. An
explicit, stable id cannot.

The engine ships `defaultRootFor(namespace, projectId)` so the common case is one line — not the only case.

Ids read `namespace:name`, matching `CgIO` domains, `StyleSheetRegistry` and `ElementRegistry` tags.

---

## The protocol

RPC over the existing channel. Every method is authorised server-side (D3) and rooted to its project (D8).

| Method | Dir | Purpose |
|---|---|---|
| `fs.projects` | C→S | Projects this player may see |
| `fs.manifest` | C→S | **One directory's** entries with `{name, isDir, size, hash, revision}` — the cache handshake (D13) |
| `fs.read` | C→S | Begin a transfer; returns a transfer id and chunk count |
| `fs.chunk` | C→S | Fetch chunk *n* of a transfer |
| `fs.writeDelta` | C→S | `{path, baseRevision, changes[]}` — the text path |
| `fs.writeChunk` | C→S | Whole-file write, chunked |
| `fs.mkdir` / `fs.delete` / `fs.rename` | C→S | Tree mutation |
| `fs.changed` | S→C | A path's content, name or existence moved |

`fs.list` and `fs.stat` are **not** separate methods — `fs.manifest` is a listing that happens to carry
the hashes, and a separate listing call would be the same query answered twice.

---

## Revisions, hashes, and why both

They look redundant and are not:

| | Answers | Needs | Survives a backup restore |
|---|---|---|---|
| **revision** (monotonic per file) | "is my delta based on the current state?" | **ordering** | ✗ — a counter rolls back and lies |
| **content hash** | "is my cached copy these bytes?" | **identity** | ✓ |

A delta declares the base revision it applies to; a mismatch is refused, never merged. A cache entry is
validated by hash, so it is self-checking — a server rollback cannot serve stale content undetected.

Content-addressing gives two things free: a file reverted to an earlier state hits the cache again, and
two projects containing an identical file store it once.

---

## Conflict handling

IntelliJ's model, because it is well understood and already familiar:

> **File Cache Conflict** — *Changes have been made to `X` in memory and on disk.*
> `Load File System Changes` · `Keep Memory Changes` · `Show Difference`

On `fs.changed`, the client compares the new revision against its base:

- **no unsaved local edits** → reload silently. No dialog for the common case.
- **unsaved local edits** → the dialog.

One mechanism serving two situations that look different and are not: another client saved, or somebody
edited on the host machine.

> **`Show Difference` is deferred.** No diff algorithm exists in the engine; Myers is its own item,
> ported from VS Code's `common/diff/`, not invented. **v1 ships Load and Keep** — a complete and honest
> dialog without the third button.

**Choosing `Load File System Changes` discards the local undo history for that document.** The stack
describes text that no longer exists, and replaying it would corrupt the file. VS Code does the same.
Stated here because silently keeping it is the tempting bug.

---

## Caching (D13)

The description cache, for files.

1. Client opens a directory → `fs.manifest(project, dir)`
2. Server returns `{name, isDir, size, hash, revision}` per entry
3. Client serves anything whose hash it already holds; fetches only misses

**Per directory and lazy**, matching a tree that expands lazily anyway. A whole-project manifest is a
large single response for a 5,000-file project and pays for directories nobody opens; per-directory makes
opening a huge project instant rather than merely fast.

**Cache key: `(serverId, hash)`.** Content-addressed, so the project id is not part of the key — the same
bytes in two projects are stored once. `serverId` is a **server-persisted UUID handed over at handshake**,
not an address: addresses change, and the same project name on two servers is not the same project.

**Eviction: LRU by total size, with a cap.** The cap is a client setting, not a protocol concern.

**This is the only thing the client writes to its own disk**, and it is an engine-owned cache directory.
It is not a route to browsing client files, and must not become one.

---

## Trust

The threat model is **a malicious or buggy client**. Three defences:

1. **Every path is resolved inside its project root, server-side** — `..`, absolute paths, and symlinks
   that escape after `toRealPath()`. Tested before implemented.
2. **Every operation is authorised** through the host callback: player, project, path, operation, with
   read and write as separate rights. Per operation — authorisation at open time is not authorisation
   for the next request.
3. **The host mod decides what a project is.** CrystalGUI registers no roots. A server exposing nothing
   has nothing to attack.

> **The client is not trusted with enforcement.** It may hide what a player cannot see, as a courtesy.
> The server must behave identically whether or not it did.

---

## Resolved 2026-08-03 — G1 to G7

These were the holes that would have produced wrong behaviour. All seven are now decided; the reasoning
is kept because three of them are the kind that look arbitrary later.

### G1 — Encoding and offset units · **UTF-8 on the wire, UTF-16 offsets, refuse non-UTF-8 as text**

- **File bytes are UTF-8** on the wire and on disk. No negotiation, no per-file encoding setting.
- **Delta offsets are UTF-16 code units**, over the **LF-normalised** document (see G2). That is exactly
  what `Change(from, to, insert)` already means, because a Java `String` is UTF-16.
- **The server converts**, and it converts with the *same `core/` code the client uses* — `LineEnding` and
  the rope are shared, so "what offset 412 means" is answered by one implementation, not two that must
  agree.
- **A file that is not valid UTF-8 will not open as text.** It falls to the binary path. Opening it
  lossily means saving it corrupts it, and the corruption is silent.

> **Why not byte offsets on the wire?** The client would need a parallel byte-offset mapping over its
> rope, maintained on every keystroke — state whose only job is to mirror another structure, which is
> what `TextEditor.xOfView` already refuses to do for the same reason. Converting once, server-side, at
> the point of application, is one place instead of everywhere.
>
> **LSP reached the same answer**: UTF-16 by default, because its editors hold UTF-16 strings. Ours do too.

- **BOM: stripped before decoding, recorded, re-emitted on write.** A UTF-8 BOM left in place becomes an
  invisible U+FEFF at offset 0 and shifts every offset in the file by one — silent, and it only appears
  for files produced by Windows tooling.

### G2 — Line endings · **LF in the document, original restored on write**

Not a free choice: `LineEnding`'s contract is already *"the document itself is always LF"*, and the rope
splits on `\n` alone, so a `\r` left in the text is a stray glyph on every line. Preserving raw CRLF would
mean rewriting the document model's central invariant.

So the existing design is adopted end to end, on **both** sides:

1. Server reads bytes → decodes UTF-8 → `LineEnding.detect` → `normalise` to LF. The detected ending is
   stored as file metadata.
2. All offsets, all deltas, all hashes-for-conflict live in **LF space**.
3. On write, `LineEnding.applyTo` restores the original ending before the bytes hit disk.

Both ends run the same `LineEnding`, so they cannot disagree. And because applying a delta rewrites the
file's tail anyway, the decode/normalise/apply/denormalise round trip costs nothing extra.

> **The content hash is over the RAW BYTES ON DISK, not the normalised text.** The hash's job is to detect
> that the file changed underneath us, including a change that only touched line endings — which a hash
> over normalised text would be blind to.

### G3 — Out-of-band edits · **correctness from re-stat, promptness from a watcher**

Three layers, and the order matters:

| Layer | Gives | Reliability |
|---|---|---|
| **Re-stat before every read and write** | **Correctness** | Total — it is on the operation that matters |
| `WatchService` where it works | Promptness | Best effort; quirky per platform, unreliable on network mounts |
| Poll of **open files only**, ~2 s | Promptness, portably | Total, and bounded by what clients actually have open |

**The guarantee never depends on the watcher.** Before applying any delta the server stats the file; if
size/mtime/hash moved, the revision is bumped and the write is refused with a conflict. So a delta can
never land on a file that changed behind us, whatever the platform's watching story is.

Watching and polling only make the client find out *sooner*, without touching the file. Building it the
other way round — correctness resting on `WatchService` — is the version that works on the developer's
machine and loses data on somebody's NFS mount.

### G4 — Rename and delete of an open file · **`fs.changed` carries a kind**

`MODIFIED` · `RENAMED(newPath)` · `DELETED`

- **MODIFIED** → the conflict logic above.
- **RENAMED** → the client retargets the open document, keeping edits *and* undo history. The bytes did
  not change, so there is nothing to reconcile.
- **DELETED** → the document becomes detached. Save turns into save-as, defaulting to the old path. Edits
  are never discarded because a file vanished.

### G5 — Transfer lifetime · **idle timeout, a per-player cap, no resume**

- **30 s idle** (no chunk requested) → the transfer is discarded.
- **4 concurrent transfers per player**, so a client cannot pin server memory by opening many and stalling.
- **No resume in v1.** The 100 MB ceiling makes a restart bounded, and resume is a protocol for a problem
  we have not measured.
- **A transfer is invalidated the moment its file's revision changes.** Without this, chunks from before
  and after a write are stitched into a file that never existed on disk — a torn read, and it would look
  like corruption in the editor rather than a protocol bug.

### G6 — Write atomicity · **temp file in the same directory, then atomic rename**

Write to a temp file **in the target's own directory** — same filesystem, so the rename is atomic; a temp
directory elsewhere silently degrades to copy-then-delete — `fsync`, then
`Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`. A crash or a full disk leaves the original intact.

### G7 — Case · ~~Windows convention everywhere~~ → **a per-provider capability**

> **Superseded by the port.** `FileSystemProviderCapabilities.PathCaseSensitive` makes this a property
> each provider reports, which is right: a Linux server genuinely has case-sensitive paths and a global
> rule would be wrong there and unwalkable back. The reasoning below still applies to *how* a
> case-insensitive provider folds — `Locale.ROOT`, never the default locale.


As chosen: it is the most common server platform, and it is also macOS's default.

- Path comparison folds case with `Locale.ROOT` — never the default locale, or Turkish `I`/`ı` makes
  `FILE.java` and `file.java` different on a Turkish server and the same everywhere else.
- The **stored** case is whatever is on disk. Listings show real names.
- One client cannot hold `Foo.java` and `foo.java` as two documents.

> **The consequence, stated because it is real:** on a genuinely case-sensitive Linux server, a project
> containing both `Foo.java` and `foo.java` has two files this protocol cannot distinguish. That is
> **surfaced as an error at listing time**, naming both paths — never silently resolved to whichever the
> directory happened to yield first.

---

## Resolved 2026-08-03 — G8 to G13

Kept short on purpose. Each is a decision, not a design.

- **G8 — `fs.create`.** Added to the protocol. An IDE whose New File does not work is not an IDE.
  Same authorisation and rooting as everything else. **MVP.**
- **G9 — symlink loops.** A **depth cap** (64 components). Two lines, kills the hang. A visited-set by
  real path is the thorough answer and is not needed until someone reports it. **MVP: the cap.**
- **G10 — directory rename/delete with open children.** The server sends one `fs.changed` per affected
  path. Correct, chatty, and simple; coalescing is a later optimisation. **Post-MVP** (rename/delete are
  themselves post-MVP).
- **G11 — a project disappearing.** `fs.projectClosed(project, reason)`. The client keeps every open
  document, detached, exactly as G4's `DELETED` does. **Post-MVP.**
- **G12 — exclusions.** A glob list on the `ProjectProvider`, applied server-side before any listing.
  The **hook exists from day one** because a manifest that once included `node_modules` is cached by
  clients; the default list ships post-MVP.
- **G13 — error vocabulary.** ~~An invented `CgFsError`~~ → **port `FileSystemProviderErrorCode`**, and
  keep conflicts one layer up as VS Code does. Still MVP and still first. See [the port](#the-port--vs-codes-platformfiles-read-2026-08-03).

---

## The port — VS Code's `platform/files`, read 2026-08-03

**This is the item's centre of gravity, and it existed before we started.** VS Code's own file layer is
MIT (`research_repos/monaco/LICENSE.txt`), and — crucially — it already solves *our exact problem*, not an
adjacent one: `diskFileSystemProviderClient` / `diskFileSystemProviderServer` are a filesystem whose
implementation lives in another process, spoken over a channel.

> **What is NOT available:** `vscode-server` itself is closed source, which is why `code-server` and
> `openvscode-server` exist. We do not need it. The provider/client/server triple is in the open
> repository and is the whole protocol.

| Ours | Theirs | Size |
|---|---|---|
| `CgFileService` | `IFileSystemProvider` (`common/files.ts`) | 1658 lines, mostly interface + errors |
| The RPC protocol | `diskFileSystemProviderClient.ts` + `node/diskFileSystemProviderServer.ts` | 262 + 344 |
| In-memory impl for tests | `common/inMemoryFilesystemProvider.ts` | **358 lines, already written** |
| Chunked read | `common/io.ts` (`readFileIntoStream`) | 133 |
| The service above the provider | `common/fileService.ts` | 1491 |

### Five things this corrects in the plan above

**1. `etag` is `mtime + size`, not a content hash.**

```ts
export function etag(stat: { mtime: number; size: number }): string {
    return stat.mtime.toString(29) + stat.size.toString(31);
}
```

The plan specified hashing file contents to build a manifest. That means **reading every byte of every
file in a directory in order to list it** — a performance bug designed in, and one that would only show
up on somebody's real project. VS Code takes both numbers straight from the directory stat: O(1) per
entry, no file opened.

It is also **one value doing both jobs** — cache validation *and* dirty-write prevention — where the plan
had a hash for identity and a revision counter for ordering. The trade is real and accepted upstream: a
write that preserves both mtime and size is missed. That is rare, and `ETAG_DISABLED` exists for callers
who cannot accept it.

> **Adopt `etag` wholesale. Delete the revision counter and the content hash.**

**2. Conflict detection belongs ABOVE the provider.**

`FileSystemProviderErrorCode` has no conflict member. Conflicts live one layer up as
`FileOperationResult.FILE_MODIFIED_SINCE`. The provider writes bytes; the service knows about etags.

The plan put `CONFLICT` in `CgFsError` beside `NOT_FOUND` — wrong layer, and it would have pushed etag
awareness down into every implementation including the in-memory one.

**3. Case sensitivity is a per-provider capability, not a global rule.**

`FileSystemProviderCapabilities.PathCaseSensitive`. G7 settled "Windows convention everywhere", which is
wrong on a Linux server where the user genuinely has case-sensitive paths — and it is exactly the kind of
global decision that cannot be walked back. **The server reports its own; the client adapts.**

**4. Chunking is `open`/`read`/`write`/`close` on a descriptor, not a transfer id.**

`FileOpenReadWriteClose` is a *capability*. A provider that only does whole files advertises
`FileReadWrite` and implements two methods; one that can stream advertises both. That is precisely the
"keep the shape, defer the machinery" the MVP section reached for — already designed, already proven, and
POSIX-shaped rather than invented.

**5. A capability bitmask is how one interface serves every backend.**

`None`, `FileReadWrite`, `FileOpenReadWriteClose`, `FileReadStream`, `FileFolderCopy`,
`PathCaseSensitive`, `Readonly`, `Trash`, `FileWriteUnlock`, `FileAtomicRead`… The plan had one flat
interface, which means either `UnsupportedOperationException` everywhere or a lowest-common-denominator
API. Capabilities are the answer, and read-only providers (a resource pack, later) fall out for free.

### Error codes — theirs, adopted

Replaces the invented `CgFsError` list. Two distinctions the invented one lacked are common in practice
(`FileIsADirectory` / `FileNotADirectory`), and one is specifically ours (`Unavailable` — the remote is
down, which a local filesystem never is).

```
FileExists · FileNotFound · FileNotADirectory · FileIsADirectory
FileExceedsStorageQuota · FileTooLarge · FileWriteLocked
NoPermissions · Unavailable · Unknown
```

### What we still have to decide ourselves

The port covers the filesystem. It does not cover:

- **Projects** (D12) — VS Code has workspace folders and a `scheme://` URI; our `project:path` is the
  same idea, and `URI` is the thing to model `CgPath` on.
- **Authorisation** (D3) — VS Code trusts its own server. We do not.
- **Text encoding and line endings** (G1/G2) — those live in `workbench/services/textfile`, above the
  file layer, and our `LineEnding` already matches the design.
- **The Minecraft transport** — `channel.call(name, args)` maps onto `MessageRouter`, but the packet
  limits are ours.

---

## Prior art — read 2026-08-03

### VS Code Remote — the architecture

A thin client over a workspace on another machine, with `FileSystemProvider` as the seam: one interface,
local or remote behind it, and the editor above cannot tell. **Take:** the seam, the whole-file-fetch /
delta-save asymmetry, and the revision-per-file model.

### IntelliJ — the conflict UX

The File Cache Conflict dialog. **Take:** the three options and the wording, minus `Show Difference` in v1.

### LDLib2 — `editor/resource/`

`FilePath`, `FileResourceProvider`/`PackFileResourceProvider`, `FileNode implements ITreeNode`, `FileMenu`.
**Take:** the provider split, the lazily-expanding tree node, and `normalizePath`'s rules — backslashes to
`/`, collapse repeats, strip trailing.

**Deliberately not ported:** `FilePath` carrying a `java.io.File` *and* a nullable `ResourceLocation`,
eagerly building `new File("assets/ns/path")` for pack entries — a file that will never exist, in a field
callers can reach. D1 exists to avoid that conflation.

### Minecraft

`PackResources.listResources`/`getNamespaces` prove enumeration is available; `LevelStorageSource` and the
loader config directory are writable roots. Relevant to *where a project may live*, not to the protocol.

> **1.7.10 unverified.** All read from `research_repos/mc1201_sources/`. Its equivalents and — more
> importantly — its custom-payload size limit must be checked before chunk sizing freezes.

---

## The client side

Assembly over finished parts, which is why it is last:

- **Project tree** — `TreeView` over a lazily-expanding source backed by `fs.manifest`
- **Editor tabs** — `TabView` + `TextEditor`, one document per path
- **Image preview** — a `UIElement` with a `background` built from fetched bytes
- **Anything else** — name / size / type, and *no viewer for this file type*. Additive later.
- **Conflict dialog** — `Dialog`, two buttons in v1
- **Progress** — determinate bar during chunked transfer; *Loading project…* while a manifest resolves. **📄 Designed in full 2026-08-19: [`CrystalGUI_P6.1.13_PROGRESS_PLAN.md`](CrystalGUI_P6.1.13_PROGRESS_PLAN.md)** — this line was the whole specification, and the feature turned out to have two consumers before this one (the engine-band download, and the MCP mapping fetch that already ships silently on a bare thread), so it is built there rather than here

---

## How this stays correct

- **`InMemoryTransport` is the workhorse.** The whole protocol is testable headlessly, both sides in one
  JVM, no Minecraft and no GL. The single biggest reason this design is worth its ceremony.
- **A traversal suite, written first** — `..`, absolute paths, symlinks, encoded separators. It should
  fail before a resolver exists.
- **An encoding suite** (G1) — non-ASCII round trips, and a delta whose offsets land mid-astral-plane.
- **A line-ending suite** (G2) — CRLF preserved across an edit that does not touch those lines.
- **A revision/conflict suite** — stale delta refused, clean reload silent, dirty reload prompts.
- **A cache suite** — hash hit serves no bytes; hash miss fetches; a rolled-back server is detected.
- **`headlessTest`** proves `core/` still loads with no GL and no MC.
- **The harness** — a `cgui-workspace` scene over `InMemoryTransport`.

---

## Work order

### MVP — the foundation

| # | Step | Why here |
|---|---|---|
| 1 | Port `FileSystemProviderErrorCode` + capability bitmask | Every signature below returns one; capabilities decide which exist |
| 2 | `CgPath` + `CgFileEntry` + round-trip tests | The one-way door |
| 3 | `ProjectProvider`, registry, `defaultRootFor` | What every path is relative to |
| 4 | Byte primitive on `DynamicOps` (+`JsonOps`, `PlainOps`) | The read path carries bytes |
| 5 | Port `IFileSystemProvider` + **`inMemoryFilesystemProvider`** + traversal suite | 358 lines of it are already written and MIT. Security tests before the resolver |
| 6 | Protocol over `InMemoryTransport`, both sides | `projects` / `manifest` / `read` / `write` / `create` / `mkdir` / `changed` |
| 7 | `etag`, re-stat-on-access, `FILE_MODIFIED_SINCE` **above** the provider | The correctness core |
| 8 | Permission callback | The trust boundary, before any real disk |
| 9 | Harness `cgui-workspace` scene over `InMemoryTransport` | First end-to-end run, still no game |
| 10 | One real `CgFileService` — the harness's | Real disk, one platform, atomic write |

**Step 10 is the MVP line.** At that point a project opens, a tree browses, a file edits and saves, a
stale write is refused, and none of it needs Minecraft running.

### Immediately after

| # | Step | |
|---|---|---|
| 11 | `fs.writeDelta` | The optimisation the protocol was shaped for |
| 12 | Chunked transfer + the 100 MB ceiling + transfer lifetime | When a file exceeds one packet |
| 13 | Per-loader `CgFileService` — mc1201 ×3, mc1710 | Needs the 1.7.10 payload limit verified first |
| 14 | Client cache | Hashes are already on the wire |
| 15 | `fs.rename` / `fs.delete`, the conflict dialog, watching | Polish on a working base |
| 16 | Exclusion defaults, viewers, diff | Additive, in any order |

Steps 2–4 are the expensive-to-reopen doors — the path type, the project seam, and the byte primitive.
Everything from step 6 onward is testable without a game running, which is what makes the MVP line
reachable in one sitting rather than one milestone.

---

## Deliberately not doing

- **Access outside a project root.** Ever. D8.
- **Merging.** A stale delta is refused and the user chooses; nothing merges silently.
- **Locking.** Optimistic concurrency via revisions; the dialog is the answer, not a lock.
- **Viewers for every file type.** Text and images in v1.
- **A general VFS.** Projects are the only roots.
- **Running anything.** This stores and edits scripts. Executing them is another feature.
- **Multi-user presence.** Seeing another player's caret is a natural extension and explicitly not v1.

---

## Open questions

| Question | Notes |
|---|---|
| Is `fs.changed` coalesced? | A build writing 500 files should not send 500 packets. Debounced per directory, window unmeasured. |
| Does the fs protocol version separately from the UI protocol? | `OpenWindow` already carries a `protocol` int. A mismatch on file ops is worse than on UI. |
| Rate limiting | A client can spam `fs.manifest`. Server-side throttle, or trust the authorisation callback to be cheap? |
| Two tabs on one path in one client | Presumably one document per path per client, but it should be stated. |
| Should `CgIO` eventually route through this? | Tempting, out of scope, and do not design something that forecloses it. |

---

## References

- `CrystalGUI_P6_TODO.md` §6.1.10 — the sketch this supersedes
- `CrystalGUI_P6.1.8_CONFIGURATOR_PLAN.md` — the plan-doc pattern
- `core/src/main/java/com/crystalgui/net/` — `MessageRouter`, `ServerUiSession`, `InMemoryTransport`, and
  the `OpenWindow`/`RequestDescription` cache handshake D13 copies
- `core/src/main/java/com/crystalgui/text/Change.java`, `ChangeSet.java`, `LineEnding.java`
- `core/src/main/java/com/crystalgui/serialization/DynamicOps.java`, `ContentHash.java`
- `research_repos/LDLib2/.../editor/resource/`
- `research_repos/monaco/src/vs/editor/common/diff/` — for step 14
