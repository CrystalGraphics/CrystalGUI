package com.crystalgui.workbench;


import com.crystalgui.workbench.extension.ProjectExtension;
import com.crystalgui.workbench.extension.ProblemsExtension;
import com.crystalgui.workbench.extension.NotificationsExtension;
import com.crystalgui.workbench.extension.SessionSlice;
import java.nio.file.Path;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.workbench.dock.WorkbenchOpener;
import com.crystalgui.workbench.editor.TextFileKind;
import com.crystalgui.workbench.explorer.*;
import com.crystalgui.workbench.extension.WorkbenchExtension;
import com.crystalgui.workbench.extension.WorkbenchExtensions;
import com.crystalgui.workbench.toolwindow.ToolWindowKind;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.workbench.decoration.FileDecorations;
import com.crystalgui.fs.client.WorkspaceProjects;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.async.Reply;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentKinds;
import com.crystalgui.document.DocumentState;
import com.crystalgui.document.EditorInput;
import com.crystalgui.document.RecentFiles;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.core.pattern.FilePatternMap;
import com.crystalgui.fs.client.ContentProvider;
import com.crystalgui.fs.client.FileOperations;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.client.WorkspaceDocuments;
import com.crystalgui.workbench.editor.EditorService;
import com.crystalgui.workbench.editor.TextEditorView;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.chrome.palette.CommandPalette;
import com.crystalgui.workbench.chrome.palette.QuickPick;
import com.crystalgui.workbench.chrome.status.StatusBarView;
import com.crystalgui.workbench.decoration.FileDecoration;
import com.crystalgui.workbench.decoration.FileDecorationProvider;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.workbench.chrome.notification.NotificationBalloons;
import com.crystalgui.workbench.chrome.notification.NotificationsView;
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.DockGroup;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.panel.DockInput;
import com.crystalgui.workbench.dock.panel.DockOpenOptions;
import com.crystalgui.workbench.dock.drag.DockPlacement;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionDropOverlay;
import com.crystalgui.workbench.region.RegionSide;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.workbench.decoration.DiagnosticDecorations;

import com.crystalgui.workbench.region.WorkbenchRegions;
import com.crystalgui.workbench.search.ProjectIndex;
import com.crystalgui.workbench.stripe.StripeRail;
import com.crystalgui.workbench.stripe.StripeView;
import com.crystalgui.workbench.toolwindow.ToolWindowLayout;
import com.crystalgui.workbench.toolwindow.ToolWindowManager;
import com.crystalgui.workbench.view.ViewContainerRegistry;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.text.diagnostic.Markers;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.workbench.chrome.menu.MainMenuCommands;
import com.crystalgui.workbench.chrome.menu.MenuBarView;
import com.crystalgui.core.undo.UndoCommands;
import com.crystalgui.fs.project.SourceRoots;
import com.crystalgui.text.lang.ProjectSources;
import com.crystalgui.text.lang.ProjectSourcesRegistry;

/**
 * <b>The workbench engine</b> - a dock, the documents open in it, and the services an extension builds on.
 *
 * <p>What it is <em>not</em> is a product. It ships no tool windows and no panels of its own: the project
 * tree, Problems, Notifications and the Inspector are all {@link WorkbenchExtension}s, and
 * {@code new Workbench(workspace, List.of())} is a perfectly good workbench with none of them that will
 * still open a file. An {@link com.crystalgui.desktop.app.ApplicationKind} names the ids it wants and
 * gets those.</p>
 *
 * <p>You normally get one from {@code WorkbenchApplication}. Extensions never name this class - they are
 * written against {@link WorkbenchContext}, which is the surface below.</p>
 *
 * <h3>What it owns</h3>
 *
 * <ul>
 *   <li>the dock and its tabs, and the one lane for opening anything into them;</li>
 *   <li>the open documents, their save path and their conflict handling;</li>
 *   <li>the project listing, the file decorations and the root watches - true whether or not any panel
 *       is showing them;</li>
 *   <li>the status bar, the menu bar, the command context and the settings scope;</li>
 *   <li>the session record, and each extension's slice of it.</li>
 * </ul>
 *
 * <h3>It requires a workspace</h3>
 *
 * <p>"Open a file" is the verb this exists for, so a workbench with no project is a different widget
 * rather than a degraded one.</p>
 *
 * <h3>The dock is asked, not remembered</h3>
 *
 * <p>Which file a save writes is derived from the dock's active tab, never from a field updated on open.
 * A remembered path saves the last file <em>opened</em>, which is the wrong one the moment you switch
 * tabs - and silently, because it reports success. The same rule is why a panel's content comes from the
 * open tab rather than a fresh read: the dock rebuilds panels on every split, drag and layout restore,
 * and re-reading there would discard unsaved edits each time.</p>
 *
 * <h3>Disposing it is final</h3>
 *
 * <p>It takes down every extension in reverse activation order, its tool windows, its watches, its
 * documents and everything it registered process-wide. A workbench is not reusable afterwards.</p>
 */
public class Workbench extends UIElement implements WorkbenchContext, DataProvider, Disposable {
    /** The shell. `ua/workbench.css` names the tag. */
    public static final Name NAME = Name.of("workbench");


    /** A document panel — one instance per file, distinguished by its {@code path} state. */
    public static final String FILE_TYPE = "file";

    /** @deprecated the panel is {@code ProjectExtension} now. @see ProjectExtension#TYPE */
    @Deprecated
    public static final String PROJECT_TYPE = ProjectExtension.TYPE;
    /** @deprecated the panel is {@code ProblemsExtension} now. @see ProblemsExtension#TYPE */
    @Deprecated
    public static final String PROBLEMS_TYPE = ProblemsExtension.TYPE;
    /** The notification history — IntelliJ's own tool window, on the auxiliary rail beside the bell. */
    /** @deprecated the panel is {@code NotificationsExtension} now; this names its id for callers
     * that have not moved. @see NotificationsExtension#TYPE */
    @Deprecated
    public static final String NOTIFICATIONS_TYPE = NotificationsExtension.TYPE;

    /**
     * The state key carrying which file a {@link #FILE_TYPE} panel shows.
     *
     * <p>An alias for {@link DockPanelRef#PATH}, which is where it belongs: the dock is what reads it,
     * to build a {@code DockInput}. Kept because it is what every caller here names.</p>
     */
    public static final String PATH_STATE = DockPanelRef.PATH;

    /** UNIQUE, never the shared "__content__" -- see ProjectFileTree.CONTENT_CLASS. */
    public static final String CONTENT_CLASS = "__workbench-content__";

    /** The dock, so the stylesheet can size it against the activity bar beside it. */
    public static final String DOCK_CLASS = "__dock__";

    /**
     * On every editor opened for a file, so the sheet can give it a size.
     *
     * <p><b>A {@code TextEditor} has no intrinsic height at all.</b> Its lines are absolutely positioned
     * inside its text viewport, so it contributes nothing to its own content size — the same shape as
     * {@code CanvasView} and its transformed plane. Dropped into a dock pane with nothing sizing it, it
     * lays out ZERO pixels tall and paints a blank pane while holding the file perfectly well: the read
     * succeeds, the status line says "opened README.md", and there is nothing on screen.</p>
     *
     * <p>A class rather than a Java-side size, per the widget rule — geometry lives in {@code
     * default.css}. A bare {@code texteditor} rule would reach every editor in the engine, including the
     * ones a page deliberately gives a fixed height.</p>
     */
    public static final String FILE_EDITOR_CLASS = "__file-editor__";

    /**
     * Marks an editor showing something the workspace does not contain -- a decompiled class.
     *
     * <p>Beside {@link #FILE_EDITOR_CLASS} rather than instead of it: a viewer is a file editor in every
     * way a stylesheet cares about, and adding a second class rather than swapping the first is what
     * lets a theme leave it alone and still have it look right.</p>
     */
    public static final String VIEWER_CLASS = "__viewer__";

    /**
     * The decoration a viewer tab carries. @see #tabDecorationFor
     *
     * <p>{@code decoration-} prefixed because {@code DockGroup.applyDecoration} swaps classes under that
     * prefix — a name outside it would be applied and never removed.</p>
     */
    public static final String LIBRARY_DECORATION = "decoration-library";

    // `onStatus` is gone. It was one Signal.Value<String>, so every writer overwrote every other and the
    // last one to speak won -- the shader graph's line-owner readout fires on every caret move and erased
    // "created folder" milliseconds after it appeared, with neither writer able to tell. It also gave a
    // caller no way to distinguish "saved" from "save failed", because both arrived as a String.
    //
    // Events go to Notifications (severity, actions, a bounded history); ambient text goes to StatusBar
    // (keyed per writer, replaced rather than accumulated). See com.crystalgui.core.notify.

    public final Workspace workspace;

    /** Every kind of document this workbench can open. An instance, so two workbenches differ. */
    public final DocumentKinds kinds = new DocumentKinds();

    /** The open documents, and the wire underneath them. */
    public final WorkspaceDocuments documents;

    /** The tabs over those documents — the ONE open lane. @see EditorService */
    public final EditorService editors;
    public final DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();

    /**
     * Saving, and what a conflict means — extracted at W5.
     *
     * <p>A <em>document</em> concern that had grown onto the shell: writing a tab back, the stale-write
     * question and its three answers, the merge, and what closing something has to ask before it
     * discards anything.</p>
     */
    public final SaveActions saveActions = new SaveActions(this);

    /**
     * How a tab presents itself, and what the dock and the documents owe each other — extracted at W5.
     *
     * <p>The seven registry providers, the placeholder rebuild, the failure banner, releasing a closed
     * panel and following a rename. Every one of them is PULLED by the strip when it builds a tab rather
     * than pushed in afterwards, which is what makes a rebuilt strip correct on the frame it is
     * rebuilt.</p>
     */
    public final DocumentTabs documentTabs = new DocumentTabs(this);

    /**
     * The ONE open lane's shell half — extracted at W5.
     *
     * <p>{@code open}, {@code openFile}, {@code openResource} and their {@code At} variants, the
     * extension bindings, the ref a path maps to, and which dock a placement lands in. Editor-service
     * work that had grown onto the shell because the shell is what owns the dock.</p>
     */
    public final WorkbenchOpener opener = new WorkbenchOpener(this);

    /**
     * What the workspace declares, and the three snapshots an analysis thread reads — extracted at W5.
     *
     * <p>A <em>language</em> concern that lived on the shell. It is the one cluster here whose comments
     * are all about threads: everything it holds is read off the frame thread and written on it, which
     * is why the snapshots are volatile and why nothing in it may reach a widget.</p>
     */
    final ProjectSourcesIndex projectSources = new ProjectSourcesIndex(this);


    /**
     * Who else is in the file that is in front — extracted at W5.
     */


    /** The transient half — balloons over the bottom-right corner. @see NotificationBalloons */
    private final NotificationBalloons balloons = new NotificationBalloons();
    /**
     * The tree's half of the workbench — extracted at W5.
     *
     * <p>What a change on the server means to it, what a drop onto it does, the recursive watches per
     * project root, and following the active tab.</p>
     */
    final ExplorerBinding explorerBinding = new ExplorerBinding(this);

