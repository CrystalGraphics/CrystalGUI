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

### 5.1 A window lifecycle, in the engine · **hide is not close, and close is not destroy**

**Researched 2026-08-21** against Win32, X11/ICCCM, Cocoa, the W3C Page Lifecycle API, bfcache and
Android, because this is a solved problem everywhere and the failure modes are already documented.

#### The problem

The window's lifetime is owned by the wrong layer. `CgUiScreen.initGui` constructs the workspace, the
editor and the `UIWindow`; `onGuiClosed` nulls all three. `AGENTS.md` already says *"`UIWindow`
deliberately implements no platform Screen/widget interface — loader modules own that"* — and the
**lifetime leaked to the loader anyway**. Escape-destroys-everything is a `GuiScreen` accident rather
than a decision, and it takes the undo history, every open buffer and every cached analysis with it.

The instinct is already in the code: `initGui` opens with `if (uiWindow != null) return;`, so the window
survives a resize. That is retention — scoped to one screen instance instead of to the engine.

#### What every other platform does

| System | Hide (retained) | Close (a *request*) | Destroy |
|---|---|---|---|
| Win32 | `ShowWindow(SW_MINIMIZE/SW_HIDE)` | `WM_CLOSE` — the app may ignore it | `DestroyWindow` → `WM_DESTROY` |
| X11 / ICCCM | `IconicState` | `WM_DELETE_WINDOW` — the client decides | `WithdrawnState`, then destroy |
| Cocoa | `orderOut:` | `windowShouldClose:` **can veto** | `close` + `releasedWhenClosed` |
| Web | `visibilitychange` → hidden, then **frozen** | `beforeunload` | terminated / **discarded** |
| Android | `onStop` | back press | `onDestroy` |

**Three findings, and all three are load-bearing here.**

1. **Close is universally a request, not an action.** `WM_CLOSE`, `WM_DELETE_WINDOW`,
   `windowShouldClose:`, `beforeunload` — every one of them *asks*. CrystalGUI already has this
   primitive: `UIElement.requestClose()` and the close-watcher cascade, where a live drag eats Escape
   before a popover and a popover before a modal. So the window level does not need a new mechanism, it
   needs to **answer** the one that already exists.

2. **When something else owns the lifetime, close stops destroying.** Cocoa is explicit:
   `releasedWhenClosed` *"is ignored for windows owned by window controllers."* That is precisely the
   model — a retained registry owns the instance, so closing a window returns it to the registry rather
   than freeing it. We are not inventing a policy; we are adopting the one AppKit already ships.

3. **A hidden thing must stop working.** The Page Lifecycle API does not merely mark a page hidden — it
   **freezes** it, and *"normally HIDDEN pages will be frozen to conserve resources."* `requestAnimationFrame`
   stops firing. Android has `onStop`. This is not an optimisation anyone chose to add later; it is part
   of the state.

#### The state model

Four states, which is the intersection of all five systems above:

```
        show()                    hide()
  ┌──────────────┐          ┌──────────────┐
  │   VISIBLE    │ ───────► │    HIDDEN    │   retained, ticking stopped
  └──────────────┘ ◄─────── └──────────────┘
         │  requestClose()          │
         │  (cascade first)         │ evicted / world gone
         ▼                          ▼
  ┌───────────────────────────────────────┐
  │              DESTROYED                │   Disposer runs, registry drops it
  └───────────────────────────────────────┘
```

- **`requestClose()` at window level means "dismiss me"**, and the window's **policy** decides whether
  that hides or destroys. Escape reaching the window is safe *because the cascade already filtered* —
  a modal, a popover and a live drag all consume it first, and those genuinely should be destroyed.
- Escape therefore defaults to **hide** for an application window and **destroy** for a transient one.
  A global "Escape always hides" rule would be wrong; the policy belongs on the window.

#### The rules that fall out, each with a source

- **A hidden window stops ticking.** `UIFrameTicker`s, smooth scrolls, transitions — and, importantly,
  the language services, which analyse on a debounce. A hidden editor that keeps compiling is worse than
  one that was destroyed. Page Lifecycle's *frozen* is the precedent.
- **Connections are dropped on hide and re-established on show.** This is the one that matters most here,
  and browsers have already been through it: an open WebSocket used to make a page **ineligible for
  bfcache**, and the resolution was not to refuse retention but to *"close or pause open connections,
  timers, and observers in your `pagehide`/`freeze` handling, and re-establish them in your
  `pageshow`/`resume` handling when `event.persisted` is true."*

  > **This is the answer to the correction recorded above.** Retaining the editor un-strikes the stale
  > `WorkspaceClient` — five sites capture it at construction and nothing rebinds them — and the fix is
  > not "refuse to retain a window with a connection" but **rebind on show**. `show()` must carry the
  > equivalent of `persisted`, so the window knows it came back from retention and revalidates rather
  > than assuming its world is unchanged. "The user pressed Escape" and "the world went away" stay
  > distinct signals.

- **Input state does not survive hide.** Hover, pointer capture, press targets, a live drag. The pointer
  moved while the window was not looking. `AGENTS.md` already records what happens when input state
  outlives its tree — a stale hover made the diff walk two trees and run off the end — and show/hide is
  the same boundary.
- **Retention is bounded.** bfcache evicts; Android destroys; the Page Lifecycle API has a whole
  *discarded* state and a `wasDiscarded` flag for it. A retained editor holds every open document's
  `Rope`, its undo stacks, ECJ analyses and tree-sitter trees. So the registry needs an eviction
  policy, and `destroy()` drives `Disposer`, which already exists to *"release on CLOSE rather than on
  exit"*.

#### The three buttons

