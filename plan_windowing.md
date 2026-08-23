# Windowing — lifetime, retention, and getting back to a hidden window

**Scoped 2026-08-21.** Split out of `plan_phase5.md` §5.1–5.2 once it became clear this is not a
networking item that happens to touch the UI — it is a missing layer of the engine that the remote
workspace merely made impossible to ignore.

> **The one-sentence version.** `UIWindow` has no lifecycle: it is constructed by a loader and nulled by
> a loader, so Escape destroys the editor, its undo history, every open buffer and every cached analysis.
> Every other windowing system separates **hide** from **close** and **close** from **destroy**, and this
> plan adopts that separation, adds the registry that owns the retained set, and builds the strip that
> makes a hidden window findable again.

---

## Why this is not a Phase 5 item

Phase 5 is *the remote workspace, made usable*. This started there — a disconnect discards unsaved work —
and outgrew it on three counts:

1. **It is engine, not networking.** The lifecycle, the registry and the strip all belong in `core/`, and
   none of them mention a wire.
2. **Every window benefits, not just the editor.** The shader graph, a settings window, anything a
   consumer builds. A per-consumer solution would be reimplemented per consumer, which is exactly what a
   framework exists to prevent.
3. **It changes what a loader is for.** Today `CgUiScreen` *owns* the window. After this it *attaches* to
   one. That is a boundary change, and boundary changes deserve their own document.

What stays in Phase 5 is the part that is genuinely about the wire: reconnect-on-restore (§5.1 here) and
persisting the retained set across a crash (`plan_phase5.md` §5.3).

---

## The problem, in the code

`CgUiScreen.initGui` constructs the workspace, the editor and the `UIWindow`; `onGuiClosed` nulls all
three. `AGENTS.md` already states that *"`UIWindow` deliberately implements no platform Screen/widget
interface — loader modules own that"* — and the **lifetime leaked to the loader anyway**.
Escape-destroys-everything is a `GuiScreen` accident, not a decision anyone made.

The instinct is already present, one layer too low: `initGui` opens with `if (uiWindow != null) return;`,
so a window survives a resize. That is retention, scoped to one screen instance instead of to the engine.

**Nothing above `UIWindow` exists.** There is no window manager, no registry, no notion of "the set of
windows this application has". That is the gap.

---

## Prior art — read 2026-08-21

| System | Hide (retained) | Close (a *request*) | Destroy |
|---|---|---|---|
| Win32 | `ShowWindow(SW_MINIMIZE/SW_HIDE)` | `WM_CLOSE` — the app may ignore it | `DestroyWindow` → `WM_DESTROY` |
| X11 / ICCCM | `IconicState` | `WM_DELETE_WINDOW` — the client decides | `WithdrawnState`, then destroy |
| Cocoa | `orderOut:` | `windowShouldClose:` **can veto** | `close` + `releasedWhenClosed` |
| Web | `visibilitychange` → hidden, then **frozen** | `beforeunload` | terminated / **discarded** |
| Android | `onStop` | back press | `onDestroy` |

### Four findings, all load-bearing

**1. Close is universally a request, not an action.** `WM_CLOSE`, `WM_DELETE_WINDOW`,
`windowShouldClose:`, `beforeunload` — every one asks and lets the application decide. CrystalGUI
already has the primitive: `UIElement.requestClose()` and the close-watcher cascade, in which a live drag
eats Escape before a popover and a popover before a modal. **The window level does not need a new
mechanism; it needs to answer the one that exists.** That is also why "Escape hides" is safe — everything
that genuinely should be destroyed consumes Escape first.

**2. When something else owns the lifetime, close stops destroying.** Cocoa is explicit that
`releasedWhenClosed` *"is ignored for windows owned by window controllers."* A retained registry owning
the instance is not a policy being invented here; it is AppKit's, and X11's `WithdrawnState` and
Android's back-vs-`onDestroy` say the same thing differently.

**3. A hidden thing must stop working.** The Page Lifecycle API does not merely mark a page hidden — it
**freezes** it, and *"normally HIDDEN pages will be frozen to conserve resources."*
`requestAnimationFrame` stops firing. This is not an optimisation added later; it is part of the state.