    /**
     * The project listing and the file decorations — <b>the engine's, not a panel's</b>.
     *
     * <p>Both used to be constructed by {@code ProjectFileTree}, so {@link #projects()} and
     * {@link #decorations()} were reads through a widget: a workbench with no explorer had no listing
     * and no decorations either, and the explorer could not become an extension without taking the
     * engine's own model with it. @see WorkspaceProjects</p>
     */
    private final WorkspaceTreeSource projectListing;

    private final FileDecorations fileDecorations = new FileDecorations();

    public final DockArea dock;

    /** The fixed region frame — sidebar, editor, panel, auxiliary. @see WorkbenchRegions */
    private WorkbenchRegions regions;

    /** @see WorkbenchRegions */
    public WorkbenchRegions regions() {
        return regions;
    }

    /**
     * Tool windows — which exist, where each belongs, whether it is on screen.
     *
     * <p>Built after {@code dock}, and handed {@code this::open} rather than {@code this}: it needs exactly
     * one thing back from here, and taking the owner would make the dependency unbounded. See
     * {@link ToolWindowManager}.</p>
     */
    private ToolWindowManager toolWindowManager;

    /**
     * What this workspace declares, by qualified name — the project half of resolution.
     *
     * <p>Registered into {@link com.crystalgui.text.lang.ProjectSourcesRegistry} so an engine can reach
     * it without {@code core/} ever naming an engine, the same inversion {@code TypeSearch} uses for the
     * classpath. It pulls rather than being pushed to: the crawl, the project listing and the watcher all
     * land on their own schedules, and an index that had to be told about each would be silently short of
     * whichever one somebody forgot. @see ProjectIndex</p>
     */
    private final ProjectIndex projectIndex;

    /**
     * An open document's CURRENT text, or null when nothing has it open.
     *
     * <p>The buffer beats the file, always: a compiler resolving against saved text reports errors about
     * code the author has already fixed, in the one place they are looking.</p>
     *
     * <p>Encoded on demand rather than cached. That is a real cost for a large file asked about often, and
     * it is the cost §24.6 names as S4's performance work — pinning it here first would be optimising a
     * path nothing has measured yet.</p>
     */
    @Nullable
    public String openBufferText(CgPath path) {
        Document document = documents.get(Resource.of(path));
        if (document == null) return null;
        byte[] bytes = document.model().encode();
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }


    /**
     * Anything an open document resolves against has changed — ask the editors again.
     *
     * <p>Three causes, one flag, deliberately: a read landing ({@link #onProjectIndexFilled}), the crawl
     * settling at a new size, and an open buffer moving. They are indistinguishable downstream — every one
     * of them means "a name that did not resolve might now" — and coalescing them is the point, since a
     * single frame routinely carries all three.</p>
     *
     * <p>Volatile because {@link #onProjectIndexFilled} runs on whatever thread the workspace client
     * answers on; drained by {@link #tick} on the UI thread.</p>
     */
    public volatile boolean projectSourcesMoved;

    // ── What the index is allowed to see ────────────────────────────────────────────────────────
    //
    // Three snapshots, all written on the UI thread by refreshProjectIndexInputs() and read from the
    // ANALYSIS thread by ProjectIndex. Volatile references to immutable values, so a reader either sees
    // the whole previous snapshot or the whole next one and never a map mid-write.
    //
    // This is the same hazard the run panel's Stop button hit from the other direction: there a worker
    // thread reached INTO the cascade, here a worker thread reads state the cascade owns. Both fail as a
    // ConcurrentModificationException or worse from a thread with nothing recognisable in its trace --
    // and here it would not even throw where anyone could see it, because ProjectSourcesRegistry's view
    // catches a provider's RuntimeException by design, turning the fault into "no, that type does not
    // exist". Identical to the symptom of having no index at all.

    /** Every file the crawl has reached, as of the last frame. */
    public volatile List<CgPath> crawledFiles = List.of();

    /** Project id to declared source roots, as of the last frame. */
    public volatile Map<String, List<String>> projectRoots = Map.of();

    /** Open documents' text, as of the last frame — the tier that beats the file on disk. */
    public volatile Map<CgPath, String> bufferSnapshot = Map.of();

    /** A buffer's CONTENT moved since the snapshot was taken. UI thread only. */
    /**
     * The open documents whose text has moved since the snapshot was taken — <b>which ones</b>, not
     * whether any.
     *
     * <p>It was a boolean, and the rebuild below is over every open document, so one keystroke re-encoded
     * every buffer in the workbench. The signal that sets this carries the path that changed and the
     * boolean threw it away; keeping it means a keystroke re-encodes the document it landed in and reuses
     * the string already held for the others.</p>
     */
    public final Set<CgPath> dirtyBuffers = new HashSet<>();

    /** Which documents the snapshot covers, so opening or closing one is noticed. UI thread only. */
    public final Set<CgPath> snapshotOver = new HashSet<>();

    /** The tree source revision these snapshots were taken at. @see WorkspaceTreeSource#indexRevision() */
    public int lastIndexRevision = -1;

    /** Whether the workspace's own inputs moved on the PREVIOUS frame. @see #refreshProjectIndexInputs */
    public boolean workspaceMovedLastFrame;




    /** What the workspace declares. @see ProjectIndex */
    public ProjectSources projectSources() {
        return projectIndex;
    }

    /**
     * Every file operation goes through this, never straight at the wire.
     *
     * <p>It is what keeps the open tabs honest across a rename or a delete: {@link WorkspaceDocuments}
     * hears the server's own {@code fs.changed} and moves the document, and this workbench follows the
     * document. Before that existed, nothing updated the tab when a path changed — it kept its old title,
     * {@code Ctrl+S} wrote to the old name, and opening the new name produced a second editor.</p>
     */
    public final FileOperations files;

    /** Marked internal exactly ONCE, while empty. {@code markAsInternal()} RECURSES, and stamping a
     * populated subtree makes {@code removeChild} silently refuse everything under it — which is how the
     * dock grew duplicate unclickable tabs and how the shader graph editor hung the window. */
    private final UIElement content = new UIElement();

    /**
     * The line along the top — File, Edit, View, Graph, Window, Help.
     *
     * <p>Chrome, like the status bar and the rails, and sitting <em>above</em> {@link #content} for the
     * same reason the status bar sits below it: the workbench is a column and content is what grows, so
     * order alone places both and neither is positioned.</p>
     *
     * <p>It holds no items. Every row in every one of its menus is a {@code .menu(...)} declaration on a
     * command that already existed for the keyboard and the palette. @see MenuBarView</p>
     */
    private final MenuBarView menuBar = new MenuBarView(CommandRegistry.global());

    /**
     * Files opened most recently — what {@code File ▸ Open Recent} lists.
     *
     * <p>Recorded at {@link #openFile}, which is the ONE place a file becomes a tab, so nothing else has
     * to remember to call it. Recording at the call sites instead is how a recent list ends up missing
     * the paths opened by the palette, by a problem row, or by a session restore.</p>
     */
    public final RecentFiles recentFiles = new RecentFiles();

    /**
     * The line along the bottom — Parts step 6.
     *
     * <p>Chrome, like the rails and for the same reason: it is not something the layout can lose. It sits
     * <em>below</em> {@link #content} because the workbench is a column and content grows, so the bar keeps
     * its declared height at every window size without being positioned.</p>
     *
     * <p>It reads {@code StatusBar} itself rather than being fed. Anything on it is an item some writer
     * keyed, which is what the keying was for; nothing needs to route a string through here.</p>
     */
    /**
     * The bar's model — an instance since W5, and per workbench.
     *
     * <p>It was a static, which was a shortcut from when there was one window. Two applications on one
     * desktop cannot share one line: the caret readout of whichever editor was focused last would win,
     * and closing one application would take entries off the other's bar.</p>
     */
    private final StatusBar statusBarModel = new StatusBar();

    private final StatusBarView statusBar = new StatusBarView(statusBarModel);

    /**
     * The two tool-window rails. Chrome, not panels — see where they are added.
     *
     * <p><b>Two, because a region is not spellable with one.</b> IntelliJ's New UI has a left stripe and a
     * right stripe, each with a top and a bottom group, and which of the four a button is in <em>is</em>
     * which region its tool window opens in. See {@link StripeRail}.</p>
     */
    private final StripeView leftStripe = new StripeView(this, StripeRail.LEFT);
    private final StripeView rightStripe = new StripeView(this, StripeRail.RIGHT);

    /** Where a dragged tool window would land, drawn over the whole workbench. @see RegionDropOverlay */
    private final RegionDropOverlay dropOverlay = new RegionDropOverlay(this);

    public RegionDropOverlay dropOverlay() {
        return dropOverlay;
    }

    public StripeView stripe(StripeRail rail) {
        return rail == StripeRail.RIGHT ? rightStripe : leftStripe;
    }

    /** Both rails, left first. */
    public List<StripeView> stripes() {
        return List.of(leftStripe, rightStripe);
    }

    /**
     * This workbench, for a command that acts on one.
     *
     * <p>What let {@code ExplorerCommands} stop capturing a workbench and become global — and with it,
     * the per-frame {@code installExplorerCommands(window)} call that used to sit in {@link #tick}
     * solely because registration needed a window to reach a registry.</p>
     */
    /**
     * <p><b>{@code workbench.new}, and the suffix goes on the NEW copy — never the old one.</b> A
     * {@code DataKey} is interned by NAME and its type IS what it names, so two engines' workbenches
     * cannot share one: the first test that loads both classes dies in a static initialiser.
     * {@code ContextKeys.find} resolves a key by name out of a {@code when} expression, so renaming
     * the SHIPPED one would silently break every command declaration that mentions it and nothing
     * would fail until somebody pressed the key. Dropped back to {@code workbench} at 6.9, when the
     * old one goes.</p>
     */
    public static final DataKey<Workbench> WORKBENCH =
            DataKey.create("workbench.new", Workbench.class);