Minecraft has no OS chrome, so CrystalGUI draws its own — the same thing VS Code does with
`window.titleBarStyle: custom` and IntelliJ does by merging the controls into the main toolbar row,
right-aligned above the right activity stripe. That placement is the reference.

| Button | Meaning | Confidence |
|---|---|---|
| **Minimise** | `hide()` — retained, frozen | Unambiguous |
| **Close** | `requestClose()` → policy | Unambiguous |
| **Maximise / restore** | **needs a decision** | See below |

**Maximise is the one that does not map.** There is no OS window to maximise. It is only meaningful once
a window can be less than full-screen — and the machinery for that exists (`UIResizer`, out-of-flow
positioning, the graph's floating panels). The coherent reading is *remember the current rect, fill the
screen, restore on toggle*. **Recommended: ship minimise and close first, and add maximise when there is
a floating window to restore to.** Shipping a button whose meaning is guessed is how it ends up meaning
three things.

> **Not the platform's job and not the consumer's.** A loader supplies a surface and input; somebody
> building a GUI writes widgets. Neither should reimplement retention, and today both would have to.
> The registry, the state machine and the controls all belong in `core/`, with the loader reduced to
> *attaching* a view to a retained window and detaching on close.

### 5.2 Persist the retained set · **because retention is always best-effort**

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

### 5.3 `enabledWhen` is evaluated locally

A command's `enabledWhen` runs on the client, so it cannot ask the server *may I?*. `explorer.delete`
looks enabled to a non-operator and the refusal arrives as a `NO_PERMISSIONS` failure after a round trip.

**Not a bug fix.** Making it honest needs the permission answer cached client-side, which is a real
feature: what is cached, when it is invalidated, and what a command shows while the answer is unknown.
The alternative — asking the server per menu open — is a round trip inside a UI gesture and is worse.

### 5.4 The conflict dialog

The protocol half is done and was done deliberately: `Failure.isConflict()` carries the live etag, and a
delta against a file that moved is **refused rather than merged** — merging is a decision with a UI
attached and does not belong in a write path. The dialog is that UI, and it stopped being hypothetical
the moment two players could edit one file.

### 5.5 Presence

C1 gives a session its viewers and their peers, which **is** the data presence needs. Nothing displays
it. Deferred from Phase 4 C5 for exactly this reason — *"broadcasting it wants a consumer that does not
exist"* — and 5.4 is plausibly that consumer, since knowing somebody else has the file open is what makes
a conflict comprehensible rather than mysterious.

### 5.6 Two windows on one connection

`ServerUiSession` enforces one UI session per connection by construction: a second registers
`ui/description` twice and `MessageRouter` refuses a duplicate. That is the right failure and a real
limit. Lifting it means the router dispatching on **window id as well as method**, which is the same
change C1 originally implied and was deliberately left out of it.

### 5.7 Server-contributed commands · `command/*`

`CommandRegistry`'s javadoc already anticipates *"a command sent from a server"*, and `Protocols` makes
the namespace straightforward — `command/*` alongside `ui/*` and `workspace/*`, registered the way the
workspace now is. Nothing implements it.

**Watch for:** this is the first thing that would let a server change what a client's palette *does*,
which is a trust boundary the protocol has not needed until now. `ScriptPolicy`'s reasoning is the
precedent — a control nobody will configure is worse than a leaky one that gets used.

### 5.8 The wire under real conditions

Everything so far is **localhost with tiny payloads**: the in-game probe moved a 44-byte file, and the
1.25 MB chunked test was in-memory over an `InMemoryTransport`. Credit flow control, fragmentation and
the 8 MB reassembly bound have never met latency, loss, or a genuinely large file over a socket.

**Watch for:** `MAX_REASSEMBLY_BYTES` is connection-wide rather than per-stream and does not scale with
the platform ceiling — several large transfers in flight together is the case that has never run.

### 5.9 Platform hygiene · **the class of bug that cost three fixes today**

- **`PlatformService1201` has the same eager-construction shape** just fixed on 1.7.10, and worse: its
  services are `private final X = new X()` inside a `static final INSTANCE`, so they are built at *class
  init* rather than at preInit. Half the fix already applies, since `CgPlatform.register` is shared.
  Whether it actually fails depends on what those service classes import, which was not finished.
- **CrystalGraphics' import guard is disabled** — its own `AGENTS.md` says so, and warns against
  assuming otherwise by analogy with CrystalGUI's active one. It is precisely what would have caught
  today's three server-side load failures.

> **Today's three, for the record**, because they are one shape: eager construction of client-only
> services (`NoClassDefFoundError: org/lwjgl/LWJGLException`), `CgPlatform.register` pulling GL itself,
> and `PlatformService1710.onInit` calling two client-only things *before* the caller's side guard. Each
> was fatal at mod load, each invisible to every test and to the harness, because **neither is a
> server**. CrystalGraphics had never run on one.

---

## Sources — read 2026-08-21

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
- **mc1201 itself.** Still a product call, not a technical block. 5.9's first bullet is the part worth
  knowing *before* that call is made.
- **The shader node graph.** Its own document.

---

## What one client cannot prove

Two things stay covered headlessly on purpose, because a single-player world has one connection and a
probe cannot stand up a second client:

- **Multi-viewer fan-out** with two real players. `MultiViewerTest` covers it; the in-game probe adds a
  real `addViewer` against the live session whose far end is an `InMemoryTransport`.
- **Anything about latency**, which is 5.8.

And a standing note from Phase 4 that the dedicated-server work reinforced: **a client is an environment
no test reproduces** — and a *server* is a second one. Every defect found today was invisible to 1090
headless tests and to the harness.
