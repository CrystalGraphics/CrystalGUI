# CrystalGUI — Workbench services

The services under the dock, the workbench and the editor: what a widget may *ask* rather than reach through the
application for. **Read before adding anything to `dock/`, `workbench/` or `editor/`**: most of what looks like a
missing feature there is a service that already exists.

> **Every new service API lands in this index in the same commit that introduces it.**

## Status

| Service | State | Section |
|---|---|---|
| `Disposer` — ownership and lifetime | shipped | [Disposal](#disposal) |
| `DataContext` — "what am I acting on" | shipped | [Data context](#data-context) |
| Typed service events | shipped | [Service events](#service-events) |
| `Resource` — URI schemes, virtual documents | shipped | [Resources](#resources) |
| `DockPane` — retargetable panel views | shipped | [Panes and placement](#panes-and-placement) |
| `Workbench.open(input, placement)` | shipped | [Opening things](#opening-things) |
| `DocumentKind` — a file type, declared by its owner | shipped | [Contributions](#contributions) |
| `Workspace` — the server's filesystem, from the client | shipped | [Resources](#resources) |
| `EditorService` — one lane for opening anything | shipped | [Opening things](#opening-things) |
| `Inspector` — one inspector, any subject | shipped | [Contributions](#contributions) |
| `Notifications` / `StatusBar` and their views | shipped | [Notifications and status](#notifications-and-status) |
| `DockBannerProvider` — a strip above a panel | shipped | [Contributions](#contributions) |
| `JobScheduler` — work off the UI thread | shipped | [Background work](#background-work) |
| `LanguageServices` — the engine behind a document | seam shipped | [Language services](#language-services) |
| `WorkbenchContext.extensionStore` + `ConfigRecord` | shipped | [Extension records](#extension-records) |

## Background work

`core.async.JobScheduler`, over CrystalGraphics' jobs (`CgJob`, `CgJobKey`, `CgJobLane`, `CgJobContext`, `CgReply`;
reference: `job/CLAUDE.md`). Runs work off the UI thread and hands the answer back on it; adds the loop a document
drains, the rows the chrome shows for long work, and a notification when announced work fails.

```java
scheduler.job(CgJobKey.of(document, "diagnostics"), CgJobLane.LATENCY, context -> compile(snapshot, context))
        .debounce(300)
        .onDone(result -> install(result))
        .submit();                     // a CgJob: a CgReply you may compose or cancel

scheduler.drain();                     // once per frame, on the UI thread
scheduler.cancelAll(document);         // closing a document
```

- **Every decision is made in `drain()`, on the UI thread**; only the body runs elsewhere. A test passes
  `new JobScheduler(Runnable::run, clock::get)`.
- **A key is `(owner, kind)`, owner by identity.** Resubmitting a waiting key replaces it; a running one is
  superseded: its `onDone` never runs. Keying on kind alone makes two documents fight; on owner alone, a reparse
  cancels its own diagnostics.
- **Lanes**: `INTERACTIVE` (a human is blocked) > `LATENCY` (visible) > `BACKGROUND`. A job waiting over two seconds
  goes first; `BACKGROUND` never takes the last worker.
- **Cancellation is cooperative**: poll `isCancelled()` / `throwIfCancelled()` (control flow, never logged).
- **The chrome** lists jobs that called `context.progress().begin(...)` and ran 400 ms (`active()`); a row stays
  500 ms. An announced job that fails raises a notification.
- **Staleness is the caller's**: compare `buffer.version()`; the scheduler only guarantees a superseded result
  never lands.

Rules:

- `drain()` on the UI thread, once per frame; it returns whether anything is outstanding.
- Off the UI thread, own a scheduler and drain it yourself (`WatchHub` is the reference): `shared()` is drained by
  the UI frame, which a dedicated server lacks. Inside a document's sequence `shared()` is the sequence's own.
- Never touch UI or document state from a body: return an immutable value; snapshot first.
- `cancelAll(owner)` when a document closes.
- Fire-and-forget work goes to `CgJob.executor(lane)`; work split within a frame is `CgJob.forEach`. Never a pool
  of your own.

## Disposal

`core.dispose`: `Disposable`, `Disposable.Gl`, `Disposer`. An ownership tree, ported from IntelliJ's `Disposer`:
disposing a parent releases its children first, in reverse registration order.

```java
Disposer.register(parent, child);
Disposer.dispose(parent);              // children first, reverse order, then parent
Disposer.isDisposed(parent);           // a second dispose is a no-op
Disposer.newDisposable("drag");        // a scope when there is no natural owner
```

Not a replacement for `CgEngineLifecycle.shutdown()`, which sweeps every registry at teardown. It is for releasing
on close rather than exit, and for what no registry sees (the preview pool's targets, the paint context's surfaces).

### Rules

- Never call `dispose()` directly: `Disposer.dispose(x)`, or the children are skipped.
- `dispose()` releases what you own directly; trust the tree for the rest. Make it idempotent; do not throw.
- **Own GPU memory? `Disposable.Gl`**: it is deferred to the device's thread through `CgDeferral`, and runs at once
  when no device is installed (headless tests).

### The disposal protocol for GL resources

`dispose()` means "give back what I was using", not "delete GL objects":

| Situation | What `dispose()` does |
|---|---|
| From a pool | release the key; delete nothing |
| Genuinely unique | delete it |
| A reference into something shared | drop the reference; the owner decides |

Pools belong to the context (`CgGraphicsLifecycle`), never to a document. A document owns slots, so the GL object
count follows what is on screen. `CgPreviewPool` is the example: one shared pool per `(size, samples)`, keyed by a
per-renderer scope prefix; closing a graph releases keys, reopening allocates nothing. Do not make something
`Disposable.Gl` just because it touches GL: ask first whether it should be pooled.

### Where it is wired today

| Owner | Owns |
|---|---|
| `CrystalEditor` | every `ShaderGraphEditor` it builds |
| `ShaderGraphView` | its `MainPreviewPanel` |
| `DocumentReference` | one holder's claim on a document; the last release disposes the model |

### What disposes, and what deliberately does not

| Action | Disposes? | Why |
|---|---|---|
| Close a tab | releases that tab's `DocumentReference` | the model goes with the **last** holder (Problems, an index, a compile) |
| Rename / move | no | `Document.retarget`: the same document at a new address |
| Delete the file | no: `DocumentState.ORPHANED` | the buffer may be worth writing back |
| Close the editor | yes | `Disposer.dispose(editor)` |
| Context destroyed | yes | where pooled GL objects die |

### Subscriptions belong to the thing they update

A binder says where the value lives and what announces it; the engine connects it while the control is in the tree,
drops it on detach and re-reads on re-attach. A property with no announcement is polled after layout.

```java
Property<Object> value = Property.derived(() -> read(store), v -> write(store, v))
        .announcedBy(refresh -> store.onChanged.connect(change -> refresh.run()));
form.prop(descriptor, value);                 // or control.bind(value)
```

Nobody releases these by hand: leaving the tree is the release. `ConfiguratorPanelLifetimeTest` pins both halves:
the subscription count stops growing, and surviving rows still follow the store.

## Extension records

What a user makes with an extension (the UI builder's Library groups) lives in the extension's own store,
`workspace-config/extensions/<extension-id>/`, the same in every application and workspace (VS Code's
`globalStorage`), as a typed record:

```java
record Shelf(boolean rows, List<String> pinned) { static final Codec<Shelf> CODEC = ...; }

ConfigRecord<Shelf> shelf = ConfigRecord.in(workbench.extensionStore(MyExtension.ID), "shelf.json",
        Shelf.CODEC, new Shelf(false, List.of()));
shelf.onChanged.connect(this::redraw);
shelf.update(s -> new Shelf(!s.rows(), s.pinned()));   // written pretty-printed, then announced
```

- **Ask on attach, not while activating**: extensions activate inside the `Workbench` constructor, before the
  application supplies the stores (`useExtensionStores`), so an early ask is null.
- Durable, and null on a host with nowhere private; a record with no store lives for the session.
- An unreadable file gives the default and is left alone until the value changes; an equal value writes nothing.
- Several records may hold one file: `update` re-reads before changing; call `reload()` before showing a value
  another holder may have changed.
- The tiers under `workspace-config/` (`apps/`, `projects/<key>/`, `extensions/`) are spelled only by
  `CrystalGuiStorage`, on CrystalGraphics' `CgStorageLayout`.

## Data context

`core.data`: `DataKey`, `DataProvider`, `DataContext`; standard keys in `ui.data.UiDataKeys`. How a command finds
its subject without naming the widget that supplies it, ported from IntelliJ.

```java
GraphView graph = context.data().get(GraphView.GRAPH_VIEW);          // asking, from a command

@Override
public Object getData(DataKey<?> key) {                              // answering, from a widget
    if (key == GRAPH_VIEW) return this;
    return super.getData(key);        // always last
}
```

### The rule

**The first non-null answer of the right type, walking outward from the focused element**: the same walk the keymap
and `UndoScope.nearest` use, so a key, an undo and a command agree on their subject. Internal children are walked;
a wrong-typed answer is skipped and the walk goes on.

### Rules for implementers

- Answer for yourself only: the innermost answer must win.
- Call `super.getData(key)` last.
- Be cheap and side-effect free: this runs while menus and palettes are built.
- Declare a key where its concept lives (`GraphView.GRAPH_VIEW`); `UiDataKeys` is for the engine's own.
- Never keep a `DataContext`: it is valid for one pass.

There is no `when`-expression parser yet: predicates over `DataContext` cover commands.

## Commands

`core.command`: `Command`, `Commands`, `MenuId`. A command carries its id, title, enablement, default bindings and
menu placement, registered into `Commands.process()`.

```java
Disposable handle = Commands.process().register(Command.of("graph.delete", "Delete")
        .binding("Delete", "Backspace")
        .menu(MenuId.GRAPH_CONTEXT, "modify", 10)
        .enabledWhereData(context -> context.has(GraphView.GRAPH_VIEW))
        .runWithData(context -> context.require(GraphView.GRAPH_VIEW).deleteSelection()));
```

`register` and `contributeMenu` return a `Disposable`, which a `WorkbenchExtension` returns from `activate`.
Disposing removes the command only if it is still the one registered, so an override survives.

### Commands are global; context is local

A command is a fact about the application; what varies is what is focused, which is `DataContext`'s job.
`Commands.process()` publishes one generation of the lookup, every menu's rows and the declared keymap. A command
runs on the sequence of the document its key or menu was in; one with no document runs on the services sequence and
may only post. `Commands` instances fall through to the process one and exist for overrides and test isolation.

### The rule for whether a command may be global

**Exactly when it resolves its subject from `DataContext`.** One that captures an owner runs against the first owner
forever, since registration is idempotent. The former holdouts resolve their owner now: `Workbench.WORKBENCH`,
`CrystalEditor.CRYSTAL_EDITOR`, `UiDataKeys.WINDOW`.

### A widget's commands arrive with the widget — two hooks on `UINode`

```java
class GraphView extends CanvasView {
    @Override protected void registerCommands(Commands registry) { GraphCommands.register(); }
    @Override protected void bindKeys() { GraphCommands.bindDefaults(keymap()); }
}
```

| Hook | Runs | For |
|---|---|---|
| `registerCommands(Commands)` | once per concrete class | the commands |
| `bindKeys()` | once per instance | chords scoped to this widget, on `keymap()` |

Both run from `UINode`'s constructor, so subclass fields do not exist yet: never capture `this`.

### Once-ness belongs to the registry: `contribute`

```java
public static void register() {
    Commands.process().contribute(GraphCommands.class, GraphCommands::declare);
}
```

Keyed on the contributor class, on the registry, so `resetForTesting()` clears it (`AutomaticCommandRegistrationTest`).
Never `if (registry.contains(SOME_ID)) return;`, and never a static latch.

### Registration is explicit

No static initialisers: a command's existence must not depend on class-loading order. Constructing a widget is the
trigger.

### Bindings and menus travel with the command

| Scope | Where | Example |
|---|---|---|
| Application-wide chord | `Command.binding(...)` | `Mod+Z`, `Mod+W` |
| Widget-scoped chord | `bindKeys()` on the element | bare `F`/`A`/`Space` in a graph, `Mod+D` in an editor |

- A bare letter is never a declared binding: it would fire over every text field.
- An element that binds a command explicitly suppresses that command's declared default.
- `Keymap.acceleratorFor` / `acceleratorsFrom` consult declared bindings last, after the scope walk.
- `Commands.menu(id, context)` returns contributions in group-then-order and omits disabled ones; a palette asks
  `all()`.

### What a host still installs: nothing

Constructing a widget registers its commands. The only `bindDefaults` left (`GraphCommands`, `EditorCommands`,
`ExplorerCommands`) are called from the owning widget's `bindKeys`.

### `onWindowChanged`, and the window-level provider

- `UINode.onWindowChanged(previous, current)`: what an element must do once it has a window, and undo on detach.
- `UIDocument.addDataProvider(...)`: IntelliJ's frame-level provider, asked **after** the element walk. It answers
  when nothing is focused (a window that just opened), which is when `Ctrl+P` and `F5` are pressed.

### Menus are queried, not written

```java
Command.of(NEW_FILE, "New File…").menu(MenuId.EXPLORER_NEW, "1_new", 10)
ContextMenu.of(MenuId.EXPLORER_CONTEXT)      // the whole menu
```

- Group then order, groups sorted lexicographically (VS Code's `"1_new"`, `"2_clipboard"`); separators fall out of
  group boundaries.
- A submenu is its own `MenuId`, nested with `nestedIn(parent, title, group, order)`, so anyone can add to it.
- `ContextMenu` dims disabled items rather than dropping them; a submenu with nothing contributed is dropped.
- A picker is not a menu: a fixed data set its owner defines (a mesh list) stays hand-built.

## Service events

`core.signal`: `Signal.Action` / `Signal.Value<T>` / `Signal.Pair<A,B>`, `Connection`, `ConnectionGroup`. A signal is
a `public final` field on the thing that owns the fact, never a global topic bus. Naming is VS Code's: `onDidX`,
past tense; there is no vetoable `onWillX`.

```java
dock.onDidChangeActivePanel.connect(panel -> follow(panel));
Disposer.register(this, source.onDidLoadListing.connect(this::retry));   // owned, not remembered
```

### What exists

| Signal | Owner | Says |
|---|---|---|
| `onDidChangeActive` | `EditorService` | the active editor changed |
| `onDidChangeActivePanel` | `DockArea` | the active panel changed |
| `onDidChangeShownPanels` | `DockArea` | what each group shows, torn-out windows included unless minimised |
| `onDidChangeVisible`, `follow` | `EditorService` | the visible editors changed: what the Hierarchy and the Library follow |
| `onDidClosePanel` | `DockArea` | a panel closed |
| `onDidOpenDocument` / `onDidCloseDocument` | `Workbench` | a document opened or closed |
| `onDidChangeLayout` | `DockArea` | the layout changed |
| `onDidRegister` | `DockPanelRegistry` | a panel type was registered |
| `onDidLoadListing` | `WorkspaceTreeSource` | a directory listing arrived |
| `onDidChangeState` | `WorkspaceDocuments` | a document's dirtiness changed |
| `onChanged()` | `DocumentModel` (SPI) | the model changed, undo and redo included |
| `onDidChangeFocus` | `Input` | who holds focus now, deduplicated: for observers off the focus path |

### Three rules that are easy to get wrong

1. **A connection is a `Disposable`**: `Disposer.register(owner, signal.connect(...))`, so it dies with its owner.
2. **Subscribing is not catching up**: a signal reports only what happens after you subscribe. Sync first, then
   subscribe (`StripeView.listenToPanels`).
3. **Announce idempotently, from every mutation path**: compare with the last value announced and call from every
   path that can change it (`DockArea.announceActivePanel()`). `Signal.Value` does not suppress equal values;
   `Property.set` does.

### Events are immediate; structural rebuilds are not

A widget must never rebuild the elements it is being clicked or dragged on, so the shape is **event → dirty flag →
the next frame rebuilds once** (`DockArea.rebuildPending`, `ProjectFileTree.pendingRefresh`). In-place updates (a
title, a class) are safe at once.

### Re-entrancy

`SignalBase` tracks emission depth, so emissions may nest. A listener connected during an emission does not receive
it; `disconnectAll()` mid-emit stops delivery at once and defers the removal.

### What stays per-frame

Incremental work, never a poll for change: `WorkspaceTreeSource.indexStep` walks a few directories a frame from
`Workbench.tick`. `fileTree.loadProjects()` is a retry until the client's session opens; its latch is set on the
attempt, so never call it from `onWindowChanged`.

## Notifications and status

`core.notify`: `Notification`, `Notifications`, `StatusBar`. Two channels: a **notification** happened once, may
carry an action and belongs in a history; a **status item** is how things are now, replaced rather than kept.

```java
Notifications.show(Notification.error("copy failed: " + name).withAction("Retry", again));

StatusBarEntryAccessor compile = StatusBar.addEntry(
        new StatusBarEntry("Shader graph compilation", "compiled 9n/8e", "996 chars", null,
                StatusBarEntry.Kind.STANDARD),
        "shadergraph.compile", StatusBarAlignment.LEFT, 100);
compile.update(compile.entry().withText("compile failed").withKind(StatusBarEntry.Kind.ERROR));
compile.dispose();   // withdrawing is disposing the handle
```

- Status items are keyed per writer; re-stating an unchanged item is silent; a changed tooltip is a change.
- Severity is on the notification, not in the text. The history is bounded.
- Nothing is displayed by default: the application mounts the views.

### Alignment — which end of the bar an item sits at

```java
StatusBar.addEntry(StatusBarEntry.of("Explorer", "created notes.txt"), "explorer", StatusBarAlignment.LEFT);
StatusBar.addEntry(StatusBarEntry.of("Cursor position", "Ln 51, Col 39"), "editor.caret", StatusBarAlignment.RIGHT, 100);
StatusBar.entries(StatusBarAlignment.RIGHT);   // what a view renders, left to right
StatusBar.allEntries();                        // what a hide menu lists, hidden ones included
```

The writer chooses the end, fixed at registration: moving an entry means disposing and registering again. Within a
group, higher priority comes first. `text()` flattens everything into one line for logs and headless assertions.

### The view is `StatusBarView`, and it renders rather than computes

`widget.chrome.StatusBarView`, mounted by `Workbench` below its content.

- It shows only what a writer keyed, and updates slots in place, never rebuilding.
- Its removal pass runs before placement, or an entry that changed ends would have two parents.
- A spacer takes the slack, not `margin-left: auto`.
- It subscribes only while attached: `StatusBar` is static.
- A slot's tooltip is attached once and re-texted; separators are elements.
- `breadcrumbs()` is the one widget the host sets.

**A document publishes its own items** through `DocumentEditor.activated(boolean)`: a text editor its caret, line
ending, encoding and indent; a shader graph its compile summary. Whatever it sets on activation it clears on
deactivation. Tests: `NotifyTest` (headless), `StatusBarViewTest`.

### Notifications: the model, and the two surfaces

```java
Notifications.show(Notification.error("Open failed")
        .withDetail(path.name() + " — " + failure.code())
        .withAction("Retry", () -> openFile(path)));
service.clear();             // "Clear all"
service.markAllRead();        // the bell, keeping the history
```

A `Notification` has a severity, a title, an optional detail, a `timestamp` (wall clock, stamped at construction), a
`groupId` and any number of `Action(label, Runnable)`. Reading is not dismissing. `clear()` is not
`resetForTesting()`, which also drops every subscription.

#### `NotificationsView` — the durable surface

A tool window on the auxiliary rail, newest first; its unread count reaches the rail button through
`ViewContainerRegistry.setBadge`.

#### `NotificationBalloons` — the transient surface

Bottom-right, over the workbench and under the drop overlay.

- `INFO` fades; `WARNING` and `ERROR` stay until closed.
- Two caps, one per kind, counting only entries not already leaving.
- Opacity is a CSS transition: a balloon arrives with `__hidden__`, dropped on the next frame.
- `FADE_MS` matches the sheet: the layer waits that long before detaching.
- No timestamp on a balloon.

One `NotificationCard` builds both surfaces; a balloon adds a close button. There is no de-duplication: never notify
from a per-frame path (use `StatusBar`). Test: `NotificationsViewTest`.

## Resources

`fs`: `Resource`, `CgPath` and the failure types. `fs.client`: `Workspace` (the desktop's service for one
connection), `WorkspaceClient` (an application's handle), `ContentProvider`, `ContentProviders`. `fs.provider`: the
filesystem SPI. `fs.server`: the workspace over it: authorisation, etags, trash, presence.

**One workspace per connection, one client per application.** `Workspace.of(connection)` lives on the services
sequence and keeps the greeting, the capabilities, the watches (one subscription per path), the backup and history
stores and the provider table. `workspace.client("Crystal Editor")` on an application's sequence returns its handle;
replies and events come back there, and the registry disposes it when the application quits. Document models are per
client: two applications agree **through the file**, a dirty copy conflicting on its next save, and each sees the
other in `presence()`.

A tab's input need not be a file: it is a `Resource` with a scheme (VS Code's `URI`, IntelliJ's `VirtualFile`).

```java
Resource.of(cgPath)                                   // project://, spelled as the bare path
Resource.of("untitled", "buffer-1")                   // untitled://buffer-1
Resource.derived("shader-generated", Resource.of(p))  // shader-generated://mymod.proj:fire.shadergraph
Resource.parse(text)                                  // round-trips all of the above
```

### The project scheme keeps `CgPath`'s exact text

A project resource is spelled as the bare path, which saved documents hold forever; everything else is
`scheme://path`. A project id cannot contain `/`, so `parse` looks for `://` first; a colon inside a segment is still
a project path.

### Derived resources carry their origin, in the text

`shader-generated://mymod.proj:fire.shadergraph` survives `parse` and a saved session, so five graphs give five
generated documents.

### Who answers for a scheme — `ContentProvider`

```java
workspace.registerScheme("library", new LibrarySources());   // this server's
ContentProviders.contribute("library", new LibrarySources()); // the process's
```

A provider answers `read` (the content), `symbolOf` (what the resource is: a tab's glyph) and `locate` (where a
member is declared), each a `CgReply`. Contribute to the process when you register before any world is joined (a
language stack at mod init); each workspace drains the contributions into its own table.

### Rules

- A project resource's content always comes from the server, whatever registered the scheme.
- An unregistered scheme is read-only.
- `read()` answers when the origin is gone: empty bytes, never a throw.
- Registration returns a `Disposable`.

### The server's watcher — `WatchHub.update`

```java
fanOut(hub.update(serverActor, deltaSeconds));   // events, what the server did, the reconcile
```

`update` drains the filesystem's events itself; nothing else may. Every `WatchHub.RECONCILE_SECONDS` it re-stats
the watched files as a job: authorised on the calling thread, stat-ed on the pool, judged on a later `update` ahead
of that tick's events, so a deletion can still pair into a rename. A path whose etag moved during the sweep is
skipped.

### The document is the identity; the resource is a property of it

`Document.resource()` is what it is called now; `onDidChangeResource` is the one event a store subscribes to, so a
rename moves the document. The server reports a rename as one change with both ends; `WorkspaceDocuments` retargets
the document and the workbench moves the tab in place.

### One store, keyed by `Resource`

`Documents` holds every open document by resource, so project files, decompiled classes and generated sources share
one way to open. Whether `Main.java` and `main.java` are one document is the host's rule, from the greeting, handed
to `Documents.setKeyStrategy`; `Resource` equality stays strict.

## Panes and placement

`widget.dock`: `DockInput`, `DockPane`, `DockPaneProvider`, `DockPlacement`.

### `DockInput` — the runtime form of what a tab shows

Wraps the persisted `DockPanelRef` (`ref()` is what the session writes) and answers which `Resource` the panel is
about. Unparseable state gives a null resource, never a throw. `matches()` is ref equality: a ref's state is part of
its identity.

### `DockPane` — one instance per (group, type), retargeted

VS Code's `EditorPane`, IntelliJ's `FileEditor`: `setInput` / `clearInput` / `onVisible` / `onHidden`, with
`writeViewState` / `readViewState` keyed by the framework. **A pane holds no per-input state except view state**: two
tabs of one type in one group share the instance. `DockPaneProvider.accepts` + `priority()` choose between two
providers for one input, whatever the registration order.

### How it is wired

Every pane-backed panel keeps its own empty host, and only the active panel's host holds the view, moved with
`setOnlyChild`. The order is the contract: write the outgoing view state → `onHidden` → `setInput` → read the incoming
state → `onVisible`. A pane is released (`clearInput`, then dispose) when its type has no panel left in the group,
including when the group itself goes.

### `DockPlacement` — "where", as a request

```java
dockArea.groupOf(myElement)                          // the group I am in
DockPlacement.resolve(DockPlacement.with(me), dock)  // "next to me"
```

VS Code's `PreferredGroup`, typed: `active()`, `side(zone)`, `with(element)`, `central()`, `leaf()`.

- `groupOf` walks `getParent()`, shadow parents included.
- `resolve` returning null is ordinary: opening reads it as "make one", asking as "nowhere".
- `active()` falls back as `activeGroup()` does, so it answers before anything is clicked.

### Torn-out windows — one set of editors

A tab dragged out lands in a `DockWindow`, whose dock belongs to its **home**. The home answers for all its windows:

```java
DockArea dock = workbench.dock();
dock.activePanel();          // front panel of the area the user last worked in, whichever window
dock.shownPanels();          // every group's front panel on screen; a minimised window's are not
dock.allPanels();            // every tab, minimised windows included
dock.activatePanel(ref);     // in whichever window holds it, brought forward and restored
dock.closePanel(ref);        // likewise, asking the home's guard; closePanelDiscarding skips it
dock.removePanel(ref);       // a tab whose subject has gone: no guard, no close announcement
dock.replacePanel(was, now); // a renamed file's tab, in place
dock.rebuildPanel(ref);      // a placeholder whose document has landed
dock.activeArea();           // where the next file opens
dock.windows();              // what the session saves
dock.setTabOverflow(WRAP);   // every group's tab strip, windows and later groups included
dock.openInNewWindow(ref);   // Open Tab in New Window
new DockWindow(dock, layout, title);   // joins dock's home: registry, close guard, icon, application
```

A tab command takes its tab from `DockTab.of(context)`: the tab its menu was opened on, else the active group's front
tab, so one command serves the tab menu (`MenuId.EDITOR_TAB_CONTEXT`), the keyboard and the main menu.
`layout.opposite(leaf)` is the neighbouring group across a split; `layout.unsplit(leaf)` folds it back.

```java
registry.register(Command.of("mymod.pin", "Pin Tab")
        .menu(MenuId.EDITOR_TAB_CONTEXT, "4_window", 5)
        .run(context -> { DockTab tab = DockTab.of(context); if (tab != null) pin(tab.panel()); }));
```

- `activeArea()` is the dock whose group was last active, not the desktop's active window; minimising or closing it
  hands back to the home.
- `activeGroup()` stays per area.
- The home announces for every window (active and shown panels, `onWillClosePanel` / `onDidClosePanel`);
  `onDidChangeLayout` stays per area.
- **A new operation on "the dock" answers across windows**, or it is a bug.

### Dropping files onto an editor area

A file dragged from the Project panel opens where it is dropped, with a dragged tab's zones. A drag the dock did not
start is asked of a `DockForeignDrop` on the home; the payload says what it carries through `TransferData`. The
clipboard carries the same type: `UiClipboard.process().put(TransferData.of(fragment, text))` keeps the typed form
and writes the text to the system; a paste offers the typed form only while the system text is still what was
written.

**Between applications** a drag leaves its document only when its payload is a value: `DraggedResources` is, and a
tab offers one (`TransferData.carrying(tabPayload, new DraggedResources(...))`). The window under the pointer hears
`Over`/`Drop`/`Leave` with `ForeignDrag.SOURCE`; a window where no element takes it gets the resources through
`Application.open`, and the bare desktop opens them in the source application.

```java
public Object transfer(List<CgPath> paths) { return new DraggedResources(files(paths)); }   // a tree's rows
dock.setForeignDrop(opener.fileDrops());                                                   // what a drop opens
```

- A file already open moves its tab.
- `panelsFor` runs on every move of the drag (cached per payload): keep it cheap.
- The dock places the tab; the panel factory reads the file.

## Opening things

**One opener, and it is the dock's.**

```java
workbench.open(input);                                              // the active group, activated
workbench.open(input, DockPlacement.with(me), DockOpenOptions.ACTIVATE);
workbench.open(input, DockPlacement.side(SPLIT_RIGHT),
               DockOpenOptions.INACTIVE.withShare(0.28f));
```

### Rules

- **"Already open" wins over placement**, in whichever window holds it.
- Anything new opens in `dock.activeArea()`.
- A ref is a panel's identity, so opening it again shows the copy that is there; a split is the one way to show it
  twice. Two panel types over one path (a diff beside the file) are two panels.
- `open` returns the leaf it landed in.
- **The dock decides what is in front; `EditorService.active()` follows it.** `editors.open(input, false)` loads and
  announces a tab without changing the front (restoring unsaved work at launch).

### Split: one document, a view per pane

A file in two groups is one `EditorService.Tab` with a `View` per group: one document, history and dirty state; a
caret, selection, scroll and folds per pane.

```java
EditorService.Tab tab = editors.tabFor(input);
tab.views();                   // one per pane showing it
tab.editor();                  // the front view's: the pane that last had focus
editors.focusView(content);    // the workbench calls this as focus moves between panes
```

- `Tab.mount()` reuses a view no pane holds, else builds one starting where the front one is.
- A group lets go of what it built for a panel that left it, pruned before any group builds, so a dragged editor
  keeps its caret and undo.
- Closing is per copy (`DockArea.closePanel(leaf, ref)`; the signals carry `DockArea.ClosedPanel`). The document goes
  with the last copy.
- A kind whose view is its document's state declares `DocumentKind.singleView()`, and a split moves it.

Every shipped kind splits:

- **A graph**: `ShaderGraphDocument` holds the `GraphDocument` and one `UndoStack`; each `ShaderGraphView` drains its
  own changeset (`GraphDocument.openChangeset`). Undo reverses the document, never a view.
- **A UI tree**: each pane shows a `ShownTree`, a copy that follows the document and reconciles after each change.
  A gesture works on the pane's drawing and crosses to the document only to commit (`UiBuilderDocument.apply`);
  geometry and computed style come from the drawing: `BuilderContext.shown(node)`,
  `UIBuilderView.drawnSelection(context)`.

## Making things — `NewDocumentKinds`

`DocumentKind` says how to **open** a format; `NewDocumentKind` how to **make** one.

```java
workbench.newDocuments().contribute(workbench.owner(), NewDocumentKind.of("mymod:notes", "Notes")
        .icon("crystalgui:file-text")
        .suffix(".notes")
        .where(NewDocumentContext::outsideSourceRoots)
        .template(target -> "# " + target.typeName() + "\n"));
```

| | |
|---|---|
| `NewDocumentKind` | id, label, icon, suffix, placement, `where`, `template`, variants |
| `NewDocumentContext` | where a New would land: the directory and its source root |
| `NewDocumentKinds` | the process-wide registry; `contribute(owner, kind)` returns a `Disposable`; one owner contributing an id twice is refused |
| `WorkbenchContext.newDocuments()` | how an extension reaches it |

- **The id is a command id**: namespace it and never rename it casually.
- **`where` is asked per click**: `inSourceRoot()`, `outsideSourceRoots()`, `sourceRootHolds("java")` (read off the
  root's last segment, `src/main/java` or `src/main/js`).
- **Variants** give the row a name-and-kind prompt (IntelliJ's New Class); the first is the default.
- **Ordering** is group, then order, with `NewDocumentKind.RESERVED` as the engine's ceiling.
- The built-ins register through the same seam (`BuiltInNewDocuments`, `UiBuilderContribution`,
  `ShaderGraphContribution`).

### `SourceRoots.rootOf`

`rootOf(path, roots)` answers which declared root contains a path, the root itself included. `locate` is for a file
inside a root and reads the last segment as a file name.

## `UINode.setOnlyChild`

"Show one of several things in this slot." Never `clearAllChildren().append(wanted)`: that skips shadow parts, and
the outgoing child stays.

## Tool windows, stripes and the drag between them

Reached from `Workbench`: `toolWindowManager()`, `stripe(rail)`, `stripes()`, `dropOverlay()`. Nothing here needs a
`UIDocument`, so the geometry is testable headlessly.

### Placement is `(region, side)`, and nothing else

`ToolWindowState` stores a `DockRegion` and a `RegionSide`: IntelliJ's `anchor` + `isSplit`. An anchor's two halves
share one stripe, separated by a rule.

| Ask | Method |
|---|---|
| Where does it belong? | `regionOf(typeId)` / `sideOf(typeId)` |
| Where in its stripe run? | `orderOf(typeId)` |
| Who else is in that run? | `groupOf(region, side)` |
| Move it | `moveTo(typeId, region, side)` / `moveTo(typeId, region, side, index)` |
| Is it on a stripe at all? | `isStripeButtonShown(typeId)` / `setStripeButtonShown(typeId, shown)` |
| What has been hidden? | `hidden()` |
| Do the stripes label their buttons? | `isShowingNames()` / `setShowingNames(b)` / `onDidChangeNames()` |
| What kind of panel is it? | `descriptorOf(typeId)` |
| Tell me when any of that changes | `onDidChangePlacement` |

`groupOf` walks the registry, not the stored placements, since a window nobody moved has none. `moveTo` renumbers the
whole group.

### What an empty editor says

`DockArea.setWatermark(heading, lines…)`: each `WatermarkLine` is text plus the command whose chord annotates it,
resolved against the live keymap. The copy belongs to whoever composes the dock; a dock with none shows none.

### Hiding a button is about the rail, not the window

`show_stripe_button` is stored on the placement, in the project session, so a hidden button returns where it was.

- Hiding closes the panel.
- Opening restores the button (in `showPanel`, so a shortcut and the palette do too).
- `hidden()` is one list for the workbench: *More tool windows*.
- The rails' context menu is `StripeCommands`, resolved from `StripeView.BUTTON_TYPE` and `Workbench.WORKBENCH`.

### The rail is derived; placement is primitive

`StripeRail.of(region, side)` answers which rail carries a button; `topRegion()` / `bottomSide()` what each rail's
groups hold. There is no stored rail.

### The drop target is the whole workbench

`RegionDropZones` is the arithmetic (six slots, a "no" over the editor, corners to the bottom band);
`RegionDropOverlay` its one consumer, listening in the bubble phase on the workbench's content box. This bands the
window, unlike IntelliJ's per-stripe areas.

### Floating windows are windows of the desktop

A floating tool window (`ToolWindowFloat`) and a torn-out editor (`DockWindow`) are desktop windows, their views
staying in the workbench's document and shown through a `ClientPortal`. `Workbench.useFloats(FloatSpace.of(
context.windows(), main))` says how to open them: tool windows are owned by `main` (above it, hidden and closed with
it, no taskbar entry), torn-out editors are peers. Without it they open on the workbench's own document.

### Two widgets that are not workbench-specific

- **`DragPreview`** (`ui.service`): what a drag looks like, as values: an icon, a label, a style class, `grabbing()`,
  `text(..)` and `flipped(..)` mid-drag.

  ```java
  Drag.startWithPayload(row, x, y, payload, listener)
          .preview(DragPreview.of("crystalgui:folder", "Problems").styled("__row-ghost__"));
  ```

  The drag owns the ghost and the host draws it: the shell while the document is a window's client, else
  `DragGhostPresenter`. A drag with no preview shows nothing. Nobody constructs a `DragGhost`.
- **`InsertionMarker`** (`widget`): where a drop lands. `OVERLAY` floats over the list (`DockGroup`); `IN_FLOW` opens
  a gap (`StripeView`). It picks the first item whose midpoint is past the pointer, over `[0, size]`.

## Widget state that outlives the run — `SessionState`

`DockLayoutCodec` records where a panel is, not what is inside it. A widget's own view state (a divider's position)
goes in `SessionState`: `writeState` payloads keyed by element id, held by the `UIDocument` and persisted by
`WorkbenchSession` under a `widgets` key.

```java
split.setId("run.rail-split");
split.setSessionPersistent(true);
```

It reuses what exists: `UINode.writeState` / `readState` for what to keep, `setId` for which widget,
`UIDocument.registerElement` / `unregisterElement` for when.

- **Applied on registration**, whenever the widget is built (a panel's split may not exist until much later).
- **Captured on unregistration**: hiding a tool window detaches it, and that is the last moment it can be read.
- The store is installed when the session is constructed, not when a restore succeeds, so a first run has one.
- An id is spent when applied: a rebuild does not drag the widget back.
- Entries nobody claimed are kept and written back.

### Rules for implementers

- An id is required, stable across runs and namespaced (`run.rail-split`).
- Opt in explicitly: ids are also set for selectors.
- `readState` is a request: clamp what comes back (`SplitView.setWeights` ignores extra values, leaves missing ones).
- View state only, never a model (a divider yes, a filter no).
- No session version bump for adopting it: the `widgets` key is additive.

## Applications, extensions and the engine

**Five tiers, each naming only the one below**: a *host* supplies a surface, a config directory and a connection;
the *shell* (`Desktop`) composites windows; an *application* is a manifest plus the features it enables; the *engine*
(`Workbench`) is what a workbench is; an *extension* attaches a feature to one.

```java
ApplicationKind.of("mymod:notes", "Notes")
        .icon("mymod:notes")
        .opens(DocumentKind.FilePatterns.extension("notes"))
        .singleInstance()
        .launch(context -> WorkbenchApplication.of(context)
                .with("crystalgui:notes")            // the extension ids this product turns on
                .title("Notes")
                .key("notes:main")
                .policy(WindowPolicy.HIDE_ON_CLOSE)
                .start());
```

A line in `META-INF/services/com.crystalgui.desktop.app.ApplicationKinds` names a class that puts the manifest into
the registry; every desktop runs those services once, lazily. `launch(kind, workspace, storage)` returns an
`Application`: `mainWindow()`, `open(resource)`, `activate()`, `dispose()`. `ApplicationRegistry.install(kind)` is
for a manifest built at run time. A second product is a second manifest, never a second shell: `WorkbenchApplication`
is the shared runtime, and `CrystalEditor` is a manifest and three choices.

### The rules that are easy to get wrong

| Rule | Detail |
|---|---|
| Installing is not launching | a launcher, `handlerFor(resource)` and search all answer with nothing running |
| Closing is not quitting | under `HIDE_ON_CLOSE` the application keeps running; `Application.dispose()` quits. `WindowRegistry.evictIfNeeded` never evicts a main window |
| Each application has its own storage | `CgConfigStorage.scoped(applicationId)`, applied before the factory runs |
| Preferences are yours, workspace state the workspace's | `AppContext.storage()` is `workspace-config/apps/<id>/`; sessions, backups and history go under `workspace-config/projects/<identity>/` through `AppContext.workspaces()`, once the greeting gives an identity (`restoreWhenReady`) |
| `cache/` is disposable | `AppContext.cache()` is `crystalgui/cache/apps/<id>/`; deleting it loses nothing. Only `CrystalGuiStorage` spells the tree |
| A session is keyed by (application id, workspace identity) | the identity is the server's `workspaceId`, or a hash of the sorted project ids |
| An id nothing contributed is a logged absence | `crystalgui:scripting` is simply missing on a host with no engine band |
| A `Workbench` built directly activates every extension | only an application filters |
| The window knows its application | `WindowFrame.setApplication(kind)`; the taskbar groups by it |
| A host names no product and no feature | both are `ServiceLoader` services; when two products claim one file type, the first installed wins |

### Writing an extension

```java
public final class NotesKind implements WorkbenchExtension {
    public static final String ID = "crystalgui:notes";
    public static final DocumentKind KIND = DocumentKind.of(ID, "Notes")
            .files(DocumentKind.FilePatterns.extension("notes"))
            .model((resource, bytes) -> NotesModel.decode(bytes))
            .editor(NotesView::new);

    public NotesKind() { }                                  // ServiceLoader's rule
    @Override public String id() { return ID; }
    @Override public Disposable activate(WorkbenchContext workbench) {
        return workbench.kinds().contribute(workbench.owner(), KIND);   // everything it contributed, in one handle
    }
}
```

- One class per feature; keep the declaration a `static final` so a launcher can read it without activating.
- The engine's own panels (Project, Problems, Notifications, Presence, the Inspector) are extensions too:
  `new Workbench(workspace, List.of())` has no tool windows.
- `META-INF/services/com.crystalgui.workbench.extension.WorkbenchExtension` says the jar has the feature; a manifest's
  `with(...)` enables it. `WorkbenchExtensions.contribute(extension)` is for one built at run time.
- An extension is written against `WorkbenchContext`, never `Workbench` (`LayeringTest`).

## Contributions

**A feature declares what it can do; nothing enumerates features** (IntelliJ's extension points, VS Code's
`contributes`).

### `DocumentKind` — which editor opens which file

```java
workbench.contribute(DocumentKind.of("crystalshader:graph", "Shader Graph")
        .files(DocumentKind.FilePatterns.extension("shadergraph"))
        .icon("crystalshader:graph")
        .model((resource, bytes) -> GraphModel.decode(bytes))   // what it is
        .editor(GraphView::new)                                 // one way of looking at it
        .status(GraphStatus::contribute),                       // while it is in front
        "shadergraph");                                         // and the panel binding
```

- Declared by the package that owns the type, in one call; `contribute` refuses an incomplete kind.
- The model and the view are separate: a model knows its content, nothing about tabs, so a document analyses with no
  tab open and split panes share one parse. `.editor` is optional.
- At most one kind calls `.fallback()`: the "File" kind, for any text file nothing else claims and every resource in
  a registered scheme.

A kind may name the tool windows its editor is worked in; they come up beside a **new** tab without taking the
keyboard (not on switching tabs, not on session restore). An unregistered id is skipped.

```java
DocumentKind.of("crystalgui:cgui", "UI Document")
        .files(DocumentKind.FilePatterns.extension("cgui"))
        .revealsToolWindows(UiBuilderContribution.HIERARCHY_PANEL, InspectorExtension.TYPE)
```

### `Inspector` — one inspector, any subject

```java
InspectorRegistry.register(new InspectorSection() {
    public String tab()                             { return "Node"; }
    public boolean accepts(DataContext ctx)         { return ctx.has(SHADER_GRAPH); }   // Blender's poll()
    public void build(ConfigForm form, DataContext ctx) { form.prop(descriptor, property); }
    public String subjectKey(DataContext ctx)       { return "node:" + …; }             // identity, for dedup
});
```

A section fills a form; it never returns a widget. Two sections on one tab write into one panel. Blender's Properties
editor is the model: a panel's `bl_context` and `poll` are `tab()` and `accepts`, the subject comes from the context,
and `layout.prop` is `ConfigForm.prop(ConfigDescriptor, Property)`: build from descriptors, not hand-placed widgets.

#### The subject is the focus owner, and the engine resolves it

| Concern | Who handles it |
|---|---|
| What is inspected | `Inspector`, from `Input.onDidChangeFocus` |
| Focus inside the inspector itself | ignored by `Inspector` |
| Losing focus | `Inspector` latches the last subject |
| A subject no section accepts | `Inspector` keeps the last describable one |
| Rebuilding | `Inspector`, a frame later; skipped when `subjectKey` has not moved |
| A replaced panel's subscriptions | `ConfiguratorPanel.clearRows()` |
| A selection that moved | its owner: `InspectorSubject.changed(node)` (`GraphSelection`, `SurfaceEditor`) |
| A closed subject | its owner: `InspectorSubject.closed(document, element)` |

Most subjects are never describable: a text buffer's metadata belongs in the status bar. Do not give a document a
section to keep the panel from looking empty.

### Editing the document from a section — `NodeFields`

A section that edits an open document binds to properties that write the document's own edits, never to the model:
otherwise the change skips the history and the tab never goes dirty. `NodeFields` (`app.uibuilder.inspect`) is that
binding for a `.cgui`:

```java
NodeFields fields = NodeFields.of(ctx);        // null over a live pick: there is no document to write
if (fields != null && fields.owns(node)) {
    form.prop(ConfigDescriptor.text("id", "id"), fields.id(node));
    NodeFields.Field hitTest = fields.attribute(node, Attribute.HIT_TEST);
    form.prop(hitTest.descriptor(), hitTest.value());          // the control the type asks for
    NodeFields.Field left = fields.style(node, LayoutProperties.LEFT, "Left");
    form.prop(left.descriptor(), left.value());                // one inline declaration
    form.prop(ConfigDescriptor.text("basis", "Basis"),                 // a field of your own
            fields.bind(() -> basisOf(node), css -> fields.inlineEdit(node, LayoutProperties.FLEX_BASIS, css)));
}
```

- Ask `owns` first: a live pick can select another window's node.
- Every property is in the document's history: a scrub is one undo step, and an undo refreshes the control without
  rebuilding the form.
- `descriptorFor(id, label, type, hint)` is the one type-to-control mapping; an unmapped type is a read-only row.
- `style(...)` writes through `SetInlineStyle`; setting a value back to the computed one drops the declaration.

### `DockBannerProvider` — why this tab is not an ordinary one

```java
DockBanners.register(panel -> SOURCE_TYPE.equals(panel.typeId())
        ? Notification.info("Generated from the shader graph. Edit the graph, not this file.")
                .withAction("Open Graph", () -> workbench.openFile(origin))
        : null);
```

IntelliJ's `EditorNotificationProvider`: facts a tab cannot show itself (generated, read-only, out of date).

- Asked with the `DockPanelRef`, not a document, so it reaches panels that are not documents.
- Wrapped in `DockGroup.contentFor`; nothing is wrapped when no provider answers (`DockBannerTest`).
- Severity is a class (`__info__` / `__warning__` / `__error__`).
- Every provider that answers gets its own strip.

### Rules

- Tabs come from the sections that applied, never a fixed list.
- A section holds nothing: it is handed the context and builds.
- Ordering is declared (`order()`).
- Nothing inspectable is an empty state (`__inspector-empty__`).
- Rebuild, never retarget: a section holds no per-input state.
- What counts as the subject is the application's policy (`CrystalEditor` latches the active document).

## Status bar entries

`core.notify`: `StatusBar`, `StatusBarEntry`, `StatusBarEntryAccessor`, `StatusBarAlignment`. Ported from VS Code's
`IStatusbarService`.

```java
StatusBarEntryAccessor caret = StatusBar.addEntry(
        new StatusBarEntry("Cursor position", "51:39", "Line and column of the caret",
                           "editor.gotoLine", StatusBarEntry.Kind.STANDARD),
        "editor.caret", StatusBarAlignment.RIGHT, 100);
```

- **The handle is the identity and the lifetime**: two registrations are two entries; register it on a `Disposer`.
- **Higher priority is further left, in both groups** (VS Code: selection 100, indentation 99, encoding 98, eol 97).
- **`name` is what the entry is, `text` what it shows**: a hide menu lists by name.
- **`command` is a command id**, run as `Commands.process().run(id, CommandContext.of(element))`: a contextless
  `run(id)` has an empty `DataContext`, and an `enabledWhereData` command silently does nothing.
- `update` is silent when the record is equal; `onDidChange` carries nothing.
- `setHidden(id, …)` hides; `allEntries()` still lists it, `entries(alignment)` does not.

## The notification model

`NotificationService` (`desktop.notifications()`, one per desktop): one observable collection with typed changes,
VS Code's `NotificationsModel`. Its changes are a `Broadcast`, heard on the subscriber's own document a turn later.

```java
whileConnected(() -> service.onDidChange.subscribe(event -> {
    switch (event.kind()) {
        case ADDED   -> add(event.notification());
        case CHANGED -> restate(event.notification());   // a repeat, or a handle's updateMessage
        case REMOVED -> remove(event.notification());    // aged out, or withdrawn
        case CLEARED -> rebuild();                       // one event, not N removals
    }
}));
NotificationHandle handle = service.show(Notification.error("Disconnected"));
handle.updateMessage("Reconnecting");   // CHANGED: does not re-ring the bell
handle.close();                          // REMOVED
```

- A view must handle `REMOVED`: the history is bounded.
- A group decides how loud a notification is: `NotificationGroups.displayOf(groupId)` → `BALLOON` / `STICKY_BALLOON`
  / `LOG_ONLY` / `NONE`, user-overridable with `setDisplay`; an unregistered group is `BALLOON`.
- Never-show-again is keyed by a message kind: `withNeverShowAgain(id)`.
- A repeat is `CHANGED` and does not move the card.
- The service is the desktop's; the toasts are the shell's, above every window. `Notifications.show(...)` is a facade
  to the caller's document's service.
- Every notification has a source application (`Notification.from(id, name)`, else the focused one): a click
  activates it, `closeFrom(id)` withdraws its sticky ones, `historyFrom(id)` lists its own.
- What the user silenced is a desktop preference (`notifications.silenced.<id>`, `notifications.display.<group>`);
  the history is not persisted.

## Diagnostics — owners and resources

`text.diagnostic`: `Diagnostic`, `DiagnosticSet`, `Markers`, `DiagnosticTag`, `RelatedInformation`. Ported from VS
Code's `IMarkerService`, keyed by `(owner, resource)`.

```java
set.changeAll(Map.of("shadergraph", emitterProblems,       // several producers on one document,
                     "glsl",        driverProblems,        // each replacing only itself;
                     "preview",     previewProblems));     // announced once

markers.attach(document.resource(), document.diagnostics());   // the workspace index, one per Workbench
markers.count(DiagnosticSeverity.ERROR);
markers.resourcesWithProblems();
markers.detach(resource);   // closing a document
```

- A producer names itself; `changeAll` for one that writes several owners.
- `Markers` is an instance, never static: it holds a listener on every set it indexes.
- `detach` on close, or the document stays alive.
- `setAll` writes the default owner's slice only.
- Tags change how a diagnostic is drawn (UNNECESSARY fades, DEPRECATED strikes through), not its severity.

## Language services

`text.lang`: `LanguageServices` and the contracts it bundles (`SemanticTokenProvider`, `Resolver`,
`CompletionProvider`) with their value types (`SymbolInfo`, `SymbolKind`, `SymbolModifier`, `TypeRef`,
`DeclarationSite`, `CompletionItem`, `CompletionList`, `Versioned`). Interfaces only; engines live in `language/`.

```java
LanguageRegistry.Entry entry = LanguageRegistry.forFileName(path.name());
editor.setTokenizer(entry.newTokenizer());
editor.setLanguageServices(entry.newServices(editor.buffer(), resource));   // null when no engine

document.diagnostics().changeOne(services.id(), compiled.problems());       // under the engine's own id
```

- **Absence is the feature flag**: no engine → grammar colouring, no grammar → keyword lexer, neither → plain text.
- **Per document, never per editor**: split panes share one.
- `LanguageServices.close()` is the only close on the seam; the document owns it (`TextDocumentModel.dispose()`,
  which also closes the tokenizer). `setLanguageServices` unsubscribes, never closes.
- Diagnostics are not on this interface: `services.id()` is the owner key in the document's set.
- Every answer is `Versioned<T>`; the consumer picks the staleness policy: discard (hover, go-to-definition), keep
  adjusted (diagnostics), keep per line (semantic tokens).
- Background analysis pushes with an invalidation range (`SemanticTokenProvider`); a user's question requests with a
  callback that may never fire (`Resolver`, `CompletionProvider`).
- Semantic tokens speak the grammars' capture names (`SymbolKind.captureName()`, pinned by `StyleGovernanceTest`).
- An engine's colouring replaces the grammar's where they overlap (`TextEditor.ensureRowSyntax`).

## Contributing to a view's header

```java
public class ProblemsPanel extends UINode implements HeaderContributor {
    @Override public UINode headerContent() { return tabs; }   // asked once, when mounted
}
```

IntelliJ's tool-window title actions. `ViewContainer` places the element after the title, only for a lone view.
Return an element the view owns, not one built per call.

## Menus, and the menu bar

**A menu is a query, not a list**: `MenuId` names a place, a command declares it belongs there, a renderer asks the
registry what is there now.

### Putting an item in a menu

```java
registry.register(Command.of("edit.save", "Save File")
        .binding("Mod+S")
        .menu(MenuId.MAIN_FILE, "3_save", 10)
        .enabledWhen(...)
        .run(...));
```

| Argument | Meaning |
|---|---|
| `MenuId` | where: `MAIN_FILE`, `MAIN_EDIT`, `MAIN_VIEW`, `MAIN_WINDOW`, `MAIN_HELP`, and nested `MAIN_FILE_NEW`, `MAIN_FILE_RECENT`, `MAIN_VIEW_TOOLWINDOWS` |
| `group` | the section and its sort key (`1_new`, `2_open`); separators fall between groups, never declared |
| `order` | position within the section |

A command may declare several placements.

### Toggles

```java
Command.of("editor.toggleSoftWrap", "Toggle Soft Wrap")
        .menu(MenuId.MAIN_VIEW, "3_editor", 40)
        .toggledWhen(when(TextEditor::isSoftWrap))     // or toggledWhereData(...)
        .run(...)
```

Read when the menu is built, never stored. `isCheckable()` (a mark column) is distinct from `isToggled()`.

### Computed rows — `MenuContributor`

For a list whose length is known only when the menu opens (open editors, recent files). IntelliJ's
`ActionGroup.getChildren`.

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

The command need not be registered; the contributor is called on every open of its own menu, so keep it cheap.

### Reading a menu — `Commands.sections`

```java
List<MenuSection> sections = registry.sections(MenuId.MAIN_FILE, CommandContext.of(source));
```

Rows grouped, in group-then-order, each `MenuEntry.Item` carrying `enabled`, `checkable` and `checked`;
`MenuEntry.Submenu` is listed, not expanded. Disabled rows are included and marked.

### Rendering — `MenuBuilder`

Never build rows against `Menu` directly. `MenuBuilder` holds the rules: separators only between sections; an
unregistered command gets a disabled row; enablement re-checked at activation and the command re-resolved when it
runs; accelerators read live; an empty submenu dropped, a disabled one kept.

```java
Menu menu = MenuBuilder.build(MenuId.MAIN_FILE, registry, source);   // a whole menu
MenuBuilder.appendSections(menu, id, registry, source);              // splice into one you own
MenuBuilder.row(menu, registry, source, "edit.save", null);          // one named row

List<Menu> live = MenuBuilder.present(menu, attachmentSite, window); // attach the whole chain
MenuBuilder.discard(live);                                           // close, then detach
```

`ContextMenu` is a caller of it; never write a second builder.

### The bar — `MenuBarView`

```java
MenuBarView bar = new MenuBarView(Commands.process());
MainMenuCommands.install(bar);      // the six standard titles, or call addMenu yourself
workbench.appendStructural(bar);    // above content; the workbench is a column
```

| | |
|---|---|
| `addMenu(MenuId, "&File")` | `&` marks the mnemonic; `&&` is a literal ampersand |
| `open(id)` / `close()` / `openMenu()` | the bar owns which menu is open |
| `onDidChangeOpenMenu` | the open id, or null |
| Alt+letter | opens from anywhere (capture phase on the window root) |
| Alt held | underlines mnemonics with `::highlight(mnemonic)` |

Commands resolve against the focused element, not the bar. A title's font size goes on its `text` element, since
font-size does not effectively inherit here.

## Cut, copy and paste — one action, many providers

`Edit ▸ Cut/Copy/Paste` ask the position what they mean; a widget joins by answering `UiDataKeys.CLIPBOARD`
(IntelliJ's `CutProvider`/`CopyProvider`/`PasteProvider`):

```java
private final ClipboardActions clipboardActions = new ClipboardActions() {
    public boolean canCut()   { return hasSelection() && !isReadOnly(); }
    public void    cut()      { … }
    public boolean canCopy()  { return hasSelection(); }
    public void    copy()     { … }
    public boolean canPaste() { return … }        // whichever clipboard you mean
    public void    paste()    { … }
};

@Override
public Object getData(DataKey<?> key) {
    if (key == UiDataKeys.CLIPBOARD) return clipboardActions;
    return super.getData(key);
}
```

- Never add another `cut` command; the specific ones (`editor.cut`, `tree.cut`, `graph.cut`) keep their scoped
  bindings.
- `canPaste()` is about the provider's own clipboard, not whether the clipboard is empty.
- No defaults on the interface. Enablement is re-asked at activation.

## The Project explorer

### Editing files in the tree

```java
tree.editWith(workbench);                 // drag, cut, copy, paste, F2 rename, delete over the selection
tree.editing().rename(path);              // F2 on one path
tree.beginNew(parentFolder, /* directory */ false, name -> files().create(…));
```

The verbs are the engine's `TreeEditing` (`widget.collection.tree`); `ExplorerEditModel` performs them through the
file service: one batch, one undo step, a failure named per file.

- A copy never overwrites (`FileOperations.incrementalName`); a move onto a namesake is refused.
- A drop on a file lands in its folder.
- Delete asks (`explorer.confirmDelete`) and closes the deleted files' tabs; unsaved work keeps its tab.
- Cut is performed at paste; the cut rows are dimmed meanwhile.
- Every path out of a paste iteration calls its `batch.track()` runnable, refusals included, or the undo group stays
  open.

Renaming is an input in the row (VS Code's `FilesRenderer.renderInputBox`); `InputDialog` is the fallback with no
tree on screen.

- The editor is built in `createTemplate`, never in `bind`.
- Blur commits; Escape cancels; an invalid name cancels.
- The stem is selected, not the extension.
- `beginNew` expands the folder first.

### Compact folders

`WorkspaceTreeSource.setCompactFolders(boolean)`, on by default.

- `rowLabel(path)`: a project's name, a plain name, or the chain a compacted row stands for.
- `visibleRowFor(path)` maps a path to the row standing for it: reveal, auto-reveal and problem jumps must use it.
- A chain never crosses a root, and stops at the first directory whose listing has not arrived.

### Find

`WorkspaceTreeSource.FindMode.HIGHLIGHT` (default) marks matches; `FILTER` removes the rest. A renderer reads
`isMatch(path)` and `descendantMatches(directory)`, both over what has been listed.