    @Override
    public Object getData(DataKey<?> key) {
        if (key == WORKBENCH) return this;
        // THE BAR THIS WORKBENCH OWNS. Answered here rather than by the view, for the
        // reason the menu bar above is: the walk finds ancestors, and the bar is a SIBLING
        // of the content everything is focused inside.
        if (key == UiDataKeys.STATUS_BAR) return statusBarModel;
        // ANSWERED HERE, NOT BY THE BAR. The walk only finds ancestors, and the menu bar is a SIBLING of
        // the content everything is focused inside -- so a command resolving outward from a focused editor
        // would never reach it. The workbench is the nearest thing that is an ancestor of both.
        // `MenuBarView.MENU_BAR`, NOT `UiDataKeys.MENU_BAR`. A DataKey's TYPE is the thing it names,
        // and the old one is a `DataKey<ui.elements.chrome.MenuBarView>` -- so answering it with THIS
        // engine's bar compiled (getData returns Object) and then failed `key.cast` inside
        // DataContext, which is a null nobody reports. The new bar declares its own key for exactly
        // this reason and its javadoc says so; this call site had not caught up.
        if (key == MenuBarView.MENU_BAR) return menuBar;
        // THE SURFACE, which nothing answered and every command gated on it was therefore disabled.
        //
        // The old engine's palette took `UiDataKeys.WINDOW`, a `DataKey<UIWindow>`; a key's TYPE is the
        // thing it names, so an engine and its copy cannot share one and the port had to declare
        // `CommandPalette.SURFACE` as a `DataKey<UIDocument>`. The consumers came across with it --
        // `ChromeCommands`, `ExplorerCommands`, `CrystalEditorCommands` all read it -- and no provider
        // was ever written, so `context.data().get(SURFACE)` answered null everywhere.
        //
        // What that looks like is not a missing key. `Show All Commands` is `enabledWhen(surface !=
        // null)`, so the row greyed out and Mod+Shift+P did nothing: the chord resolved, found its
        // command, and declined to run a disabled one. Every row that reaches for the surface greyed
        // with it, which reads as the whole menu being broken rather than as one unanswered key.
        //
        // ANSWERED HERE for the reason the two above are: the walk only finds ancestors, and this is
        // registered as a document-level provider (`addDataProvider`), so it is reachable with nothing
        // focused at all -- which is exactly when the palette is opened.
        if (key == CommandPalette.SURFACE) return document();
        return null;
    }

    /**
     * Names this workbench at the <b>window</b> level as well as in the tree.
     *
     * <p>The element walk only finds ancestors, and a workbench is a descendant of the root — so with
     * nothing focused there is no workbench on the path at all. {@code Ctrl+P} and {@code F5} are exactly
     * the keys pressed before anything is focused, which is why they need this. See {@code DataContext}.</p>
     */
    @Override
    protected void connected() {
        super.connected();
        // THE PER-FRAME HOOK, and the guard is not the old one's: `registerTicker` was
        // HashSet-backed and idempotent, and `Animation.every` is a plain add, so a second attach
        // without this is a second hook. `disconnected()` clears it, or a panel that is hidden and
        // reshown -- which is every tool window -- comes back with the flag set and no hook behind it.
        if (!ticking && document() != null) {
            ticking = true;
            document().animation().every(this, this::tick);
        }
        UIDocument current = document();
        // JOINING A WINDOW IS WHAT LETS A WINDOWED TOOL WINDOW FINALLY OPEN. A session restore can run
        // before the tree is attached -- a host that restores on its first frame does so before anything
        // called UIDocument.init. The docked panels come back regardless; the windowed ones had nowhere to
        // open into and were remembered. @see ToolWindowManager#retryPendingShows
        //
        // ON THE NEXT FRAME, never inside this hook. This fires DURING the attach walk, so the rest of
        // the subtree -- including the regions root the retry has to ask -- may not have been registered
        // yet, and the retry would answer "still no window" and drop the panels for good. The same rule
        // ProjectFileTree's deferred refresh follows, and for the same reason: an attach is not a moment
        // to build things in.
        if (current != null) {
            current.animation().every(this, deltaSeconds -> {
                if (toolWindowManager != null) toolWindowManager.retryPendingShows();
                // ...AND ANYTHING ELSE THAT NEEDED A WINDOW. A session restore of torn-out editor
                // windows has the identical problem and the identical deadline, so it rides the same
                // deferral rather than growing a second one that could drift from it.
                onDidJoinWindow.emit();
                return false;
            });
        }
        // THE WORKSPACE'S PROBLEM COUNT IS A CLAIM ON A SCREEN, so it belongs to an ATTACHED workbench.
        // Subscribed from the constructor instead, every workbench ever built stayed subscribed and kept
        // writing its own entry into the one static bar -- one per test in the suite, so the entries
        // accumulated and every later change did O(entries) work in every live view.
        markerWatch.disconnectAll();
        capabilityWatch.disconnectAll();
        if (current == null) return;
        // THE PROBLEM INDEXING AND THE COUNT WERE HERE, gated on being attached because a workbench
        // subscribing from its constructor "stayed subscribed and kept writing its own entry into the
        // one static bar". Both halves of that are gone -- the bar is per workbench, and an extension's
        // handle is disposed with the workbench -- so the feature owns them. @see ProblemsExtension
        // ATTACHED WORKBENCHES ONLY, for the reason above it: this is a listener on a PROCESS-LIVED
        // static, so a workbench that subscribed from its constructor would stay reachable for ever and
        // keep an entire editor tree alive behind it.
        capabilityWatch.add(LanguageRegistry.onCapabilityChanged.connect(projectSources::attachLateServices));
        // A LIBRARY TYPE'S KIND ARRIVING LATE is the same event as a project file's declaration arriving
        // late, so it sets the same flag and is coalesced with it. `symbolOf` is allowed to answer "not
        // yet" precisely so that working it out cannot land on a frame; this is the other half of that
        // bargain -- without it a decompiled tab keeps the generic glyph until something unrelated
        // rebuilds the strip. Same group, because this is a listener on a process-lived static too.
        for (ContentProvider provider : workspace.providers()) {
            Disposable subscription = provider.onDidResolveSymbol(
                    resource -> projectSourcesMoved = true);
            capabilityWatch.add(subscription::dispose);
        }
        current.addDataProvider(this);
        // The rail's buttons, once there is a window to take a registry from.
        //
        // THE WINDOW'S registry, deliberately not the global one. A `view.<type>` command closes over
        // THIS workbench, and a captured owner cannot be registered once for the application -- the
        // second workbench would silently reuse the first's command and toggle a panel in a window
        // nobody was looking at. That is the rule step 2.5 wrote down after the suite caught it, and
        // routing these through the global registry walks straight back into it.
        for (StripeView stripe : stripes()) {
            stripe.listenToPanels(registry, current.getCommands());
            // The window is passed rather than looked up: this runs DURING the attach that sets the
            // stripe's own window reference, so it does not have one yet. @see StripeView#listenToFocus
            stripe.listenToFocus(current);
        }
    }

    /** The explorer's verbs come with the explorer. Global, so no window is needed. */
    @Override
    protected void registerCommands(CommandRegistry registry) {
        ExplorerCommands.register();
        // THE PROJECT INDEX IS *NOT* CONTRIBUTED HERE, and it was, and that was the whole of S4 being
        // dead on arrival. This method runs from UIElement's INSTANCE INITIALISER -- before the Workbench
        // constructor body -- so `projectIndex` was still null, and `contribute` opens with
        // `if (provider == null) return`. Nothing threw, nothing logged, and every cross-file reference in
        // the workspace reported "cannot be resolved to a type" with the registry, the name environment
        // and the project tier all correct and all covered by passing tests. Registration is per INSTANCE
        // and belongs in the constructor; this hook is per CLASS and may only name statics, which is
        // exactly what its own javadoc says. @see UIElement#registerCommands
        // Undo comes with a workbench because the file tree IS the workspace's UndoScope -- deleting a
        // file is undoable and reaches the workspace stack. Same ids the editor and the graph use, so
        // there is one Undo in the palette rather than one per widget.
        UndoCommands.register();
    }


    // ---- The facade's own lane -------------------------------------------------------------
    //
    // Thin by design. Each of these is on WorkbenchContext or called from outside the package, and the
    // work behind it moved at W5 -- so what is left here is the SURFACE, which is the thing an
    // application and an extension are written against and the thing that must not move when the
    // engine is rearranged behind it.

    /** Adds a file type. @see DocumentKind */
    @Override
    public Workbench contribute(DocumentKind kind) {
        opener.contribute(kind);
        return this;
    }

    /** As {@link #contribute}, plus the file patterns that open into this kind's panel. */
    @Override
    public Workbench contribute(DocumentKind kind, String... extensions) {
        opener.contribute(kind, extensions);
        return this;
    }

    /** Opens {@code input} where the placement says and how the options say. */
    public DockLeaf open(DockInput input, DockPlacement placement, DockOpenOptions options) {
        return opener.open(input, placement, options);
    }

    /** Opens {@code input} beside whatever is in front. */
    public DockLeaf open(DockInput input) {
        return opener.open(input);
    }

    /** The panel ref a path opens as. */
    public DockPanelRef refFor(CgPath path) {
        return opener.refFor(path);
    }

    @Override
    public void openFile(CgPath path) {
        opener.openFile(path);
    }

    @Override
    public void openFile(CgPath path, @Nullable Runnable onOpened) {
        opener.openFile(path, onOpened);
    }

    @Override
    public void openResource(Resource resource) {
        opener.openResource(resource);
    }

    @Override
    public void openResource(Resource resource, @Nullable Runnable onOpened) {
        opener.openResource(resource, onOpened);
    }

    /** Opens {@code path} and puts the caret at {@code at}. */
    public void openFileAt(CgPath path, @Nullable TextPoint at) {
        opener.openFileAt(path, at);
    }

    /** Opens {@code resource} and puts the caret at {@code at}. */
    public void openResourceAt(Resource resource, @Nullable TextPoint at) {
        opener.openResourceAt(resource, at);
    }

    /** ...and reveals {@code member} once the document is there, for a declaration with no position. */
    public void openResourceAt(Resource resource, @Nullable TextPoint at, @Nullable String member) {
        opener.openResourceAt(resource, at, member);
    }

    /** Who else is EDITING it, phrased for a human. @see SaveActions#phrase */
    @Override
    @Nullable
    public String othersEditing(@Nullable CgPath target) {
        return saveActions.othersEditing(target);
    }

    /** Who else merely has it open. @see #othersEditing */
    @Override
    @Nullable
    public String othersViewing(@Nullable CgPath target) {
        return saveActions.othersViewing(target);
    }

    /** Writes the active tab back. A stale write is reported distinctly -- it has a recovery path. */
    @Override
    public boolean saveActiveFile() {
        return saveActions.saveActiveFile();
    }

    /** Writes back every dirty document. @return how many were written */
    public int saveAll() {
        return saveActions.saveAll();
    }

    /** Whether {@code path} has unsaved changes. */
    public boolean isDirty(CgPath path) {
        return saveActions.isDirty(path);
    }

    /** Every file with unsaved changes. */
    public List<CgPath> unsavedFiles() {
        return saveActions.unsavedFiles();
    }

    /** Brings a tool window to the front, creating it if it is not open. */
    public boolean revealPanel(String typeId) {
        return toolWindowManager != null && toolWindowManager.showPanel(typeId);
    }

    /** Reveals the Problems panel. What a failing status readout points at. */
    /** @deprecated @see ProblemsExtension#SHOW */
    @Deprecated
    public static final String SHOW_PROBLEMS = ProblemsExtension.SHOW;

    /** Reveals the Notifications panel. */
    /** @deprecated @see NotificationsExtension#SHOW */
    @Deprecated
    public static final String SHOW_NOTIFICATIONS = NotificationsExtension.SHOW;

    /**
     * A workbench with every contributed extension on — which is what a test and a harness scene mean,
     * and what a host meant before applications existed.
     */
    public Workbench(Workspace workspace) {
        this(workspace, null);
    }

