# Writing Workbench Extensions

**This is the guide for people extending a CrystalGUI workbench, not building one.** A workbench is the
shell — the editor, the dock, the activity bar, the status bar. An *extension* is how anything optional
gets into it: a panel, a file type, a command, a status readout.

Everything the engine itself offers beyond an editor and a dock — the project tree, Problems,
Notifications, the Inspector — arrives through exactly this door, so nothing you write is second-class.
If you want a UI that is *not* part of a workbench, that is [`CGUI_BUILDING_UIS.md`](CGUI_BUILDING_UIS.md).

---

## Contents

1. [The shape of an extension](#1-the-shape-of-an-extension)
2. [Getting it found, and getting it turned on](#2-getting-it-found-and-getting-it-turned-on)
3. [An activity-bar panel](#3-an-activity-bar-panel)
4. [A file type](#4-a-file-type)
5. [Commands, menus and shortcuts](#5-commands-menus-and-shortcuts)
6. [A status-bar entry](#6-a-status-bar-entry)
7. [Decorating files in the explorer](#7-decorating-files-in-the-explorer)
8. [Reporting problems](#8-reporting-problems)
9. [Remembering things between sessions](#9-remembering-things-between-sessions)
10. [Following what the user is doing](#10-following-what-the-user-is-doing)
11. [Settings and scratch files](#11-settings-and-scratch-files)
12. [A whole product](#12-a-whole-product)
13. [Cheat sheet](#13-cheat-sheet)

---

## 1. The shape of an extension

One interface, one moment, one handle back.

```java
public final class MyFeature implements WorkbenchExtension {

    public static final String ID = "mymod:myfeature";

    /** ServiceLoader's rule: public, no arguments. */
    public MyFeature() { }

    @Override public String id() { return ID; }

    @Override
    public Disposable activate(WorkbenchContext workbench) {
        return workbench.registerToolWindow(
                ToolWindowKind.of("myfeature", "My Feature")
                        .icon("mymod:icons/feature")
                        .view(ctx -> new MyPanel()));
    }
}
```

That is a complete extension. Three things are worth knowing before you write more:

**`activate` runs while the workbench is being built.** The tree exists; a window does not. Anything that
needs geometry, a document or a frame waits for one rather than asking here — see
[§10](#10-following-what-the-user-is-doing).

**The handle is the contract.** Whatever you hand back is what the engine disposes, so nothing has to
enumerate what any extension did. Anything registered *on the workbench* needs no handle — it goes when
the workbench does. What needs one is anything **process-wide**: a global command, a static registry
entry. That is exactly the class of thing that otherwise gets left behind.

**One class per feature.** Put `activate` on the thing itself rather than writing a `MyFeatureExtension`
beside it. The two would share one lifetime and one id, and the second file's only real content would be
the first one's name.

Several registrations? Group them:

```java
@Override
public Disposable activate(WorkbenchContext workbench) {
    ConnectionGroup lifetime = new ConnectionGroup();
    Disposable panel = workbench.registerToolWindow(...);
    lifetime.add(workbench.onDidOpenDocument().connect(path -> refresh()));

    return () -> {
        lifetime.disconnectAll();
        panel.dispose();
    };
}
```

---

## 2. Getting it found, and getting it turned on

These are two different things.

**Found** — one line in your jar, and nothing calls it:

```
src/main/resources/META-INF/services/com.crystalgui.workbench.extension.WorkbenchExtension
```
```
mymod.MyFeature
```

**Turned on** — an application's manifest names your id:

```java
WorkbenchApplication.of(context)
        .with(ProjectExtension.ID, ProblemsExtension.ID, MyFeature.ID)
        .start();
```

Shipping the jar makes your feature *available*; a manifest naming its id is what makes it *on*. An id
nothing ships is a **logged absence, not an error** — which is what lets one manifest name a feature that
is simply not present on some hosts. The language stack's scripting support is named that way and is
absent wherever there is no engine band.

> Your extension is written against `WorkbenchContext`, never against `Workbench`. That is what lets it
> live outside `core/` entirely — the Run panel does, and it cannot see the workbench module's classes.

---

## 3. An activity-bar panel

Describe the panel once; the engine derives the rail button, the dock entry, the toggle command with its
accelerator, whether it opens on a fresh workspace, and the badge.

```java
Disposable panel = workbench.registerToolWindow(
        ToolWindowKind.of("myfeature", "My Feature")
                .icon("mymod:icons/feature")
                .region(DockRegion.AUXILIARY)          // left rail is SIDEBAR, bottom is PANEL
                .side(RegionSide.PRIMARY)              // which half of that region
                .view(ctx -> panelInstance)
                .toggle("mymod.showMyFeature", "Mod+Shift+M")
                .openByDefault());
```

| Builder | Means |
|---|---|
| `.icon(name)` | The rail button's glyph. An SVG under `assets/<ns>/ui/icons/`. |
| `.region(...)` / `.side(...)` | Where it opens **by default**. |
| `.anchor(DockDropZone.SPLIT_DOWN)` | The same statement in the dock's own words. |
| `.view(factory)` | Builds the panel. |
| `.view(id, title, factory)` | Call it more than once for a panel with several tabs. |
| `.toggle(commandId[, accel])` | Registers a show/hide command and binds it. |
| `.badge(installer)` | A dot or a count on the rail button. |
| `.openByDefault()` | Open on a workspace with no session record yet. |
| `.persistent()` | Keep the panel's state while it is closed. |

**Build the view eagerly and return the same instance.** The dock caches what a factory answers, so a
placeholder returned "just for this frame" is what it hands back for the rest of the session.

```java
MyPanel view = new MyPanel();                       // eager
... .view(ctx -> view);                             // same instance, every time
```

A badge is an installer rather than a value, because it is a *subscription* — the engine hands you the
sink and keeps the handle, so a withdrawn extension stops writing to a button that is gone:

```java
.badge((ctx, set) -> {
    Connection watch = myModel.onDidChangeCount.connect(
            count -> set.accept(count > 0 ? String.valueOf(count) : null));
    return watch::disconnect;
})
```

> Where a panel opens is a **default, never a rule**. A placement restored from a session outranks all
> three of `region`, `side` and `anchor` — a panel the user dragged to the other rail stays there, which
> is the whole point of persisting one.

---

## 4. A file type

A `DocumentKind` is the whole of what a mod writes to own a file format.

```java
public final class NotesKind implements WorkbenchExtension {

    public static final String ID = "mymod:notes";

    /** Data, so a launcher and an "open with" lookup can read it without activating anything. */
    public static final DocumentKind KIND = DocumentKind.of(ID, "Notes")
            .files(DocumentKind.FilePatterns.extension("notes"))
            .icon("crystalgui:file-text")
            .model((resource, bytes) -> NotesModel.decode(bytes))
            .editor(NotesView::new);

    public NotesKind() { }

    @Override public String id() { return ID; }

    @Override
    public Disposable activate(WorkbenchContext workbench) {
        return workbench.kinds().register(KIND);
    }
}
```

From those declarations: `.notes` files open with this icon, edits are undoable, `Ctrl+S` encodes and
writes with the etag, a change on the server reloads a clean document and marks a dirty one, an unsaved
list survives a quit, and the tab says which file it is — **none of which you write**, because all of it
is the document layer's.

Matching files:

```java
DocumentKind.FilePatterns.extension("notes")     // *.notes
DocumentKind.FilePatterns.name("build.gradle")   // that exact filename
DocumentKind.FilePatterns.glob("**/*.test.js")   // a glob
```

For a text format, `.text(Language)` supplies the model for you. The languages that ship are
`Language.PLAIN`, `JAVA`, `JAVASCRIPT` and `GLSL`; a jar adds its own through a `LanguageKinds`
service, the same way an extension is found:

```java
DocumentKind.of("mymod:recipe", "Recipe")
        .files(DocumentKind.FilePatterns.extension("recipe"))
        .text(Language.PLAIN)           // TextDocumentModel over the bytes, tokenizer bound
        .editor(TextEditorView::new);
```

Other builders worth knowing: `.fallback()` claims anything nothing else matched (at most one kind may),
and `.status(document -> ...)` adds a status-bar contribution while that document is in front.

---

## 5. Commands, menus and shortcuts

A command is one declaration that a keybinding, a menu row and the palette all point at.

```java
CommandRegistry commands = CommandRegistry.global();
commands.register(Command.of("mymod.reload", "Reload Recipes")
        .binding("Mod+Alt+R")
        .menu(MenuId.MAIN_FILE, "5_tools", 10)          // menu, group, order within it
        .run(context -> reload())
        .enabledWhen(context -> workbench.activeFilePath() != null));
```

The registry is **process-wide**, so this is exactly the case that needs a handle:

```java
return () -> commands.unregister("mymod.reload");
```

| Builder | Means |
|---|---|
| `.binding("Mod+S", "F5")` | One or more accelerators. `Mod` is Ctrl, or Cmd on macOS. |
| `.menu(id, group, order)` | Where the row appears. Groups are sorted by name; use `1_`, `2_` prefixes. |
| `.run(ctx -> …)` / `.run(Runnable)` | What it does. |
| `.enabledWhen(ctx -> …)` | Greyed rather than hidden when false. |
| `.toggledWhen(ctx -> …)` | Draws a check. |
| `.when("expression")` | The same as `enabledWhen`, written as a context expression. |

Menu ids you will want: `MenuId.MAIN_FILE`, `MAIN_EDIT`, `MAIN_VIEW`, `MAIN_WINDOW`, `MAIN_HELP`,
`EXPLORER_CONTEXT`, `EDITOR_CONTEXT`, `EDITOR_TAB_CONTEXT`, `PALETTE`.

> **Resolve your subject from the context, never from "whatever is active".** Four routes can reach the
> same command and each arrives with a different `source` element — a menu bar resolves against what is
> *focused*, a context menu against what was *clicked*. Asking the workbench for the active thing is
> right for three of them and silently wrong for the fourth, which is the one the user reached for
> precisely *because* it is not in front.

A panel's own show/hide command needs none of this — `ToolWindowKind.toggle(id)` writes it.

---

## 6. A status-bar entry

```java
StatusBarEntry entry = new StatusBarEntry(
        "Recipes",                       // name, for the settings list
        "42 recipes",                    // the text on screen
        "Recipes loaded from this pack", // tooltip, nullable
        "mymod.reload",                  // command id to run on click, nullable
        StatusBarEntry.Kind.STANDARD);   // STANDARD | WARNING | ERROR

StatusBarEntryAccessor slot = workbench.statusBar()
        .addEntry(entry, "mymod.recipes", StatusBarAlignment.LEFT, 200);
```

The accessor is the handle: `slot.update(entry.withText("43 recipes"))` to change it,
`slot.dispose()` to take it away. Higher priority sits further from the edge.

**Withdraw an entry with nothing to say** rather than showing a permanent zero — the Problems count does,
because a clean workspace is the normal state and a readout that never changes is one you learn to stop
seeing.

---

## 7. Decorating files in the explorer

A colour, a one-letter badge and a tooltip, keyed on a path:

```java
FileDecorationProvider provider = new FileDecorationProvider() {
    @Override public FileDecoration decorationFor(CgPath path) {
        return isDirty(path)
                ? FileDecoration.of(10, "decoration-modified", "M", "Modified")
                : null;
    }
    /** What to bubble up onto folders. */
    @Override public Collection<CgPath> decorated() { return dirtyPaths; }
};

workbench.decorations().addProvider(provider);
return () -> workbench.decorations().removeProvider(provider);
```

Call `workbench.decorations().invalidate()` when your answers change. The colour comes from the style
class, in `decorations.css` — a decoration names a class, never a colour, so a theme can restyle it.

A folder gets the **colour** and not the letter: a folder showing `M` claims the folder itself is
modified, and the split is the entire information content of the bubble.

---

## 8. Reporting problems

Diagnostics are per-resource and per-owner, so several producers can mark one file without fighting.

```java
DiagnosticSet set = workbench.markers().forResource(resource);
set.changeOne("mymod:linter", List.of(
        Diagnostic.onRow(12, DiagnosticSeverity.WARNING, "Unknown ingredient"),
        Diagnostic.error(new TextPoint(20, 4), new TextPoint(20, 9), "Unknown ingredient")));
```

`onRow` marks a whole line; `error`/`warning` take a start and an end point. They reach the Problems
panel, the editor's squiggles and the error stripe with nothing further from you. Withdraw yours with
`set.remove("mymod:linter")` — which touches nobody else's, because the set is keyed by owner.

> A diagnostic is a row and column, and it only means anything against the text your analysis actually
> saw. If you analyse off the frame thread, stamp the version you read and drop a result whose document
> has moved on — see `Versioned`.

---

## 9. Remembering things between sessions

A `SessionSlice` is your corner of the workbench's arrangement record.

```java
public final class MyFeature implements WorkbenchExtension, SessionSlice {

    @Override public String id() { return ID; }        // the slice key IS the extension id

    @Override public void write(StateMap<JsonElement> into) {
        into.putString("filter", currentFilter);
    }

    @Override public void read(StateMap<JsonElement> from) {
        currentFilter = from.getString("filter", "");   // empty map = ordinary first run
    }

    @Override public Disposable activate(WorkbenchContext workbench) {
        return workbench.registerSessionSlice(this);
    }
}
```

The key is your extension id rather than a name of its own, so an application that stops enabling your
feature leaves its corner untouched rather than dropping it.

**What belongs here is arrangement, not content.** A filter, a sort order, which tab was selected. Not
the user's data — that is a file — and not anything another person would see, because a session is per
client. Writing view state into a shared file means one person's camera position becomes everybody's.

---

## 10. Following what the user is doing

`activate` runs before there is a window, so anything that needs one subscribes and waits.

```java
ConnectionGroup lifetime = new ConnectionGroup();

lifetime.add(workbench.dock().onDidChangeActivePanel.connect(ref -> refresh()));
lifetime.add(workbench.onDidOpenDocument().connect(path -> refresh()));
lifetime.add(workbench.documents().onDidOpen.connect(document -> index(document)));
lifetime.add(workbench.markers().onDidChange.connect(resource -> recount()));
```

| Signal | Fires when |
|---|---|
| `dock().onDidChangeActivePanel` | **The tab in front changed.** What you want for "follow the editor". |
| `onDidOpenDocument()` | A file's *content* landed. Not a tab change — it says nothing when you click between two files that are already open. |
| `documents().onDidOpen` | A document was opened, for indexing. |
| `markers().onDidChange` | Diagnostics moved. |

Subscribe to **both** of the first two if you follow the active file: the panel is announced as soon as
the dock has built its tree, which can be before the document behind it exists, and a restored tab's
content arrives some frames later.

Reading what is in front:

```java
CgPath path = workbench.activeFilePath();        // null when what is in front is not a file
Resource resource = workbench.activeResource();
TextEditor editor = workbench.activeEditor();    // null when it is not a text editor
List<CgPath> open = workbench.openPaths();
```

Opening things:

```java
workbench.openFile(path);
workbench.openFile(path, () -> jumpTo(line));    // after the read lands
workbench.openResource(resource);
```

> **Never do slow work on the frame thread.** Opening an archive, compiling, indexing, probing a
> classpath — those are pure functions of a snapshot and belong on `JobScheduler`, whose `onDone` hands
> the answer back on the frame thread. The engine times every provider call and will name yours if it
> costs a frame.

---

## 11. Settings and scratch files

Settings resolve outward from wherever they are asked, so a value read where it is used cannot go stale:

```java
public static final Setting<Boolean> AUTO_RELOAD =
        Setting.bool("mymod.autoReload", "Reload recipes on save", true);

if (workbench.resolve(AUTO_RELOAD)) reload();
```

`Setting` has `bool`, `string`, `integer`, `number` and `select`. Resolve it at the point of use — never
push it onto the engine through a setter, which makes the engine hold a field for you and fires on every
settings change whether or not it concerns you.

For anything on disk that is yours:

```java
Path cache = workbench.cacheDirectory("mymod");   // per host, yours alone
```

That is `crystalgui/cache/apps/<application>/mymod/`, and **it can be deleted at any moment**. Put
compiled output, an index or a thumbnail there; anything a user would miss is not a cache. It is null on
a host with nowhere to write, which is an ordinary answer — an extension that caches nowhere still
works, and a test is exactly that host.

Ask for it while your extension is being activated, not later: extensions activate while the workbench
is being built, and that is when the root is set.

---

## 12. A whole product

An extension adds to somebody's workbench. If you want your *own* application — its own taskbar entry,
its own window, its own set of extensions — declare an `ApplicationKind`.

```java
public static final ApplicationKind KIND = ApplicationKind.of("mymod:recipes", "Recipe Editor")
        .icon("mymod:logo")
        .opens(DocumentKind.FilePatterns.extension("recipe"))
        .singleInstance()
        .launch(context -> WorkbenchApplication.of(context)
                .with(ProjectExtension.ID, ProblemsExtension.ID, MyFeature.ID)
                .title("Recipe Editor")
                .key("recipes:main")
                .policy(WindowPolicy.HIDE_ON_CLOSE)
                .start());
```

Offer it to every desktop with an `ApplicationKinds` service:

```java
public final class MyApplications implements ApplicationKinds {
    @Override public void register(ApplicationRegistry applications) {
        applications.install(KIND);
    }
}
```
```
src/main/resources/META-INF/services/com.crystalgui.desktop.app.ApplicationKinds
```

The manifest is **data**: `opens` is read by "open with" and by a launcher without anything being
launched, so it cannot be derived by building an application and asking what it registered.

---

## 13. Cheat sheet

```java
// The extension itself
public final class MyFeature implements WorkbenchExtension {
    public static final String ID = "mymod:myfeature";
    public MyFeature() { }
    @Override public String id() { return ID; }
    @Override public Disposable activate(WorkbenchContext w) { ... }
}
// META-INF/services/com.crystalgui.workbench.extension.WorkbenchExtension  ->  mymod.MyFeature
// ...then an ApplicationKind names MyFeature.ID in .with(...)
```

| I want to add… | Call | Handle |
|---|---|---|
| An activity-bar panel | `workbench.registerToolWindow(ToolWindowKind.of(...))` | returned |
| A file type | `workbench.kinds().register(kind)` | returned |
| …plus its extensions | `workbench.contribute(kind, "recipe", "rcp")` | with the workbench |
| A command | `CommandRegistry.global().register(Command.of(...))` | **`unregister(id)` yourself** |
| A status entry | `workbench.statusBar().addEntry(entry, id, alignment, priority)` | `accessor.dispose()` |
| An explorer decoration | `workbench.decorations().addProvider(p)` | `removeProvider(p)` |
| Diagnostics | `workbench.markers().forResource(r).changeOne(owner, list)` | `remove(owner)` |
| Session state | `workbench.registerSessionSlice(slice)` | returned |
| A whole application | `ApplicationKind.of(...)` + an `ApplicationKinds` service | — |

**Five things that are silent when you get them wrong**

1. A panel view built lazily — the dock caches the first answer for the session. Build it eagerly.
2. A process-wide registration with no handle — it outlives the workbench and the next one inherits it.
3. Following `onDidOpenDocument` alone — it is not a tab change.
4. `activate` asking for a window, a document or geometry — none exists yet.
5. An extension id in a manifest that nothing ships — a log line, not an error. Check the log if your
   feature simply is not there.

---

## Where to read next

| For | Read |
|---|---|
| Making a UI that is not part of a workbench | [`CGUI_BUILDING_UIS.md`](CGUI_BUILDING_UIS.md) |
| What the services under a workbench offer | [`CGUI_WORKBENCH_SERVICES.md`](CGUI_WORKBENCH_SERVICES.md) |
| Styling anything you build | [`CGUI_STYLE_RENDER_PIPELINE.md`](CGUI_STYLE_RENDER_PIPELINE.md), [`CGUI_THEMING.md`](CGUI_THEMING.md) |
| The widgets you can put in a panel | [`CGUI_WIDGETS.md`](CGUI_WIDGETS.md) |

Worked examples in this repository, in increasing size: `NotesKind` (a file type, one class),
`InspectorExtension` (a panel that follows focus), `ProblemsExtension` (a panel, a status entry and an
index), `ScriptWorkbench` (an extension living outside `core/` entirely).
