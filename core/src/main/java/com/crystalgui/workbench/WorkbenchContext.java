package com.crystalgui.workbench;

import com.crystalgui.core.settings.SettingsScope;
import com.crystalgui.workbench.explorer.WorkspaceTreeSource;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.workbench.dock.panel.DockOpenOptions;
import com.crystalgui.workbench.dock.panel.DockInput;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.drag.DockPlacement;
import java.nio.file.Path;
import com.crystalgui.core.notify.StatusBar;
import java.util.List;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentKinds;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.client.WorkspaceDocuments;
import com.crystalgui.fs.client.WorkspaceProjects;
import com.crystalgui.text.diagnostic.Markers;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.workbench.decoration.FileDecorations;
import com.crystalgui.workbench.editor.EditorService;
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import com.crystalgui.workbench.extension.SessionSlice;
import com.crystalgui.workbench.toolwindow.ToolWindowKind;
import com.crystalgui.workbench.toolwindow.ToolWindowManager;

/**
 * <b>What an extension is written against</b> - the workbench's surface, without the workbench's class.
 *
 * <p>Everything outside {@code com.crystalgui.workbench} names this: an application, the language
 * stack's Run shell, a mod's own panel. It is what {@link WorkbenchExtension#activate} is handed, and it
 * is the whole API a feature needs.</p>
 *
 * <pre>{@code
 * public Disposable activate(WorkbenchContext workbench) {
 *     Disposable panel = workbench.registerToolWindow(ToolWindowKind.of("mymod:panel", "My Panel")
 *             .view(ctx -> myView).openByDefault());
 *     Disposable kind  = workbench.kinds().register(MY_FILE_TYPE);
 *     workbench.onDidOpenDocument().connect(path -> ...);
 *     return () -> { kind.dispose(); panel.dispose(); };
 * }
 * }</pre>
 *
 * <p>The main groups: the workspace and its {@link #projectListing()}; documents, tabs and
 * {@link #openFile}; the {@link #dock()} and {@link #registerToolWindow}; the {@link #statusBar()};
 * {@link #kinds()} for file types; {@link #registerSessionSlice} for anything to remember between runs;
 * and settings resolution, since it extends {@code SettingsScope}.</p>
 *
 * <h3>An interface, so an extension cannot reach past it</h3>
 *
 * <p>{@code Workbench} implements this and {@code LayeringTest} forbids naming the class from outside
 * the package - an engine that can be named can be reached into. The surface was measured from what the
 * outside actually calls rather than designed: nothing is here because an extension <em>might</em> want
 * it. What is absent is as informative as what is present - no style engine, no dock-tree surgery, no
 * way to ask which host this is.</p>
 */
public interface WorkbenchContext extends SettingsScope {

    // ── The workspace under it ──────────────────────────────────────────────────────────────────

    /** The workspace this workbench is a view of. */
    Workspace workspace();

    /** The projects and their listings. @see WorkspaceProjects */
    WorkspaceProjects projects();

    /**
     * The same listing, typed concretely — <b>what a tree VIEW needs and the model contract does not
     * carry</b>.
     *
     * <p>{@code filter}, {@code visibleRowFor} and {@code drainRefresh} are view-model methods: a
     * filtered tree, a path-to-row mapping and a coalesced redraw. {@link #projects()} is the honest
     * contract for everything else and is what eight consumers use.</p>
     *
     * <p><b>This is a named leak, not a clean seam.</b> The honest fix is to separate the listing cache
     * from the tree adapter, so the engine owns the first and the explorer owns the second; until then
     * one object is both, and a panel that draws it needs the whole object. W3b's territory.</p>
     */
    WorkspaceTreeSource projectListing();

    /** The open documents, one per resource. */
    WorkspaceDocuments documents();

    /** Every kind of document this workbench can open. @see #contribute */
    DocumentKinds kinds();

    /** The tabs over those documents — the one open lane. */
    EditorService editors();

    /** The status bar of this workbench — per workbench since W5, never a static. */
    StatusBar statusBar();

    /** Problems, by owner. */
    Markers markers();

    /** What the explorer draws beside a file — colour, badge, tooltip. */
    FileDecorations decorations();

    // ── The chrome ──────────────────────────────────────────────────────────────────────────────

    /** The dock: what is on screen and where. */
    DockArea dock();

    /** typeId → what it is and how to build one. */
    DockPanelRegistry<UIElement> panels();

    /** The tool windows and their regions. */
    ToolWindowManager toolWindowManager();

    /** The window this workbench is in, or null before it is attached to one. */
    @Nullable
    UIDocument document();

    // ── What is active ──────────────────────────────────────────────────────────────────────────

    /** The file in front, or null when what is in front is not a file. */
    @Nullable
    CgPath activeFilePath();