**4. Retention is best-effort, so retention never replaces persistence.** bfcache evicts, Android
destroys, and the Page Lifecycle API ships a `wasDiscarded` flag precisely so a page can tell it was
dropped and needs a full reload. Every system that retains also persists.

---

## The model

### States

```
        show()                    hide()
  ┌──────────────┐          ┌──────────────┐
  │   VISIBLE    │ ───────► │    HIDDEN    │   retained; ticking stopped
  └──────────────┘ ◄─────── └──────────────┘
         │  requestClose()          │
         │  (cascade filters)       │  evicted, or the world went away
         ▼                          ▼
  ┌───────────────────────────────────────┐
  │              DESTROYED                │   Disposer runs; registry drops it
  └───────────────────────────────────────┘
```

`requestClose()` at window level means **"dismiss me"**, and the window's **policy** decides whether that
hides or destroys — hide for an application window, destroy for a transient one. A global "Escape always
hides" rule would be wrong; the policy belongs on the window.

### The registry

One owner of the retained set, in `core/`, keyed so a window can be found without holding a reference to
it. It is the **model** for everything in the next section — the strip and the switcher are views of it,
never a second list.

**A window joins on open and leaves only on destroy.** That is Windows' rule and it is the right one: the
strip shows what is *live*, visible or hidden, with the focused one highlighted — not a bin of minimised
things. It also needs no separate bookkeeping, because `hide()` and `destroy()` are already different
verbs.

### The rules that fall out

- **A hidden window stops ticking.** `UIFrameTicker`s, smooth scrolls, transitions — and, most
  importantly, the language services, which analyse on a debounce. A hidden editor that keeps compiling
  is worse than one that was destroyed.
- **Connections drop on hide and re-establish on show.** Browsers went through exactly this: an open
  WebSocket used to make a page **ineligible for bfcache**, and the resolution was not to refuse
  retention but to *"close or pause open connections, timers, and observers in your `pagehide`/`freeze`
  handling, and re-establish them in your `pageshow`/`resume` handling when `event.persisted` is true."*

  > **This is what makes retention safe here.** Retaining the editor resurrects a defect
  > `plan_phase5.md` records as unreachable — the stale `WorkspaceClient`, captured at construction in
  > five places (`CrystalEditor:159`, `Workbench:141`, `ProjectFileTree:187`, `WorkspaceTreeSource:109`,
  > `WorkspaceFileService:81`) with nothing rebinding it. So `show()` must carry the equivalent of
  > `persisted`, and the window revalidates rather than assuming its world is unchanged. **"The user
  > pressed Escape" and "the world went away" stay different signals.**

- **Input state does not survive hide.** Hover, pointer capture, press targets, a live drag. The pointer
  moved while the window was not looking. `AGENTS.md` already records what happens when input state
  outlives its tree — a stale hover made the diff walk two trees and run off the end — and show/hide is
  the same boundary.
- **Retention is bounded.** A retained editor holds every open document's `Rope`, its undo stacks, ECJ
  analyses and tree-sitter trees. The registry needs an eviction policy, and `destroy()` drives
  `Disposer`, which already exists to *"release on CLOSE rather than on exit"*.

---

## Getting back to a hidden window

### The one fact that shapes it

`uiWindow.paintFrame()` is called from **exactly one place**: `CgUiScreen.drawScreen`. CrystalGUI has
never rendered outside a `GuiScreen`, so an always-visible in-game strip is a new platform capability —
a render-overlay hook, a window with no screen, input while the game runs.

And the cost is not what rules it out. **In-game the cursor is captured for look control**, so a strip
cannot be clicked without freeing it, and freeing it means opening a screen — which is the thing the
strip was supposed to save. Any always-on clickable HUD degenerates into "hold a modifier to free the
mouse", which is a worse keybind.

> Even systems that own the whole screen hide their taskbar: the macOS Dock auto-hides by default, the
> iPadOS Dock is a gesture. Windows' is permanent **because Windows owns everything**. Here we are a
> guest on somebody else's screen, competing with the hotbar, chat, potion effects and every other mod.

### So: a strip on a keybind

**Settled 2026-08-21.** One surface, summoned. It is itself a screen, so the cursor is free and the game
pauses exactly as it already does, and it costs nothing when unused — Alt+Tab, Recents, `tmux` `prefix w`.
When the shell is already open the same strip can live in its chrome beside the window controls, because
there the screen and the cursor already exist; that is a placement, not a second feature.

