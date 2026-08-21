# Classpath Navigation — Reaching Code That Is Not In The Workspace

**Status**: N1 shipped; N2–N5 planned
**Depends on**: `plan_viewer.md` (V1–V5, shipped) — the library viewer is the *destination* this plan
builds routes to.

> The viewer answers "show me `java.util.ArrayList`" once you already have a reference to click.
> This plan answers the other half: **finding** something you cannot already see. Today the project
> explorer shows the workspace and nothing else, so every class on the classpath — the JDK, every
> dependency, every other module — is reachable only by Ctrl+clicking a name that happens to be
> written in a file you already have open.

---

## 0. What exists already — much more than the gap suggests

The destination is finished. What is missing is only the ways in.

| Piece | Where | State |
|---|---|---|
| `library:` resource scheme | `core/fs/Resource` | Shipped |
| Library content provider — source, else decompile | `language/java/LibrarySources` | Shipped |
| CFR decompiler in the engine band | `language/java/…` | Shipped |
| Attached-source discovery (`-sources.jar`, `src.zip`, bundled `assets/<ns>/sources/`) | `language/java/classpath/AttachedSources`, `BundledSources` | Shipped |
| Read-only viewer tab, kind icon, `static`/`final` marks, path + kind tooltips | `Workbench.openResource`, `SymbolIcon`, `Tooltip` regions | Shipped |
| `TreeDataSource<T>` — `roots`/`children`/`hasChildren`/`title` | `ui/elements/tree/TreeDataSource` | Shipped |
| **`TypeIndex.childrenOf(package, partial) → (packages, types, truncated)`** | `language/java/classpath/TypeIndex` | Shipped — **a classpath tree source in all but name** |
| `TypeIndex.matching(prefix)`, `.kindOf(Entry)`, `.similar(name)` | same | Shipped, bounded at `MAX_TYPES = 60_000` |
| `HostClasspath.detect()` | `language/java/classpath/HostClasspath` | Shipped |
| `ProjectProvider` / `ProjectRegistry` — pluggable project enumeration | `core/fs/` | Shipped; the harness already registers one |
| `QuickPick`, `GoToFile`, `SearchMatch` tiers | `ui/elements/chrome/`, `workbench/GoToFile` | Shipped |
| `FilteredTreeSource`, `TreeSearch`, file icons, decorations, `reveal` | `ui/elements/tree/`, `workbench/` | Shipped |

`TypeIndex` is the one worth pausing on. It is built for completion, it is already paid for, and its
`childrenOf` returns exactly `(sub-packages, types)` for a package prefix. A package tree over the
whole classpath needs **no new I/O whatsoever** — only a seam to reach it (§2.2).

---

## 1. The measured finding — a jar is already a directory tree

`LocalFileSystem` uses `java.nio.file.Files` exclusively (**zero `toFile()` calls**, verified), and
`WorkspaceProject` accepts any `Path` with no validation beyond non-null. The JDK's own zipfs
provider therefore makes a jar browsable through the existing filesystem stack **with no new
filesystem code at all**.

Probed against the real Gradle cache on this machine:

```
mounted gson-2.11.0.jar in                 9 ms
/com, /META-INF                            isDirectory = true
/com/google/gson/stream                    exists, isDirectory, 7 entries
                                           ^ no directory entry in the zip — zipfs synthesises it
Files.readAllBytes(/com/google/gson/Gson.class)   25996 bytes
Files.getLastModifiedTime(<a directory>)   2024-05-19T08:53:38Z   (so CgFileEntry is satisfiable)
119 of 120 jars mounted at once            271 ms, ~45 MB heap
```

Three conclusions, and each is load-bearing:

- **Mounting is cheap enough to do on expand.** 9 ms is imperceptible, so nothing needs mounting
  until a person opens that jar's node.
- **It must be, because holding them all is not free.** ~45 MB per 120 jars extrapolates to ~130 MB
  for this repo's 359 — well past what a mod may take for a browsing affordance.
- **A bad archive throws.** One jar in 120 failed to mount. That has to degrade to an empty node
  with a decoration, never to an exception out of a tree listing.

> The `BundledSources` scan is the precedent for the cost of touching every jar: **232 ms cold,
> ~105 ms warm over 359 jars and 268k entries**, paid once per classpath. Enumerating the jar *list*
> is affordable; mounting them all is not.

---

## 2. The critical review — what breaks a naive sketch

### 2.1 `ProjectFileTree` is `CgPath`-typed end to end

