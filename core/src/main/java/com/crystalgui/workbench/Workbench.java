package com.crystalgui.workbench;

import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.document.DocumentType;
import com.crystalgui.document.FileDocument;
import com.crystalgui.document.OpenDocuments;
import com.crystalgui.document.RecentFiles;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.ResourceContentProvider;
import com.crystalgui.fs.ResourceRegistry;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.FilePatternMap;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkingCopies;
import com.crystalgui.fs.WorkspaceFileService;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.cursor.IndentationProvider;
import com.crystalgui.text.fold.FoldingRangeProvider;
import com.crystalgui.text.syntax.DocComments;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.workbench.chrome.palette.CommandPalette;
import com.crystalgui.workbench.chrome.status.Breadcrumbs;
import com.crystalgui.workbench.chrome.palette.QuickPick;
import com.crystalgui.workbench.chrome.status.StatusBarView;
import com.crystalgui.workbench.decoration.FileDecoration;
import com.crystalgui.workbench.decoration.FileDecorationProvider;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.SymbolIcon;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.diff.ThreeWayMerge;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.InputDialog;
import com.crystalgui.workbench.chrome.notification.NotificationBalloons;
import com.crystalgui.workbench.chrome.notification.NotificationsView;
import com.crystalgui.workbench.chrome.problems.ProblemsPanel;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.workbench.diff.ConflictDialog;
import com.crystalgui.workbench.diff.MergeView;
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.DockWindow;
import com.crystalgui.workbench.dock.layout.DockBranch;
import com.crystalgui.workbench.dock.layout.DockNode;
import com.crystalgui.workbench.dock.layout.DockOrientation;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.DockGroup;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.workbench.dock.panel.DockInput;
import com.crystalgui.workbench.dock.panel.DockOpenOptions;
import com.crystalgui.workbench.dock.drag.DockPlacement;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.explorer.ExplorerCommands;
import com.crystalgui.workbench.explorer.ProjectFileTree;
import com.crystalgui.workbench.explorer.WorkspaceTreeSource;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionDropOverlay;
import com.crystalgui.workbench.region.RegionSide;
import com.crystalgui.workbench.dock.layout.DockPath;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import com.crystalgui.widget.texteditor.EditorCommands;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.workbench.decoration.DiagnosticDecorations;
import com.crystalgui.document.TextFileDocument;

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
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.Markers;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.workbench.chrome.menu.MainMenuCommands;
import com.crystalgui.ui.UiDataKeys;
import com.crystalgui.workbench.chrome.menu.MenuBarView;
import com.crystalgui.core.undo.UndoCommands;

/**
 * A project editor: a dock, a file tree, one editor per open file, and a Problems panel — the shell.
 *
 * <h3>It owns its panel registry and requires a workspace</h3>
 *
 * <p>Owning the registry is what lets it guarantee its own panel types exist; {@link #registerPanel} is
 * there for a host's extras, which is how a shader graph or a console gets a tab. Requiring a
 * {@link WorkspaceClient} is the sharper line: "open a file" is the verb this widget exists for, and a
 * project editor with no project is a different widget rather than a degraded one.</p>
 *
 * <h3>The dock is asked, not remembered</h3>
 *
 * <p>Which file {@link #saveActiveFile()} writes is derived from the dock's active tab, never from a field
 * updated on open. A remembered path saves the last file <em>opened</em>, which is the wrong one the moment
 * you switch tabs — and silently, because it reports success.</p>
 *
 * <h3>Editors are cached per path</h3>
 *
 * <p>{@code DockArea} asks the registry for a panel's content on every rebuild, so handing back a fresh
 * editor each time would discard unsaved edits on every split, drag or close.</p>
 */
public class Workbench extends UINode implements DataProvider {
    /** The shell. `ua/workbench.css` names the tag. */
    public static final Name NAME = Name.of("workbench");


    /** A document panel — one instance per file, distinguished by its {@code path} state. */
    public static final String FILE_TYPE = "file";

    /**
     * A tab showing a resource the workspace does not contain — a library class, a decompiled one.
     *
     * <h3>Its own type, and its own state key, on purpose</h3>
     *
     * <p>{@link #PATH_STATE} is <b>persisted into the dock layout and parsed back as a {@code CgPath}</b>
     * on restore. Putting a {@code library://…} string through it would ship a landmine that detonates
     * on the next session rather than on the click that created it — the delayed failure this codebase
     * has paid for more than once. So a viewer panel carries {@link #RESOURCE_STATE} instead, and the
     * whole {@code CgPath} pipeline (read, save, rename, decorate, recent files, close guard) never sees
     * it.</p>
     *
     * <p>A parallel lane rather than generalising that pipeline to {@code Resource}, because a viewer
     * document genuinely has none of the obligations the pipeline exists to meet: it cannot be saved,
     * renamed, created or decorated. Generalising is where this ends up when a second non-file document
     * kind turns up to justify it; until then it would be code written for nobody.</p>
     */
    public static final String VIEWER_TYPE = "viewer";

    /** Which resource a {@link #VIEWER_TYPE} panel shows, as {@code Resource#toString}. */
    public static final String RESOURCE_STATE = "resource";
    public static final String PROJECT_TYPE = "project";
    public static final String PROBLEMS_TYPE = "problems";
    /** The notification history — IntelliJ's own tool window, on the auxiliary rail beside the bell. */
    public static final String NOTIFICATIONS_TYPE = "notifications";

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
     * Marks an editor showing something the workspace does not contain. @see #VIEWER_TYPE
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

    private final WorkspaceClient<?> client;
    private final DockPanelRegistry<UINode> registry = new DockPanelRegistry<>();
    private final ProjectFileTree fileTree;
    private final ProblemsPanel problems = new ProblemsPanel();

    /** The notification history. @see #NOTIFICATIONS_TYPE */
    private final NotificationsView notificationsView = new NotificationsView();

