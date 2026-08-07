package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.FilePatternMap;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkingCopies;
import com.crystalgui.fs.WorkspaceFileService;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.chrome.InputDialog;
import com.crystalgui.ui.elements.chrome.ProblemsPanel;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockBranch;
import com.crystalgui.ui.elements.dock.DockNode;
import com.crystalgui.ui.elements.dock.DockOrientation;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.ui.elements.dock.DockInput;
import com.crystalgui.ui.elements.dock.DockOpenOptions;
import com.crystalgui.ui.elements.dock.DockPlacement;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPath;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import com.crystalgui.ui.elements.editor.EditorCommands;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.document.TextFileDocument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.command.CommandRegistry;
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
public class Workbench extends UIElement {

    /** A document panel — one instance per file, distinguished by its {@code path} state. */
    public static final String FILE_TYPE = "file";
    public static final String PROJECT_TYPE = "project";
    public static final String PROBLEMS_TYPE = "problems";

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

    /** Whatever a status line should say — an open, a save, or a refusal. */
    public final Signal.Value<String> onStatus = new Signal.Value<>();

    private final WorkspaceClient<?> client;
    private final DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
    private final ProjectFileTree fileTree;
    private final ProblemsPanel problems = new ProblemsPanel();
    private final DockArea dock;

    /**
     * Every open file, and what is known about each one.
     *
     * <p>This was four maps keyed by {@code CgPath} — the documents, their on-disk bytes, which had been
     * requested and which had refused to load — and every close and rename had to update all four. That is
     * one object pulled apart, and the drift it produces is the failure this codebase keeps paying for.</p>
     */
    private final OpenDocuments open = new OpenDocuments();

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
    private final UIElement content = new UIElement();

    /** The tool-window rail. Chrome, not a panel — see where it is added. */
    private final ActivityBar activityBar = new ActivityBar(this);

    public ActivityBar activityBar() {
        return activityBar;
    }

    /**
     * This workbench, for a command that acts on one.
     *
     * <p>What let {@code ExplorerCommands} stop capturing a workbench and become global — and with it,
     * the per-frame {@code installExplorerCommands(window)} call that used to sit in {@link #tick}
     * solely because registration needed a window to reach a registry.</p>
     */
    public static final DataKey<Workbench> WORKBENCH =
            DataKey.create("workbench", Workbench.class);

    @Override
    public Object getData(DataKey<?> key) {
        if (key == WORKBENCH) return this;
        return super.getData(key);
    }

    /**
     * Names this workbench at the <b>window</b> level as well as in the tree.
     *
     * <p>The element walk only finds ancestors, and a workbench is a descendant of the root — so with
     * nothing focused there is no workbench on the path at all. {@code Ctrl+P} and {@code F5} are exactly
     * the keys pressed before anything is focused, which is why they need this. See {@code DataContext}.</p>
     */
    @Override
    protected void onWindowChanged(@Nullable UIWindow previous, @Nullable UIWindow current) {
        if (previous != null) previous.removeDataProvider(this);
        if (current == null) return;
        current.addDataProvider(this);
        // The rail's buttons, once there is a window to take a registry from.
        //
        // THE WINDOW'S registry, deliberately not the global one. A `view.<type>` command closes over
        // THIS workbench, and a captured owner cannot be registered once for the application -- the
        // second workbench would silently reuse the first's command and toggle a panel in a window
        // nobody was looking at. That is the rule step 2.5 wrote down after the suite caught it, and
        // routing these through the global registry walks straight back into it.
        activityBar.listenToPanels(registry, current.getCommands());
    }

    /** The explorer's verbs come with the explorer. Global, so no window is needed. */
    @Override
    protected void registerCommands(CommandRegistry registry) {
        ExplorerCommands.register();
        // Undo comes with a workbench because the file tree IS the workspace's UndoScope -- deleting a
        // file is undoable and reaches the workspace stack. Same ids the editor and the graph use, so
        // there is one Undo in the palette rather than one per widget.
        UndoCommands.register();
    }