    /**
     * @param extensionIds the extensions this workbench enables, in the order named, or null for
     *                     everything contributed. An application's manifest is what names them, which is
     *                     how two applications on one desktop enable different sets
     */
    public Workbench(Workspace workspace, @Nullable List<String> extensionIds) {
        super(NAME);
        if (workspace == null) throw new IllegalArgumentException("A Workbench needs a workspace");
        this.workspace = workspace;
        this.files = workspace.files();
        this.documents = new WorkspaceDocuments(workspace, kinds);
        this.editors = new EditorService(workspace, documents, kinds);
        // AFTER `client`, and it has to be: a field initialiser capturing a constructor-assigned final is
        // a definite-assignment error, not a nullable read.
        // SNAPSHOTS, not live views. Everything below is read from the ANALYSIS thread, inside a compile,
        // while the UI thread is mutating the tree's listing maps and the open-document map. @see #refreshProjectIndexInputs
        this.projectIndex = new ProjectIndex(
                () -> crawledFiles,
                id -> projectRoots.getOrDefault(id, SourceRoots.CONVENTION),
                // A LAMBDA, NEVER `bufferSnapshot::get`. A bound method reference captures the object the
                // field points at WHEN THE REFERENCE IS MADE -- here the empty map, in this constructor --
                // so every snapshot taken afterwards is invisible to it. It compiles, it reads as an
                // accessor, and the buffer tier silently never answers: an open document's unsaved text
                // loses to whatever is on disk, with nothing to see. The two lines above are lambdas for
                // exactly this reason.
                path -> bufferSnapshot.get(path),
                (path, onText) -> workspace.files().readWhole(Resource.of(path))
                        .then(read -> onText.accept(new String(read.bytes(), StandardCharsets.UTF_8)))
                        .onError(error -> onText.accept(null)),
                projectSources::onProjectIndexFilled);
        // REGISTERED HERE, where the field exists. Contributed rather than set, because two workbenches in
        // one process are two projects rather than a fight over one slot -- which is also why this cannot
        // live in registerCommands, which runs once per CLASS: the second workbench would never register
        // its own index and the first would answer from a file tree nobody is looking at.
        ProjectSourcesRegistry.contribute(projectIndex);
        this.projectListing = new WorkspaceTreeSource(workspace);
        // PROBLEMS AS A DECORATION. Everything for this already existed -- the weights, the
        // `.decoration-error` classes, the tree's own resolve-and-apply -- and nothing read Markers.
        //
        // Through `pendingRefresh` rather than a direct refresh, for the reason FileDecorations records:
        // a provider can fire from inside a click handler on a row, and a widget must never rebuild the
        // elements it is being clicked on.
        fileDecorations.addProvider(new DiagnosticDecorations(markers));
        // ONE SIGNAL, BOTH SURFACES. The tree redraws from the decorations' own announcement; the tabs
        // have to be told, because a tab is not a decoration consumer -- it pulls a class when it is
        // built and has no reason to look again on its own.
        lifetime.add(markers.onDidChange.connect(resource -> {
            fileDecorations.invalidate();
            documentTabs.syncTabDecorations();
        }));
        // RENDERED FROM THE RESULT, never from the call site. One update path serves this client's own
        // operations and another client's alike -- see Q11 in the chrome plan, and why two paths into
        // one model always end up disagreeing.
        lifetime.add(files.onDidRun.connect(documentTabs::refreshAfter));
        // AND THE TABS FOLLOW THE DOCUMENTS. @see #followDocuments
        documentTabs.followDocuments();
        // ANOTHER CLIENT'S CHANGES, through the same path as our own -- which is the whole reason the
        // explorer renders from events rather than from its own call sites (Q11). The server pushes
        // fs.changed for anything watched; a create or delete elsewhere shows up here without the tree
        // knowing who did it.
        //
        // A WORKSPACE-WIDE WATCH, recursive, so a create or a delete anywhere shows up. The per-document
        // watches WorkspaceDocuments takes are a different subscription for a different question -- what
        // happened to THIS file -- and the tree must not depend on one of them existing.
        //
        // TAKEN WHEN THE ROOTS LAND, and it used to be taken here. Roots come from the project listing,
        // which is asked for from tickFrame -- after attach, and after a session has opened, because a
        // call made earlier is discarded by the server with no error. So at THIS moment the tree has no
        // roots, on every host, always: the loop that stood here ran over an empty list and watched
        // nothing at all. Nothing failed, and the explorer simply never heard about another client's
        // create, delete or rename outside the files it happened to have open.
        lifetime.add(projectListing.onDidChangeProjects().connect(explorerBinding::watchProjectRoots));
        fileDecorations.addProvider(externalChanges);

        // A RECONNECT INVALIDATES EVERYTHING AT ONCE, and for a different reason than a change does --
        // CrystalOS W11. The client survives a disconnect and rejoin so that a window retained across one
        // comes back working, but every listing it fetched describes a server it is no longer attached
        // to, and no fs.changed can arrive to say so: nothing was watching, because there was nothing to
        // watch with. Wired here beside the notification above so there is one place the tree learns that
        // the far side has moved under it.

        // How a tab presents itself. Both are PULLED by the strip when it builds a tab rather than pushed
        // in afterwards, which is what makes a rebuilt strip correct on the frame it is rebuilt -- a dock
        // rearrangement recreates every tab element, and anything pushed would have to be pushed again by
        // someone who noticed.
        registry.setTitleProvider(documentTabs::tabTitleFor);
        registry.setWindowTitleProvider(documentTabs::windowTitleFor);
        registry.setIconProvider(documentTabs::tabIconFor);
        registry.setIconElementProvider(documentTabs::viewerIconElement);
        registry.setTooltipProvider(DocumentTabs::tabTooltipFor);
        registry.setIconTooltipProvider(documentTabs::tabIconTooltipFor);
        registry.setDecorationProvider(documentTabs::tabDecorationFor);

        opener.contribute(TextFileKind.declare(this));

        dock = new DockArea(registry, defaultLayout());

        // ASKED BEFORE ANYTHING IS DISCARDED. Ctrl+W on an edited file used to throw the work away with no
        // warning at all -- the tab marker said it was modified and nothing acted on that.
        dock.setCloseGuard(saveActions::confirmClose);
        // Two of this widget's per-frame polls, replaced by the announcement they were both watching for.
        // Not registered on a Disposable: the signal belongs to the dock, this workbench owns the dock, so
        // the subscription cannot outlive either -- an ownership registration here would be ceremony.
        lifetime.add(dock.onDidChangeActivePanel.connect(panel -> {
            // THE MOMENT THE REBUILD HAS HAPPENED, which is what a close was waiting for. The frame
            // countdown below is a backstop for the case this signal never comes -- closing a tab that
            // was not the active one leaves the active panel where it was and announces nothing.
            focusActiveEditorAfterClose();
        // ANYTHING ASKED FOR BEFORE IT COULD BE SHOWN. The one-shot hook in connected() covers the frame
        // the workbench joins a window; nothing covered a panel asked for AFTER that and before its
        // region existed -- which is what a server opening a tool window does. Free when the set is
        // empty, which is every frame but the few that matter. @see ToolWindowManager#retryPendingShows
        if (toolWindowManager != null) toolWindowManager.retryPendingShows();
                bindStatusToActiveTab();
        }));
        // The rails' :checked state follows the dock's structure and nothing else, so they can subscribe
        // now. Their BUTTONS wait for a window -- see onWindowChanged.
        for (StripeView stripe : stripes()) stripe.listenToLayout(dock);
        // A CLOSED TAB RELEASES ITS DOCUMENT. Until the dock could announce a close, nothing did: the
        // document stayed open, its editor stayed reachable and anything it owned -- a preview pool, a
        // renderer -- lived until the process did. Disposer could not help, because the thing that knew
        // the tab was gone had no way to say so.
        lifetime.add(dock.onDidClosePanel.connect(documentTabs::releaseClosedPanel));
        // ...AND ITS PLACEHOLDER RECORD, which is keyed by a ref and would otherwise outlive the
        // panel and be read against whatever reopened under the same name.
        lifetime.add(dock.onDidClosePanel.connect(placeholders::remove));
        // AND THE EDITOR THAT TOOK OVER GETS THE FOCUS THE CLOSED ONE HAD. Spent a frame later -- see
        // focusActiveEditorPending.
        lifetime.add(dock.onDidClosePanel.connect(panel -> focusActiveEditorPending = FOCUS_AFTER_CLOSE_FRAMES));
        /*
         * A TAB'S VIEW ARRIVING IS A PANEL THAT HAS TO BE BUILT AGAIN.
         *
         * Opening is asynchronous: a Tab exists immediately in LOADING and is filled when the read
         * lands. The dock builds on the next frame and asks the tab for its view; while the read is in
         * flight there is none, so the factory below returns an empty element as the placeholder for
         * that frame -- and DockGroup MEMOISES what it built, which is right for every other reason and
         * fatal here. Nothing rebuilt it, so the file was blank for ever.
         *
         * `openResource` cannot reach this, because it adds the ref inside the read's own `then`. A
         * SESSION RESTORE can and does: the record names files nothing has read, the dock builds first,
         * and the factory is what starts the read. Every tab from the previous session came back empty.
         *
         * The guard is not "the state changed". This signal also fires on CLEAN -> DIRTY, which is
         * every keystroke, and rebuilding there would detach the editor the user is typing in. It fires
         * only when what is ON SCREEN is not the view the tab now has.
         */
        lifetime.add(editors.onDidChangeState.connect(documentTabs::refreshPanelForTab));
        // PRESENCE MOVES WITHOUT THE TAB MOVING. It was refreshed on a tab change alone, which was
        // enough while nothing ever pushed one -- somebody else opening the file you are looking at
        // changes the answer and changes nothing about which tab is in front.
        documentTabs.registerFailureBanner();
        // Tab dirty markers. Was a per-frame refreshDirtyMarkers(), which meant encoding every open
        // document -- a whole shader graph serialised sixty times a second -- to notice a marker that
        // moves when somebody types. The equality guard SURVIVES the move: the announcement means
        // "content changed", which is not the same as "dirtiness flipped", and only the encode can tell
        // the difference. It just runs once per edit now instead of once per frame.
        lifetime.add(documents.onDidChangeState.connect((document, state) -> {
            CgPath path = document.resource().asPath();
            if (path == null) return;
            documentTabs.refreshDirtyMarkers();
            // AND THE INDEX'S VIEW OF IT. This signal means "content moved", which is exactly when a
            // snapshot of that content stops being true. Marked rather than re-encoded, so a burst of
            // keystrokes costs one encode on the next frame instead of one each.
            dirtyBuffers.add(path);
        }));
        // EVERY FILE FAILURE IS REPORTED FROM ONE PLACE, and this is the whole of the change that made it
        // so. FileOperations already announces each one through onDidFail, carrying the resource --
        // and nothing listened, so all eleven call sites wrote their own `failure -> Notifications.show(...)`
        // instead. That copy is where "created X" and "moved X" ended up on the ERROR channel: the lambda
        // was pasted from the failure branch into the success one with a word changed.
        //
        // Wired here because this is where the workbench's parts are introduced to each other, and because
        // FileOperations must not reach for Notifications itself: it already has an announcement
        // channel, and a service with two would leave a listener unable to tell which was authoritative.
        lifetime.add(files.onDidFail.connect((resource, failure) -> Notifications.show(
                Notification.error("File operation failed")
                        .withDetail(resource.name() + " — " + failure.detail()))));

        // THE BELL'S BADGE. Routed through the container registry rather than reaching for a rail button,
        // because a badge is a fact about a CONTAINER and both rails already listen for it -- so a tool

        // BEFORE content, which is the whole of what puts it at the top -- see the field.
        append(menuBar);
        MainMenuCommands.install(menuBar);
        // The two menu sections that cannot be registered ahead of time. Wired here because this is where
        // the workbench's parts are introduced to each other, and because neither the View menu nor the
        // Window menu may go looking for a workbench itself -- both read it from the data context.
        WorkbenchMenus.register(CommandRegistry.global());

        content.addClass(CONTENT_CLASS);
        append(content);
        // AFTER content, which is the whole of what puts it at the bottom: a workbench is a column and
        // content is the growing child, so order alone decides this and nothing is positioned.
        append(statusBar);
        // The rails sit BESIDE the dock rather than inside it, which is what both originals do and is not
        // merely cosmetic: a stripe inside the dock would be a panel, and therefore droppable onto,
        // draggable and closable. It is chrome -- the thing that gets you back when everything else is
        // closed -- so it must not be something the layout can lose.
        content.append(leftStripe);
        // Named so the stylesheet can give it the remaining width. DockArea carries no class of its own
        // and is not a registered tag, so there is otherwise nothing for a selector to hold onto.
        dock.addClass(DOCK_CLASS);
        // THE DOCK IS THE EDITOR REGION, and the frame is what goes in the workbench. The dock is no
        // longer added directly: it is one region among four, and the other three are fixed slots that
        // hiding cannot collapse away. See WorkbenchRegions.
        regions = new WorkbenchRegions(dock);
        toolWindowManager = new ToolWindowManager(regions, registry);
        content.append(regions.root());
        // AFTER the regions, so the row reads left rail | regions | right rail. Order here is the only
        // thing that puts the right-hand stripe on the right: it is an ordinary flex child, not something
        // positioned, and both rails carry the same fixed width.
        content.append(rightStripe);
        // AFTER the regions and BEFORE the drop overlay. Balloons must float over the workbench, but not
        // over a live drag: the overlay is what tells you where a panel will land, and a message arriving
        // mid-drag must not cover the answer.
        content.append(balloons);
        // LAST, so it draws over everything it covers, and listening on `content` so it hears a drag
        // anywhere in the workbench -- DragEvent.Over bubbles, which is what makes one listener enough.
        content.append(dropOverlay);
        dropOverlay.listenOn(content);
        // The overlay ANNOUNCES where a drag is aiming; each rail decides what that means for its own gap
        // and its own ghost. Wired here because this is where the workbench's parts are introduced to
        // each other -- neither of them goes looking for the other.
        for (StripeView stripe : stripes()) stripe.listenToDrag(dropOverlay);
        // THE ENGINE'S OWN THREE, each in ONE declaration -- the descriptor, the icon, where it
        // lands, what builds it, whether it is open on a fresh workspace, the command that reveals it
        // and, for notifications, the badge. Anchors match where the default arrangement puts them, so
        // closing a panel and reopening it from the activity bar lands it back where it was rather than
        // somewhere merely legal.
        // EXTENSIONS LAST, when everything they may reach has been built.
        //
        // WHAT THE APPLICATION ASKED FOR, and everything contributed when nobody asked -- a workbench
        // built directly, by a test or a scene, still gets the lot. What this settles is which HOST
        // remembered what: the Notes kind was registered by two harness scenes and by no loader, so a
        // file type shipped in this repository opened in the harness and not in the game.
        activeExtensions.addAll(WorkbenchExtensions.activate(this, extensionIds));
    }

