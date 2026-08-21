# Phase 5 — the remote workspace, made usable

**Scoped 2026-08-21**, immediately after Phase 4 closed and the first genuinely two-process test ran.

Phase 4 answered *can the workspace be served over a wire* — yes, verified on a dedicated server with the
files on the server's disk and nowhere near the client's. Phase 5 is the gap between that and **a person
being able to use it without losing work**, plus the things that only became reachable once there was a
real server.

> **Scope.** Networking, the workspace, and UI over the wire. The shader node graph (`P6.3`) is
> deliberately out — it is tracked in its own document and does not interact with any of this.

---

## Provenance, and what was actually checked

Every item below was verified against the code on 2026-08-21 rather than carried over from a plan. Where
something is a *decision* rather than a defect it says so, and where the evidence is a file and a line it
gives them.

| Established | How |
|---|---|
| The workspace really is served from the server's disk | `runServer` + `runClient -PcgJoin=localhost:25565`; `run/server/crystalgui/workspace/remote-probe.txt` exists and `run/client/…` does not |
| The editor works against it end to end | Browsed, edited (`README.md *`) and **ran** a script off the server's disk |
| The server-side surface is exactly 15 methods | `grep registry.register(WorkspaceProtocol.*` in `WorkspaceRpc` |
| The command layer has no side concept at all | `Command`, `CommandRegistry`, `CommandContext` — the only two occurrences of "server" are javadoc asides (`Command.java:90`, `CommandRegistry.java:25`) |

### The whole server-side surface

`WorkspaceRpc.installOn` registers these and nothing else. Each runs
`authorise(actor, path, operation)` **per call**, which is what makes `OperatorsMayWrite` work — the read
column passes for anyone, the write column does not.

| Read | Write | Trash | Watch |
|---|---|---|---|
| `fs.projects` | `fs.write` | `fs.delete` | `fs.watch` |
| `fs.manifest` | `fs.writeDelta` | `fs.restore` | `fs.unwatch` |
| `fs.read` | `fs.create` | `fs.purge` | `fs.changed` (server→client) |
| `fs.readChunk` | `fs.mkdir` | `fs.trashList` | |
| | `fs.rename` | | |

### How a command reaches the server, and what decides its side

```
palette / keybind / context menu
  → Command.execute(CommandContext)      client
  → workbench.files().delete(path, …)    client   (WorkspaceClient)
  → fs.delete                            ─── the wire ───
  → WorkspaceRpc handler                 server
  → WorkspaceService.authorise + delete  server
  → LocalFileSystem                      server disk
```

**Nothing marks a command's side, and nothing needs to.** The client physically cannot reach the
server's files: `Mc1710Workspace` holds a `WorkspaceClient` and no `CgFileSystem`, no path, no handle. A
command runs on the client because that is the only place it *can* run, and it reaches the server only by
making a call that crosses the seam. The split that exists is by **what a thing is** — workspace files are
the server's, config and session records are client-local under `config/crystalgui`, deliberately outside
the workspace so private state never becomes part of a project a resource pack could ship.

---

## Two corrections to carry in

Recorded so nobody re-derives them.

- ~~**The editor holds a stale `WorkspaceClient` across a reconnect.**~~ **Not reachable, struck
  2026-08-21.** It looked certain — five places capture the client at construction
  (`CrystalEditor:159`, `Workbench:141`, `ProjectFileTree:187`, `WorkspaceTreeSource:109`,
  `WorkspaceFileService:81`), and nothing rebinds any of them. But `CgUiScreen.onGuiClosed` nulls the
  editor, the window and the workspace, and Minecraft replaces any open screen with the disconnect
  screen — so **the editor cannot survive a disconnect** and the next open builds a fresh client. The
  teardown should have been checked before the capture was called a defect.

- **The C1 × C2 seam is fixed, and is the shape to expect more of.** A viewer joining *after* a reshape
  was told a description hash the session no longer served, and its window never appeared. Each feature
  was correct alone; neither's tests combined them. Found by running both in one client.
  `aViewerAddedAfterAReshapeStillGetsTheWindow` pins it.

---

## The items

### 5.1–5.2 Windowing · **moved to [`plan_windowing.md`](plan_windowing.md)**

Started here — a disconnect discards unsaved work — and outgrew the phase. It is **engine work, not
networking**: a lifecycle for `UIWindow` where hide, close and destroy are three different things, the
registry that owns the retained set, and the strip that makes a hidden window findable again. Every
window benefits, not just the editor, and it changes what a loader is for — `CgUiScreen` stops *owning* a
window and starts *attaching* to one.

The numbers are left as they are rather than closing the gap, so anything citing 5.3–5.10 keeps meaning
what it meant.