Not at one boundary — throughout. `TreeView<CgPath>`, `WorkspaceTreeSource implements
TreeDataSource<CgPath>`, `Map<UIElement, CgPath> rowItems`, `Signal.Value<CgPath> onFileChosen`,
`reveal(CgPath)`, `selectedPath()`. Nothing that is not a `CgPath` can appear in that tree.

Two ways out, and they are not close:

- **Make the tree generic.** Every explorer part — `FilesRenderer`, `ExplorerDragAndDrop`,
  `ExplorerFind`, `ExplorerEditing`, `ExplorerClipboard`, `WorkspaceTreeSource` — is written against
  the concrete type. This is a large refactor of shipped, working code, and it buys a generality
  nothing else has asked for.
- **Make libraries speak `CgPath`.** `CgPath` is already `project:path` with `PROJECT_SEPARATOR = ':'`.
  A library entry is `CgPath.of("<libraries>", "netty-1.8.8.jar/io/netty/bootstrap/Bootstrap.class")`.

**Decision: libraries speak `CgPath`.** Everything downstream — search, compact folders, decorations,
`reveal`, type-ahead, the find bar — keeps working with no change, because it is all written against
paths and none of it cares where a path resolves.

> The angle bracket in `<libraries>` is deliberate: `CgPath` refuses `U+0000` and the codebase already
> uses `U+0001` for a placeholder path, so a reserved-looking project id is the established way to
> spell "not a real project". A real project cannot collide with it because a provider names its own
> id and no filesystem path produces one.

### 2.2 `core/` cannot see `TypeIndex` or `HostClasspath`

Both live in `language/`, and **`core/` must never depend on `language/`** — that is what keeps
tree-sitter's natives and ECJ's ~15 MB off a dedicated server. Verified: no reference to either type
exists anywhere under `core/src/main/java`.

So neither of the two obvious data sources is directly reachable from the explorer. The seam already
exists in two shapes, and the right one differs per model:

- **For the jar tree**: `ProjectProvider` — core-side, pluggable, and already how the harness supplies
  its roots. A `ClasspathProjectProvider` in `language/` (or in the harness) hands over the mounted
  jars, and the explorer never learns what a classpath is.
- **For a package tree**: a new core-side SPI, shaped exactly like `ResourceContentProvider` — core
  declares the interface, `language/` implements it over `TypeIndex`.

### 2.3 Nothing enforces read-only

There is no permission constant wired into the explorer's editing paths. `WorkspacePermission` exists
as a type but the explorer's write affordances do not consult anything equivalent — they are gated on
what the *widget* offers, not on what the path allows.

Every one of these has to learn to refuse on a library path:

| Affordance | Owner |
|---|---|
| F2 inline rename | `ExplorerEditing` |
| Cut / Copy / Paste | `ExplorerClipboard`, via `ClipboardActions`/`DataProvider` |
| Drag and drop (as a **drop target**, and as a source into the workspace) | `ExplorerDragAndDrop` |
| New File / New Folder | `ExplorerCommands` |
| Delete | `ExplorerCommands` |

**This is the piece that does damage if skipped**, and it fails in the worst way: F2 on a jar entry
opens an inline editor that looks like it works, and the failure arrives from the filesystem layer
afterwards — or does not arrive at all, because zipfs is writable and a rename would *succeed*,
silently mutating a dependency in the Gradle cache that every other project on the machine shares.

> Mount read-only explicitly (`Map.of("create", "false")` and never opening for write) **as well as**
> refusing at the widget. The viewer already learned this lesson once — `plan_viewer.md` §1.7 records
> that read-only enforced only at the widget is enforcement in one place too few.

### 2.4 The tree's `children()` is a REQUEST, and the classpath is LOCAL

`WorkspaceTreeSource.children(parent)` returns `List.of()` and calls `request(parent)` when it has no
cached listing — the answer arrives later through `WorkspaceClient`. That client may be remote:
`WorkspaceRpc`, `WorkspaceProtocol` and `WorkspaceActor` all exist because a workspace can live on a
dedicated server.

**A library root is not like that.** The classpath that matters is the one the *scripts* compile and
run against, which is the local JVM's — `HostClasspath.detect()` is a local call by construction. So
a `<libraries>` root served over an RPC to a server would enumerate the *server's* jars, which is a
different and useless answer.

Two consequences. The zipfs projects must be registered into the **local** `ProjectRegistry` that the
in-process service reads; and where a workspace is genuinely remote, the libraries root has to
short-circuit the client rather than travel. Left as a stated constraint rather than designed here,
because every host that exists today is in-process — but a design that cannot express it is one that
gets rewritten the first time a server-backed workspace appears.

### 2.5 The open path opens a text document, not a viewer

