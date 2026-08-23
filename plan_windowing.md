# CrystalOS — a window compositor for CrystalGUI

**Rewritten 2026-08-21**, superseding the same-day single-window plan after a direction decision:
*"we should be able to have MULTIPLE visible windows all resizable, exactly like an application desktop
manager"* — *"basically a window compositor with multiple different windows stacking exactly like how an
OS would do it."* The old plan's scope fence — *"one visible window at a time, a set of retained ones,
and a way to switch"* — is the thing that was wrong, and everything downstream of it is rewritten here.
What survives is carried forward explicitly rather than assumed: the lifecycle research, the state
model, the taskbar content tables and the icon traps were all still correct.

> **The one-sentence version.** CrystalOS is a stacking window manager inside a CrystalGUI screen:
> multiple simultaneously visible, draggable, resizable, minimisable windows over the game world, with a
> taskbar, a switcher, and a lifecycle in which hide, close and destroy are three different verbs — built
> almost entirely out of element-layer machinery the engine already ships.

---

## Scope — an "OS" in exactly one sense

A **basic stacking window manager with a taskbar**. That means: window chrome (title bar, icon, the
three buttons), drag to move, resize on all eight edges, raise-on-click stacking, an active window,
minimise to a taskbar, maximise/restore, a keybind switcher, retained lifecycles, and the dock ↔
window bridge — tool windows that float out of the dock and dock back, editor tabs that tear out into
windows of their own. Plus a **pinned tier**: always-on-top windows that stay over the running game as
a display-only HUD.

It does **not** mean: tiling, virtual desktops, multi-monitor, a start menu (the command palette
exists), a file manager (the explorer exists), processes, or drawing outside a `GuiScreen`. The scope
fence section at the end is normative — anything not listed as built or deferred is out.

---

## The architecture: a window is an element; `UIWindow` is the desktop

This is the load-bearing decision, and the codebase had already taken it before anyone asked.

### The evidence

1. **The network layer models a window as `(int windowId, UIElement root)`.**
   `ServerUiSession(int windowId, UIElement root, …)` and `ClientUiSession.root()`/`windowId()` — a
   "window" over the wire is an element subtree with an id, not a `UIWindow`. *"Every packet carries a
   window id"* is a documented invariant. The compositor gives that id the visual home it never had.
2. **`UIWindow` is documented as the *runtime engine*, not a window.** It owns the Taffy tree, the style
   engine, the input handler and the screen dimensions — it is the display surface. Its own `modalStack`
   javadoc says *"the spec hangs it off the `Document` — which is what this class is."* One document,
   one desktop.
3. **Everything a frame needs already exists at the element layer** — see the reuse table below, each
   row verified against the code on 2026-08-21, not assumed.

### Design A vs Design B

| | **A: N `UIWindow`s + a new compositor layer** | **B: windows as element subtrees under one `UIWindow`** |
|---|---|---|
| Taffy trees, style engines, input handlers | N of each, plus a router across them | one of each, unchanged |
| Hit-testing across overlapping windows | a new layer that must agree with N paint orders | `sortedChildren` already guarantees paint and hit-test agree |
| `CgUiPaintContext.beginFrame`/`endFrame` | N times per frame, or a new aggregation pass | once, unchanged |
| Focus/tab/hover across windows | a new cross-window focus model | already per-element |
| The network's `(windowId, root)` | needs an adapter per window | is already the model |
| What must be built | an input router, a paint scheduler, a z-order layer | one widget, one host element, one registry |

**B, without reservation.** A is what an actual OS must do because its windows belong to different
processes; ours share a heap and a tree, and pretending otherwise buys N copies of everything and a
routing layer whose only job is to reassemble what B never took apart.

### What already exists (verified)

