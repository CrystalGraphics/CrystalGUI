# CrystalGUI — Workbench services

The service layer under the dock, the workbench and the editor: the things a widget is allowed to
*ask* rather than reach through the application for.

**Read this before adding anything to `ui/elements/dock/`, `ui/elements/workbench/` or `editor/`.**
Most of what looks like a missing feature there is a service that already exists, and most of what
looks like a needed helper is the symptom of one that does not — see
[`plan.md`](../plan.md) for the architecture review this layer is being built from.

> **This document is the index of that layer. Every new service API lands here in the same commit
> that introduces it.** A capability nobody knows about gets re-implemented locally, which is exactly
> the accumulation `plan.md` exists to stop.

---

## Status

| Service | State | Section |
|---|---|---|
| `Disposer` — ownership and lifetime | **shipped** | [Disposal](#disposal) |
| `DataContext` — "what am I acting on" | **shipped** | [Data context](#data-context) |
| Typed service events — replacing polling | **shipped** | [Service events](#service-events) |
| `Resource` — URI schemes, virtual documents | **shipped** | [Resources](#resources) |
| `DockPane` — retargetable panel views | **shipped** | [Panes and placement](#panes-and-placement) |
| `DockService` — `open(input, placement)` | **shipped** as `Workbench.open` | [Opening things](#opening-things) |
| `DocumentKind` — a file type, declared by its owner | **shipped** | [Contributions](#contributions) |
| `Workspace` — the server's filesystem, from the client | **shipped** | [Resources](#resources) |
| `EditorService` — one lane for opening anything | **shipped** | [Opening things](#opening-things) |
| `Inspector` — one inspector, any subject | **shipped** | [Contributions](#contributions) |
| `Notifications` / `StatusBar` — events and ambient text, plus their views (`StatusBarView`, `NotificationsView`, `NotificationBalloons`) | **shipped** | [Notifications and status](#notifications-and-status) |
| `DockBannerProvider` — a strip above a panel | **shipped** | [Contributions](#contributions) |
| `JobScheduler` — work off the UI thread | **shipped** | [Background work](#background-work) |
| `LanguageServices` — the engine behind a document | **seam shipped, no engine yet** | [Language services](#language-services) |

---

## Background work

`com.crystalgui.core.async` — `JobScheduler`, `JobKey`, `JobLane`, `JobContext`.

Runs work off the UI thread and hands the answers back on it. Until this landed, the only executor in
`core/` was SVG preloading and everything else ran on the frame — which is fine until something wants
to compile a script, scan a classpath or reparse a file, all of which are far past a frame budget.

```java
scheduler.job(JobKey.of(document, "diagnostics"), JobLane.LATENCY, context -> compile(snapshot, context))
        .debounce(300)
        .onDone(result -> install(result))
        .submit();

scheduler.drain();                     // once per frame, on the UI thread
scheduler.cancelAll(document);         // closing a document
```

### The frame tick is the heartbeat

**Every decision the scheduler makes happens on the UI thread inside `drain()`** — debounce windows are
evaluated there against an injected clock, jobs are promoted there, results are delivered there. Only
the job body runs elsewhere.

That is what makes it testable: no timer threads, no scheduled futures, no sleeping, so a test with a
same-thread executor and a hand-cranked clock exercises the identical code path the editor does. It is
also what keeps the concurrency surface to exactly one object (the completion queue) — nothing else is
touched by two threads, so no widget ever needs a lock.

The cost is that a job starts on a frame boundary, up to ~16ms at 60fps. That is inside every budget in
`plan_syntax.md` §7.3, and it buys determinism.

`drain()` is **deliver → promote → deliver**. The second delivery catches a job that finished *during*
promotion (always, on a same-thread executor; sometimes, on a real pool with short work) which would
otherwise wait a whole extra frame. Deliberately two passes rather than a loop to quiescence: a
completion handler that re-submits an undebounced job is an ordinary shape, and a loop would let it spin
the frame instead of settling on the next one.

### Single-flight is keyed, and both halves of the key matter

A `JobKey` is `(owner, kind)`. Re-submitting a key that is **waiting** replaces it; re-submitting one
that is **running** supersedes it — the runner is asked to stop and its result is dropped when it
arrives. So a burst of keystrokes leaves one live job per key rather than one per keystroke, and the
last submission always wins.

- Keying on **kind alone** makes two open documents fight, and one editor of a split pair never updates.
- Keying on **owner alone** makes a document's reparse cancel its own diagnostics.
- The owner is compared by **identity**, never `equals` — two documents holding identical text are not
  the same document.

> **A superseded job's result is discarded even if the job never polled for cancellation.** Correctness
> therefore does not depend on well-behaved job bodies; only responsiveness does.

### Lanes, and the starvation guard

`INTERACTIVE` (a human is blocked — completion) > `LATENCY` (visible, tolerates a few frames — reparse,
semantic tokens) > `BACKGROUND` (nobody is watching — indexing, first compile).

Strict priority **plus** a guard: a job that has waited past `DEFAULT_STARVATION_GUARD_MILLIS` is
promoted regardless of lane. Without it, a document being typed in produces a steady stream of
higher-lane work and a `BACKGROUND` index queued behind it is never built — and the symptom is not a
hang but completion quietly never learning about unimported types, which reads as a missing feature
rather than a scheduling bug.

### Cancellation is cooperative

`JobContext.isCancelled()` / `throwIfCancelled()`, polled at loop heads and between phases.
`Thread.stop` is gone and interrupting a compiler mid-run leaves its state undefined, so there is no
honest alternative. `throwIfCancelled` is control flow, not failure: the scheduler drops it silently
without logging.

### What it deliberately does not know

**Document versions.** `TextBuffer.version()` is a monotonic counter bumped by every applied edit —
*including undo and redo*, which move the text as surely as typing does. Staleness policy belongs to the
consumer, because there are three legitimate answers (discard / keep-and-adjust / keep-per-line, see
`plan_syntax.md` §8) and a scheduler deciding centrally would have to understand every consumer. It
guarantees only that a superseded job's result never lands; comparing the stamp is the caller's job.

### Rules

- **`drain()` on the UI thread, once per frame.** It returns whether anything is outstanding, so a
  ticker can stop when idle.
- **Never touch UI or document state from a job body.** Return an immutable value; the `onDone` handler
  runs on the UI thread.
- **Snapshot what the job needs before submitting**, and stamp it with `buffer.version()`.
- **`cancelAll(owner)` when a document closes**, or its work arrives for an editor that no longer exists.
- **`dispose()` does not shut the executor down** — it may be injected or shared, and a scheduler does
  not own what it was handed.

---

## Disposal

`com.crystalgui.core.dispose` — `Disposable`, `Disposable.Gl`, `Disposer`.

An ownership tree, ported from IntelliJ's `Disposer`. Register a child against a parent; disposing the
parent releases its children **first, in reverse registration order**, then the parent.

```java
Disposer.register(parent, child);      // child's lifetime is now part of parent's
Disposer.dispose(parent);              // children first, reverse order, then parent
Disposer.isDisposed(parent);           // idempotent — a second dispose is a no-op
Disposer.newDisposable("drag");        // a scope to hang things off when there is no natural owner
```

### What it is NOT

**It does not replace `CgGraphicsLifecycle.destroyContext()`.** That already sweeps every
CrystalGraphics registry in a documented order at context teardown, and nothing escapes the process.
Reaching for `Disposer` to "stop GL leaking at shutdown" is solving a solved problem.

It exists for the two things a registry structurally cannot do:

1. **Release on close rather than on exit.** A registry knows what exists, never what is still
   *wanted*. Closing a shader graph frees nothing unless somebody says so.
2. **Reach what no registry can see.** `CgPreviewRenderer.delete()` states it: *"the pool's targets
   are `createOwned`, so no registry sweeps them."* `CgUiPaintContext`'s layer FBO pool is the same.
   For that class, release depends on somebody remembering — which is the thing an ownership tree
   exists to stop depending on.

### Rules

- **Never call `dispose()` directly.** Go through `Disposer.dispose(x)`, or the children are skipped.
- **`dispose()` releases what you own *directly*.** Trust the tree for anything registered under you.
- **Make it idempotent.** The tree calls it once; a surviving hand-written `delete()` may call it again.
- **Do not throw for an ordinary reason.** A throw is caught and logged so siblings still release, but
  it leaves your own state undefined.
- **Own GPU memory? implement `Disposable.Gl`.** Freeing a GL object off the GL thread is silent
  corruption, not an exception. `Disposer` defers those to the GL thread; see below.

### The GL gate

`Disposable.Gl` disposals are routed to the GL thread rather than run wherever the request came from.

- `CgUiLifecycle.onInit` installs the gate — the thread receiving `onInit` *is* the GL thread.
- `CgUiLifecycle.onFrame` drains the queue; it is the one moment we are certainly on it.
- **The default gate runs everything immediately**, which is what keeps `Disposer` usable from
  `headlessTest` where there is no context. Do not "fix" that by requiring a gate.

### The disposal protocol for GL resources

**`dispose()` must mean "give back what I was using", not "delete GL objects".** Creating and
destroying GPU objects is expensive and driver-serialised; a design where closing a tab deletes
framebuffers and reopening it creates them is paying that cost for nothing.

So, in order of preference:

| Situation | What `dispose()` should do |
|---|---|
| The resource comes from a pool | **release the key back to the pool.** Delete nothing |
| The resource is genuinely unique and unshareable | delete it |
| The resource is a *reference* into something shared | drop the reference; the owner decides |

Pools are owned by the **context** (`CgGraphicsLifecycle`), never by a document or a panel. A document
owns *slots*, not GL objects. That way the GL object count is bounded by what is on screen rather than
by how many things happen to be open.

**What this looks like in practice.** Closing a shader graph should be bookkeeping — release its
preview keys, delete nothing — and reopening it should allocate nothing, because the pool still holds
the targets. The FBOs live as long as the context, which is the lifetime they should have had all
along.

#### How it was closed

`CgPreviewSlots` was instantiated **per `CgPreviewRenderer`, therefore per open graph**, so N open graphs
held N × capacity targets and closing one *deleted a whole pool*. It is now owned by
`CgPreviewPool` — one shared pool per `(size, samples)`, keyed by a per-renderer **scope prefix** so one
graph's cull cannot release another's targets.

| | Before | Now |
|---|---|---|
| Memory | scales with graphs **open** | bounded by capacity — what is **visible** |
| Close a graph | deletes its framebuffers | releases keys; deletes nothing |
| Reopen | allocates a pool | takes slots back; allocates nothing |
| Teardown | every renderer's owner had to remember `delete()` | `CgGraphicsLifecycle.destroyContext()` frees the pool |

That last row is the ownership point made real: the targets are `createOwned`, so no registry sweeps them,
and release depended on somebody remembering — which is the thing an ownership tree exists to stop
depending on.

`CgPreviewRenderer.delete()` still deletes its own two meshes. Those are genuinely its own, and they are
the small half.

#### The rule that follows

**Do not add `Disposable.Gl` to something just because it touches GL.** Ask first whether the resource
should be pooled instead. An object that returns slots to a shared pool does not need to be
`Disposable.Gl` at all — its release is ordinary bookkeeping and is safe on any thread. `Disposable.Gl`
is for the genuinely unique and unshareable, and the smaller that set is, the better the engine
behaves.

### Where it is wired today

| Owner | Owns | Notes |
|---|---|---|
| `CrystalEditor` | every `ShaderGraphEditor` it builds | replaced a `graphs` list that was never pruned, so every graph ever opened stayed reachable for the session |
| `ShaderGraphEditor` | its `MainPreviewPanel` | that panel's `delete()` had **no caller anywhere** — its `createOwned` target and meshes leaked for the life of the process |
| `DocumentReference` | one holder's claim on a document | the model is disposed by the **last** reference released, so a tab, the Problems panel and a background compile can each hold one |
| `CgUiLifecycle` | the GL gate and its queue | not an owner — the seam |

### What disposes, and what deliberately does not

| Action | Disposes? | Why |
|---|---|---|
| **Close a tab** | **releases that tab's reference** | `EditorService.close` drops the `DocumentReference` and nothing more. The model is disposed when the **last** holder releases — which may be the Problems panel, an index or a background compile, later than the tab and never earlier. Releasing on the tab's close is the "Parser is closed" defect: the surviving holder is left reading something torn down, which fails while looking fine |
| **Rename / move a file** | **no** | `Document.retarget` moves it. It is the same document at a new address; disposing and rebuilding would discard unsaved work and churn GL for a path change |
| **Delete a file** | **no — it is ORPHANED** | The buffer is kept and the document moves to `DocumentState.ORPHANED`, because closing the tab would throw away text the user may well want to write back. That is the whole reason a buffer is worth more than the file |
| **Close the editor** | yes | `Disposer.dispose(editor)` — the root of the tree |
| **Context destroyed** | yes | `CgGraphicsLifecycle`, which is where pooled GL objects are supposed to die |

### Subscriptions belong to the thing they update

A bound widget follows its **store** — `settings.onChanged`, `document.onChanged` — so that an edit made
anywhere else reaches it. The store outlives the widget by a long way: a `Settings` lives as long as the
application and a `GraphDocument` as long as the file is open, while an inspector rebuilds every control
it shows on every click.

**A binder declares the subscription; the engine decides when it is live.**

```java
control.follows(() -> {                       // ConfigControl.follows
    control.setValueObject(read(store));      // read FIRST — it may have moved while detached
    return store.onChanged.connect(...);
});
```

`ConfigControl` connects on attach, disconnects on detach, and **re-establishes on re-attach** —
`onWindowChanged` already fires for every element of a detached subtree, so leaving the tree is announced
without anyone arranging it.

> **Nobody releases these, because nobody can be trusted to.** The first version had the binder subscribe
> directly and every *owner* release: a `ConfiguratorPanel` replacing its rows, a `GraphNode` being
> deleted, the graph clearing every node, and a floating port editor being unmounted — four owners sharing
> no supertype, each needing to remember a call whose omission is invisible, and a fifth owner would
> simply not know. That is the bookkeeping the ownership tree exists to remove.

> **Re-subscribing, not merely releasing.** Dropping on detach alone is a trap: a control taken out of the
> tree and put back — a tab hidden and shown, a pane retargeted — comes back permanently deaf, and only in
> cases nobody tests. It also means a detached control follows nothing *by design*: it cannot be seen, so
> there is nothing to keep current, and it re-reads on the way back in.

> **This is the failure mode the whole `Disposable` layer exists for, and it is invisible from both ends.**
> The host looks correct because it subscribed; the store looks correct because it notified. Nothing
> throws, nothing logs, and the only symptom is a session that gets slower the longer it is open. It went
> unnoticed in both `SettingsConfigurator` and `NodeFieldBinder`. Pinned by
> `ConfiguratorPanelLifetimeTest`, which asserts both that the count stops growing **and** that the
> surviving rows still follow the store — a fix that disconnects everything passes the first alone.

No known gap here. A node's field controls, a floating port editor's control and an inspector row all
release the same way, because none of them is doing anything — leaving the tree is the release.

---

## Data context

`com.crystalgui.core.data` — `DataKey`, `DataProvider`, `DataContext`. Standard keys in
`com.crystalgui.ui.data.UiDataKeys`.

How a command finds its subject without naming the widget that supplies it. Ported from IntelliJ's
`DataKey`/`DataProvider`/`DataContext`.

```java
// asking, from a command
GraphView graph = context.data().get(GraphView.GRAPH_VIEW);
if (context.data().has(UiDataKeys.SELECTION)) { … }

// answering, from a widget
@Override
public Object getData(DataKey<?> key) {
    if (key == GRAPH_VIEW) return this;
    return super.getData(key);        // ALWAYS last
}
```

### The rule

**First non-null answer of the right type, walking outward from the focused element.** The same rule
the keymap uses to resolve a binding and `UndoScope.nearest` uses to find a stack — so a keystroke, an
undo and a command all agree about what they are addressing.

This replaced three hand-rolled copies of the same walk (`GraphCommands.graphFor`,
`ShaderGraphEditor.editorFor`, `UndoScope.nearestScope`), each with a different `instanceof`. The point
is not that it is shorter: a widget can now *supply* a subject without **being** it, so a wrapper, a
preview or something not yet written can participate in a command written today.

### Rules for implementers

- **Answer for yourself only.** An element that answers on behalf of its children defeats the walk —
  inner elements are asked first precisely so the innermost answer wins.
- **Call `super.getData(key)` last**, or the generic `ELEMENT` answer stops being reachable.
- **Be cheap and side-effect free.** This runs while menus are built and palettes filtered — often, for
  many keys. Cache where the value changes, not here.
- **Declare a key where its concept lives.** `UiDataKeys` is for what the engine has an opinion about;
  a key belonging to one feature belongs with that feature (`GraphView.GRAPH_VIEW`,
  `ShaderGraphEditor.SHADER_GRAPH`).
- **Do not keep a `DataContext`.** It caches for one pass and is only valid for that pass.

### Two behaviours worth knowing

- **Internal children are walked.** Click-focus targets the exact element hit, which in a composite is
  one of its internal parts — a walk that skipped them would lose the subject for precisely the widgets
  built properly.
- **A wrong-typed answer is skipped, not fatal, and does not stop the walk.** Accepting it and casting
  to null afterwards would let one mistaken provider shadow a correct one further out; the command then
  reports "nothing selected" in a widget that plainly has a selection.

### Not yet here

The `when`-expression parser (`editorFocus && resourceExtname == .java`). Predicates over
`DataContext` cover commands today; the parser is only needed when keymaps want conditions, and it is
~1000 lines in VS Code with most of that being parsing. See `plan.md` §15.6.

---

## Commands

`com.crystalgui.core.command` — `Command`, `CommandRegistry`, `MenuId`.

One type. A command carries its id, title, enablement, default bindings **and** menu placement, and is
registered into `CommandRegistry.global()`.

```java
CommandRegistry.global().register(Command.of("graph.delete", "Delete")
        .binding("Delete", "Backspace")
        .menu(MenuId.GRAPH_CONTEXT, "modify", 10)
        .enabledWhereData(context -> context.has(GraphView.GRAPH_VIEW))
        .runWithData(context -> context.require(GraphView.GRAPH_VIEW).deleteSelection()));
```

> There was briefly a separate `Action`/`ActionRegistry` beside this. It was the same concept under a
> second name — a command with menu and binding metadata — so it was folded back in. If you find a
> reference to `Action` anywhere, it is stale.

### Commands are global; context is local

A command is a fact about the *application*. What varies per window is what is **focused**, and that is
`DataContext`'s job. Registering per window meant every window re-registered everything, a widget had
to find "its" window before it could contribute, and one widget ended up calling `installCommands()`
from a **frame ticker** — so its commands did not exist until a frame after it attached.

`CommandRegistry` instances still exist and fall through to the global one. An instance is a place for
*overrides*; almost nothing needs one, and tests use them for isolation.

### The rule for whether a command may be global

**A command can be global exactly when it resolves its subject from `DataContext`.**

A command that *captures* an owner cannot: registration is idempotent, so the second registration is
skipped and every later invocation runs against the **first** owner. That is not hypothetical — it is
what the test suite caught when `ExplorerCommands` (which closes over a `Workbench`) was globalised.

| Global today | Still per-window, because they capture an owner |
|---|---|
| `UndoCommands`, `GraphCommands`, `DockCommands`, `EditorCommands`, `ShaderGraphEditor`, `BlackboardPanel` | `ExplorerCommands` (`Workbench`), `CrystalEditorCommands` (`CrystalEditor`, `UIDocument`), `ChromeCommands` (`UIDocument`) |

The three on the right migrate once there are data keys for a workbench, an editor and a window — the
same move that turned `GraphCommands.graphFor` from an `instanceof` walk into a key.

### A widget's commands arrive with the widget — two hooks on `UINode`

```java
class GraphView extends CanvasView {
    @Override protected void registerCommands(CommandRegistry registry) { GraphCommands.register(); }
    @Override protected void bindKeys() { GraphCommands.bindDefaults(keymap()); }
}
```

| Hook | Runs | For |
|---|---|---|
| `registerCommands(CommandRegistry)` | **once per concrete class** | the commands themselves — one application-wide fact each |
| `bindKeys()` | **once per instance** | chords scoped to *this* widget, on `keymap()` |

The split is load-bearing. A command is registered once and resolves its subject from `DataContext`; a
**binding on an element** is the only thing that scopes a chord to a widget, so it must be on each one.
`F` frames a graph and `Mod+D` adds a caret in an editor precisely because those live on the elements.

Both run from `UINode`'s constructor, so **subclass fields do not exist yet** — the classic Java
hazard, deliberately embraced. Registration happens once per class, so a captured `this` would pin every
later invocation to whichever instance was built first; the timing makes that hard to write by accident.

> **Every earlier shape was something a caller had to remember, and each failed silently.** A static
> `register(registry)` needed a host that knew the widget existed. An instance `installCommands()` needed
> a window, so it was called from a **frame ticker** — commands did not exist until a frame after the
> widget attached. `TextEditor` guarded one with a `commandsInstalled` flag consulted from the **layout
> path**, so an editor never laid out had no commands at all. And for one commit `GraphView` had
> registration without binding: every graph command existed, was enabled, showed in the palette, and
> answered no key — because the binding half was still waiting on `GraphCommands.install(window)`, which
> by then nothing called.

### Once-ness belongs to the registry: `contribute`

```java
public static void register() {
    CommandRegistry.global().contribute(GraphCommands.class, GraphCommands::declare);
}
```

Bundles used to open with `if (registry.contains(SAVE_FILE)) return;` — one arbitrary command id
standing in for a whole set. Wrong in both directions: add a command to the bundle and it never
registers; unregister that one id and the whole bundle re-runs.

Keying on the **contributor class** asks the real question, and because the record lives on the registry
rather than in a static, `resetForTesting()` clears it too. A static latch does not — the reset empties
the registry, the next widget of an already-seen class registers nothing, and the command is simply
absent. `AutomaticCommandRegistrationTest` pins that case specifically.

It is per registry instance, which is what gives an owner-capturing bundle **per-window** once-ness: those
register into the window's own registry, so a second window gets its own copy instead of reusing the
first's.

### Registration is explicit

Nothing self-registers. **No static initialisers** — a command's existence would then depend on
class-loading order, and `CrystalEditorCommands` already states why that is refused: *"a registry that
quietly acquired commands nobody registered surprises anything that enumerates it"*, which is exactly
what the palette does. `registerCommands` is not an exception: it is triggered by constructing the
widget, which is a deterministic act by a caller, not by a class happening to load.

### Bindings and menus travel with the command

A binding and the thing it invokes are one fact. They used to be two: registration in one place,
`keymap().bind(spec, id)` in another — usually inside a widget, so it could only run after that widget
existed. `Command.binding(...)` is the default; a user's keymap still overrides it.

**Declared, or element-scoped — pick by scope, not by convenience.**

| | Where | Example |
|---|---|---|
| Application-wide chord | `Command.binding(...)` | `Mod+Z` undo, `Mod+W` close panel — a dock wraps everything |
| Widget-scoped chord | `bindKeys()` on the element | bare `F`/`A`/`Space` in a graph, `Mod+D` in an editor |

A declared binding is application-wide *by definition*, so a bare letter must never be one — it would be
live over every text field on screen. Conversely, binding an application-wide chord onto a root element
makes the whole set a **host obligation**: `DockCommands` was bound that way, no harness scene called
`install`, and every dock in the gallery had eight commands and not one key.

Two consequences that are easy to miss:

- **An element that binds a command explicitly suppresses that command's declared default** — otherwise
  rebinding undo would leave `Mod+Z` live and the two would disagree. This is why `EditorCommands` no
  longer re-binds `edit.undo` on the editor.
- **`Keymap.acceleratorFor` / `acceleratorsFrom` consult declared bindings last**, after the scope walk.
  They must, or every menu and palette entry for a declared chord renders blank while the key works —
  the same "menu disagrees with the keystroke" failure their own javadoc warns about, from the other side.

`menu(MenuId, group, order)` is what lets a widget contribute to a menu it does not own, instead of
reaching the method that builds it. `CommandRegistry.menu(id, context)` returns the contributions in
group-then-order, **omitting disabled ones** — a context menu is built for one position and an entry
that cannot apply there is noise. A palette wants the opposite and asks `all()`, rendering enablement
itself.

### What a host still installs: nothing

`CrystalEditor.install(window)` is **gone**, and so is every `install`/`bindDefaults` pair except the two
element-scoped ones (`GraphCommands`, `EditorCommands`, `ExplorerCommands` — called from the owning
widget's `bindKeys`). Constructing a widget is what registers its commands.

The last three holdouts captured an owner, which is what made them un-registerable once. They now resolve
it from the context instead:

| Was captured | Now |
|---|---|
| `ExplorerCommands(Workbench)` | `Workbench.WORKBENCH` |
| `CrystalEditorCommands(CrystalEditor, UIDocument)` | `CrystalEditor.CRYSTAL_EDITOR` + `UiDataKeys.WINDOW` |
| `ChromeCommands(UIDocument)` | `UiDataKeys.WINDOW` |

This is strictly better than capturing, not merely equivalent: with two windows open the palette now opens
in the one you pressed the key in, and Save Layout saves the right one. The captured version could not
have done either.

**It also deleted a polling loop.** `Workbench.tick()` called `installExplorerCommands(window)` *every
frame* behind a flag, for one reason — registration needed a window to reach a registry. Same for
`TextEditor`, which installed from `updateWindow()`, i.e. the layout path.

### `onWindowChanged`, and the window-level provider

Two things fell out of that work.

`UINode.onWindowChanged(previous, current)` is the hook whose absence caused those polls. Anything an
element must do *once it has a window* goes here. Both arguments are given because detach is the half that
leaks.

`UIDocument.addDataProvider(...)` is IntelliJ's frame-level `DataProvider`, consulted by `DataContext`
**after** the element walk. It exists because the walk goes *outward*, so it only finds ancestors — and a
`Workbench` is a descendant of the root, alongside everything else. With nothing focused, which is how a
window looks the moment it opens, there is no workbench on the path at all. `Ctrl+P` and `F5` are precisely
the keys pressed before anything is focused; resolving from focus alone re-broke both, and silently, since
a command with no subject just reports itself disabled.

Last, never first: an element that answers still wins, or two open editors would both resolve to whatever
the window named and focus would stop deciding anything.

### Menus are queried, not written

```java
Command.of(NEW_FILE, "New File…").menu(MenuId.EXPLORER_NEW, "1_new", 10)
ContextMenu.of(MenuId.EXPLORER_CONTEXT)      // the whole menu
```

A hand-written builder lists exactly what a menu has, which means **only its author can add to it**. That
is the coupling `MenuId` was introduced to remove — and for a while it removed nothing, because
`Command.menu(...)` recorded placements that nothing read: the explorer's menu was still a thirteen-line
literal and the id had no production users at all.

**Two menus were queried and one was still written.** `BlackboardPanel.openRowMenu` built Rename / Delete /
Duplicate by hand: three literal items, a hand-placed separator, an accelerator read by hand, and a
listener dispatching on the item's **display label**. All three commands already existed with their own
labels, bindings and `enabledWhen`, so the menu was a second copy of each that could drift from the first —
and nothing could add a fourth item without editing that method. It is now
`ContextMenu.of(MenuId.BLACKBOARD_CONTEXT)`, and accelerators, the separator, dimming and dispatch all fall
out instead of being maintained.

> **A picker is not a menu.** `BlackboardPanel`'s `+` and type menus and the Main Preview's mesh menu stay
> hand-built, correctly: they list a fixed data set their owner defines, and there is no third party who
> could meaningfully contribute a mesh. `MenuId` is for menus of *commands*, where the open set is the
> point.

- Group then order, groups sorted lexicographically — VS Code's `"1_new"`, `"2_clipboard"` convention.
- Separators fall out of group boundaries; no contributor asks for one.
- **A submenu is its own `MenuId`** (`MenuId.EXPLORER_NEW`), nested via `nestedIn(parent, title, group,
  order)`. If it were an entry kind, only the parent's author could add to it — the same coupling one
  level down.
- **Disabled items are dimmed, not dropped** — `ContextMenu`'s rule, deliberately *not*
  `CommandRegistry.menu`'s, which filters. A menu whose items move depending on what applies is a menu
  whose items are never in the same place twice. A submenu with *nothing contributed* is dropped, which is
  a different thing: that is a registration that never happened.

---

## Service events

`com.crystalgui.core.signal` — `Signal.Action` / `Signal.Value<T>` / `Signal.Pair<A,B>`, `Connection`,
`ConnectionGroup`.

**Service-owned typed signals, not a global topic bus.** A signal is a `public final` field on the thing
that owns the fact:

```java
dock.onDidChangeActivePanel.connect(panel -> follow(panel));
Disposer.register(this, source.onDidLoadListing.connect(this::retry));   // owned, not remembered
```

IntelliJ's `MessageBus`/`Topic` was rejected deliberately: a topic is a global constant anything may
publish to, so "who fires this" is a repo-wide search and "what does this service announce" has no answer
at all. A field is discoverable by autocomplete and impossible to publish to from outside.

**Naming is VS Code's**: `onDidX`, past tense — the change has already happened. There is no `onWillX`
(vetoable) signal yet and none should be invented speculatively.

### What exists

| Signal | Owner | Replaced |
|---|---|---|
| `onDidChangeActivePanel` | `DockArea` | three per-frame polls at once |
| `onDidClosePanel` | `DockArea` | nothing — the fact nobody could state |
| `onDidOpenDocument` / `onDidCloseDocument` | `Workbench` | `onDocumentLoaded`, and its missing half |
| `onDidChangeLayout` | `DockArea` | the activity bar's `:checked` sweep |
| `onDidRegister` | `DockPanelRegistry` | the activity bar's descriptor walk |
| `onDidLoadListing` | `WorkspaceTreeSource` | `WorkbenchSession.tick`'s per-frame restore retry |
| `onDidChangeState` | `WorkspaceDocuments` | a per-frame poll of every open document's dirtiness |
| `onChanged()` | `DocumentModel` (SPI) | — the source the above is built from |
| `onDidChangeFocus` | `Input` | the Inspector's application-supplied subject — see below |

**`onDidChangeFocus` is the one that unlocked the Inspector.** `FocusEvent.Focus`/`Blur` are dispatched
*at the element* and bubble, so they answer "did I gain focus"; they cannot answer "who holds focus now"
for an observer that is not on the path — and a workbench has several (an inspector, a context-sensitive
toolbar, a status line). Without it those observers can only poll, or be handed a subject the application
picked, and the Inspector had the second: its subject was `workbench.activeDocument().view()`, so nothing
outside a document could be inspected however many sections were registered. Deduplicated, so the
blur-then-focus pair one click produces announces two real states and never the same one twice.

### Three rules that are easy to get wrong

**1. A connection is a `Disposable`.** `Disposer.register(owner, signal.connect(...))` and the
subscription dies with its owner. Without this every listener needs a hand-written disconnect on a
matching teardown path — the bookkeeping the ownership tree exists to remove, and what leaks when a panel
closes: the widget goes, the subscription stays, and it keeps being called about a tree it is not in.

**2. Subscribing is not catching up.** A signal only reports what happens *after* you subscribe. Anything
registered before you subscribed is invisible, forever. `StripeView.listenToPanels` syncs first and
subscribes second, because the workbench registers its own panel types in its constructor — subscribing
alone left the rail permanently empty. **This is the failure mode of every poll-to-event change.**

**3. Announce idempotently, from every mutation path.** `DockArea.announceActivePanel()` compares against
the last value it announced and is called from the press path, the focus path, the tab-selection path and
the end of a rebuild. That is the design, not a safety net: those paths overlap (a tab click activates
both a panel and its group), and the one that looks redundant is the one that matters — a tab switch
*within* a group changes no group, so `setActiveGroup`'s early return would make it the single change
that announced nothing.

> **`Signal.Value` does not suppress equal values** — `Property.set` does. Every emitter decides for
> itself whether a no-op is worth announcing, and an existing equality guard must survive the move into
> the emitter rather than being dropped as "the signal handles it".

### Events are immediate; structural rebuilds are not

Replacing a poll with an event changes *when* work runs — from "next frame" to "inside whatever mutated
the state". The engine has a hard rule against that:

> *A widget must never rebuild the elements it is being clicked or dragged on.*

So the shape is **`event → set a dirty flag → next frame rebuilds once`**, not `event → rebuild`.
`DockArea` already obeys this (`rebuildPending` + tick) and `ProjectFileTree` routes decoration changes
through `pendingRefresh`. In-place updates — a tab's title, a button's checked class — are safe
immediately; only structural changes defer.

### Re-entrancy is supported, and was not free

`SignalBase` tracks an emission **depth**, not a boolean. A nested emission's cleanup used to flush the
pending-disconnect list while an enclosing loop still held the size it cached before starting, so the
outer loop indexed past the end. Harmless while every signal was a leaf; service events chain by design
(`onDidChangeDirty` → tab title → layout), so it had to be fixed before any of them landed.

Two related behaviours, both deliberate: a listener connected *during* an emission does not receive that
emission, and `disconnectAll()` mid-emit stops delivery immediately but defers the removal.

### What stays per-frame

**Incremental work, never a poll for change.** `WorkspaceTreeSource.indexStep` walks a few directories
per frame until the workspace is mapped — there is no "change" to announce, the frame *is* the schedule.
`Workbench.tick` survives for it.

`fileTree.loadProjects()` is also still there and looks like a one-shot dressed as a loop, because
`ProjectFileTree` latches it. It is a **retry**: a client's window id is not valid until its session has
opened, and a packet sent earlier is discarded with no error. The latch is set on the *attempt*, not on
success, so calling it from `onWindowChanged` poisons it permanently — twelve explorer tests came up with
no project roots. It needs a session-opened announcement.

---

## Notifications and status

`com.crystalgui.core.notify` — `Notification`, `Notifications`, `StatusBar`.

```java
// an EVENT — happened once, may deserve an action, belongs in a history
Notifications.show(Notification.error("copy failed: " + name).withAction("Retry", again));

// AMBIENT — how things are right now. addEntry hands back the handle that OWNS the entry.
StatusBarEntryAccessor compile = StatusBar.addEntry(
        new StatusBarEntry("Shader graph compilation", "compiled 9n/8e", "996 chars", null,
                StatusBarEntry.Kind.STANDARD),
        "shadergraph.compile", StatusBarAlignment.LEFT, 100);
compile.update(compile.entry().withText("compile failed").withKind(StatusBarEntry.Kind.ERROR));
compile.dispose();   // withdrawal IS disposing the handle — there is no clear(id)
```

> **`StatusBar.set(id, text, …)` is gone.** See *Status bar entries* below for why a handle replaced it.

**Two channels, deliberately.** A notification *happened once*, may deserve an action, and belongs in a
history. A status item describes *how things are right now*, is replaced rather than accumulated, and has
no history. The shader graph produces both — a compile result is ambient (it re-arrives every frame while
a node animates), a failed copy is an event. Routed through one channel the ambient half buries the other
within seconds, which is why VS Code separates them and so does every editor that has both.

**This is what let a contribution stop being handed anything.** `ShaderGraphContribution.register` took a
`Signal.Value<String> status`, with a javadoc arguing that where a status line lives is the application's
decision. True — and the wrong conclusion: *where* a message is displayed belongs to the application,
*that* one exists does not, and a parameter for it couples every contribution to an application that has
one. It now takes only the workbench.

| Rule | Why |
|---|---|
| Status items are **keyed per writer** | `onStatus` was one slot, so the line-owner readout (every caret move) erased "created folder" milliseconds after it appeared, and neither writer could tell |
| Re-stating an unchanged item is **silent** | The compile summary is written from a recompile, and an animated graph recompiles every frame — an announcement per frame is a poll wearing a callback |
| Severity is on the notification, not in the text | "saved" and "save failed" arrived identically as Strings; a consumer cannot colour, hold or filter what it cannot distinguish |
| The history is **bounded** | It is a convenience, not a log. Its value is that a message which arrived while you were looking elsewhere is still findable |
| Nothing is displayed by default | A core that drew its own toast would be deciding application layout, and there is no window here to draw it in. `CrystalEditor` subscribes to both and flattens them into one line for the harness |

### Alignment — which end of the bar an item sits at

```java
StatusBar.addEntry(StatusBarEntry.of("Explorer", "created notes.txt"),
                   "explorer", StatusBarAlignment.LEFT);
StatusBar.addEntry(StatusBarEntry.of("Cursor position", "Ln 51, Col 39"),
                   "editor.caret", StatusBarAlignment.RIGHT, 100);
StatusBar.entries(StatusBarAlignment.RIGHT);   // what a view renders, in left-to-right order
StatusBar.allEntries();                        // what a hide menu enumerates — includes hidden ones
```

**The writer chooses, not the view.** Only the writer knows: "Ln 51, Col 39" belongs on the right because
it is *about the thing you are looking at* and is glanced at in a fixed place, while "created notes.txt"
belongs on the left because it is about what just happened and is read as prose. A view sorting by id
prefix, or guessing from the text, would be inventing an answer that already exists.

An entry may also carry a **tooltip** — `new StatusBarEntry(name, text, tooltip, command, kind)`. It is part of the entry rather than
decoration a view adds, because only the writer knows what the short text left out: a status bar is glanced
at, so `compiled 12n/9e` is the item and `996 chars, 1 varyings, 6 mapped lines` is what it left behind.
Both references explain every widget on hover for the same reason. A changed tooltip counts as a change, or
re-stating one silently keeps the old text.

Order within a group is **priority**, higher first — see *Status bar entries*. `text()` still flattens
every entry into one line and ignores alignment, because a line has no ends; it is what a log, a headless
assertion or a single-label host wants.

**Alignment is fixed at registration.** Moving an entry between ends means disposing the handle and
registering a new one, which is VS Code's arrangement too — an accessor names a place as well as an entry.
The view's removal pass therefore runs before its placement pass, or an element that changed ends would
briefly be a child of both groups and `append` throws on a second parent.

### The view is `StatusBarView`, and it renders rather than computes

`widget.chrome.StatusBarView` — the model/view split is in the names, because two classes called
`StatusBar` in two packages compile and read as a mistake forever after. `Workbench` mounts one below its
content, where the column layout alone puts it at the bottom.

| Rule | Why |
|---|---|
| It shows **only** what a writer keyed | Anything it computed itself would be a fact with no owner, and the keying would be pointless |
| Slots are updated **in place**, never rebuilt | Status items are written from per-frame paths, so a rebuild per change discards and recreates the tree continuously — invisible in any screenshot. The engine's standing "never rebuild what is being clicked on" rule |
| The removal pass runs **before** placement | An id that changed ends would otherwise be a child of both groups for an instant, and `append` throws on a second parent |
| A spacer takes the slack, not `margin-left: auto` | Auto margins *share* free space between every auto margin in the row — the trap the activity bar's groups already cost a session |
| It subscribes only while attached | `StatusBar` is static and outlives every view of it, so a view that never unsubscribed keeps itself and its elements alive for the rest of the process |
| A slot's tooltip is attached **once** and re-texted | `Tooltip.attach` adds a hover listener pair per call and `detach` leaves them inert rather than removing them, so attach/detach cycling accumulates listeners — and the compile summary rewrites its tooltip on every recompile |
| Separators are **elements**, not borders | The paint path takes `border().left` as *the* border width and strokes a uniform box, so `border-width-left` drew a rectangle around every readout instead of a rule between two. `Breadcrumbs` spells its separators the same way |
| `breadcrumbs()` is the one widget, not an item | A trail is clickable and structured. The host sets it; the view still derives nothing |

**A view publishes its own items, through `DocumentEditor.activated(boolean)`.** The workbench knows
exactly one thing no document can work out for itself — which tab is in front — and that is all it says.
`TextEditorView` answers with caret, line ending, encoding and indent; a shader graph answers with a
compile summary; an image answers with neither.

The first cut had `Workbench` writing the text readouts directly. It worked, and it does not scale: every
new document type would be another branch there, in a codebase whose direction is that a document type is a
*contribution*. Both references draw the line at the document — IntelliJ's `StatusBarWidgetFactory` is asked
per file, VS Code's extensions show and hide their own items on `onDidChangeActiveTextEditor`.

Two rules fall out. **Whatever a document sets on activation, it clears on deactivation** — a status item
describes what is true right now, so one that is published and never withdrawn sits under somebody else's
tab. And the caret is stated by the *document*, not the editor: a `TextEditor` is reusable, so three on one
page would write one key with the last to move winning, whereas a document is exactly one tab.

The breadcrumb trail stays with the workbench, and is a different kind of fact: it describes the tab's
*identity* — where the thing you are looking at lives — which is answerable even for a document with no
content to report.

Pinned by `NotifyTest` headlessly — a dedicated server that creates a folder should be able to say so — and
by `StatusBarViewTest` for the view half.

### Notifications: the model, and the two surfaces

```java
Notifications.show(Notification.error("Open failed")
        .withDetail(path.name() + " — " + failure.code())
        .withAction("Retry", () -> openFile(path)));
Notifications.clear();          // "Clear all"
Notifications.markAllRead();    // the bell, without touching the history
```

`Notification` carries a severity, a **title**, an optional **detail** body, a wall-clock `timestamp`, a
`groupId` and any number of `Action(label, Runnable)`. Two of those are worth the words:

| Field | Why it exists |
|---|---|
| `timestamp` | Stamped at **construction**, with `currentTimeMillis` — never `nanoTime`, whose origin is arbitrary and may be negative. Read at render time instead and every message is stamped with the moment you opened the panel |
| `groupId` | Carried although nothing reads it yet, because the two things it enables — per-group display settings and a "from" line — would otherwise force a model change later |

**Title and detail, not one string.** The title is what you read going down a column; the detail is what you
read when one of them stops you. A single string forces every producer to choose between a title too long to
skim and a message too short to act on.

`clear()` is separate from `resetForTesting()`, which also tears down every subscription — doing that in
production leaves the panel that invoked it deaf. And **reading is not dismissing**: opening the history
clears the bell and keeps the messages. Folding those together is how a panel you opened once quietly throws
away the thing you opened it for.

#### `NotificationsView` — the durable surface

A tool window on the **auxiliary** rail, where IntelliJ keeps it: a history is something you consult, not
something you work in. Newest first. Its unread count reaches the rail button through
`ViewContainerRegistry.setBadge`, so a tool window dragged between stripes keeps its badge with no further
wiring.

#### `NotificationBalloons` — the transient surface

Bottom-right, over the workbench content and **under** the drop overlay, so a message arriving mid-drag
cannot cover where a panel is about to land.

| Rule | Why |
|---|---|
| `INFO` fades; `WARNING` and `ERROR` stay | IntelliJ's `BALLOON` vs `STICKY_BALLOON`. A failure that removed itself while you read something else is a failure you were never told about |
| **Two caps**, one per kind | One cap over both starves whichever kind is unlucky: four routine messages evict an unread error, and reversing the preference makes three unread failures block everything after them |
| The cap counts entries **not already leaving** | `beginLeaving` only marks — the element stays mounted for its fade — so `while (live.size() > cap)` never terminates. It hung the harness on the fifth file opened in a row, and every test that built a workbench |
| Opacity is a **CSS transition** | The balloon arrives carrying `__hidden__` and it is dropped on the *next* frame. Writing the opaque value from Java would not animate: the write is itself transitionable, so the engine eases toward it and the cleanup retargets it back |
| `FADE_MS` must match the sheet | The layer waits that long before detaching; detaching early cuts the fade off. Stated in both places rather than parsed out of the cascade, where a theme change would break it silently |
| No timestamp on a balloon | A balloon *is* now. It is the one thing copied from the list that says nothing here — and it cost a third of the text column, which is what kept forcing the balloon wider while short messages still wrapped |

**One `NotificationCard` builds both.** The surfaces show the same object, and two builders would look
identical the day they were written and drift on the first change to either. The only difference is the
close button, which a balloon has because it has no "Clear all".

#### Not built, deliberately

Per-group display settings (needs a settings page; with three producers there is nothing to tune) and
progress notifications (its own framework — indeterminate state and a cancel affordance are not expressible
as a text item). Neither needs a model change to add. There is also **no de-duplication**: nothing produces
from a per-frame path today — the shader graph deliberately uses `StatusBar` for exactly that reason — and
the 100-cap bounds the damage, but it is the trap a future producer will fall into.

Pinned by `NotificationsViewTest`, including the cap's termination and the sticky split, both verified
against mutants.

---

## Resources

`com.crystalgui.fs` — `Resource`, `CgPath`. `com.crystalgui.fs.client` — `Workspace` and its facades,
`ContentProvider`, `ContentProviders`.

**A tab's input, whether or not it is a file.** A workbench opens things with no disk presence — a
generated shader, a diff, an untitled buffer — and both references model that with a *scheme* rather than
a flag (VS Code's `URI` + `IFileSystemProvider`, IntelliJ's `VirtualFile` + `VirtualFileSystem`).

```java
Resource.of(cgPath)                                   // project://, spelled as the bare path
Resource.of("untitled", "buffer-1")                   // untitled://buffer-1
Resource.derived("shader-generated", Resource.of(p))  // shader-generated://mymod.proj:fire.shadergraph
Resource.parse(text)                                  // round-trips all of the above
```

### The project scheme keeps `CgPath`'s exact text

Not negotiable: `CgPath` is written into saved documents and must round trip *"exactly and forever"*. So a
project resource is spelled as the bare path and everything else as `scheme://path`. They cannot collide,
because a project id may not contain `/` and so `://` cannot occur in the existing form — which is why
`parse` looks for the marker first. **A colon inside a path segment is still a project path.**

### Derived resources carry their origin, in the text

`shader-generated://mymod.proj:fire.shadergraph`. That relationship is what an application would otherwise
keep a map for, and because it survives `parse` it survives a saved session with nothing to keep in step.
Five graphs give five distinct generated resources — which is what makes a compiled source a *document per
graph* rather than one shared panel showing whichever is in front.

### Who answers for a scheme — `ContentProvider`

```java
workspace.registerScheme("library", new LibrarySources());   // this server's
ContentProviders.contribute("library", new LibrarySources()); // the process's
```

A provider answers three questions and only one is about bytes: `read` (the content), `symbolOf` (what
the resource *is*, which is how a tab draws its glyph) and `locate` (where a member of it is declared).
Every answer is a `Reply`, because the work can be real — reading a source archive is I/O and
decompiling a class is hundreds of milliseconds, and neither may land on a frame.

Register through `Workspace.registerScheme` when the scheme belongs to one server, and through
`ContentProviders.contribute` when it belongs to the process — a language stack registers at mod init,
long before any world is joined, so it has no workspace to be handed and `core/` may never name
`language/`. Each workspace drains the contributions into its own table, so two servers in one client
keep separate ones.

### Rules

- **A project resource's CONTENT always comes from the server**, whatever has registered the scheme.
  `Workspace.read` checks the scheme before it checks the provider table, so a provider registered for
  `project://` — which is how the author's own `.java` files get a declaration glyph — is never asked
  for their bytes.
- **Unregistered schemes are read-only.** Refusing to write something nobody claims is the safe direction.
- **`read()` must answer when the origin is gone** — empty bytes, never a throw. A derived tab outlives
  what it was derived from, and a pane can render a banner over empty but not over an exception.
- **Registration answers a `Disposable`**, so a mod that unloads takes its schemes with it.

### The document is the identity; the resource is a property of it

`Document.resource()` is what it is *currently* called, and `onDidChangeResource` is the one event a
store subscribes to — so a rename **moves** the document rather than orphaning every map keyed on its
old name. IntelliJ's `VirtualFile` is the same object after a rename and its
`VFilePropertyChangeEvent` is this signal.

That matters because a rename can come from anywhere. The server reports one as a single change
carrying both ends, `WorkspaceDocuments` retargets the document, and the workbench moves the tab **in
place** so it keeps its position and its selection. A rename reported as a deletion closes the tab
instead, which is what happens to anyone reading `path` alone.

### One store, keyed by `Resource`

`Documents` holds every open document by resource, so a project file, a decompiled class and a
generated shader source are all in it and there is one way to open any of them. Keyed by `CgPath`,
anything that was not a project file could not be in the store at all — which is what forces a second
open lane into existence.

Whether `Main.java` and `main.java` are one document is the **host's** rule, and only the server knows
it: it arrives in the protocol's greeting and is handed to `Documents.setKeyStrategy`. `Resource`
equality stays strict, exactly as VS Code keeps `URI` strict and folds in `extUri` — a key that folded
would make two genuinely different files on a case-sensitive host collide.

---

## Panes and placement

`com.crystalgui.widget.dock` — `DockInput`, `DockPane`, `DockPaneProvider`, `DockPlacement`.

### `DockInput` — the runtime form of what a tab shows

`DockPanelRef` stays the **persisted** form; `DockInput` wraps it, so the session codec is untouched
(`ref()` is what gets written). What it adds is the question a ref cannot answer without every caller
re-deriving it: which `Resource` this panel is about.

- Unparseable state degrades to a **null resource, never a throw** — this runs while a layout is built
  from a saved session, so one odd value costs that panel its content and not the whole restore.
- `matches()` is **ref equality**. A ref's identity includes its state, which is what makes two file tabs
  on different paths different panels; comparing resources alone would make two panel *types* over one
  file look like one input, and a retarget would be skipped with the pane pointed at the wrong thing.

### `DockPane` — one instance per (group, type), retargeted

VS Code's `EditorPane`, IntelliJ's `FileEditor`. `setInput` / `clearInput` / `onVisible` / `onHidden`, with
`writeViewState`/`readViewState` **keyed by the framework**. A pane that keyed its own state is the
stacked-inspector bug one level down.

> **The contract:** a pane holds no per-input state except through view state, because two tabs of one
> type in one group share the instance.

`DockPaneProvider.accepts` + `priority()` resolve the two-providers-one-input case — IntelliJ's
`FileEditorPolicy` — independent of registration order.

### How it is wired, and the trap it sidesteps

A pane is one instance per *type* while `DockGroup.content` is keyed per *panel*, so returning the pane's
view from `contentFor` would hand one element to two tabs and `rebuildStrip` would parent it twice.

**So every pane-backed panel keeps its own stable, empty host**, and only the *active* panel's host holds
the view. `rebuildStrip` is untouched, nothing is shared, and moving the view is one `setOnlyChild`.

> This was held back once on the grounds that `sync()` runs during a tab click, and a widget must never
> re-parent what it is being clicked on. Against this shape the rule does not bite: the click target is
> the `Tab` in the **strip**; what moves lives in the tab's **content**. That is precisely why per-panel
> hosts are better than moving one shared view between tabs.

**Ordering is the contract**, asserted as a sequence: write the outgoing view state → `onHidden` →
`setInput` → read the incoming state → `onVisible`. Backwards, it saves the incoming input's state over
the outgoing one's — silent, and visible only as a caret in the wrong place.

**A pane is released when its type has no panel left in the group** — `clearInput`, then dispose, once.
Not when its tab merely stops being active: that pane is still the group's and returns on the next
selection. Closing the *last* panel removes the leaf, so a departing group releases its own panes —
otherwise the one case that most needs the release is the one a per-sync prune cannot reach.

### `DockPlacement` — "where", as a request

```java
dockArea.groupOf(myElement)                          // the group I am in
DockPlacement.resolve(DockPlacement.with(me), dock)  // "next to me"
```

VS Code's `PreferredGroup`, typed: `active()`, `side(zone)`, `with(element)`, `central()`, `leaf()`.
Until placement is a value, "open this next to me" is not a request a widget can make —
`CrystalEditor.showCompiled` hand-rolled `layout().leafContaining(refFor(parse(path)))`, an application
reaching through the dock and the layout to ask what the dock knows about itself.

- **`groupOf` walks `getParent()`**, which returns the real parent regardless of how a child was added. A
  panel's content is often an shadow part of a composite, so skipping internal parents would answer
  null for exactly the widgets built properly.
- **`resolve` returning null is ordinary**: `with()` may name an element outside any dock, `side()` names
  a split that does not exist yet. Opening reads null as "make one"; asking reads it as "nowhere".
- `active()` falls back the way `activeGroup()` does, so a placement asked for before anything is clicked
  answers with the work area — the same bug class that made `Ctrl+S` silently do nothing after a restore.

`DockService.open()` is not here: opening needs the insertion logic in `Workbench.openPanel*`, and folding
that into the dock only removes duplication once panes exist.

---

## Opening things

**One opener, and it is the dock's.**

```java
workbench.open(input);                                              // central, activated
workbench.open(input, DockPlacement.with(me), DockOpenOptions.ACTIVATE);
workbench.open(input, DockPlacement.side(SPLIT_RIGHT),
               DockOpenOptions.INACTIVE.withShare(0.28f));
```

This replaced `openPanel(ref)`, `openPanelWith(sibling, ref)` and `openPanelBeside(ref, zone, share)` —
three methods that read as three operations and were one operation with two independent variables. Their
real differences were buried in their bodies: one activated what it opened, one deliberately restored the
previous selection, one set a size share. **A caller wanting "beside, without stealing focus" had no
overload and no way to ask.** That is why VS Code's is `openEditor(input, options, group)`.

`showCompiled` went from fourteen lines to one.

### Rules

- **"Already open" wins over placement**, in one place rather than in two of three overloads. Re-opening
  something on screen means "show me that one", never "make a second copy somewhere else".
- **A ref is a panel's identity**, so the same ref cannot be opened twice. Two tabs on one file means two
  panel *types* over one path, which is what the release guard checks for.
- `open` returns the leaf it landed in, so a caller acts on it rather than searching for it again.

## `UINode.setOnlyChild`

**"Show one of several things in this slot", done correctly.** Not
`clearAllChildren().append(wanted)` — that skips shadow parts *by design*, and a composite widget
routinely marks itself internal, so the obvious pair leaves the outgoing child in place and stacks the
incoming one underneath. That was "two inspectors in one tab", and it read as a paint bug.

Removing through the **matching** API is the fix rather than un-marking the child: internal is the child's
own statement about its parts, and it is right. The host was what assumed one kind of child.

---

## Tool windows, stripes and the drag between them

The service layer under the two rails. Everything here is reached from `Workbench` —
`toolWindowManager()`, `stripe(rail)`, `stripes()`, `dropOverlay()` — and nothing in it needs a
`UIDocument`, which is why the geometry halves are testable headlessly.

### Placement is `(region, side)`, and nothing else

`ToolWindowState` stores a `DockRegion` and a `RegionSide`. That pair is IntelliJ's `anchor` +
`isSplit`, and it is the **whole** of where a tool window lives — see `plan.md` §24.10, which quotes
the platform source. An anchor's two halves share one stripe, separated by a rule.

| Ask | Method |
|---|---|
| Where does it belong? | `regionOf(typeId)` / `sideOf(typeId)` |
| Where in its stripe run? | `orderOf(typeId)` |
| Who else is in that run? | `groupOf(region, side)` |
| Move it | `moveTo(typeId, region, side)` / `moveTo(typeId, region, side, index)` |
| Tell me when any of that changes | `onDidChangePlacement` |

**`groupOf` walks the registry, not the stored placements.** A tool window has no `ToolWindowState`
until something asks where it is, so a group read from the states alone omits every member nobody has
touched — and a renumber would then skip them, leaving them on `Integer.MAX_VALUE` where they sort
last for ever. That is not hypothetical: it made it impossible to drop anything *below* a button that
had never itself been moved.

**`moveTo` renumbers the whole group**, for the same reason both references do: orders are dense but
arbitrary, so "between 3 and 4" has no integer to use.

### The rail is derived; placement is primitive

`StripeRail.of(region, side)` answers which of the two rails carries a button, and `topRegion()` /
`bottomSide()` say what each rail's two groups hold. There is deliberately **no stored rail** — that
would be a fifth statement of a fact `WorkbenchRegions`, `RegionHost` and `ToolWindowState` already
make, free to disagree with all three and silently.

### The drop target is the whole workbench

`RegionDropZones` is pure arithmetic — six slots, a "no" over the editor, corners belonging to the
bottom band — and `RegionDropOverlay` is the one element that consumes it. **One listener, on the
workbench's content box, subscribed to the bubble phase**: `DragEvent.Over` is dispatched to whatever
is geometrically under the pointer, so nothing higher up is ever the target and `(false, false)` —
target-phase only — hears nothing at all.

> This is a **deliberate divergence** from IntelliJ, which uses per-stripe drop areas and halves the
> *stripe's* bounds. Ours bands the window. The "Move to …" wording is IntelliJ's around a target
> computed differently; do not read it as a port.

### Two widgets that came out of this, and are not workbench-specific

- **`DragGhost`** (`widget`) — the capsule under the cursor. `parkIn(host)` once, `follow(window,
  icon, text)` per drag. It exists because `Drag.setGhost` takes any element by design, and
  three rules are invisible in that signature: it must be in the tree before it can be promoted, it must
  be `absolute`/`none` **from Java at IMPORTANT at construction** (the first layout runs before any rule
  matches, and `UIText` latches its self-sizing there), and it must be registered per drag.
- **`InsertionMarker`** (`widget`) — the gap showing where a drop lands. `OVERLAY` floats over the
  list; `IN_FLOW` opens a real gap and everything after it shifts. `DockGroup` uses the first because its
  caret is not a sibling of the tabs; `StripeView` uses the second. It owns the two rules every reorder
  needs: the first item whose **midpoint** is past the pointer, and an index range of `[0, size]` so the
  far end stays reachable.


## Widget state that outlives the run — `SessionState`

`DockLayoutCodec` records where a panel **is**: which region, which half, how wide. It says nothing
about what is *inside* one, and deliberately — the dock does not serialize an element tree, because a
frozen DOM would restore whatever widgets happened to exist when it was written, and every panel
rebuilds its own from its model on each open.

So a widget's own geometry had nowhere to live. `SessionState` is where — a bag of `writeState`
payloads keyed by element id, held by the `UIDocument` and persisted by `WorkbenchSession` under a
`widgets` key.

```java
split.setId("run.rail-split");
split.setSessionPersistent(true);
```

That is the whole of adopting it. The Run panel is the case that found it: the divider between its
script rail and its transcript is a real preference, and without this it snapped back on every launch.

### It reuses what was already there, and the first attempt did not

> **This was a `PanelViewState` interface a tool window implemented, and that was the wrong shape.**
> The engine *already* has a way for a widget to say what it wants preserved — `writeState`/`readState`
> — and a way to name one — `setId`. A second, parallel mechanism made every panel re-implement
> persistence for widgets that could already describe themselves, and it could only ever reach a
> panel's **root**: a divider three levels down had to be hand-proxied out through the panel and back.
> `SplitView` now answers for its own weights, which `UINodeMirror` gets for free.

| Concern | Where it already lived |
|---|---|
| What a widget wants preserved | `UINode.writeState` / `readState` |
| Which widget it is | `UINode.setId` |
| When a widget appears | `UIDocument.registerElement` |
| When it goes away | `UIDocument.unregisterElement` |

The only new parts are the bag and one boolean.

### Applied on REGISTRATION, captured on UNREGISTRATION

> **`registerElement` is the one moment every element joins a window, whenever it is made — and
> "whenever" is the point.** A tool window is built the first time it is opened, and a widget inside one
> may be built later still: the Run panel's split does not exist until a script runs, which can be
> minutes after the session was restored or never at all. Anything applied once at startup misses all of
> that, silently, because the widget looks perfectly correct sitting at its default.

> **`unregisterElement` is the mirror, and skipping it loses everything on close.** Hiding a tool window
> *detaches* it, so a save afterwards walks a tree the widget has left and writes nothing — drag the Run
> panel's divider, close the panel, quit, and the width is gone. Reading it back as it leaves is also the
> last moment it can be read at all.

> **The store is installed when the session is CONSTRUCTED, not when a restore succeeds.** A first run
> has no record to restore, so an install-on-restore would leave that whole session with no store — and
> the unregister hook above then captures nothing.

### Two more rules, each learned the same way

> **An id is spent when it is applied.** Re-applying would drag a divider back to the session's position
> every time its panel was rebuilt, undoing wherever the user had since dragged it — the same rule a
> document's caret restore follows.

> **Entries nobody claimed are kept and re-emitted.** Writing only what is on screen makes every save an
> erasure for every widget not built that session: a divider survives exactly as long as the habit of
> opening its panel, and the erosion is invisible because each individual save looks correct.

### Rules for implementers

- **An id is required and must be stable across runs** — it is the only thing tying a payload to a
  widget that may not exist yet. Namespace it (`run.rail-split`), because the store is keyed across the
  whole workbench.
- **Opt in explicitly.** Persisting everything that has an id would need no flag, but an id is set for
  `querySelector` and for CSS at least as often as for identity, and silently restoring a `TextField`'s
  text because somebody named it is a surprise nobody can search for.
- **`readState` is a request, not a command.** Clamp what comes back; a record can be hand-edited or
  written by a build whose limits differed. `SplitView.setWeights` already states the shape of this —
  extra values ignored, missing ones left alone — and a restore that threw because a pane count drifted
  would take the whole arrangement with it.
- **State, not a model.** A divider is view state; a filter is not. A remembered filter naming a script
  that is not running again opens a console empty for a reason three clicks away. Same
  document-versus-view boundary the undo stack draws.
- **No version bump for adopting it.** The `widgets` key is additive and every read tolerates its
  absence; bumping discards every existing arrangement.

## Contributions

**A feature declares what it can do; nothing enumerates features.** This is the principle the six earlier
steps kept running into and the two below finally apply — IntelliJ's extension points, VS Code's
`contributes` manifest. The test of it is mechanical: **`com.crystalgui.editor` imports exactly one name
from `com.crystalgui.graph`**, the contribution it enables.

### `DocumentKind` — which editor opens which file

```java
workbench.contribute(DocumentKind.of("crystalshader:graph", "Shader Graph")
        .files(DocumentKind.FilePatterns.extension("shadergraph"))
        .icon("crystalshader:graph")
        .model((resource, bytes) -> GraphModel.decode(bytes))   // what it IS
        .editor(GraphView::new)                                 // one way of looking at it
        .status(GraphStatus::contribute),                       // while it is in front
        "shadergraph");                                         // and the panel binding
```

Declared by the package that owns the type, not by the application. **One call**, because a factory
with no binding never opens anything and a binding with no factory fails at the moment somebody opens a
file — two calls that must both happen are one fact, and `contribute` refuses an incomplete kind on the
spot.

**The model and the view are declared separately**, and that split is what the rest of the layer rests
on: a model knows its content and nothing about paths, tabs, saving or windows, so a document analyses
with no tab open and two split panes share one parse tree. `.editor` is optional — a kind that can be
opened, analysed and saved with nothing to look at it is what a build artefact is.

At most one kind may call `.fallback()`, and that is the "File" kind: every text file nothing else
claims, plus every resource in a registered scheme. Without it, opening an unrecognised extension
answers "nothing knows how to open this", which is right for a graph format nobody registered and
wrong for a `.txt`.

### `Inspector` — one inspector, any subject

```java
InspectorRegistry.register(new InspectorSection() {
    public String tab()                             { return "Node"; }
    public boolean accepts(DataContext ctx)         { return ctx.has(SHADER_GRAPH); }   // Blender's poll()
    public void build(InspectorForm form, DataContext ctx) { form.row(descriptor, value); }
    public String subjectKey(DataContext ctx)       { return "node:" + …; }             // identity, for dedup
});
```

There was a `ShaderGraphInspector` — a general tool with a graph in its name, its constructor and its
fields. It is deleted; the shader package registers five sections instead, and `Inspector` knows nothing.

**A section fills a form; it does not return a widget.** Two sections sharing a tab write into one panel,
which is what sharing a tab should look like — returning an element each stacks two independently
scrolling panels with two sets of group headers and a visible seam. It also keeps the engine owning the
engine-shaped parts: the panel, its scrolling, its group collapse state, and when to clear it.

### `DockBannerProvider` — why this tab is not an ordinary one

```java
DockBanners.register(panel -> SOURCE_TYPE.equals(panel.typeId())
        ? Notification.info("Generated from the shader graph. Edit the graph, not this file.")
                .withAction("Open Graph", () -> workbench.openFile(origin))
        : null);
```

IntelliJ's `EditorNotificationProvider`. Facts about a tab that the tab cannot show itself — *generated*,
*read-only*, *out of date*. The motivating case: `compiled_graph.shader` is `setReadOnly(true)`, so typing
in it silently does nothing, which reads as a **broken editor** rather than as a generated file.

- **Asked with the `DockPanelRef`, not a document** — deliberately. The generated source tab is not a
  document of its own; it is a panel type whose ref carries the derived `Resource` in its state. A
  document-shaped question could not have been asked about the one tab that needed it.
- **Wrapping happens in `DockGroup.contentFor`**, the single place every panel passes through — document
  tabs, pane-backed panels and plain registry-built ones alike.
- **Nothing is wrapped when nothing answered**, which is nearly always. A wrapper column per panel would
  add a flex level between a pane and its content on *every* tab to serve the rare one. Pinned by
  `DockBannerTest`, including the case where a provider is registered but declines.
- Severity is a class (`__info__` / `__warning__` / `__error__`), never a colour in Java.
- **Every provider that answers gets a strip**, not the first — a file that is both read-only and
  generated has two things to say.

#### The subject is the focus owner, and the engine resolves it

A contributor declares *what* it can describe and *how to read it*. Everything else is the engine's:

| Boilerplate | Who does it |
|---|---|
| Deciding what is being inspected | `Inspector`, from `Input.onDidChangeFocus` |
| Ignoring focus that lands **inside the inspector** | `Inspector` — asking to see something must not change what is shown |
| Latching, so losing focus does not blank the panel | `Inspector` |
| Keeping the last subject when **nothing can describe** the new one | `Inspector` |
| Deferring the rebuild off the event that caused it | `Inspector` (a frame later — never rebuild what is being clicked) |
| Skipping the rebuild when the subject has not moved | `Inspector`, via `subjectKey` |
| Releasing a replaced panel's subscriptions | `ConfiguratorPanel.clearRows()` |
| Announcing that a **selection** moved | the widget that owns it — `GraphSelection` does it itself |

> **Nothing outside a document could be inspected** until this moved. The application resolved the
> subject as `workbench.activeDocument().view()`, so a section describing a file-tree row or a timeline
> key could register successfully and never once be asked. What remains in `CrystalEditor` is a *seed*:
> a restored tab exists before anything has been focused, so the workbench states what it just opened and
> focus supersedes it the moment there is one.

> **A subject nothing can describe is not a subject.** Focusing a `.txt` beside a graph does not blank the
> panel — no section accepted, so the last describable subject stays. Blender behaves identically and does
> not treat it as a case: the Properties editor reads the scene's *active object*, which moving into the
> Text Editor cannot change because that editor never contributed to it. IntelliJ lets a component that
> provides no data fall through rather than answer null for everyone.
>
> **Most subjects are never describable, and that is permanent.** An inspector is for structured,
> non-linear data whose editing surface genuinely is a property list — a graph node, a canvas item, a
> mesh, a scene object. A text buffer is edited in place, and the metadata it has (encoding, line endings,
> language, indent) belongs in a **status bar**: VS Code puts all four there, clickable, and IntelliJ has
> no inspector at all. Do not give a document a section to stop the panel looking empty — the retention
> rule is the answer, and it is the steady state rather than a stopgap.

**Blender's Properties editor is the reference**, and it works for a mesh, a light, a camera, a material
and an add-on's own datablock through three mechanisms:

| Blender | Ours |
|---|---|
| a `Panel` declares `bl_context` (tab) and `poll(context)` | `InspectorSection.tab()` and `accepts(DataContext)` |
| the subject comes from `context.object` etc., never from the editor | `DataContext` |
| `layout.prop(data, "x")` draws **reflectively** from the declared property | `ConfigDescriptor` / `SettingsConfigurator` |

> **The third is doing most of the work and is the easiest to miss.** With contributions but no
> reflection you still hand-write a form per type — you have only moved where it lives. A section should
> build from descriptors, not from hand-placed widgets.

### Rules

- **Tabs come from the sections that applied**, never a fixed list. A hardcoded pair is the old per-type
  design wearing a registry.
- **A section holds nothing.** It is handed the context and asked to build; one that caches its subject is
  a per-type inspector again, with the lifetime bug that made the old per-graph map retain every graph.
- **Ordering is declared** (`order()`), so two features cannot interleave by class-loading order.
- **Nothing inspectable is an empty *state*** (`__inspector-empty__`), not a framed panel with nothing in
  it — Blender hides a panel outright when its poll fails.
- **Rebuild, do not retarget.** There is no `setInput` here: a section holds nothing, so pointing the
  inspector elsewhere is one rebuild. The retarget protocol `DockPane` needs exists because a *pane* holds
  per-input state.
- **What counts as "the subject" is the application's policy.** `CrystalEditor` latches the active
  document, because the gesture that opens the Inspector makes the Inspector's own group active — the same
  reason Blender reads the scene's *active* object rather than focus.

---

## Status bar entries

`com.crystalgui.core.notify` — `StatusBar`, `StatusBarEntry`, `StatusBarEntryAccessor`,
`StatusBarAlignment`. Ported from VS Code's `IStatusbarService`.

```java
StatusBarEntryAccessor caret = StatusBar.addEntry(
        new StatusBarEntry("Cursor position", "51:39", "Line and column of the caret",
                           "editor.gotoLine", StatusBarEntry.Kind.STANDARD),
        "editor.caret", StatusBarAlignment.RIGHT, 100);
```

| Rule | Why |
|---|---|
| **`addEntry` returns a handle; the handle is the identity and the lifetime** | A string key made withdrawal etiquette a writer had to remember, and made two writers on one id a silent collision — the same failure `onStatus` had, narrowed from one slot for everyone to one slot per string. Two registrations are two entries whatever they are called. Register it on a `Disposer` and it dies with its owner |
| **Higher priority is further LEFT, in BOTH groups** | Not "closer to the outer edge", which sounds right and gets the right-hand group backwards. VS Code's own are selection 100, indentation 99, encoding 98, eol 97 |
| **`name` is what the entry IS; `text` is what it shows** | A hide menu lists entries by `name` — you cannot offer "hide 51:39" as a checkbox, and the text changes on every keystroke. The split looks redundant until something enumerates the bar |
| **`command` is a command ID, never a callback** | Keeps a clickable readout reachable from the palette and a keymap too. The view runs it as `CommandRegistry.global().run(id, CommandContext.of(element))` — **contextless `run(id)` builds an EMPTY DataContext**, so any command with `enabledWhereData` fails its guard and silently does nothing |
| **`update` is silent when the entry is equal** | The record's own `equals`, not a hand-written field comparison — which is how adding a field to `StatusBarEntry` would otherwise silently skip the guard. The caret readout writes on every selection change |
| **`onDidChange` carries nothing** | It carried the composed line, so `text()` walked every entry on every write whether or not anything wanted a string |
| **`setHidden(id, …)` hides; `allEntries()` still lists it** | Or there is no way to switch it back on. `entries(alignment)` is what a bar renders; `allEntries()` is what a menu enumerates |

## The notification model

`Notifications` is one observable collection emitting typed changes — VS Code's `NotificationsModel`.

```java
Notifications.onDidChange.connect(event -> {
    switch (event.kind()) {
        case ADDED   -> add(event.notification());
        case CHANGED -> restate(event.notification());   // a repeat, or a handle's updateMessage
        case REMOVED -> remove(event.notification());    // aged out, or withdrawn
        case CLEARED -> rebuild();                       // one event, not N removals
    }
});
NotificationHandle handle = Notifications.show(Notification.error("Disconnected"));
handle.updateMessage("Reconnecting");   // arrives as CHANGED — not a new event, does not re-ring the bell
handle.close();                          // arrives as REMOVED
```

| Rule | Why |
|---|---|
| **A view must handle REMOVED** | The history is bounded, so entries age out. With no REMOVED kind an eviction was unannouncable and the panel's column grew past the history it was showing. **Ask of any new list-shaped model: can it express every way an entry can leave?** |
| **CLEARED is one event** | A view rebuilds an empty column once rather than splicing a hundred rows out individually |
| **A group decides how loud a notification is** | `NotificationGroups.displayOf(groupId)` → BALLOON / STICKY_BALLOON / LOG_ONLY / NONE, user-overridable via `setDisplay`. IntelliJ's model. An **unregistered group gets BALLOON**, because a producer that forgot to register must not go silent |
| **Never-show-again is keyed by a message KIND** | `withNeverShowAgain(id)`; the instance is gone the moment it fades, so a flag on it would suppress nothing. Deliberately not the group — silencing one warning and silencing a whole producer are different requests |
| **A repeat is CHANGED, and does not move the card** | Collapsing exists so a repeated message stops burying the list; re-ordering on every repeat would undo half of that |

## Diagnostics — owners and resources

`com.crystalgui.text.diagnostic` — `Diagnostic`, `DiagnosticSet`, `Markers`, `DiagnosticTag`,
`RelatedInformation`. Ported from VS Code's `IMarkerService`, whose key is `(owner, resource)`.

```java
// OWNER: one document, several independent producers. Each replaces only itself.
set.changeAll(Map.of("shadergraph", emitterProblems,
                     "glsl",        driverProblems,
                     "preview",     previewProblems));   // announces ONCE

// RESOURCE: the workspace index. One per workspace, owned by the Workbench.
markers.attach(document.resource(), document.diagnostics());
markers.count(DiagnosticSeverity.ERROR);
markers.resourcesWithProblems();
markers.detach(resource);   // closing a document
```

| Rule | Why |
|---|---|
| **A producer names itself** | A flat list means the last writer wins — the failure `onStatus` had, in a different package. `ShaderGraphEditor` has four producers and had to merge them by hand because the model could not hold them apart |
| **`changeAll` for a producer that writes several owners** | Otherwise a bound Problems panel rebuilds once per owner for one compile, and an owner left unmentioned keeps last compile's errors beside this one's |
| **`Markers` is an INSTANCE, never static** | It holds a listener on every set it indexes, so nothing indexed can be collected. As a global that is forever: it killed the test worker with a non-zero exit and no failing assertion. VS Code injects its marker service per window |
| **`detach` on close is the half that leaks** | A closed file's problems are not the workspace's, and the listener keeps the document alive |
| **`setAll` writes the DEFAULT owner's slice** | Not all of them. Kept because forcing every single-producer document to name an owner makes it invent one, scattering keys that never collide and never merge |
| **Tags change how a diagnostic is DRAWN, not how bad it is** | UNNECESSARY fades, DEPRECATED strikes through, and both keep their severity |

## Language services

`com.crystalgui.text.lang` — `LanguageServices` and the three contracts it bundles
(`SemanticTokenProvider`, `Resolver`, `CompletionProvider`), plus the value types they speak
(`SymbolInfo`, `SymbolKind`, `SymbolModifier`, `TypeRef`, `DeclarationSite`, `CompletionItem`,
`CompletionList`, `Versioned`). **Interfaces only** — every engine lives in `language/`.

```java
// The workbench builds one per DOCUMENT, from the same registry entry that answers "what language".
LanguageRegistry.Entry entry = LanguageRegistry.forFileName(path.name());
editor.setTokenizer(entry.newTokenizer());
editor.setLanguageServices(entry.newServices(editor.buffer(), resource));   // null when no engine

// An engine publishes diagnostics into the document's existing set, under its own id.
document.diagnostics().changeOne(services.id(), compiled.problems());
```

| Rule | Why |
|---|---|
| **Absence is the feature flag, and there is no other one** | Three tiers degrade independently and each absence is silent: no engine → grammar colouring, no grammar → keyword lexer, neither → plain text. A `enableSemanticHighlighting` boolean would be a second source of truth about what is actually loaded, and the two disagree the moment a native fails to load |
| **Per DOCUMENT, never per editor** | The same file in two split panes is one document. Two sets would double every compile, publish two competing diagnostic slices into one `DiagnosticSet`, and disagree about which version they had reached |
| **`LanguageServices.close()` is the ONLY close on the seam** | `SemanticTokenProvider` has one too and nothing outside an implementation may call it — an editor closing a provider releases something it was only lent, while the document's other view carries on using it |
| **The document owns them — `TextDocumentModel.dispose()`** | Not the widget. The dock rebuilds every panel on every split and drag, so releasing on widget teardown frees a parse tree for a document that is still open and rebuilds it next frame. **This is also what finally calls `SyntaxTokenizer.close()`**: that method has existed since the seam did and nothing in the application ever reached it, so every text document's native parse tree survived until the process ended |
| **`setLanguageServices` unsubscribes, it does not close** | Same reason — the editor holds, the document owns |
| **Diagnostics are NOT on this interface** | They already have a home with a per-owner model built for exactly this. `services.id()` is the owner key; mirroring the list here would be two copies with no rule about which is authoritative |
| **Every answer carries the document version it describes** | `Versioned<T>`. The consumer picks the staleness policy, because there are three correct ones and they are not interchangeable: **discard** for hover and go-to-definition, **keep adjusted** for diagnostics, **keep per line** for semantic tokens — dropping those on every keystroke flickers the file back to lexer colouring and restores it 300ms later |
| **Two async shapes, and the split is not arbitrary** | Continuous background analysis **pushes** with an invalidation range (`SemanticTokenProvider`, mirroring `SyntaxTokenizer`); a user-initiated question **requests** with a callback that may never fire (`Resolver`, `CompletionProvider`). LSP splits them the same way — `publishDiagnostics` is a notification, `hover` is a request |
| **Semantic tokens speak the grammars' capture vocabulary** | `SymbolKind.captureName()` is the bridge. A parallel vocabulary would need its own scheme tokens, its own governance test and a mapping table nobody keeps current. `StyleGovernanceTest.everySymbolKindNamesACaptureTheSheetColours` pins it |
| **An engine's colouring REPLACES the grammar's where they overlap** | Merged into one per-row bucket in `TextEditor.ensureRowSyntax`, not layered. Two overlapping ranges under unrelated names leave the winner to paint order — and both names resolve to real colours, so the wrong one reads as a scheme bug |

## Contributing to a view's header

`HeaderContributor` — a view offers controls; the container decides placement.

```java
public class ProblemsPanel extends UINode implements HeaderContributor {
    @Override public UINode headerContent() { return tabs; }   // asked ONCE, when mounted
}
```

IntelliJ's tool-window title actions. A view cannot reach its container and should not: it does not know
whether it is alone in one, sharing it, or in a container at all. `ViewContainer` places the element after
the title, and **only for a lone view** — with two sharing a container the header names the container, and
one view's controls beside it would look like they governed both. The element must be one the view *owns*
rather than built per call, or the container ends up holding a previous one.

---

## Menus, and the menu bar

**A menu is a query, not a list.** `MenuId` names a place a menu is drawn; a command declares that it
belongs there; a renderer asks the registry what is there *right now*. Nothing enumerates menu items —
which is what lets `com.crystalgui.widget.graph` own the entire Graph menu without the shell
importing it, and what makes `MainMenuCommands` 70 lines that declare two commands.

### Putting an item in a menu

One line, on the command itself, at the place it is already registered:

```java
registry.register(Command.of("edit.save", "Save File")
        .binding("Mod+S")
        .menu(MenuId.MAIN_FILE, "3_save", 10)          // ← the whole of "add it to the File menu"
        .enabledWhen(...)
        .run(...));
```

| Argument | Meaning |
|---|---|
| `MenuId` | Where. The main-menu ids are `MAIN_FILE`, `MAIN_EDIT`, `MAIN_VIEW`, `MAIN_GRAPH`, `MAIN_WINDOW`, `MAIN_HELP`, plus the nested `MAIN_FILE_NEW`, `MAIN_FILE_RECENT`, `MAIN_VIEW_TOOLWINDOWS` |
| `group` | The **section**, and the sort key. VS Code's `N_name` convention: `1_new`, `2_open`. **Separators are drawn between groups and never declared** — so adding to an existing section cannot produce a stray rule, and starting a new one cannot fail to |
| `order` | Position within the section |

A command may declare **several** placements — `explorer.newFile` is in both `EXPLORER_NEW` and
`MAIN_FILE_NEW`, because the difference between them is already inside its own `enabledWhen`.

### Toggles

A checkmark is a fact about the application, so the **command** states it. The row is built by a menu
that has never heard of soft wrap:

```java
Command.of("editor.toggleSoftWrap", "Toggle Soft Wrap")
        .menu(MenuId.MAIN_VIEW, "3_editor", 40)
        .toggledWhen(when(TextEditor::isSoftWrap))     // or toggledWhereData(...) over the DataContext
        .run(...)
```

Read when the menu is built, never stored. There is no `setToggled`: the command *reports* the state, it
does not own it. `isCheckable()` (is there a mark column at all) is deliberately distinct from
`isToggled()` (which way it points) — an unchecked toggle still reserves its column, which is what stops
a menu's labels shifting sideways as its toggles change.

### Computed rows — `MenuContributor`

For a list whose **length is not known until the menu opens**: the open editors, a Recent Files list, one
row per registered tool window. There is no id to register a command against, because the thing being
offered is the *list*. IntelliJ's `ActionGroup.getChildren`, computed per invocation.

```java
registry.contributeMenu(MenuId.MAIN_WINDOW, (menu, context) -> {
    Workbench workbench = context.data().get(Workbench.WORKBENCH);
    if (workbench == null) return List.of();
    List<MenuEntry> rows = new ArrayList<>();
    int order = 0;
    for (DockPanelRef panel : workbench.dock().allPanels()) {
        rows.add(new MenuEntry.Item(
                Command.of("workbench.editor." + …, workbench.panels().titleOf(panel))
                        .run(() -> workbench.dock().activatePanel(panel)),
                "9_editors", order += 10,
                /* enabled */ true, /* checkable */ true, panel == active));
    }
    return rows;
});
```

- **The command need not be registered anywhere.** `MenuBuilder` runs the held command when the registry
  does not know the id, which is what lets "open this specific recent file" exist without one palette
  entry per file.
- **Called on every open.** That is what "computed" means; keep it cheap. Reading a list somebody else
  maintains is the intended cost, going to disk is not.
- Keyed per menu, so opening File does not ask the Window menu's contributor anything.

### Reading a menu — `CommandRegistry.sections`

```java
List<MenuSection> sections = registry.sections(MenuId.MAIN_FILE, CommandContext.of(source));
```

Returns the rows **grouped**, in group-then-order, each `MenuEntry.Item` carrying `enabled`, `checkable`
and `checked`, with `MenuEntry.Submenu` listed but **not expanded** (expanding would resolve the whole
tree to draw one row).

> **Disabled rows are included, marked.** The registry states the answer and does not act on it. Both
> current renderers dim rather than hide — see `MenuBuilder`'s class note for why, and for the 1-of-9
> palette this repo already paid for. `CommandRegistry.menu()` is the deprecated flat, filtered view.

### Rendering — `MenuBuilder`

**Never build menu rows against `Menu` directly.** Six rules live here, each learned from a bug:
separators between sections but never leading, trailing or doubled; an unregistered command still gets a
row (disabled); enablement re-checked at activation; the command re-resolved through the registry when it
runs; accelerators read live from the keymap; an empty submenu dropped but a disabled one kept.

```java
Menu menu = MenuBuilder.build(MenuId.MAIN_FILE, registry, source);   // a whole menu
MenuBuilder.appendSections(menu, id, registry, source);              // splice into one you own
MenuBuilder.row(menu, registry, source, "edit.save", null);          // one named row

List<Menu> live = MenuBuilder.present(menu, attachmentSite, window); // attach the whole chain
MenuBuilder.discard(live);                                           // close, then detach
```

`ContextMenu` is a caller of this, not a parallel implementation. If you are about to write a second one,
that is the thing the plan warned about: two builders disagree about separators and greying within a
release.

### The bar — `MenuBarView`

```java
MenuBarView bar = new MenuBarView(CommandRegistry.global());
MainMenuCommands.install(bar);      // the six standard titles, or call addMenu yourself
workbench.appendStructural(bar);    // above content; the workbench is a column
```

| | |
|---|---|
| `addMenu(MenuId, "&File")` | `&` marks the mnemonic and is stripped; `&&` is a literal ampersand |
| `open(id)` / `close()` / `openMenu()` | The bar owns **which** menu is open, because hover-switching is a fact about the bar and no title could decide it alone |
| `onDidChangeOpenMenu` | Fires with the open id, or null |
| Alt+letter | Opens from anywhere — a capture-phase listener on the window root, since a bar is never focused |
| Alt held | Underlines the mnemonics, via `::highlight(mnemonic)` |

Commands resolve against the **focused** element, not the bar — File ▸ Save saves the active editor. That
is the opposite of `ContextMenu`'s rule and for the opposite reason: a right-click names its subject, a
menu bar does not.

> **Styling a title's text**: the size must go on the `text` element, not on `.__menu-title__`.
> `* { font-size: 10 }` in `default.css` gives every element a candidate, so font-size does not inherit
> anywhere in this engine. See the invariant table in `AGENTS.md`.

---

## Cut, copy and paste — one action, many providers

**Never add a fourth `cut` command.** `Edit ▸ Cut/Copy/Paste` are single rows that ask the *position* what
they mean, so a widget joins in by answering one data key:

```java
private final ClipboardActions clipboardActions = new ClipboardActions() {
    public boolean canCut()   { return hasSelection() && !isReadOnly(); }
    public void    cut()      { … }
    public boolean canCopy()  { return hasSelection(); }
    public void    copy()     { … }
    public boolean canPaste() { return … }        // whichever clipboard YOU mean
    public void    paste()    { … }
};

@Override
public Object getData(DataKey<?> key) {
    if (key == UiDataKeys.CLIPBOARD) return clipboardActions;
    return super.getData(key);
}
```

IntelliJ's `CutProvider`/`CopyProvider`/`PasteProvider`, reached through the walk that already exists —
innermost answer wins, so the widget you are in is the widget that decides.

| Rule | Why |
|---|---|
| The specific commands stay | `editor.cut`, `explorer.cut`, `graph.cut` keep their own element-scoped bindings and their palette rows. What changes is that the **menu** stops naming one of them |
| `canPaste()` is not "is the clipboard non-empty" | A file tree cannot paste text and an editor cannot paste files. Only the provider knows which clipboard it means |
| No defaults on the interface | Six abstract methods. A provider silently inheriting "cannot paste" is indistinguishable from one that considered paste and refused |
| Enablement is re-asked at activation | The menu may have been open while the selection changed — the same rule `MenuBuilder` follows for every row |

## The Project explorer

### Inline editing

```java
tree.beginRename(path, name -> files().move(path, path.parent().resolve(name), false));
tree.beginNew(parentFolder, /* directory */ false, name -> files().create(…));
```

An input **in the row** — VS Code's `FilesRenderer.renderInputBox`. `InputDialog` survives only as the
fallback for a host with no tree on screen (New File from the palette with the explorer closed).

| Rule | Why |
|---|---|
| The editor is built in `createTemplate`, never in `bind` | The edit begins from a key press *on the row*; building the field then rebuilds the element that press is being dispatched through |
| Blur **commits** | Cancelling on blur throws away a name you have finished typing — the more expensive of the two mistakes. Escape cancels |
| An invalid name **cancels**, never commits | Committing a name the validator refused would overwrite; trapping the user in a row they cannot leave is the only other option |
| The **stem** is selected, not the whole name | F2 everywhere. The extension is almost never what is being changed, and selecting it means the first keystroke destroys it |
| `beginNew` expands the folder first | Otherwise the placeholder is a child of a folded folder and nothing appears — which reads as New File doing nothing |

### Compact folders

`WorkspaceTreeSource.setCompactFolders(boolean)`, **on by default** as in VS Code.

- `rowLabel(path)` is the one question a renderer asks: a project's name, a plain name, or the whole chain
  a compacted row stands for. The view cannot derive the last — by the time a row exists, the swallowed
  directories are not in the tree.
- `visibleRowFor(path)` maps any path to the row standing for it. **Anything mapping a path back to the
  tree must use it** — reveal, auto-reveal, a problem row jumping to a file — or it expands a directory
  that is no longer a row and silently does nothing.
- A chain never crosses a **root**, and stops at the first directory whose listing has not arrived.

### Find

`WorkspaceTreeSource.FindMode.HIGHLIGHT` (default) keeps every row and marks matches;
`FILTER` removes non-matching rows. `isMatch(path)` and `descendantMatches(directory)` are what a renderer
reads. Both counts are over what has been **listed** — a lazily-loaded tree cannot answer for a folder
nobody has opened without fetching the project.

> Highlight is the default because a filter with nothing on screen saying it is on is a tree that has
> mysteriously lost half its files. The find bar exists for the same reason and is the actual feature.

### File operations

`ExplorerCommands` resolves conflicts rather than writing through them: a **copy** onto a taken name gets
`FileOperations.incrementalName`, and a **move** onto one is refused with the name in the message.
Every path out of a paste iteration must call its `batch.track()` runnable, including the refusals — a
`continue` that skips it leaves the undo group open forever.