    /**
     * Project down the left, documents in the middle, Problems beneath.
     *
     * <p>Authored rather than accumulated. A split halves the <em>target's</em> share and gives the other
     * half to the newcomer, so building this in the obvious order would hand half the screen to the file
     * tree — a default layout has to state what it wants.</p>
     */
    /**
     * The work area, and nothing else — <b>documents only</b>.
     *
     * <p>It used to drop Project into a left leaf and Problems into a bottom one, which is what made the
     * dock tree responsible for tool windows and cost the four-tier restoration heuristic. Those two are
     * regions now: {@link DockRegion#SIDEBAR} and {@link DockRegion#PANEL}. See {@link WorkbenchRegions}.</p>
     */
    private DockLayout defaultLayout() {
        DockLeaf centre = new DockLeaf();
        centre.setCentral(true);
        return DockLayout.of(centre);
    }

    /** The status line along the bottom. @see StatusBarView */
    /** The main menu bar. @see MenuBarView */
    public MenuBarView menuBar() {
        return menuBar;
    }

    /** The recently-opened files. @see RecentFiles */
    public RecentFiles recentFiles() {
        return recentFiles;
    }

    public StatusBarView statusBarView() {
        return statusBar;
    }

    /**
     * Where private records go. Null until an application says, and everything derived from it is then
     * absent — which is an ordinary state, not a broken one.
     */
    @Nullable
    private ConfigStorage storage;

    /**
     * Gives this workbench somewhere private to keep derived output.
     *
     * <p>Called by the application that owns the store, never by a host: where a private directory IS
     * is a host fact, and which parts of one an application uses is the application's.</p>
     */
    public Workbench useConfig(@Nullable ConfigStorage storage) {
        this.storage = storage;
        return this;
    }

    @Override
    @Nullable
    public Path cacheDirectory(String name) {
        Path root = storage == null ? null : storage.directory();
        return root == null ? null : root.resolve(name);
    }

    /** The bar an entry goes on. @see UiDataKeys#STATUS_BAR */
    @Override
    public StatusBar statusBar() {
        return statusBarModel;
    }

    public DockArea dock() {
        return dock;
    }

    /**
     * Everything this workbench subscribed to while it was being built.
     *
     * <p>Held rather than dropped because most of it is on something that outlives a workbench: the
     * {@link Workspace} is per connection, {@code Notifications} is per process, and a listener on
     * either keeps this whole element tree reachable. {@code capabilityWatch} beside it has said so
     * since it was written — <i>"a workbench that subscribed from its constructor would stay reachable
     * for ever"</i> — and was the only subscription anybody had applied it to.</p>
     */
    private final ConnectionGroup lifetime = new ConnectionGroup();

    /** What each {@link WorkbenchExtension} handed back from {@code activate}. @see #dispose() */
    private final List<Disposable> activeExtensions = new ArrayList<>();

    /** One recursive watch per project root, keyed by the root it covers. @see #watchProjectRoots */
    public final Map<CgPath, RootWatch> rootWatches = new HashMap<>();

    /**
     * Lets go of everything: the listeners, the watches, the registry entry, and the open tabs.
     *
     * <p>Called by whatever built this — an application — and never by the tree, because a workbench
     * that is merely detached is a hidden window and must come back working. The rule this closes is
     * the one {@code CgUiScreen.disposeAll}'s javadoc has always stated and nothing performed.</p>
     *
     * <p>Idempotent through its parts: a {@code ConnectionGroup} clears itself, a {@code Watch} counts
     * its holders, and {@code EditorService.closeAll} is written to be safe twice.</p>
     */
    @Override
    public void dispose() {
        // THE TOOL WINDOWS THIS WORKBENCH DECLARED, whose badges are subscriptions on process-wide
        // signals and whose commands are in the global registry.
        for (Disposable handle : new ArrayList<>(toolWindowHandles)) handle.dispose();
        toolWindowHandles.clear();
        // EXTENSIONS FIRST, in reverse activation order -- a later one may have been built from an
        // earlier one's contribution, which is the same argument Disposer makes about children.
        for (int i = activeExtensions.size() - 1; i >= 0; i--) activeExtensions.get(i).dispose();
        activeExtensions.clear();
        lifetime.disconnectAll();
        markerWatch.disconnectAll();
        capabilityWatch.disconnectAll();
        for (RootWatch watch : rootWatches.values()) watch.dispose();
        rootWatches.clear();
        // WITHDRAWN, not left: this is a process-wide list, and an index kept in it after its workbench
        // is gone answers questions about a workspace nobody is looking at -- with the whole tree behind
        // it still reachable.
        ProjectSourcesRegistry.remove(projectIndex);
        // The tabs, and with them every DocumentReference they hold: a document is disposed by its LAST
        // holder, so a workbench that never closed its tabs kept every file it had ever opened.
        editors.dispose();
        // AND THE STORE UNDER THEM. It subscribes to the WORKSPACE, which outlives every workbench on
        // it -- so leaving it connected keeps this workbench's DocumentKinds, and a kind's model factory
        // captures the workbench. Measured: that is the path a heap walk finds from a process-wide
        // static to a workbench that has already been disposed.
        documents.dispose();
        // AND THE RAILS' OWN COMMANDS, which are in the WINDOW's registry rather than the global one --
        // so they outlive this workbench by exactly as long as the surface does, each one capturing it.
        for (StripeView stripe : stripes()) stripe.dispose();
    }

    /**
     * A workspace-wide watch and the listener reading it, released together.
     *
     * <p>Both halves are needed: a {@code Watch} is shared by everything that asked for the same
     * resource, so disposing it only unwatches when the last holder lets go — and until then this
     * workbench's listener would go on being called on a signal it no longer has any business reading.</p>
     */
    public static final class RootWatch implements Disposable {
        private final Workspace.Watch watch;
        private final Connection listener;

 public RootWatch(Workspace.Watch watch, Connection listener) {
            this.watch = watch;
            this.listener = listener;
        }

        @Override
        public void dispose() {
            listener.disconnect();
            watch.dispose();
        }
    }


    public DockPanelRegistry<UIElement> panels() {
        return registry;
    }

    /** @see #windowMount */
    @Nullable
    private NetworkedPanels networkedPanels;

    /**
     * <b>Where a server's windows land on this workbench</b> — a tab, a rail, or the desktop.
     *
     * <pre>{@code
     * ClientWindows.of(connection).setMount(workbench.windowMount(desktopMount));
     * }</pre>
     *
     * <p>Install it as the client's one {@link WindowMount}. It honours the
     * placement each server names and hands everything else — including anything it does not recognise
     * — to {@code desktop}, so a window always opens somewhere. A host with no workbench installs its
     * desktop mount directly and every window opens there, which is the hint working rather than
     * failing.</p>
     *
     * <p>One per workbench, built on first ask: the manifest of what has been seen is what a restore
     * reads, and a second instance would restore a layout it had no descriptors for.</p>
     */
    public NetworkedPanels windowMount(@Nullable WindowMount desktop) {
        if (networkedPanels == null) networkedPanels = new NetworkedPanels(this, desktop);
        return networkedPanels;
    }


    /** The networked panels on this workbench, or null before anything asked for a mount. */
    @Nullable
    public NetworkedPanels networkedPanels() {
        return networkedPanels;
    }

    /** @see WorkbenchContext#projectListing */
    @Override
    public WorkspaceTreeSource projectListing() {
        return projectListing;
    }