    /** The transient half — balloons over the bottom-right corner. @see NotificationBalloons */
    private final NotificationBalloons balloons = new NotificationBalloons();
    private final DockArea dock;

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
     * Every open file, and what is known about each one.
     *
     * <p>This was four maps keyed by {@code CgPath} — the documents, their on-disk bytes, which had been
     * requested and which had refused to load — and every close and rename had to update all four. That is
     * one object pulled apart, and the drift it produces is the failure this codebase keeps paying for.</p>
     */
    private final OpenDocuments open = new OpenDocuments();

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
    private String openBufferText(CgPath path) {
        FileDocument document = open.get(path);
        if (document == null) return null;
        byte[] bytes = document.encode();
        return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * A background read landed, so anything that resolved without it is now out of date.
     *
     * <h3>A flag, because this is not the UI thread</h3>
     *
     * <p>It runs on whatever thread the workspace client answers on. Walking the open editors from here
     * would reach {@code setEnabled}-shaped state and end in {@code invalidateStyleMatch()}, adding to
     * {@code StyleEngine}'s dirty-match set while the frame is copying it — the {@code RunSessions} crash
     * exactly, which arrives as an {@code ArrayIndexOutOfBoundsException} out of {@code advanceFrame} with
     * nothing about this class in the trace.</p>
     *
     * <p>So: set, and let {@link #tick} drain it. Coalescing is free and wanted — a workspace crawl fills
     * many files in one frame and they all mean the same single "ask again".</p>
     */
    private void onProjectIndexFilled() {
        projectSourcesMoved = true;
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
    private volatile boolean projectSourcesMoved;

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
    private volatile List<CgPath> crawledFiles = List.of();

    /** Project id to declared source roots, as of the last frame. */
    private volatile Map<String, List<String>> projectRoots = Map.of();

    /** Open documents' text, as of the last frame — the tier that beats the file on disk. */
    private volatile Map<CgPath, String> bufferSnapshot = Map.of();

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
    private final java.util.Set<CgPath> dirtyBuffers = new HashSet<>();

    /** Which documents the snapshot covers, so opening or closing one is noticed. UI thread only. */
    private final java.util.Set<CgPath> snapshotOver = new java.util.HashSet<>();

    /** The tree source revision these snapshots were taken at. @see WorkspaceTreeSource#indexRevision() */
    private int lastIndexRevision = -1;

    /** Whether the workspace's own inputs moved on the PREVIOUS frame. @see #refreshProjectIndexInputs */
    private boolean workspaceMovedLastFrame;

    /**
     * Re-takes the three snapshots the index reads. UI thread, once a frame.
     *
     * <h3>Nothing here is re-derived unless something says it changed</h3>
     *
     * <p>This runs every frame, so every unguarded line in it is a per-frame cost. {@code knownFiles()}
     * builds a list of <b>every file in the workspace</b>, and the roots walk is over that same list — so
     * taking them unconditionally meant allocating and walking the whole workspace sixty times a second to
     * discover that nothing had happened. {@link WorkspaceTreeSource#indexRevision()} answers that in a
     * field read.</p>
     *
     * <p>Buffers are the same argument one level down: encoding an open document is not free, and
     * {@code refreshDirtyMarkers} doing it for every open file every frame is precisely what
     * {@link OpenDocuments#onDidChangeDirty} was added to stop.</p>
     */
    private void refreshProjectIndexInputs() {
        int revision = fileTree == null ? 0 : fileTree.source().indexRevision();
        boolean workspaceMoved = revision != lastIndexRevision;
        if (workspaceMoved) {
            lastIndexRevision = revision;
            List<CgPath> files = fileTree == null ? List.of() : fileTree.source().knownFiles();
            crawledFiles = files;

            Map<String, List<String>> roots = new HashMap<>();
            for (CgPath file : files) {
                if (file == null) continue;
                roots.computeIfAbsent(file.project(),
                        id -> fileTree == null ? com.crystalgui.fs.SourceRoots.CONVENTION
                                : fileTree.source().sourceRootsOf(id));
            }
            projectRoots = roots;
        }

        // A BIGGER WORKSPACE RESOLVES MORE NAMES, so a crawl that grew is a reason to ask again.
        //
        // This is the case that shipped broken and the hardest to see, because every part of it behaves
        // correctly. A file is opened immediately; the crawl is still walking; the package it imports has
        // not been reached yet, so the index truthfully reports that nothing is declared there and the
        // import is marked unresolvable. The crawl then finds it, the index becomes right, and NOTHING
        // re-runs the analysis that was wrong -- so the error stands for the life of the session while
        // every file opened afterwards resolves perfectly. It reads as a problem with the broken file
        // rather than as a race with a background walk.
        //
        // ONCE IT SETTLES, not on every change. The crawl lands listings continuously at startup, and a
        // debounced job whose trigger fires every frame is a job that never runs -- the first analysis
        // would be pushed back until the whole walk finished. Announcing on the frame AFTER the last
        // change fires at each point the crawl pauses, which is when its answer is worth re-asking.
        if (!workspaceMoved && workspaceMovedLastFrame) projectSourcesMoved = true;
        workspaceMovedLastFrame = workspaceMoved;

        refreshBufferSnapshot();
    }

    /**
     * Re-takes the open documents' text, when it has moved.
     *
     * <p>Its own method because it ends in a guard clause, and a guard clause halfway down a three-part
     * method is a trap for whatever gets added below it — the same reason a paint method may skip the
     * draw but never the method.</p>
     */
    private void refreshBufferSnapshot() {
        List<CgPath> open = openPaths();
        // THE SET, NOT THE COUNT. Two independent things move this snapshot -- a buffer's content, and
        // which documents are open -- and comparing counts gets the second wrong in BOTH directions. One
        // file closing while another opens leaves the count identical with every entry different, so the
        // snapshot silently keeps a closed document's text, which outranks the file on disk. And a
        // document whose text cannot be encoded never enters the snapshot at all, so the counts differ
        // for as long as it is open and every frame re-encodes every buffer -- the exact per-frame cost
        // this whole shape exists to avoid.
        if (dirtyBuffers.isEmpty() && snapshotOver.size() == open.size()
                && snapshotOver.containsAll(open)) return;

        // REBUILT WHOLE, so a closed document's text leaves with it. A stale entry here outranks the file
        // on disk, so a document that was closed and edited elsewhere would resolve to what it used to say.
        Map<CgPath, String> buffers = new HashMap<>();
        for (CgPath path : open) {
            // ALREADY HELD AND STILL TRUE. `encode()` serialises the whole document, so re-taking it for
            // a file nobody touched is the per-frame cost this shape exists to avoid, paid per keystroke
            // instead -- and paid for every open document rather than the one being typed into. The map
            // is still REBUILT rather than patched, so a closed document's text still leaves with it.
            if (!dirtyBuffers.contains(path)) {
                String held = bufferSnapshot.get(path);
                if (held != null) {
                    buffers.put(path, held);
                    continue;
                }
            }
            String text;
            try {
                text = openBufferText(path);
            } catch (RuntimeException unreadable) {
                // ONE DOCUMENT'S PROBLEM IS NOT THE FRAME'S. Encoding reaches into a live widget, and a
                // document that is closing has already disposed its tokenizer -- asking it for text throws
                // `IllegalStateException: Parser is closed`, which is the same fault
                // `reopeningAClosedFileShowsTheLiveEditor` was written for. Thrown from here it escapes
                // `tick()`, so every later line of the frame is skipped: the dock never attaches the panel
                // it was mid-way through opening, and a REOPENED FILE COMES UP BLANK. Nothing in that
                // symptom points at a cache of source text.
                //
                // Skipped rather than fatal because this cache is best-effort by construction -- `sourceOf`
                // answering null is a supported state that costs one re-analysis, and it is what a file
                // nobody has open already returns.
                continue;
            }
            if (text != null) buffers.put(path, text);
        }
        bufferSnapshot = buffers;
        snapshotOver.clear();
        snapshotOver.addAll(open);
        dirtyBuffers.clear();
        // The buffer tier moved, so anything that resolved against the old one is stale -- the same
        // announcement a landed read makes, for the same reason.
        projectSourcesMoved = true;
    }

    /**
     * Tells every open editor that the world outside its document moved.
     *
     * <p>Needed because {@code ProjectSources.sourceOf} answers null for a file nobody has open and
     * schedules a read — so the first analysis after opening a file that names a sibling resolves nothing.
     * Without this the error stands until the author types, which reads as the feature being flaky rather
     * than as one missing signal.</p>
     *
     * <p>Broadcast to every editor rather than to the ones that asked. A services object cannot say which
     * names it failed to resolve, and an analysis is debounced and keyed — so the cost of telling a
     * document that did not care is one coalesced job that finds nothing changed.</p>
     */
    private void announceProjectSourcesMoved() {
        if (!projectSourcesMoved) return;
        projectSourcesMoved = false;
        // AND THE TREE, whose rows resolve against the same thing an editor does. A `.java` row shows
        // what the file DECLARES, read through `ProjectSources` -- which answers null for a file nobody
        // has read yet and schedules the read. Without this the row keeps the file-type icon until
        // something else happens to rebind it, so a package's icons appear one at a time as you click
        // around, or never. @see ProjectFileTree#requestRefresh
        fileTree.requestRefresh();
        // AND EVERY TAB, for the same reason and one more: a tab's icon is pulled when the tab is BUILT
        // and never re-read, which was correct while it was a function of the file NAME. It is now a
        // function of what the file declares, and that answer arrives later than the tab does.
        syncTabDecorations();
        for (CgPath path : openPaths()) {
            TextEditor editor = editorFor(path);
            if (editor == null || editor.languageServices() == null) continue;
            editor.languageServices().environmentChanged();
        }
    }

    /** What the workspace declares. @see ProjectIndex */
    public com.crystalgui.text.lang.ProjectSources projectSources() {
        return projectIndex;
    }

    /** How to build a document for a given panel type. Keyed by type id, so a bound editor supplies its
     * own and the text editor is simply the one registered for {@link #FILE_TYPE}. */
    private final Map<String, Function<CgPath, FileDocument>> documentFactories = new HashMap<>();

    /**
     * Every file operation goes through this, never straight to the client.
     *
     * <p>It is what keeps {@link #editors} honest across a rename or a delete. Before it existed, nothing
     * updated that map when a path changed: the tab kept its old title, {@code Ctrl+S} wrote to the old
     * name, and opening the new name produced a second editor for the same file.</p>
     */
    private final WorkspaceFileService fileService;

    /** Marked internal exactly ONCE, while empty. {@code markAsInternal()} RECURSES, and stamping a
     * populated subtree makes {@code removeChild} silently refuse everything under it — which is how the
     * dock grew duplicate unclickable tabs and how the shader graph editor hung the window. */
    private final UINode content = new UINode();

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
    private final RecentFiles recentFiles = new RecentFiles();

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
    private final StatusBarView statusBar = new StatusBarView();

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
        if (current == null) {
            if (problemCountEntry != null) problemCountEntry.dispose();
            problemCountEntry = null;
            return;
        }
        // Idempotent, and here rather than in a field initialiser because the index is declared after the
        // document map -- a forward reference the compiler rejects outright.
        open.indexInto(markers);
        markerWatch.add(markers.onDidChange.connect(resource -> refreshProblemCount()));
        // ATTACHED WORKBENCHES ONLY, for the reason above it: this is a listener on a PROCESS-LIVED
        // static, so a workbench that subscribed from its constructor would stay reachable for ever and
        // keep an entire editor tree alive behind it.
        capabilityWatch.add(LanguageRegistry.onCapabilityChanged.connect(this::attachLateServices));
        // A LIBRARY TYPE'S KIND ARRIVING LATE is the same event as a project file's declaration arriving
        // late, so it sets the same flag and is coalesced with it. `symbolOf` is allowed to answer "not
        // yet" precisely so that working it out cannot land on a frame; this is the other half of that
        // bargain -- without it a decompiled tab keeps the generic glyph until something unrelated
        // rebuilds the strip. Same group, because this is a listener on a process-lived static too.
        capabilityWatch.add(ResourceRegistry.onSymbolResolved.connect(
                resource -> projectSourcesMoved = true));
        refreshProblemCount();
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
        // dead on arrival. This method runs from UINode's INSTANCE INITIALISER -- before the Workbench
        // constructor body -- so `projectIndex` was still null, and `contribute` opens with
        // `if (provider == null) return`. Nothing threw, nothing logged, and every cross-file reference in
        // the workspace reported "cannot be resolved to a type" with the registry, the name environment
        // and the project tier all correct and all covered by passing tests. Registration is per INSTANCE
        // and belongs in the constructor; this hook is per CLASS and may only name statics, which is
        // exactly what its own javadoc says. @see UINode#registerCommands
        // Undo comes with a workbench because the file tree IS the workspace's UndoScope -- deleting a
        // file is undoable and reaches the workspace stack. Same ids the editor and the graph use, so
        // there is one Undo in the palette rather than one per widget.
        UndoCommands.register();
        registerToolWindowCommands(registry);
    }

    /**
     * Revealing a tool window, as a named command.
     *
     * <p>Here rather than nowhere because a status bar entry names a <b>command id</b>, not a callback —
     * which is what keeps a clickable readout reachable from the palette and a keymap as well as from the
     * bar. VS Code's error counter opens its Problems panel exactly this way; ours had the mechanism and
     * nothing to point it at.</p>
     *
     * <p>Global, taking the workbench from the data context, for the reason {@link #WORKBENCH} exists: a
     * captured workbench makes a second window's command toggle a panel in the first.</p>
     */
    private static void registerToolWindowCommands(CommandRegistry registry) {
        // REGISTERED ON THE REGISTRY WE WERE HANDED, never through a nested contribute(Workbench.class).
        // UINode already calls contribute(getClass(), this::registerCommands) to get here, so
        // Workbench.class is ALREADY in the contributor set by the time this runs -- a second contribute
        // under the same key adds nothing and returns, silently, and the commands were never registered
        // at all. The status entries drew a pointer cursor and did nothing, which is precisely the failure
        // CommandRegistry.resetForTesting warns about: "a missing command only shows up as a key that
        // does nothing".
        registry.register(Command.of(SHOW_PROBLEMS, "Show Problems")
                .runWithData(data -> {
                    Workbench workbench = data.get(WORKBENCH);
                    if (workbench != null) workbench.revealPanel(PROBLEMS_TYPE);
                })
                .enabledWhereData(data -> data.get(WORKBENCH) != null));
        registry.register(Command.of(SHOW_NOTIFICATIONS, "Show Notifications")
                .runWithData(data -> {
                    Workbench workbench = data.get(WORKBENCH);
                    if (workbench != null) workbench.revealPanel(NOTIFICATIONS_TYPE);
                })
                .enabledWhereData(data -> data.get(WORKBENCH) != null));
    }

    /** Brings a tool window to the front, creating it if it is not open. */
    public boolean revealPanel(String typeId) {
        return toolWindowManager != null && toolWindowManager.showPanel(typeId);
    }

    /** Reveals the Problems panel. What a failing status readout points at. */
    public static final String SHOW_PROBLEMS = "workbench.showProblems";

    /** Reveals the Notifications panel. */
    public static final String SHOW_NOTIFICATIONS = "workbench.showNotifications";

    public Workbench(WorkspaceClient<?> client) {
        super(NAME);
        if (client == null) throw new IllegalArgumentException("A Workbench needs a workspace client");
        this.client = client;
        // AFTER `client`, and it has to be: a field initialiser capturing a constructor-assigned final is
        // a definite-assignment error, not a nullable read.
        // SNAPSHOTS, not live views. Everything below is read from the ANALYSIS thread, inside a compile,
        // while the UI thread is mutating the tree's listing maps and the open-document map. @see #refreshProjectIndexInputs
        this.projectIndex = new ProjectIndex(
                () -> crawledFiles,
                id -> projectRoots.getOrDefault(id, com.crystalgui.fs.SourceRoots.CONVENTION),
                // A LAMBDA, NEVER `bufferSnapshot::get`. A bound method reference captures the object the
                // field points at WHEN THE REFERENCE IS MADE -- here the empty map, in this constructor --
                // so every snapshot taken afterwards is invisible to it. It compiles, it reads as an
                // accessor, and the buffer tier silently never answers: an open document's unsaved text
                // loses to whatever is on disk, with nothing to see. The two lines above are lambdas for
                // exactly this reason.
                path -> bufferSnapshot.get(path),
                (path, onText) -> client.read(path,
                        read -> onText.accept(new String(read.content(),
                                java.nio.charset.StandardCharsets.UTF_8)),
                        error -> onText.accept(null)),
                this::onProjectIndexFilled);
        // REGISTERED HERE, where the field exists. Contributed rather than set, because two workbenches in
        // one process are two projects rather than a fight over one slot -- which is also why this cannot
        // live in registerCommands, which runs once per CLASS: the second workbench would never register
        // its own index and the first would answer from a file tree nobody is looking at.
        com.crystalgui.text.lang.ProjectSourcesRegistry.contribute(projectIndex);
        this.fileService = new WorkspaceFileService(client, new Copies());
        this.fileTree = new ProjectFileTree(client);
        // At construction, not on the first frame with a window: the registry is global, so there is
        // nothing left to wait for.
        this.fileTree.setContextMenu(
                CommandRegistry.global(), ExplorerCommands::menu);
        // The explorer IS the workspace's undo scope. UndoScope.nearest walks outward from focus, so
        // Ctrl+Z in the tree reaches file operations and Ctrl+Z in an editor still reaches its own text.
        this.fileTree.setUndoStack(fileService.undoStack());
        // PROBLEMS AS A DECORATION. Everything for this already existed -- the weights, the
        // `.decoration-error` classes, the tree's own resolve-and-apply -- and nothing read Markers.
        //
        // Through `pendingRefresh` rather than a direct refresh, for the reason FileDecorations records:
        // a provider can fire from inside a click handler on a row, and a widget must never rebuild the
        // elements it is being clicked on.
        this.fileTree.getDecorations().addProvider(new DiagnosticDecorations(markers));
        // ONE SIGNAL, BOTH SURFACES. The tree redraws from the decorations' own announcement; the tabs
        // have to be told, because a tab is not a decoration consumer -- it pulls a class when it is
        // built and has no reason to look again on its own.
        markers.onDidChange.connect(resource -> {
            fileTree.getDecorations().invalidate();
            syncTabDecorations();
        });
        fileTree.onFileChosen.connect(this::openFile);
        fileTree.onFilesDropped.connect(this::dropFiles);
        // RENDERED FROM THE RESULT, never from the call site. One update path serves this client's own
        // operations and another client's alike -- see Q11 in the chrome plan, and WorkspaceFileService's
        // note on why two paths into one model always end up disagreeing.
        fileService.onDidRun.connect(this::refreshAfter);
        // ANOTHER CLIENT'S CHANGES, through the same path as our own -- which is the whole reason the
        // explorer renders from events rather than from its own call sites (Q11). The server pushes
        // fs.changed for anything watched; a create or delete elsewhere shows up here without the tree
        // knowing who did it.
        client.onFileChanged(change -> {
            fileTree.source().invalidate(change.path().parent());
            fileTree.treeView().refresh();
            applyExternalChange(change);
        });
        fileTree.getDecorations().addProvider(externalChanges);

        // A RECONNECT INVALIDATES EVERYTHING AT ONCE, and for a different reason than a change does --
        // CrystalOS W11. The client survives a disconnect and rejoin so that a window retained across one
        // comes back working, but every listing it fetched describes a server it is no longer attached
        // to, and no fs.changed can arrive to say so: nothing was watching, because there was nothing to
        // watch with. Wired here beside the notification above so there is one place the tree learns that
        // the far side has moved under it.
        client.onRebound(fileTree::markListingsStale);

        // How a tab presents itself. Both are PULLED by the strip when it builds a tab rather than pushed
        // in afterwards, which is what makes a rebuilt strip correct on the frame it is rebuilt -- a dock
        // rearrangement recreates every tab element, and anything pushed would have to be pushed again by
        // someone who noticed.
        registry.setTitleProvider(this::tabTitleFor);
        registry.setWindowTitleProvider(this::windowTitleFor);
        registry.setIconProvider(Workbench::tabIconFor);
        registry.setIconElementProvider(Workbench::viewerIconElement);
        registry.setTooltipProvider(Workbench::tabTooltipFor);
        registry.setIconTooltipProvider(Workbench::tabIconTooltipFor);
        registry.setDecorationProvider(this::tabDecorationFor);

        // Anchors match where defaultLayout() puts them, so closing a panel and reopening it from the
        // activity bar lands it back where it was rather than somewhere merely legal.
        registry.register(DockPanelDescriptor.singleton(PROJECT_TYPE, "Project")
                .icon("crystalgui:folder").anchor(DockDropZone.SPLIT_LEFT), ref -> fileTree);
        registry.register(DockPanelDescriptor.singleton(PROBLEMS_TYPE, "Problems")
                .icon("crystalgui:toolwindows/problems").anchor(DockDropZone.SPLIT_DOWN), ref -> problems);
        // THE AUXILIARY RAIL, which is where IntelliJ keeps it and is not an arbitrary choice: the
        // notification history is something you consult, not something you work in, so it belongs on the
        // side that holds the things you glance at rather than beside the project tree.
        registry.register(DockPanelDescriptor.singleton(NOTIFICATIONS_TYPE, "Notifications")
                .icon("crystalgui:toolwindows/notifications").region(DockRegion.AUXILIARY).side(RegionSide.PRIMARY),
                ref -> notificationsView);
        // NOT `registerDocumentType`, which is CgPath-keyed from its first line. @see #VIEWER_TYPE
        registry.register(DockPanelDescriptor.document(VIEWER_TYPE, "Viewer"), ref -> {
            return viewerFor(Resource.parse(ref.state(RESOURCE_STATE, "")));
        });

        registerDocumentType(FILE_TYPE, "File", path -> {
            TextEditor created = new TextEditor("");
            created.addClass(FILE_EDITOR_CLASS);
            LanguageRegistry.Entry entry = LanguageRegistry.forFileName(path.name());
            created.setLanguage(entry.language());
            // A FRESH tokenizer per document -- the interface exists for implementations holding a parse
            // tree per file, and sharing one would cross-contaminate them.
            // AND ITS DOC COMMENTS READ. A grammar reports `/** ... */` as ONE comment token, because to
            // a parser that is what it is -- the tags and the HTML inside are a convention rather than
            // syntax. `DocComments` is the lexing pass that reads them, composed here rather than inside
            // `newTokenizer` so the registry keeps answering with what was registered.
            SyntaxTokenizer tokenizer = DocComments.refining(entry.newTokenizer());
            created.setTokenizer(tokenizer);
            // AND IF IT CAN FOLD, IT FOLDS. A tokenizer holding a parse tree already knows where a block
            // begins and ends, which is strictly better than guessing from indentation -- and asking it
            // costs no second parse, which a separate provider would. The indentation provider stays the
            // default and answers for every language with no grammar behind it, which is most of them.
            if (tokenizer instanceof FoldingRangeProvider) {
                created.setFoldingProvider((FoldingRangeProvider) tokenizer);
            }
            // AND IF IT CAN SAY HOW DEEP A LINE IS, Enter asks it rather than reading the last character
            // of the line -- which is right for a brace language and silently wrong for a `case` arm, a
            // wrapped expression, or a nested CSS rule. Same seam, same fallback: a language with no
            // indent query keeps the rule it had.
            if (tokenizer instanceof IndentationProvider) {
                created.setIndentationProvider((IndentationProvider) tokenizer);
            }
            // Fresh services per document too, and for the same reason one level up: they hold a compile
            // result about THIS text. Null unless a language module registered an engine, which is the
            // whole feature flag -- see LanguageServices. Released by TextFileDocument.dispose().
            Resource resource = Resource.of(path);
            created.setLanguageServices(entry.newServices(created.buffer(), resource));
            // A CROSS-FILE jump, which the editor announces rather than performs -- see the signal's own
            // note. Same-file jumps never arrive here because the editor already made them; this hears
            // only what genuinely needs the workspace, and routes it through the primitive the Problems
            // panel uses so the two cannot disagree about focus or framing.
            routeDefinitionsOf(created);
            // No command installation here: TextEditor registers its own and binds its own chords, so a
            // document created before this workbench is attached is no longer a special case.
            // Here rather than only from WorkbenchSettings.apply: a document opened after the settings
            // were installed would otherwise get the widget's own defaults, so folding and tab size would
            // apply to the files that happened to be open when a preference was last changed and to no
            // others -- which reads as the setting working intermittently.
            WorkbenchSettings.applyTo(this, created);
            return new TextFileDocument(created, resource);
        });

        dock = new DockArea(registry, defaultLayout());

        // ASKED BEFORE ANYTHING IS DISCARDED. Ctrl+W on an edited file used to throw the work away with no
        // warning at all -- the tab marker said it was modified and nothing acted on that.
        dock.setCloseGuard(this::confirmClose);
        // Two of this widget's per-frame polls, replaced by the announcement they were both watching for.
        // Not registered on a Disposable: the signal belongs to the dock, this workbench owns the dock, so
        // the subscription cannot outlive either -- an ownership registration here would be ceremony.
        dock.onDidChangeActivePanel.connect(panel -> {
            // THE MOMENT THE REBUILD HAS HAPPENED, which is what a close was waiting for. The frame
            // countdown below is a backstop for the case this signal never comes -- closing a tab that
            // was not the active one leaves the active panel where it was and announces nothing.
            focusActiveEditorAfterClose();
            revealActiveFile();
            rebindProblems();
            bindStatusToActiveTab();
        });
        // The rails' :checked state follows the dock's structure and nothing else, so they can subscribe
        // now. Their BUTTONS wait for a window -- see onWindowChanged.
        for (StripeView stripe : stripes()) stripe.listenToLayout(dock);
        // A CLOSED TAB RELEASES ITS DOCUMENT. Until the dock could announce a close, nothing did: the
        // document stayed open, its editor stayed reachable and anything it owned -- a preview pool, a
        // renderer -- lived until the process did. Disposer could not help, because the thing that knew
        // the tab was gone had no way to say so.
        dock.onDidClosePanel.connect(this::releaseClosedPanel);
        // AND THE EDITOR THAT TOOK OVER GETS THE FOCUS THE CLOSED ONE HAD. Spent a frame later -- see
        // focusActiveEditorPending.
        dock.onDidClosePanel.connect(panel -> focusActiveEditorPending = FOCUS_AFTER_CLOSE_FRAMES);
        // Tab dirty markers. Was a per-frame refreshDirtyMarkers(), which meant encoding every open
        // document -- a whole shader graph serialised sixty times a second -- to notice a marker that
        // moves when somebody types. The equality guard SURVIVES the move: the announcement means
        // "content changed", which is not the same as "dirtiness flipped", and only the encode can tell
        // the difference. It just runs once per edit now instead of once per frame.
        open.onDidChangeDirty.connect(path -> {
            refreshDirtyMarkers();
            // AND THE INDEX'S VIEW OF IT. This signal means "content moved", which is exactly when a
            // snapshot of that content stops being true. Marked rather than re-encoded, so a burst of
            // keystrokes costs one encode on the next frame instead of one each.
            dirtyBuffers.add(path);
        });
        // EVERY FILE FAILURE IS REPORTED FROM ONE PLACE, and this is the whole of the change that made it
        // so. WorkspaceFileService already announced each one through onDidFail, carrying the operation --
        // and nothing listened, so all eleven call sites wrote their own `failure -> Notifications.show(...)`
        // instead. That copy is where "created X" and "moved X" ended up on the ERROR channel: the lambda
        // was pasted from the failure branch into the success one with a word changed.
        //
        // Wired here because this is where the workbench's parts are introduced to each other, and because
        // WorkspaceFileService must not reach for Notifications itself: it already has an announcement
        // channel, and a service with two would leave a listener unable to tell which was authoritative.
        files().onDidFail.connect((operation, failure) -> Notifications.show(
                Notification.error(failedVerb(operation)).withDetail(failureDetail(operation, failure))));

        // THE BELL'S BADGE. Routed through the container registry rather than reaching for a rail button,
        // because a badge is a fact about a CONTAINER and both rails already listen for it -- so a tool
        // window dragged from one stripe to the other keeps its count with no further wiring.
        //
        // Written whether or not the panel has ever been opened: the count is what tells you to open it.
        // A DOT, NOT A COUNT. IntelliJ marks the bell and does not say how many, which is the right call:
        // the number is not actionable -- you open the panel either way -- and a two-digit count over a
        // 20px rail icon is unreadable. The exact figure is a scroll away in the history.
        Notifications.onDidChangeUnread.connect(count -> toolWindowManager.viewContainers().setBadge(
                NOTIFICATIONS_TYPE, count == null || count <= 0 ? null : ViewContainerRegistry.DOT));

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
        // THE DEFAULT ARRANGEMENT, which used to be three leaves in defaultLayout(). Stated as "show these
        // two" rather than as a tree, which is the whole difference: a region cannot be collapsed away, so
        // this says what is on screen rather than where in a structure it sits.
        toolWindowManager.showPanel(PROJECT_TYPE);
        toolWindowManager.showPanel(PROBLEMS_TYPE);

        // BOTH HANDLERS ARE INLINE, and deliberately not folded into one openAndReveal(CgPath, TextPoint).
        //
        // That helper reads as the obvious de-duplication and gives this class a navigation API in terms
        // of a text POSITION -- which is knowledge a workbench has no business holding. It arranges panels
        // and owns documents; where a caret goes inside one is the editor's affair, and a method here
        // taking a TextPoint invites every future caller to route text navigation through the shell.
        //
        // What the two handlers actually share is `openFile(path, continuation)`, which is already the
        // primitive and is already stated once. The four lines they each spell out are the CALLER's
        // business -- which editor, what to do with it -- and spelling them out is what keeps the coupling
        // pointing the right way.
        problems.onProblemChosen.connect(node -> {
            if (node.diagnostic() == null || node.resource() == null || !node.resource().isProject()) return;
            TextPoint at = node.diagnostic().start();
            // AS THE CONTINUATION OF THE OPEN, not as the statement after it. openFile is asynchronous for
            // a file that is not already on screen -- it returns before client.read has come back -- so
            // positioning on the next line acted on the editor from BEFORE the click. That is correct for
            // a problem in the file you are already looking at and wrong for every other, which is why it
            // read as intermittent rather than as broken.
            openFile(node.resource().asPath(), () -> {
                TextEditor editor = activeEditor();
                if (editor == null) return;
                editor.revealAt(at);
                UIDocument window = document();
                if (window != null) window.focus().requestFocus(editor);
            });
        });

        // SHOW QUICK-FIXES IS NAVIGATE PLUS ONE STEP, and it is spelled out here for the same reason the
        // handler above is: which editor and what to do with it is the caller's business. The panel has
        // no editor and must not reach for one -- it asks, and this answers.
        //
        // The list is opened INSIDE the continuation, after the caret has been placed: the actions are
        // resolved from an offset, so asking before the file is open and positioned would ask about
        // wherever the previous editor's caret happened to be.
        problems.onQuickFixesRequested.connect(node -> {
            if (node.diagnostic() == null || node.resource() == null || !node.resource().isProject()) return;
            TextPoint at = node.diagnostic().start();
            openFile(node.resource().asPath(), () -> {
                TextEditor editor = activeEditor();
                if (editor == null) return;
                editor.revealAt(at);
                UIDocument window = document();
                if (window != null) window.focus().requestFocus(editor);
                editor.showCodeActionsAt(editor.getCaret());
            });
        });
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

    public StatusBarView statusBar() {
        return statusBar;
    }

    public DockArea dock() {
        return dock;
    }

    public DockPanelRegistry<UINode> panels() {
        return registry;
    }

    public ProjectFileTree fileTree() {
        return fileTree;
    }

    public ProblemsPanel problems() {
        return problems;
    }

    /** Adds a host's own panel type — a shader graph, a console, an inspector. */
    public Workbench registerPanel(DockPanelDescriptor descriptor,
                                   Function<DockPanelRef, UINode> factory) {
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
    public ToolWindowManager toolWindowManager() {
        return toolWindowManager;
    }


    /**
     * Opens {@code input} <b>where</b> {@code placement} says and <b>how</b> {@code options} say.
     *
     * <h3>What this replaced</h3>
     *
     * <p>Three overloads — {@code openPanel(ref)}, {@code openPanelWith(sibling, ref)} and
     * {@code openPanelBeside(ref, zone, share)} — which read as three operations and were really one
     * operation with two independent variables. Their genuine differences were buried in their bodies:
     * one activated what it opened, one deliberately restored the previous selection, one set a size
     * share. A caller wanting "beside, without stealing focus" had no overload and no way to ask.</p>
     *
     * <p>That is VS Code's {@code openEditor(input, options, group)}, and the reason it has that shape.</p>
     *
     * @return the leaf it landed in, so a caller can act on it without searching for it again
     */
    public DockLeaf open(DockInput input, DockPlacement placement, DockOpenOptions options) {
        // THE DOCK THE USER IS WORKING IN, which is not always this workbench's own -- see activeDock().
        // Shadowed deliberately: every line below means "the dock this open is going into", and one
        // resolution at the top is what stops half a method reading the field and half the answer.
        DockArea dock = activeDock();
        DockPanelRef ref = input.ref();

        // ALREADY OPEN wins over placement, always. Re-opening a file that is on screen means "show me
        // that one", never "make a second copy of it somewhere else" -- and a placement that ignored this
        // would silently duplicate a document, which is the one outcome no caller wants.
        DockLeaf existing = dock.layout().leafContaining(ref);
        if (existing != null) {
            existing.activate(ref);
            dock.syncGroups();
            if (options.activates()) dock.setActiveGroup(dock.groupFor(existing));
            return existing;
        }

        DockLeaf target = DockPlacement.resolve(placement, dock);
        boolean splitting = placement instanceof DockPlacement.Side;
        if (target == null) target = centralLeaf(dock);

        if (!splitting) {
            // The selection is captured and PUT BACK when the caller asked not to activate. DockLeaf.add
            // activates what it inserts, which is right for a file and wrong for a companion panel.
            DockPanelRef wasActive = target.activePanel();
            long timed = FrameProfile.begin();
            target.add(ref);
            FrameProfile.step(timed, "dock.leaf.add");
            if (!options.activates() && wasActive != null) target.activate(wasActive);
            timed = FrameProfile.begin();
            dock.syncGroups();
            FrameProfile.step(timed, "dock.syncGroups");
            if (options.activates()) {
                timed = FrameProfile.begin();
                dock.setActiveGroup(dock.groupFor(target));
                FrameProfile.step(timed, "dock.setActiveGroup");
            }
            return target;
        }

        DockDropZone zone = ((DockPlacement.Side) placement).zone();
        float whole = target.size();
        DockLeaf placed = dock.layout().drop(target, zone, new DockLeaf(ref));
        if (options.hasShare()) {
            // Ratios within a branch are all that matter, so this is correct whether drop inserted a
            // sibling (the two weights still sum to the target's old share, leaving every other child
            // untouched) or wrapped the target in a new branch (where the pair are its only children).
            target.size(whole * (1f - options.share()));
            placed.size(whole * options.share());
        }
        // requestRebuild, not syncGroups: the TREE changed, not just a selection.
        //
        // The new pane is deliberately NOT made active even when asked. It has no group yet -- the
        // rebuild is deferred to the next frame -- so asking for one now yields null, and setting THAT
        // sends rebuild() down its "nothing is active" path, which picks leaves.get(0): the file tree.
        dock.requestRebuild();
        return placed;
    }

    /** Opens into the central work area and brings it forward — what opening a file means. */
    public DockLeaf open(DockInput input) {
        return open(input, DockPlacement.central(), DockOpenOptions.ACTIVATE);
    }

    // ── Files ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Which editor opens which file — pattern to dock panel type.
     *
     * <p>Per workbench rather than static, and that is not caution: a panel type id only means anything
     * against the {@code DockPanelDescriptor} registry that defined it, and that registry belongs to this
     * workbench. A global map would let one window bind a type the other cannot build.</p>
     */
    private final FilePatternMap<String> editorBindings = new FilePatternMap<>();

    /**
     * Opens files matching these extensions with the panel type {@code typeId} instead of the text editor.
     *
     * <p>The host registers the panel itself with {@link #registerPanel} and then says which files it is
     * for. The two are separate calls because they are separate facts — a panel type can exist without
     * claiming any file (the graph, the Problems list), and a binding is meaningless without a panel to
     * build.</p>
     *
     * <p>A bound panel is handed the same {@code PATH_STATE} and title as a text editor would be, so its
     * factory reads the path exactly the same way and nothing else in the dock needs to know a binding
     * happened.</p>
     */
    public Workbench bindEditorExtensions(String typeId, String... extensions) {
        editorBindings.putExtensions(typeId, extensions);
        return this;
    }

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
     * The panel reference identifying one open file — {@code path} is what makes two of them distinct.
     *
     * <p><b>The type comes from the binding, not from a constant.</b> This returned {@link #FILE_TYPE}
     * unconditionally, which is why every file opened in a text editor however little sense that made: a
     * PNG arrived as mojibake and a {@code .shadergraph} as JSON. Resolution is
     * {@link FilePatternMap}'s — exact name, then extension, then glob — and the text editor is the
     * fallback rather than the rule.</p>
     *
     * <p>An instance method now, because bindings belong to a workbench. It is also the identity used to
     * <em>find</em> an open tab again, for closing and for renaming, so it must be a pure function of the
     * path and the bindings — which it is, since bindings are registered at startup. A rename that changes
     * the extension therefore legitimately produces a different ref, and the rename path already replaces
     * one ref with the other: renaming {@code a.txt} to {@code a.png} swaps the editor with it, which is
     * the correct answer rather than an accident.</p>
     */
    public DockPanelRef refFor(CgPath path) {
        String bound = editorBindings.get(path.name());
        return new DockPanelRef(bound == null ? FILE_TYPE : bound)
                .withState(PATH_STATE, path.toString())
                .withState(DockPanelRef.TITLE, path.name());
    }

    /**
     * Opens a file in its own tab, or focuses the tab it is already in.
     *
     * <p><b>Reads first, adds the tab second.</b> A tab created before the content arrives stays empty when
     * the read fails, and the failure has nowhere to go but a status line nobody was watching — leaving a
     * blank editor with no explanation.</p>
     */
    public void openFile(CgPath path) {
        openFile(path, null);
    }

    /**
     * Opens a file and runs {@code onOpened} <b>once the document actually exists</b>.
     *
     * <p><b>The callback is the whole point, because this method has two paths and only one of them is
     * synchronous.</b> A file already on screen is activated and returns immediately; a file that is not
     * open goes through {@code client.read}, which is a round trip, and returns long before anything has
     * been adopted. Every caller that wanted to do something <em>to</em> the file it just opened wrote
     * the second statement as though the first had finished:</p>
     * 
     * @param onOpened run after the document is present and its tab is active, on both paths; never run
     *                 if the read fails, since there is nothing to act on
     */
    public void openFile(CgPath path, @Nullable Runnable onOpened) {
        // BEFORE the already-open early return below, so re-activating a tab still promotes the file.
        // "Recent" means recently used, not recently created -- and the branch that returns early is the
        // common one once a session has been running for a while.
        recentFiles.record(path);
        DockPanelRef ref = refFor(path);
        for (DockLeaf leaf : dock.layout().leaves()) {
            if (leaf.indexOf(ref) < 0) continue;
            leaf.activate(ref);
            // syncGroups, not requestRebuild: only the selection changed, and this usually runs inside the
            // click that asked for it -- a widget must never rebuild the elements it is being clicked on.
            dock.syncGroups();
            dock.setActiveGroup(dock.groupFor(leaf));
            if (onOpened != null) onOpened.run();
            return;
        }
        client.read(path, read -> {
            adoptInto(path, read.content());
            open.requestRead(path);
            open(DockInput.of(ref));
            // AFTER open(), not before: the tab has to be the active one for activeEditor() to answer
            // with the document this callback is about.
            if (onOpened != null) onOpened.run();
        }, failure -> Notifications.show(openFailed(path, failure)
                // AN ACTION, because a read failure is the case actions exist for: it is usually transient
                // (a server round trip), the recovery is exactly what was just attempted, and without one
                // the message names a problem and leaves the user to find the verb again.
                .withAction("Retry", () -> openFile(path))));
    }

    // ── The viewer lane ────────────────────────────────────────────────────

    /**
     * Every viewer on screen, by the resource it shows.
     *
     * <p>Keyed by the resource's TEXT rather than by the record, so a restored panel and a fresh open of
     * the same class find each other: {@code Resource} is rebuilt from the ref's state on restore, and
     * two equal-valued instances have to name one editor or a split would show two of them.</p>
     */
    private final Map<String, TextEditor> viewers = new HashMap<>();

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

    /** Which viewers have their text — what tells "still reading" from "read and empty" apart. */
    private final Set<String> viewersLoaded = new HashSet<>();

    /** What is waiting on a viewer's first read. @see #whenViewerLoaded */
    private final Map<String, List<Runnable>> viewerPending = new HashMap<>();

    /**
     * Sends this editor's cross-document jumps somewhere — a workspace file, or a viewer.
     *
     * <p><b>Every editor the workbench builds needs this, and one of them did not have it.</b> A viewer
     * was created without it, so Ctrl+B <em>inside</em> a library class emitted into a signal nobody was
     * listening to — and so did the documentation popup's Jump to Source, which is the same call one
     * layer up. Both looked like resolution failing, while the hover in the very same file was drawing
     * the symbol's full documentation: the engine had the answer throughout and nothing was carrying
     * it.</p>
     *
     * <p>Written once and called from both, rather than copied into the viewer, because the two are
     * expected to stay identical: jumping out of a library class into another library class is the same
     * gesture as jumping out of your own file, and a reader drilling through the JDK is doing it
     * repeatedly. Two copies would be two places for the routing rules to drift.</p>
     */
    private void routeDefinitionsOf(TextEditor editor) {
        editor.onDefinitionChosen.connect(site -> {
            if (site.resource() == null) return;
            // A RESOURCE THE WORKSPACE DOES NOT HOLD goes to a viewer. This used to return here, so
            // Ctrl+B into anything on the classpath did nothing -- and it read as the engine having
            // no answer, when the engine had simply never been asked for one.
            if (!site.resource().isProject()) {
                openResourceAt(site.resource(), site.start(), site.member());
                return;
            }
            openFileAt(site.resource().asPath(), site.start());
        });
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

    /** The panel ref for a resource — a pure function of it, as {@link #refFor} is of a path. */
    public DockPanelRef refForResource(Resource resource) {
        String text = resource.toString();
        return new DockPanelRef(VIEWER_TYPE)
                .withState(RESOURCE_STATE, text)
                .withState(DockPanelRef.TITLE, viewerTitleOf(resource));
    }

    /**
     * What a viewer tab is called — the simple name, which is what a tab strip has room for.
     *
     * <p>{@code library://java.util.ArrayList} becomes {@code ArrayList}. The package is what the
     * breadcrumb and the tooltip are for; a tab that reads {@code library://java.util.ArrayList} pushes
     * every other tab off the strip to say what one word already says.</p>
     */
    private static String viewerTitleOf(Resource resource) {
        String path = resource.path();
        int dot = path.lastIndexOf('.');
        return dot < 0 || dot == path.length() - 1 ? path : path.substring(dot + 1);
    }

    /**
     * Opens {@code resource} in a read-only tab, or focuses the tab it is already in.
     *
     * <p>The same two paths {@link #openFile} has and the same reason for the callback: an already-open
     * viewer activates and returns, a new one waits on a provider that may be reading an archive or
     * running a decompiler. A caller that wants to reveal a position has to be told when there is
     * something to reveal it in.</p>
     *
     * <p><b>Nothing happens when no provider claims the scheme</b>, which is the ordinary state of a
     * deployment that ships no engine: the answer to "go to declaration" is then the same as it was
     * before any of this existed. Silence rather than an error, for the reason the three-tier absence
     * rule gives everywhere else.</p>
     */
    public void openResource(Resource resource, @Nullable Runnable onOpened) {
        if (resource == null || ResourceRegistry.providerFor(resource) == null) return;
        long profiled = FrameProfile.enter("Workbench.openResource " + resource);
        try {
            openResourceInternal(resource, onOpened);
        } finally {
            FrameProfile.leave(profiled, "Workbench.openResource");
        }
    }

    private void openResourceInternal(Resource resource, @Nullable Runnable onOpened) {
        long timed = FrameProfile.begin();
        DockPanelRef ref = refForResource(resource);
        FrameProfile.step(timed, "refForResource (displayName + tab title)");
        // CREATED BEFORE THE TAB, which also starts its read. `openFile` reads before it adds a tab so a
        // failed read leaves no empty editor behind; here the editor is the thing the dock builds panels
        // FROM — on a split, a drag, and a layout restore — so it has to exist independently of any one
        // open, and the ordering that matters instead is that nothing reveals a position until there is
        // text to reveal it in.
        timed = FrameProfile.begin();
        viewerFor(resource);
        FrameProfile.step(timed, "viewerFor (build editor + submit read)");
        for (DockLeaf leaf : dock.layout().leaves()) {
            if (leaf.indexOf(ref) < 0) continue;
            FrameProfile.note("already open -- activating existing tab");
            leaf.activate(ref);
            timed = FrameProfile.begin();
            dock.syncGroups();
            FrameProfile.step(timed, "dock.syncGroups");
            dock.setActiveGroup(dock.groupFor(leaf));
            whenViewerLoaded(resource, onOpened);
            return;
        }
        timed = FrameProfile.begin();
        open(DockInput.of(ref));
        FrameProfile.step(timed, "dock.open (new tab)");
        whenViewerLoaded(resource, onOpened);
    }

    /**
     * Opens a non-workspace resource and puts the caret at {@code at}, focusing it.
     *
     * <h3>One definition, two callers, and a third coming</h3>
     *
     * <p>Ctrl+B into a library class and Go to Class are the same act with different ways of naming the
     * target — one has a {@code DeclarationSite}, the other has a name and a line somebody typed. The
     * routing between them was written inline for the first caller; extracting it when the second arrived
     * is the rule {@code routeDefinitionsOf} already states about its own two halves ("two copies would be
     * two places for the routing rules to drift").</p>
     *
     * <p><b>The viewer's own editor, never {@code activeEditor()}.</b> That resolves through
     * {@code PATH_STATE}, which a viewer panel deliberately does not carry, so it answers null here — and
     * a null there is silent: the tab opens at the top of the file and the reveal simply does not happen,
     * which reads as the declaration having been at line 1.</p>
     *
     * @param at where to put the caret, or null to open at the top — which is what a name with no
     *           location means, and is not an error
     */
    public void openFileAt(CgPath path, @Nullable TextPoint at) {
        if (path == null) return;
        openFile(path, () -> {
            TextEditor opened = activeEditor();
            if (opened == null) return;
            if (at != null) opened.revealAt(at);
            UIDocument window = document();
            if (window != null) window.focus().requestFocus(opened);
        });
    }

    /** @see #openFileAt — the same act for a resource the workspace does not hold. */
    public void openResourceAt(Resource resource, @Nullable TextPoint at) {
        openResourceAt(resource, at, null);
    }

    /**
     * @param member the member to land on once the text exists, or null when {@code at} is already right
     *               — see {@code DeclarationSite.member}
     */
    public void openResourceAt(Resource resource, @Nullable TextPoint at, @Nullable String member) {
        if (resource == null) return;
        openResource(resource, () -> {
            TextEditor opened = viewers.get(resource.toString());
            if (opened == null) return;
            if (at != null) opened.revealAt(at);
            UIDocument window = document();
            if (window != null) window.focus().requestFocus(opened);
            if (member != null) revealMember(resource, opened, member);
        });
    }

    /**
     * Moves the caret onto {@code member} once the provider has worked out where it is.
     *
     * <h3>Off the UI thread, and the reveal to the top happens anyway</h3>
     *
     * <p>{@link ResourceContentProvider#locate} parses the reconstructed text, which is the same order of
     * cost as producing it, so it cannot run in the callback that opened the tab. The tab therefore opens
     * at the top and the caret arrives a moment later — which is the behaviour every IDE has for a
     * decompiled class and is strictly better than the alternative of holding the tab closed until a
     * parse finishes.</p>
     *
     * <p><b>Nothing is revealed if the tab moved on.</b> A reader who navigates twice quickly must not
     * have the first answer land in the second class, so the viewer is re-read from the map rather than
     * captured, and a null answer leaves the caret where the open put it.</p>
     */
    private void revealMember(Resource resource, TextEditor viewer, String member) {
        ResourceContentProvider provider = ResourceRegistry.providerFor(resource);
        if (provider == null) return;
        jobs().job(JobKey.of(this, "locate-member"), JobLane.LATENCY,
                        context -> provider.locate(resource, member))
                .onDone(point -> {
                    if (point == null) return;
                    if (viewers.get(resource.toString()) != viewer) return;
                    viewer.revealAt(point);
                })
                .submit();
    }

    /**
     * Runs {@code then} once the viewer's text has landed — immediately if it already has.
     *
     * <p>Without the two cases a reveal races the read it depends on: the first open of a class waits on
     * an archive, and every later open of the same one is already loaded and would otherwise wait for a
     * job that is never submitted. The same shape {@code openFile}'s callback has, for the same
     * reason.</p>
     */
    private void whenViewerLoaded(Resource resource, @Nullable Runnable then) {
        if (then == null) return;
        String key = resource.toString();
        if (viewersLoaded.contains(key)) {
            then.run();
            return;
        }
        viewerPending.computeIfAbsent(key, ignored -> new ArrayList<>()).add(then);
    }

    /**
     * Fills the viewer for {@code resource}, then runs {@code then} on the UI thread.
     *
     * <p><b>Off the UI thread, though the provider's own contract is synchronous.</b>
     * {@link ResourceContentProvider} documents itself as callable from a paint path and returns bytes
     * rather than a promise, which is right for a small generated document and is not what this reaches:
     * a source archive is IO and a decompiler is hundreds of milliseconds. Scheduling the call rather
     * than changing the contract keeps both usable — the hop back is {@code JobScheduler}'s
     * {@code onDone}, which is documented to run during {@code drain()} on the UI thread.</p>
     */
    private void readViewer(Resource resource, TextEditor editor) {
        // CONTENT, not description: a project file's bytes come from the workspace client and from
        // nowhere else, whatever has registered to describe the scheme. @see ResourceRegistry
        ResourceContentProvider provider = ResourceRegistry.contentProviderFor(resource);
        if (provider == null) return;
        String key = resource.toString();
        jobs()
                // KEYED ON THE EDITOR, which is what a JobKey's owner is for — it is compared by
                // identity, and there is exactly one editor per resource. Two opens of the same class
                // while the first read is in flight therefore replace rather than race.
                .job(JobKey.of(editor, "viewer-read"), JobLane.LATENCY,
                        context -> {
                            // OFF THE FRAME THREAD -- an archive read, or CFR.
                            long read = System.nanoTime();
                            byte[] got = provider.read(resource);
                            FrameProfile.note("[worker] provider.read " + resource + " -> "
                                    + (got == null ? -1 : got.length) + " bytes in "
                                    + (System.nanoTime() - read) / 1_000_000L + "ms");
                            // AND EVERYTHING THE BYTES ALONE DECIDE, on this thread rather than on the
                            // frame that receives them: the decode, the line-ending scan, the
                            // normalisation copy and the rope. Measured at ~10ms of a 34ms frame for a
                            // 108KB decompiled class. @see TextBuffer#prepare
                            long prepared = System.nanoTime();
                            TextBuffer.Prepared ready = TextBuffer.prepare(
                                    got == null ? "" : new String(got, StandardCharsets.UTF_8));
                            FrameProfile.note("[worker] prepare -> " + ready.document().lineCount()
                                    + " lines in " + (System.nanoTime() - prepared) / 1_000_000L + "ms");
                            return ready;
                        })
                .onDone(ready -> {
                    // READ-ONLY IS LIFTED FOR THE FILL AND PUT BACK. `setText` goes through the same
                    // edit path typing does, so a viewer that is already read-only refuses its own
                    // content -- and refuses it silently, leaving a blank tab that looks like a failed
                    // read. The window is one statement long and on the UI thread.
                    long profiled = FrameProfile.enter("viewer-read onDone (FRAME THREAD) " + key);
                    editor.setReadOnly(false);
                    long timed = FrameProfile.begin();
                    editor.setText(ready);
                    FrameProfile.step(timed, "editor.setText (prepared)");
                    editor.setReadOnly(true);
                    viewersLoaded.add(key);
                    List<Runnable> waiting = viewerPending.remove(key);
                    if (waiting != null) {
                        timed = FrameProfile.begin();
                        for (Runnable each : waiting) each.run();
                        FrameProfile.step(timed, "waiting callbacks (reveal, focus)");
                    }
                    FrameProfile.leave(profiled, "viewer-read onDone");
                })
                .submit();
    }

    /**
     * The editor for a resource, created once and kept.
     *
     * <p>Created empty and filled by {@link #readViewer}, because the dock builds a panel from its ref
     * alone — on a split, on a drag, and on a <b>layout restore</b>, where nothing has read anything.
     * The restore path therefore re-asks the provider rather than reconstructing from a file, which is
     * the whole reason a viewer tab can survive a restart at all.</p>
     */
    private TextEditor viewerFor(Resource resource) {
        String key = resource.toString();
        TextEditor existing = viewers.get(key);
        if (existing != null) return existing;

        long timed = FrameProfile.begin();
        TextEditor created = new TextEditor("");
        FrameProfile.step(timed, "new TextEditor");
        created.addClass(FILE_EDITOR_CLASS);
        created.addClass(VIEWER_CLASS);
        created.setReadOnly(true);
        timed = FrameProfile.begin();
        LanguageRegistry.Entry entry = LanguageRegistry.forFileName(viewerFileNameOf(resource));
        FrameProfile.step(timed, "LanguageRegistry.forFileName");
        created.setLanguage(entry.language());
        // THE TOKENIZER IS BUILT ON A WORKER, and arrives beside the text rather than before it.
        //
        // A tree-sitter tokenizer compiles its grammar's whole `highlights.scm` natively, per document,
        // and Java's is large: measured at 17ms on the frame thread for the SECOND one in a process --
        // so it is not a cold start, it is what opening any Java document costs. It was a third of the
        // keystroke that opens a viewer.
        //
        // Off-thread is safe here in a way sharing one compiled query would not be. `close()` frees the
        // query, so a cache handing the same TSQuery to two documents would free it under whichever
        // outlives the other -- a use-after-free, which is a JVM crash rather than an exception. Building
        // a fresh one on a worker keeps every native object owned by exactly one document, and asks
        // nothing about whether tree-sitter's query type is safe to read from two threads at once.
        //
        // And nothing is missing meanwhile: this editor is EMPTY until `readViewer` lands, so there is no
        // window in which text is on screen uncoloured. `setTokenizer` clears the row cache and marks the
        // highlights dirty, so it re-colours whatever arrived first regardless of which job wins.
        jobs().job(JobKey.of(created, "viewer-tokenizer"), JobLane.LATENCY,
                        context -> DocComments.refining(entry.newTokenizer()))
                .onDone(tokenizer -> {
                    // STILL THE EDITOR FOR THIS RESOURCE. A tab opened and closed inside the build would
                    // otherwise be handed a tokenizer nothing will ever close -- one leaked native set.
                    if (viewers.get(key) != created) return;
                    created.setTokenizer(tokenizer);
                })
                .submit();
        // AND SERVICES, HANDED THE RESOURCE -- which is what lets the engine recognise a borrowed
        // document and configure itself for one: no diagnostics, because its problems are ours and
        // nobody reading it can act on them, and a compliance chosen by where the text came from,
        // because a JDK file parsed above Java 8 conflicts with the module that owns its package, and
        // that single error stops the whole unit resolving.
        //
        // Without them a viewer colours from the grammar and answers nothing: no hover, no Ctrl+Click
        // onward, no telling a field from a parameter -- which is most of why anybody opens a class
        // they cannot edit.
        timed = FrameProfile.begin();
        created.setLanguageServices(entry.newServices(created.buffer(), resource));
        FrameProfile.step(timed, "entry.newServices (engine open + first analyse)");
        // AND ITS JUMPS GO SOMEWHERE. @see #routeDefinitionsOf
        routeDefinitionsOf(created);
        timed = FrameProfile.begin();
        WorkbenchSettings.applyTo(this, created);
        FrameProfile.step(timed, "WorkbenchSettings.applyTo");
        viewers.put(key, created);
        timed = FrameProfile.begin();
        readViewer(resource, created);
        FrameProfile.step(timed, "readViewer (submit)");
        return created;
    }

    /**
     * A file name for the registry to key on — {@code ArrayList.java}.
     *
     * <p>{@code LanguageRegistry} answers by file name, and a resource has none. Deriving one is honest
     * here in a way it is not in general: the resource names a Java type, so {@code .java} is a fact
     * about it rather than a guess. A scheme whose content is not Java will need its own answer, which is
     * why this is a method rather than a concatenation at the call site.</p>
     */
    private static String viewerFileNameOf(Resource resource) {
        return viewerTitleOf(resource) + ".java";
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
        String path = panel.state(PATH_STATE, "");
        return path.isEmpty() ? null : CgPath.parse(path);
    }

    /** The active document, whatever kind it is. */
    @Nullable
    public FileDocument activeDocument() {
        CgPath path = activeFilePath();
        return path == null ? null : open.get(path);
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
        return activeDocument() instanceof TextFileDocument text ? text.editor() : null;
    }

    /** The document currently told it is active, so the previous one can be told it is not. */
    @Nullable
    private FileDocument activeStatusDocument;

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
     * <p>The one thing no document can work out for itself is which tab is active. So that is what is said,
     * through {@link FileDocument#setActive}, and each document publishes and withdraws its own items. Both
     * references draw the line in the same place; see that method.</p>
     *
     * <p>The breadcrumb trail stays here, and is not the same kind of thing: it describes the tab's
     * <em>identity</em> — where the thing you are looking at lives — which is the dock's business and is
     * answerable for a document that has no content to report at all.</p>
     */
    private void bindStatusToActiveTab() {
        statusBar.breadcrumbs().setCrumbs(trailFor(activeFilePath()));
        refreshPresence();

        FileDocument active = activeDocument();
        if (active == activeStatusDocument) return;
        // DEACTIVATE FIRST. Both halves write status items, and a document that publishes before the
        // previous one has withdrawn would have its keys cleared a moment later by the tab it replaced.
        if (activeStatusDocument != null) activeStatusDocument.setActive(false);
        activeStatusDocument = active;
        if (active != null) active.setActive(true);
    }

    /**
     * The project, then the path within it — which is what IntelliJ shows and what a bare path cannot say.
     *
     * <p>{@code segments()} is project-<em>relative</em>, so a file at the project root produced a
     * one-segment trail reading just {@code manifest.mf}: true, and useless, because the one thing a
     * breadcrumb is for is saying where among several places you are.</p>
     */
    private static List<Breadcrumbs.Crumb> trailFor(@Nullable CgPath path) {
        if (path == null) return List.of();
        List<Breadcrumbs.Crumb> trail = new ArrayList<>();
        trail.add(Breadcrumbs.Crumb.of(path.project()));
        List<String> segments = path.segments();
        for (int i = 0; i < segments.size(); i++) {
            String name = segments.get(i);
            // THE FILE GETS AN ICON; THE FOLDERS ABOVE IT DO NOT. IntelliJ draws a folder glyph on every
            // directory crumb, and in a 22px bar that is four near-identical marks competing with the one
            // that carries information -- the file's type is the thing you cannot read off the text.
            if (i < segments.size() - 1) {
                trail.add(Breadcrumbs.Crumb.of(name));
                continue;
            }
            FileIconTheme theme = FileIconTheme.getDefault();
            trail.add(new Breadcrumbs.Crumb(name, theme.drawableFor(name, false, false),
                    theme.classFor(name, false)));
        }
        return trail;
    }

    /** Writes the active tab back. A stale write is reported distinctly — it has a recovery path. */
    public boolean saveActiveFile() {
        CgPath target = activeFilePath();
        if (target == null) {
            Notifications.show(Notification.warning("No file tab active"));
            return false;
        }
        FileDocument document = open.get(target);
        if (document == null) return false;
        if (!open.isSaveable(target)) {
            Notifications.show(Notification.error("Refusing to save")
                    .withDetail(target.name() + " never loaded"));
            return false;
        }
        byte[] written = document.encode();
        client.save(target, written, etag -> saved(target, written),
                failure -> {
                    if (failure.isConflict()) {
                        askWhichVersionSurvives(target, written);
                        return;
                    }
                    Notifications.show(saveFailed(target, failure)
                            .withAction("Retry", this::saveActiveFile));
                });
        return true;
    }

    /**
     * THE BYTES THAT WERE WRITTEN become the new baseline, not the document's current state: a document
     * edited again while the write was in flight is still modified afterwards, and recording what it
     * looks like NOW would call it clean.
     */
    private void saved(CgPath target, byte[] written) {
        open.markSaved(target, written);
        // THE DISAGREEMENT IS OVER, whichever way it was resolved -- a successful write means this
        // buffer is now what the server holds. Leaving the mark would show a conflict badge on a file
        // that has none, which teaches people to ignore the badge.
        externallyChanged.remove(target);
        externallyDeleted.remove(target);
        refreshTabTitles();
    }

    /**
     * Phase 5.5 — a conflict is a question, not a notification.
     *
     * <p>This was a balloon whose single action was <i>"Reopen to take theirs"</i>, and the comment beside
     * it already said the prose had named the fix and that it should be a button. It understated the
     * problem twice. A balloon <b>fades</b>, so a user who was not looking takes the default — and the
     * default was "your save silently did not happen". And that one button <b>discards unsaved work in a
     * click</b>, while the opposite resolution, keep mine, was not offered at all.</p>
     *
     * <p>Both resolutions destroy something, which is exactly the case that earns a modal.
     * @see ConflictDialog</p>
     */
    /**
     * Phase 6.3 — what to do when a file changes on the server underneath an open editor.
     *
     * <p>The notification has crossed the wire since Phase 4 and reached <b>only the file tree</b>: an
     * open editor was never told. So a clean buffer showed stale content for ever, a deleted file left a
     * perfectly normal-looking tab, and a change under a dirty buffer was discovered at save time or not
     * at all.</p>
     *
     * <table>
     *   <tr><th>State</th><th>What happens</th></tr>
     *   <tr><td>Clean, changed</td><td><b>Reloaded silently.</b> The overwhelmingly common case — a
     *       git checkout, an external save — and prompting for it is what makes a watcher feel naggy
     *       rather than helpful. VS Code does the same, and there is nothing to lose: the buffer and the
     *       file agreed a moment ago</td></tr>
     *   <tr><td>Dirty, changed</td><td>Marked and <b>left alone</b>. Reloading would destroy unsaved
     *       work without asking; the decision belongs at save time, where {@code ConflictDialog} already
     *       makes it with all three answers on the table</td></tr>
     *   <tr><td>Deleted</td><td>Marked, buffer kept. Closing the tab throws away text the user may well
     *       want to write back — which is the whole reason the buffer is worth more than the file</td></tr>
     *   <tr><td>Not open</td><td>Nothing. The tree refresh above is the entire correct response</td></tr>
     * </table>
     */
    private void applyExternalChange(WorkspaceClient.FileChanged change) {
        CgPath path = change.path();
        if (open.get(path) == null) return;

        if (change.isDeleted()) {
            externallyDeleted.add(path);
            externallyChanged.remove(path);
            refreshTabTitles();
            return;
        }

        externallyDeleted.remove(path);
        if (open.isDirty(path)) {
            // MARKED, NOT RELOADED. @see ConflictDialog, which is where this is actually resolved.
            externallyChanged.add(path);
            refreshTabTitles();
            return;
        }

        // CLEAN: take the new bytes without asking. forget() first, or the conditional read answers
        // UNCHANGED out of the etag this client is holding -- which is precisely the etag that just
        // stopped being true.
        client.forget(path);
        client.read(path,
                reloaded -> {
                    adoptInto(path, reloaded.content());
                    open.markSaved(path, reloaded.content());
                    externallyChanged.remove(path);
                    refreshTabTitles();
                },
                failure -> {
                    // Could not take it after all. Marked rather than silently left stale, because a
                    // buffer that disagrees with the server and says nothing is the state this whole
                    // item exists to remove.
                    externallyChanged.add(path);
                    refreshTabTitles();
                });
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
        public java.util.Collection<CgPath> decorated() {
            if (externallyChanged.isEmpty() && externallyDeleted.isEmpty()) return java.util.List.of();
            java.util.List<CgPath> all =
                    new java.util.ArrayList<>(externallyChanged.size() + externallyDeleted.size());
            all.addAll(externallyChanged);
            all.addAll(externallyDeleted);
            return all;
        }
    };

    /** Files that moved on the server under a DIRTY buffer, so the tab can say so. @see #applyExternalChange */
    private final java.util.Set<CgPath> externallyChanged = new java.util.LinkedHashSet<>();

    /** Files deleted on the server while still open here. @see #applyExternalChange */
    private final java.util.Set<CgPath> externallyDeleted = new java.util.LinkedHashSet<>();

    /**
     * Phase 5.6 — who else has this file open, phrased for a human.
     *
     * <p>{@code null} rather than an empty string when nobody is known, because the dialog omits the
     * whole line rather than drawing "nobody has it open" — which would be a claim, and the client cannot
     * make it: an empty presence list means <em>nothing has been said</em>, not that the file is
     * unoccupied. @see WorkspaceClient#whoElseHasOpen</p>
     */
    @Nullable
    private String othersEditing(CgPath target) {
        List<String> others = client.whoElseHasOpen(target);
        if (others.isEmpty()) return null;
        if (others.size() == 1) return others.get(0);
        if (others.size() == 2) return others.get(0) + " and " + others.get(1);
        return others.get(0) + " and " + (others.size() - 1) + " others";
    }

    private void askWhichVersionSurvives(CgPath target, byte[] written) {
        // CAPTURED BEFORE ANYTHING READS: finishRead overwrites cachedContent with whatever the server
        // now holds, so a base fetched after the read is the SERVER version wearing the name of the
        // ancestor -- and a three-way merge against that reports every one of my own edits as a conflict.
        byte[] base = client.baseContent(target);
        ConflictDialog.ask(this, target, othersEditing(target),
                () -> overwrite(target, written),
                () -> openFile(target),
                // No base, no merge. A file that was never read has no common ancestor, and offering the
                // option and then failing is worse than not offering it.
                base == null ? null : () -> openMerge(target, written, base));
    }

    /**
     * Fetches the server's current copy and opens a three-way merge over it.
     *
     * <p>The read is what makes this asynchronous: {@code written} and {@code base} are both already in
     * hand, and only <em>theirs</em> has to come off the wire.</p>
     */
    private void openMerge(CgPath target, byte[] written, byte[] base) {
        client.read(target,
                document -> showMerge(target, base, written, document.content()),
                failure -> Notifications.show(saveFailed(target, failure)));
    }

    private void showMerge(CgPath target, byte[] base, byte[] mine, byte[] theirs) {
        UIDocument window = document();
        if (window == null) return;

        ThreeWayMerge merge = ThreeWayMerge.of(text(base), text(mine), text(theirs));
        MergeView view = new MergeView(merge);

        Dialog dialog = new Dialog("Merge " + target.name());
        dialog.getContent().append(view);

        UINode actions = new UINode();
        actions.addClass(MergeView.DIALOG_ACTIONS_CLASS);
        dialog.getContent().append(actions);

        Button apply = new Button("Save merged");
        Button cancel = new Button("Cancel");
        actions.append(apply);
        actions.append(cancel);

        // GATED ON EVERY CONFLICT HAVING BEEN DECIDED, not on there being none. An undecided conflict
        // still produces text -- it defaults to mine -- so an ungated button would write a merge nobody
        // read and it would look like it worked.
        Runnable syncEnabled = () -> {
            boolean ready = view.isResolved();
            apply.setEnabled(ready);
            apply.setHitTest(ready);
        };
        view.onChanged.connect(syncEnabled);
        syncEnabled.run();

        apply.onPressed.connect(() -> {
            String merged = view.mergedText();
            dialog.close();
            overwrite(target, merged.getBytes(StandardCharsets.UTF_8));
        });
        cancel.onPressed.connect(dialog::close);

        window.addOverlay(dialog, this);
        dialog.onClosed.connect(dialog::removeSelf);
        dialog.showModal();
        window.focus().requestFocus(cancel);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * <b>Keep mine</b> — writes the active file over whatever the server holds.
     *
     * <p>Named rather than left inline inside the conflict handler, because it is one of the three
     * answers {@code ConflictDialog} offers and the only one with no other way to reach it. A branch that
     * exists only inside a modal's callback cannot be tested without driving the modal, which is how a
     * resolution path ends up being the one nobody checks.</p>
     */
    public boolean overwriteActiveFile() {
        CgPath target = activeFilePath();
        if (target == null) return false;
        FileDocument document = open.get(target);
        if (document == null || !open.isSaveable(target)) return false;
        overwrite(target, document.encode());
        return true;
    }

    private void overwrite(CgPath target, byte[] written) {
        client.overwrite(target, written, etag -> saved(target, written),
                // A SECOND failure is not another conflict -- overwrite carries no etag, so it cannot be
                // refused as stale. Anything arriving here is a real error and belongs on the ordinary
                // path rather than reopening the question.
                again -> Notifications.show(saveFailed(target, again)));
    }

    /**
     * The two failures that come from the CLIENT rather than the file service, phrased once.
     *
     * <p>{@code WorkspaceClient} deliberately reports nothing — it is the transport, it has no business
     * deciding UI, and it is the layer a dedicated server also runs. So these two cannot be centralised the
     * way file operations were, through {@code onDidFail}; what they can be is stated in one place here,
     * which is what stops "Open failed" drifting between the two sites that raise it.</p>
     *
     * <p>Returned rather than shown, so a caller can add the action it knows about — retrying an open means
     * something different from retrying a save.</p>
     */
    private static Notification openFailed(CgPath path, WorkspaceClient.Failure failure) {
        return Notification.error("Open failed").withDetail(path.name() + " — " + failure.code());
    }

    /** @see #openFailed */
    private static Notification saveFailed(CgPath path, WorkspaceClient.Failure failure) {
        return Notification.error("Save failed").withDetail(path.name() + " — " + failure.code());
    }

    /**
     * How a failed operation is titled — "Move failed", "Delete failed".
     *
     * <p>Derived from the {@code Kind} rather than passed in, which is the point of reporting centrally: the
     * verb was the only thing that differed between the eleven copies, and it is something the operation
     * already knows.</p>
     *
     * <p>A null operation is the undo path — {@code WorkspaceFileService.report} emits one when an
     * <em>inverse</em> fails, where there is no user-initiated operation to name. That is worth reporting
     * with less precision rather than not reporting: an undo that silently did not happen is the worst case
     * here, because the tree still shows the state the user was trying to leave.</p>
     */
    private static String failedVerb(@Nullable WorkspaceFileService.Operation operation) {
        if (operation == null) return "Undo failed";
        switch (operation.kind()) {
            case CREATE:
            case CREATE_FOLDER: return "Create failed";
            case MOVE:          return "Move failed";
            case COPY:          return "Copy failed";
            case DELETE:        return "Delete failed";
            default:            return "Operation failed";
        }
    }

    /** The name it was about, and what the server said. */
    private static String failureDetail(@Nullable WorkspaceFileService.Operation operation,
                                        WorkspaceClient.Failure failure) {
        String code = failure == null ? "unknown" : failure.code();
        if (operation == null) return code;
        // The SOURCE for a move or a copy: "Move failed / notes.txt" is what the user asked about, whereas
        // the target is a name they have never seen if the operation is what created it.
        CgPath named = operation.source() != null ? operation.source() : operation.target();
        return named.name() + " — " + code;
    }

    /** Every file operation, with the open editors accounted for. */
    public WorkspaceFileService files() {
        return fileService;
    }

    /**
     * Re-reads the folders an operation touched.
     *
     * <p>Both ends of a move, because a rename empties one directory and fills another — invalidating only
     * the destination leaves the file visible in the folder it left, which reads as the rename having
     * duplicated it.</p>
     */
    private void refreshAfter(WorkspaceFileService.Operation op) {
        fileTree.source().invalidate(op.target().parent());
        if (op.source() != null) fileTree.source().invalidate(op.source().parent());
        fileTree.treeView().refresh();
    }

    /**
     * How {@link WorkspaceFileService} reaches the open editors without being able to see a widget.
     *
     * <p>An inner class rather than {@code Workbench implements WorkingCopies}: these three methods are
     * the file service's business and nobody else's, and putting them on the public surface invites a
     * caller to {@code close()} an editor directly — which drops it from the map and leaves its tab in the
     * dock, because closing a document and closing a tab are two different things that look like one.</p>
     */
    private final class Copies implements WorkingCopies {

        @Override
        public List<CgPath> openUnder(CgPath path) {
            List<CgPath> found = new ArrayList<>();
            for (CgPath candidate : open.paths()) {
                // contains() covers "the path itself" as well as "beneath it", so a file delete and a
                // directory delete are one question. Deleting a folder with six files open in it is
                // exactly the case a per-path lookup misses.
                if (candidate.equals(path) || path.contains(candidate)) found.add(candidate);
            }
            return found;
        }

        @Override
        public void close(CgPath path) {
            open.close(path);
            // The TAB goes too, and this is the half that is easy to forget: an editor dropped from the
            // map with its tab left behind leaves the dock asking the registry to rebuild a panel for a
            // file that no longer exists, which comes back as the "__missing__" placeholder.
            dock.layout().closePanel(refFor(path));
            dock.requestRebuild();
        }

        @Override
        public void retarget(CgPath from, CgPath to) {
            if (!open.isOpen(from)) return;
            // ONE CALL, because one entry holds the document, its baseline and its load state together.
            open.retarget(from, to);
            // In place, so the tab keeps its position and its selection. A remove-then-add would send the
            // renamed file to the end of the strip and, if it was active, hand the selection to a
            // neighbour on the way -- the file you just renamed vanishing from where you were looking.
            DockPanelRef was = refFor(from);
            for (DockLeaf leaf : dock.layout().leaves()) {
                if (leaf.replace(was, refFor(to))) break;
            }
            dock.requestRebuild();
        }
    }

    private DockLeaf centralLeaf() {
        return centralLeaf(dock);
    }

    /** The central leaf of {@code in} — which is not always this workbench's own dock. @see #activeDock */
    private static DockLeaf centralLeaf(DockArea in) {
        for (DockLeaf leaf : in.layout().leaves()) {
            if (leaf.isCentral()) return leaf;
        }
        return in.layout().leaves().get(0);
    }

    /**
     * The dock a newly opened document goes into — <b>the one the user is working in</b> (W9).
     *
     * <h3>Asked of the compositor, not tracked here</h3>
     *
     * <p>Once an editor tab can be torn out into a window of its own, "open this file" has more than one
     * possible destination, and both references answer it the same way: the last editor group you were
     * in gets the next file. Opening into this workbench's own dock regardless would mean a torn-out
     * window could never be worked in — every file you opened from it would appear behind it, in the
     * window you had just deliberately left.</p>
     *
     * <p>The answer is the <b>active window</b>, which the desktop already tracks and already updates on
     * exactly the gestures that should move it: a press in a frame, focus arriving, the switcher. A
     * second notion of "active dock" maintained here would be a copy of that, kept in step by hand, and
     * would disagree with the title bar the first time one of them missed an event.</p>
     *
     * <p>Falls back to this workbench's own dock whenever the active window is not a torn-out one, which
     * covers the ordinary case, the no-desktop case, and the editor's own frame.</p>
     */
    public DockArea activeDock() {
        UIDocument window = document();
        if (window == null) return dock;
        WindowFrame active = Desktop.of(window).activeWindow();
        if (active instanceof DockWindow torn && torn.area() != null) return torn.area();
        return dock;
    }

    /**
     * Registers a document kind: how to build it, and the dock panel that shows it.
     *
     * <p>One call rather than two, because the panel content simply <em>is</em> the document view.
     * Separating them would let a host register a panel type it has no document for, which builds a tab
     * that cannot be saved and reports nothing wrong.</p>
     *
     * <p>Bind it to files with {@link #bindEditorExtensions} and friends. A type with no binding is
     * reachable only by opening its ref directly, which is what a panel that is not file-backed does.</p>
     */
    /**
     * Enables a contributed {@link DocumentType} — the whole registration, in one call.
     *
     * <p>What a package that owns a file type calls, and the only thing an application has to know about
     * that package. Replaces a {@code registerDocumentType} plus one binding call per pattern kind, which
     * could be — and were — half-done.</p>
     *
     * @throws IllegalArgumentException if the type declares no factory, because that is a registration
     *         that would fail at the moment a user opens a file instead of here
     */
    public Workbench contribute(DocumentType type) {
        if (type.factory() == null) {
            throw new IllegalArgumentException(
                    "DocumentType " + type.typeId() + " has no document factory — it could never open");
        }
        registerDocumentType(type.typeId(), type.title(), type.factory());
        if (!type.extensions().isEmpty()) {
            bindEditorExtensions(type.typeId(), type.extensions().toArray(new String[0]));
        }
        if (!type.fileNames().isEmpty()) {
            bindEditorNames(type.typeId(), type.fileNames().toArray(new String[0]));
        }
        if (!type.globs().isEmpty()) {
            bindEditorGlobs(type.typeId(), type.globs().toArray(new String[0]));
        }
        return this;
    }

    public Workbench registerDocumentType(String typeId, String title,
                                          Function<CgPath, FileDocument> factory) {
        documentFactories.put(typeId, factory);
        registry.register(DockPanelDescriptor.document(typeId, title), ref -> {
            CgPath path = CgPath.parse(ref.state(PATH_STATE, ""));
            FileDocument document = documentFor(path);
            // Read here as well as in openFile, because the dock also builds panels after a layout
            // RESTORE, where nothing has read the file yet. Guarded, or every split and drag would
            // re-read the file over whatever is unsaved in it.
            if (open.requestRead(path)) {
                client.read(path, read -> adoptInto(path, read.content()),
                        failure -> Notifications.show(openFailed(path, failure)));
            }
            return document.view();
        });
        return this;
    }

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
     * Whether this open file has changes that are not on disk.
     *
     * <p>Compared against the bytes last read or written rather than counted from edit events: a counter
     * says "modified" after a change <em>and its undo</em>, which is exactly the state somebody is in when
     * they close a tab and get asked to save a file identical to the one already there.</p>
     *
     * <p>False for a file that is not open, and false for one whose document refused to load it. Encoding
     * on each call is what makes the comparison exact; it runs once a frame per open file, from the tick
     * that keeps the tab markers current. See {@link OpenDocuments#isDirty}.</p>
     */
    public boolean isDirty(CgPath path) {
        return open.isDirty(path);
    }

    /** Every open file with unsaved changes, in no particular order. */
    public List<CgPath> unsavedFiles() {
        return open.dirtyPaths();
    }

    /**
     * Writes every modified file.
     *
     * <p>Issued per file rather than as one call, because they succeed and fail separately — the same
     * reasoning the drop and the paste follow. No undo grouping, though: saving is not an edit, and it is
     * not on the undo stack at all.</p>
     *
     * @return how many writes were issued
     */
    public int saveAll() {
        int issued = 0;
        for (CgPath path : unsavedFiles()) {
            FileDocument document = open.get(path);
            if (document == null) continue;
            issued++;
            byte[] written = document.encode();
            client.save(path, written, etag -> {
                open.markSaved(path, written);
                refreshTabTitles();
            }, failure -> Notifications.show(saveFailed(path, failure)));
        }
        if (issued == 0) Notifications.show(Notification.info("Nothing to save"));
        return issued;
    }

    /**
     * Whether {@code panel} may close now, asking the user first when it would discard unsaved work.
     *
     * <p>Returns <b>false</b> for a modified file and puts up a prompt, rather than trying to answer
     * "yes, eventually": the prompt is asynchronous, so there is no answer to give at the moment the dock
     * asks. Confirming closes through {@link DockArea#closePanelDiscarding}, which skips this guard —
     * without that it would ask again, forever.</p>
     *
     * <p>Only files are guarded. A tool panel — the tree, Problems — holds nothing that is not on disk, and
     * asking about it would train the answer out of the user by the time it matters.</p>
     */
    private boolean confirmClose(DockPanelRef panel) {
        String state = panel.state(PATH_STATE, "");
        if (state.isEmpty()) return true;
        CgPath path = CgPath.parse(state);
        if (!isDirty(path)) return true;

        InputDialog.confirm(this, "Unsaved changes",
                path.name() + " has unsaved changes — Enter to discard, Escape to keep editing",
                () -> dock.closePanelDiscarding(panel));
        return false;
    }

    /**
     * What each tab's label should say right now — the file name, plus a marker when it is modified.
     *
     * <p>Registered as the registry's title provider, so it is consulted whenever a tab is built or
     * refreshed. Returns null for a panel with no file, which is the provider contract's way of saying
     * "nothing to add" and lets the registry fall through to the panel's own title.</p>
     *
     * <p>Reads {@code TITLE} state directly rather than calling {@code registry.titleOf}, which would
     * re-enter this method.</p>
     */
    @Nullable
    private String tabTitleFor(DockPanelRef panel) {
        Resource viewed = viewedResource(panel);
        if (viewed != null) return viewerDisplayName(viewed);
        String path = panel.state(PATH_STATE, "");
        if (path.isEmpty()) return null;
        String title = panel.state(DockPanelRef.TITLE, CgPath.parse(path).name());
        return isDirty(CgPath.parse(path)) ? title + DIRTY_MARKER : title;
    }

    /**
     * How a tab is coloured — the same answer the file's row in the tree gets, from the same providers.
     *
     * <p>Asked of {@link FileDecorations} rather than of {@code markers} directly, and that is the point
     * of routing it this way: a tab and a tree row showing different things about one file is precisely
     * the disagreement a shared model exists to prevent, and everything else that decorates a file —
     * dirty state, VCS, whatever comes next — reaches the tab for free rather than needing a second
     * mechanism per surface.</p>
     *
     * <p><b>Not bubbled and not directory-resolved</b>: a tab is always a file.</p>
     */
    @Nullable
    private String tabDecorationFor(DockPanelRef panel) {
        // A BORROWED FILE IS TINTED, which is the one decoration a viewer carries and the reason it can
        // share the file-decoration slot rather than needing a second one: a library class has no VCS
        // state, no dirty marker and no compile errors of its own to report, so nothing can collide.
        // IntelliJ tints these tabs for the same reason -- it is the fastest way to say "this is not
        // yours" without spending a word on it.
        if (VIEWER_TYPE.equals(panel.typeId())) return LIBRARY_DECORATION;
        String path = panel.state(PATH_STATE, "");
        if (path.isEmpty()) return null;
        // NULL IS THE ORDINARY ANSWER -- an undecorated file is the state nearly every file is in, and
        // resolve() says so with null rather than with an empty decoration.
        var decoration = fileTree.getDecorations().resolve(CgPath.parse(path), false);
        return decoration == null ? null : decoration.styleClass();
    }

    /**
     * Re-reads every open tab's decoration.
     *
     * <p>Through the dock's own {@code refreshPanelPresentation} rather than by walking leaves to groups
     * to tabs — the walk {@code DockArea} explicitly warns callers off, because it keeps compiling long
     * after the dock changes how a tab is built.</p>
     */
    private void syncTabDecorations() {
        for (DockPanelRef panel : dock.allPanels()) dock.refreshPanelPresentation(panel);
    }

    /**
     * Which icon a tab shows — the same one the file's row in the tree shows, from the same theme.
     *
     * <p>Static, because it depends on nothing but the panel. It is no longer pulled once and kept,
     * though: the icon element beside this one asks what the file DECLARES, and that answer is read
     * through {@code ProjectSources}, which does not have it until the file has been read. So
     * {@link #announceProjectSourcesMoved} re-reads every tab's presentation when one lands.</p>
     */
    @Nullable
    private static String tabIconFor(DockPanelRef panel) {
        Resource viewed = viewedResource(panel);
        if (viewed != null) {
            // A DECLARATION'S GLYPH IS AN ELEMENT, not a name -- see viewerIconElement, which the dock
            // asks for first. This is the fallback for a viewer showing a FILE: a `.java` tab takes the
            // file icon, exactly as one in the project does.
            String name = viewerDisplayName(viewed);
            return name == null ? null : FileIconTheme.getDefault().iconFor(name, false, false);
        }
        String path = panel.state(PATH_STATE, "");
        if (path.isEmpty()) return null;
        return FileIconTheme.getDefault().iconFor(CgPath.parse(path).name(), false, false);
    }

    /**
     * The glyph for a viewer tab showing a DECLARATION, or null to fall back to a file icon.
     *
     * <p>{@link SymbolIcon} is the union point: the completion popup builds the same widget from the
     * same {@code completion-kind-*} vocabulary, so a tab and a completion row cannot come to disagree
     * about what an interface looks like. It also carries the {@code static} and {@code final} marks,
     * which an icon NAME cannot — they are layers stacked over the glyph rather than a picture.</p>
     *
     * <p>Null when nothing can say what the tab holds — see {@link #symbolFor}, which is where both
     * kinds of tab now ask the same question.</p>
     */
    @Nullable
    private static UINode viewerIconElement(DockPanelRef panel) {
        SymbolInfo symbol = symbolFor(panel);
        if (symbol == null) return null;
        return new SymbolIcon().show(symbol.kind(), symbol.modifiers());
    }

    /**
     * What the thing behind this tab IS, or null when nothing knows.
     *
     * <p><b>Both kinds of tab, through one seam.</b> This used to ask only about a VIEWER panel, so a
     * decompiled {@code FlexDirection.class} drew an enum glyph and hovered "Final enum" while the
     * author's own {@code Main.java} in the next tab drew a file icon — the same question, asked of a
     * resource nobody had registered a provider for. {@code ProjectSourceSymbols} answers
     * {@code project://} now, and this is where the tab stopped asking.</p>
     *
     * <p>Null stays a supported answer at every step: no resource, no provider, no symbol, or a symbol
     * with no kind all fall through to the file-type icon the tab drew before.</p>
     */
    @Nullable
    private static SymbolInfo symbolFor(DockPanelRef panel) {
        Resource resource = viewedResource(panel);
        if (resource == null) {
            String path = panel.state(PATH_STATE, "");
            if (path.isEmpty()) return null;
            try {
                resource = Resource.of(CgPath.parse(path));
            } catch (RuntimeException notAPath) {
                return null;
            }
        }
        ResourceContentProvider provider = ResourceRegistry.providerFor(resource);
        SymbolInfo symbol = provider == null ? null : provider.symbolOf(resource);
        return symbol == null || symbol.kind() == null ? null : symbol;
    }

    /**
     * What a tab says on hover — where the thing it shows actually is.
     *
     * <p>The label is a bare name, and a name stops identifying anything the moment two of them collide:
     * two {@code Main.java} in one workspace, or {@code java.util.List} beside {@code java.awt.List}. The
     * second pair is the reason a viewer answers with its <b>fully-qualified</b> name rather than a file
     * path — there often is no file, the tab is a decompilation, and the qualified name is the only thing
     * that names it uniquely.</p>
     *
     * <p>Null for a panel that is not about a location at all — a console, the Problems view — which the
     * registry reads as "no tooltip", not as an empty one.</p>
     */
    /**
     * A torn-out window's caption: {@code Project - name.ext [where]} — W9.
     *
     * <h3>Three parts, in the order they are useful</h3>
     *
     * <p>A tab can be terse because it sits in a strip of siblings and is read by shape; a caption is
     * read alone, from across a desktop, and is the only thing that can say which of three files called
     * {@code build.gradle.kts} this one is. So it names the workspace, then the file, then where the
     * file is — the file in the middle because that is what the eye is looking for, with the context on
     * either side of it.</p>
     *
     * <p><b>Both kinds of document take the same shape</b>, which is the point of doing it here rather
     * than twice. A workspace file's "where" is its directory within the project; a borrowed class's is
     * its package, and its project is the workspace you are in rather than one of its own — a library
     * class belongs to a jar, not to the project, and the caption is still telling you where YOU are.
     * With more than one project open that stops being unambiguous, so it says nothing instead of
     * guessing (see {@code WorkspaceTreeSource.soleProjectName}).</p>
     *
     * <p>Null for anything that is not a document at all — a torn-out tool window has a name and no
     * location — and the registry falls back to the tab label for those.</p>
     */
    @Nullable
    private String windowTitleFor(DockPanelRef panel) {
        WorkspaceTreeSource source = fileTree != null ? fileTree.source() : null;

        Resource viewed = viewedResource(panel);
        if (viewed != null) {
            // A qualified name is its own path: everything up to the last dot is the package, and the
            // display name is already the file-shaped form of the last segment ("JarFile.java"), so
            // neither half has to be reassembled from the other.
            String qualified = viewed.path();
            int lastDot = qualified.lastIndexOf('.');
            String pkg = lastDot > 0 ? qualified.substring(0, lastDot) : "";
            return caption(source == null ? null : source.soleProjectName(),
                    viewerDisplayName(viewed), pkg);
        }

        String raw = panel.state(PATH_STATE, "");
        if (raw.isEmpty()) return null;
        CgPath path = CgPath.parse(raw);
        if (path == null) return null;
        String within = path.path();
        int lastSlash = within.lastIndexOf('/');
        String directory = lastSlash > 0 ? within.substring(0, lastSlash) : "";
        return caption(source == null ? null : source.displayNameOf(path), path.name(), directory);
    }

    /**
     * {@code Project - name [where]}, with either context omitted when there is none.
     *
     * <p>Omitted rather than left empty: a caption reading {@code " - name []"} says the same thing as
     * {@code "name"} and looks like a bug in the formatter, which is worse than saying less.</p>
     */
    private static String caption(@Nullable String project, String name, @Nullable String where) {
        StringBuilder out = new StringBuilder();
        if (project != null && !project.isEmpty()) out.append(project).append(" - ");
        out.append(name);
        if (where != null && !where.isEmpty()) out.append(" [").append(where).append(']');
        return out.toString();
    }

    @Nullable
    private static String tabTooltipFor(DockPanelRef panel) {
        Resource viewed = viewedResource(panel);
        if (viewed != null) return viewed.path();
        String path = panel.state(PATH_STATE, "");
        return path.isEmpty() ? null : path;
    }

    /**
     * What a tab's ICON says on hover — what the declaration behind it <em>is</em>.
     *
     * <p>The one fact a library tab shows nowhere else. Nothing in {@code ArrayList.class} distinguishes a
     * class from an interface, an enum or an annotation, and the glyph is where that answer already
     * lives — so the icon is the part of the tab that has something of its own to say, and this is it in
     * words. {@link SymbolIcon#describe} is the single source of both, so the picture and the sentence
     * cannot drift apart.</p>
     *
     * <p>And it is no longer only a library tab that has it: a project {@code .java} row and its tab
     * both show what the file declares, so the sentence follows them there. A tab whose provider cannot
     * say keeps the file icon and gets no icon tooltip, which is the honest pair.</p>
     */
    @Nullable
    private static String tabIconTooltipFor(DockPanelRef panel) {
        SymbolInfo symbol = symbolFor(panel);
        return symbol == null ? null : SymbolIcon.describe(symbol.kind(), symbol.modifiers());
    }

    /** The resource a viewer panel shows, or null for every other kind of tab. */
    @Nullable
    private static Resource viewedResource(DockPanelRef panel) {
        if (!VIEWER_TYPE.equals(panel.typeId())) return null;
        String text = panel.state(RESOURCE_STATE, "");
        return text.isEmpty() ? null : Resource.parse(text);
    }

    /**
     * What a viewer tab is called — the provider's answer, or the bare type name.
     *
     * <p>Asked of the provider rather than derived here, because the extension depends on what is
     * SERVING the resource: {@code ArrayList.java} where source was attached and
     * {@code FlexDirection.class} where the bytes were decompiled. The workbench has no way to know
     * which, and inventing {@code .java} for both would put a source extension on a tab full of
     * reconstructed code.</p>
     *
     * <p><b>Not written into the ref.</b> {@link DockPanelRef} equality includes its state, and the ref
     * is how an open tab is FOUND again — so a title that can change between two reads would orphan the
     * tab it names. The ref keeps the stable simple name; this decorates it for display, which is
     * exactly the split the title provider exists for.</p>
     */
    @Nullable
    private static String viewerDisplayName(Resource resource) {
        ResourceContentProvider provider = ResourceRegistry.providerFor(resource);
        String named = provider == null ? null : provider.displayName(resource);
        return named != null && !named.isEmpty() ? named : viewerTitleOf(resource);
    }

    /**
     * Brings every visible tab label into line with its document.
     *
     * <p>The labels are otherwise only computed when the strip is <b>rebuilt</b>, and a rebuild is exactly
     * what must not happen for this: it detaches and recreates the tab elements, so doing it on every
     * keystroke would tear down the tab the user is typing under — the rule the table header and the file
     * tree both paid for. Setting the text on the tabs that already exist changes nothing structural.</p>
     */
    private void refreshTabTitles() {
        for (DockLeaf leaf : dock.layout().leaves()) {
            DockGroup group = dock.groupFor(leaf);
            if (group == null) continue;
            for (DockPanelRef panel : group.panels()) dock.refreshPanelPresentation(panel);
        }
    }

    /**
     * Keeps the dirty markers current.
     *
     * <p>Polled rather than pushed, because a document goes dirty by being <em>typed into</em> and there is
     * no edit event to hang this on that would not also mean routing every keystroke through the workbench.
     * The cost is one string comparison per open document per frame, and it is only when the answer changes
     * that any element is touched.</p>
     */
    /**
     * Releases the document behind a panel that has just been closed.
     *
     * <h3>Only when nothing else is showing it</h3>
     *
     * <p>A document can have more than one tab — a split showing the same file twice, or a derived view
     * of it. Closing one must not release what the other is still drawing, so this asks the layout
     * whether any panel still names this resource before letting go.</p>
     *
     * <p>Unsaved work is not a consideration here, deliberately: the dock's close <b>guard</b> already
     * asked before anything got this far, and re-asking at release time would be a second prompt for one
     * decision.</p>
     */
    private void releaseClosedPanel(DockPanelRef closed) {
        String raw = closed.state(DockPanelRef.PATH, "");
        if (raw.isEmpty()) return;
        CgPath path;
        try {
            Resource resource = Resource.parse(raw);
            // Only a project resource owns a document. A derived view is somebody else's business and
            // releasing its ORIGIN because a generated tab closed would take the graph with it.
            if (!resource.isProject()) return;
            path = resource.asPath();
        } catch (RuntimeException unparseable) {
            return;
        }
        for (DockLeaf leaf : dock.layout().leaves()) {
            for (DockPanelRef panel : leaf.panels()) {
                if (path.toString().equals(panel.state(DockPanelRef.PATH, ""))) return;
            }
        }
        long timed = FrameProfile.begin();
        open.close(path);
        FrameProfile.step(timed, "close.open.close (dispose the document)");
        timed = FrameProfile.begin();
        onDidCloseDocument.emit(path);
        FrameProfile.step(timed, "close.onDidCloseDocument -> "
                + onDidCloseDocument.connectionCount() + " listeners");
    }

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

    private void refreshDirtyMarkers() {
        List<CgPath> dirty = unsavedFiles();
        if (!dirty.equals(lastDirty)) {
            lastDirty = dirty;
            refreshTabTitles();
        }
    }

    private List<CgPath> lastDirty = new ArrayList<>();


    /**
     * Hands {@code bytes} to a document and records them as what is on disk.
     *
     * <p>The single place a read lands, so the baseline cannot drift from what was actually applied. A
     * document that refuses the bytes is remembered as unreadable rather than left looking modified
     * against a file it never managed to load.</p>
     */
    private void adoptInto(CgPath path, byte[] bytes) {
        documentFor(path);
        String refused = open.adopt(path, bytes);
        if (refused != null) Notifications.show(Notification.error("Cannot open").withDetail(path.name() + " — " + refused));
        // AFTER the bytes are in, which is the whole reason this signal exists rather than the panel
        // factory announcing the open. A document restoring a caret at line 400 into text that has not
        // landed yet clamps it to 0, and the failure looks like the caret never having been saved.
        onDidOpenDocument.emit(path);
    }

    /**
     * Fires when a file's content has been applied to its document.
     *
     * <p>The one moment at which anything derived from the content -- a restored caret, a fold set, a
     * diagnostic pass -- can act. There is deliberately no signal for "a panel was created": that happens
     * while the read is still in flight.</p>
     */
    public final Signal.Value<CgPath> onDidOpenDocument =
            new Signal.Value<>();

    /** The document for a path, created on first use from whichever type its name binds to. */
    public FileDocument documentFor(CgPath path) {
        return open.documentFor(path, key -> {
            String typeId = refFor(key).typeId();
            Function<CgPath, FileDocument> factory = documentFactories.get(typeId);
            // A binding with no document factory is a host bug, and falling back to an empty text editor
            // would hide it: the file would open, show nothing, and save that nothing back over itself.
            if (factory == null) {
                throw new IllegalStateException("No document factory for panel type " + typeId
                        + " -- a bindEditor call named it, registerDocumentType did not");
            }
            return factory.apply(key);
        });
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
     * — and asking for a document by path <em>creates</em> one, so a caller that walked the tabs instead
     * would build the whole session to look at it.</p>
     */
    public java.util.List<CgPath> openPaths() {
        return open.paths();
    }

    /**
     * Every file with a <b>tab</b>, built or not, in strip order across every group.
     *
     * <p>The counterpart to {@link #openPaths()}, and the one that answers "what is open" the way a user
     * would mean it: a restored tab is a title until something activates it, and it is no less open for
     * having no widget behind it yet. Read off the dock's own panel refs, which carry the path — so it
     * costs a walk and builds nothing.</p>
     */
    public java.util.List<CgPath> openTabPaths() {
        java.util.List<CgPath> paths = new ArrayList<>();
        for (DockLeaf leaf : dock.layout().leaves()) {
            for (DockPanelRef panel : leaf.panels()) {
                String path = panel.state(PATH_STATE, "");
                if (path.isEmpty()) continue;
                CgPath parsed = CgPath.parse(path);
                // The same file can be open in two groups -- a split of one document is two tabs and one
                // document -- and this is a set of files, not of tabs.
                if (!paths.contains(parsed)) paths.add(parsed);
            }
        }
        return paths;
    }

    /**
     * <b>Fills in documents that were opened before their language could answer.</b>
     *
     * <p>Services are attached once, when a document is created, and that is right — they hold a compile
     * result about <em>this</em> text and re-creating them would throw one away. It is also why an editor
     * already on screen when an engine band finished downloading stayed dark until it was closed and
     * reopened: {@code JavaLanguage} retries its resolve per document, so a document opened <em>after</em>
     * the band arrived was fine and one opened before it was not, which reads as the feature working for
     * some files and not others.</p>
     *
     * <p><b>Only the nulls.</b> Anything already attached is left alone — replacing a live services object
     * would discard a compile result about text that has not changed, and re-subscribe every listener that
     * hangs off it. Filling a gap is not the same operation as refreshing.</p>
     *
     * <p>On the UI thread, because {@code LanguageRegistry.onCapabilityChanged} is emitted there — see
     * that signal's own note for why an emit from a job would be a different and much worse thing.</p>
     */
    private void attachLateServices() {
        for (CgPath path : openPaths()) {
            TextEditor editor = editorFor(path);
            if (editor == null || editor.languageServices() != null) continue;
            LanguageRegistry.Entry entry = LanguageRegistry.forFileName(path.name());
            editor.setLanguageServices(entry.newServices(editor.buffer(), Resource.of(path)));
        }
    }

    /** The text editor for a path, or null when that file is not opened by a text editor. */
    @Nullable
    public TextEditor editorFor(CgPath path) {
        return documentFor(path) instanceof TextFileDocument text ? text.editor() : null;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────────────

    /**
     * Binds Problems to whichever editor is active, and asks the tree for its projects.
     *
     * <p>Both from a ticker, not from {@code onLayoutChanged}: rebinding replaces the panel's rows and
     * asking for projects eventually refreshes the tree, and neither is safe inside the layout pass.</p>
     */
    @Override
    protected void disconnected() {
        UIDocument leaving = document();
        if (leaving != null) leaving.removeDataProvider(this);
        super.disconnected();
        // CLEARED, or the panel never ticks again. `Animation` drops a hook on the first tick after
        // its owner disconnects, so a tool window -- which is hidden and reshown rather than rebuilt
        // -- would come back with the flag still set and no hook behind it.
        ticking = false;
    }

    private boolean ticking;

    @Nullable
    private DiagnosticSet boundTo;

    /**
     * Whether the tree follows the active tab — {@code explorer.autoReveal}, <b>default off</b>.
     *
     * <p>Which is IntelliJ's posture and not VS Code's; see {@code WorkbenchSettings.AUTO_REVEAL} for why
     * the default went that way. The field's own default matches the setting's so a workbench built
     * without a settings store behaves like one built with the shipped defaults — a default stated in two
     * places that disagree is worse than either.</p>
     */
    private boolean autoReveal = false;

    public Workbench setAutoReveal(boolean enabled) {
        this.autoReveal = enabled;
        return this;
    }

    @Nullable
    private CgPath revealed;

    /**
     * Selects the active file in the tree when the active tab changes.
     *
     * <p>On a CHANGE only. Revealing every frame would fight the user for the selection — they click a
     * folder, and a frame later the tree jumps back to whatever file is open.</p>
     */
    private void revealActiveFile() {
        if (!autoReveal) return;
        CgPath active = activeFilePath();
        if (active == null || active.equals(revealed)) return;
        revealed = active;
        fileTree.reveal(active);
    }

    /**
     * Performs a drag-and-drop from the tree — move by default, copy with the modifier.
     *
     * <p>Each item is issued independently, for the reason paste is: several files dropped into a folder
     * are several operations that can succeed or fail separately, and stopping on the first refusal leaves
     * the user guessing which ones landed.</p>
     */
    private void dropFiles(List<CgPath> sources, ProjectFileTree.DropRequest request) {
        // ONE UNDO STEP FOR THE WHOLE DROP -- see WorkspaceFileService.batch for why the group cannot
        // simply be opened and closed around this loop.
        WorkspaceFileService.Batch batch = fileService.batch(
                request.copy() ? "copy files" : "move files");
        for (CgPath source : sources) {
            // A folder dropped into itself or its own descendant would move a directory under itself,
            // which the filesystem refuses with a message about paths rather than about the gesture.
            if (source.equals(request.destination()) || source.contains(request.destination())) {
                Notifications.show(Notification.error("Cannot move").withDetail(source.name() + " into itself"));
                continue;
            }
            CgPath target = request.destination().resolve(source.name());
            if (target.equals(source)) continue;   // dropped back where it already is
            Runnable done = batch.track();
            // ONE completion hook per operation. This was `done` in the success lambda and `done.run()`
            // again in the failure one, plus a copy of the reporting -- and the batch has to be told either
            // way or its transaction never closes, silently.
            if (request.copy()) fileService.copyFile(source, target, done);
            else fileService.move(source, target, false, done);
        }
        batch.sealed();
    }

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
        fileTree.source().indexStep(WorkspaceTreeSource.DEFAULT_INDEX_BUDGET);
        // WHAT THE INDEX MAY SEE, re-taken on this thread because the crawl above just grew the list the
        // analysis thread reads. Before announceProjectSourcesMoved, so a buffer that moved this frame is
        // announced this frame rather than next. @see #refreshProjectIndexInputs
        refreshProjectIndexInputs();
        // A project file's text landed, so anything that resolved without it is stale. Drained here
        // because the read answers on the client's thread. @see #onProjectIndexFilled
        announceProjectSourcesMoved();
        // STAYS PER FRAME, and the attempt to move it to onWindowChanged is why this comment exists.
        //
        // It looks like a one-shot dressed as a loop -- ProjectFileTree.loadProjects latches on
        // `projectsRequested`, so this is free after the first call. It is really a RETRY: a client's
        // window id is not valid until its session has opened, and the server discards a packet addressed
        // to another window, so a call made too early is thrown away with no error at all
        // (WorkspaceTreeSource.loadProjects says exactly this). Attach happens before that, and because
        // the latch is set on the ATTEMPT rather than on success, one early call poisons it permanently:
        // twelve explorer tests came up with no project roots at all.
        //
        // Moving it needs a session-opened announcement, which is step 4's territory, not this one.
        fileTree.loadProjects();
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
     * {@code FileDocument.setActive}: the workbench knows which tab is in front and nothing else about it,
     * so what a document has to say is the document's to answer.</p>
     */
    /**
     * Every open document's problems, indexed by resource — this workspace's, and nobody else's.
     *
     * <p>An instance rather than a static for the reason on {@link Markers}: the index holds a listener on
     * every set in it, so a process-wide one can never let a document go.</p>
     */
    private final Markers markers = new Markers();

    /** @see #markers */
    public Markers markers() {
        return markers;
    }

    private final ConnectionGroup markerWatch = new ConnectionGroup();

    /**
     * ONE connection for the workbench, not one per document.
     *
     * <p>The tempting place to subscribe is beside the attach, inside the document factory — which would
     * add a listener per file opened, all of them doing the same whole-workspace sweep. The question
     * "which open documents are missing services" is about the workspace, so it is asked once.</p>
     *
     * <p>In a group so it is released with everything else this workbench holds: a static signal outliving
     * a disposed workbench is a leak that keeps a whole editor tree alive, and this one is on
     * {@code LanguageRegistry}, which lives for the process.</p>
     */
    private final ConnectionGroup capabilityWatch = new ConnectionGroup();

    @Nullable
    private StatusBarEntryAccessor problemCountEntry;

    /** Ahead of the shader graph's own readouts, which are about one document. */
    private static final int PROBLEM_COUNT_PRIORITY = 200;

    /** The workspace's error and warning totals, as one status entry. @see Markers */
    private void refreshProblemCount() {
        int errors = markers.count(DiagnosticSeverity.ERROR);
        int warnings = markers.count(DiagnosticSeverity.WARNING);
        // WITHDRAWN WHEN THERE IS NOTHING TO SAY, rather than reading "0 errors, 0 warnings". A clean
        // workspace is the normal state, and a permanent zero is a readout you learn to stop seeing.
        if (errors == 0 && warnings == 0) {
            if (problemCountEntry != null) problemCountEntry.dispose();
            problemCountEntry = null;
            return;
        }
        StatusBarEntry entry = new StatusBarEntry("Problems",
                errors + " " + (errors == 1 ? "error" : "errors")
                        + ", " + warnings + " " + (warnings == 1 ? "warning" : "warnings"),
                "Problems in the workspace", SHOW_PROBLEMS,
                errors > 0 ? StatusBarEntry.Kind.ERROR : StatusBarEntry.Kind.WARNING);
        if (problemCountEntry == null) {
            problemCountEntry = StatusBar.addEntry(entry, "workbench.problems",
                    StatusBarAlignment.LEFT, PROBLEM_COUNT_PRIORITY);
        } else {
            problemCountEntry.update(entry);
        }
    }

    /**
     * Phase 5.6 — says who else has the active file open.
     *
     * <p>The data has existed since Phase 4 and nothing showed it: {@code fs.watch} is sent for every
     * file a client reads, so the server has always known. What was missing was a view <em>across</em>
     * peers — a watcher belongs to one connection — and anywhere to put the answer.</p>
     *
     * <p><b>Removed rather than emptied when nobody is there.</b> A permanent "1 person" slot that
     * usually reads zero is a thing the eye learns to skip, which is the one failure a presence
     * indicator cannot afford. Same shape as the problem count above, and for the same reason.</p>
     */
    private void refreshPresence() {
        String others = othersEditing(activeFilePath());
        if (others == null) {
            if (presenceEntry != null) presenceEntry.dispose();
            presenceEntry = null;
            return;
        }
        StatusBarEntry entry = new StatusBarEntry("Editing",
                others, others + " also has this file open", null, StatusBarEntry.Kind.STANDARD);
        if (presenceEntry == null) {
            presenceEntry = StatusBar.addEntry(entry, "workbench.presence",
                    StatusBarAlignment.RIGHT, PRESENCE_PRIORITY);
        } else {
            presenceEntry.update(entry);
        }
    }

    /** Right of the problem count and left of anything a document contributes. */
    private static final int PRESENCE_PRIORITY = 50;

    @Nullable
    private StatusBarEntryAccessor presenceEntry;

    /**
     * Keeps the panel pointed at this workspace's index.
     *
     * <p>Bound <b>once</b>, not per tab. It used to re-point at the active document's set on every tab
     * change, which is what made it a second opinion about the file already on screen; the index is the
     * whole workspace, so switching tabs changes nothing about what it should show. Re-binding would also
     * rebuild the tree and throw away which files you had expanded.</p>
     */
    private void rebindProblems() {
        if (problems.source() == null || problems.source().markers() != markers) {
            problems.bindTo(markers);
        }
        // WHICH FILE IS IN FRONT, told on every tab change whether or not the filter is on -- so switching
        // "Show Active File Only" on narrows to what you are looking at now rather than to whatever
        // happened to be in front when you last switched it off.
        FileDocument active = activeDocument();
        problems.setActiveResource(active == null ? null : active.resource());
    }
}