| Compositor need | Already shipped, and where |
|---|---|
| Title bar that drags its window | `Dialog` — `__title-bar__` drag writes `left`/`top` at **INLINE** origin via `UIDragController` |
| A close button, a backdrop, modal vs modeless | `Dialog` — `__close__`, `__backdrop__`, `show()`/`showModal()`, close watcher only when modal |
| Resize on all eight edges/corners | `UIResizer.Handle` — all eight, driven by `resize:` in CSS; INLINE-origin writes (spec: a user resize is "without `!important`"), clamped against the element's own `min-*`/`max-*` **and** its containing block |
| Clamped floating-panel movement, re-clamp on container resize | `CanvasOverlayMove` — extracted when the Blackboard became its second consumer; the window title bar is the third |
| A torn-off dock that floats, splits and tabs | `FloatingDock` — written, currently **unconsumed**: hosts a whole `DockLayout` (ImGui's rule), non-modal and non-light-dismissable by recorded decision |
| Tool-window placement stored beside the layout | `ToolWindowManager` / `ToolWindowState` / `ToolWindowLayout` — IntelliJ's `ToolWindowManager` / `WindowInfoImpl` / `DesktopLayout`, ported; content built once per type and cached across hides |
| Panel and tab drags with banded drop targets | `RegionDropOverlay` + `RegionDropZones` (whole-workbench bands), `DockDropZones` (VS Code's editor drop geometry), `DockDragPayload` |
| Free positioning against the screen | out-of-flow (`position: absolute`) children; `TopLayer` proves the reparent-to-root pattern |
| Stacking where paint and hit-test agree | `sortedChildren` — z-descending, equal-z later-inserted-first, paint walks it reversed; a documented invariant |
| Clipping window content to its bounds | `overflow` + `ScissorStack` |
| Focus, tab order, hover chains, pointer capture, drag | `UIInputHandler` — all per-element already |
| Escape as a cascade | drag → popover → modal → leftover; `CgUiScreen.keyTyped` is already deliberately empty so the cascade sees Escape first |
| View state that survives detach/reattach | `SessionState` — captured in `unregisterElement`, applied in `registerElement`, once per id |
| Teardown on destroy | `Disposer` — exists to *"release on CLOSE rather than on exit"* |
| An icon pipeline | `CgUiSvg`, `icon()` in CSS, `FileIconTheme` |
| Per-entry menus, badges, progress | `ContextMenu` + `MenuBuilder`, `FileDecoration`, `ProgressBar` |

### What is genuinely new

1. **`WindowFrame`** — the widget: chrome around a content slot, the state machine, a close policy.
2. **`Desktop`** — the compositor host element: the window layer, the taskbar, placement, clamping.
3. **`WindowRegistry`** — the retained set, MRU order, eviction. The model the taskbar and switcher render.
4. **Window-scoped modality** — today a modal freezes the whole document; it must freeze its window.
5. **Activation** — the notion of *the active window*, raise-on-click, per-window focus memory.
6. **The presentation bridge** — one content instance moving between docked, floating and windowed
   with the mode remembered (see Views); the workbench's tool-window model already reserved the field.
7. **The HUD surface** — a paint-only entry (layout + draw, no input frames) and a loader overlay
   hook, so a pinned window can render over the running game (see Pinned windows).

---

## Prior art

### Lifecycle (carried forward from the previous plan — read 2026-08-21)

| System | Hide (retained) | Close (a *request*) | Destroy |
|---|---|---|---|
| Win32 | `ShowWindow(SW_MINIMIZE/SW_HIDE)` | `WM_CLOSE` — the app may ignore it | `DestroyWindow` → `WM_DESTROY` |
| X11 / ICCCM | `IconicState` | `WM_DELETE_WINDOW` — the client decides | `WithdrawnState`, then destroy |
| Cocoa | `orderOut:` | `windowShouldClose:` **can veto** | `close` + `releasedWhenClosed` |
| Web | `visibilitychange` → hidden, then **frozen** | `beforeunload` | terminated / **discarded** |
| Android | `onStop` | back press | `onDestroy` |
| Swing | `setVisible(false)` | `windowClosing` | `dispose()` |

The four findings stand: **close is universally a request** (and CrystalGUI already has the primitive —
`requestClose()` and the close-watcher cascade); **when something else owns the lifetime, close stops
destroying** (Cocoa: `releasedWhenClosed` *"is ignored for windows owned by window controllers"*; Swing:
`setDefaultCloseOperation(HIDE_ON_CLOSE)`); **a hidden thing must stop working** (Page Lifecycle
*frozen*); **retention never replaces persistence** (`wasDiscarded`, `onSaveInstanceState`).

### The compositor (new)

- **Swing MDI is the closest precedent in existence** — an in-process window manager on the JVM:
  `JDesktopPane` (the desktop), `JInternalFrame` (title bar, the three buttons, resizable, draggable,
  iconifiable, maximisable), `DesktopManager` (the policy object), `JInternalFrame.JDesktopIcon` (the
  minimised representation), `setDefaultCloseOperation` (the close policy enum). The *names* below
  deliberately rhyme with it. What we do **not** take from it: its per-L&F `DesktopManager` indirection
  (we have one look), and its selection model quirks.
- **Win32 stacking**: raise-on-activate; **owned windows always stay above their owner and travel with
  it** — the fact that decides where modals may promote to (see Modality below). New windows **cascade**
  (`CW_USEDEFAULT` offsets each successive window by the caption height). A drag keeps the caption
  reachable. Dragging a maximised window restores it first, cursor kept proportionally inside the title
  bar.
- **Alt+Tab orders by MRU, not by z** — the two agree under raise-on-click *except* for minimised
  windows, which leave the z-order but keep their MRU slot. That is why the registry keeps an MRU list
  rather than deriving order from the tree.
- **X11/EWMH**: `WM_TRANSIENT_FOR` is the owner relationship; stacking is maintained per-layer
  (desktop < normal < dock < above), which is the band model — ours has four bands: desktop content,
  windows, pinned, the global top layer.
- **macOS**: window levels (`NSWindow.Level`) are the same band model; a sheet is window-modal, not
  app-global — the precedent for window-scoped modality.

---

## The pieces

Package: **`com.crystalgui.ui.elements.desktop`**. Tags registered in `ElementRegistry` (`desktop`,
`window`, `taskbar`) — a widget's cascade identity is its **tag**, per the recorded invariant, so CSS
cannot style them until they are registered. Geometry in a new UA-sheet part (`ua/desktop.css`,
appended to `DEFAULT_SHEET_PARTS`); colours as `var(--token, #fallback)` like every other part.

| Piece | Kind | Responsibility |
|---|---|---|
| `WindowFrame` (tag `window`) | widget, `acceptsPublicChildren() == false` | chrome (`__title-bar__`, `__title__`, `__icon__`, `__controls__` with `__minimize__`/`__maximize__`/`__close__`, `__content__` slot — **never targeted by a descendant selector**, per the thrice-paid rule), the state machine, `WindowPolicy`, per-frame focus memory, its own overlay slot (see Modality), and a **role** — top-level or owned — deciding its control set: minimise/maximise/close for a top-level frame, Dock/Hide for a floating tool window (see Views) |
| `Desktop` (tag `desktop`) | element, the compositor host — **owned by `UIWindow`, never built by an application** | owns the **window layer** (an internal child; frames are *public* children of it, exactly the `windowOverlayLayer` pattern — the layer internal, its occupants public, so frames can still remove themselves), the `Taskbar`, placement (cascade), clamp-on-resize, click-on-empty-desktop = blur |
| `WindowRegistry` | model, no GL | the retained set (`open` → joins, `destroy` → leaves), MRU order, eviction policy, lookup by key |
| `Taskbar` (tag `taskbar`) | widget inside `Desktop` | the registry, rendered — one entry per live window |
| `WindowSwitcher` | overlay | MRU-ordered cycling on a keybind |
| `WindowPolicy` | enum on the frame | `HIDE_ON_CLOSE` (application windows) / `DESTROY_ON_CLOSE` (transients) — Swing's `setDefaultCloseOperation`, minus `DO_NOTHING` until someone needs it |

### Who owns the desktop — decided at W1, 2026-08-22

**`UIWindow` owns it, and nothing else may build one.** The first draft of this plan left the desktop
as an element a host constructs and hands to `Ui.of(...)`, which is a compositor every application
would assemble slightly differently. It is engine infrastructure, so it is owned exactly like the
window's other engine-owned layer: `UIWindow.desktop()` builds it on first use and hands out the same
one forever, `UIWindow.openWindow(frame)` is the whole of the API, and a host provides nothing but a
root with a size.

Two consequences that are not obvious and are both load-bearing:

- **It sits *over* the root rather than being it.** That is the band model spelled structurally — the
  root's own children are the *desktop content* band, the desktop is the *windows* band above them,
  and the top layer is above both by construction. It also means no existing UI changes shape: the
  desktop is an internal child, so `ui.rootElement` still means what it always did, and the 220-odd
  windows in the test suite are untouched.
- **It is zero-sized until a window is open.** An overlay hit-tests, so a full-size empty desktop
  would swallow every click that landed on background — an application that never opened a window
  would go dead with nothing pointing at a compositor. It claims the surface when the first window
  opens and gives it back when the last one closes. W7's migration removes the transitional question
  entirely by making the editor itself a frame.

A `WindowFrame` declares a **title**, an **icon**, and a **policy**; content goes in via `content()`.
It is **client chrome**: the server ships a window's *content* tree (`UIDescriptionCodec` never needs
the `window` tag); the client wraps it in a frame. Registering the tags anyway costs nothing and keeps
decode honest if that ever changes.

**`WindowFrame` extends `UIElement`, never `Dialog`.** Dialog's bundle is modality, a close watcher
and a backdrop — exactly what a frame must not inherit, and `FloatingDock`'s javadoc already paid for
that lesson once (its whole "what it must NOT inherit" section exists because borrowing `Dialog`
hands those over for free). The title-bar drag and the INLINE geometry writes are a *pattern* to
port, not a base class to take; `Dialog` itself becomes a consumer of the frame-scoped overlay slot.

### Tags, classes and the sheet

Three new tags — `desktop`, `window`, `taskbar` — join `ElementRegistry.bootstrapBuiltins()`
(eighteen today; unknown tags still throw on decode). Chrome parts are internal children with the
usual double-underscore classes; **state** is flipped by the widgets and therefore lives on classes,
never pseudo-classes — the rule that has now cost three rounds:

| Where | Classes |
|---|---|
| Frame chrome | `__title-bar__` `__title__` `__icon__` `__controls__` `__minimize__` `__maximize__` `__close__` `__content__`, plus the per-frame overlay slot |
| Frame state | `__active__` `__maximized__` `__fullscreen__` `__pinned__` `__hud__` |
| Taskbar | one entry element per window; `__attention__` on a flashing entry |

Geometry — frame minimums included — lives in `ua/desktop.css`; colours are `var(--token, #fallback)`
with tokens added to the theme tables, and `CGUI_THEMING.md`'s token table is **generated**, so it
regenerates from the failing governance test rather than by hand. AGENTS.md's internal-class
inventory and widget/scene tables go stale silently (their own warnings say so); W1 extends them in
the same commit.

### The command set

Every window operation is a `Command` first and chrome second — the buttons, the system menu, the
taskbar context menu and the keymap are four renderers of the same ids, which is what keeps them
from drifting. Registered in `CommandRegistry.global()`: they are facts about the application, and
what varies per window is focus, which `DataContext`'s walk already answers.

| Id | Does | Reference chord |
|---|---|---|
| `window.close` | `requestClose()` → policy | **none** — decided at W13; see Open questions |
| `window.minimize` | hide to the taskbar | — |
| `window.maximize` | toggle maximise / restore | — |
| `window.fullscreen` | toggle fullscreen | `F11` |
| `window.pin` | toggle pin, promoting to top-level if needed | — · **registered at W14, not W13** |
| `window.move` / `window.size` | keyboard modes: arrows nudge, Enter commits, Escape cancels | via the system menu |
| `window.systemMenu` | the system menu on the active frame | `Alt+Space` |
| `toolwindow.hide` | hide the active tool window | `Shift+Escape` (IntelliJ's) |
| `desktop.showDesktop` | toggle minimise-all | — |
| `desktop.switchWindow` | the MRU switcher | decided at W10 — `Ctrl+Tab` is conventional and claimed by recent-files |
| `desktop.taskManager` | open the task manager | — · **registered at W15, not W13** |

> **A command lands with its feature, never ahead of it.** `window.pin` and `desktop.taskManager` are in
> this table because the table is the whole vocabulary, not because W13 registers them. The registry
> carries `enabled` and both menu renderers **dim rather than hide** — deliberately, after the palette
> once listed 1 of 9 commands — so registering a command whose feature does not exist yet puts a
> permanently grey row in every menu that shows it. Grey means "not right now"; a row that can never be
> anything else is a lie about the application.

Enablement follows the standing rules — the registry carries `enabled`, both menu renderers dim
rather than hide, `enabledWhen` resolves from focus — so a restore command on an unmaximised window
greys rather than vanishes.

---

## The window state machine

Carried from the previous plan, with one implementation decision made concrete.

```
        show()                    hide()
  ┌──────────────┐          ┌──────────────┐
  │   VISIBLE    │ ───────► │    HIDDEN    │   retained; DETACHED from the tree
  └──────────────┘ ◄─────── └──────────────┘
         │  requestClose()          │
         │  (cascade filters)       │  evicted, or the world went away
         ▼                          ▼
  ┌───────────────────────────────────────┐
  │              DESTROYED               │   Disposer runs; registry drops it
  └───────────────────────────────────────┘
```

### Hide is **detach**, and the freeze falls out of it

A hidden frame's subtree is removed from the window layer (the element instance retained by the
registry). Detachment is what the engine already treats as "not participating": selectors do not match
detached elements (`invalidateStyleMatch` early-returns), there is no layout, no paint, no hit-test.
The alternative — `display: none` in place — keeps the subtree matching selectors and keeps every
ticker firing, which is precisely the *"hidden editor that keeps compiling"* failure.

Detach also buys correctness for free at the seams that already exist:

- `unregisterElement` already **captures `SessionState`** per element on the way out, pops the element
  from the **top layer, the modal stack, the popover stack and the close watchers** — so a frame hidden
  with a dialog open cannot leave the desktop inert (the documented unrecoverable state).
- `registerElement` is **idempotent** and re-applies nothing spent — re-showing rebuilds Taffy nodes and
  relayouts, which is correct, not wasteful: the world may have resized while the frame was away.

What detach does **not** do by itself, each named because each fails silently:

- **Tickers.** `registerTicker` has no unregister by design; a ticker captured on an element inside a
  hidden frame keeps firing if it keeps returning `true`. The rule: **a ticker whose element is detached
  must return `false`** — most already do by construction (caret blink stops on blur, and hide blurs),
  but the contract goes in `UIFrameTicker`'s javadoc and a test pins the frame case.
- **Input state.** Hover, pointer capture, press targets, a live drag anchored inside the frame. The
  `UIInputHandler` must forget a detached element — the invariant table already demands this and records
  the two-trees hover-diff crash that motivated it. Hide runs the same forgetting.
- **Connections.** Dropped on hide, re-established on show, exactly bfcache's resolution (*"close or
  pause open connections … re-establish them in your `pageshow`/`resume` handling when
  `event.persisted` is true"*). `show()` carries a `persisted` flag so a restored window revalidates
  rather than assuming its world is unchanged — **"the user pressed Escape" and "the world went away"
  stay different signals.** This is what makes retention safe against the stale-`WorkspaceClient`
  defect (captured at construction in five named places; `plan_phase5.md` records them).
- **Bounded retention.** A retained editor holds every open document's `Rope`, undo stacks, ECJ
  analyses and tree-sitter trees. Eviction is LRU over the registry's MRU list with a small cap
  (default: eight hidden windows), and eviction = `destroy()` = `Disposer` — with one exemption:
  **a frame whose content is dirty is never auto-evicted**, because silently discarding unsaved work
  is the failure this whole plan exists to prevent. The dirty question goes through the close-guard
  seam the dock already has (`setCloseGuard`), so content answers it once for close and eviction
  both. Persistence (`plan_phase5.md` §5.3) is what makes eviction
  survivable, and stays a separate item because retention is always best-effort.

### Close is a request, routed through the policy

`requestClose()` on a frame means *"dismiss me"*. The cascade has already filtered — a live drag, a
popover, a modal all consume Escape first, and those genuinely should close — so what reaches the frame
is policy: `HIDE_ON_CLOSE` minimises, `DESTROY_ON_CLOSE` destroys. The close *button* and Escape go
through the same method; there is exactly one dismissal path.

---

## Stacking and activation

### Raise is a z-index assignment, **never** a reparent

The obvious raise — move the frame to the end of the window layer's child list — is a trap this
codebase is uniquely equipped to spring: `removeChild`/`addChild` runs `unregisterElement` /
`registerElement` over the **whole frame subtree**, which captures session state, pops modal/popover/
close-watcher stacks, destroys and rebuilds every Taffy node, and re-applies spent session ids — per
click. A widget must never rebuild the elements it is being clicked on (the invariant that froze the
table header), and a raise happens *on* a click.

So: the window layer's manager assigns each raised frame the next value of a monotonic counter as its
`z-index` (IMPORTANT origin — a stylesheet must not fight activation). `sortedChildren` then keeps
paint and hit-test agreeing with zero new machinery, which is the invariant that must never be
re-implemented. Pinned frames take the same counter **plus a band offset** (say `1 << 20`) — that offset is the
entire implementation of the always-on-top band. The counter renormalises per band when it grows
silly, preserving order; nobody clicks 2³¹ times.

The **global top layer stays above every frame** — it paints after the whole main tree by construction.
See Modality for what may still promote into it.

### Activation

- **Click-to-focus, and the click is delivered** (Windows' model, not macOS click-through): a
  mouse-down anywhere in a frame raises it and makes it active *and* the press reaches its target. The
  raise listens in the **CAPTURE phase on the frame** — the recorded scar (`TextEditor`'s unconditional
  `stopPropagation()` starving later same-element listeners) says the same element is not early enough,
  and capture on the frame ancestor is the documented answer.
- **The active window is where focus lives.** Keyboard input already goes to the focused element;
  activation restores focus to the frame's **remembered focus** — each frame records its last focused
  element, exactly Win32's `WM_ACTIVATE` convention. Restoration follows the `ListView` rule: restore,
  never steal — if the element is gone, delegate to the frame's first focusable.
- **Active chrome is a class** (`__active__` on the frame), not a pseudo-class — the "state a widget
  flips from its own listener belongs on a CLASS" rule has now cost a round three times.
- **Clicking the empty desktop blurs** — `emitMouseDown` already blurs before dispatch and nothing
  takes the focus; no active window is a legal state.
- **A window opened by anything other than a user gesture takes no focus.** The networked case
  forces this: a server can open a window mid-keystroke, and focus-stealing would land the next
  words in it — half a sentence into a villager shop. Every OS converged on the same answer
  (Windows' `SetForegroundWindow` foreground-lock plus `FlashWindowEx`, X11's urgency hint, macOS's
  bouncing dock icon): a background-opened window appears in view but inactive and **requests
  attention** — its taskbar entry flashes (a class + a transition) until it is activated. Windows
  the user's own click or command opened focus exactly as before. And a background-opened window joins the MRU at the **back**: the
  switcher's first offer must stay the user's last window, or the flash becomes a steal with one
  keystroke of delay.

### MRU

The registry keeps most-recently-*activated* order, updated on activation only. The switcher reads MRU;
the taskbar reads open order (stable positions — a taskbar whose entries jump on every activation is
the "never in the same place twice" menu bug wearing a bar).

### The system menu

Alt+Space, right-click on the title bar and right-click on the taskbar entry all open the same menu:
Restore / Move / Size / Minimise / Maximise / Fullscreen / Pin / Close — built by `MenuBuilder`,
which stays the one place commands become rows, so the title-bar menu and the taskbar context menu
cannot drift apart. **Move and Size are keyboard modes** (arrows nudge, Enter commits, Escape
cancels — the Win32 behaviour), which makes every window operation reachable without a pointer. The
commands must exist for the keymap anyway, so the menu is rows over work already done.

---

## Geometry

- **Move**: the title bar drags the frame — `Dialog` already does exactly this (drag → `left`/`top` at
  INLINE origin). Clamping and re-clamp-on-desktop-resize come from `CanvasOverlayMove`, whose javadoc
  documents the three mistakes to not re-make (`getX()` is not `left`'s space; `resizeOriginLeft()`
  answers 0 for an `auto` inset; clamping only during the drag lets a container resize strand the
  panel). The clamp rule is Windows': **the title bar must stay reachable** — a frame may hang off the
  sides and bottom, never off the top, and some minimum sliver stays on-screen.
- **Alt-drag** (W13b): hold the window-move modifier and drag anywhere inside a frame to move it —
  the Linux WM staple, same drag path and clamp as the title bar, and the answer for a window whose
  title bar is tiny. The chord is keymap-resolved, never a hardcoded Alt: Alt is already contested
  territory (text fields refuse Alt chords, the menu bar claims Alt+letter mnemonics), and both of
  those rules were paid for.
- **Resize**: `resize: both` on the frame; `UIResizer` provides all eight handles, INLINE-origin writes,
  clamping against the frame's CSS `min-width`/`min-height` (definite lengths — already how
  `clampToStyleRange` works) and against the containing block. Minimum sizes live in `ua/desktop.css`,
  not Java, per the no-pixels-in-widgets rule.
- **Maximise / restore** — now meaningful, so it ships (the old plan deferred it *because* nothing could
  be less than full-screen; that reason is gone):
  - Maximise records the current INLINE rect as the **restore rect**, fills the work area, sets
    `__maximized__` (chrome swaps the glyph; the resize handles stop applying — `resize: none` via the
    class), and clears the frame's inset/size at the same origin it wrote them.
  - Restore puts the rect back. Double-click on the title bar toggles (Windows). Dragging a maximised
    title bar restores first, cursor kept proportionally inside the bar (Windows' restore-drag).
  - **Fullscreen** (F11, W13b) is maximise's sibling: the same remembered rect, and the taskbar
    hides too — one more class on the frame.
- **Placement**: a new frame with no remembered geometry cascades from the last placement by the
  title-bar height (Win32), wrapping back to the origin when it walks off the work area. A frame with
  remembered geometry (see Persistence) gets it, clamped.
- **Screen resize**: `Desktop` re-clamps every visible frame and re-fills maximised ones —
  `CanvasOverlayMove.reclampIfPlaced` is the shape.
- **The work area**: the taskbar is *laid out* as a bottom bar, never overlaid — so the window
  layer's box **is** the work area. Maximise fills it with no bar special-case, drags clamp at it,
  and fullscreen's hiding of the bar re-flows the layer to full height. Windows' own model:
  maximise respects the taskbar, fullscreen covers it.
- **Per-window zoom** (W15): a frame-level `UITransform` scale — layout-free by design, so it is
  magnification rather than re-layout; hit-testing and glyph rasterisation already follow the pose.
  The accessibility answer for one window without touching the global `uiScale`.
- **Snapping**: drag-to-edge halves and drag-to-top maximise ship in W13b — after W1 the band
  arithmetic is the same family as `DockDropZones`. Keyboard snap (the Win+arrows analogue) stays
  deferred.

---

## Modality, popovers and the top layer with many windows

Today all four stacks are global to the `UIWindow`, and for frames that is wrong in one specific,
user-visible way: **a modal in window A freezes window B and the taskbar.** `isModalBlocked` answers
against "the document's active modal dialog", `getHoveredElement` skips the main tree wholesale while
any modal is open. On a desktop, modality is per-application: a sheet blocks its window (macOS), an
owned dialog blocks its owner (Win32). Our "application" is the frame.

### The changes

1. **Modality scopes to the frame.** The modal stacks become per-scope, where an element's scope is its
   nearest `WindowFrame` ancestor, falling back to the desktop (so a modal opened by desktop chrome can
   still block everything — the current behaviour remains expressible). `isModalBlocked(element)` asks
   the element's own scope. Enforcement is at **four points, deliberately not one** — hit-testing, Tab
   scoping, `requestFocus`, and the top-layer hit-test skip — and the invariant table's warning applies
   verbatim: a "simplify to one predicate" refactor that misses one still looks green. Each point gets
   its own test, as the current four have.
2. **A modal promotes into its *frame*, not the global top layer.** Win32's rule decides it: owned
   windows stay above their owner *and travel with it* — if A's modal sat in the global top layer, it
   would float above B after B is raised. So `WindowFrame` carries its own overlay slot (an internal,
   absolutely-positioned, zero-sized layer — the `windowOverlayLayer` pattern, per frame), and
   `Dialog.showModal`/`overlayHost` resolve to the nearest frame's slot. The `__backdrop__` then sizes
   against the frame and dims exactly the window it blocks. The slot is the general
   **owned-window** surface, not a modal-only one — a floating tool window is its second consumer (see Views), owned and above its
   owner *without* disabling it: Win32's owner/owned pair, with the disabled owner as the modal
   special case. An owned window paints *inside its owner's subtree*, so the whole group raises,
   lowers and hides as one with no bookkeeping — Win32's group behaviour for free. The slot carries
   a high `z-index` within the frame (above `__content__`), and the frame element itself keeps
   `overflow` visible — only `__content__` clips — so a dialog or float may legally overhang its
   owner's edge.
3. **The global top layer keeps the pointer-transients**: menus, tooltips, the drag ghost, toasts.
   These belong to the pointer or the active frame; a click into another window is a mouse-down, and
   light dismiss already closes them on it, so the "floats above the wrong window" case cannot outlive
   one click. The light-dismiss algorithm itself needs no scoping.
4. **Escape routes through the active frame's cascade**: the active frame's close watchers first
   (dropdown before modal, as today), then the frame's own policy, then — with no active frame — the
   screen closes. The close-watcher stack becomes per-scope alongside the modal stack; a live drag
   still eats Escape before everything, globally, because a drag is the innermost live interaction.
5. **Being blocked is shown, never silent.** A click on a window blocked by its own modal pulses
   the modal (a transition on a class) and dings — Windows' exact behaviour, and the first real
   consumer of `CgPlatform.sound()`, a platform SPI wired on every loader that nothing uses today.
   Without it, window-scoped modality's failure mode reads as "this window ignores my clicks",
   indistinguishable from a bug.

### What deliberately does not change

- `TopLayer`'s mechanics (insertion order, reparent-to-root, the four positional divergences).
- `pushModal`-on-`unregisterElement` cleanup — detach-hides ride on it.
- Popovers, close watchers and light dismiss semantics within one scope — every documented behaviour
  (invoker carve-out, dismiss-after-dispatch, the show-seq exemption) is scope-local already.

---

## Views: docked, floating, windowed — the dock ↔ window bridge

The compositor's windows and the workbench's dock are two levels, and the engine keeps them apart —
but IntelliJ's most-used window gesture is precisely a *conversion* between them: drag a tool-window
button off the stripe and the panel becomes a floating window over the IDE, with a **Dock** button to
re-embed it and a **Hide** button that dismisses it — and the stripe button then reopens it *in the
same mode at the same position*. Editor tabs go further: dragged out of the tab strip, they become a
top-level window with its own taskbar entry. This section makes that conversion a general engine
capability rather than a workbench trick.

### One content, three presentations

| | **DOCKED** | **FLOATING** | **WINDOWED** |
|---|---|---|---|
| Lives in | its `DockRegion` / the dock tree | an **owned** `WindowFrame` over its owner | a top-level `WindowFrame`, peer of the workbench |
| Chrome | the pane/strip header | **Dock** + **Hide** (+ the panel's own menu) | minimise / maximise / close |
| Z | the owner's content | above its owner, travels and hides with it | the normal window band |
| Taskbar | — | **no entry** | its own entry |
| Summoned by | its stripe button | its stripe button | the taskbar and the switcher |
| IntelliJ name | Dock | Float | Window |

**The instance is the same element in all three.** `ToolWindowManager` already builds content once per
type and caches it across every hide precisely so toggling never rebuilds; a presentation change is a
*reparent* of that instance, so scroll, expansion, caret and undo all survive because they were never
touched. The reparent runs `unregisterElement`/`registerElement` over the subtree — the cost the raise
rule forbids per click is fine at gesture speed, and it is the price a hide already pays.

**Every window has exactly one summon surface.** A top-level frame is in the `WindowRegistry` and on
the taskbar; a floating tool window is in neither — its way back is its stripe button, and its hidden
state lives in `ToolWindowState`. A hidden float owned by two lists comes back twice or not at all, so
registry membership follows presentation and changes with it. (One *owner*, not necessarily one
control: a WINDOWED tool window's stripe button stays lit and keeps toggling it — by delegating to
the registry entry, never by keeping a second copy of the state.)

### The model already reserved the seat

`ToolWindowState` is the port of IntelliJ's `WindowInfoImpl`, and its javadoc has said since it was
written: *"`type` (docked/floating/windowed) and `autoHide` are still absent: floating tool windows do
not exist here. Named so their absence reads as a decision."* This is the feature that fills the seat:
`type` plus a floating rect join the record, `ToolWindowLayout` persists them beside everything else,
and reopen-where-it-was falls out of the architecture's founding rule — *placement is stored beside
the layout, never derived from it* — with no new mechanism. Hide flips `visible`; the mode and the
rect stay; the stripe button reopens whatever the record says.

### `FloatingDock` is the quarry, re-founded

`FloatingDock` exists, is currently **unconsumed**, and is a top-layer `Dialog` under a rationale its
own javadoc states — *"Minecraft has one window, so none of that is available"* — which CrystalOS
retires. Under the compositor a top-layer float is exactly the bug the modality section names: it
would paint above *other* windows even when its owner is behind. So it re-founds on an **owned
`WindowFrame`** in its owner's overlay slot, keeping its three load-bearing decisions verbatim:

- **Neither modal nor light-dismissable** — the class note's whole reason for being written down: a
  floating panel that vanishes when you click the graph behind it is a bug shipped looking like a
  feature.
- **It hosts a whole `DockLayout`, never a single panel** — ImGui's rule, so a float can be split and
  tabbed exactly like the dock it left, and tear-out/re-dock stay the same two operations the drop
  code already performs.
- **`closeIfEmpty` is called by the drag's owner, never a tick** — an empty float is a legitimate
  state *during* a drag.

### The gestures

- **Stripe button dragged off the rail** → the panel floats at the drop point. The machinery is the
  existing region drag: `RegionDropOverlay` already bands the whole workbench for dragging between
  regions, and a drop outside every band — which today changes nothing, rejection being the drag
  default — becomes the FLOAT answer from `RegionDropZones`. A new answer, not a new gesture.
- **Dock** → back to the remembered `region`/`side` — a lookup, because placement was stored. The
  four-tier restoration heuristic this architecture already deleted stays deleted.
- **Hide** (and Shift+Escape, IntelliJ's binding, read from the keymap) → `visible = false`, frame
  detached, mode and rect kept.
- **Editor tab dragged past every editor drop zone** → a **WINDOWED** frame at the drop point,
  hosting its own small dock area — tab strip included, ImGui's rule again — with a taskbar entry and
  the full top-level chrome. Dragging its tab back into the main dock is the return path, and
  `closeIfEmpty` retires the frame. Closing the torn-out frame closes its tab *views*, never the
  documents — the document/view split already guarantees a document still open in another frame is
  untouched.

### The editor case is where Design B pays

A document open in the main workbench and in a torn-out window is **one document** — the invariant
*"`LanguageServices` belongs to the DOCUMENT, not to the editor or the widget"* already covers two
split panes, and two frames under one `UIWindow` are the same case wearing chrome: one analysis, one
`DiagnosticSet`, one undo stack (per document, deliberately never per window), and an edit in either
view lands in both. Under Design A this would have been genuinely hard — N `UIWindow`s would have
turned the split-pane rule into a cross-window synchronisation problem.

### Client-side decorations — decided at W7, 2026-08-22

Reported from the harness the moment the editor became a window: **it had two headers**, the window's
caption and its own menu row, stacked. That is the problem every desktop toolkit solved the same way,
and CrystalOS takes the same answer — the application's chrome goes **in** the caption:

- GTK's `GtkHeaderBar`, the canonical name for it: the app owns the title bar, the WM contributes buttons.
- VS Code's `window.titleBarStyle: custom` with `menuBarVisibility: compact` — the menu is a hamburger
  inside the caption.
- IntelliJ's New UI, where the main menu, the project widget and the run configurations share that row.
- WinUI's `ExtendsContentIntoTitleBar` + `SetTitleBar(element)`, the same arrangement as an API.

The seam is `WindowChrome`: content that has caption chrome offers **one element**, and
`WindowFrame.setContent` moves it into the caption and puts it back when the window lets go. Moved, not
copied and not hidden behind a flag — which is the same rule this section already states for the
dock↔window bridge one level down. The drag region needs no declaration: the caption's move gesture is
target-only, so anything hit-testable the application puts there keeps its presses and the space left
over still drags the window.

### Engine vs workbench — where the seam sits

The **engine** owns what is general: frame roles (top-level vs owned) and their control sets, the
owned-window slot, the reparent contract, the one-summon-surface rule. The **workbench** owns what is
policy: which presentations each panel offers, the stripe, `ToolWindowState`, and what its drop zones
mean. A consumer that is not the workbench — the shader graph, a mod's own UI — gets float-capable
panels through the same seam without inheriting the stripe.

---

## The taskbar

### The persistent-strip conclusion, revised — and why the old one was still right

The previous plan ruled out a persistent taskbar on the cursor argument: in-game the cursor is captured
for look control, so an always-on HUD strip cannot be clicked, and that argument **stands — for the
HUD**. What it never considered is a desktop, because the old model had no desktop: inside the
compositor screen the cursor is free, the game is paused, and a persistent bar along the bottom is
exactly what every precedent ships. So:

- **On the desktop: a persistent `Taskbar`**, docked bottom, part of `Desktop`'s chrome. Never a
  window itself — not in the registry, not minimisable, not modal-blockable.
- **The entries are CENTRED, and the band paints nothing** — decided at W4, 2026-08-22. The strip
  lands in the same place, and roughly the same shape, as Minecraft's hotbar: that is where a
  player's eye already lives, so the compositor's one permanent landmark costs no new habit. The
  band spans the full width for *layout* (which is what keeps the work area a plain flex box) while
  only the island in the middle draws, leaving the corners free. There is no conflict with the real
  hotbar, and the reason is structural rather than lucky: the taskbar exists **only on the desktop
  screen**, where the game is paused and the cursor is free. What genuinely has to respect the
  hotbar is W14's pinned windows, which paint over the running game.
- **From the game: the keybind** summons the desktop screen (the existing `openEditor` binding's
  successor). There is no HUD strip, same reasoning as before.

### The model

**A window joins the taskbar on open and leaves only on destroy** — Windows' rule — and **top-level
frames only**: a floating tool window's summon surface is its stripe button, never the bar (see
Views). The strip shows what is *live*, visible or hidden, the active one highlighted; a minimised
window's entry is how it comes back. Entry click: activate (raising and restoring as needed); click on the active entry minimises
(Windows). The taskbar renders `WindowRegistry` — it is the registry, rendered, never a second list.

**Show desktop** — minimise everything, restore everything — is one command over the same registry
(W13c), and earns its keybind exactly when floats and torn-out editors multiply. It is a toggle with
a memory: it restores exactly the set it minimised, and forgets that memory the moment any window is
activated in between — Windows' Win+D.

### What to take (carried forward)

| Take | Why it fits | Reuses |
|---|---|---|
| Icon + label per entry | The minimum that identifies an entry | `CgUiSvg`, `icon()` in CSS |
| **Active highlight** | Otherwise the bar cannot say where you are | a **class** |
| **Badges** — unsaved dot, error count | The tab strip already draws `*`; the Problems panel already counts | `FileDecoration`'s badge/colour split |
| **Per-entry context menu** — restore, minimise, maximise, close | Right-click is where "close" lives on every taskbar | `ContextMenu` + `MenuBuilder` — already the ONE place commands become rows |
| **Overflow** when full | — | — |
| **Progress on an entry** | A chunked transfer or a band download has a real duration | `ProgressBar`, `JobScheduler` |
| **Attention flash** on an entry | A background-opened window must be able to say so without stealing focus (see Activation) | a class + a transition, cleared on activation |

### What NOT to take (carried forward)

| Leave | Reason |
|---|---|
| **A search box** | The command palette already is this; two search surfaces means two matchers that disagree — the recorded `TreeSearch.Model` failure |
| **A clock / system tray** | Minecraft has time; `StatusBarView` owns that role inside windows |
| **Pinning / launching** | These are windows a command opens, not apps a user launches; "pinned" would mean *retained even when destroyed* — a third lifecycle state nobody asked for |
| **Auto-hide** | The bar lives in a screen the user chose to open |

### Icons (carried forward — both traps recorded)

A frame declares an icon the way a file type does. Resolve light/dark **through `CgUiSvg.ofIcon`**
(`TextureValue.parseIcon` once didn't, and every stylesheet `icon()` drew the light file forever);
16px matching the filetype set; a badge is a **full-size layer**, never a glyph scaled into a corner
box (the JetBrains `staticMark` lesson).

### Discoverability — the day-one trap

**Minimise with no discoverable way back is worse than no minimise.** The taskbar answers it on the
desktop; from the game, the first hide fires a `Notification` — *"Editor minimised · press ⟨key⟩ to
return"* — with the accelerator **read from the keymap** (`Keymap.acceleratorFor`), never spelled as a
literal, per the recorded tooltip rule.

---

## The switcher

A keybind cycles the registry in **MRU order** (not z — minimised windows have no z), showing icon +
title per entry, activating on release or Enter. It is an overlay on the desktop screen; pressed
in-game, the binding opens the desktop first. Ships after the taskbar — it is a convenience view of the
same model, not the safety net (the taskbar is).

Two binding systems meet here: the in-game summon is a Minecraft `KeyBinding` (as the existing
`openEditor` key in `CgUiInput` is), while in-desktop cycling is the engine `Keymap`. The first-hide
notification reads whichever applies — never a spelled literal.

---

## The loader seam

Where this landed since the last plan was written: `CgUiScreen` already retains — `editor`, `uiWindow`
and `workspace` are **static fields "kept across opens"**, `onGuiClosed` only saves the session, and
`disposeAll()` runs at game shutdown. Retention exists; it is owned by the wrong layer, holds exactly
one window, and the freeze is an accident of the paint loop (everything is driven from `drawScreen`, so
a closed screen ticks nothing — including `JobScheduler.drain()`, which parks background results).

The move:

1. **`core/` owns a retained `Desktop`** (with its `UIWindow`, registry, taskbar). The loader's statics
   shrink to a reference to it.
2. **`CgUiScreen` becomes a viewport**: `initGui` attaches (creating on first use), `drawScreen` pumps
   input and paints, closing the screen **hides the desktop** — all window states retained exactly as
   they were, input state cleared (the same
   boundary as a frame hide) — pinned frames excepted, which keep painting over the game (see
   Pinned windows below). `disposeAll()` at shutdown is
   unchanged.
3. **The editor becomes the first `WindowFrame`** — `CrystalEditor` in a frame with
   `HIDE_ON_CLOSE`, opened **maximised by default**. This is the migration's masterstroke *and* its
   biggest test surface: on day one nothing visibly changes (a maximised frame is the current
   full-screen editor), and un-maximising is what reveals the desktop. The editor's `width: 100%;
   height: 100%` host rule moves onto the desktop root; the frame sizes the editor.
4. **Escape, end to end**: cascade inside the active frame → frame policy (the editor minimises) → no
   active frame → the screen closes, desktop hidden, everything retained. Every step is the existing
   `requestClose` machinery; the vanilla `keyTyped` close stays disabled as it already is.

`doesGuiPauseGame` stays `true`; the paused world behind the desktop **is the wallpaper**.

---

## Pinned windows — always-on-top, and the HUD over the running game

**Adopted 2026-08-21.** The use case is live debugging: pin the Run console or a graph preview, close
the desktop, and keep watching it stream while playing. Discord's and Steam's in-game overlays are the
exact precedent — a window pinned over a running game, display-only, arranged from the overlay UI
rather than in-game.

One toggle, two effects:

1. **Above the normal band on the desktop** — Win32's `WS_EX_TOPMOST`, EWMH's `_NET_WM_STATE_ABOVE`.
   In the compositor this is a second z-band: pinned frames sort above unpinned ones and below the
   global top layer. The band model absorbs it without redesign — exactly as predicted when
   always-on-top was refused for having no consumer; it has one now.
2. **It survives the desktop closing.** Closing the screen hides every window *except* pinned ones,
   which keep rendering over the running game.

**Pin implies top-level.** An owned float hides with its owner by definition, and a pinned window
must survive the whole desktop hiding — the two contracts collide. So pinning a FLOATING tool window
first promotes it to WINDOWED (IntelliJ's Window mode), and unpinning returns it to the mode it came
from. The Run-console use case is exactly this path: float it off the stripe, pin it, close the
desktop, play.

### Display-only is the rule that makes it sound

In-game the cursor is grabbed and the keyboard is the game's, so a HUD window can receive no input —
the refusal of a *clickable* HUD stands in full. What changed is recognising that display-only needs
no cursor. Three consequences, each load-bearing:

- **A `__hud__` class restyles the frame while it is on the HUD**: the controls hide — a control that
  cannot be clicked but looks clickable is the lie the disabled-control rule already forbids — and a
  theme may add translucency. Pinning, unpinning, placing and sizing all happen from the desktop.
- **The HUD paints through a paint-only entry** — layout and draw, no input-handler frames. The hover
  pipeline must never run against a grabbed cursor's stale position; `updateWithoutPainting` already
  proves the frame loop decomposes this way, and the HUD entry is its mirror (paint without input). It still runs the full
  `advanceFrame` — styles, transitions, layout and the `JobScheduler` drain, which is precisely what
  keeps a pinned Run console's async output flowing — and it seeds the same raw-pixel `init` and
  `rootTransform`, so a pinned window is pixel-identical on and off the desktop.
- **Visible stays live.** The freeze contract keys on *hidden*, not on *the screen being closed*: a
  pinned window keeps its tickers, transitions and connections, because watching live data is the
  entire use case. Everything unpinned freezes exactly as before. The state model stays honest —
  VISIBLE ticks, HIDDEN does not, and pinning just means visible on a second surface.

### The platform capability

This is the one genuinely new loader seam in the plan. The documented fact stands — `paintFrame` has
only ever been called from `CgUiScreen.drawScreen` — and the HUD adds a second caller: a
render-overlay hook that paints pinned frames after the game's own HUD. The hook pattern already
exists in the family (CrystalGraphics' mc1201 loaders register `HudRenderCallback` on Fabric and the
render-stage events on Forge/NeoForge for their own passes; 1.7.10 has `RenderGameOverlayEvent`), and
the GL-state discipline `CgUiScreen` documents applies verbatim — `CgGlState.invalidateAllIfPresent()`
on entry, hand Minecraft back its fixed-function state on exit. Only pinned subtrees paint, so the
per-game-frame cost scales with what the player pinned, never with the desktop.

Pause semantics are untouched: `doesGuiPauseGame` matters only while the screen is open, and a pinned
window over the *running* game exists precisely when it is not. A world exit hides pinned windows like
everything else — "the world went away" is already a lifecycle signal.

---

## Networking — server windows land as frames

This is where the compositor pays for itself beyond the editor:

- A `ClientUiSession`'s root (`windowId` attached) is wrapped in a `WindowFrame` and opened on the
  desktop. **Two server UIs open at once are two windows** — the situation `UIWindow.commands`' javadoc
  has predicted since it was written (*"a server-driven UI can have two windows whose `edit.save`
  legitimately mean different things"*).
- The wire protocol needs nothing new: window identity already crosses (*"every packet carries a window
  id"*), and `OpenWindow` carrying a content hash means re-opening is one small packet.
- Command resolution: `CommandRegistry.global()` + the desktop `UIWindow`'s registry already layer;
  whether a frame carries a third layer is an open question (below) — `DataContext`'s focus walk
  already disambiguates the common cases.
- Server-side close semantics (does hiding a server window notify the server? does the server destroy
  its session?) belong to `plan_phase5.md` — the seam here is only that hide and close are now
  different messages *because* they are different verbs. Until Phase 5 decides, server frames default to
  `DESTROY_ON_CLOSE` — a server UI is dialog-shaped (closing a chest does not retain it), and
  `OpenWindow`'s content hash already makes reopening one small packet.

---

## Persistence

- **Window geometry** — rect, maximised flag, and the restore rect — persists per window key in
  `LocalConfigStorage` (client-side, private, beside the session record; never in the workspace, for
  the recorded resource-pack reason). Applied at open, clamped against the current screen.
  W12 also restores the **window set** itself — which windows were open, their modes (maximised,
  pinned, presentation) and MRU order — so a relaunch reopens the desktop as it was left (macOS resume, a browser's session restore); §5.3's content persistence is what makes that
  restoration mean something for dirty work.
- **View state inside frames** — already `SessionState`, captured/applied at the register seam; hide
  and destroy both pass through it with no new code.
- **Dirty content across crash/quit** — `plan_phase5.md` §5.3, unchanged and still mandatory, because
  retention is best-effort and eviction is real.

---

## Tool windows are not citizens — decided at W12, 2026-08-23

Reported from a screenshot, and the screenshot is the argument: the strip along the bottom read
**Welcome · Geometry · Crystal Editor · Inspector · Notifications**, with two *panels* sitting as peers
of the IDE they belong to. IntelliJ does not do this. Its floating tool windows are in neither the
taskbar nor Alt+Tab; hiding the IDE hides them and showing it shows them; and that is precisely *why*
their captions carry Dock and Hide and no maximise or close — a window with no taskbar entry must not be
able to put itself somewhere a taskbar would be needed to get it back.

Win32 has one bit for exactly this — **`WS_EX_TOOLWINDOW`** — and it is the right port, because the
distinction it draws is not "tool window" at all. It is *is this a destination, or is it part of one*:

| | taskbar entry | switcher entry | hides with owner | desktop geometry record |
|---|---|---|---|---|
| Torn-out **editor** window (`DockWindow`) | yes | yes | no | yes |
| **Tool** window, `FLOATING` or `WINDOWED` | no | no | yes | no — the project's |

`WindowFrame.isToolWindow()` is that bit. Three consequences, and they were three separate defects:

1. **`WindowRegistry` grew `taskbarOrder()` and `switcherOrder()`**, filtered views beside the complete
   `windows()`/`mruOrder()`. Filtering `windows()` itself was the obvious move and is wrong: `Desktop`
   sizes its whole surface from whether any window is open, so a tool window alone on the desktop would
   have collapsed the surface and taken itself with it.
2. **A hide cascade.** `FLOATING` gets this free — it is a child of the overlay slot, so detaching the
   owner detaches it. `WINDOWED` is genuinely top-level and needed the bookkeeping W8 predicted: the
   owner remembers *which* windows it took down, because on the way back "hidden because the owner went"
   and "hidden because the user closed it" are indistinguishable.
3. **The cascade must not emit `onHidden`.** That signal has exactly one listener —
   `ToolWindowManager`, which reads it as the user closing the panel and records it shut. Firing it while
   an owner merely minimises marks every panel on that window closed, and the *next session save writes
   it down*: the window comes back and the panels do not.

**What `WINDOWED` still means** is now only the clamp — top-level, so it reaches the whole desktop
instead of being confined to its owner's box. That is a thin distinction and it may yet collapse into
one mode; it is left standing because nobody asked for it to go, and because "can I drag this outside
the IDE" is a real difference IntelliJ's Float mode also has.

---

## W12's tail — what a restore still does not cover

Found by reading both records rather than by reasoning about them, and each is silent.

**1. Tool windows restore per project, and that half works.** `session.<projectId>.json` is at version 6
and `ToolWindowState` persists `type` and `floatingBounds` beside region/side/weight, so the Inspector
floating in one project and docked left in another each come back their own way.

**2. A torn-out editor window was persisted by nothing at all, and did not even come back docked.**
*(Fixed — `WorkbenchSession`'s `windows` key.)* Two independent reasons, either of which alone would do
it:

- `DockArea.tearOutToWindow` builds a `DockWindow` and never calls `setKey`, and
  `Desktop.applyPersistedGeometry` early-returns on a null key. It was in no desktop record.
- `DockLayout.tearOut` **removes** the leaf from the layout, and the session's `dock` record *is*
  `workbench.dock().layout()`. It was in no project record either.

The file's caret and scroll did survive — `openPaths()` reads `OpenDocuments`, which is document-level —
so the view state was saved with no tab left to land in, which is the shape that makes it read as an
editor bug rather than a persistence gap.

The record is a `windows: [{ title, left, top, width, height, dock }]` list **beside** the main `dock`
tree, in the project's session rather than the desktop's: a torn-out window holds *this project's*
documents, and what has to survive is not only where it was but what was in it, which is a dock tree.
No version bump — the key is purely additive, and a record written before it decodes to "no torn-out
windows", which is exactly what was true of those sessions.

Three things it needed that are not obvious:

- **Ownership is panel-registry identity.** A `DockWindow` is a top-level desktop citizen and no
  descendant of the workbench, so "which project is this?" cannot be answered by walking the tree. What
  ties it back is that its dock builds content from this workbench's registry.
- **The replay is deferred a frame**, on `Workbench.onDidJoinWindow` — the same one-frame deferral the
  windowed tool windows ride, and for the identical reason: a host may restore before `UIWindow.init`,
  `openWindow` needs a desktop, and the docked half succeeds regardless, so the failure is *ordered*
  rather than total.
- **`openTabPaths()` still does not see them.** It walks the main dock's leaves, so "what is open" has
  two answers — `openPaths()` (documents, global, sees them) and `openTabPaths()` (tabs, main dock
  only, does not). Pre-existing since W9 and left alone; recorded here because it is the kind of split
  that will be rediscovered as a bug.

**3. Windowed tool windows were recorded twice, and the wrong copy won.** *(Fixed with the section
above.)* Per-project as `floatingBounds`, and per-host in `desktop.<id>.json` under
`toolwindow:<typeId>` — and `showInFrame` applies the project's bounds and *then* calls `openWindow`,
where the desktop record is applied. Second writer wins, so the first windowed tool window to open after
launch landed wherever the *previous project* had left it. `FLOATING` was unaffected, because
`attachOwned` never reaches `addWindow` — so it presented as only *some* of them being wrong, which
reads as a placement bug rather than as one fact with two owners. Both halves were needed: stop writing
them, **and** refuse to apply a record that already has them, or the bleed outlives the fix by as long
as somebody's config file does.

**4. Server windows land without stealing focus.** *(Done.)* `Desktop.addWindow(frame, false)` — reached
as `UIWindow.openWindowInBackground` — registers, attaches and animates the window exactly as any other
and skips only the raise and the activation. **Not raising is half of it**: a background window that
jumped to the front of the stack is a focus steal missing only the focus, since it covers what is being
typed in. It goes in at the back of the MRU too, which `WindowRegistry.opened` already arranged.

Asking for attention is part of appearing in the background rather than a second call a caller has to
remember — a window that appears with no focus and no announcement is one nobody knows opened, which is
worse than either alternative. **Cleared by activation, never by a timer**: a flash that gives up after
a few seconds is a notification you can miss by looking away, which is the thing the entry exists to
prevent.

**5. What a taskbar entry can say.** *(Done.)* `WindowFrame.setBadge` (an unsaved or error count, the
badge/colour split `FileDecoration` already draws), `setProgress` (Windows' fill-behind-the-label;
negative means "nothing to show", guarded as `!(x >= 0)` so NaN is refused rather than multiplied into a
width), and **middle-click closes** through the window's own policy. The fill needed a third `Button`
slot — `setUnderlay`, behind the label rather than beside it, because as a flex item it would shove the
label sideways as the job ran, and it must be a child of the entry or `left: 0` measures against the
wrong box.

**Also still open, carried from earlier Ws:** W11's reconnect has no in-game verification (the remote
probe joins but never reconnects, and two processes from one worktree contend for the vanilla jar at
build time).

---

## Deliberately not building

| Not building | Why |
|---|---|
| Tiling, virtual desktops, multi-monitor | Not the scope; recorded so "basic" stays basic |
| A *clickable* HUD over the running game | The cursor-capture argument stands for anything needing a pointer — the taskbar never renders in-game. Pinned windows do, **display-only** (see Pinned windows) |
| Window open/close/minimise animations | Transitions exist if wanted; not structural |
| Occlusion culling / per-window FBO caching | Painter's-order overdraw is acceptable at this window count; `CgUiPaintContext`'s layer machinery is the future answer if it ever isn't |
| Keyboard snap (the Win+arrows analogue) | Drag-to-edge snap ships in W13b; the keyboard half waits for demand |
| Live thumbnails on hover | ✅ **Shipped 2026-08-23.** Deferred first, on the grounds that "a preview of a frozen window means keeping its last frame, which fights the freeze contract" — and that reasoning does not survive being set beside what the contract says. Hiding is detaching so a hidden window **stops running**: no layout, no paint, no selectors, no input references. A texture does not run. Keeping a picture of a window lets it do nothing it was supposed to have stopped doing, which is why DWM keeps one and nobody calls a minimised Windows app live. What the deferral was really guarding against is a preview that is secretly a live window, which is a different design. A VISIBLE window was never covered by the objection at all and is simply re-drawn: `WindowThumbnail` mirrors the subtree under another pose, which needed `CgUiPaintContext.mirrored` so the second draw does not overwrite the placement caches hit-testing walks. A MINIMISED one is `WindowSnapshot`, an owned FBO captured on the first frame of the minimise |
| Focus-follows-mouse | Click-to-focus only; the other model surprises everyone but its twelve fans |
| IntelliJ's auto-hide / sliding tool windows | `ToolWindowState` already names `autoHide` a deliberate absence; this plan keeps it one |
| Clipboard history | Declined 2026-08-21 — an editor nicety, not a compositor feature |

---

## Order of work

**Second pass, 2026-08-21 — the good-to-have sweep, decided.** Adopted: attention/no-steal, the
system menu, show desktop, modal-blocked feedback, drag-to-edge snap, fullscreen, Alt-drag,
pin/HUD (always-on-top over the running game), session restore of the window set, the task
manager, and per-window zoom — homes in W12–W15 below. Declined: clipboard history and keyboard
snap; hover thumbnails stay deferred.

| # | Step | Unblocks | Notes |
|---|---|---|---|
| **W1** ✅ | `WindowFrame` + `Desktop`: chrome, `resize: both`, title-bar drag with clamping, tags + `ua/desktop.css`; harness scene `cgui-desktop` with two floating windows | everything visual | **Shipped 2026-08-22.** No lifecycle yet — geometry and chrome only. `Dialog` and `CanvasOverlayMove` are the quarries; the scene joins `SceneRegistry` **and** the AGENTS.md scene table in the same commit — those lists go stale silently |
| **W2** ✅ | Stacking + activation: z-assignment raise, `__active__`, per-frame focus memory, empty-desktop blur | W3's "active frame" routing | **Shipped 2026-08-22.** The raise-is-not-a-reparent rule is load-bearing here. Two activation paths, both needed: a capture-phase press on the frame (a right-click moves no focus at all) and the focus owner moving into one (Tab, a command, W10's switcher) |
| **W3** ✅ | Lifecycle + registry: states, hide-as-detach, `persisted` show, destroy → `Disposer`, eviction, the ticker/input-forget contracts | W4, W5 | **Shipped 2026-08-22.** The freeze tests land here. Two decisions made concrete: the default policy is `DESTROY_ON_CLOSE` (Swing's `HIDE_ON_CLOSE` default is a famous footgun, and until W4 a hidden window has no way back), and eviction **passes over** a window whose content refuses rather than stopping at it — the cap is a budget, and stopping lets retention grow by one window per unsaved document |
| **W4** ✅ | `Taskbar` over the registry: entries, active highlight, activate/minimise clicks, context menu | W3 being safe to ship | **Shipped 2026-08-22**, minus the per-entry context menu — that needs the `window.*` commands, and `MenuBuilder` is the ONE place commands become rows, so it lands with W13a's system menu where the same rows serve the title bar, the strip and `Alt+Space`. **W3 and W4 ship together or not at all** — minimise with no way back is worse than destroy |
| **W5** ✅ | Window-scoped modality: per-scope stacks, the frame overlay slot, `overlayHost`/`Dialog.showModal` retargeting, Escape per-frame | server windows, editor dialogs behaving | **Shipped 2026-08-22**, minus change 5 (the blocked pulse + the first `CgPlatform.sound()`), which W13c owns. The four-points warning applies and each has its own test; Tab needed **both** halves — scoping when the focused window is blocked, and skipping blocked candidates when it is not, since a modal in one window no longer traps the whole document. A frame registers as its own last close watcher, which is what makes Escape's cascade come out dropdown → modal → window with no special case |
| **W6** ✅ | Maximise/restore: restore rect, double-click, restore-drag, `__maximized__` | W7's editor-maximised default | **Shipped 2026-08-22.** One correction found by testing: the restore-drag fires on the first **movement**, never on the press — restoring on the press means a double-click restores and then re-maximises, so double-clicking a maximised caption appears to do nothing. Windows behaves the same way, and it is what click-and-hold on a maximised title bar does there. The restore rect is the **measured** box, since a window may never have been given an explicit size |
| **W7** ✅ | The loader seam: engine-owned desktop, `CgUiScreen` as viewport, the editor as a maximised `HIDE_ON_CLOSE` frame, Escape end-to-end | the actual product | **Shipped 2026-08-22**, compiling; **in-game verification is still owed** — `:mc1710:compileJava` is green and nothing here has been run in a client. Escape needed no arranging at all: a frame is its own last close watcher, so the cascade already ends at the window's policy and only a leftover Escape reaches `handleKeyboardInput`. `suspendDesktop()` is the new core primitive — the compositor leaves the tree on screen close, retaining every window's state, position and z-order, and dropping the input state that would otherwise describe a screen that is no longer up. **AGENTS.md was wrong that `mc1710` is out of the build**; it is in `settings.gradle.kts` and compiles |
| **W8** ✅ | Tool windows in windows: `ToolWindowType` (docked/floating/windowed) + floating rect on `ToolWindowState`, `ToolWindowFrame`, stripe drag-out, Dock/Hide chrome, the stripe toggle honouring the remembered mode | the IntelliJ gesture set | **Shipped 2026-08-22.** `FloatingDock` was **deleted**, not re-founded: it extended `Dialog`, promoted itself to the global top layer, and had no callers — its own javadoc opened by arguing that a second window was impossible here, which CrystalOS had made false. **The tear-out produces `WINDOWED`, not IntelliJ's Float**: an owned window is parented in its owner's overlay slot, so it is clamped inside it and has no taskbar entry, and a panel dragged onto the desktop sprang back into the editor. The gesture needed no new drop target — `RegionDropZones` already answers `null` for the workbench's middle — but it lives on the drag SOURCE's `onDragEnd`, because a drop released over the desktop is dispatched to `Desktop`, which is engine-side and rightly ignorant of tool windows. Three engine-level bugs fell out and are the durable part: stylesheet candidates surviving a detach, `tagName()` being an exact-class lookup, and the record-vs-host rule for which half of a region holds a panel |
| **W9** ✅ | Editor tear-out → **WINDOWED** frames: `DockWindow` hosts its own dock area, taskbar entry and all; dragging the tab back re-docks and the emptied window closes itself | multi-window editing | **Shipped 2026-08-22.** The drag itself needed almost nothing — `DockArea.detach` already reads `payload.sourceArea()`, and every window shares one `UIWindow`, one `UIDragController` and one hit-test, so a drag out of one dock and into another was cross-area by construction. That is the Design B payoff arriving on schedule. What W9 actually cost was everything AROUND the gesture, and none of it was dock logic: a torn-out window is a **peer** and not an accessory (`DESTROY_ON_CLOSE`, no `setOwnerWindow` — the opposite of W8's float, because a second place to work should bury the first when clicked); it closes when emptied but **never mid-drag**, since an empty dock is a legitimate transient state while a panel is detached and not yet dropped; the caption is the workbench's `Project - name.ext [where]`, through a title provider on the registry rather than anything the frame knows; and the tab strip's own scrollbar had to be given room, which took five wrong attempts before measuring showed the rail was `flex-grow: 1` over a `flex-shrink: 0` default and had simply never left the bar any. The durable half is four engine-level findings, all in the invariants table: focus delegation stopping on a focusable container, a window being activated a frame before deferred content exists, a drop that told nobody what it had moved, and a drag never completing the click that would have selected what it picked up |
| **W9.5** ✅ | **Window animations**: open, close, minimise into the taskbar, restore out of it, and maximise/restore-down — plus `transition: none` and `TransitionEngine.isAnimating` in the style engine, and `Desktop.setAnimationsEnabled` | it is what makes a compositor read as one | **Shipped 2026-08-22**, out of order — it belonged before W10 and was simply missed. Every one of them animates `transform` and `opacity` and nothing else, which is what DWM, Quartz and Mutter all do: a compositor animates a SURFACE. `UITransform` is layout-free here, so a window flying into the taskbar reflows nothing; transitioning `left/top/width/height` instead would re-run layout for the whole window on every frame, and for a maximise that is the worst moment to pay it. Maximise is therefore **FLIP** — let layout jump to the destination, apply the inverse transform instantly, ease it away. The existing transition engine turned out to be the right substrate and short by exactly two things, both of which are general and now exist: **`transition: none`**, without which applying an entry state is a change like any other and the window animates *backwards* out of it; and **`isAnimating`**, the DOM's `transitionend`, without which an exit cannot know when to actually detach — and it cannot simply wait the duration, because durations live in the sheet and this codebase does not put timings in Java. Nothing in `WindowAnimator` names a time or a curve, which is also the accessibility switch: a theme that drops the declarations gets everything instantly through no code path of its own. **The motion is ported from `gnome-shell/js/ui/windowManager.js`** — durations, easing modes, scale factors and pivots, shape only and no code, since GNOME Shell is GPL. **It took four passes and the first three are the useful part of this row.** The first used remembered Material-ish curves and read as "not quite Windows", which it was: both Fluent curves are far more extreme than a typical web easing and Microsoft's docs say so outright. The second was driven by CSS `transition`s and was reported as buggy, chopped and flickering, with minimise and close not animating at all — four separate silent failures, every one of them from the same root, which is that **the cascade is for rest states and an animation is a timeline**. The third was an imperative driver over the engine's own `ActiveTransition` maths, writing at ANIMATION origin so the cascade cannot see or fight it — the shape `CABasicAnimation`, `ValueAnimator` and `AnimationController` all share, and the right mechanism. It was still reported as not looking native, and that one was about the NUMBERS: WinUI's are **control** tokens governing a button's hover, not a window's flight, and DWM's own window timings are published nowhere. GNOME Shell is a production window manager whose constants are readable, so `SHOW/DESTROY/MINIMIZE/WINDOW_ANIMATION_TIME` (150/150/400/250ms), `EASE_OUT_EXPO` for travel and `EASE_OUT_QUAD` for a shape change are what it now uses. `ua/desktop.css` carries a comment where the CSS rules used to be, saying why a `transition` on `window` must never come back |
| **W10** ✅ | The switcher (MRU keybind) + the first-hide notification with `Keymap.acceleratorFor` | discoverability | **Shipped 2026-08-23.** `Mod+Tab` / `Mod+Shift+Tab`, resolved against the live keymap as open question 2 required — nothing binds a Tab stroke, the dock's Next/Previous Tab are on `Mod+PageDown`/`Mod+PageUp`, so the conventional pick was free. **`Alt+Tab` is the host OS's and a Minecraft client never sees it**, which is the whole reason a desktop metaphor inside an application needs a chord of its own. Ported in shape from GNOME Shell's `switcherPopup.js`/`altTab.js` (GPL — constants and behaviour, no code), and three of its four constants are load-bearing rather than cosmetic: **selection starts on the SECOND MRU entry** (the first is the window you are already in, so starting there makes the commonest gesture a no-op that reads as the switcher not working); the panel is **invisible for `POPUP_DELAY_TIMEOUT` 150ms** and a release inside that window commits having drawn nothing, which is the entire tap-to-bounce gesture and without which every bounce flashes a panel; and `NO_MODS_TIMEOUT` 1500ms is the only way to commit if somebody rebinds to a bare key. The modifier that holds it open is **read from the invoking command's live accelerator** and polled per frame against `getCurrentModifiers()` — GNOME's own `modifier-change` masking, which sidesteps the left/right-Alt duality entirely and cannot miss a release the way a key-up listener can. **Shift is stripped from that mask**, or letting go of Shift to cycle forwards again would commit mid-gesture. Tiles carry live `WindowThumbnail`s, so a **minimised** window shows the snapshot taken on its way out — the case the switcher exists for and the one a live-only mirror cannot draw. Escape sits on the **live-drag rung** and not the close-watcher cascade, which asks the active frame's stack first and would have minimised the window behind the switcher instead. Two defects fell out on the way: **`TextEditor` and `SearchReplaceBar` both ate Ctrl+Tab**, running their bare-Tab case regardless of the modifier — the keymap resolves only on an unconsumed event, so the chord indented the current line and the switcher never heard it, which reads as the switcher being broken rather than the editor being greedy. `TextEditor`'s own comment already stated the rule; Tab was missing from its yield list. **Arrow navigation is the same problem one level down** and is why the switcher gets first refusal on the keyboard while it is up: an arrow reaches the focused element and a focused editor moves its caret with it, so an arrow through ordinary dispatch would scroll the document behind the panel and never touch the selection. GNOME holds a modal grab (`Main.pushModal`) for the whole gesture; ours intercepts only the keys it acts on, ahead of dispatch, on the live-drag rung — **Tab deliberately excluded**, so repeating the chord keeps resolving through the keymap and the gesture stays rebindable. Left/Right are previous/next and wrap (GNOME's, modulo and all); **Up/Down move by a ROW and do not wrap**, which is a divergence: GNOME's switcher is a single line and spends the vertical arrows on an app's window sub-list we have no equivalent of, while ours wraps into a grid. The row width is read off the LAYOUT rather than computed — how many tiles fit depends on each window's shape and on what the sheet's `max-width` leaves, so re-deriving it would be a second implementation of flex-wrap free to disagree with the one on screen. Enter and Space commit on the spot with the modifier still held (GNOME's base class), which is the only way to finish at all if somebody rebinds to a bare key, and a handled key **reveals the panel** rather than waiting out the delay. **The mouse works too**: hover selects (hover IS the selection here, so the ring follows the pointer), a press activates, a press on the backdrop cancels, and each tile carries Windows' close button — red on hover, shown on the hovered tile AND the selected one, since on-hover alone it would not exist for anyone cycling with the chord. That needed the overlay to become hittable, which the standing fear says never to do; what makes it safe is that it takes no box at all whenever it is not drawn, so an invisible switcher cannot eat a click. Closing a tile goes through `requestClose` (a window's policy still decides what closing means) and drops the entry **optimistically**, because a close animates and waiting for the registry would leave a tile on screen for the window just dismissed. Selection is a **pure-white ring and no fill**: a darker fill competes with whatever the thumbnail is showing, a bright outline never does — and the colour is a theme decision rather than a derivation, which the governance test proved by refusing a token the light theme had no answer for |
| **W11** ✅ | Reconnect-on-restore: `persisted` reaches the workspace client, rebind on show | remote use | **Shipped 2026-08-23.** The client's IDENTITY survives the reconnect and the wire under it is swapped — `WorkspaceClient.rebind`. Everything holding one holds it in a `final` field (`Workbench`, `WorkspaceTreeSource`, `WorkspaceFileService`) and every consumer callback is registered on the client rather than on the wire, so swapping underneath needs no rebind threaded through five widgets; building a second client instead would strand every one of those subscriptions on an object nobody can reach. It is also bfcache's own answer — close on entry, reconnect on restore, do not rebuild the page. **The defect that fails in silence is `watched`**: a client-side memo meaning "I have already asked the server to watch this", which after a reconnect records promises the new peer never made — `finishRead` sees the path present, never re-asks, and change notifications stop PERMANENTLY for exactly the files that were open, with no error and no log line. Watches are therefore re-issued; presence and capabilities are dropped and re-seeded (capabilities to their optimistic default, never to denied); cached content is dropped and **etags are kept**, because an etag is what a save quotes and the server re-stats before writing, so a stale write returns a conflict a user can act on. **The view half is deferred to the frame the panel comes back**, which is where `on show` genuinely belongs: a listing describes a server nobody is talking to, no `fs.changed` can ever say so, and re-fetching one for a hidden window is the invisible work a detached window is supposed to have stopped doing. It needed no new mechanism — `ProjectFileTree`'s drain ticker returns false when detached and `onLayoutChanged` registers it again on the way back. **And the loader half was a real defect, found by reading rather than by any test**: nothing ever re-asked for the client. `CgUiScreen` calls `workspace.client()` once at editor construction and `CrystalEditor` holds it for the life of the screen, so the rebind was correct and permanently unreachable — every headless test passed because a test calls `rebind` itself. `Mc1710Workspace.pump`, which was an empty method kept only so the frame loop read the same, now does the per-frame re-ask; it is free when the wire has not moved, and one re-ask repairs the whole tree because the rebind preserves the client's IDENTITY. **`serverSmoke` passes** (including "no client-only class loaded on the server"); the live disconnect/rejoin gesture against `runServer` is still unverified — the existing remote probe joins but never reconnects, and two processes from one worktree contend for the vanilla jar at build time |
| **W12** | ✅ **Done.** Geometry persistence everywhere it means anything and session restore of the window set; **tool windows are not citizens** (`WS_EX_TOOLWINDOW`); **torn-out editor windows persist** (`windows` beside the dock tree); the **no-steal rule** (`openWindowInBackground` + attention flash); entry **badges, progress and middle-click close**. See *W12's tail* for the two properties deliberately left as they are | — | Ordered by demand |
| **W13a** ✅ | **Shipped 2026-08-23.** `WindowCommands` registers `window.restore/minimize/maximize/close/systemMenu`; `MenuId.WINDOW_SYSTEM` is rendered by all three routes; `WindowFrame` and `Desktop` answer `WINDOW_FRAME` and `Taskbar.Entry` overrides them for its own window. The caption **buttons are deliberately not routed through the commands** — they end in the same `WindowFrame` methods so they cannot diverge, and the maximise button is a toggle that does not map onto one id. `window.fullscreen` is W13b's and `window.move`/`size` are W13c's, per the lands-with-its-feature rule. ~~**The command surface.**~~ The `window.*` set in `CommandRegistry.global()`, resolved against focus by `DataContext`; the system menu rendered through `MenuBuilder` in all three places — `Alt+Space`, title-bar right-click, taskbar right-click. Retires W4's deferred per-entry context menu | everything below it | **Split out 2026-08-23.** This is the piece with architectural consequences and the rest are one-liners on top of it: the menu, the strip, the title bar and the keymap are four RENDERERS of one set of ids, which is the whole reason they cannot drift. Doing it first is also what stops W13b/c inventing a second way to invoke a window operation |
| **W13b** | Gestures: Alt-drag (keymap-resolved, **never** a hardcoded Alt), fullscreen (`F11`), drag-to-edge snap | — | Snap is the same band arithmetic as `DockDropZones`; fullscreen is a class plus `Taskbar.setBarVisible`, which already exists |
| **W13c** | Show desktop (minimise-all with a memory), the modal-blocked pulse + the first `CgPlatform.sound()` consumer, keyboard Move/Size | — | Move/Size is the only genuinely new interaction MODE here: modal, with no element to dispatch to, so it takes the rung the window switcher already occupies — intercepted in `consumeKeyboardEvent` ahead of dispatch, and only the keys it acts on |
| **W14** | Pin: the always-on-top band, the toggle, and the HUD — the overlay render hook, the paint-only entry, `__hud__` display-only presentation, visible-stays-live | live debugging over the running game | The one new platform capability; after W7, so there is a loader seam to extend |
| **W15** | The task manager panel; per-window zoom | observability, accessibility | Both standalone |

---

## How each piece is tested

| Piece | Test |
|---|---|
| State machine, illegal transitions | headless — no UI involved |
| **A hidden frame stops ticking** | headless: ticker on an element in a frame, hide, advance, assert silent — the regression most likely to be invisible |
| Hide clears input state | headless: hover/capture inside a frame, hide, assert forgotten |
| Raise/z-order and hit-test agreement | headless: two overlapping frames, click the lower — **through the real mouse path, never `sendInputEvent`**, which skips focus resolution and `emitMouseDown` and has now shipped two bugs behind sixteen green tests |
| Frame-scoped modality, all four points | one headless test per enforcement point, mirroring the existing four |
| Registry eviction runs `Disposer` | headless |
| Activation restores remembered focus | headless; the restore-never-steal rule from `ListView` applies |
| Geometry: clamp, cascade, maximise/restore rect | headless — geometry is style writes, all observable |
| Chrome, taskbar, switcher visuals | `cgui-desktop` harness scene — anything visual is harness, never Minecraft |
| Reconnect-on-restore | headless over `InMemoryTransport` ✅ (`WorkspaceReconnectTest`, `WorkbenchReconnectTest`) **and** in-game against `runServer` — still outstanding. The content-cache case is only reachable by rejoining a **different** world: an etag is `mtime + size` and nothing about it is server-scoped, so the obvious same-world test proves nothing |
| The whole gesture | in game: open editor, edit unsaved, un-maximise, open a second window, minimise both, restore from taskbar, assert undo history and dirty buffer survive |
| Presentation round-trip | headless: dock → float → dock keeps the **same content instance** and its view state (a scroll offset, an expanded folder) — and registry membership follows the mode |
| One document across two frames | headless: the same file in the workbench and a torn-out frame — one `LanguageServices`, one undo stack, an edit in either lands in both |
| No focus stealing | headless: with focus held in one frame, open another from a non-gesture source — focus unmoved, the new entry carries the attention class |
| The HUD path synthesizes no input | headless: paint a pinned frame through the paint-only entry — no hover, no enter/leave, no capture disturbed |
| Pin, end to end | in game: pin the Run console, close the desktop, run a script — output streams over the world with the cursor grabbed, and nothing is clickable |
| Cross-window drag-and-drop stays free | headless, after W9: drag a file from the explorer onto a torn-out editor's dock — one `UIDragController`, zero new code; pins the Design B payoff |

### `cgui-desktop` grows with the plan — the hands-on contract

**Decided 2026-08-22.** Every W with something visible lands its demonstration in the `cgui-desktop`
scene **in the same commit**, so each step can be driven by hand the day it exists — the harness boots
in seconds and is the recorded way to test anything visual. The scene is a living testbed, not a W1
artifact:

| After | The scene demonstrates |
|---|---|
| W1 ✅ | two floating windows: title-bar drag, eight-handle resize, clamping — plus a live geometry readout (the only way to watch the clamp arithmetic *while* dragging) and **F2** to open a cascaded window |
| W2 ✅ | overlap + click-to-raise (with the click still landing), `__active__` chrome, focus memory across activation — two buttons per window, because with one the focus delegate *is* the remembered control and the test passes by coincidence |
| W3 ✅ | minimise/close via the chrome against the real lifecycle — including an on-screen ticker counter that provably **stops** while its window is hidden and **resumes from where it stopped** on restore, which is the difference between retention and a rebuilt window. F3 restores the most recently used hidden window: scaffolding, because minimise with no way back is worse than no minimise, and W4's taskbar is what retires it |
| W4 ✅ | the taskbar: entries, active highlight, restore/minimise clicks — and it retires W3's F3 scaffolding, which is the point of the pairing |
| W5 ✅ | a **Modal** button in each window: the dialog blocks exactly its own window (backdrop and all) while the other window and the taskbar stay fully live, and Escape closes the dialog before the window |
| W6 ✅ | maximise/restore filling the work area (the taskbar's row is not part of it), the glyph swapping to "restore", handles disappearing, double-click, and the restore-drag coming loose under the pointer |
| W8–W9 | a window hosting a small dock: stripe float-out, Dock/Hide, tab tear-out into a new frame (`cgui-dock` keeps owning dock-*internal* behaviour) |
| W10 ✅ | the switcher on its chord, MRU order visible — a grid of live thumbnails, and the Welcome window advertises the chord by **resolving** it through `Keymap.acceleratorFor` rather than spelling it |
| W13a ✅ | right-click a title bar and a taskbar entry, and press `Alt+Space` — the same rows in three places, because they are one command set with three renderers |
| W13b | Alt-drag a window by its middle, `F11` to fullscreen (the strip goes too), and shove one at an edge to snap it |
| W13c | show desktop as a toggle that remembers; click a modal-blocked window and watch it pulse and hear it ding; keyboard Move/Size from the system menu |
| W14 | a **simulated game mode**: one key flips the scene to input-off, painting only pinned frames through the paint-only entry with `__hud__` styling live — the closest a GL harness can get to Minecraft without being it |
| W15 | the task manager listing the scene's own windows; per-window zoom |

W7's own row is now green in the scene too — the editor runs there as a window over the harness's
workspace, floating rather than maximised because the whole point in the harness is to see it share a
desktop. W11 and W12 remain the wire items. Their visual halves are still reachable here — once W12
lands, the scene can open a "server window" over `InMemoryTransport` — and the real thing is verified
in game per the rows above.

Standing scars that shape these tests: `TransitionEngine` ignores the delta (assert inputs, not
mid-flight values); a closed `Dialog` measures 0 (show first); dotted-capture pairs for any
token-precedence assertion; test worktrees need the provisioning ritual.

---

## Risks

- **The raise reparent trap.** Anyone "simplifying" z-assignment into a child-list move re-runs
  register/unregister over the clicked subtree. Named here and in the eventual `Desktop` javadoc; the
  hit-test-agreement test fails loudly on the session-state side effects.
- **A frame that keeps working while hidden.** Invisible by definition; the ticker test is the guard,
  and the ticker contract is the fix, not per-widget whack-a-mole. W15's task manager is the
  observability half — a panel over the registry showing each window's state, ticker count and
  retained size, so the invisible failure has somewhere to be seen.
- **The four-point modality refactor.** Miss one and everything looks green — the invariant table says
  so from last time. Four tests, one per point, before any refactor of the predicate.
- **Unbounded retention.** Slow leak, long sessions only. Eviction cap from day one, even if generous.
- **The disconnect interaction.** Recorded three times now; W11 exists because retention makes the
  struck-through stale-client defect reachable again.
- **Scope creep — in the new direction.** The old fence ("not a window manager") was wrong; the new
  fence is the Deliberately-not-building table, and it is normative. A compositor invites polish
  infinitely; W-items only.
- **`getAttachedWindow()` across hide.** Detached elements answer null; any code that caches the
  window across frames must re-ask. Mostly already true (detach exists today); hide makes it routine.
- **A hidden float with two owners.** Registry membership must follow presentation exactly — a
  float that is also in the `WindowRegistry` comes back from both the stripe and the taskbar, or
  from neither. The one-summon-surface rule is normative; the round-trip test asserts membership.
- **The HUD surface is real platform work.** A second paint entry over the running game, GL state
  shared with Minecraft's own HUD pass (the `invalidateAllIfPresent` discipline applies verbatim),
  per-game-frame cost, and a freeze contract that now keys on *hidden* rather than *screen closed*.
  It ships last among the core items (W14) for exactly that reason, and the paint-only entry gets
  its own no-input test.

---

## Exit criteria

1. Two windows visible at once, both draggable, both resizable on all eight edges, overlapping, with
   the click landing in the visually-top one — and raise-on-click reordering them.
2. Escape on the editor **minimises** it to the taskbar; the editor, its undo history and its dirty
   buffers are intact on restore. Closing the screen and reopening retains everything.
3. A hidden frame fires no tickers, runs no transitions, performs no analysis, and holds no input state.
4. A modal dialog in one window blocks only that window: the other window and the taskbar stay live.
5. Maximise fills the desktop and restore returns the exact prior rect; double-click and restore-drag
   both work.
6. The taskbar lists every live window with icon and title, marks the active one, restores a minimised
   one on click, and its context menu closes one — running `Disposer`.
7. `CgUiScreen` constructs no `UIWindow` and holds no editor static — it attaches to the engine-owned
   desktop and detaches on close.
8. A disconnect and rejoin leaves retained windows usable, their workspace clients rebound.
9. The switcher keybind is discoverable without reading the source (the first-hide notification).
10. A server-opened UI arrives as a window on the desktop beside the editor.
11. A tool window drags off the stripe into a floating window with Dock and Hide; Dock returns it
    to its remembered region, and Hide + a stripe press reopens it floating at the same rect.
12. An editor tab tears out into a taskbar-listed window sharing the document: an edit in one view
    is visible in the other, and one undo stack serves both.
13. A window opened by the server while the user types elsewhere takes no focus, and its taskbar
    entry flashes until activated.
14. A pinned window keeps rendering and updating over the running game with the cursor grabbed —
    display-only — and unpins back into the normal band from the desktop.
15. Alt+Space opens the system menu on the active window, and Move/Size drive it from the keyboard.

---

## Open questions

Named so they read as decisions pending, not gaps nobody saw.

1. **A per-frame `CommandRegistry` layer** (global < desktop < frame). Not needed yet: commands are
   facts about the application, enablement resolves from focus through `DataContext`, and the
   two-windows-two-`edit.save`s scenario the registry's own javadoc predicts is really an enablement
   question the focus walk already answers. Revisit at W12 if two server UIs genuinely register
   clashing ids.
2. **Default chords for `window.close` and `desktop.switchWindow`.** `Ctrl+W` and `Ctrl+Tab` are the
   OS-conventional picks and both are plausibly claimed (tab close; recent files). Resolved at
   W10/W13 against the live keymap — never hardcoded in this document.
   - **`desktop.switchWindow` = `Mod+Tab`, resolved 2026-08-23 at W10.** The keymap holds nothing on a
     Tab stroke at all: the dock's Next/Previous Tab are `Mod+PageDown`/`Mod+PageUp`, and no other
     bundle binds one — so the "plausibly claimed" worry did not apply to this keymap and the
     conventional pick was free. `desktop.switchWindowBack` takes `Mod+Shift+Tab`, as a separate
     command rather than one command reading the Shift bit: a user who remapped to a chord already
     containing Shift would otherwise lose the reverse gesture with nothing to report it.
   - **`window.close` = NO CHORD, menu only. Resolved 2026-08-23 at W13.** The worry that `Ctrl+W` is
     "plausibly claimed by tab close" turned out to be the wrong framing: the question is not who owns
     the chord but which gesture deserves one. Closing an editor tab is the frequent, cheap, undoable
     act; closing a *window* is rare and takes its content with it. Giving the rare destructive one a
     chord one keystroke away from the common one is how a user loses a window they meant to lose a tab
     from. It is reachable from the system menu, the ✕, and middle-click on its taskbar entry — three
     routes, none of them a slip. The command still exists and is still the one thing every route calls;
     it simply has no accelerator, which `MenuBuilder` renders as an empty column rather than a lie.
3. **An interactive HUD.** Discord's overlay has a clickable mode behind a cursor-freeing chord;
   ours is display-only by decision. Recorded so the display-only rule reads as chosen rather than
   overlooked — revisiting it means a platform capability for input while the game is ungrabbed,
   which is a new plan, not a W-item.

---

## Geometry persistence — the whole of it, deferred to W12

W8 persisted exactly one thing: a floating tool window's rect, because a float's frame is destroyed on
every hide and nothing else could say where it had been. That is the narrowest case of a general one,
and the general one is worth doing in a single pass rather than a field at a time.

**What should survive a restart**, and none of it does today beyond the one case above:

| Thing | Where it lives now | Note |
|---|---|---|
| Each window's position and size | `WindowFrame.wantedLeft/wantedTop` + inline width/height | Save the **intent** pair, never the placed one — a clamp saved is a clamp that compounds every launch |
| Which windows were open, and their stacking | `WindowRegistry` (open order + MRU) | Both orders, since neither is derivable from the other |
| Maximised state and the restore rect | `WindowFrame.restoreLeft/Top/Width/Height` | Restoring maximised without the rect leaves nothing to restore *to* |
| Hidden-but-retained windows | the registry's LRU | A hidden window with no record comes back as a fresh one, losing its content |
| A tool window's mode and float rect | `ToolWindowState` ✅ | Done at W8; the only piece that exists |
| Region weights and side weights | `WorkbenchRegions` / `ToolWindowLayout` ✅ | Already persisted by `WorkbenchSession` |
| Splitter positions inside a window | `SplitView` percentages | Not captured anywhere |
| Scroll positions | view state | **Deliberately not** — VS Code and IntelliJ both drop it, and a restored scroll into a file that changed is worse than the top |

**Two rules the W8 work already paid for, and they generalise:**

1. **Capture before the thing goes away, not after.** `hide()` detaches and *then* announces, so anything
   measuring in the listener measures a freed Taffy node and records a zero. `ToolWindowFrame` snapshots
   in its own `hide()` override for exactly this.
2. **A zero rect is refused on the way in.** A 0×0 frame at the origin is a legal encoding and an
   unusable window, and four floats cannot distinguish it from "never placed" — so an absent optional is
   omitted rather than written as zeroes, and a non-positive size falls back to the default.

---

## Always-on-top — decided at W8, 2026-08-22

A tool window torn out of the editor fell behind it the moment the editor was clicked. The obvious fix
is to open floats **pinned**, and it is the wrong lever.

**Pinned means above everything.** That is a strictly larger claim than the one the case needs: a pinned
Inspector also sits above a window it has nothing to do with, and `Desktop.PINNED_BAND` is reserved for
W14's HUD windows, where floating over the running game *is* the point. Spending it here would leave the
band meaning two different things.

**The relation actually wanted is Win32's owner/owned** — *"an owned window is always above its owner
and travels with it"* — which this document already cites and W5 already implements. What W5 implements
is ownership by **parenting** (`attachOwned`), and that is what made `FLOATING` unusable for a tear-out:
a child's containing block is its owner, so the window is clamped inside it, and it is not in the
`WindowRegistry`, so it has no taskbar entry.

So W8 splits the two. `WindowFrame.setOwnerWindow` is the relation **without** the parenting: the frame
stays top-level in the window layer — draggable anywhere, its own entry, its own stacking slot — and the
one thing it inherits is that `Desktop.raise` moves the whole owner group, owner first and its owned
windows immediately above it. Raising an *owned* window raises its owner too rather than lifting it out
of the group, which is what every desktop does when you click a palette.

`attachOwned` keeps its meaning for the case it was written for — a modal, which genuinely should be
confined to its owner and genuinely should not be independently reachable.

~~**Still owed, and deliberately not done here:** owned windows do not yet *travel* with their owner
through hide and minimise.~~ **Done at W12** — see *Tool windows are not citizens* below. The prediction
was right about the shape: it needed a record of which windows the owner took down, so that a re-show
restores exactly those and not the ones the user had already closed.

---

## Sources — read 2026-08-21

- [Page Lifecycle API](https://developer.chrome.com/docs/web-platform/page-lifecycle-api) and the
  [WICG spec](https://wicg.github.io/page-lifecycle/) — the six states, `freeze`/`resume`, `wasDiscarded`
- [Back/forward cache](https://web.dev/articles/bfcache) — eligibility, `pageshow` + `event.persisted`
- [Disconnect WebSockets on BFCache entry](https://groups.google.com/a/chromium.org/g/blink-dev/c/52nlr8z3Png)
  — from *"an open connection blocks retention"* to *"close on entry, reconnect on restore"*
- [Opening and Closing Windows](https://developer.apple.com/library/mac/documentation/Cocoa/Conceptual/WinPanel/Tasks/OpeningClosingWindows.html)
  — `orderOut:` vs `close`, `releasedWhenClosed` ignored under a window controller
- [Using Window Notifications and Delegate Methods](https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/WinPanel/Tasks/UsingWindowNotDel.html)
  — `windowShouldClose:` as a veto
- Swing MDI: `JDesktopPane`, `JInternalFrame`, `DesktopManager`, `WindowConstants`
  (`setDefaultCloseOperation`) — the JVM's in-process window manager, and the naming precedent
- Win32: `WM_CLOSE`/`DestroyWindow`, owner/owned z-order ("an owned window is always above its owner"),
  `CW_USEDEFAULT` cascade, Alt+Tab's MRU ordering, restore-drag of a maximised window
- X11 ICCCM/EWMH: `WM_DELETE_WINDOW`, `WM_TRANSIENT_FOR`, layered stacking order
- LDLib2 `WindowDragHelper.ResizeHandle` — the eight-handle precedent `UIResizer` already cites
- IntelliJ tool windows — view modes (Dock/Undock/Float/Window), `WindowInfoImpl.type`,
  Shift+Escape hide, the float's Dock/Hide chrome — the behaviour W8 ports
- VS Code `auxiliaryEditorPart.ts` (floating editor windows) — shape only; `FloatingDock`'s
  javadoc already cites it as the thing deliberately not done at the OS level
- Discord and Steam in-game overlays — the pinned, display-only HUD precedent: arranged from the
  overlay UI, watched in-game
- Win32 `WS_EX_TOPMOST` / EWMH `_NET_WM_STATE_ABOVE` (the pin band); `SetForegroundWindow`'s
  foreground-lock rules + `FlashWindowEx`, X11's urgency hint, macOS `requestUserAttention`
  (no-steal + attention requests)