    /** Adds a host's own panel type — a shader graph, a console, an inspector. */
    /**
     * One declaration, and everything a tool window needs derived from it.
     *
     * <p>What this replaces, per panel: a {@code DockPanelDescriptor} with its icon and placement, a
     * factory, an entry per view, a {@code showPanel} for the default arrangement, a command registered
     * by hand in a method that runs before the fields exist, a key binding, and a badge subscription
     * wired wherever its source happened to live. Five places, per A6 — and a panel that forgot one of
     * them failed in a way that named none of the others.</p>
     *
     * <p><b>The view is built once, lazily.</b> The dock asks for content whenever it rebuilds a strip,
     * and a factory that answered a new element each time would hand the user a fresh empty panel every
     * time they dragged a tab.</p>
     */
    @Override
    public Disposable registerToolWindow(ToolWindowKind kind) {
        DockPanelDescriptor descriptor = DockPanelDescriptor.singleton(kind.id(), kind.displayName());
        if (kind.icon() != null) descriptor.icon(kind.icon());
        if (kind.region() != null) descriptor.region(kind.region());
        if (kind.side() != null) descriptor.side(kind.side());
        if (kind.anchor() != null) descriptor.anchor(kind.anchor());

        for (ToolWindowKind.View view : kind.views()) {
            toolWindowManager.viewContainers().addView(kind.id(), view.viewId(), view.title(),
                    () -> built(kind, view.viewId(), view.factory()));
        }
        Function<WorkbenchContext, UIElement> single = kind.singleView();
        registry.register(descriptor, ref -> single == null
                ? new UIElement() : built(kind, kind.id(), single));

        String command = kind.toggleCommand();
        if (command != null) {
            // GLOBAL, and resolved from the data context rather than captured: a captured workbench
            // makes a second window's command toggle a panel in the first.
            if (!CommandRegistry.global().contains(command)) {
                CommandRegistry.global().register(Command.of(command, "Show " + kind.displayName())
                        .runWithData(data -> {
                            Workbench workbench = data.get(WORKBENCH);
                            if (workbench != null) workbench.revealPanel(kind.id());
                        })
                        .enabledWhereData(data -> data.get(WORKBENCH) != null));
            }
            if (kind.accelerator() != null) keymap().bind(kind.accelerator(), command);
        }

        ToolWindowKind.Badge badge = kind.badgeSource();
        Disposable badgeWatch = badge == null ? null : badge.install(this,
                text -> toolWindowManager.viewContainers().setBadge(kind.id(), text));

        // A DEFAULT, never a rule: a placement restored from a session outranks it, which is what makes
        // dragging a panel to the other rail stick.
        if (kind.isOpenByDefault()) toolWindowManager.showPanel(kind.id());

        Disposable handle = new Disposable() {
            private boolean withdrawn;

            @Override
            public void dispose() {
                if (withdrawn) return;
                withdrawn = true;
                if (badgeWatch != null) badgeWatch.dispose();
                // THE COMMAND AND ITS KEY, which are process-wide and would otherwise point at a panel
                // type nothing builds. The panel type and its views stay: the registry holding them
                // belongs to this workbench and goes when it does.
                if (command != null) CommandRegistry.global().unregister(command);
                toolWindowHandles.remove(this);
            }
        };
        // HELD AS WELL AS RETURNED, because a workbench registers three of these itself and there is
        // nobody else to dispose those. A badge is a subscription on a process-wide signal, so leaving
        // one behind keeps the whole workbench reachable -- which is exactly what the retention test
        // caught the moment the notification badge became a kind's rather than a line in the ctor.
        toolWindowHandles.add(handle);
        return handle;
    }

    /** Every {@link SessionSlice} an extension claimed, in registration order. @see WorkbenchSession */
    private final List<SessionSlice> sessionSlices = new ArrayList<>();

    /** @see WorkbenchContext#registerSessionSlice */
    @Override
    public Disposable registerSessionSlice(SessionSlice slice) {
        if (slice == null) return () -> { };
        sessionSlices.add(slice);
        return () -> sessionSlices.remove(slice);
    }

    /** What {@code WorkbenchSession} writes and reads. Package-private: it is the record's, not an API. */
    List<SessionSlice> sessionSlices() {
        return sessionSlices;
    }

    /** Every {@link #registerToolWindow} handle, so this workbench can withdraw its own. */
    private final List<Disposable> toolWindowHandles = new ArrayList<>();

    /** One element per view, memoised: the dock asks again on every strip rebuild. */
    private UIElement built(ToolWindowKind kind, String viewId,
                            Function<WorkbenchContext, UIElement> factory) {
        UIElement built = toolWindowViews.get(viewId);
        if (built != null) return built;
        built = factory.apply(this);
        if (kind.isPersistent()) built.set(Attribute.SESSION_PERSISTENT, true);
        toolWindowViews.put(viewId, built);
        return built;
    }

    /** What {@link #registerToolWindow} has built, so a rebuilt strip gets the same panel back. */
    private final Map<String, UIElement> toolWindowViews = new HashMap<>();

    public Workbench registerPanel(DockPanelDescriptor descriptor,
                                   Function<DockPanelRef, UIElement> factory) {
        registry.register(descriptor, factory::apply);
        return this;
    }

    /**
     * Tool windows — extracted to {@link ToolWindowManager}.
     *
     * <p>These four stay on {@code Workbench} as delegates because they are what a command and an activity
     * bar button call, and moving the <em>call sites</em> is a separate change from moving the logic. The
     * 220 lines behind them — the four-tier restoration heuristic, placement capture, the outer-edge
     * derivation — are gone from this class. See {@code plan.md} §23 F1.</p>
     */
    public boolean isPanelOpen(String typeId) {
        return toolWindowManager.isPanelOpen(typeId);
    }

    /** @see ToolWindowManager#togglePanel */
    public boolean togglePanel(String typeId) {
        return toolWindowManager.togglePanel(typeId);
    }

    /** @see ToolWindowManager#hidePanel */
    public boolean hidePanel(String typeId) {
        return toolWindowManager.hidePanel(typeId);
    }

    /** @see ToolWindowManager#showPanel */
    public boolean showPanel(String typeId) {
        return toolWindowManager.showPanel(typeId);
    }

    /** @see ToolWindowManager#toolWindows */
    public ToolWindowLayout toolWindows() {
        return toolWindowManager.toolWindows();
    }

    /** The tool-window half, for anything that wants it directly rather than through the delegates. */
    @Override
    public ToolWindowManager toolWindowManager() {
        return toolWindowManager;
    }




    // ── Files ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Which editor opens which file — pattern to dock panel type.
     *
     * <p>Per workbench rather than static, and that is not caution: a panel type id only means anything
     * against the {@code DockPanelDescriptor} registry that defined it, and that registry belongs to this
     * workbench. A global map would let one window bind a type the other cannot build.</p>
     */
    public final FilePatternMap<String> editorBindings = new FilePatternMap<>();


    /** As {@link #bindEditorExtensions}, for files identified by their whole name — {@code Dockerfile}. */
    public Workbench bindEditorNames(String typeId, String... fileNames) {
        editorBindings.putNames(typeId, fileNames);
        return this;
    }

    /** As {@link #bindEditorExtensions}, for a glob over the whole name — {@code *.g.dart}. */
    public Workbench bindEditorGlobs(String typeId, String... globs) {
        editorBindings.putGlobs(typeId, globs);
        return this;
    }


    /**
     * Which panel type shows this resource — the binding, or the fallback text editor.
     *
     * <p>Resolution is {@link FilePatternMap}'s — exact name, then extension, then glob — asked of the
     * resource's NAME rather than its path, for the reason {@code DocumentKind.matches} records: a
     * project resource's path carries its project prefix, so a last-segment split answers the whole
     * string for a file at a project root.</p>
     */
    private String typeFor(Resource resource) {
        String bound = editorBindings.get(resource.name());
        return bound == null ? FILE_TYPE : bound;
    }





    public void runWhenReady(EditorService.Tab tab, @Nullable Runnable then) {
        if (then == null) return;
        editors.activate(tab);
        then.run();
    }

    // ── Opening things that are not project files ──────────────────────────

    /**
     * The one Go to File picker, kept between invocations.
     *
     * <h3>Why it is held here rather than by {@code GoToFile}</h3>
     *
     * <p>Because it has to be held <em>somewhere</em> for its query to survive a close, and the two
     * alternatives are worse. A static on {@code GoToFile} is shared by every window in the process, so
     * two workbenches would fight over one popup and one of them would find it parented elsewhere — the
     * same class of bug {@code JobScheduler.shared()} caused in this session's tests. Rebuilding it per
     * open is what it did before and is what makes retention impossible.</p>
     *
     * <p>It stays attached and {@code display: none} while closed, like any closed popover, so there is
     * nothing to dispose and nothing to reattach.</p>
     */
    @Nullable
    private QuickPick quickOpen;

    /** @see #quickOpen */
    @Nullable
    public QuickPick quickOpen() {
        return quickOpen;
    }

    /** @see #quickOpen */
    public Workbench setQuickOpen(@Nullable QuickPick picker) {
        this.quickOpen = picker;
        return this;
    }


    /**
     * Where this workbench schedules background work — the shared pool unless a caller says otherwise.
     *
     * <h3>Injectable because {@code JobScheduler}'s own note says so</h3>
     *
     * <p>"Tests construct their own instead, which is what the injecting constructor is for", and "a
     * same-thread executor makes every test deterministic". Reaching for {@code shared()} inside the
     * viewer's read ignored both, and it cost three separate rounds of chasing a test that passed alone
     * and failed in its class: a job submitted by one test completes during the NEXT one's drain, so a
     * viewer is filled late, or a completion the next test was waiting for is consumed by the previous
     * one. Nothing about the symptom points at the scheduler — it reads as the feature being flaky.</p>
     */
    private JobScheduler jobs = JobScheduler.shared();

    private JobScheduler jobs() {
        return jobs;
    }

    /** @see #jobs */
    public Workbench setJobScheduler(@Nullable JobScheduler scheduler) {
        this.jobs = scheduler == null ? JobScheduler.shared() : scheduler;
        return this;
    }

    /**
     * The panel ref for a resource — a pure function of it, as {@link #refFor} is of a path.
     *
     * <p><b>One state key for everything.</b> A viewer panel used to carry its own, on the reasoning
     * that {@link #PATH_STATE} is parsed back as a {@code CgPath} on restore and putting a
     * {@code library://…} through it would ship a landmine that detonates on the next session. That was
     * true and is no longer: the state is parsed as a {@link Resource}, and {@code Resource.parse}
     * answers a project resource for anything with no {@code ://} in it — which is byte-for-byte what
     * {@code CgPath} already wrote. Every saved layout keeps restoring.</p>
     */
    public DockPanelRef refForResource(Resource resource) {
        return new DockPanelRef(typeFor(resource))
                .withState(PATH_STATE, resource.toString())
                .withState(DockPanelRef.TITLE, titleOf(resource));
    }