**What stays in this phase** is the half that is genuinely about the wire:

- **Reconnect-on-restore** — retention resurrects the stale `WorkspaceClient` this document records as
  unreachable, and the fix is browsers': close connections on hide, re-establish on show, with the
  restore carrying the equivalent of `pageshow`'s `event.persisted`. Tracked as `plan_windowing.md` W6
  because it is a step of that build, and noted here because **this** is the document that struck the
  defect.
- **Persisting the retained set** — 5.3 below. Retention is in-memory and survives Escape, not a crash,
  a quit or a disconnect, so the two are complementary rather than alternatives.

### 5.3 Persist the retained set · **because retention is always best-effort**

Retention is in-memory: it survives Escape, and not a crash, not quitting the game, not a disconnect.
**Every system that retains also persists**, and says so — the Page Lifecycle API ships `wasDiscarded`
precisely so a page can tell it was dropped and needs a full reload, and Android's `onSaveInstanceState`
exists because being stopped may become being destroyed without warning.

So this does not merge into 5.1 and must not be skipped because 5.1 covers the common path.

**What is missing today.** `WorkbenchSession` persists **view state only** — its own words, *"a caret, a
scroll offset, a fold set — is applied when the file's content arrives."* It does not carry buffer
content. There *is* an unsaved-changes guard — `Workbench.java:495` sets
`dock.setCloseGuard(this::confirmClose)` — but it guards closing a **tab**, and
`CgUiScreen.onGuiClosed` does not consult it. Locally that was survivable because closing was the
user's choice; **on a server, disconnection is not**.

**Stored client-side, always.** The whole reason the buffers need saving is that the server may be
unreachable, so a recovery that requires the server is not one. `LocalConfigStorage` already exists for
this class of state, and writing them into the workspace would also put private in-progress work into a
project a resource pack could ship.

> With 5.1 in place this becomes *"persist the retained set"* rather than *"persist dirty buffers"* —
> a better-shaped problem, because the retained set is already the thing that knows what mattered.

### 5.4 `enabledWhen` is evaluated locally

A command's `enabledWhen` runs on the client, so it cannot ask the server *may I?*. `explorer.delete`
looks enabled to a non-operator and the refusal arrives as a `NO_PERMISSIONS` failure after a round trip.

**Not a bug fix.** Making it honest needs the permission answer cached client-side, which is a real
feature: what is cached, when it is invalidated, and what a command shows while the answer is unknown.
The alternative — asking the server per menu open — is a round trip inside a UI gesture and is worse.

### 5.5 The conflict dialog

The protocol half is done and was done deliberately: `Failure.isConflict()` carries the live etag, and a
delta against a file that moved is **refused rather than merged** — merging is a decision with a UI
attached and does not belong in a write path. The dialog is that UI, and it stopped being hypothetical
the moment two players could edit one file.

### 5.6 Presence

C1 gives a session its viewers and their peers, which **is** the data presence needs. Nothing displays
it. Deferred from Phase 4 C5 for exactly this reason — *"broadcasting it wants a consumer that does not
exist"* — and 5.5 is plausibly that consumer, since knowing somebody else has the file open is what makes
a conflict comprehensible rather than mysterious.

### 5.7 Two windows on one connection

`ServerUiSession` enforces one UI session per connection by construction: a second registers
`ui/description` twice and `MessageRouter` refuses a duplicate. That is the right failure and a real
limit. Lifting it means the router dispatching on **window id as well as method**, which is the same
change C1 originally implied and was deliberately left out of it.

### 5.8 Server-contributed commands · `command/*`

`CommandRegistry`'s javadoc already anticipates *"a command sent from a server"*, and `Protocols` makes
the namespace straightforward — `command/*` alongside `ui/*` and `workspace/*`, registered the way the
workspace now is. Nothing implements it.

**Watch for:** this is the first thing that would let a server change what a client's palette *does*,
which is a trust boundary the protocol has not needed until now. `ScriptPolicy`'s reasoning is the
precedent — a control nobody will configure is worse than a leaky one that gets used.

### 5.9 The wire under real conditions

Everything so far is **localhost with tiny payloads**: the in-game probe moved a 44-byte file, and the
1.25 MB chunked test was in-memory over an `InMemoryTransport`. Credit flow control, fragmentation and
the 8 MB reassembly bound have never met latency, loss, or a genuinely large file over a socket.

**Watch for:** `MAX_REASSEMBLY_BYTES` is connection-wide rather than per-stream and does not scale with
the platform ceiling — several large transfers in flight together is the case that has never run.

### 5.10 Platform hygiene · **the class of bug that cost three fixes today**