    /**
     * What is in front, as a resource — <b>a panel need not be about a file</b>.
     *
     * <p>{@link #activeFilePath()} answers null for a shader graph's generated source, a diff or a
     * networked panel; this answers for all of them, which is what a feature filtering "the active
     * thing" wants. Null means nothing has been said.</p>
     */
    @Nullable
    Resource activeResource();

    /** The text editor in front, or null when what is in front is not one. */
    @Nullable
    TextEditor activeEditor();

    /** The text editor showing {@code resource}, if one is open. */
    @Nullable
    TextEditor editorFor(@Nullable Resource resource);

    /** The text editor showing {@code path}, if one is open. */
    @Nullable
    TextEditor editorFor(CgPath path);

    /** The document open for {@code path}, if any. */
    @Nullable
    Document documentFor(CgPath path);

    /** Every file open in a tab. */
    List<CgPath> openPaths();

    // ── Opening and saving ──────────────────────────────────────────────────────────────────────

    void openFile(CgPath path);

    void openFile(CgPath path, @Nullable Runnable onOpened);

    void openResource(Resource resource);

    void openResource(Resource resource, @Nullable Runnable onOpened);

    /** @return whether there was something to save */
    boolean saveActiveFile();

    /** Who else is EDITING {@code target}, phrased for a human, or null when nobody is. */
    @Nullable
    String othersEditing(@Nullable CgPath target);

    /** Who else merely has it open. @see #othersEditing */
    @Nullable
    String othersViewing(@Nullable CgPath target);

    /**
     * A document arrived in a tab.
     *
     * <p>Not the same signal as the dock's active-panel change, and an extension that follows what is
     * in front needs both: the active PANEL is announced as soon as the dock has built its tree, which
     * can be before the document behind it exists — a restored tab's content arrives over the network
     * some frames later. Following only the panel leaves such an extension looking at nothing until
     * something else moves, which is exactly what "I have to click something first" is.</p>
     */
    Signal.Value<CgPath> onDidOpenDocument();

    /**
     * Opens anything the dock can hold, wherever the caller says.
     *
     * <p>The general form the four {@code open*} methods above are conveniences over — a panel that is
     * not a document, or a placement that is not "wherever the active group is", needs it. The shader
     * graph's generated source is both: a derived resource, opened <em>beside</em> the graph it came
     * from rather than as a tab in the same group, because the whole point of it is watching it change
     * as you wire.</p>
     */
    DockLeaf open(DockInput input, DockPlacement placement, DockOpenOptions options);

    // ── Contributing ────────────────────────────────────────────────────────────────────────────

    /** Adds a file type. @see DocumentKind */
    WorkbenchContext contribute(DocumentKind kind);

    /** Adds a file type, plus the extensions that open into it. */
    WorkbenchContext contribute(DocumentKind kind, String... extensions);

    /**
     * Adds a tool window from one declaration. @see ToolWindowKind
     *
     * <p>Here rather than on {@code toolWindowManager()} — which is where the plan put it — because a
     * kind derives a command and an accelerator as well as a panel, and the manager can register
     * neither: it holds the regions and the panel registry, not the workbench's keymap.</p>
     *
     * @return a handle that withdraws what can be withdrawn — the command, its key and the badge
     *         subscription. The panel type itself stays, because the registry it is in dies with this
     *         workbench and a half-removed panel type is worse than a kept one
     */
    Disposable registerToolWindow(ToolWindowKind kind);

    /**
     * Claims a corner of the session record for this extension. @see SessionSlice
     *
     * <p>What a FEATURE remembers between runs, as opposed to what the engine does. The engine
     * serialises the dock layout, the tool-window placements and the per-tab view state; it has no way
     * to reach inside an extension, and reaching for one is exactly the arrangement being removed —
     * {@code WorkbenchSession} named {@code fileTree().treeView().expandedItems()} outright.</p>
     *
     * @return a handle that withdraws the slice, so a deactivated extension stops being written
     */
    Disposable registerSessionSlice(SessionSlice slice);

    /**
     * A private directory for derived output, or null on a host with nowhere to put one.
     *
     * <p>Compiled scripts, an index, a thumbnail cache — things that are rebuildable and must never
     * become part of a project somebody ships, which is why it is beside the config store rather than
     * inside the workspace. Null is an ordinary answer: an extension that caches nowhere still works,
     * and a test is exactly that host.</p>
     */
    @Nullable
    Path cacheDirectory(String name);

    /** Adds a panel type and how to build one. */
    WorkbenchContext registerPanel(DockPanelDescriptor descriptor,
                                   Function<DockPanelRef, UIElement> factory);

    /** @return whether the panel was opened */
    boolean showPanel(String typeId);

    /** @return whether the panel is on screen */
    boolean isPanelOpen(String typeId);

    /** Brings a panel forward, opening it if it is closed. @return whether it is now showing */
    boolean revealPanel(String typeId);
}
