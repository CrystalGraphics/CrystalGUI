package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkingCopies;
import com.crystalgui.fs.WorkspaceFileService;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.chrome.ProblemsPanel;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import com.crystalgui.ui.elements.editor.EditorCommands;
import com.crystalgui.ui.elements.editor.TextEditor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

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

    /** The state key carrying which file a {@link #FILE_TYPE} panel shows. */
    public static final String PATH_STATE = "path";

    /** UNIQUE, never the shared "__content__" -- see ProjectFileTree.CONTENT_CLASS. */
    public static final String CONTENT_CLASS = "__workbench-content__";

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

    /** One editor per open file. See the class note on why this is cached. */
    private final Map<CgPath, TextEditor> editors = new HashMap<>();

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

    public Workbench(WorkspaceClient<?> client) {
        if (client == null) throw new IllegalArgumentException("A Workbench needs a workspace client");
        this.client = client;
        this.fileService = new WorkspaceFileService(client, new Copies());
        this.fileTree = new ProjectFileTree(client);
        // The explorer IS the workspace's undo scope. UndoScope.nearest walks outward from focus, so
        // Ctrl+Z in the tree reaches file operations and Ctrl+Z in an editor still reaches its own text.
        this.fileTree.setUndoStack(fileService.undoStack());
        fileTree.onFileChosen.connect(this::openFile);
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

        registry.register(DockPanelDescriptor.singleton(PROJECT_TYPE, "Project"), ref -> fileTree);
        registry.register(DockPanelDescriptor.singleton(PROBLEMS_TYPE, "Problems"), ref -> problems);
        registry.register(DockPanelDescriptor.document(FILE_TYPE, "File"), this::fileEditorPanel);

        dock = new DockArea(registry, defaultLayout());
        content.addClass(CONTENT_CLASS);
        addInternalChild(content);
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

    /** Drops a panel into the central work area and selects it. */
    public Workbench openPanel(DockPanelRef ref) {
        DockLeaf target = centralLeaf();
        if (target.indexOf(ref) < 0) target.add(ref);
        target.activate(ref);
        dock.syncGroups();
        dock.setActiveGroup(dock.groupFor(target));
        return this;
    }

    /**
     * Opens a panel as a tab in whichever leaf already holds {@code sibling}.
     *
     * <p>The third placement, and the one neither of the others can express: {@link #openPanel} always
     * targets the central leaf, {@link #openPanelBeside} always makes a new pane. This puts two panels in
     * one strip — right when they are alternatives rather than companions, so the pane's space is spent on
     * whichever you are reading.</p>
     *
     * <p>Falls back to the central leaf when {@code sibling} is nowhere to be found, which is what happens
     * after a layout restore that dropped it: a tab in the work area is a worse place than beside its
     * sibling and a far better one than not being opened at all.</p>
     */
    public Workbench openPanelWith(DockPanelRef sibling, DockPanelRef ref) {
        DockLeaf target = dock.layout().leafContaining(sibling);
        if (target == null) return openPanel(ref);
        if (target.indexOf(ref) >= 0) {
            target.activate(ref);
            dock.syncGroups();
            return this;
        }

        // The selection is captured and PUT BACK, because DockLeaf.add activates what it inserts -- right
        // for "open this file", wrong here. A panel that steals its sibling's tab on open is one that
        // opens by hiding the thing you were looking at, and the source pane exists to be looked at.
        DockPanelRef wasActive = target.activePanel();
        target.add(ref);
        if (wasActive != null) target.activate(wasActive);
        dock.syncGroups();
        return this;
    }

    /**
     * Opens a panel in a pane of its <em>own</em>, beside the central work area.
     *
     * <p>{@link #openPanel} merges into the central strip, where a second panel <b>hides</b> the first —
     * right for a second document, wrong for anything meant to be read <em>alongside</em> one. This splits
     * instead, so both are on screen at once.</p>
     *
     * <p>Idempotent through the layout rather than through a flag: a panel already somewhere in the tree is
     * activated where it is, so a host may call this freely and a pane the user has since dragged elsewhere
     * stays where they put it.</p>
     *
     * @param share how much of the central area's slice the new pane takes, 0..1. Applied after the drop,
     *              which halves the target — a split pane is a reading companion far more often than an
     *              equal, and a caller that wanted half can say so.
     */
    public Workbench openPanelBeside(DockPanelRef ref, DockDropZone zone, float share) {
        DockLeaf existing = dock.layout().leafContaining(ref);
        if (existing != null) {
            existing.activate(ref);
            dock.syncGroups();
            dock.setActiveGroup(dock.groupFor(existing));
            return this;
        }

        DockLeaf central = centralLeaf();
        float whole = central.size();
        DockLeaf placed = dock.layout().drop(central, zone, new DockLeaf(ref));
        // Ratios within a branch are all that matter, so this is correct whether drop inserted a sibling
        // (the two weights still sum to the target's old share, leaving every other child untouched) or
        // wrapped the target in a new branch (where the pair are the branch's only children).
        central.size(whole * (1f - share));
        placed.size(whole * share);

        // requestRebuild, not syncGroups: the TREE changed, not just a selection. Safe here because
        // nothing is being clicked -- this runs from a host's setup, never from inside an event on a tab.
        //
        // The new pane is deliberately NOT made active. It has no group yet -- the rebuild is deferred to
        // the next frame -- so asking for one now yields null, and setting THAT sends rebuild() down its
        // "nothing is active" path, which picks leaves.get(0): the file tree. A companion pane should not
        // steal the work area's focus anyway.
        dock.requestRebuild();
        return this;
    }

    // ── Files ───────────────────────────────────────────────────────────────────────────────────

    /** The panel reference identifying one open file — {@code path} is what makes two of them distinct. */
    public static DockPanelRef refFor(CgPath path) {
        return new DockPanelRef(FILE_TYPE)
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
        client.read(path, document -> {
            editorFor(path).setText(document.text());
            openPanel(ref);
            onStatus.emit("opened " + path.name());
        }, failure -> onStatus.emit("open failed: " + failure.code()));
    }

    /** The file behind the active tab, or null when the active tab is not a file. */
    @Nullable
    public CgPath activeFilePath() {
        DockGroup group = dock.activeGroup();
        if (group == null) return null;
        DockPanelRef panel = group.leaf().activePanel();
        if (panel == null || !FILE_TYPE.equals(panel.typeId())) return null;
        return CgPath.parse(panel.state(PATH_STATE, ""));
    }

    @Nullable
    public TextEditor activeEditor() {
        CgPath path = activeFilePath();
        return path == null ? null : editors.get(path);
    }

    /** Writes the active tab back. A stale write is reported distinctly — it has a recovery path. */
    public boolean saveActiveFile() {
        CgPath target = activeFilePath();
        if (target == null) {
            onStatus.emit("no file tab active");
            return false;
        }
        TextEditor editor = editors.get(target);
        if (editor == null) return false;
        client.save(target, editor.getText().getBytes(StandardCharsets.UTF_8),
                etag -> onStatus.emit("saved " + target.name()),
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
            for (CgPath open : editors.keySet()) {
                // contains() covers "the path itself" as well as "beneath it", so a file delete and a
                // directory delete are one question. Deleting a folder with six files open in it is
                // exactly the case a per-path lookup misses.
                if (open.equals(path) || path.contains(open)) found.add(open);
            }
            return found;
        }

        @Override
        public void close(CgPath path) {
            editors.remove(path);
            // The TAB goes too, and this is the half that is easy to forget: an editor dropped from the
            // map with its tab left behind leaves the dock asking the registry to rebuild a panel for a
            // file that no longer exists, which comes back as the "__missing__" placeholder.
            dock.layout().closePanel(refFor(path));
            dock.requestRebuild();
        }

        @Override
        public void retarget(CgPath from, CgPath to) {
            TextEditor editor = editors.remove(from);
            if (editor == null) return;
            editors.put(to, editor);
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

    /** The editor for a path, created on first use and given the language its name implies. */
    public TextEditor editorFor(CgPath path) {
        return editors.computeIfAbsent(path, key -> {
            TextEditor created = new TextEditor("");
            created.addClass(FILE_EDITOR_CLASS);
            LanguageRegistry.Entry entry = LanguageRegistry.forFileName(key.name());
            created.setLanguage(entry.language());
            // A FRESH tokenizer per document -- the interface exists for implementations holding a parse
            // tree per file, and sharing one would cross-contaminate them.
            created.setTokenizer(entry.newTokenizer());
            UIWindow window = getAttachedWindow();
            if (window != null) EditorCommands.install(window, created);
            return created;
        });
    }

    /** Built by the dock when it needs a file panel — including after a layout restore, where the read
     * has not happened yet and the content arrives late into an editor that already exists. */
    private UIElement fileEditorPanel(DockPanelRef ref) {
        CgPath path = CgPath.parse(ref.state(PATH_STATE, ""));
        TextEditor target = editorFor(path);
        if (target.getText().isEmpty()) {
            client.read(path, document -> target.setText(document.text()),
                    failure -> onStatus.emit("open failed: " + failure.code()));
        }
        return target;
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

    private boolean commandsInstalled;

    /**
     * Installs the explorer's commands and its right-click menu, once there is a window.
     *
     * <p>Self-installed for the reason {@code GraphView} records: a widget's own verbs belong to the
     * widget, and a requirement every host has to remember is one that gets forgotten — which it was,
     * twice, for {@code GraphCommands} and again for undo. Bound on the <b>file tree</b>, so bare
     * {@code Delete} and {@code F2} exist only while focus is in the panel rather than firing while
     * typing into any editor sharing the window.</p>
     */
    private void installExplorerCommands(UIWindow window) {
        if (commandsInstalled) return;
        commandsInstalled = true;
        ExplorerCommands.install(window, this);
        // edit.undo/edit.redo bound on the TREE, against the workspace stack the tree scopes. Same ids the
        // editor uses -- one Undo in the palette, not one per widget.
        com.crystalgui.core.undo.UndoCommands.install(window.getCommands(), fileTree);
        fileTree.setContextMenu(window.getCommands(), ExplorerCommands::menu);
    }

    private boolean tick(float deltaSeconds) {
        if (getAttachedWindow() == null) {
            ticking = false;
            return false;
        }
        installExplorerCommands(getAttachedWindow());
        revealActiveFile();
        fileTree.loadProjects();
        // Follows the active tab. Only on a CHANGE -- rebinding every frame would rebuild the table's
        // rows sixty times a second for a set that has not moved.
        TextEditor active = activeEditor();
        if (active != boundTo) {
            boundTo = active;
            problems.bindTo(active == null ? null : active.diagnostics());
        }
        return true;
    }
}