### What to take from a real taskbar

| Take | Why it fits | Reuses |
|---|---|---|
| Icon + label per entry | The minimum that identifies an entry | `CgUiSvg`, `icon()` in CSS |
| **Focused highlight** | Otherwise the strip cannot say where you are | a **class**, per "state a widget flips itself belongs on a class, not a pseudo-class" |
| **Grouping by window type** | Two editors are two entries | — |
| **Badges** — unsaved dot, error count | The tab strip already draws `*`; the Problems panel already counts | `FileDecoration`'s badge/colour split |
| **Per-entry context menu** — close, recent | Right-click is where "close" lives on every taskbar | `ContextMenu` + `MenuBuilder`, already the ONE place commands become rows |
| **Overflow** when full | — | — |
| **Progress on an entry** | A chunked transfer or a band download has a real duration | `ProgressBar`, `JobScheduler` |

### What NOT to take, and why

| Leave | Reason |
|---|---|
| **A search box** | The command palette already *is* this. Two search surfaces means two matchers that disagree — exactly what `TreeSearch.Model` taking a `String` cost once, silently dropping Match Case, Words and Regex |
| **A clock** | Minecraft has its own time, and `StatusBarView` owns that role |
| **A system tray** | `StatusBarView` already occupies it |
| **Pinning / launching** | These are not apps a user launches; they are windows a command opens. "Pinned" would have to mean *retained even when destroyed*, a third lifecycle state nobody asked for |
| **Auto-hide** | Solves a problem we do not have — the strip is summoned, and when docked it lives in a screen the user chose to open |

### Icons — what a window must declare

A strip entry needs an icon, so **a window declares one** the way a file type does. The machinery exists:
`ui/icons/*.svg` (Feather, stroked `currentColor` chrome marks) for window icons, `FileIconTheme` for
anything file-shaped, and `CgUiSvg` to draw them with no atlas and one instanced draw call.

Two recorded traps apply directly:

- **Resolve the light/dark variant through `CgUiSvg.ofIcon`.** `TextureValue.parseIcon` once called
  `of(toResourcePath(...))` instead, so every `icon()` in every stylesheet drew the light file forever
  and a theme swap changed nothing — invisible while the shipped icons were `currentColor` marks.
- **16px, matching the filetype set**, and a badge is a **full-size layer**, not a glyph in a corner box.
  JetBrains draws `staticMark`/`finalMark` on their own 16×16 canvases with the glyph already placed, so
  they compose by stacking; scaled into a corner they draw a third too large and read as bad artwork.

### The window controls

Minecraft has no OS chrome, so CrystalGUI draws its own — as VS Code does with
`window.titleBarStyle: custom` and IntelliJ does by merging the controls into the main toolbar row,
right-aligned above the right activity stripe. **That placement is the reference.**

| Button | Meaning | Confidence |
|---|---|---|
| **Minimise** | `hide()` — retained, frozen | Unambiguous |
| **Close** | `requestClose()` → policy | Unambiguous |
| **Maximise / restore** | needs a decision | Deferred — see below |