- **`PlatformService1201` has the same eager-construction shape** just fixed on 1.7.10, and worse: its
  services are `private final X = new X()` inside a `static final INSTANCE`, so they are built at *class
  init* rather than at preInit. Half the fix already applies, since `CgPlatform.register` is shared.
  Whether it actually fails depends on what those service classes import, which was not finished.
- ~~**CrystalGraphics' import guard is disabled**, and is what would have caught today's failures.~~
  **Half right, corrected 2026-08-21 while enabling it.** The guard *was* disabled — its own `AGENTS.md`
  warns against assuming otherwise by analogy with CrystalGUI's active one — and it is now **on**, with
  `org.lwjgl` added, which the commented-out original omitted. Both `core/` and `platform/` were already
  clean, so it cost nothing and only prevents a regression; it is verified to fire by compiling a
  deliberate violation.

  **But it would not have caught any of the three.** All three were in `mc1710/`, where `org.lwjgl` is a
  **legal** import, or in `CgPlatform`, which imports nothing offending and simply *called*
  `platform.gl()`. The class of bug is *"a client-only class is constructed on a server"* — a **runtime**
  property that no import scan can see.

- **What does catch it is booting a dedicated server**, which found all three in one run. `runServer`
  works now, so making that a repeatable smoke check — boot, assert `[cgui-net] connection lifecycle
  installed`, kill — turns a class of bug that was invisible to 1090 headless tests and to the harness
  into something a build can fail on. This is the item; the guard was the consolation prize.

  > The same reasoning `core/src/headlessTest/` already embodies — *"the absence is the assertion"*.
  > A server is simply a second environment no test reproduces, and until today nothing had ever run one.

> **Today's three, for the record**, because they are one shape: eager construction of client-only
> services (`NoClassDefFoundError: org/lwjgl/LWJGLException`), `CgPlatform.register` pulling GL itself,
> and `PlatformService1710.onInit` calling two client-only things *before* the caller's side guard. Each
> was fatal at mod load, each invisible to every test and to the harness, because **neither is a
> server**. CrystalGraphics had never run on one.

---

## Sources — read 2026-08-21

> The windowing research (Win32, X11, Cocoa, Page Lifecycle, bfcache) moved with §5.1–5.2 to
> [`plan_windowing.md`](plan_windowing.md). What remains below is what the rest of this phase cites.

- [Page Lifecycle API](https://developer.chrome.com/docs/web-platform/page-lifecycle-api) and the
  [WICG spec](https://wicg.github.io/page-lifecycle/) — the six states, `freeze`/`resume`, and
  `wasDiscarded`
- [Back/forward cache](https://web.dev/articles/bfcache) — eligibility, `pageshow` + `event.persisted`,
  and the connection rule this phase adopts wholesale
- [Disconnect WebSockets on BFCache entry](https://groups.google.com/a/chromium.org/g/blink-dev/c/52nlr8z3Png)
  — the change from *"an open connection blocks retention"* to *"close it on entry, reconnect on
  restore"*, which is the shape 5.1 takes
- [Opening and Closing Windows](https://developer.apple.com/library/mac/documentation/Cocoa/Conceptual/WinPanel/Tasks/OpeningClosingWindows.html)
  — `orderOut:` vs `close`, `releasedWhenClosed`, and that it is **ignored under a window controller**
- [Using Window Notifications and Delegate Methods](https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/WinPanel/Tasks/UsingWindowNotDel.html)
  — `windowShouldClose:` as a veto

## Explicitly not in this phase

- **`WatchService`.** Not a gap. `WorkspaceWatcher`'s own javadoc rejects it — *"quirky per platform and
  unreliable on network mounts"* — and notes a faster path can be layered under it without changing
  anything above. An optimisation behind an intact seam.
- **Slots and inventory** (Phase 4 C6). Refused on the plan family's own rule: no consumer, no
  `ItemStack` anywhere in `core/src/main` or `mc1710/src`. Revive when something renders an item.
- **mc1201 itself.** Still a product call, not a technical block. 5.10's first bullet is the part worth
  knowing *before* that call is made.
- **The shader node graph.** Its own document.

---

## What one client cannot prove

Two things stay covered headlessly on purpose, because a single-player world has one connection and a
probe cannot stand up a second client:

- **Multi-viewer fan-out** with two real players. `MultiViewerTest` covers it; the in-game probe adds a
  real `addViewer` against the live session whose far end is an `InMemoryTransport`.
- **Anything about latency**, which is 5.9.

And a standing note from Phase 4 that the dedicated-server work reinforced: **a client is an environment
no test reproduces** — and a *server* is a second one. Every defect found today was invisible to 1090
headless tests and to the harness.