    public Workbench(WorkspaceClient<?> client) {
        if (client == null) throw new IllegalArgumentException("A Workbench needs a workspace client");
        this.client = client;
        this.fileService = new WorkspaceFileService(client, new Copies());
        this.fileTree = new ProjectFileTree(client);
        // At construction, not on the first frame with a window: the registry is global, so there is
        // nothing left to wait for.
        this.fileTree.setContextMenu(
                CommandRegistry.global(), ExplorerCommands::menu);
        // The explorer IS the workspace's undo scope. UndoScope.nearest walks outward from focus, so
        // Ctrl+Z in the tree reaches file operations and Ctrl+Z in an editor still reaches its own text.
        this.fileTree.setUndoStack(fileService.undoStack());
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
        });

        // How a tab presents itself. Both are PULLED by the strip when it builds a tab rather than pushed
        // in afterwards, which is what makes a rebuilt strip correct on the frame it is rebuilt -- a dock
        // rearrangement recreates every tab element, and anything pushed would have to be pushed again by
        // someone who noticed.
        registry.setTitleProvider(this::tabTitleFor);
        registry.setIconProvider(Workbench::tabIconFor);

        // Anchors match where defaultLayout() puts them, so closing a panel and reopening it from the
        // activity bar lands it back where it was rather than somewhere merely legal.
        registry.register(DockPanelDescriptor.singleton(PROJECT_TYPE, "Project")
                .icon("crystalgui:folder").anchor(DockDropZone.SPLIT_LEFT), ref -> fileTree);
        registry.register(DockPanelDescriptor.singleton(PROBLEMS_TYPE, "Problems")
                .icon("crystalgui:file-text").anchor(DockDropZone.SPLIT_DOWN), ref -> problems);
        registerDocumentType(FILE_TYPE, "File", path -> {
            TextEditor created = new TextEditor("");
            created.addClass(FILE_EDITOR_CLASS);
            LanguageRegistry.Entry entry = LanguageRegistry.forFileName(path.name());
            created.setLanguage(entry.language());
            // A FRESH tokenizer per document -- the interface exists for implementations holding a parse
            // tree per file, and sharing one would cross-contaminate them.
            created.setTokenizer(entry.newTokenizer());
            // No command installation here: TextEditor registers its own and binds its own chords, so a
            // document created before this workbench is attached is no longer a special case.
            // Here rather than only from WorkbenchSettings.apply: a document opened after the settings
            // were installed would otherwise get the widget's own defaults, so folding and tab size would
            // apply to the files that happened to be open when a preference was last changed and to no
            // others -- which reads as the setting working intermittently.
            WorkbenchSettings.applyTo(this, created);
            return new TextFileDocument(created, Resource.of(path));
        });

        dock = new DockArea(registry, defaultLayout());
        // ASKED BEFORE ANYTHING IS DISCARDED. Ctrl+W on an edited file used to throw the work away with no
        // warning at all -- the tab marker said it was modified and nothing acted on that.
        dock.setCloseGuard(this::confirmClose);
        // Two of this widget's per-frame polls, replaced by the announcement they were both watching for.
        // Not registered on a Disposable: the signal belongs to the dock, this workbench owns the dock, so
        // the subscription cannot outlive either -- an ownership registration here would be ceremony.
        dock.onDidChangeActivePanel.connect(panel -> {
            revealActiveFile();
            rebindProblems();
        });
        // The rail's :checked state follows the dock's structure and nothing else, so it can subscribe
        // now. Its BUTTONS wait for a window -- see onWindowChanged.
        activityBar.listenToLayout(dock);
        // A CLOSED TAB RELEASES ITS DOCUMENT. Until the dock could announce a close, nothing did: the
        // document stayed open, its editor stayed reachable and anything it owned -- a preview pool, a
        // renderer -- lived until the process did. Disposer could not help, because the thing that knew
        // the tab was gone had no way to say so.
        dock.onDidClosePanel.connect(this::releaseClosedPanel);
        // Tab dirty markers. Was a per-frame refreshDirtyMarkers(), which meant encoding every open
        // document -- a whole shader graph serialised sixty times a second -- to notice a marker that
        // moves when somebody types. The equality guard SURVIVES the move: the announcement means
        // "content changed", which is not the same as "dirtiness flipped", and only the encode can tell
        // the difference. It just runs once per edit now instead of once per frame.
        open.onDidChangeDirty.connect(path -> refreshDirtyMarkers());
        content.addClass(CONTENT_CLASS);
        addInternalChild(content);
        // The rail sits BESIDE the dock rather than inside it, which is what both originals do and is not
        // merely cosmetic: a stripe inside the dock would be a panel, and therefore droppable onto,
        // draggable and closable. It is chrome -- the thing that gets you back when everything else is
        // closed -- so it must not be something the layout can lose.
        content.addChild(activityBar);
        // Named so the stylesheet can give it the remaining width. DockArea carries no class of its own
        // and is not a registered tag, so there is otherwise nothing for a selector to hold onto.
        dock.addClass(DOCK_CLASS);
        content.addChild(dock);

        problems.onProblemChosen.connect(diagnostic -> {
            TextEditor editor = activeEditor();
            if (editor == null) return;
            editor.setCaret(editor.buffer().pointToOffset(diagnostic.start()));
            UIWindow window = getAttachedWindow();
            if (window != null) window.getInputHandler().requestFocus(editor);
        });
    }

    /**
     * Project down the left, documents in the middle, Problems beneath.
     *
     * <p>Authored rather than accumulated. A split halves the <em>target's</em> share and gives the other
     * half to the newcomer, so building this in the obvious order would hand half the screen to the file
     * tree — a default layout has to state what it wants.</p>
     */
    private DockLayout defaultLayout() {
        DockLeaf centre = new DockLeaf();
        centre.setCentral(true);
        DockLayout layout = DockLayout.of(centre);
        layout.drop(centre, DockDropZone.SPLIT_LEFT, new DockLeaf(new DockPanelRef(PROJECT_TYPE)));
        layout.drop(centre, DockDropZone.SPLIT_DOWN, new DockLeaf(new DockPanelRef(PROBLEMS_TYPE)));
        layout.root().child(0).size(0.20f);
        layout.root().child(1).size(0.80f);
        return layout;
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public DockArea dock() {
        return dock;
    }

    public DockPanelRegistry<UIElement> panels() {
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
                                   Function<DockPanelRef, UIElement> factory) {
        registry.register(descriptor, factory::apply);
        return this;
    }

    /** Whether a singleton panel type is currently anywhere in the layout. */
    public boolean isPanelOpen(String typeId) {
        return dock.layout().leafContaining(new DockPanelRef(typeId)) != null;
    }

    /**
     * Shows a singleton panel, or hides it if it is already showing — what an activity bar button does.
     *
     * <h3>Toggle, because that is what both editors do</h3>
     *
     * <p>Clicking the visible tool window's stripe button <b>hides</b> it, in IntelliJ and in VS Code
     * alike ({@code hideActivePaneComposite}). Open-only would leave the rail able to fill the screen
     * with panels and unable to clear one, which is how a bar of buttons stops being a bar of toggles and
     * starts needing a close affordance on every panel.</p>
     *
     * <p>Reopens against the type's {@linkplain DockPanelDescriptor#anchor anchor} rather than into the
     * central strip. {@link #openPanel} is right for a document — a thing you opened, which belongs where
     * you are working — and wrong for a tool window, which has a home. Dropping Project into the middle
     * would bury the file you were reading behind the file tree.</p>
     *
     * @return whether the panel is open <em>after</em> this call
     */
    public boolean togglePanel(String typeId) {
        return isPanelOpen(typeId) ? hidePanel(typeId) : showPanel(typeId);
    }

    /**
     * Hides a tool window, recording where it was so that showing it again is exact.
     *
     * <p>Everything is read <b>before</b> the close, because closing collapses the branch that held the
     * leaf: the weight goes with it, the path stops resolving, and the strip-mates are no longer
     * reachable. Capturing afterwards would record the tree the close produced rather than the one the
     * user arranged.</p>
     *
     * @return false, always -- the panel is closed after this
     */
    public boolean hidePanel(String typeId) {
        DockPanelRef ref = new DockPanelRef(typeId);
        DockLeaf showing = dock.layout().leafContaining(ref);
        if (showing == null) return false;

        List<DockPanelRef> neighbours = new ArrayList<>(showing.panels());
        neighbours.remove(ref);
        DockPath parent = showing.parent() == null ? null : dock.layout().pathOf(showing.parent());
        int index = showing.parent() == null ? -1 : showing.parent().indexOf(showing);

        ToolWindowState state = placementOf(typeId)
                .withVisible(false)
                .withWeight(showing.size())
                .withGroupedWith(neighbours)
                .withActive(ref.equals(showing.activePanel()))
                .withPlacement(parent, index);
        DockDropZone edge = outerEdgeOf(showing);
        if (edge != null) state = state.withAnchor(edge);
        state = withRelativePosition(state, showing);
        toolWindows.put(state);

        dock.layout().closePanel(ref);
        // requestRebuild, NOT syncGroups. Both showing and hiding change the SHAPE of the tree -- a close
        // removes a leaf and normalise() may collapse the branch, a show inserts one -- and syncGroups only
        // reconciles tabs inside groups that already exist. The asymmetry is what made the button look like
        // it "only closes": closing emptied the group so the pane visibly went away, while opening added a
        // leaf no SplitView had been built for and nothing appeared.
        dock.requestRebuild();
        return false;
    }

    /**
     * Shows a tool window at the most specific remembered position that still exists.
     *
     * <h3>Three tiers, most specific first</h3>
     *
     * <ol>
     *   <li><b>A strip-mate</b> -- rejoin the tab strip it shared. First because it is the only tier that
     *       names a <em>leaf</em>; the rest name a position between leaves, so letting one of them win for
     *       a panel that was a tab reopens it beside its own strip rather than in it.</li>
     *   <li><b>The structural path</b> -- the exact branch and index its leaf occupied. Precise, and it
     *       only survives while that branch does.</li>
     *   <li><b>A surviving neighbour</b> -- replay the drop that put it beside that panel. This is what
     *       carries the common case: hiding a panel that was alone in its pane collapses the branch tier 2
     *       names, and the neighbour is still on screen.</li>
     *   <li><b>The anchor</b> -- which wall. The answer for a panel that has never been open, and the
     *       backstop when everything else has moved.</li>
     * </ol>
     *
     * <p>The order is load-bearing in both directions. An anchor always succeeds, so checking it early
     * means the specific tiers are never consulted and every nested tool window drifts to a wall. And the
     * positional tiers always succeed for a panel that was a <em>tab</em>, so checking those first splits
     * a strip that the user had deliberately grouped.</p>
     *
     * @return true, always -- the panel is open after this
     */
    public boolean showPanel(String typeId) {
        DockPanelRef ref = new DockPanelRef(typeId);
        if (dock.layout().leafContaining(ref) != null) return true;
        ToolWindowState state = placementOf(typeId);

        DockLeaf placed = null;
        // 1. THE STRIP IT WAS A TAB IN. First because it is the only tier that names a LEAF; every other
        //    one names a position between leaves, so honouring one of those for a panel that was a tab
        //    reopens it beside its own strip instead of in it.
        for (DockPanelRef mate : state.groupedWith()) {
            DockLeaf strip = dock.layout().leafContaining(mate);
            if (strip == null) continue;
            open(DockInput.of(ref), DockPlacement.leaf(strip), DockOpenOptions.INACTIVE);
            placed = dock.layout().leafContaining(ref);
            break;
        }
        // 2. THE EXACT BRANCH AND INDEX, when that branch is still there.
        if (placed == null && state.path() != null) {
            DockLeaf candidate = new DockLeaf(ref);
            candidate.size(state.weight());
            if (dock.layout().insertAt(state.path(), state.indexInParent(), candidate)) {
                placed = candidate;
            }
        }
        // 3. BESIDE A SURVIVING NEIGHBOUR, replaying the drop that produced the arrangement. This is what
        //    carries the common case, where hiding collapsed the branch tier 2 named.
        if (placed == null && state.relativeTo() != null) {
            DockLeaf beside = dock.layout().leafContaining(state.relativeTo());
            if (beside != null) {
                DockLeaf candidate = new DockLeaf(ref);
                dock.layout().drop(beside, state.relativeZone(), candidate);
                candidate.size(state.weight());
                placed = candidate;
            }
        }
        // 4. A WALL.
        if (placed == null) {
            DockLeaf opened = new DockLeaf(ref);
            dock.layout().dropOnOuterEdge(state.anchor(), opened);
            // AFTER the drop, never before: dropOnOuterEdge assigns size(1f) itself, so a weight set on
            // the way in is overwritten -- and a weight of 1 against siblings summing to 1 is what made a
            // reopened Project take half the window.
            opened.size(state.weight());
            placed = opened;
        }

        // BRING IT TO THE FRONT wherever it landed. openPanelWith deliberately restores the previous
        // selection -- right for its original caller, which opens the inspector beside the source without
        // stealing the source's tab, and wrong here: this panel is open because someone pressed its button,
        // and one that joins a strip behind another tab has, from the user's side, not opened at all.
        if (placed != null) placed.activate(ref);
        toolWindows.put(state.withVisible(true));
        dock.requestRebuild();
        if (placed != null) dock.setActiveGroup(dock.groupFor(placed));
        return true;
    }

    /**
     * Every tool window's placement, open or closed -- the model {@link WorkbenchSession} persists.
     *
     * <p>This is the whole of what replaced three ad-hoc maps of remembered fragments. See
     * {@link ToolWindowLayout} for why both editors keep placement <em>beside</em> the layout rather than
     * deriving it from one.</p>
     */
    public ToolWindowLayout toolWindows() {
        return toolWindows;
    }

    private final ToolWindowLayout toolWindows = new ToolWindowLayout();

    /**
     * Records the panel's position <b>relative to a neighbour</b> — the tier that survives a collapse.
     *
     * <p>{@link ToolWindowState#path()} names a branch, and hiding a panel usually destroys that branch:
     * a leaf alone with one sibling leaves the sibling behind, and {@code normalise()} correctly dissolves
     * the now-pointless branch. So the most common arrangement of all — a tool window in a pane of its own
     * — is precisely the one whose path stops resolving the moment it is hidden. A neighbouring
     * <em>panel</em> is still on screen and still findable, so "to the right of that one" keeps working.</p>
     *
     * <p>The neighbour is taken from the adjacent child of the same branch, and the zone from which side
     * it is on and which axis the branch divides — so reopening replays the very drop that produced the
     * arrangement.</p>
     */
    private ToolWindowState withRelativePosition(ToolWindowState state, DockLeaf showing) {
        DockBranch parent = showing.parent();
        if (parent == null || parent.childCount() < 2) return state;
        int mine = parent.indexOf(showing);
        int besideIndex = mine > 0 ? mine - 1 : mine + 1;
        if (besideIndex < 0 || besideIndex >= parent.childCount()) return state;

        List<DockLeaf> leaves = parent.child(besideIndex).leaves();
        if (leaves.isEmpty() || leaves.get(0).panels().isEmpty()) return state;
        DockPanelRef neighbour = leaves.get(0).panel(0);

        boolean after = mine > besideIndex;
        boolean horizontal =
                parent.orientation(dock.layout().rootOrientation()) == DockOrientation.HORIZONTAL;
        DockDropZone zone = horizontal
                ? (after ? DockDropZone.SPLIT_RIGHT : DockDropZone.SPLIT_LEFT)
                : (after ? DockDropZone.SPLIT_DOWN : DockDropZone.SPLIT_UP);
        return state.withRelativeTo(neighbour, zone);
    }

    /** This type's placement, seeded from its descriptor the first time it is asked for. */
    private ToolWindowState placementOf(String typeId) {
        DockPanelDescriptor descriptor = registry.descriptor(typeId);
        return toolWindows.getOrCreate(typeId,
                descriptor != null ? descriptor.anchor() : DockDropZone.SPLIT_LEFT);
    }

    /**
     * Which outer edge a leaf sits against, or null when it is not against one.
     *
     * <p>Read off the <b>top-level</b> ancestor: whichever child of the root the leaf descends from, and
     * whether that child is first or last. The root's orientation says which axis that is, so the answer
     * round-trips exactly through {@link DockLayout#dropOnOuterEdge}, which inverts the same rule.</p>
     *
     * <p><b>Null for anything nested</b>, and that is the honest answer rather than a gap -- a panel
     * between two others is not on an edge, and naming the nearest one would move it on reopen. Since the
     * structural path handles exactly that case, this is now the backstop it should always have been
     * rather than the whole answer it was briefly asked to be.</p>
     */
    @Nullable
    private DockDropZone outerEdgeOf(DockLeaf leaf) {
        DockNode node = leaf;
        while (node.parent() != null && node.parent() != dock.layout().root()) node = node.parent();
        DockBranch root = dock.layout().root();
        if (node.parent() != root) return null;
        int index = root.children().indexOf(node);
        if (index != 0 && index != root.childCount() - 1) return null;
        boolean after = index != 0;
        boolean horizontal = root.orientation(dock.layout().rootOrientation()) == DockOrientation.HORIZONTAL;
        if (horizontal) return after ? DockDropZone.SPLIT_RIGHT : DockDropZone.SPLIT_LEFT;
        return after ? DockDropZone.SPLIT_DOWN : DockDropZone.SPLIT_UP;
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
        if (target == null) target = centralLeaf();

        if (!splitting) {
            // The selection is captured and PUT BACK when the caller asked not to activate. DockLeaf.add
            // activates what it inserts, which is right for a file and wrong for a companion panel.
            DockPanelRef wasActive = target.activePanel();
            target.add(ref);
            if (!options.activates() && wasActive != null) target.activate(wasActive);
            dock.syncGroups();
            if (options.activates()) dock.setActiveGroup(dock.groupFor(target));
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
        DockPanelRef ref = refFor(path);
        for (DockLeaf leaf : dock.layout().leaves()) {
            if (leaf.indexOf(ref) < 0) continue;
            leaf.activate(ref);
            // syncGroups, not requestRebuild: only the selection changed, and this usually runs inside the
            // click that asked for it -- a widget must never rebuild the elements it is being clicked on.
            dock.syncGroups();
            dock.setActiveGroup(dock.groupFor(leaf));
            onStatus.emit("focused " + path.name());
            return;
        }
        client.read(path, read -> {
            adoptInto(path, read.content());
            open.requestRead(path);
            open(DockInput.of(ref));
            onStatus.emit("opened " + path.name());
        }, failure -> onStatus.emit("open failed: " + failure.code()));
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
    public TextEditor activeEditor() {
        return activeDocument() instanceof TextFileDocument text ? text.editor() : null;
    }

    /** Writes the active tab back. A stale write is reported distinctly — it has a recovery path. */
    public boolean saveActiveFile() {
        CgPath target = activeFilePath();
        if (target == null) {
            onStatus.emit("no file tab active");
            return false;
        }
        FileDocument document = open.get(target);
        if (document == null) return false;
        if (!open.isSaveable(target)) {
            onStatus.emit("refusing to save " + target.name() + " -- it never loaded");
            return false;
        }
        byte[] written = document.encode();
        client.save(target, written,
                etag -> {
                    // THE BYTES THAT WERE WRITTEN become the new baseline, not the document's current
                    // state: a document edited again while the write was in flight is still modified
                    // afterwards, and recording what it looks like NOW would call it clean.
                    open.markSaved(target, written);
                    refreshTabTitles();
                    onStatus.emit("saved " + target.name());
                },
                failure -> onStatus.emit(failure.isConflict()
                        ? "CONFLICT: " + target.name() + " changed on disk — reopen to take theirs"
                        : "save failed: " + failure.code()));
        return true;
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
        for (DockLeaf leaf : dock.layout().leaves()) {
            if (leaf.isCentral()) return leaf;
        }
        return dock.layout().leaves().get(0);
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
                        failure -> onStatus.emit("open failed: " + failure.code()));
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
                onStatus.emit("saved " + path.name());
            }, failure -> onStatus.emit("save failed: " + path.name() + " -- " + failure.code()));
        }
        if (issued == 0) onStatus.emit("nothing to save");
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
        String path = panel.state(PATH_STATE, "");
        if (path.isEmpty()) return null;
        String title = panel.state(DockPanelRef.TITLE, CgPath.parse(path).name());
        return isDirty(CgPath.parse(path)) ? title + DIRTY_MARKER : title;
    }

    /**
     * Which icon a tab shows — the same one the file's row in the tree shows, from the same theme.
     *
     * <p>Static, because it depends on nothing but the panel: the icon is a function of the file name,
     * which is already in the ref. That is the whole reason it can be pulled at build time and never
     * refreshed, unlike the dirty marker beside it.</p>
     */
    @Nullable
    private static String tabIconFor(DockPanelRef panel) {
        String path = panel.state(PATH_STATE, "");
        if (path.isEmpty()) return null;
        return FileIconTheme.getDefault().iconFor(CgPath.parse(path).name(), false, false);
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
        open.close(path);
        onDidCloseDocument.emit(path);
    }

    /**
     * A document was released, because the last tab showing it closed.
     *
     * <p>The counterpart of {@link #onDidOpenDocument}, and the half that did not exist — which is why
     * nothing could clean up after a close.</p>
     */
    public final Signal.Value<CgPath> onDidCloseDocument = new Signal.Value<>();

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
        if (refused != null) onStatus.emit("cannot open " + path.name() + ": " + refused);
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
     */
    public java.util.List<CgPath> openPaths() {
        return open.paths();
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
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (ticking || getAttachedWindow() == null) return;
        ticking = true;
        getAttachedWindow().registerTicker(this::tick);
    }

    private boolean ticking;

    @Nullable
    private TextEditor boundTo;

    /**
     * Whether the tree follows the active tab — VS Code's {@code explorer.autoReveal}, default on.
     *
     * <p>Off is a real preference rather than a hypothetical: revealing scrolls the tree, and somebody
     * navigating the tree while switching tabs loses their place every time.</p>
     */
    private boolean autoReveal = true;

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
                onStatus.emit("cannot move " + source.name() + " into itself");
                continue;
            }
            CgPath target = request.destination().resolve(source.name());
            if (target.equals(source)) continue;   // dropped back where it already is
            Runnable done = batch.track();
            if (request.copy()) {
                fileService.copyFile(source, target,
                        () -> {
                            onStatus.emit("copied " + source.name());
                            done.run();
                        },
                        failure -> {
                            onStatus.emit("copy failed: " + source.name() + " -- " + failure.code());
                            done.run();
                        });
            } else {
                fileService.move(source, target, false,
                        () -> {
                            onStatus.emit("moved " + source.name());
                            done.run();
                        },
                        failure -> {
                            onStatus.emit("move failed: " + source.name() + " -- " + failure.code());
                            done.run();
                        });
            }
        }
        batch.sealed();
    }

    // installExplorerCommands(window) used to live here and be called EVERY FRAME from tick(), behind a
    // commandsInstalled flag, for one reason: registration needed a window to reach a registry. Commands
    // are global and the explorer's resolve their workbench from the data context, so registration moved
    // to registerCommands (once per class), the tree binds its own bare keys in bindKeys (once per
    // instance), and the context menu is wired at construction. Nothing is left to do per frame.

    private boolean tick(float deltaSeconds) {
        if (getAttachedWindow() == null) {
            ticking = false;
            return false;
        }
        // A few directories a frame, until the workspace is walked. Go to File searches what this has
        // reached, so warming it in the background is what makes the first Ctrl+P useful rather than
        // empty -- and it warms the tree's own listing cache, so there is no second index to keep in step.
        // NOT LATCHED. "Nothing to ask for right now" and "nothing left to walk" are the same answer from
        // outside, and a latch on that turned the crawl off the first time every known directory happened
        // to be in flight -- and left it off when a folder appeared later. The step is O(budget) against a
        // queue, so asking every frame costs nothing once the queue is empty.
        fileTree.source().indexStep(WorkspaceTreeSource.DEFAULT_INDEX_BUDGET);
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
    private void rebindProblems() {
        TextEditor active = activeEditor();
        if (active == boundTo) return;
        boundTo = active;
        problems.bindTo(active == null ? null : active.diagnostics());
    }
}
