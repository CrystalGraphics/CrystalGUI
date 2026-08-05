package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;
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
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
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

    public Workbench(WorkspaceClient<?> client) {
        if (client == null) throw new IllegalArgumentException("A Workbench needs a workspace client");
        this.client = client;
        this.fileService = new WorkspaceFileService(client, new Copies());
        this.fileTree = new ProjectFileTree(client);
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

        registry.register(DockPanelDescriptor.singleton(PROJECT_TYPE, "Project"), ref -> fileTree);
        registry.register(DockPanelDescriptor.singleton(PROBLEMS_TYPE, "Problems"), ref -> problems);
        registerDocumentType(FILE_TYPE, "File", path -> {
            TextEditor created = new TextEditor("");
            created.addClass(FILE_EDITOR_CLASS);
            LanguageRegistry.Entry entry = LanguageRegistry.forFileName(path.name());
            created.setLanguage(entry.language());
            // A FRESH tokenizer per document -- the interface exists for implementations holding a parse
            // tree per file, and sharing one would cross-contaminate them.
            created.setTokenizer(entry.newTokenizer());
            UIWindow window = getAttachedWindow();
            if (window != null) EditorCommands.install(window, created);
            return new TextFileDocument(created);
        });

        dock = new DockArea(registry, defaultLayout());
        // ASKED BEFORE ANYTHING IS DISCARDED. Ctrl+W on an edited file used to throw the work away with no
        // warning at all -- the tab marker said it was modified and nothing acted on that.
        dock.setCloseGuard(this::confirmClose);
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

    /** What each tab's label should say right now — the file name, plus a marker when it is modified. */
    private String tabTitleFor(DockPanelRef panel) {
        String title = registry.titleOf(panel);
        String path = panel.state(PATH_STATE, "");
        if (path.isEmpty()) return title;
        return isDirty(CgPath.parse(path)) ? title + DIRTY_MARKER : title;
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
            for (DockPanelRef panel : group.panels()) {
                com.crystalgui.ui.elements.Tab tab = group.tabFor(panel);
                // setText suppresses an equal write, so the common case -- nothing changed -- costs one
                // string comparison per visible tab and touches no element.
                if (tab != null) tab.setText(tabTitleFor(panel));
            }
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
    }

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
        refreshDirtyMarkers();
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
