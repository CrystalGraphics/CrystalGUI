# plan_workbench_rewrite.md — the application, the engine under it, and the host under that

**Status:** audit v2 — supersedes v1 (`6af157ce`), which answered "should `ScriptWorkbench` exist" and
stopped there. Not started.
**Measured against** `rewrite` @ `6af157ce`, 2026-09-04. Every number below was counted by a script or
`grep`, not estimated, except where a cell says *est.*

---

## 0. The ask, and the answer

Five claims, restated so each can be checked against the tree:

1. **`CrystalEditor` is an application** — the thing a user opens, extends, and sees panels in.
2. **`Workbench` is the engine under it** — docks, regions, tool windows, editors, sessions; no opinion
   about which panels exist.
3. **Several applications can be built on one engine**, each choosing its own panels — so Run,
   Project, Inspector and Problems are not baked into everything.
4. **An application is a general thing the desktop knows about** — it can have an icon, a shortcut, a
   search entry, and file associations, the way an OS treats one. `ShaderGraphEditor` is such a thing
   even though it opens inside `CrystalEditor` today.
5. **Platform seams are minimised**: `core/` owns the logic, and a loader supplies only what is
   genuinely only knowable on that platform. `Mc1710Workspace` should not exist.

**Yes, it makes sense — and the tree is already most of the way there in the wrong places.** The
engine exists (regions, tool windows, dock, sessions, settings, kinds — ~23,000 lines of it are fine);
what is missing is the three concepts that would let it be assembled: an *application*, an *extension*
seam, and a *host* seam. Today those three jobs are done, respectively, by a 391-line constructor, by
whoever remembers to call `install(...)`, and by a Minecraft `GuiScreen` that holds the whole product in
static fields. §1 measures that; §2 names the findings; §3 is the prior art; §4 the design; §5 the
decisions; §6 the priced ledger; §7 the phasing.

One correction to v1 before anything else: it said `Workbench` has *zero* section headers. It has
**five** (`What the index is allowed to see`, `Files`, `Opening things that are not project files`,
`Unsaved changes`, `Lifecycle`), and the content under each does not match its title — the block under
`Lifecycle` holds `disconnected`, auto-reveal, `tick`, the Problems binding, the marker index, the
problem-count entry and presence. A wrong number is worse than none, so it is corrected here.

---

## 1. The measurements

### 1.1 `Workbench` — 3,378 lines, and where they go

| | |
|---|---|
| lines | 3,378 (2.7× the next file in the package; 12.6% of the package) |
| imports | 116 |
| public members | 83 |
| methods | **130**, totalling **1,721** lines |
| the constructor | **391 lines** (792–1183) — the single largest method in the repository |
| comment lines | **1,709 — 50% of the file** |
| section headers | 5, none of which matches what is under it |

The 1,721 method lines cluster by what they are *for*. Measured by method name, so a method in the
wrong section still counts against its job:

| Cluster | Lines | What it is |
|---|---|---|
| lifecycle + constructor | 564 | 391 of them the constructor: it builds and wires eight collaborators, registers three panels, declares the fallback document kind (its editor factory alone is 60 lines), and connects fourteen signals |
| active tab, save, conflict resolution | 283 | `activeFilePath` … `saveActiveFile`, the conflict dialog, the merge dialog |
| opening | 278 | `open`×3, `openFile`, `openResource`, `openFileAt`, `openResourceAt`, bindings, `activeDock` |
| project source index snapshots | 140 | three volatile snapshots for the analysis thread |
| document↔dock glue | 129 | `contribute`, placeholder rebuilds, the failure banner, releasing a closed panel |
| tab presentation | 112 | title/icon/tooltip/decoration/window-title providers |
| tool-window delegates + accessors | 84 | `isPanelOpen`…`showPanel`, sixteen getters |
| problems | 62 | the marker index, the count entry, the Problems binding |
| presence | 45 | the status entry |
| external change | 24 | the badge |

**None of the top four is "the shell".** The constructor is *an application being assembled*; saving
and conflicts are *document* work; opening is an *editor service*; the index is a *language* concern.
The shell — regions, rails, dock, status bar, menu bar, balloons — is about 150 lines of the file.

### 1.2 The product is written three times

The v1 drift table, extended with what each host decides on its own:

| Decision | `CgUiScreen` (mc1710) | `CgUiDesktopScene` | `CgUiDockScene` |
|---|---|---|---|
| Builds `CrystalEditor` | ✓ | ✓ | ✓ |
| Notes kind | ✗ | ✓ | ✓ |
| Scripting | ✓ | ✓ | ✗ |
| Reveal Run panel on start | ✓ | ✗ | ✗ |
| Script cache root | `config/crystalgui/script-cache` | `build/script-cache` | — |
| Editor window title / key / icon / policy | `"Crystal Editor"` / `editor:main` / logo / `HIDE_ON_CLOSE` | same, repeated | (old-engine scene) |
| First-run geometry | 86% of the display, centred | `300,55 → 600×400` | — |
| Project list ask + session restore ordering | on connection change | on first `isConnected()` | ditto |
| Session project id | `Mc1710Workspace.PROJECT_ID` (hard-coded, duplicated server-side) | `HarnessWorkspace.PROJECT_ID` | ditto |
| `giveInitialFocus` | every frame | — | — |
| Window mount binding | `CgUiWindowMount.bind(...)` per frame | — | — |
| Language registration | `Mc1710Workspace` ctor **and** `CrystalGUI.scriptInit` | `HarnessWorkspace` ctor | ditto |

`CgUiScreen.ensureEditorWindow` (57 lines) and `CgUiDesktopScene.openEditorWindow` (30 lines) are the
same paragraph with different constants. The product's definition is whatever the last host to be
edited says it is.

### 1.3 The loader is half core

`mc1710` is 6,099 lines. 2,699 of them are diagnostics (`CgUiAutoTest` 628, seven probes 2,071) and
stay. The **3,400 production lines** split as follows — *est.* by reading each class for what reaches an
MC/LWJGL/FML type versus what does not:

| Class | Lines | Genuinely platform (est.) | Relocatable to `core/` (est.) | What the relocatable part is |
|---|---|---|---|---|
| `CgUiScreen` | 748 | ~200 | **~550** | product assembly, window defaults, session/restore ordering, mount binding, focus, and the application's state in eight `static` fields |
| `CgUiWorkspaceHost` | 403 | ~90 | **~310** | per-peer binding table, change fan-out, presence fan-out, poll cadence, README seed, `forget`/`reset` |
| `CgUiConnections` | 320 | ~80 | **~240** | the peer table, open/close/route/tick, "one peer's exception must not stop the others" |
| `CgUiWindowMount` | 266 | **0** | **266** | names `Desktop`, `WindowFrame`, `ClientWindows`, `ScopedSheets` and nothing from MC |
| `Mc1710Workspace` | 106 | **0** | **106** | "a `Workspace` that follows the current connection" |
| `CgUiHud` | 177 | ~60 | ~117 | the presentation transition tracking and the paint bracket |
| `Mc1710NetworkChannel`, `LaunchWrapperBytes`, `CgUiInput`, `CgUiOverlayInput`, `ScriptService1710`, `Mc1710Peer`, `MixinGuiScreen`, `CrystalGUI`, the proxies, the Machine example | 1,380 | 1,380 | 0 | correct platform code |

**≈1,590 of 3,400 production lines — 47% — are engine or product logic in a loader's package.** Two of
the classes contain no platform reference at all.

What is *genuinely* only knowable on the platform, from reading all sixteen classes:

- the connection and its lifecycle events (join, leave, connect, disconnect, tick)
- the config directory; the server's workspace root; the player identity and the permission model
- display size, the frame clock, raw input events, the GL-state handoff
- when the screen is shown/hidden, and that `initGui` re-runs on a display resize
- key bindings

That list is a `record`, not a package.

### 1.4 Process-wide state two applications would share

A census of mutable statics in `core/` (script: any static field of a collection, signal, or non-final
type, excluding loggers and constants): **76 files, 125 fields**. Most are registries meant to be
process-wide — `WidgetContracts`, `StylePropertyRegistry`, `UIElementRegistry`, interning tables. The
ones that would make a **second application** behave wrongly:

| Static | Effect on a second workbench/application in one process |
|---|---|
| `StatusBar.ENTRIES` | one status bar for the process; `StatusBarView` "reads `StatusBar` itself", so **both applications' bars show both applications' entries** |
| `Notifications.HISTORY`, `unread` | one history; the bell badge on both rails reflects both |
| `DockBanners.PROVIDERS` | a list of lambdas **each closing over one workbench's `editors`** (`Workbench.registerFailureBanner`) or one workbench (`ShaderGraphContribution.register`); every panel build in either workbench asks both |
| `ProjectSourcesRegistry.PROVIDERS` | each workbench contributes its `ProjectIndex`; **never withdrawn**; a resolve asks every workbench that has ever existed |
| `TextEditorView.ACTIVE_ENTRIES`, `caretEntry` | the caret readout is process-wide: two editors in two windows fight over one entry |
| `InspectorRegistry.SECTIONS` + `onDidChangeSubject` | sections are per class (fine); the subject signal is process-wide (fine — it is focus-driven) |
| `CommandRegistry.global()` | by design, resolved through `DataContext` — fine |
| `JobScheduler.shared()`, `UiThread` | by design — fine |
| `ClientWindows.CLIENT`, `Protocols.CONTRIBUTORS` | per process by design — fine |

Four of these are genuine defects for the vision; the first two are also *design* questions (§5 D3, D4).

### 1.5 What retains a `Workbench` or a `CrystalEditor` after it is gone

The reason `HotExitTest` exhausted a JUnit worker's heap with four editors, enumerated:

| Holder | Connected where | Disconnected where |
|---|---|---|
| `Notifications.onDidChange` (static) | `CrystalEditor` ctor, captures `this` | **never** |
| `StatusBar.onDidChange` (static) | `CrystalEditor` ctor | **never** |
| `InspectorRegistry.onDidChangeSubject` (static) | `CrystalEditor` ctor, `this::refreshInspector` | **never** |
| `Notifications.onDidChangeUnread` (static) | `Workbench` ctor, captures `toolWindowManager` | **never** |
| `ProjectSourcesRegistry` (static) | `Workbench` ctor | **never** |
| `DockBanners` (static) | `Workbench` ctor, `ShaderGraphContribution.register` | **never** |
| `Workspace.files().onDidFail`, `.onDidRun` | `Workbench` ctor | **never** — and the `Workspace` is per *connection*, so it outlives every workbench on it |
| `Workspace.presence().onDidChange`, `onDidReconnect` | `Workbench` ctor | **never** |
| `Workspace.watch(root, true)` per project root | `Workbench` ctor, over an **empty list** — so never at all until it was moved to `onDidChangeProjects`; see the correction below | **never** — the returned `Watch` is `Disposable` and dropped, so the *server* keeps a recursive subscription per dead workbench |
| `LanguageRegistry.onCapabilityChanged` | `connected()` | ✓ `capabilityWatch` — the one that is right |

> **Corrected while writing W0.** That row described a leak this tree did not have yet. Roots arrive
> from the project listing, which is asked for from `tickFrame` — after attach, and after a session has
> opened, because the server discards a call naming a window that does not exist. So the constructor's
> loop ran over an empty list on **every host, always**: no workspace-wide watch was ever taken, and the
> explorer never heard about another client's create, delete or rename outside the files it happened to
> have open. Per-document watches are a separate subscription and still arrived, which is what made it
> read as the tree being stale rather than as a subscription that was never made. Fixed before W1, since
> a disposal step that disposes nothing is not a disposal step; `WorkbenchWatchesProjectRootsTest` pins
> it, and it fails against the line being removed.

And the teardown surface that would drop them:

| | |
|---|---|
| `CrystalEditor.dispose()` | body is a comment |
| `Workbench` | not `Disposable` |
| `EditorService.dispose()` | no caller |
| `ScriptWorkbench.close()` | no caller |
| `CgUiScreen.disposeAll()` | **no caller** — its javadoc says "at game shutdown", and nothing runs it at game shutdown either |

### 1.6 What "application" means in the tree today: nothing

The concepts that exist, and what each lacks to be one:

| Exists | Has | Lacks |
|---|---|---|
| `CrystalEditor` | a `Workbench`, two panels, config/session/focus | an identity, an icon, a launch, a manifest of what it enables |
| `WindowFrame` | key, title, icon, policy | any notion of *which application* opened it — so the taskbar groups by window, never by app |
| `UiType` (net.window) | id, tag, factory, guarded client init — **the closest thing to an app manifest here** | is for networked panels only |
| `ServerWindows.openable(type, resolver, presentation)` | "this may be opened on request", with a placement | is server-side, per connection |
| `DocumentKind` | file patterns → model → editor | any notion of which *application* handles it; `DocumentKinds` is per workbench |
| `Desktop` + `WindowRegistry` + `Taskbar` | what is **running** | what **can run**: no launcher, no app list, no search, no associations |
| `QuickPick`, `CommandPalette`, `GoToFile` | search UIs | all workbench-scoped; nothing at desktop level |
| `MachineExampleClient` (F8) | the only "launch" in the tree | is a key binding calling `ClientWindows.requestOpen` |

---

## 2. Findings

Numbered **A1–A15** so the ledger and the phasing can cite them. (The phasing's steps in §7 are
**W0–W8**, a separate series. Both were `W` in the first draft, which made a bare `(W6)` ambiguous
between a finding and a step in the same document — and §4.14 and §4.4 each carried one.) Each
states the fact, the evidence, and why it blocks one of the five claims.

**A1 — The seam is inverted: the loader assembles the product.** `CgUiScreen` decides the editor
window's title, key, icon, close policy and first-run geometry; when to ask for the project list and
restore the session; that scripting is installed and the Run panel revealed; where the script cache
goes; that the workbench is the window mount; that focus goes to the dock. None of those references a
Minecraft type. (Claims 1, 5.)