**Maximise does not map**, and guessing is how a button ends up meaning three things. There is no OS
window to maximise; it is only meaningful once a window can be *less* than full-screen. The machinery for
that exists (`UIResizer`, out-of-flow positioning, the graph's floating panels), so the coherent reading
is *remember the rect, fill the screen, restore on toggle* — **ship minimise and close first, and add
maximise when there is a floating window to restore to.**

---

## Order of work, and what each step unblocks

| # | Step | Unblocks | Why not earlier |
|---|---|---|---|
| **W1** | The lifecycle in `core/`: states, `hide`/`show`/`destroy`, the frozen contract | everything | — |
| **W2** | The registry: one owner of the retained set, keyed, with an eviction bound | W3, W4 | Needs W1's states to hold |
| **W3** | Rewire `CgUiScreen` to **attach/detach** instead of construct/null; Escape begins to hide | the actual behaviour | Shipping this before W4 makes a black hole |
| **W4** | The strip, summoned by a keybind, over the registry | W3 being safe | — |
| **W5** | Window controls (minimise, close) in the chrome, and a per-window icon | — | Needs a window to declare an icon, which is W1 |
| **W6** | Reconnect-on-restore: `show()` carries `persisted`, the workspace client rebinds | remote use | Only matters once windows outlive a disconnect, which is W3 |
| **W7** | Maximise, once a floating window exists | — | Its meaning is undecided until then |
| **W8** | Nice-to-haves: hover thumbnails, drag to reorder, middle-click close | — | — |

> **W3 and W4 ship together or not at all.** Escape that hides, with no way back, is strictly worse than
> Escape that destroys — at least destruction is honest about what it did.

---

## How each piece is tested

| Piece | Test |
|---|---|
| State transitions, illegal transitions | headless — the state machine has no UI |
| **A hidden window stops ticking** | headless: register a `UIFrameTicker`, hide, advance frames, assert it never fires. This is the one most likely to regress invisibly |
| `show()` resets input state | headless: hover an element, hide, show, assert no hover and no capture |
| Registry eviction and `Disposer` running on destroy | headless |
| Reconnect-on-restore | headless over `InMemoryTransport`, **and** in game against `runServer` — the dedicated-server path is where it actually matters |
| The strip and controls | a harness scene, per the repo rule that anything visual is tested there rather than through Minecraft |
| The whole gesture | in game: open the editor, edit without saving, minimise, do something in the world, restore, and assert the undo history and the dirty buffer survived |

---

## Risks

- **A window that keeps working while hidden.** The failure is invisible by definition — nothing is on
  screen to look wrong — and it shows up as frame time or a compile storm nobody can attribute. Mitigated
  by the ticker test above, which is why that test is called out rather than left implied.
- **Unbounded retention.** Every retained editor holds ropes, undo stacks and analyses. Without an
  eviction policy this is a slow leak that only appears in a long session.
- **Escape becoming ambiguous.** The cascade already resolves this and must keep doing so; the window is
  the *last* consumer, never an earlier one.
- **The disconnect interaction.** Recorded twice now, in two documents, because it was struck as
  unreachable and this plan makes it reachable again.
- **Scope creep into a full window manager.** Floating, tiling, snapping, multi-monitor. None of that is
  wanted. The line: **one visible window at a time, a set of retained ones, and a way to switch.**

---

## Exit criteria

1. Escape on the editor **hides** it; the editor, its undo history and its dirty buffers are intact on
   restore.
2. A hidden window fires no tickers, runs no transitions and performs no analysis.
3. The strip lists every live window, shows which is focused, and restores one on activation.
4. Closing a window destroys it, runs `Disposer`, and removes it from the strip.
5. `CgUiScreen` constructs no `UIWindow` — it attaches to one and detaches on close.
6. A disconnect and rejoin leaves a retained window usable, its workspace client rebound.
7. The keybind that summons the strip is discoverable without reading the source.

---

## Deliberately not in this plan

- **Persisting the retained set across a crash or a quit** — `plan_phase5.md` §5.3. Retention is
  in-memory; that is persistence, and the two are complementary rather than alternatives.
- **A HUD strip drawn over the running game.** Ruled out above on the cursor argument, not on cost.
- **Floating, tiling, snapping, multi-monitor.** Not a window manager.
- **Maximise**, until something can be less than full-screen (W7).

---

## Sources — read 2026-08-21

- [Page Lifecycle API](https://developer.chrome.com/docs/web-platform/page-lifecycle-api) and the
  [WICG spec](https://wicg.github.io/page-lifecycle/) — the six states, `freeze`/`resume`, `wasDiscarded`
- [Back/forward cache](https://web.dev/articles/bfcache) — eligibility, `pageshow` + `event.persisted`
- [Disconnect WebSockets on BFCache entry](https://groups.google.com/a/chromium.org/g/blink-dev/c/52nlr8z3Png)
  — the move from *"an open connection blocks retention"* to *"close on entry, reconnect on restore"*
- [Opening and Closing Windows](https://developer.apple.com/library/mac/documentation/Cocoa/Conceptual/WinPanel/Tasks/OpeningClosingWindows.html)
  — `orderOut:` vs `close`, and `releasedWhenClosed` being **ignored under a window controller**
- [Using Window Notifications and Delegate Methods](https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/WinPanel/Tasks/UsingWindowNotDel.html)
  — `windowShouldClose:` as a veto
