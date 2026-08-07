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
| `OpenDocuments.close` | the document it drops | only reached today when a file is **deleted or moved**; closing a *tab* does not come through here yet |
| `CgUiLifecycle` | the GL gate and its queue | not an owner — the seam |

### What disposes, and what deliberately does not

| Action | Disposes? | Why |
|---|---|---|
| **Close a tab** | **yes**, when it was the last panel showing that path | `DockArea.onDidClosePanel` → `Workbench` releases the document and emits `onDidCloseDocument`. Guarded on "is anything else still showing this", because one document can have several panels — releasing on the first close leaves the surviving tab drawing something torn down, which is worse than the leak because it fails while looking fine. Derived resources are skipped: releasing a graph because its generated-source tab closed would take the document with it |
| **Rename / move a file** | **no** | Goes through `OpenDocuments.retarget`, not `close`. It is the same document at a new address; disposing and rebuilding would discard unsaved work and churn GL for a path change |
| **Delete a file** | **yes** | `WorkspaceFileService` closes what was open under it. The document is genuinely dead |
| **Close the editor** | yes | `Disposer.dispose(editor)` — the root of the tree |
| **Context destroyed** | yes | `CgGraphicsLifecycle`, which is where pooled GL objects are supposed to die |

### Known gap

**Closing a tab still does not release its document.** `DockArea.closePanel` tells the content nothing,
and `Workbench`'s `close(path)` is only reached on delete/move. The dock cannot fix this alone: panel
content is *shared* — the registry hands back the same `fileTree`, `problems` and cached editors — so
disposing on close would destroy the file tree the first time somebody closes the Project panel.

The missing half is the dock announcing a close so the workbench can drop the document. That is
`plan.md` step 3. Do not work around it locally.

---

## Data context

`com.crystalgui.core.data` — `DataKey`, `DataProvider`, `DataContext`. Standard keys in
`com.crystalgui.ui.UiDataKeys`.

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
| `UndoCommands`, `GraphCommands`, `DockCommands`, `EditorCommands`, `ShaderGraphEditor`, `BlackboardPanel` | `ExplorerCommands` (`Workbench`), `CrystalEditorCommands` (`CrystalEditor`, `UIWindow`), `ChromeCommands` (`UIWindow`) |

The three on the right migrate once there are data keys for a workbench, an editor and a window — the
same move that turned `GraphCommands.graphFor` from an `instanceof` walk into a key.

### A widget's commands arrive with the widget — two hooks on `UIElement`

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

Both run from `UIElement`'s constructor, so **subclass fields do not exist yet** — the classic Java
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
| `CrystalEditorCommands(CrystalEditor, UIWindow)` | `CrystalEditor.CRYSTAL_EDITOR` + `UiDataKeys.WINDOW` |
| `ChromeCommands(UIWindow)` | `UiDataKeys.WINDOW` |

This is strictly better than capturing, not merely equivalent: with two windows open the palette now opens
in the one you pressed the key in, and Save Layout saves the right one. The captured version could not
have done either.

**It also deleted a polling loop.** `Workbench.tick()` called `installExplorerCommands(window)` *every
frame* behind a flag, for one reason — registration needed a window to reach a registry. Same for
`TextEditor`, which installed from `updateWindow()`, i.e. the layout path.

### `onWindowChanged`, and the window-level provider

Two things fell out of that work.

`UIElement.onWindowChanged(previous, current)` is the hook whose absence caused those polls. Anything an
element must do *once it has a window* goes here. Both arguments are given because detach is the half that
leaks.

`UIWindow.addDataProvider(...)` is IntelliJ's frame-level `DataProvider`, consulted by `DataContext`
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
| `onDidChangeDirty` | `OpenDocuments` | `encode()` on every open document, every frame |
| `onDidChange(Runnable)` | `FileDocument` (SPI) | — the source the above is built from |

### Three rules that are easy to get wrong

**1. A connection is a `Disposable`.** `Disposer.register(owner, signal.connect(...))` and the
subscription dies with its owner. Without this every listener needs a hand-written disconnect on a
matching teardown path — the bookkeeping the ownership tree exists to remove, and what leaks when a panel
closes: the widget goes, the subscription stays, and it keeps being called about a tree it is not in.

**2. Subscribing is not catching up.** A signal only reports what happens *after* you subscribe. Anything
registered before you subscribed is invisible, forever. `ActivityBar.listenToPanels` syncs first and
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

## Resources

`com.crystalgui.fs` — `Resource`, `ResourceContentProvider`, `ResourceRegistry`.

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

### Rules

- **The project scheme refuses a provider.** It is read through the workspace client, which needs a
  session and a round trip; a provider is a synchronous byte-returning method reached from a paint path.
- **Unregistered schemes are read-only.** Refusing to write something nobody claims is the safe direction.
- **`read()` must answer when the origin is gone** — empty bytes, never a throw. A derived tab outlives
  what it was derived from, and a pane can render a banner over empty but not over an exception.
- Registration is explicit and global, for the same reasons commands are.

### `FileDocument.resource()` — and what it deleted

A document says what it *is* (IntelliJ's `FileEditor.getFile()`, VS Code's `EditorInput.resource`).
Without it, going from a document back to its address needs a map maintained beside the document store —
`CrystalEditor` had `Map<String, ShaderGraphEditor>` plus a **reverse linear scan** to answer "which graph
is this". Gone, with `graphForPath` and `pathOf`.

The generated shader's tab input is now the derived resource itself, so a restored session resolves by
**parsing its own input** — nothing has to have been rebuilt first, which the map could not promise.

### `OpenDocuments` stays keyed by `CgPath`, deliberately

It is the *disk* store: `onDisk` bytes, `unreadable`, `requested`, `markSaved`. A derived resource has no
disk presence, so re-keying would give every entry fields half of them can never use — the flag-shaped
design schemes exist to replace. **A derived document needs a provider and a view, not a slot in the file
store.**

No session version bump either. That tab's state used to be the graph's bare path, which parses as a
project resource with no origin; reading it as the origin itself is one line, and the forms are
unambiguous — a derived resource always has an origin, a bare path never does.

---

## Panes and placement

`com.crystalgui.ui.elements.dock` — `DockInput`, `DockPane`, `DockPaneProvider`, `DockPlacement`.

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
  panel's content is often an internal child of a composite, so skipping internal parents would answer
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

## `UIElement.setOnlyChild`

**"Show one of several things in this slot", done correctly.** Not
`clearAllChildren().addChild(wanted)` — that skips internal children *by design*, and a composite widget
routinely marks itself internal, so the obvious pair leaves the outgoing child in place and stacks the
incoming one underneath. That was "two inspectors in one tab", and it read as a paint bug.

Removing through the **matching** API is the fix rather than un-marking the child: internal is the child's
own statement about its parts, and it is right. The host was what assumed one kind of child.