**A2 — Three incompatible ways to attach a feature, and they have drifted.** Baked into the shell
(`Workbench`'s constructor registers Project/Problems/Notifications), baked into the product
(`CrystalEditor` registers the shader graph and the Inspector), installed by hand per host
(`ScriptWorkbench.install`, `NotesKind.register`). §1.2 is the drift. (Claims 2, 3.)

**A3 — `ScriptWorkbench` is five jobs in one class with a dead `close()`, two compile paths, a null-
means-unavailable return, eleven `Workbench` methods reached directly, and a per-host cache path.**
Unchanged from v1 §3; the fix changes shape under the extension seam (§4.3). (Claim 2.)

**A4 — `Workbench`'s constructor *is* the application.** 391 lines that build, wire and register
everything a product would choose. There is no way to build a workbench without Project, Problems,
Notifications, the project index, presence, the fallback text kind and the file-tree drop handler.
(Claim 3.)

**A5 — No application concept; the desktop knows only windows.** §1.6. A taskbar entry is a window;
two windows of one application are two unrelated entries; nothing can be launched except by a key
binding a mod wrote. (Claim 4.)

**A6 — A tool window is declared in five places.** A `DockPanelDescriptor` (kind, region, side, icon,
closable), a factory (`registry.register`), a `ViewContainerRegistry.addView` for multi-view containers,
a reveal command written by hand (`registerToolWindowCommands` for Problems and Notifications), and a
menu row derived in `WorkbenchMenus`. `RunPanels.install` repeats the shape for Run. The `DocumentKind`
model — one declaration, the engine derives the rest — does not exist for panels. (Claim 3.)

**A7 — Process-wide statics make a second application share the first's status bar, banners, index
and notifications.** §1.4. (Claim 3.)

**A8 — Nothing is disposed, and the retention chain is nine links long.** §1.5. Also why
`CrystalEditor` cannot be constructed in a test. (Claims 1–3: an application that cannot be closed
cannot be launched twice.)

**A9 — The four host classes each decide something they cannot know better than the engine.**
`Mc1710Workspace` is a connection-following `Workspace` and nothing else; `CgUiWindowMount` is a
desktop mount with zero platform references; `CgUiWorkspaceHost` owns the peer table and the fan-out
policy beside the two things only it knows (the root path and MC's permission check);
`CgUiConnections` owns a generic peer table beside the FML events that feed it. (Claim 5.)

**A10 — The client hard-codes the server's project id.** `Mc1710Workspace.PROJECT_ID =
"minecraft.workspace"` with a comment saying it "matches `CgUiWorkspaceHost.PROJECT_ID`"; the harness
has `HarnessWorkspace.PROJECT_ID`. The server already *lists* its projects (`Workspace.projects()`),
so the client is guessing an answer it is handed. A second project on the server has no session.
(Claim 5.)

**A11 — The application's state lives in a `GuiScreen`'s statics, and the one method that frees it is
never called.** `editor`, `uiWindow`, `workspace`, `editorWindow`, `config`, `scripting`,
`projectsAskedOn`, `showEditorOnOpen` — eight statics on a class Minecraft constructs fresh on every
display. `disposeAll` has no caller. (Claims 1, 5.)

**A12 — Notifications are modelled process-wide and drawn as workbench chrome.** `Notifications` is a
static model — correct for a notification centre — but its two surfaces (`NotificationBalloons`,
`NotificationsView`) are built by `Workbench`, so a desktop with no workbench on it shows nothing, and
two workbenches show everything twice. The OS shape is one centre on the *desktop*, with an
application's own view filtered to its source. (Claims 3, 4.)

**A13 — `CrystalEditor`'s javadoc draws the shell/product line and the code holds it in neither
direction.** v1 §4; half of the host's calls on it are `.workbench()`. (Claim 1.)

**A14 — The language module reaches `Workbench` for eleven methods** (`activeEditor` ×6,
`openPaths` ×3, `openFile`, `editorFor`, `documents`, `activeFilePath`, `toolWindowManager`,
`showPanel`, `panels`, `isPanelOpen`, `fileTree`) across four files. That set is the measured
minimum a contribution needs, and it is what `WorkbenchContext` should carry. (Claims 2, 3.)

**A15 — `DockBanners` is a static list of per-instance closures.** Every provider registered
captures a specific workbench; the list is never emptied. A second workbench runs the first's failure
banner against the first's `EditorService` on every panel build. (Claims 3, 8.)

---

## 3. Prior art — how the references separate host, shell, application, engine and extension

The question the vision asks is one every desktop and every IDE platform has answered. The answers
agree more than they differ:

| | Host / platform | Shell (what lists and launches) | Application (identity + manifest) | Engine (what an app is built on) | Extension point |
|---|---|---|---|---|---|
| **macOS** | kernel, WindowServer | Finder, Dock, Spotlight, LaunchServices | `Info.plist`: `CFBundleIdentifier`, `CFBundleDocumentTypes` (associations + Editor/Viewer role), icon; `NSApplication` at runtime | AppKit / `NSDocumentController` | app extensions, plug-in bundles |
| **Windows** | Win32 | Explorer, Start, taskbar, Search | `AppUserModelID` ties *windows* to a taskbar button + jump list; ProgID file associations; Start shortcuts | — | COM, shell extensions |
| **freedesktop / GNOME** | X/Wayland | GNOME Shell: `AppSystem` maps window→app by `WM_CLASS`/`_GTK_APPLICATION_ID`; app grid; search providers | `.desktop`: `Name`, `Icon`, `Exec`, `MimeType`, `Keywords`, `Actions` (jump list), `SingleMainWindow` | GTK `GApplication` | search-provider D-Bus interface |
| **Android** | Linux | launcher, Recents, "open with" | manifest: `<activity>` + intent filters (`VIEW` + mime), icon, label | `Activity`/`Application` | content providers, services |
| **Eclipse RCP** | JVM + SWT | — | `.product` + `IApplication` | workbench: perspectives, views, editors, extension registry | `org.eclipse.ui.views`, `.editors` (file extensions), `.perspectives` |
| **IntelliJ Platform** | JVM | — | a *product* = platform + plugin set (IDEA vs PyCharm are one engine, two manifests) | `Application`, `Project`, `ToolWindowManager`, `FileEditorManager` | `plugin.xml` EPs: `toolWindow` (`id`, `anchor`, `icon`, `factoryClass`, `secondary`, `canCloseContents`), `fileEditorProvider`, `applicationService`, `projectService` |
| **VS Code** | Electron | — | one product; the workbench | `workbench` + services (`IEditorService`, `IViewsService`, `IStatusbarService`, `INotificationService`) | `contributes.viewsContainers` (activitybar/panel, id, title, icon), `views`, `customEditors`, `commands`, `menus` |
| **Qt Creator** | Qt | — | one product | `ICore`, modes | `IPlugin`, `IMode`, `INavigationWidgetFactory` (id, displayName, priority, activationSequence) |

Three conclusions fall out and the design follows them:

1. **Every reference separates "what can run" from "what is running".** The shell keeps a registry of
   installed applications and maps each window back to its application. That mapping is what makes
   taskbar grouping, jump lists, "open with" and search possible — and it is one field on the window
   (`AppUserModelID`, `WM_CLASS`). The desktop here has the *running* half (`WindowRegistry`) and
   nothing of the other.
2. **A product is an engine plus a manifest.** IDEA and PyCharm are the proof that "different
   applications with different default panels" is one engine and two lists. Nobody builds a second
   workbench to get a second product.
3. **An extension point is one declaration per thing, from which the engine derives the rest.**
   IntelliJ's `toolWindow` EP and VS Code's `viewsContainers`/`views` both take an id, an anchor, an
   icon and a factory, and both derive the button, the menu row, the toggle command, the persistence
   key and the badge. `DocumentKind` already does this for files; §4.4 does it for panels.

And the platform seam: **every reference gives the host a small, named surface.** GTK's `GApplication`
gets `startup`/`activate`/`open(files)`/`shutdown`; Android's `Activity` gets a handful of lifecycle
callbacks; Eclipse's `IApplication` gets `start(context)`/`stop()`. The host does not assemble the
product; it calls into one object with the few facts it owns.

---

## 4. The design

### 4.1 Five tiers, top-down

```
Host        a loader / the harness: supplies HostServices, drives frames, forwards input and events
Shell       Desktop: compositor + taskbar + launcher + notification centre + search  (desktop/)
Application an installed thing with an id, icon, manifest, launch; owns its windows      (app/ + desktop.app)
Engine      Workbench: regions, rails, dock, editors, documents, sessions, settings     (workbench/)
Extension   one declaration per feature, activated per workbench against WorkbenchContext
```

`core/` owns all five. A loader implements `HostServices` and nothing else.

### 4.2 The application

```java
/** What an installed application IS — freedesktop's .desktop entry, macOS's Info.plist. */
public final class ApplicationKind {                                  // desktop.app
    public static ApplicationKind of(String id, String displayName);   // "crystalgui:editor"
    ApplicationKind icon(String iconName);
    ApplicationKind keywords(String... searchTerms);
    ApplicationKind category(String category);
    ApplicationKind opens(DocumentKind.Matcher... files);              // file associations
    ApplicationKind action(String id, String label, Consumer<Application> run); // jump-list actions
    ApplicationKind launch(Function<LaunchContext, Application> factory);
    ApplicationKind singleInstance();                                  // SingleMainWindow
}

/** One running instance: its main window and everything it owns. */
public interface Application extends Disposable {
    ApplicationKind kind();
    WindowFrame mainWindow();
    void open(Resource resource);        // "open this file with me"
    void activate();                     // bring forward
}

public final class ApplicationRegistry {   // per Desktop; what the launcher lists
    Disposable install(ApplicationKind kind);
    List<ApplicationKind> installed();
    Application launch(ApplicationKind kind, LaunchContext ctx);
    List<Application> running(ApplicationKind kind);
    @Nullable ApplicationKind handlerFor(Resource resource);           // LaunchServices
}
```

`WindowFrame` gains `setApplication(ApplicationKind)` — the `AppUserModelID`. From it: the taskbar
groups a window under its application's icon; the switcher can cycle applications; a jump list is the
kind's `action`s plus recent files; the launcher lists `installed()`; "open with" reads `handlerFor`.

`LaunchContext` carries what a launch is handed: the `Desktop`, the `Workspace` (if connected), the
`ConfigStorage`, and optional arguments (a file to open, an action id). It is the `Exec` line's
arguments and the intent's extras.

**Where the shader graph fits (claim 4):** the package contributes a `DocumentKind` (any workbench
application can open `.shadergraph`) *and* an `ApplicationKind.action("new-graph", ...)` on the editor
— a launcher entry "Shader Graph" that launches `CrystalEditor` with a new graph, the way a `.desktop`
`Action` or a Windows jump-list task does. It is not a second shell. If a graph-only product is ever
wanted, it is a second `ApplicationKind` whose manifest enables `ExplorerExtension + ShaderGraphExtension
+ InspectorExtension` — a list, not a class (§3, conclusion 2).

### 4.3 The extension seam

```java
public interface WorkbenchExtension {
    String id();                                          // "crystalgui:problems"
    Disposable activate(WorkbenchContext workbench);      // everything it registers, in one handle
}

public final class WorkbenchExtensions {                  // process-wide, like ContentProviders
    public static Disposable contribute(WorkbenchExtension extension);
    public static List<WorkbenchExtension> all();
    public static @Nullable WorkbenchExtension byId(String id);
}
```

An application's manifest says which extensions it enables:

```java
public static final ApplicationKind CRYSTAL_EDITOR = ApplicationKind.of("crystalgui:editor", "Crystal Editor")
        .icon("crystalgui:logo")
        .opens(FilePatterns.extension("shadergraph"), FilePatterns.extension("java"), ...)  // derived from the kinds its extensions declare
        .launch(ctx -> WorkbenchApplication.of(ctx)
                .with("crystalgui:explorer", "crystalgui:problems", "crystalgui:notifications",
                      "crystalgui:presence", "crystalgui:project-sources",
                      "crystalgui:shadergraph", "crystalgui:inspector", "crystalgui:notes",
                      "crystalgui:scripting")
                .title("Crystal Editor").key("editor:main").policy(WindowPolicy.HIDE_ON_CLOSE));
```

`with(String...)` resolves ids against `WorkbenchExtensions`; an id nothing contributed is a **logged
absence**, not an error — that is the three-tier degradation the language stack already follows, and it
is what lets `crystalgui:scripting` be listed on a host with no engine band. A registered-but-not-listed
extension is simply not activated: the harness's Notes kind and mc1710's Run panel stop being a
question of which host remembered what.

**`WorkbenchContext`** is the narrow surface an extension is written against — measured from what
`language/` uses (A14) plus what the built-ins need once they are extensions:

```java
public interface WorkbenchContext {
    // the engine's registries
    ToolWindows toolWindows();          // §4.4 — register kinds, show/hide/toggle, badges
    WorkspaceProjects projects();       // §4.11 — the listing model, promoted out of the explorer
    DocumentKinds kinds();
    EditorService editors();
    WorkspaceDocuments documents();
    Workspace workspace();
    CommandRegistry commands();          // global, but this is where a contribution asks
    StatusBar statusBar();               // PER WORKBENCH — §5 D4
    Markers markers();
    FileDecorations decorations();
    Settings settings();
    // what is active
    @Nullable Resource activeResource();
    @Nullable Document activeDocument();
    @Nullable TextEditor activeEditor();
    @Nullable TextEditor editorFor(Resource resource);
    List<Resource> openResources();
    // opening
    void open(Resource resource, @Nullable Runnable onOpened);
    void openAt(Resource resource, @Nullable TextPoint at);
    DockLeaf open(DockInput input, DockPlacement placement, DockOpenOptions options);
    // services
    JobScheduler jobs();
    @Nullable Path cacheDirectory(String name);   // null on a host with no private directory
    UIDocument document();                        // may be null before attach
    Disposable disposer();                        // what activate() registers into
}
```

`Workbench implements WorkbenchContext`. `LayeringTest` gains a case: nothing under `app/` or
`language/` may name `com/crystalgui/workbench/Workbench` — only the context. That is the enforcement
v1 proposed and it is the whole reason the interface exists rather than the class being handed over.

> **Shipped at W2, with three deviations worth writing down.** The interface carries **today's names**:
> `toolWindowManager()` rather than `toolWindows()` — which is not caution, the name is already taken by
> the accessor answering a `ToolWindowLayout` — and no `statusBar()`, because a status bar is still a
> process-wide static until D4. `disposer()` is absent because `activate` returning a `Disposable` made it
> redundant. And the surface was **measured rather than designed**: the union of what the outside actually
> calls is seventeen methods from `app/`, eleven from `language/`, eight from the harness and three from
> the loader; `decorations()` is on it because `RunPanels` reaches `fileTree().getDecorations()`, which was
> the last thing `language/` wanted that the context did not carry.
>
> **`LayeringTest` gained the rule for extensions only.** `NotesExtension`'s class file names
> `WorkbenchContext` and not `Workbench`, asserted from the constant pool. The full rule — nothing under
> `app/` or `language/` may name the engine — is not yet assertable: `CrystalEditor` holds a `Workbench`
> field and the Run shell names it in four files, which is W6's and W7's port. An assertion that fails, or
> one that is `@Ignore`d, would be worse than the narrow one that passes.

### 4.4 `ToolWindowKind` — the panel API, shaped like `DocumentKind`

```java
public static final ToolWindowKind PROBLEMS = ToolWindowKind.of("crystalgui:problems", "Problems")
        .icon("crystalgui:toolwindows/problems")
        .region(DockRegion.PANEL)                       // default only; ToolWindowState overrides
        .side(RegionSide.PRIMARY)
        .view(ctx -> new ProblemsPanel())               // one view, or:
        // .view("outline", "Outline", ctx -> ...)     // a second view in the same container
        .toggle("workbench.showProblems", "Alt+6")      // command + menu row + accelerator, derived
        .badge(ctx -> ctx.markers().onDidChange, ctx -> countOrNull(ctx.markers()))
        .openByDefault()
        .persistent();                                  // SESSION_PERSISTENT on the element
```

`ctx.toolWindows().register(kind)` → `Disposable`. From that one declaration the engine derives what
today is spread across five places (A6): the `DockPanelDescriptor` (kept as the *compiled* form —
§5 D9), the factory, the `ViewContainerRegistry` views, the stripe button, the `View ▸ Tool Windows`
row, the toggle command in the palette with its accelerator, the session record keyed by id, and the
badge subscription. A second extension may add a view to an existing container by id
(`ToolWindowKind.viewInto("crystalgui:problems", ...)`), which is VS Code's `views` contribution into
somebody else's container.

Mapping to the references, so a reader from either recognises it: IntelliJ `toolWindow` EP — `id`,
`anchor`→`region`, `icon`, `factoryClass`→`view`, `secondary`→`side`, `doNotActivateOnStart`→ the
absence of `openByDefault`. VS Code — `viewsContainers.activitybar` → a kind with `region(SIDEBAR)`,
`views` → `view(...)`, `viewsWelcome` is not ported.

### 4.5 The engine — `Workbench` decomposed by the clusters in §1.1

| Today's cluster | Lines | Becomes | Where |
|---|---|---|---|
| constructor (391) + `connected`/`disconnected`/`tick` | 564 | `Workbench` keeps ~250: build the fixed chrome (menu bar, content row, rails, regions, status bar, balloons slot, drop overlay), own the services, drain extensions, dispose | `workbench/` |
| opening (278) | 278 | `WorkbenchOpener` — the `open`/`openFile`/`openResource`/`openAt` family, bindings, `refFor`, `activeDock` | `workbench/editor/` |
| active + save + conflict (283) | 283 | `SaveActions` (save, save all, overwrite, the conflict and merge dialogs) — a *document* concern that today lives on the shell | `workbench/editor/` |
| tab presentation (112) + doc↔dock glue (129) | 241 | `DocumentTabs` — the seven registry providers, placeholders, the failure banner, release on close, follow-rename | `workbench/editor/` |
| project index snapshots (140) | 140 | `ProjectSourcesExtension` — registers into `ProjectSourcesRegistry` and **withdraws** | `workbench/ext/` |
| problems (62) + marker index | 62 | `ProblemsExtension` — the `ToolWindowKind`, the marker index, the count entry | `workbench/ext/` |
| presence (45) | 45 | `PresenceExtension` — the status entry | `workbench/ext/` |
| external change (24) + explorer wiring in the ctor | ~120 | `ExplorerExtension` — the tree, its drop handler, the root watches (**disposed**), the change badges, the reconnect hook | `workbench/ext/` |
| notifications wiring in the ctor | ~20 | `NotificationsExtension` — the history tool window and the bell badge; the *balloons* move to the desktop (§5 D3) | `workbench/ext/` |
| the fallback `File` kind (60-line factory in the ctor) | ~80 | `TextFileKind` — a `DocumentKind` declared in one place, registered by the engine because every workbench can open text | `workbench/editor/` |
| tool-window delegates + accessors (84) | 84 | stay, as `WorkbenchContext`'s implementation | — |

**The price is measured, not guessed.** §6 of `plan_m6.md` records that three static counts of a split
were wrong, one by a factor of two, and that the only honest instrument is a scratch tree plus `javac`
(`tools/port/pricesplit.py` — deleted since M6 and rebuilt at D24). W5 in the phasing runs it before committing to these boundaries.

### 4.6 Process-wide → per-scope (A7, A12, A15)

| Today | Becomes | Why |
|---|---|---|
| `StatusBar` static | an instance on `WorkbenchContext`; `StatusBarView` is handed one | a status bar belongs to a window; two applications cannot share one line of text |
| `Notifications` static model | stays static — it is the desktop's notification centre — but `Notification` carries a `source` (application id), and `NotificationBalloons` + the bell move to `Desktop` | one centre, many applications; a desktop with no workbench still shows balloons |
| `DockBanners` static list | `DockBannerProviders` **on the `DockPanelRegistry`**, so a provider lives with the registry it answers for | every provider today captures one workbench |
| `ProjectSourcesRegistry.contribute` | unchanged API; the extension keeps the `Disposable` and withdraws on deactivate | never withdrawn today |
| `TextEditorView.ACTIVE_ENTRIES` | per `StatusBar` instance, which is per workbench | two editors fight over one caret entry |
| `InspectorRegistry` | unchanged | sections are per class; the subject is focus-driven |

### 4.7 Disposal (A8)

`Workbench implements Disposable`; every extension's `activate` returns the `Disposable` holding what it
registered; `WorkbenchApplication.dispose()` disposes its workbench, its window and its session; the
host calls `DesktopHost.dispose()` at shutdown. The acceptance test is the one `HotExitIsWiredTest`'s
javadoc says could not be written: **construct and dispose four `CrystalEditor`s in one JVM and assert
`Disposer.liveCount()` returns to its starting value and every static signal's `connectionCount()` is
what it was**. That test is written *first* (W0) so every later step is measured against it.

### 4.8 The host seam (claim 5) — what a loader supplies, and what moves

```java
/** Everything a platform genuinely knows and the engine cannot. A record, not a package. */
public interface HostServices {
    Path configDirectory();
    float uiScale();
    @Nullable ProtocolConnection<Object> connection();   // re-asked per frame; null when not in a world
    Signal.Action onConnectionChanged();
}

/** The one object a loader talks to. Owns the UIDocument, the Desktop, storage, the app registry,
 *  the connection-following Workspace, and the mount. */
public final class DesktopHost implements Disposable {          // desktop.host
    public static DesktopHost create(HostServices services);
    public UIDocument document();
    public Desktop desktop();
    public ApplicationRegistry applications();
    public @Nullable Workspace workspace();                      // follows services.connection()
    public void frame(float delta, int widthPx, int heightPx);   // pump the workspace, bind the mount, frame the document
    public void shown();  public void hidden();                  // initGui / onGuiClosed
    public DesktopPresentation presentation(boolean ourScreenIsUp, boolean anyScreenIsUp);
}
```

What that deletes or shrinks, per class (details in §6.4):

| Class | Today | After |
|---|---|---|
| `Mc1710Workspace` | 106 | **deleted** — `DesktopHost.workspace()` is the connection-following workspace, and `Workspace.rebind` already exists |
| `CgUiWindowMount` | 266 | **moved** to `net.window.DesktopWindowMount(UIDocument)` unchanged; the loader has no mount class |
| `CgUiConnections` | 320 | `net.protocol.Connections` (core, ~230) holds the peer table, open/close/route/tick and the "one peer's failure" rule; the loader keeps `Mc1710Connections` (~90): the FML events, the channel, `Mc1710Peer`, translated into six calls |
| `CgUiWorkspaceHost` | 403 | `fs.server.WorkspaceHost` (core, ~300): per-peer bindings, fan-out, presence, poll cadence, seed, `forget`; the loader keeps `Mc1710WorkspaceHost` (~90): the root path, `OperatorsMayWrite`, `actorFor`, and a tick forward |
| `CgUiScreen` | 748 | ~200: `GuiScreen` plumbing, input pump, GL handoff, `initGui`/`onGuiClosed` → `host.shown()/hidden()`, F6/F7 → `host.applications().launch(...)`/`desktop.activate` |
| `CgUiHud` | 177 | ~90: the two Forge hooks and the input drain; the presentation-transition logic moves beside `Desktop.presentation` |

The measured claim: **mc1710 production code goes from 3,400 to ≈1,800 lines (est.)**, and the
remaining 1,800 reference a Minecraft, FML or LWJGL type in nearly every method — which is the
definition of a platform seam that is only a seam.

The harness gets the same: one `HarnessHost implements HostServices` (in-process transport, the
scratch project, the script policy — today's `HarnessWorkspace` plus twenty lines) and every scene that
wants an editor calls `DesktopHost.create(host)` and `applications().launch(CRYSTAL_EDITOR)`. The three
scene-specific assemblies in §1.2 go.

### 4.9 The session — who owns it, and what it is keyed by (A10)

**The engine serialises it and the record does not change shape.** `WorkbenchSession` keeps writing
what it writes today: the dock layout (every leaf, split weight and tab), every tool window's placement
(region, side, weight, order, docked/floating/windowed, floating bounds), torn-out editor windows and
their trees, per-tab view state (caret, scroll, folds), the active file, the expanded folders, opted-in
widget state by element id, and the networked-panel manifest. Panel positions and sizes, open files and
what was in front are the engine's record and stay so. What changes is three things around it:

**(a) The key — and v1 of this section was wrong about it.** It proposed restoring "one record per
listed project". A session record describes a *workbench* — one dock, whose tabs may come from any
project — so one record per project over one dock would restore N layouts onto one screen. What the
record has always described is the **workspace** (the server's set of projects); it was merely *named*
after one project because the client held a constant. So it is keyed by **(application id, workspace
identity)**:

- application id, because two applications sharing one `ConfigStorage` have different layouts for the
  same workspace;
- workspace identity: **`FsHello` gains a `workspaceId`** — the world's in single-player, the server's on a dedicated server, see §4.13 — additive, so no protocol bump (`FsHello`'s
  own javadoc: *"a field a client has never heard of costs it nothing"*). The server derives it once
  and persists it beside the root it serves, so it survives a rename of the directory the way a project
  id survives a move. A client talking to an older server falls back to a
  hash of the sorted project ids it is listed — VS Code's multi-root workspace id, computed the same
  way for the same reason.

`Mc1710Workspace.PROJECT_ID` and `HarnessWorkspace.PROJECT_ID` go from the client. Per-project state
is not lost: every `files[]`, `expanded[]` and tool-window entry names its project in its path, and a
second project on the server now appears in the *same* record instead of having no session at all.

**(b) Slices come from extensions.** `expanded` and its listing-by-listing retry belong to the explorer,
and once the explorer is an extension `WorkbenchSession` cannot reach `fileTree()`. So an extension may
declare a `SessionSlice`:

```java
public interface SessionSlice {
    void write(StateMap<JsonElement> into);                      // at save
    void read(StateMap<JsonElement> from, WorkbenchContext ctx);  // after the engine's own restore; projects().onDidLoadListing is there for retries
}
```

written under `"extensions": { "<extension id>": {...} }` in the same record — IntelliJ's
`PersistentStateComponent`, one per component, in one file. Widget-level `SessionState` (opt-in by
element id, `Attribute.SESSION_PERSISTENT`) stays as the codeless second channel; the Run panel already
uses it and needs no slice. No version bump: the key is additive.

**(c) Who triggers.** `WorkbenchApplication` restores when the workspace greets and the project list has
landed — the same ordering `CgUiScreen` enforces today, moved into the engine — and saves on
`disconnected()`, which is what a window going off screen already fires. The application owns the
storage and the moment; the engine owns the bytes.

**The desktop's record is a different file and stays one.** `desktop.<host>.json` holds top-level window
geometry by key and the MRU order, per host; the project session holds a floating tool window's bounds,
because those are about the workbench. That is the ONE FACT, ONE RECORD rule already in `AGENTS.md`,
unchanged. It gains one list: which applications were running, so `DesktopHost` can relaunch the ones
whose kind says `restoreOnStart`.

### 4.10 What `CrystalEditor` becomes

A manifest (§4.3's `CRYSTAL_EDITOR`), plus `WorkbenchApplication` — the runtime base every workbench
application shares: build a `Workbench`, activate the listed extensions, open the main `WindowFrame`
with the window as its `setApplication`, own `ConfigStorage`, `WorkbenchSession` (keyed per §4.9), preferences, initial
focus, `WindowChrome`, and dispose all of it. `CrystalEditorCommands` (97 lines) is unchanged.
`CrystalEditor` as a class is **~60 lines**: the manifest constant and the three application-specific
choices (the status flattening for a host that binds one line, the default layout, which extensions).

---

### 4.11 How a panel is fed the workspace

The question every extension asks first, and the one the current tree answers by ownership rather than
by service: the workspace's **listing model** — `WorkspaceTreeSource` — is constructed by
`ProjectFileTree` and read by five classes outside the explorer (`Workbench` at 8 sites for the crawl,
roots and captions; `WorkbenchSession` at 3 for the expansion retry; `WorkbenchSettings` at 6;
`GoToFile`; `CrystalEditor`). It is already a shared service in everything but who owns it, and an
explorer that becomes an extension cannot go on owning what the engine, the session and Go to File read.

So it splits along the line its own surface already shows:

| `WorkspaceTreeSource` today (864 lines) | Becomes | Owner |
|---|---|---|
| `loadProjects`, `roots`, `children`, `listedChildren`, `ensureListed`, `isListed`, `invalidate(All)`, `indexStep`, `knownFiles`, `indexRevision`, `roleOf`, `sourceRootsOf`, `displayNameOf`, `soleProjectName`, `isDirectory`, `onDidLoadListing`, `failure` | **`WorkspaceProjects`** (~380 *est.*) — the listing cache, the crawl, the roots | the engine: `ctx.projects()` |
| sort order, compact folders, filter, find mode, pending-new rows, `rowLabel`, `visibleRowFor`, the `TreeSource` adaptation | **`ExplorerTreeSource`** (~480 *est.*) — a view over the above for a `ListView` | the explorer extension |

From there the feed contract is a table an extension author can read once:

| A panel needs | It asks | It hears about changes from | Before the connection exists |
|---|---|---|---|
| the projects and their roots | `ctx.projects().roots()`, `.entries()` | `onDidChangeProjects` | empty; the **engine** asks on greet |
| a directory's listing | `ctx.projects().children(dir)`, `ensureListed(dir)` | `onDidLoadListing(dir)` | empty until listed |
| every file crawled so far | `knownFiles()`, `indexRevision()` | the revision, per frame | empty |
| a file's bytes | `ctx.workspace().read(resource)` → `Reply<Content>` | the continuation, on the frame thread | fails with an `FsError` the panel shows |
| an open document / a tab | `ctx.documents()`, `ctx.editors()` | `onDidOpen`, `onDidChangeState`, `onDidClose` | — |
| what changed on the server | `ctx.workspace().watch(root, true).onChanged` — **registered into `ctx.disposer()`** | per change; `Workspace.rebind` re-issues every watch itself | none until connected |
| who is in a file | `ctx.workspace().presence()` | `onDidChange` | nobody |
| problems, decorations | `ctx.markers()`, `ctx.decorations()` | `onDidChange`; `addProvider` + `invalidate` | empty |
| what the workspace declares | `ProjectSourcesRegistry` (process-wide, as today) | `environmentChanged` on each editor's services | — |

Four rules go with it, each of which the current tree pays for somewhere:

1. **A panel is built at activation, which may be before a connection exists** — the title screen, and
   the harness before its session has a window id. It renders its empty state and subscribes. It
   **never polls** `isConnected`: the one ask for the project list moves from `CgUiScreen`'s
   `projectsAskedOn` into `Workbench`, keyed on `Workspace.onDidGreet` and the connection identity —
   once per wire, re-asked on a new wire, never on a re-open of the same one.
2. **A panel holds the `Workspace`, never the connection.** `Workspace.rebind` keeps the object across
   a reconnect and re-issues the watches; `onDidReconnect` marks the listings stale
   (`projects().markStale()`, today `fileTree::markListingsStale`), and a hidden panel re-fetches on the
   frame it comes back, not before — the two deadlines `AGENTS.md` already records.
3. **The frame thread owns the tree.** A `Reply` continuation lands on it; anything a panel computes
   from a snapshot goes through `ctx.jobs()`; the `ProjectSourcesExtension` keeps its three volatile
   snapshots for exactly the reason the comments in `Workbench` give at length.
4. **Extensions do not depend on each other's panels.** Scripting decorates the tree's rows through
   `ctx.decorations()` — the engine's — and never through the explorer; a second view into somebody
   else's tool window goes through `ToolWindowKind.viewInto(id)`. Activation is in manifest order, and
   an extension that needs another's *service* uses a process-wide registry, as `ProjectSourcesRegistry`
   already is.

> **Shipped at W2 as an INTERFACE, not a move.** `fs.client.WorkspaceProjects` names the listing model
> and `WorkspaceTreeSource` implements it, so `ctx.projects()` answers a service rather than a widget's
> field and every consumer outside the explorer is written against the smaller thing today. The physical
> split of the 864-line class is left to W5, where it is priced with the rest of the `Workbench` split:
> the seam is what an extension needs, and moving 380 lines is what a package boundary needs — doing the
> first without the second costs nothing and unblocks W4 and W6.
>
> One cost is real and worth stating: the engine's idiom is a `public final Signal` field, and an
> interface cannot carry one, so `onDidLoadListing()` and `onDidChangeProjects()` are accessors. They are
> the only signals in this stack reached through a call.

### 4.12 What belongs to the workspace, the application, the desktop, and the process

The vision's own premise — *a workspace is very important to a workbench* — cuts one way the design
above had not followed through: two applications on **one** workspace must share what is about the
workspace. Today everything below is per `Workbench`, because there has only ever been one.

| Per **workspace** (one per connection; shared by every application on it) | Per **application** | Per **desktop** (host) | Per **process** |
|---|---|---|---|
| `Workspace` (files, watches, presence, capabilities, backup, local history — already here), now a facade over its **sources** (§4.13) | the `Workbench` and its chrome | the `UIDocument`, the `Desktop`, the taskbar, the launcher, the notification centre | `WorkbenchExtensions`, `DocumentKind`/`ToolWindowKind` *declarations*, `CommandRegistry.global()`, the widget/style registries, `JobScheduler` |
| **`WorkspaceDocuments`** — one document per resource *across applications* (IntelliJ's `FileDocumentManager` is application-level; VS Code has one model per URI). Today it is built in `Workbench`'s constructor, so two applications would hold two documents, two undo stacks and two etags for one file and conflict with each other | `EditorService` (tabs are a view), `DockPanelRegistry`, `ToolWindowManager`, `WorkbenchSession`, `StatusBar`, the settings *scope* | `DesktopSession`, the theme (§5 D21), `ApplicationRegistry` | `ScriptRuntimes` — the engine band is opened once (`EngineHost.shared`), so the runtimes are refcounted across applications, never opened per application |
| **`WorkspaceProjects`** (§4.11) — the listing cache and the crawl; two applications crawling one server is the double cost the `WatchHub` was written to remove on the other end | `DocumentKinds` — which *kinds* an application enables; a document already open under another application's kind answers `CONFLICT`, which `WorkspaceDocuments.open(resource, preferredKindId)` already does | | |
| **`ProjectIndex`** — what the workspace declares; contributed once per workspace, withdrawn with it | `Markers`, `FileDecorations` — what *this* application shows about files | | |

The rule that decides a borderline case: **if two applications on the same server would disagree about
it, it is theirs; if they would merely duplicate it, it is the workspace's.** `WorkspaceDocuments`,
`WorkspaceProjects` and `ProjectIndex` therefore hang off the `Workspace` (all three are UI-free, so
`fs.client` can own the first two and `text.lang` the third, which keeps the `headlessTest` line
where it is), and `WorkbenchContext.documents()`/`projects()` answer the workspace's. `DocumentKinds`
stays per application and is passed at *open* time rather than at the store's construction.

### 4.13 Projects — many per source, and a scope that is only a choice in single-player

The single hard-coded project (`minecraft.workspace` at `<installation>/crystalgui/workspace`, on both
ends) was a test fixture. The model is IntelliJ's: **authorised users create as many projects as they
like**, each a directory with a manifest, and creation is a server-side operation. The two scopes
resolve into something the tree already has a word for — a *source* that serves a root:

| Where the client is | Sources it sees | Root each serves | Scope choice at creation |
|---|---|---|---|
| **Title screen** (no world — D16) | its own local source | `.minecraft/crystalgui/` | none: the only place |
| **Single-player** (integrated server) | its own local source **and** the integrated server | `.minecraft/crystalgui/` (GLOBAL) and `saves/<world>/crystalgui/` (WORLD) | **GLOBAL or WORLD** — the one place the option is shown; WORLD preselected |
| **Dedicated server** | its own local source (read/edit — §4.14 for scripts) **and** the server | `.minecraft/crystalgui/` and `<serverdir>/crystalgui/` — a dedicated server has one world, so it has one root and no WORLD scope | none: New Project creates on the server, for actors it authorises; the scope option is hidden |

Three things follow, each simpler than the version this section replaced:

- **A server always serves exactly one root.** Integrated: the world's `crystalgui/`. Dedicated: the
  server directory's `crystalgui/`. `WorkspaceHost` (§4.8) takes one root and a display label — "World"
  or the server's name — and the loader decides which (`MinecraftServer.isDedicatedServer()` is the one
  platform fact). Today's `server.getFile("crystalgui/workspace")` is already this shape, one level too
  deep.
- **The client always serves its own GLOBAL root locally**, through the protocol (`WorkspaceService`
  over `LocalFileSystem`, in-process — the `HarnessWorkspace` shape). So "scope" needs no field on the
  wire: in single-player, choosing GLOBAL or WORLD chooses **which source** the `fs/createProject`
  request goes to, and every source is a server. The verb carries `{ id, displayName, sourceRoots? }`
  and nothing about scope. `ProjectEntry` gains only the source's *label*, so the explorer can group
  roots as *Global* / *World* / *the server's name*.
- **A project is a directory with a manifest**, `crystalgui/<project>/project.json` (`id`, `displayName`,
  `sourceRoots`, `excludes`), listed by a `ScanningProjectProvider` (core, `fs.project`) over the root
  it is given — so creating and discovering are one mechanism on every source. Ids are unique per
  source; a collision across sources (a local `notes` and a server `notes`) is resolved by the client
  prefixing the local source's ids on the wire boundary, once, so saved sessions and paths stay stable.
  Today's `crystalgui/workspace` is **adopted in place** by writing its manifest on first sight, id kept,
  so nothing anybody has is lost.

**The client's `Workspace` becomes a facade over sources**, routed by project id: `fs/projects` merges
the sources' listings, `FileOperations`, watches, presence and documents route by the project a path
names. Phase 4's principle survives in the form that mattered — the client holds no handle to the
*server's* files, and its own files go through the protocol too, so etags, trash, watches and presence
apply to both without a second code path.

**Workspace identity (§4.9)** is the server source's: the world's id in single-player
(`saves/<world>/crystalgui/world.id`, written on first serve), the installation's on a dedicated server
(`<serverdir>/crystalgui/installation.id`), and a fixed local id on the title screen. The local source
needs none — it is always this machine. A session record is therefore per world in single-player and
per server otherwise, and the local projects' tabs ride in whichever record was open.

**Deliberately not offered:** creating a *local* project while connected to a dedicated server. Global
projects are made in single-player or on the title screen and follow the player to servers; the New
Project dialog on a dedicated server targets the server only. One `if` to relax later if it turns out
to be wanted, and hiding it now is what keeps "creation is a server-side operation" literally true.

What it costs, above the ledger in §6: `fs.client.Workspace` routing by project (~250 *est.*), the
local source (~120), `ScanningProjectProvider` + `project.json` (~150), `fs/createProject` on both ends
(~100), the explorer's source grouping (~80); the creation dialog is §10, designed later and not priced here. Wire changes: `ProjectEntry`'s
source label and `FsHello.workspaceId`, both additive.

### 4.14 Scripting is a capability the server grants, not a feature the client has

Today the Run command compiles the buffer in front and executes it **in the client's JVM**, wherever
the file came from. On a dedicated server that is a live scripting environment inside every player's
client, reachable from any project they can edit — including their own GLOBAL projects, which nobody
but them controls. That is the surface the vision closes.

**A per-project `ScriptingMode`**, carried in `ProjectCapability` beside `mayRead`/`mayWrite` (additive):

| Mode | The Run panel | Who decides |
|---|---|---|
| `LIVE` | today's behaviour: compile what is on screen, run it here | the server, for its projects: always in single-player (the player owns the machine), and on a dedicated server only for an actor the server's config grants it to |
| `AUTHORIZED` | no local Run: the command is disabled with its reason in the tooltip ("scripts run here only when the server sends them"); the panel is the transcript of runs the server *sent* | the dedicated server's default for its own projects |
| `NONE` | nothing runs, nothing arrives | the **client's own rule for its local scope while connected to a remote server** — no server speaks for those files, so `localMode = connection.isRemote() ? NONE : LIVE` |

**The authorized channel** is the piece that does not exist. A mod on the server asks for a script to
run on a client the way it asks for a window to open:

```java
ServerScripts.authorize("mymod:tools/*.js", (viewer, args) -> viewer.isOperator());   // like ServerWindows.openable
ServerScripts.of(connection).run(Resource.of(path), args);                            // at the trigger
```

`run` has the server **compile and validate first** — its own `ScriptRuntimes` runs headless, and
`RefusedTypes` plus the server's `ScriptPolicy` are asked before anything leaves — then sends
`script/run { resource, contentHash, source?, bindings, policy }`. The client checks the hash against the
content it holds (or takes the inline source), compiles under **its own** policy as well, executes,
and reports `script/state` back, which the server records in a `RunSessions`-shaped log an operator can
read. The `ScriptingExtension` (W6) reads `ctx.workspace().capabilities().scriptingMode(resource)` and
`ScriptLauncher` refuses on anything but `LIVE`.

**What this does and does not buy, stated so it is not oversold.** No client-side check stops a modified
client — nothing can, and `ScriptPolicy`'s javadoc already says the trust model is the answer. What it
buys is exact: a **stock** client offers no live-scripting surface while connected to a server, and
nothing executes on a stock client that the server has not validated and asked for. That is what a
server administrator can rely on, and it is the same guarantee every anti-cheat that is not a rootkit
offers.

Where it lives: the vocabulary (`ScriptMethods`, the two records) in `core/net/script/`, beside the
window protocol, because a dedicated server and a client with no engine both have to parse it; the two
halves in `language.run.net`, because only a host with a runtime can act on it.

## 5. Decisions

Each with a recommendation; the phasing assumes the recommendation unless the decision says otherwise.

| # | Question | Recommendation | Why |
|---|---|---|---|
| **D1** | What is the seam called — *contribution*, *extension*, *plugin*? | **`WorkbenchExtension`** | `contribute` is already the verb every process-wide registry uses (`ContentProviders.contribute`, `CommandRegistry.contribute`), and a per-workbench activation is a different thing; "plugin" implies loading, which this does not do. VS Code and Eclipse both say *extension* for the thing that is activated against a host |
| **D2** | Process-wide registry that a manifest picks from, or manifest-only? | **Both** — the registry is how a module makes itself *available* (`ScriptingExtension` from `language/`, a networked mod's client half), the manifest is how an application *enables* it | Manifest-only means every application names every class, which is what a loader does today; registry-only is A2 again (everything everywhere) |
| **D3** | Where do notifications live? | **The desktop** — one centre; balloons and the bell are `Desktop`'s; `NotificationsExtension` gives a workbench its history tool window filtered to its source | A12; every OS has one notification centre and it is not inside any application |
| **D4** | `StatusBar` per workbench — it breaks a static API used at 5 sites | **Yes** — `StatusBar.addEntry(...)` becomes `ctx.statusBar().addEntry(...)`; `TextEditorView` gets its bar from the tab's context | Two applications cannot share one line; the static was a shortcut from when there was one window |
| **D5** | How does a window know its application? | `WindowFrame.setApplication(ApplicationKind)`; `WindowRegistry` groups by it | The `AppUserModelID`/`WM_CLASS` mechanism: one field, and every shell feature reads it |
| **D6** | Where does `DesktopHost` live? | `desktop.host`, beside `ScreenOverlay` | It is the second class a loader talks to; the package exists for exactly that |
| **D7** | Is the shader graph an application? | **An action on `CrystalEditor` and a `DocumentKind`**, not a second shell — unless a graph-only product is wanted, which is then a second *manifest* | §4.2; IDEA/PyCharm |
| **D8** | Desktop search — a new picker or the workbench palette? | A **desktop `QuickPick`** over `SearchProvider`s (applications, windows, recent files, commands of the active application); the workbench palette stays for commands | Spotlight is not the IDE's palette; the two have different scopes and the palette must not know about windows |
| **D9** | Keep `DockPanelDescriptor`? | **Yes, as the compiled form** of `ToolWindowKind`; nothing outside the dock names it | `DockLayoutCodec` and `DockPanelRegistry` are written against it and are fine |
| **D10** | Does `Workbench` stay a `UIElement`? | **Yes** — it *is* the element the chrome hangs off; the engine's *services* are on `WorkbenchContext` | Splitting element from context is what the interface does; splitting the element into two would move 3,000 lines for no boundary gained |
| **D11** | The harness | One `HarnessHost implements HostServices`; scenes launch applications | §1.2 |
| **D12** | The mc1710 probes and `CgUiAutoTest` | **Stay**, rewired to `DesktopHost` and the scripting extension's public surface | Diagnostics are the reason every server-side defect this year was found |
| **D13** | `ScriptWorkbench` | **Deleted**; `ScriptingExtension` (activation, ~100) + `ScriptLauncher` (one async compile path, ~150) + `RunReporting` (~120) + `RunConsolePresenter` (~70), per v1 §5.4 — but written against `WorkbenchContext`, in `language.run.view` | A3; and it is the first extension that lives outside `core/`, which is what proves the seam |
| **D14** | Session keyed how? | By (application id, workspace identity), where the identity is a `workspaceId` the server greets with and a hash of the listed project ids as the fallback — record format unchanged, no version bump | A10 and §4.9(a): the record describes a workbench over a workspace, never one project |
| **D15** | Order of work | Host seam and disposal **before** the application concept | An application that cannot be launched twice or closed cleanly is a manifest with nothing behind it; §1.5 is the blocker for everything else |
| **D16** ✓ | Can an application be launched with no world? (decided: yes) | **The `Workspace` learns to be detached**: `Workspace.detached()` answers every call with `FsError.DISCONNECTED` until `rebind`, and `DesktopHost.workspace()` is never null. The engine keeps requiring a `Workspace`; `ApplicationKind.requiresConnection()` says whether the launcher offers the application on the title screen | Today `ensureEditorWindow` refuses with a log line; a detached workspace makes "connected later" the ordinary path a panel already handles (§4.11 rule 1), and it is what a reconnect already looks like from inside |
| **D17** | Is a running application's hidden main window evictable? | **No.** `WindowRegistry.evictIfNeeded` exempts a window that is an application's main window; a hidden main window is the application running in the background, and quitting it is `Application.dispose()` — recorded, never inferred from a cap | Today only a dirty window is exempt; a clean editor hidden under `HIDE_ON_CLOSE` could be evicted by seven other hidden windows, and the "application" would vanish with nothing having asked |
| **D18** | Where do the toggle commands `ToolWindowKind` derives register? | **Globally, keyed by kind id**, resolving the workbench from `DataContext` — the pattern `Workbench.SHOW_PROBLEMS` already follows. `CommandRegistry.contribute(Class, …)` gains a `contribute(String key, …)` overload so a kind enabled by two applications registers once | A per-document registry (what the stripe's `view.<type>` commands use today) collides the moment two workbenches share a document, which two applications do |
| **D19** | How does `ApplicationRegistry.handlerFor(resource)` know an application's file associations before it is launched? | `WorkbenchExtension.kinds()` declares its `DocumentKind`s **as data** (a static list — `NotesKind.KIND` already is one); the registry reads the manifests' extensions, never an instance | Associations must be answerable from the launcher with nothing running, as LaunchServices answers from `Info.plist` |
| **D20** | One `ConfigStorage`, several applications | `ConfigStorage.scoped(appId)` for an application's own files (its sessions, its preferences); the root keeps what is shared (the desktop record, the theme, backup and local history) | Two applications writing `settings.json` into one directory is the same collision as two status bars |
| **D21** | Theme and editor scheme | The **theme** (tokens) is the desktop's — one style engine per document; the **editor colour scheme** is per application, installed as a sheet *scoped to the application's root* (`StyleEngine.addStylesheet(sheet, root)` exists for this) | `UiThemeManager.installInto(window.styles())` is document-wide today, so two applications' schemes would fight and the last to install wins |
| **D22** | Which application receives a server-pushed `EDITOR_TAB` / `TOOL_WINDOW` window? | The most-recently-active workbench application; the desktop when none is running | `NetworkedPanels` is per workbench and the mount is per connection, so a route has to be chosen; MRU is what every "open in the current window" rule does |
| **D23** | Existing session files under the old name | A one-time fallback read of `session.<first project id>.json` when the new key has no record, then written under the new key | Costs ten lines; without it every user loses one arrangement on the day the key changes |
| **D24** | `tools/port/pricesplit.py` | **Rebuilt** — it existed at `629ccbf9` and was deleted with the port tools; the plan cites it for W5. Copy a package into a scratch tree, apply the partition, `javac`, count `is not public`. ~60 lines | A split priced from source has been wrong three times (`plan_m6.md` §6); the instrument has to exist before W5 |
| **D25** | Are the client's own GLOBAL projects reachable while connected to a dedicated server? | **Yes, served locally through the protocol** (§4.13), edit-only as far as scripts go (§4.14); a server serves exactly one root and an integrated server's is the world's | Hiding a player's own notes and configs the moment they join a server makes the global scope pointless; serving them through the same protocol is what keeps etags, trash, watches and presence honest for them |
| **D26** | When is a scope chosen? | **Only in single-player** — GLOBAL or WORLD, WORLD preselected, and the choice picks the *source* the `fs/createProject` goes to. Title screen: local only. Dedicated: the server only, for authorised actors; no option shown | A dedicated server has one world and therefore one root; the option would be a choice with one answer |
| **D27** | Who grants `LIVE` scripting on a dedicated server? | The server's config, per actor, defaulting to nobody; `AUTHORIZED` is the default for every server project; the client's local scope is `NONE` while remote | §4.14: a stock client offers no live surface on a server unless the server says so |
| **D28** | May a server-sent script be one the client cannot see? | Yes — `source` may travel inline (under the size tier) for a script that is not in any project the viewer may read | A mod's client-side behaviour is not necessarily a file the player is entitled to open |

---

## 6. Ledger — per file: deleted, moved, kept, and what replaces it

Line counts are today's. *Est.* on replacements.

### 6.1 `core/.../workbench/`

| File | Lines | Disposition | Replacement |
|---|---|---|---|
| `Workbench.java` | 3,378 | **split** | `Workbench` ~350 (chrome, services, `WorkbenchContext` impl, extension draining, dispose) + `editor/WorkbenchOpener` ~280 + `editor/SaveActions` ~280 + `editor/DocumentTabs` ~240 + `editor/TextFileKind` ~80 + `ext/ExplorerExtension` ~150 + `ext/ProblemsExtension` ~110 + `ext/PresenceExtension` ~60 + `ext/ProjectSourcesExtension` ~160 + `ext/NotificationsExtension` ~50 — **≈1,760 est.**, i.e. the 1,721 method lines with the 50% comment load halved |
| `WorkbenchMenus.java` | 137 | kept, reads `ToolWindows` | — |
| `WorkbenchSession.java` | 809 | kept; keyed per §4.9; `"extensions"` slices; the explorer's expansion retry moves out | ~+60, −80 |
| `explorer/WorkspaceTreeSource.java` | 864 | **split** (§4.11) | `WorkspaceProjects` ~380 (engine) + `explorer/ExplorerTreeSource` ~480 |
| `fs/protocol/FsHello.java` | — | + `workspaceId` (additive) | ~+10; the server derives and persists it |
| `WorkbenchSettings.java` | 313 | kept; `install(WorkbenchContext, Settings)` | — |
| `NetworkedPanels.java` | 329 | kept; written against `WorkbenchContext` | — |
| `WorkbenchKinds.java` | 106 | kept | — |
| `toolwindow/ToolWindowManager.java` | 671 | kept + `register(ToolWindowKind)` | `toolwindow/ToolWindowKind` new ~180 |
| `dock/panel/DockPanelDescriptor.java` | 205 | kept (D9) | — |
| `dock/banner/DockBanners.java` | 62 | **deleted** | `DockPanelRegistry.banners()` (~40 added) |
| `view/ViewContainerRegistry.java` | 151 | kept; fed by `ToolWindowKind.view(...)` | — |
| new | — | `WorkbenchContext` ~120, `WorkbenchExtension` ~20, `WorkbenchExtensions` ~60 | |
| `search/ProjectIndex.java` | 382 | **moved** to `text/lang/` beside `ProjectSourcesRegistry`, owned per workspace (§4.12) | — |
| `fs/client/WorkspaceDocuments.java` | — | owned by the `Workspace`; `open(resource, kinds)` takes the application's kinds | ~+30 |

### 6.2 `core/.../app/` and `core/.../desktop/`

| File | Lines | Disposition | Replacement |
|---|---|---|---|
| `crystaleditor/CrystalEditor.java` | 542 | **rewritten** | `CrystalEditor` ~60 (manifest + choices) over `desktop/app/WorkbenchApplication` ~250 |
| `crystaleditor/CrystalEditorCommands.java` | 97 | kept | — |
| `shadergraph/ShaderGraphContribution.java` | 175 | **rewritten** as `ShaderGraphExtension implements WorkbenchExtension` | ~160; banner goes to the registry |
| `AppKinds.java` | 31 | kept | — |
| `example/notes/NotesKind.java` | 58 | + `NotesExtension` ~25 | the example becomes the proof that a host installs nothing |
| `desktop/Desktop.java` | 1,752 | + `applications()`, notification balloons host | ~+80 |
| `desktop/window/WindowFrame.java` | 2,593 | + `setApplication` | ~+20 |
| `desktop/window/WindowRegistry.java` | 215 | + grouping by application | ~+40 |
| `desktop/taskbar/Taskbar.java` | 595 | + launcher button, grouping | ~+120 |
| `core/notify/StatusBar.java` | 242 | **de-staticised** (D4) | same size, instance |
| new `desktop/app/` | — | `ApplicationKind` ~150, `Application` ~30, `ApplicationRegistry` ~120, `LaunchContext` ~40, `Launcher` (the app grid / start menu) ~250, `DesktopSearch` + `SearchProvider` ~200 | |
| new `desktop/host/` | — | `HostServices` ~30, `DesktopHost` ~250 | |
| moved in | — | `net/window/DesktopWindowMount` (from `CgUiWindowMount`, 266, unchanged bar the static `CgUiScreen.window()` lookups → a field) | |
| moved in | — | `net/protocol/Connections` ~230 (from `CgUiConnections`) | |
| moved in | — | `fs/server/WorkspaceHost` ~300 (from `CgUiWorkspaceHost`) | |

### 6.3 `language/.../run/view/`

| File | Lines | Disposition | Replacement |
|---|---|---|---|
| `ScriptWorkbench.java` | 567 | **deleted** | `ScriptingExtension` ~100, `ScriptLauncher` ~150, `RunReporting` ~120, `RunConsolePresenter` ~70 (D13) |
| `RunPanels.java` | 261 | kept; takes `WorkbenchContext`; the Run `ToolWindowKind` declared here | ~230 |
| `MappingCommands.java` | 174 | kept; `register(CommandRegistry, WorkbenchContext)` | — |
| everything else (2,906) | | unchanged | — |

### 6.4 `mc1710/`

| File | Lines | Disposition | Replacement |
|---|---|---|---|
| `Mc1710Workspace.java` | 106 | **deleted** | `DesktopHost.workspace()` |
| `CgUiWindowMount.java` | 266 | **moved** to core | — |
| `CgUiConnections.java` | 320 | **split** | core `Connections` ~230; `Mc1710Connections` ~90 keeps the FML events, `Mc1710NetworkChannel`, `Mc1710Peer` |
| `CgUiWorkspaceHost.java` | 403 | **split** | core `WorkspaceHost` ~300; `Mc1710WorkspaceHost` ~90 keeps `server.getFile(...)`, `OperatorsMayWrite`, `actorFor`, the tick |
| `CgUiScreen.java` | 748 | **shrunk** | ~200: `GuiScreen` overrides, `pumpInput`, the GL bracket, `frameDelta`, F6/F7 → `DesktopHost` |
| `CgUiHud.java` | 177 | shrunk | ~90 |
| `CgUiAutoTest.java` + 7 probes | 2,699 | kept, rewired | — |
| `CgUiInput`, `CgUiOverlayInput`, `MixinGuiScreen`, `Mc1710NetworkChannel`, `LaunchWrapperBytes`, `ScriptService1710`, `Mc1710Peer`, proxies, `CrystalGUI`, Machine example | 1,380 | unchanged | — |

Production: **3,400 → ≈1,800 (est.)**.

### 6.5 Harness

| File | Disposition |
|---|---|
| `HarnessWorkspace.java` | becomes `HarnessHost implements HostServices` (+~20 lines) |
| `CgUiDesktopScene.openEditorWindow` (30), `CgUiDockScene` assembly (~40), `CgUiCompletionScene`/`CgUiGalleryScene` editor construction | replaced by `host.applications().launch(CrystalEditor.KIND)` |

### 6.6 Tests

`HotExitIsWiredTest` becomes a real fixture (§4.7) and is the first thing written. `LayeringTest`
gains the `Workbench`-is-not-nameable case. `ModeStackTest`-style constant-pool scans:
`ExtensionsNameNoWorkbenchTest`, `LoaderNamesNoProductTest` (nothing under `mc1710/.../client` may name
`CrystalEditor`, `ScriptWorkbench`, `Workbench`). Two existing test files construct a `Workbench` and
move with the rewrite.

---

## 7. Phasing

| Step | Contents | Accepts | Risk |
|---|---|---|---|
| **W0 — the leak test** | `ApplicationRetentionTest`: build and dispose four `CrystalEditor`s; assert `Disposer.liveCount()`, static signal `connectionCount()`s and `ProjectSourcesRegistry.size()` return to baseline. **It fails on the current tree**, which is the point | the test exists and is red | none |
| **W1 — disposal** | `Workbench implements Disposable`; every ctor subscription into a `ConnectionGroup`; `Watch`es disposed; `ProjectSourcesRegistry` withdrawn; `CrystalEditor.dispose()` real; `DockBanners` → registry-owned | W0 green | low |
| **W2 — `WorkbenchContext` + `WorkbenchExtension`** | the interface extracted (no behaviour change), measured from what the outside calls; `WorkspaceProjects` named as an interface over the explorer's model (§4.11, the move deferred to W5); `WorkbenchExtensions` + `activateAll`, activated by the workbench and disposed with it; `NotesExtension` first, and the harness's two `NotesKind.register` calls deleted; `LayeringTest` case for extensions | notes appear on every host with no host code — `aShippedExtensionIsActiveOnAPlainWorkbench` | low |
| **W3 — the host seam** | `HostServices`, `DesktopHost`, `DesktopWindowMount`, `Connections`, `WorkspaceHost`; `Mc1710Workspace` deleted; `CgUiScreen` shrunk; `HarnessHost` | `:mc1710:compileJava`, `serverSmoke`, `runClient -PcgJoin`, the session probe; `LoaderNamesNoProductTest` | **medium** — crosses the loader seam, so only `serverSmoke` and a client can see it |
| **W3b — projects and sources** | `project.json` + `ScanningProjectProvider`, `fs/createProject`, the client-local source and `Workspace` routing by project, one root per server chosen by `isDedicatedServer()`, `workspaceId`, the explorer's source groups, the creation dialog with the scope choice shown only in single-player (§4.13) | many projects listed from both sources on a dedicated server and in single-player; one created into each; `serverSmoke` + the two-client probe | **medium–high** — the largest fs change since F7, and it crosses the loader seam |
| **W4 — `ToolWindowKind`** | the declaration; Problems, Notifications, Run, Inspector ported; `registerToolWindowCommands` deleted | every tool window has one declaration; `View ▸ Tool Windows` and the palette rows are derived | medium |
| **W5 — the `Workbench` split** | `pricesplit.py` rebuilt (D24) and run first; the extraction per §4.5; `StatusBar` per workbench (D4) | `:core:check`; the 1,745+1,749 tests | **high** — the largest move; done after W1–W4 so it moves code that is already disposed and already context-shaped |
| **W6 — `ScriptWorkbench` → extension, and scripting as a capability** | D13; `ScriptingMode` in `ProjectCapability`; `ScriptMethods` + `ServerScripts`/the client half (§4.14); `ScriptLauncher` refuses on anything but `LIVE` | the Run panel appears on every host that lists `crystalgui:scripting` and has a band; on a dedicated server a non-op's Run is disabled and a server-sent script runs and reports; `CgUiAutoTest.runScriptOnce` rewired | medium–high — `language/` is in another worktree (§8), and the authorized channel is new protocol |
| **W7 — applications** | `ApplicationKind`/`Registry`/`WorkbenchApplication`; `CrystalEditor` as manifest; `WindowFrame.setApplication`; taskbar grouping; notifications to the desktop (D3); the session key per §4.9 with `FsHello.workspaceId` | two `CrystalEditor`s on one desktop with separate status bars and one notification centre | medium |
| **W8 — launcher, search, associations** | `Launcher`, `DesktopSearch`, `handlerFor`, jump-list actions, the shader-graph action | F7 desktop with a launcher; "open with"; a search that finds an app, a window, a file | medium |

W0–W3 answer claim 5 and the blocker; W4–W6 answer claims 2–3; W7–W8 answer claims 1 and 4.

**Every step that adds or moves a service API updates `docs/CGUI_WORKBENCH_SERVICES.md` in the same
commit** — the standing rule in `AGENTS.md`, and the one the filesystem cutover broke once. The `AGENTS.md`
package map and doc index follow the same rule.

---

## 8. Hazards

1. **`language/` is checked out in another worktree** (`X:/projects/CrystalGUI-language`, branch
   `language-stack`). W6 touches `language/run/view`; re-check that branch's diff against `run/`
   immediately before starting, and never enter that directory.
2. **Nothing in `:core:check` compiles `mc1710`.** W3 deletes from `core/`-facing loader code; run
   `:mc1710:compileJava` and `serverSmoke` at every W3 checkpoint, not at the end.
3. **The static `StatusBar` has 5 `addEntry` call sites and `TextEditorView` holds the caret entry statically.**
   D4 is a mechanical change with a silent failure mode (an entry written to the wrong bar). The
   two-application test in W7 is what sees it.
4. **`initGui` re-runs on every display resize**, and `DesktopHost.shown()` will be called from it.
   The consumed-flag rule in `CgUiScreen.bringEditorForward` has to survive the move — the launch
   request is consumed, never latched.
5. **`ClientWindows` queues windows until a mount exists**; the mount is installed by `DesktopHost`
   at creation rather than per frame. A server window arriving before the host exists still queues;
   a host destroyed and recreated must re-bind — the per-frame re-ask stays inside `frame()`.
6. **W0's fixture needs both halves of a workspace in one process** — `PresenceTest.Peer` is the
   shape (`InMemoryTransport` pair, `WorkspaceBinding`, `Workspace.over`), and it belongs in `core/src/test`
   because a `CrystalEditor` reaches `StyleSheet`.
7. **A server serves exactly one root**, and which one is the loader's only decision here:
   `isDedicatedServer()` picks the server directory, otherwise the world's. Serving both from an
   integrated server would open the client's own global files as two documents (§4.13).
8. **The existing `crystalgui/workspace` project is adopted, never moved** — a manifest is written beside
   it; its id stays `minecraft.workspace` so every saved session and path keeps parsing.
9. **A `workspaceId` per world means the session changes when the world does.** That is the intent;
   the trap is a GLOBAL project's tabs appearing under whichever world's record was open, which is
   correct and will be reported as a bug the first time somebody notices.
10. **Two `DocumentKinds` claiming one extension is legal (last wins).** Two *applications* claiming
   one file association is a user preference (`handlerFor`), never an exception.

---

## 9. What done measures

- A loader assembles nothing: `mc1710/.../client` names no product class (scan).
- The three hosts run the same product from the same manifest (the §1.2 table has one column).
- Four editors built and disposed leave the process where it started (W0).
- Every tool window and every file type is one declaration.
- Two applications on one desktop: separate status bars, grouped taskbar entries, one notification
  centre, and a launcher that lists both.
- `mc1710` production ≤ 1,900 lines, every class in it naming a platform type.
- Many projects per source; the scope choice appears in single-player and nowhere else; the client's
  own global projects reachable on a dedicated server with scripts that will not run there.
- A non-operator on a dedicated server has no Run; a server-sent script runs on a stock client, is
  validated on the server first, and reports its state back.

---

## 10. Project Creator UI — deferred, to be designed together

Not planned here on purpose. §4.13 fixes what the dialog *does* — it names a project and sends
`fs/createProject` to one source, with the GLOBAL/WORLD choice shown only in single-player — and nothing
about what it looks like, what a template is, or how it fits the launcher. That is designed with the
user when W3b reaches it, not before. The only things W3b builds ahead of it are the verb, the manifest
and the scanning provider, which the dialog will sit on whatever shape it takes.