`Workbench` wires `fileTree.onFileChosen.connect(this::openFile)`, and `openFile(CgPath)` opens an
editable `FileDocument`. A `.class` under a library must instead go to
`openResource(Resource.of(SCHEME_LIBRARY, binaryName))` — the path `plan_viewer.md` already built.

A one-line fork keyed on `path.project()`, plus the mapping from a jar-relative path to a binary
name: strip the jar segment, drop `.class`, replace `/` with `.`. Inner classes need care — `Foo$Bar`
resolves to its **top-level** file, which the viewer already handles (`plan_viewer.md` §1.9).

Non-class files under a jar (`META-INF/MANIFEST.MF`, a `.properties`, a `version` file) are ordinary
text and should open read-only in the normal editor. That is a third case, not a second.

### 2.6 Display names are not file names

IntelliJ shows `Gradle: com.mojang:netty:1.8.8`, not `netty-1.8.8.jar`. The coordinate is derivable
from the cache layout — `…/<group>/<artifact>/<version>/<sha1>/<artifact>-<version>.jar` — and
`BundledSources` already carries the test that identifies such a directory: **forty hex characters**,
one string comparison before any filesystem call.

Anything that does not match that shape falls back to the file name, which is correct for a loose
directory on the classpath, a mod jar in `mods/`, or a build output.

### 2.7 Icons key on the file name, so every `.class` looks alike

`FileIconTheme.iconFor(name, …)` resolves by extension. Every entry in every jar would draw one
generic glyph, which loses the single most useful distinction in a package listing — class vs
interface vs enum vs annotation.

`TypeIndex.Filtered.kindOf(Entry)` already answers it **with no engine call**, and `SymbolIcon` (built
for the completion popup, now also drawing library tabs) already renders it. This is the argument that
most favours the package model: it gets correct icons for free, where the jar model has to ask
something per visible row and cache it.

### 2.8 Two different products wear the same name

They are not the same feature and should not be built as if they were.

| | **A. Package tree** (`TypeIndex`) | **B. Jar tree** (zipfs) |
|---|---|---|
| Roots | packages | jars, under one `External Libraries` row |
| Leaves | types | every file, `META-INF` and resources included |
| New I/O | **none** — the index is already built | mount per jar, evicted |
| Icons | correct kinds, free (§2.7) | one per row, needs a lookup |
| Matches the reference | no | yes |
| Can show a non-class file | no | yes |
| Bounded | yes, `MAX_TYPES` | by what is mounted |

B is what the screenshots show. A is cheaper by an order of magnitude and answers most of the
questions people actually ask a library tree. They compose — the same root can offer both as a
presentation toggle, the way the reference offers flatten-packages.

---

## 3. Design decisions, stated once

1. **Libraries are a pseudo-project, not a new node type.** `<libraries>` as a `CgPath` project id.
   §2.1.
2. **The explorer never learns what a classpath is.** It arrives through `ProjectProvider`, the seam
   that already exists. §2.2.
3. **Mount lazily, evict by LRU, mount read-only.** §1, §2.3.
4. **A failed mount is an empty node with a decoration**, never an exception. §1.
5. **Read-only is enforced at the widget AND at the mount.** §2.3.
6. **A `.class` opens the viewer; anything else under a jar opens read-only in the editor.** §2.5.
7. **Ctrl+N is the primary affordance and the tree is secondary.** In the reference, a tree is for
   browsing and a name-search is for reaching — and reaching is what people do a hundred times a day.
8. **Nothing here may make `core/` depend on `language/`.** §2.2.

---

## 4. Milestones

### N1 — Classpath types in Go to File — **SHIPPED**

`QuickPick` over `TypeIndex.matching(prefix)`, ranked by the existing `SearchMatch` tiers, opening
through `Workbench.openResourceAt`, behind the core-side `text.lang.TypeSearch` SPI from §2.2 so the
picker can ask without `core/` naming `TypeIndex`.

> **Revised while building**: this said "a `GoToClass` beside `GoToFile`, sharing its substrate", and it
> shipped as **one picker**. Two popups that look identical and behave differently make you decide, before
> you start typing, whether the thing you want is in the workspace — which is the thing you are searching
> to find out. `Mod+P` / `Mod+T` / `Mod+Shift+T` are three doors into it, as IntelliJ's `Ctrl+N` and
> `Ctrl+Shift+N` are two doors into one window.

**Shipped**: typing `ArrLi` from anywhere opens `java.util.ArrayList` in a viewer tab — source where
attached, a decompilation where not — with project files listed above classpath types, kind glyphs on
types and file icons on files, and `Main.java:42` opening at line 42.