    /**
     * What a tab is called — the simple name, which is what a tab strip has room for.
     *
     * <p>{@code library://java.util.ArrayList} becomes {@code ArrayList}; the package is what the
     * breadcrumb and the tooltip are for. A provider that knows better says so, which is the difference
     * between a tab reading {@code ArrayList.java} and one reading {@code FlexDirection.class} — how
     * IntelliJ says the same thing, and how a reader tells at a glance whether they are looking at what
     * somebody wrote.</p>
     */
    String titleOf(Resource resource) {
        ContentProvider provider = workspace.providerFor(resource);
        String named = provider == null ? null : provider.displayName(resource);
        if (named != null) return named;
        if (resource.isProject()) return resource.name();
        String path = resource.path();
        int dot = path.lastIndexOf('.');
        return dot < 0 || dot == path.length() - 1 ? path : path.substring(dot + 1);
    }






    /** The file behind the active tab, or null when the active tab is not a file. */
    @Nullable
    public CgPath activeFilePath() {
        DockGroup group = dock.activeGroup();
        if (group == null) return null;
        DockPanelRef panel = group.leaf().activePanel();
        // A PANEL WITH A PATH, not a panel of one particular type. Binding an extension to its own editor
        // gives that tab a different type id, so testing for FILE_TYPE made every bound document report
        // "no file tab active" -- unsaveable by the very mechanism that opened it.
        if (panel == null) return null;
        Resource resource = documentTabs.viewedResource(panel);
        return resource == null ? null : resource.asPath();
    }

    /** What the active tab shows, project file or not. */
    @Nullable
    public Resource activeResource() {
        DockGroup group = dock.activeGroup();
        if (group == null) return null;
        DockPanelRef panel = group.leaf().activePanel();
        return panel == null ? null : documentTabs.viewedResource(panel);
    }

    /** The active document, whatever kind it is. */
    @Nullable
    public Document activeDocument() {
        Resource resource = activeResource();
        return resource == null ? null : documents.get(resource);
    }

    /**
     * The active document's editor when it is a TEXT document, else null.
     *
     * <p>Null for a graph or an image rather than throwing: the callers that want a text editor - jumping
     * to a diagnostic's line - have nothing to do with a document that has no lines.</p>
     */
    @Nullable
    /**
     * A tab was closed and the editor that took its place has not been focused yet.
     *
     * <p>@see #focusActiveEditorAfterClose</p>
     */
    private int focusActiveEditorPending;

    /**
     * Puts the focus the closed tab held onto the editor that replaced it.
     *
     * <h3>Why this is needed at all</h3>
     *
     * <p>Closing a tab detaches the editor that had focus, and {@code UIInputHandler} correctly forgets a
     * detached element — so the focus owner becomes <b>null</b> and the keyboard goes nowhere. Every part
     * is behaving: the dock does not know what a document is, and the input handler is right to drop a
     * reference to something that left the tree. Nobody was left holding the question "and now who has
     * it?", which is why Ctrl+W ended with the caret in no editor at all.</p>
     *
     * <h3>A frame later, and only when nobody else took it</h3>
     *
     * <p>Deferred because {@code requestRebuild} only sets a flag: at the moment the close is announced
     * the strip has not been rebuilt and the panel that is about to become active has not been retargeted,
     * so there is nothing yet to focus.</p>
     *
     * <p>Gated on the focus owner being <b>null</b>, which is what keeps this from being the auto-focus
     * coupling that was just taken out of the project tree. Closing a background tab from a menu, or
     * closing one while the caret is in the terminal, leaves focus exactly where the user put it — this
     * only fills a vacuum, it never takes.</p>
     */
    private void focusActiveEditorAfterClose() {
        if (focusActiveEditorPending <= 0) return;
        UIDocument window = document();
        if (window == null) return;
        // SOMEBODY ELSE HAS IT, so there is no vacancy to fill and nothing more to wait for.
        if (window.focus().focused() != null) {
            focusActiveEditorPending = 0;
            return;
        }
        // A FEW FRAMES, not one. `requestRebuild` only sets a flag, and the dock rebuilds from its own
        // tick -- which may run after this one. Spending the request on the first frame therefore asked
        // `activeEditor()` before the strip had been rebuilt and the pane retargeted, got null, and threw
        // the request away: Ctrl+W left the focus nowhere, which is exactly what it did before any of this
        // was written. Counting down instead means the frame ordering between two tickers does not have to
        // be assumed.
        focusActiveEditorPending--;
        TextEditor editor = activeEditor();
        if (editor == null || editor.document() == null) return;
        focusActiveEditorPending = 0;
        window.focus().requestFocus(editor);
    }

    /**
     * How many frames a close may take to settle before the focus request is dropped.
     *
     * <p>Bounded on purpose: this is covering an ordering between two tickers, not waiting for I/O, and
     * holding the request open indefinitely would mean pouncing on the first vacancy that appeared long
     * afterwards.</p>
     *
     * <p><b>Twelve rather than four, and the difference was a flaky test.</b> Four covered the ordering on
     * an idle JVM and not on a loaded one: {@code closingTheFocusedTabFocusesTheEditorThatReplacesIt}
     * passed alone and failed in the full suite, which is the shape of a race rather than of a wrong
     * answer. The rebuild is what is being waited for and it takes as long as it takes; the bound exists
     * to stop the request outliving the close, not to express how long a rebuild should need.</p>
     */
    private static final int FOCUS_AFTER_CLOSE_FRAMES = 12;

    public TextEditor activeEditor() {
        return editorFor(activeResource());
    }

    /**
     * The text editor showing a resource, or null when nothing does or it is not text.
     *
     * <p>Through the tab rather than a map of our own: {@link EditorService} builds the view lazily and
     * holds it, so a second map here would be a copy that drifts on every close and every restore —
     * which is exactly what the viewer lane's own {@code viewers} map was.</p>
     */
    @Nullable
    public TextEditor editorFor(@Nullable Resource resource) {
        if (resource == null) return null;
        EditorService.Tab tab = editors.tabFor(EditorInput.of(resource));
        if (tab == null) return null;
        DocumentEditor view = tab.editor();
        return view instanceof TextEditorView text ? text.editor() : null;
    }

    /** The document currently told it is active, so the previous one can be told it is not. */
    @Nullable
    private Document activeStatusDocument;

    /**
     * Announces which tab is in front, and sets the trail. <b>That is all it does.</b>
     *
     * <h3>What the workbench is and is not entitled to know</h3>
     *
     * <p>This used to write the caret position, line ending, encoding and indent width itself. It worked,
     * and it does not scale: those are a <em>text file's</em> facts, so a shader graph, a diff or an image
     * would each need another branch here — in a codebase whose whole direction is that a document type is
     * a contribution rather than a case in a switch.</p>
     *
     * <p>The one thing no document can work out for itself is which tab is active. So that is what is
     * said, through {@code DocumentEditor.activated}, and each view publishes and withdraws its own
     * items. Both references draw the line in the same place; see that method.</p>
     *
     * <p>The breadcrumb trail stays here, and is not the same kind of thing: it describes the tab's
     * <em>identity</em> — where the thing you are looking at lives — which is the dock's business and is
     * answerable for a document that has no content to report at all.</p>
     */
    private void bindStatusToActiveTab() {
        statusBar.breadcrumbs().setCrumbs(saveActions.trailFor(activeFilePath()));

        Document active = activeDocument();
        if (active == activeStatusDocument) return;
        // DEACTIVATE FIRST. Both halves write status items, and a view that publishes before the
        // previous one has withdrawn would have its keys cleared a moment later by the tab it replaced.
        setViewActive(activeStatusDocument, false);
        activeStatusDocument = active;
        setViewActive(active, true);
    }

    private void setViewActive(@Nullable Document document, boolean active) {
        if (document == null) return;
        EditorService.Tab tab = editors.tabFor(EditorInput.of(document.resource()));
        DocumentEditor view = tab == null ? null : tab.editor();
        if (view != null) view.activated(active);
    }





    /**
     * The badge an externally-changed or deleted file carries — Phase 6.3.
     *
     * <p>Through {@code FileDecorations} rather than a second marking mechanism, so it reaches the tab
     * <b>and</b> the file tree from one place, merges with the other providers by weight, and bubbles to
     * the containing folder like everything else. A decoration written straight onto the tab would have
     * needed a second one for the tree, and the two would have disagreed.</p>
     *
     * <p><b>It bubbles</b>, so a folder says one of its files moved without the user opening it. The
     * recorded rule applies: the colour climbs and the badge does not — a folder wearing a
     * {@code ✕} would be claiming the folder itself was deleted.</p>
     */
    private final FileDecorationProvider externalChanges = new FileDecorationProvider() {
        @Override
        public String label() {
            return "Changed on the server";
        }

        @Override
        @Nullable
        public FileDecoration decorationFor(CgPath path) {
            if (externallyDeleted.contains(path)) {
                return FileDecoration.of(90, "decoration-deleted", "✕", "Deleted on the server")
                        .withStrikethrough(true).withBubble(true);
            }
            if (externallyChanged.contains(path)) {
                return FileDecoration.of(80, "decoration-conflict", "!",
                        "Changed on the server since you opened it").withBubble(true);
            }
            return null;
        }

        @Override
        public Collection<CgPath> decorated() {
            if (externallyChanged.isEmpty() && externallyDeleted.isEmpty()) return List.of();
            List<CgPath> all =
                    new ArrayList<>(externallyChanged.size() + externallyDeleted.size());
            all.addAll(externallyChanged);
            all.addAll(externallyDeleted);
            return all;
        }
    };

    /** Files that moved on the server under a DIRTY buffer, so the tab can say so. @see #externalChange */
    public final Set<CgPath> externallyChanged = new LinkedHashSet<>();

