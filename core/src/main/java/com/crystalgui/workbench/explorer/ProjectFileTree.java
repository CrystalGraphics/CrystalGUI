package com.crystalgui.workbench.explorer;

import com.crystalgui.core.command.ActionIcons;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoScope;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.widget.display.SymbolIcon;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.core.collection.list.SelectionMode;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.widget.collection.list.RowEditing;
import com.crystalgui.widget.collection.tree.TreeClipboard;
import com.crystalgui.widget.collection.tree.TreeEditing;
import com.crystalgui.widget.collection.tree.TreeSearch;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.collection.tree.TreeViewCommands;
import com.crystalgui.widget.composite.ActionButton;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.view.FocusableView;
import com.crystalgui.workbench.view.TitleActionsContributor;
import com.crystalgui.workbench.decoration.FileDecorations;
import com.crystalgui.ui.input.FocusPolicy;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The project's files, as a tree — click a directory to expand, click a file to open it.
 *
 * <h3>One click selects, two act</h3>
 *
 * <p>This started as VS Code's rule — a single click opens, because a selection that opened nothing would
 * be a state with no purpose — and that stopped being true the moment the panel grew commands. A press is
 * now how you aim Delete, Rename, a drag, a Ctrl-toggle or a Shift-range, so it has to mean "this is the
 * row I am talking about" and nothing else. Folding on it made a folder impossible to select without also
 * opening it, and re-flattened the model in the middle of any range that crossed one.</p>
 *
 * <p>So: <b>one click selects, a double click acts</b> — opening a file, folding a directory. That is
 * IntelliJ's Project view, which is what this panel is modelled on. IntelliJ also folds on a single click
 * of the chevron; ours has no separate chevron hit target yet, since the {@code +}/{@code -} is part of the
 * label's text.</p>
 *
 * <h3>It reports; it does not open</h3>
 *
 * <p>{@link #onFileChosen} fires and that is all. What "open" means belongs to whatever owns the editors —
 * see {@link Workbench} — and a tree that held one could only ever serve a single host.</p>
 *
 * <h3>This is the VIEW. Four parts sit beside it.</h3>
 *
 * <p>VS Code's explorer is five files and the split is worth having, so it is ported <b>as a split</b> and
 * not only as behaviour — the same reason {@code com.crystalgui.text.cursor} took Monaco's module
 * boundaries rather than just its algorithms. This class had reached 1194 lines owning all five jobs at
 * once, which is the shape that makes every new feature land in the same place.</p>
 *
 * <table>
 *   <caption>The mapping</caption>
 *   <tr><th>Ours</th><th>VS Code</th><th>Owns</th></tr>
 *   <tr><td>{@code ProjectFileTree}</td><td>{@code views/explorerView.ts}</td>
 *       <td>The view: the tree widget, selection, reveal, the public surface</td></tr>
 *   <tr><td>{@link FilesRenderer}</td><td>{@code FilesRenderer} in {@code explorerViewer.ts}</td>
 *       <td>Building and filling a row, and every recycling rule</td></tr>
 *   <tr><td>{@link TreeEditing}, the engine's, over {@link ExplorerEditModel}</td>
 *       <td>{@code FileDragAndDrop}, same file, and the explorer's file commands</td>
 *       <td>Drag, cut, copy, paste, rename and delete over the selection; the model performs them on files</td></tr>
 *   <tr><td>{@link ExplorerFind}</td><td>{@code ExplorerFindProvider}, same file</td>
 *       <td>The find bar, its two modes, the per-row marking</td></tr>
 *   <tr><td>{@link RowEditing}, the engine's</td><td>{@code IExplorerService.setEditable} + {@code renderInputBox}</td>
 *       <td>The inline edit state machine and its row wiring, shared with every list that renames in a row</td></tr>
 *   <tr><td>{@link WorkspaceTreeSource}</td><td>{@code common/explorerModel.ts}</td>
 *       <td>The model: listings, sorting, compaction, what matches</td></tr>
 * </table>
 *
 * <p><b>Beside, not behind an interface.</b> The parts reach this class through package-private
 * accessors rather than through an {@code IExplorerService}, which is the decomposition {@code TextEditor}
 * already uses for its ten view parts and for the reason stated there: VS Code needs the indirection
 * because a renderer may not touch the view, and with one view implementation in one package that is a
 * layer to keep in step rather than a seam. What is worth porting is the decomposition.</p>
 *
 * <p>Two things deliberately did <b>not</b> move. The CSS class names stay here, because a stylesheet
 * targets {@code projectfiletree .__find-bar__} — they belong to the widget a selector names rather than
 * to whichever part happens to write them. And the public surface stays: {@code ExplorerCommands} asks
 * the <em>panel</em> to rename, not the panel's editing part.</p>
 */
public class ProjectFileTree extends UIElement
        implements UndoScope, DataProvider, TitleActionsContributor, FocusableView {
    /** The file panel. Named by the sheets. */
    public static final Name NAME = Name.of("projectfiletree");


    // THE BARE KEYS -- Delete, F2, Mod+X/C/V -- are TreeEditing's, bound on the tree's own keymap so they
    // are live only while focus is in it: declared on the commands they would fire while typing into any
    // editor sharing the window. The explorer's chords (Mod+N, F5, Mod+P, Alt+Shift+S) are the opposite
    // case and are declared on the commands.

    /** UNIQUE, never the shared "__content__". CanvasView uses that name for its transformed world
     * plane, so any descendant rule naming it also styles every graph plane below -- and a flex rule on
     * an absolutely positioned plane is what put layoutAbsoluteChildren in a hung thread dump. */
    public static final String CONTENT_CLASS = "__tree-content__";
    public static final String TREE_CLASS = "__project-tree__";
    public static final String ROW_CLASS = "__project-row__";
    /** The fold marker. Drawn by CSS from the row's own {@code __expanded__}/{@code __collapsed__}. */
    public static final String TWISTY_CLASS = "__twisty__";
    /** The file-type icon slot. {@code __pre-icon__} is the engine's established name for this. */
    public static final String ICON_CLASS = "__pre-icon__";
    /** The decoration letter at the row's trailing edge. */
    public static final String BADGE_CLASS = "__badge__";

    /** Class prefixes the row swaps per bind; see {@link #swapPrefixedClass}. */
    static final String FILETYPE_PREFIX = "filetype-";
    static final String DECORATION_PREFIX = "decoration-";

    /**
     * What a directory IS in the layout — module, source root, package or plain folder.
     *
     * <p>Separate from {@link #FILETYPE_PREFIX} because they answer different questions and only one is
     * about the file. A type is read off the NAME, which is VS Code's icon-theme model and is why
     * {@code FileIconTheme} knows nothing about paths; a role is read off the PATH and is a fact about the
     * project. IntelliJ's tree decides the icon the same way round, with the file-type registry as the
     * fallback rather than the authority.</p>
     *
     * <p>It is also the only part of the icon a test can SEE. A drawable is an SVG document, and asserting
     * on one means asserting on geometry — the shape of test that breaks on a redesign and proves nothing
     * in the meantime.</p>
     */
    /** @see SymbolIcon#NODEROLE_CLASS_PREFIX */
    static final String NODEROLE_PREFIX = SymbolIcon.NODEROLE_CLASS_PREFIX;

    /**
     * The decorations shown on rows. Empty until something registers a provider, which is why a tree with
     * no version control and no diagnostics costs nothing for the feature.
     */
    /**
     * <b>Handed over, not owned</b> — and that is what let the explorer become an extension.
     *
     * <p>The tree used to construct both this and its {@link WorkspaceTreeSource}, which made the
     * engine's own {@code decorations()} and {@code projects()} accessors reads THROUGH a widget: a
     * workbench with no explorer had no file decorations and no project listing either. The model
     * belongs to the workbench and the view to the feature, which is the same split
     * {@code WorkspaceProjects} was extracted for at W2.</p>
     */
    private final FileDecorations decorations;


    /**
     * The tree a command acts on — resolved from the data context, never through the workbench.
     *
     * <p>Which is what let the explorer become an extension. {@code ExplorerCommands} used to ask the
     * context for a {@code Workbench} and then call {@code fileTree()} on it, so the ENGINE had to hold
     * a field for a panel it may not have. Registered document-level, exactly as
     * {@code Workbench.WORKBENCH} is, so it answers with focus anywhere — a menu-bar Save or a palette
     * invocation resolves nothing through the focused element.</p>
     */
    public static final DataKey<ProjectFileTree> PROJECT_TREE =
            DataKey.create("projectTree", ProjectFileTree.class);

    public FileDecorations getDecorations() {
        return decorations;
    }

    /** A file the user asked to open. Never fires for a directory — those expand instead. */
    public final Signal.Value<CgPath> onFileChosen = new Signal.Value<>();

    private final WorkspaceTreeSource source;
    private final TreeView<CgPath> tree;

    /** Marked internal exactly ONCE, while empty -- see the constructor. */
    private final UIElement content = new UIElement();

    /**
     * Which item each pooled row currently shows.
     *
     * <p><b>Read per event, never captured.</b> Templates are pooled and rebound as the list scrolls, and
     * a listener may only be attached once — closing over the item would freeze a row on whatever it first
     * displayed, and keep working right up until somebody scrolled. The same rule the editor's fold arrows
     * carry.</p>
     */
    private final Map<UIElement, CgPath> rowItems = new HashMap<>();

    private boolean ticking;
    private boolean projectsRequested;

    /**
     * The workspace's history, so {@code UndoScope.nearest} finds it from anything inside the panel.
     *
     * <p>This is the whole of what makes Ctrl+Z work in the explorer without weakening the rule that an
     * {@code UndoStack} belongs to a document: file operations go on the <em>workspace's</em> stack, text
     * edits stay on their document's, and the resolver picks whichever scope encloses the focused element.
     * Set by {@link Workbench}, which owns the file service.</p>
     */
    @Nullable
    private UndoStack workspaceHistory;

    @Override
    public UndoStack undoStack() {
        if (workspaceHistory == null) workspaceHistory = new UndoStack();
        return workspaceHistory;
    }

    public ProjectFileTree setUndoStack(UndoStack stack) {
        this.workspaceHistory = stack;
        return this;
    }

    /** The workspace this tree is a view of — what a row asks when it wants a symbol. */
    private final Workspace workspace;

    public Workspace workspace() {
        return workspace;
    }

    /** @param source and {@code decorations} are the WORKBENCH's; see the field note. */
    public ProjectFileTree(Workspace workspace, WorkspaceTreeSource source, FileDecorations decorations) {
        super(NAME);
        this.workspace = workspace;
        this.source = source;
        this.decorations = decorations;
        // A provider changing state has to reach the rows, and nothing else would carry it: decorations
        // are read during bind(), so a tree that is already bound shows the state from whenever it last
        // was. Routed through pendingRefresh rather than calling tree.refresh() straight away, for the
        // reason activate() spells out -- a provider may well fire from inside a click handler on a row,
        // and a widget must never rebuild the elements it is being clicked on.
        decorations.onChanged.connect(() -> pendingRefresh = true);
    
        this.tree = new TreeView<>(source);
        tree.addClass(TREE_CLASS);
        tree.setRenderer(new FilesRenderer(this));
        // THE DOUBLE-CLICK IS THE LIST'S, not a listener on each row template: ListView raises activation
        // from Enter and from a double-click alike, so a file opens the same way by either.
        tree.onRowActivated.connect(index -> {
            TreeRow<CgPath> row = tree.rowAt(index);
            if (row != null) activate(row.item());
        });
        // MULTIPLE, which ListView already implements in full -- Ctrl to toggle, Shift for a range. This
        // is configuration rather than code, and it is what every file command that acts on "the
        // selection" rather than "the selected path" needs.
        tree.setSelectionMode(SelectionMode.MULTIPLE);
        // A TIGHTER ROW than ListView's 16px default. A file tree is the densest list in the
        // application -- it is read as a column of names, not browsed a row at a time -- and both
        // references set it tighter than their generic lists for exactly that reason. This is the
        // sanctioned place for the number: row height belongs to the size STRATEGY rather than to
        // CSS (a virtualised list positions rows from it), which is why ListView documents
        // setItemHeight as the way to say it and the sheet deliberately declares no height.
        tree.setItemHeight(14f);
        // A DEEP TREE IS THE CASE horizontal scrolling exists for: every level of nesting spends indent
        // the name then has to fit inside, so the panel width a name has to survive shrinks as you go
        // down. Truncating alone left a name unreadable with no way to reach the rest of it.
        tree.setHorizontalScrolling(true);
        // THE WRAPPER IS MARKED INTERNAL WHILE EMPTY; the tree is an ordinary child of it.
        //
        // append(tree) is the obvious line and it is wrong, because markAsInternal() RECURSES.
        // A TreeView is a ListView, which builds its own viewport and recycles rows through
        // addInternalChild/removeInternalChild -- and removeChild/clearAllChildren SILENTLY REFUSE an
        // internal child. Stamping the whole subtree makes those removals no-ops, the realised window
        // only ever grows, and layout takes longer every frame until the window stops responding.
        //
        // Same fix as QuickPick, ProblemsPanel and ShaderGraphView. Four widgets now; the wrapper is
        // the pattern, not a workaround.
        // FOCUSABLE, because this panel's keys are COMMANDS. Delete, F2 and Ctrl+Z all resolve outward
        // from the focused element -- a keymap and an UndoScope both walk that path -- so a panel that
        // cannot hold focus is a panel whose whole command set silently disables the moment focus is not
        // on one of its rows. That is the invariant GraphView already shipped the wrong side of, and it is
        // how Ctrl+Z stopped undoing a delete: the row it was invoked from no longer existed.
        //
        // CLICK rather than FOCUSABLE: the tree is reached by pointing at it, and rows carry their own
        // roving tab stop, so the panel does not want a second one in the Tab sequence.
        setFocusPolicy(FocusPolicy.CLICK);
        content.addClass(CONTENT_CLASS);
        append(content);
        content.append(tree);
        // DEFERRED REBUILD, like every other refresh here: an edit begins from a key press or a menu row,
        // and a rebuild now would replace the element that dispatch is still walking. The kit also takes
        // the list's Cut/Copy/Paste, which would otherwise be a row-text copier. @see ListView#setClipboardActions
        this.editing = new TreeEditing<>(tree, this, this::itemForRow, this::requestRefresh, CLIPBOARD);
        find.build();
    }

    public TreeView<CgPath> treeView() {
        return tree;
    }

    public WorkspaceTreeSource source() {
        return source;
    }

    /**
     * Asks for the project list once the session can carry the call.
     *
     * <p>Idempotent, and driven from the ticker rather than construction: a client's window id is invalid
     * until its session has opened, and the server silently discards a packet addressed to another window.
     * A call made too early is not an error — it simply never happens, and the tree stays empty with
     * nothing to explain it.</p>
     */
    public void loadProjects() {
        if (projectsRequested) return;
        projectsRequested = true;
        source.loadProjects(tree::refresh, () -> {
            // RELEASED, so the caller's per-frame retry is a retry rather than a name for one.
            //
            // The latch used to be set on the ATTEMPT and never cleared, and Workbench.tick's comment
            // said out loud what that costs -- "one early call poisons it permanently" -- while its own
            // ticker made exactly that early call, on the first frame the workbench attaches, guarded
            // only by whether there is a window. CgUiScreen asks properly, gated on isConnected(), and
            // by then the latch was already spent.
            //
            // Reported from a client: F6 the instant a world finished loading gave an empty Project
            // panel with New File and New Folder greyed -- because there was no project root to create
            // INTO, not because anything was refused. Waiting a few seconds before opening the editor
            // worked, which is why it survived this long.
            projectsRequested = false;
        });
    }

    /**
     * Starts the drain ticker once there is a window.
     *
     * <p>Registration only — the refresh happens in the tick. A listing arrives on some later frame, and
     * refreshing the view is a structural change, so it must not run inside the layout pass that this hook
     * is part of.</p>
     */
    @Override
    protected void disconnected() {
        super.disconnected();
        // CLEARED, or the panel never ticks again. `Animation` drops a hook on the first tick after
        // its owner disconnects, so a tool window -- which is hidden and reshown rather than rebuilt
        // -- would come back with the flag still set and no hook behind it.
        ticking = false;
    }

    /**
     * Re-binds the rows when the panel comes back into a window.
     *
     * <p>A tool window is <b>hidden and reshown, not rebuilt</b> — the container is cached per type, so
     * closing the explorer detaches this exact element and reopening re-parents it. The rows survive that;
     * what does not is their <em>binding</em>, and every listener on a pooled row reads its item out of
     * {@code rowItems} rather than capturing it, precisely so recycling cannot freeze a row on stale data.
     * An unbound row therefore answers null and every handler returns early: the tree looks completely
     * normal and does nothing at all, which is how this was reported — "close the explorer, reopen it, and
     * double-clicking a folder stops working".</p>
     *
     * <p>Through {@code pendingRefresh} rather than refreshing here, for the reason {@link #activate}
     * gives: a refresh re-flattens the model and recycles every realised row, which must not happen inside
     * an attach.</p>
     */
    @Override
    protected void connected() {
        super.connected();
        // ANNOUNCED TO THE SURFACE, so a command can resolve this tree with focus anywhere -- which
        // is what let the explorer become an extension: ExplorerCommands used to ask the context for a
        // Workbench and call fileTree() on it, so the ENGINE had to hold a field for a panel it may not
        // have. No matching removal: the engine drops a detached provider itself, and document()
        // answers null inside disconnected() anyway. @see #PROJECT_TREE
        UIDocument surface = document();
        if (surface != null) surface.addDataProvider(this);
        // THE PER-FRAME HOOK, and the guard is not the old one's: `registerTicker` was
        // HashSet-backed and idempotent, and `Animation.every` is a plain add, so a second attach
        // without this is a second hook. `disconnected()` clears it, or a panel that is hidden and
        // reshown -- which is every tool window -- comes back with the flag set and no hook behind it.
        if (!ticking && document() != null) {
            ticking = true;
            document().animation().every(this, this::drain);
        }
        if (document() != null) pendingRefresh = true;
    }

    private boolean drain(float deltaSeconds) {
        UIDocument window = document();
        if (window == null) {
            ticking = false;
            return false;
        }
        // FIRST, and only while attached, which is the whole point of doing it here — CrystalOS W11.
        //
        // A reconnect leaves every listing describing a server this client is no longer talking to. The
        // client repairs what the PROTOCOL needs (watches, capabilities) the moment the wire moves,
        // because a notification missed is missed for good; a listing is different, and re-fetching one
        // for a window nobody is looking at is exactly the invisible work a hidden window is supposed to
        // have stopped doing. Deferring costs nothing: this ticker returns false when the element leaves
        // the tree and onLayoutChanged registers it again when it comes back, so a restored window
        // re-lists on its first frame and a window still put away does not.
        if (staleListings) {
            staleListings = false;
            source.invalidateAll();
            tree.refresh();
        }
        if (pendingRefresh) {
            pendingRefresh = false;
            tree.refresh();
        }
        if (source.drainRefresh()) tree.refresh();
        // Re-driven every frame while a reveal is outstanding: each step needs one more listing, and the
        // listing arrives on some later frame.
        if (revealTarget != null) stepReveal();
        return true;
    }

    /**
     * What a command should act on — the selected row, or null when nothing is selected.
     *
     * <p>Derived from the tree's selection rather than remembered, for the reason the dock's active group
     * already records: a field updated on click answers correctly right up until something else changes
     * the selection, and then answers confidently and wrongly.</p>
     */
    @Nullable
    public CgPath selectedPath() {
        for (int index : tree.getSelectedIndices()) {
            TreeRow<CgPath> row = tree.rowAt(index);
            if (row != null) return row.item();
        }
        return null;
    }

    /**
     * Expands to a path, selects it and scrolls it into view — VS Code's {@code explorer.autoReveal}.
     *
     * <p><b>Asynchronous by nature, so it retries rather than failing.</b> Revealing
     * {@code proj:a/b/c.txt} needs {@code a} listed to know {@code b} exists, and {@code b} listed to find
     * {@code c.txt} — each a round trip. This expands as far as it currently can, asks for the next
     * listing, and is re-driven from the ticker until it arrives or the path turns out not to exist.</p>
     *
     * <p>Doing it in one pass and giving up is the tempting version, and it works exactly when the folders
     * happen to be open already — which is to say, when reveal was not needed.</p>
     */
    public void reveal(@Nullable CgPath path) {
        this.revealTarget = path;
        stepReveal();
    }

    @Nullable
    private CgPath revealTarget;

    private void stepReveal() {
        CgPath target = revealTarget;
        if (target == null) return;

        // Walk down from the project root, expanding and requesting as far as the listings allow.
        List<CgPath> chain = new ArrayList<>();
        for (CgPath at = target.parent(); at != null && !at.isProjectRoot(); at = at.parent()) {
            chain.add(0, at);
        }
        chain.add(0, CgPath.ofProject(target.project()));

        for (CgPath directory : chain) {
            if (!source.isListed(directory)) {
                source.ensureListed(directory);
                return;                       // wait for it; the ticker calls back
            }
            // MAPPED THROUGH THE CHAIN. With compact folders on, an intermediate directory is not a row at
            // all -- expanding `src/main` when the row is `src/main/java/com/crystalgui` sets a flag
            // nothing reads, so the reveal walks the whole way down and then finds nothing to select.
            CgPath row = source.visibleRowFor(directory);
            if (!tree.isExpanded(row)) tree.setExpanded(row, true);
        }
        tree.refresh();

        int index = indexOf(target);
        if (index < 0) {
            // Listed all the way down and still not there: the file does not exist, or an operation
            // removed it while the reveal was in flight. Stop rather than retrying forever.
            revealTarget = null;
            return;
        }
        tree.select(index);
        tree.scrollToIndex(index);
        revealTarget = null;
    }

    /** The visible row for a path, or -1. */
    private int indexOf(CgPath path) {
        List<TreeRow<CgPath>> rows = tree.visibleRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item().equals(path)) return i;
        }
        return -1;
    }

    /**
     * Narrows the tree to what matches, and reports what is being typed.
     *
     * <p>Kept on the widget rather than only on the source so a host can show it — a filter with nothing
     * saying it is on is a tree that has mysteriously lost half its files, which is IntelliJ's one real
     * weakness with speed search.</p>
     */
    public final Signal.Value<String> onFilterChanged = new Signal.Value<>();

    public ProjectFileTree setFilter(String query) {
        // THROUGH THE COMPONENT, which owns the query now: it narrows the source through this class's
        // Model, refreshes, and jumps to the first match. Setting the source directly would leave the bar
        // and the match position describing a query that is no longer the one in effect.
        find.setFilter(query);
        onFilterChanged.emit(source.filter());
        return this;
    }

    public String filter() {
        return source.filter();
    }

    /** Whether a path is a folder, as far as the listings this tree has seen are concerned. */
    public boolean isDirectory(CgPath path) {
        return source.isDirectory(path);
    }

    /**
     * Opens a right-click menu built per row.
     *
     * <p>The menu is the host's, not the tree's — a tree in a different application wants different verbs,
     * and the widget has no business naming commands it does not own.</p>
     */
    public ProjectFileTree setContextMenu(CommandRegistry registry, Supplier<ContextMenu> menu) {
        // THE CLICKED ROW IS THE SUBJECT: every command resolves its target through the selection, so a
        // right-click outside it has to become it -- and one inside a multi-selection has to keep it.
        editing.attachContextMenu(registry, menu);
        return this;
    }

    /**
     * Reports a file. A directory is folded by {@link TreeView} itself and never reaches here.
     *
     * <p>Both halves used to live here, and the directory one hand-rolled the deferral every tree needs:
     * a re-flatten from inside the press recycles the row under the pointer, and {@code recycle()} blurs
     * what it takes back — so folding a folder left it unselected while file rows selected perfectly, and
     * it read as folders and files being styled differently.</p>
     */
    void activate(CgPath path) {
        // A ROW BEING NAMED IS NOT A FILE YET. Enter commits the name and activates the selected row in
        // the same keystroke, so the placeholder went to the opener -- and its name is a control
        // character no filesystem permits, chosen so that a path escaping this far would be refused
        // rather than create something. It was, loudly, which is how this was found.
        if (WorkspaceTreeSource.isPlaceholder(path)) return;
        if (source.isDirectory(path)) return;
        onFileChosen.emit(path);
    }

    /** Set by a listing or a decoration change, drained by the ticker. */
    private boolean pendingRefresh;

    /**
     * Marks every listing as coming from a connection that has since been replaced.
     *
     * <p>Acted on in {@link #drain}, which only runs while this is in a window — see there for why a
     * hidden panel deliberately waits. Idempotent, and safe to call from anywhere: a reconnect is not a
     * frame event and arrives whenever the wire happens to move.</p>
     *
     * @see com.crystalgui.fs.client.Workspace#onDidReconnect
     */
    public void markListingsStale() {
        staleListings = true;
    }

    private boolean staleListings;

    /**
     * A recycled row's writable parts.
     *
     * <p>Held in a map rather than reached through {@code getChildren().get(n)}: four slots addressed by
     * index is one insertion away from silently writing the badge into the label, and the indices would
     * live at the call site where nothing explains them.</p>
     */
    /** Package-private, so the parts beside this class can write into a row without reaching for
     * children by index. @see RowEditing */
    public record RowParts(UIElement twisty, SymbolIcon icon, UIText label, UIText badge,
                           TextField editor) {
    }

    /**
     * Replaces whichever {@code prefix}-ed class this element currently carries with {@code next}.
     *
     * <p>A recycled row arrives wearing the previous file's classes. Adding without removing accumulates
     * every file type the slot has ever shown, and the cascade then resolves whichever rule happens to win
     * — which looks like a random colour rather than a stale class, because nothing is obviously wrong.</p>
     */
    /**
     * Delegates to {@link UIElement#swapPrefixedClass}, which is where this moved once the editor tab
     * strip needed the same thing — the trap it guards is not specific to recycled rows, and two copies
     * of it would be two chances to get the "swap, never add" half wrong.
     */
    static void swapPrefixedClass(UIElement element, String prefix, String next) {
        element.swapPrefixedClass(prefix, next);
    }

    // -- Find ---------------------------------------------------------------------------------------
    //
    // The bar, the modes, the arrows and the marking are TreeSearch's, on any tree -- ExplorerFind is
    // only the four answers a FILE tree has that a generic one cannot. These constants are kept as
    // aliases so existing selectors and callers still name the panel, but they ARE the component's: two
    // string literals for one class name is how a stylesheet and a widget drift apart.

    /** The bar along the top of the panel while a search is live. */
    public static final String FIND_BAR_CLASS = TreeSearch.BAR_CLASS;

    /** The search box itself. A real input — see {@link ExplorerFind}. */
    public static final String FIND_INPUT_CLASS = TreeSearch.INPUT_CLASS;

    /** The button that switches Filter and Highlight. */
    public static final String FIND_MODE_CLASS = TreeSearch.MODE_CLASS;

    /** The match readout. */
    public static final String FIND_COUNT_CLASS = TreeSearch.COUNT_CLASS;

    /**
     * The {@code ::highlight()} name the matched characters carry.
     *
     * <p>A highlight rather than a class, because what is being styled is a <b>range inside a string</b>
     * and not an element — which is the whole reason the CSS Custom Highlight API exists. Wrapping the
     * matched letters in spans would put a real Taffy node around three characters of every filename.</p>
     */
    public static final String FIND_HIGHLIGHT = TreeSearch.HIGHLIGHT;

    /** On a row whose own name matches, in Highlight mode. */
    public static final String MATCH_CLASS = TreeSearch.MATCH_CLASS;

    /** On a row that matches nothing and contains nothing that does. */
    public static final String DIMMED_CLASS = TreeSearch.DIMMED_CLASS;

    private final ExplorerFind find = new ExplorerFind(this);

    /** Filter ⇄ Highlight. @see com.crystalgui.ui.elements.tree.TreeSearch */
    public void toggleFindMode() {
        find.toggleFindMode();
    }

    /**
     * Whether a search removes non-matching rows.
     *
     * <p>On the PANEL rather than on {@code source()}, because the mode is the search component's state
     * now: setting it on the source alone is overwritten by the next keystroke, which re-derives it from
     * the component. That is the honest consequence of the two having one owner instead of two.</p>
     */
    public void setFindFiltering(boolean filtering) {
        find.setFiltering(filtering);
    }

    public boolean isFindFiltering() {
        return find.isFiltering();
    }

    /** Shows the search box and puts the caret in it — Ctrl+F. @see ExplorerFind */
    public void openFind() {
        find.openBar();
    }

    /** Hides it and clears the query. @see ExplorerFind */
    public void closeFind() {
        find.closeBar();
    }

    /** Whether the search box is showing. */
    public boolean isFindOpen() {
        return find.isOpen();
    }

    /** The match the search box's arrows are currently on, or null. @see ExplorerFind */
    @Nullable
    public CgPath currentMatch() {
        return find.currentMatchPath();
    }

    /** How many matches are on screen. */
    public int matchCount() {
        return find.matchCount();
    }

    /** Which of them is current, zero-based, or -1. */
    public int currentMatchIndex() {
        return find.currentMatchIndex();
    }

    /** The element the parts add their own chrome to. */
    UIElement contentBox() {
        return content;
    }

    // -- Editing --------------------------------------------------------------------------------------
    //
    // Drag, clipboard, rename and delete are the engine's TreeEditing, performed by ExplorerEditModel.
    // What stays here is what a FILE adds: the sibling rule, and the placeholder a new entry is named in.

    /** On the row's inline input, hidden unless that row is being edited. */
    public static final String EDITOR_CLASS = "__row-editor__";

    /** On the row while it is being edited, so a theme can quiet the rest of it. */
    public static final String EDITING_CLASS = RowEditing.EDITING_CLASS;

    private final TreeEditing<CgPath> editing;

    /** One for every Project panel, so a cut in one pastes in another. */
    private static final TreeClipboard<CgPath> CLIPBOARD = new TreeClipboard<>();

    /**
     * Gives the kit what performs its verbs on files. Until then drag, clipboard, rename and delete are
     * disabled.
     *
     * <pre>{@code
     * tree.editWith(workbench);   // ProjectExtension, at activation
     * }</pre>
     */
    public ProjectFileTree editWith(WorkbenchContext workbench) {
        editing.setModel(new ExplorerEditModel(workbench, this));
        return this;
    }

    /**
     * Adds a placeholder row under {@code parent} and edits it -- VS Code's {@code NewExplorerItem}: the row
     * exists before the file does, so you see where it will land before committing to a name.
     */
    public void beginNew(CgPath parent, boolean directory, Consumer<String> onCommit) {
        if (parent == null) return;
        RowEditing<CgPath> rows = editing.rows();
        // ENDED FIRST: an open edit's own ending withdraws the placeholder, which would take the new one.
        rows.cancel();
        // EXPANDED, or the placeholder is a child of a folded folder and nothing appears at all.
        if (!tree.isExpanded(parent)) tree.setExpanded(parent, true);
        CgPath placeholder = source.beginPendingNew(parent, directory);
        rows.begin(RowEditing.Edit.of(placeholder, "", onCommit)
                .accepting(ProjectFileTree::isWellFormedName)
                .conflicting(directory ? "folder" : "file", typed -> freeNameFor(placeholder, typed))
                .onEnd(source::endPendingNew));
    }

    public boolean isEditing() {
        return editing.rows().isEditing();
    }

    /** The row being edited, or null. */
    @Nullable
    public CgPath editingPath() {
        return editing.rows().item();
    }

    /** Drops the edit, and any placeholder with it. */
    public void cancelEdit() {
        editing.rows().cancel();
    }

    /** Not empty, not {@code .} or {@code ..}, and no path separator, which would create the entry in another directory. */
    public static boolean isWellFormedName(String name) {
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return false;
        return name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    /**
     * Null when no sibling of {@code path} is called {@code name}, else Windows' free name for it --
     * {@code plan.md} becomes {@code plan (2).md}.
     *
     * <p>Against what has been <b>listed</b>, which catches the case that matters: a name you can see on screen.</p>
     */
    @Nullable
    String freeNameFor(CgPath path, String name) {
        if (!siblingHas(path, name)) return null;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            String candidate = stem + " (" + n + ")" + extension;
            if (!siblingHas(path, candidate)) return candidate;
        }
    }

    private boolean siblingHas(CgPath path, String name) {
        CgPath parent = path.parent();
        if (parent == null) return false;
        for (CgPath sibling : source.listedChildren(parent)) {
            if (!sibling.equals(path) && sibling.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** What a part needs to reach back for: the item a realised row is showing. */
    @Nullable
    CgPath itemForRow(UIElement row) {
        return rowItems.get(row);
    }

    /**
     * Defers a refresh to the ticker — never immediate, see {@link #activate}.
     *
     * <p>Also how the workbench says a project READ has landed. A row's icon is what the file declares,
     * and that is read through {@code ProjectSources} — which answers null for a file nobody has read yet
     * and schedules the read, because this is asked while painting. So the first paint of a package draws
     * file-type icons and the real answers arrive afterwards, one at a time, with nothing in the tree
     * watching for them.</p>
     */
    public void requestRefresh() {
        pendingRefresh = true;
    }


    /** Every realised row and the item it is showing. Written by {@link FilesRenderer}, read by the
     * parts that have to answer "what is this row about" — the drag, the context menu, the editor. */
    Map<UIElement, CgPath> rowItems() {
        return rowItems;
    }

    /** Drag, clipboard, rename and delete over this tree's selection. */
    public TreeEditing<CgPath> editing() {
        return editing;
    }

    /** The tree, so activating the tool window puts its keys and its focused selection there. */
    @Override
    public UIElement focusTarget() {
        return tree;
    }

    @Nullable
    private List<ActionButton> titleActions;

    /** New ▸, Select Opened File, Expand Selected and Collapse All — IntelliJ's Project title line. */
    @Override
    public List<ActionButton> titleActions() {
        if (titleActions == null) {
            titleActions = List.of(
                    ActionButton.menu("New File or Directory…", MenuId.EXPLORER_NEW)
                            .icon(ActionIcons.ADD).context(tree),
                    ActionButton.command(ExplorerCommands.SELECT_OPENED_FILE)
                            .icon(ActionIcons.LOCATE).context(tree),
                    ActionButton.command(TreeViewCommands.EXPAND_SELECTED)
                            .icon(ActionIcons.EXPAND_ALL).context(tree)
                            .hint(TreeViewCommands.EXPAND_ALL, "Press {} to expand all nodes"),
                    ActionButton.command(TreeViewCommands.COLLAPSE_ALL)
                            .icon(ActionIcons.COLLAPSE_ALL).context(tree));
        }
        return titleActions;
    }

    ExplorerFind find() {
        return find;
    }

    /**
     * Answers the panel and its clipboard, and routes {@link UiDataKeys#UNDO_STACK} through the same walk
     * everything else uses.
     *
     * <p>Not {@link TreeEditing#KEY}: this is also a document-level provider, and a Delete invoked from the
     * palette in an editor must not reach the files selected here. The tree answers it from inside.</p>
     */
    @Override
    public Object getData(DataKey<?> key) {
        if (key == PROJECT_TREE) return this;
        if (key == UiDataKeys.CLIPBOARD) return editing.clipboardActions();
        return undoScopeData(key);
    }

}