> **Researched in full in `plan_goto.md`** — IntelliJ's Search Everywhere read out of the platform
> source: the contributor model and its real sort weights, the location-suffix regex verbatim, the
> matching rules, the row anatomy, and a cut list. That document supersedes this row for scope.

> Deliberately first, and deliberately shippable alone. It is a fraction of the rest of this plan and
> it is most of the value.

### N2 — The libraries root exists

`ClasspathProjectProvider` enumerates `HostClasspath.detect()`, deriving display names per §2.6. One
`<libraries>` root row in the explorer; expanding it lists jars; expanding a jar mounts it lazily
(§1) and lists its tree through the existing `LocalFileSystem`.

**Exit**: the root appears, expands, and a jar's packages and classes are browsable; 359 jars cost one
row each and no mount until opened; a corrupt jar shows an empty node and logs once.

### N3 — Read-only, everywhere it has to be

§2.3 in full: five affordances taught to refuse, plus the read-only mount. Each refusal tested — and
tested through the **command**, not the widget, since a menu bar and a context menu resolve their
target differently and only one of them is exercised by driving a row directly.

**Exit**: no route from the explorer mutates anything under `<libraries>`; the affordances are dimmed
rather than hidden, per the registry's own rule.

### N4 — Opening, and the icons

The §2.5 fork; kind icons via §2.7; `Show in Project View` from a viewer tab, over the existing
`ProjectFileTree.reveal`.

**Exit**: double-clicking `Bootstrap.class` opens the viewer on `io.netty.bootstrap.Bootstrap`;
`MANIFEST.MF` opens read-only in the editor; a class row draws the right kind glyph; a viewer tab can
reveal itself in the tree.

### N5 — The package presentation

Model A from §2.8 as a toggle on the same root, over the SPI N1 already added.

**Exit**: the root can be shown as packages-and-types rather than jars-and-files, and the two agree
about what exists.

---

## 5. Deliberately not doing

| Not doing | Why |
|---|---|
| **The JDK module tree** — `< openjdk-25 >` → `java.base` → `java`/`javax`/`jdk` | Needs the `jrt:` filesystem and a second node model with its own root kind. `src.zip` already covers the case that matters (quoting a real declaration), and `java.util.ArrayList` is reachable by Ctrl+N from N1 |
| Other *projects* on the classpath as separate roots | The workspace already has a project concept; a second module system on top of it is a different plan |
| Editing anything under a library | §2.3. The reference does not either |
| A dependency graph, scopes, or "which module pulls this in" | That is build-tool information this stack does not have and should not invent |
| Making `ProjectFileTree` generic | §2.1. Large, and buys generality nothing has asked for |
| Search *inside* library sources ("find in path" over jars) | A different feature with a different cost model — it needs an index of contents, not of names |

---

## 6. Risks

| Risk | Shape it takes | Mitigation |
|---|---|---|
| **Mounts leak** | A jar expanded once is never closed; heap climbs across a session and nothing points at the tree | LRU with a hard cap, closed on collapse; the existing `Disposable`/`Disposer` ownership tree is the right owner. §1 measured the cost per mount, so the cap can be chosen rather than guessed |
| **A rename silently succeeds** | zipfs is writable; renaming inside a Gradle cache jar corrupts a dependency shared by every project on the machine | §2.3 — refuse at the widget *and* mount read-only. Two places, because one has already proven insufficient once |
| **`TypeIndex` truncation reads as absence** | `MAX_TYPES = 60_000` and `MAX_RESULTS` bound every query; a class that exists but is past the cap looks like a class that does not exist | `Match.truncated` is already carried — surface it. A list that says "showing 100 of many" is honest; one that silently stops is not |
| **The remote-workspace case** | A server-backed workspace enumerates the server's jars — a plausible, wrong answer | §2.4. Stated now so the seam can express it; not designed until a host needs it |
| **Cold index on first Ctrl+N** | The first press stalls while 60k types are scanned | The index is already built for completion, so in practice it is warm by the time anything is typed. If not: the `QuickPick` opens empty and fills, which is what a search box should do anyway |
| **Display-name derivation is wrong for an unfamiliar layout** | A jar shows as `Gradle: …:…` with nonsense coordinates | §2.6 gates on forty hex characters *before* any filesystem call and falls back to the file name. Wrong-looking is worse than plain, so the gate must stay strict |

---

## 7. What this plan is worth, honestly

N1 alone is a small fraction of the total and is most of the benefit. N2–N4 build the thing in the
screenshots, and the thing in the screenshots is genuinely useful — but it is a *browsing* affordance,
and browsing is the rarer half of navigation.

The recommendation is to ship N1, use it, and let that decide whether N2 onwards is worth its read-only
enforcement work. The one thing not worth doing is N2 without N3.