    /** Files deleted on the server while still open here. @see #externalChange */
    public final Set<CgPath> externallyDeleted = new LinkedHashSet<>();





    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }





    /** Every file operation, each answering a {@link Reply}. */
    public FileOperations files() {
        return files;
    }

    /** The open documents, and the wire underneath them. */
    public WorkspaceDocuments documents() {
        return documents;
    }

    /** The tabs over those documents — the one open lane. */
    public EditorService editors() {
        return editors;
    }

    /** Every kind of document this workbench can open. @see #contribute */
    public DocumentKinds kinds() {
        return kinds;
    }

    /**
     * The projects and their listings.
     *
     * <p>The explorer's tree source, named by what it IS rather than by the widget that holds it. Five
     * things outside the explorer read it -- this class for the crawl and the roots, the session for
     * its expansion retry, the settings, Go to File and the editor -- so it was already a service in
     * everything but who owned it. The physical split of the 864-line class follows; naming it now is
     * what lets an extension be written against the model instead of against a widget's field.</p>
     */
    /**
     * What the explorer draws beside a file.
     *
     * <p>Named here because a decoration is contributed by whoever knows the fact — the language stack
     * marks what a script is, the shader graph marks a generated file — and none of them should have to
     * reach through the explorer's widget to say so. It was the eleventh and last thing {@code language/}
     * needed from the engine that the context did not already carry.</p>
     */
    @Override
    public FileDecorations decorations() {
        return fileDecorations;
    }

    @Override
    public WorkspaceProjects projects() {
        return projectListing;
    }

    /** The workspace this workbench is a view of. */
    @Override
    public Workspace workspace() {
        return workspace;
    }







    /**
     * What the panel factory last built a <b>placeholder</b> for, by ref — and no entry once a real
     * view is up.
     *
     * <p>One writer (the factory) and one reader ({@link #refreshPanelForTab}), so the two cannot drift.
     * The alternative was to interrogate the element on screen, which cannot survive a banner wrapping
     * it and would rebuild the editor on every keystroke the moment one did.</p>
     */
    public final Map<DockPanelRef, DocumentState> placeholders = new HashMap<>();





    // ── Unsaved changes (E16) ───────────────────────────────────────────────────────────────────

    /**
     * The marker a modified tab carries, appended to its name.
     *
     * <p>An asterisk rather than a bullet or a dot, deliberately. The bundled Minecraft fonts are missing
     * codepoints that read as obvious choices here — {@code MinecraftRegular.otf} has no U+2026, which is
     * why {@code text-overflow} falls back to three periods — and a marker that renders as a blank advance
     * is worse than none, because the tab then looks clean while the file is not. An asterisk is ASCII and
     * cannot go missing. It is also what a good half of editors use.</p>
     */
    public static final String DIRTY_MARKER = " *";




















    /**
     * A document was released, because the last tab showing it closed.
     *
     * <p>The counterpart of {@link #onDidOpenDocument}, and the half that did not exist — which is why
     * nothing could clean up after a close.</p>
     */
    public final Signal.Value<CgPath> onDidCloseDocument = new Signal.Value<>();

    /**
     * This workbench has joined a {@code UIDocument} — emitted <b>one frame after</b> the attach.
     *
     * <p>For anything that needs a window and may legitimately be asked for before there is one. A host
     * may restore its session on its first frame, which is before {@code UIDocument.init} has run, so a
     * windowed tool window and a torn-out editor window both have to be remembered and replayed.</p>
     *
     * <p><b>A frame later, never inside the attach hook.</b> {@code onWindowChanged} fires during the
     * attach walk, so the rest of the subtree may not be registered yet and anything built there inserts
     * a Taffy node into a parent whose children are still being registered — the {@code Index (is 1)
     * should be < child_count (0)} crash. Same rule {@code ProjectFileTree}'s deferred refresh follows.
     * </p>
     */
    public final Signal.Action onDidJoinWindow = new Signal.Action();


    List<CgPath> lastDirty = new ArrayList<>();


    /**
     * Fires when a file's content has been applied to its document.
     *
     * <p>The one moment at which anything derived from the content — a restored caret, a fold set, a
     * diagnostic pass — can act. There is deliberately no signal for "a panel was created": that happens
     * while the read is still in flight.</p>
     */
    public final Signal.Value<CgPath> onDidOpenDocument = new Signal.Value<>();

    /**
     * The same signal, as an accessor.
     *
     * <p>The engine's idiom is a {@code public final Signal} field and an interface cannot carry one, so
     * {@link WorkbenchContext} declares it as a method — the same trade {@code WorkspaceProjects} makes,
     * for the same reason: a consumer written against the context does not name the engine.</p>
     */
    @Override
    public Signal.Value<CgPath> onDidOpenDocument() {
        return onDidOpenDocument;
    }

    /**
     * The document for a path, or null when nothing has it open.
     *
     * <p><b>It no longer CREATES one.</b> Asking by path used to build it, so a caller that merely wanted
     * to look ended up reading the file — which is why {@code openPaths} carried a paragraph warning that
     * walking it would build the whole session. Opening is {@link EditorService#open}, and it is
     * asynchronous because reading a file is.</p>
     */
    @Nullable
    public Document documentFor(CgPath path) {
        return documents.get(Resource.of(path));
    }

    /**
     * Every file with an open document, in no particular order.
     *
     * <p>Open in the sense of "has a document", which is not the same as "has a tab": a file whose tab was
     * closed while a save was in flight still has one, and a session record wants both.</p>
     *
     * <p><b>And the gap now points both ways.</b> Since {@code DockGroup} builds a tab's content on first
     * activation, a session restored with five files open has five tabs and <em>one</em> document — so
     * this is a subset of {@link #openTabPaths()} as often as it is a superset. Every caller here wants
     * this one, and wants it for the same reason: they have something to do to a live editor
     * ({@code upgradeServices}, the settings sweep) or something to read off a live document (the session
     * record's view state). None of them would be improved by being handed a path with nothing behind it
     * — and it costs nothing to ask, since {@link #documentFor} no longer builds anything.</p>
     */
    public List<CgPath> openPaths() {
        List<CgPath> paths = new ArrayList<>();
        for (Document document : documents.all()) {
            CgPath path = document.resource().asPath();
            if (path != null) paths.add(path);
        }
        return paths;
    }

    /**
     * Every file with a <b>tab</b>, built or not, in strip order across every group.
     *
     * <p>The counterpart to {@link #openPaths()}, and the one that answers "what is open" the way a user
     * would mean it: a restored tab is a title until something activates it, and it is no less open for
     * having no widget behind it yet. Read off the dock's own panel refs, which carry the path — so it
     * costs a walk and builds nothing.</p>
     */
    public List<CgPath> openTabPaths() {
        List<CgPath> paths = new ArrayList<>();
        for (DockLeaf leaf : dock.layout().leaves()) {
            for (DockPanelRef panel : leaf.panels()) {
                String path = panel.state(PATH_STATE, "");
                if (path.isEmpty()) continue;
                Resource resource = Resource.parse(path);
                CgPath parsed = resource.asPath();
                if (parsed == null) continue;
                // The same file can be open in two groups -- a split of one document is two tabs and one
                // document -- and this is a set of files, not of tabs.
                if (!paths.contains(parsed)) paths.add(parsed);
            }
        }
        return paths;
    }


    /** The text editor for a path, or null when that file is not opened by a text editor. */
    @Nullable
    public TextEditor editorFor(CgPath path) {
        return editorFor(Resource.of(path));
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────────────

    /**
     * Stops ticking, so a workbench that is off screen does nothing.
     *
     * <p><b>It does not withdraw its own {@code DataProvider}</b>, and the missing line is deliberate:
     * {@code document()} answers null in here — the callback is queued and the field cleared before the
     * queue drains — so the {@code if (leaving != null)} that used to sit on this line was dead code on
     * every node in the engine, and every workbench ever attached stayed in {@code scopeProviders} with
     * its whole tree behind it. The engine drops a document-level provider at detach now, which is what
     * {@code removeDataProvider}'s own javadoc always said the rule was.</p>
     */
    @Override
    protected void disconnected() {
        super.disconnected();
        // CLEARED, or the panel never ticks again. `Animation` drops a hook on the first tick after
        // its owner disconnects, so a tool window -- which is hidden and reshown rather than rebuilt
        // -- would come back with the flag still set and no hook behind it.
        ticking = false;
    }

    private boolean ticking;

    @Nullable
    private DiagnosticSet boundTo;

    // installExplorerCommands(window) used to live here and be called EVERY FRAME from tick(), behind a
    // commandsInstalled flag, for one reason: registration needed a window to reach a registry. Commands
    // are global and the explorer's resolve their workbench from the data context, so registration moved
    // to registerCommands (once per class), the tree binds its own bare keys in bindKeys (once per
    // instance), and the context menu is wired at construction. Nothing is left to do per frame.

    private boolean tick(float deltaSeconds) {
        if (document() == null) {
            ticking = false;
            return false;
        }
        focusActiveEditorAfterClose();
        // A few directories a frame, until the workspace is walked. Go to File searches what this has
        // reached, so warming it in the background is what makes the first Ctrl+P useful rather than
        // empty -- and it warms the tree's own listing cache, so there is no second index to keep in step.
        // NOT LATCHED. "Nothing to ask for right now" and "nothing left to walk" are the same answer from
        // outside, and a latch on that turned the crawl off the first time every known directory happened
        // to be in flight -- and left it off when a folder appeared later. The step is O(budget) against a
        // queue, so asking every frame costs nothing once the queue is empty.
        projectListing.indexStep(WorkspaceTreeSource.DEFAULT_INDEX_BUDGET);
        // WHAT THE INDEX MAY SEE, re-taken on this thread because the crawl above just grew the list the
        // analysis thread reads. Before announceProjectSourcesMoved, so a buffer that moved this frame is
        // announced this frame rather than next. @see #refreshProjectIndexInputs
        projectSources.refreshProjectIndexInputs();
        // A project file's text landed, so anything that resolved without it is stale. Drained here
        // because the read answers on the client's thread. @see #onProjectIndexFilled
        projectSources.announceProjectSourcesMoved();
        // THE PROJECT LISTING IS ASKED FOR PER FRAME, and the latch is the SOURCE's rather than a
        // panel's -- which is what let the explorer leave. It looks like a one-shot dressed as a loop and
        // is really a RETRY: a client's window id is not valid until its session has opened, and the
        // server discards a packet addressed to another window, so a call made too early is thrown away
        // with no error at all. Moving it off the frame needs a session-opened announcement, which is
        // W3b's territory rather than this one's.
        projectListing.loadProjects(() -> { }, () -> { });
        return true;
    }

    /**
     * Points the Problems panel at whatever editor is in front.
     *
     * <p>Was in {@link #tick}, deriving {@code activeEditor()} every frame and comparing it with
     * {@link #boundTo} to avoid rebuilding the table's rows sixty times a second. The comparison
     * <b>stays</b> — the announcement says the active <em>panel</em> moved, which is not the same as the
     * active <em>editor</em> moving: switching between two non-file panels changes the panel and leaves
     * this alone.</p>
     */
    /**
     * Points the Problems panel at whatever the active DOCUMENT has to report.
     *
     * <p>This asked {@code activeEditor()} — the active {@code TextEditor}. A shader graph has no text
     * editor, so the panel was empty by construction for the whole time a graph was in front, while its
     * compiler was producing attributed errors with nowhere to go. Same correction as
     * {@code DocumentEditor.activated}: the workbench knows which tab is in front and nothing else,
     * so what a document has to say is the document's to answer.</p>
     */
    /**
     * Every open document's problems, indexed by resource — this workspace's, and nobody else's.
     *
     * <p>An instance rather than a static for the reason on {@link Markers}: the index holds a listener on
     * every set in it, so a process-wide one can never let a document go.</p>
     */
    public final Markers markers = new Markers();

    /** @see #markers */
    public Markers markers() {
        return markers;
    }

    /**
     * ONE connection for the workbench, not one per document.
     *
     * <p>The tempting place to subscribe is beside the attach, inside the document factory — which would
     * add a listener per file opened, all of them doing the same whole-workspace sweep. The question
     * "which open documents are missing services" is about the workspace, so it is asked once.</p>
     *
     * <p>In a group so it is released with everything else this workbench holds: a static signal
     * outliving a disposed workbench is a leak that keeps a whole editor tree alive, and this one is on
     * {@code LanguageRegistry}, which lives for the process.</p>
     */
    private final ConnectionGroup capabilityWatch = new ConnectionGroup();

    private final ConnectionGroup markerWatch = new ConnectionGroup();
}
