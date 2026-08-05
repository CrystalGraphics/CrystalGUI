package com.crystalgui.ui.elements.workbench;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.style.StyleGroup;
import dev.vfyjxf.taffy.style.TaffyPosition;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.tree.TreeRenderer;
import com.crystalgui.ui.elements.chrome.ContextMenu;
import com.crystalgui.ui.elements.tree.TreeRow;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.UIDragController;
import com.crystalgui.ui.elements.tree.TreeView;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

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
 */
public class ProjectFileTree extends UIElement implements com.crystalgui.core.undo.UndoScope {

    /** UNIQUE, never the shared "__content__". CanvasView uses that name for its transformed world
     * plane, so any descendant rule naming it also styles every graph plane below -- and a flex rule on
     * an absolutely positioned plane is what put layoutAbsoluteChildren in a hung thread dump. */
    public static final String CONTENT_CLASS = "__tree-content__";
    public static final String TREE_CLASS = "__project-tree__";
    public static final String ROW_CLASS = "__project-row__";

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
    private com.crystalgui.core.undo.UndoStack workspaceHistory;

    @Override
    public com.crystalgui.core.undo.UndoStack undoStack() {
        if (workspaceHistory == null) workspaceHistory = new com.crystalgui.core.undo.UndoStack();
        return workspaceHistory;
    }

    ProjectFileTree setUndoStack(com.crystalgui.core.undo.UndoStack stack) {
        this.workspaceHistory = stack;
        return this;
    }

    public ProjectFileTree(WorkspaceClient<?> client) {
        this.source = new WorkspaceTreeSource(client);
        this.tree = new TreeView<>(source);
        tree.addClass(TREE_CLASS);
        tree.setRenderer(new RowRenderer());
        // MULTIPLE, which ListView already implements in full -- Ctrl to toggle, Shift for a range. This
        // is configuration rather than code, and it is what every file command that acts on "the
        // selection" rather than "the selected path" needs.
        tree.setSelectionMode(com.crystalgui.ui.elements.list.SelectionMode.MULTIPLE);
        // THE WRAPPER IS MARKED INTERNAL WHILE EMPTY; the tree is an ordinary child of it.
        //
        // addInternalChild(tree) is the obvious line and it is wrong, because markAsInternal() RECURSES.
        // A TreeView is a ListView, which builds its own viewport and recycles rows through
        // addInternalChild/removeInternalChild -- and removeChild/clearAllChildren SILENTLY REFUSE an
        // internal child. Stamping the whole subtree makes those removals no-ops, the realised window
        // only ever grows, and layout takes longer every frame until the window stops responding.
        //
        // Same fix as QuickPick, ProblemsPanel and ShaderGraphEditor. Four widgets now; the wrapper is
        // the pattern, not a workaround.
        content.addClass(CONTENT_CLASS);
        addInternalChild(content);
        content.addChild(tree);
        buildDragGhost();
        installTypeToFilter();
        installDropTarget();
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
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
        source.loadProjects(tree::refresh);
    }

    /**
     * Starts the drain ticker once there is a window.
     *
     * <p>Registration only — the refresh happens in the tick. A listing arrives on some later frame, and
     * refreshing the view is a structural change, so it must not run inside the layout pass that this hook
     * is part of.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (ticking || getAttachedWindow() == null) return;
        ticking = true;
        getAttachedWindow().registerTicker(this::drain);
    }

    private boolean drain(float deltaSeconds) {
        UIWindow window = getAttachedWindow();
        if (window == null) {
            ticking = false;
            return false;
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
        List<CgPath> chain = new java.util.ArrayList<>();
        for (CgPath at = target.parent(); at != null && !at.isProjectRoot(); at = at.parent()) {
            chain.add(0, at);
        }
        chain.add(0, CgPath.ofProject(target.project()));

        for (CgPath directory : chain) {
            if (!source.isListed(directory)) {
                source.ensureListed(directory);
                return;                       // wait for it; the ticker calls back
            }
            if (!tree.isExpanded(directory)) tree.setExpanded(directory, true);
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
     * A drag finished over a folder: these paths, into that one, copying or moving.
     *
     * <p>Reported rather than performed, exactly as {@link #onFileChosen} is. The tree does not own the
     * file service — {@link Workbench} does — and a tree that reached for one could serve a single host.</p>
     */
    public final Signal.Pair<List<CgPath>, DropRequest> onFilesDropped = new Signal.Pair<>();

    /** Where a drop landed and what it meant. */
    public record DropRequest(CgPath destination, boolean copy) {
    }

    /** What is being dragged. A record so a foreign payload cannot be mistaken for ours. */
    private record DragPayload(List<CgPath> paths) {
    }

    /**
     * Makes a row draggable and the tree a drop target.
     *
     * <p><b>Dropping on a FILE targets its parent folder.</b> VS Code's rule, and the one that makes a
     * tree forgiving: rows are small, a folder's children are directly beneath it, and "into the folder
     * this thing is in" is almost always what was meant. Refusing the drop instead means aiming at a
     * 12-pixel row.</p>
     *
     * <p>Rejection is the default — a target accepts by calling {@code preventDefault()} on {@code Over},
     * re-read every frame and never latched. HTML5 drag-and-drop's one good idea, which this engine
     * already keeps.</p>
     */
    private void installRowDrag(UIElement row) {
        row.events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            // NEVER FROM THE KEYBOARD. Space and Enter on a focused element are delivered as a synthesized
            // MouseEvent.Down -- that is how Button and Checkbox get keyboard activation with no keyboard
            // code -- and the synthesized press carries the PHYSICAL CURSOR POSITION. A drag is the one
            // press that means "the pointer went down here" rather than "activate me", so confirming a New
            // File prompt with Enter started a drag anchored at wherever the mouse happened to be resting.
            //
            // And it could never end: pointer capture is released by a real button-up, which is not coming,
            // so the drag stayed armed. That is the second half of the same report -- with a live drag,
            // ListView's release handler correctly declines to collapse the selection, so every later click
            // added a row instead of replacing one, and the panel looked like it had lost multi-select
            // semantics entirely.
            //
            // KEYBOARD_DETAIL is the opt-out the input handler already provides for exactly this, and
            // GraphView is the widget that found it: Enter synthesized a press and started a marquee.
            if (event.getDetail() == com.crystalgui.ui.input.UIInputHandler.KEYBOARD_DETAIL) return;
            UIWindow window = getAttachedWindow();
            CgPath item = rowItems.get(row);
            if (window == null || item == null || item.isProjectRoot()) return;

            // The whole SELECTION when the pressed row is part of it, otherwise just this row -- the
            // same rule the graph uses for node drags, and for the same reason: "drag the five I
            // selected" is the common gesture, and a press that collapsed the selection breaks it.
            List<CgPath> dragged = selectedPaths().contains(item) ? selectedPaths() : List.of(item);

            // RE-REGISTERED PER DRAG, which is what the controller expects: it drops its reference when
            // the drag ends, so a ghost handed over once appears for the first drag and never again.
            // The label is rebuilt here too, because what is being dragged changes every time.
            ghostLabel.setText(dragged.size() == 1
                    ? dragged.get(0).name()
                    : dragged.size() + " items");
            window.getInputHandler().getDragController().setGhost(dragGhost,
                    UIDragController.GhostAnchor.CURSOR);

            window.getInputHandler().getDragController().startDrag(row,
                    event.getPosition().x(), event.getPosition().y(), new DragPayload(dragged),
                    new UIDragController.DragListener() {
                        @Override
                        public void onDragUpdate(float mx, float my, float sx, float sy,
                                                 float dx, float dy) {
                            // Nothing per frame: where the drop would land is decided by DragEvent.Over
                            // on the TREE, which is dispatched against what is geometrically under the
                            // pointer. This listener is pinned to the source by pointer capture and can
                            // never tell.
                        }
                    });
        }, false, false);
    }

    private void installDropTarget() {
        tree.events.getGroup(DragEvent.Over.class).attachListener((element, event) -> {
            if (!(event.getPayload() instanceof DragPayload)) return;
            // OUTLINED WHEREVER THE POINTER IS, not only where a drop would be accepted.
            //
            // IntelliJ marks the row under the cursor even when dropping there does nothing, and that is
            // the more useful signal: an outline that appears only over valid targets leaves you unable to
            // tell "this cannot take it" from "the drag is not tracking me at all". The refusal is carried
            // by the cursor, which is what a cursor is for.
            markDropTarget(rowElementFor(event.getTarget()));
            if (dropTargetFor(event.getTarget()) != null) {
                // ACCEPTING is preventDefault. Re-read every frame, so a drag that wanders over
                // something invalid stops being accepted without anything having to un-latch it.
                event.preventDefault();
            }
        }, false, true);

        // The pointer left the tree entirely -- over the editor, or off the window. Over stops firing, so
        // without this the last row keeps its outline for the rest of the drag.
        tree.events.getGroup(DragEvent.Leave.class).attachListener(
                (element, event) -> markDropTarget(null), false, true);

        tree.events.getGroup(DragEvent.Drop.class).attachListener((element, event) -> {
            markDropTarget(null);
            if (!(event.getPayload() instanceof DragPayload payload)) return;
            CgPath destination = dropTargetFor(event.getTarget());
            if (destination == null) return;
            // The modifier means COPY, matching every file manager. Read at DROP time rather than at
            // press time, because the decision is made while dragging -- you pick the folder first and
            // then hold the key.
            boolean copy = (CgPlatform.input().getCurrentModifiers() & CgModifiers.CTRL) != 0;
            onFilesDropped.emit(payload.paths(), new DropRequest(destination, copy));
        }, false, true);
    }

    /** On the row the pointer is over during a drag. */
    public static final String DROP_TARGET_CLASS = "__drop-target__";

    /** The row currently outlined, so the class can be taken off again without searching for it. */
    @Nullable
    private UIElement outlinedRow;

    /**
     * Moves the drop outline to {@code row}, or clears it for {@code null}.
     *
     * <p>Held as a reference rather than re-derived, because the row it has to come <em>off</em> may no
     * longer be under the pointer, may have scrolled out of the window, and — since rows are pooled — may
     * by then be showing a different file entirely. A pooled row that kept this class would wear an outline
     * around whatever it was next bound to.</p>
     *
     * <p>Cleared from {@code Drop} and from {@code Leave}, and those two are enough: a cancelled drag
     * leaves the pointer's boundary and so raises {@code Leave} on the way out. A third, defensive
     * clear driven off "is a drag still live" was written and removed again -- no path could reach
     * it, and an untestable backstop is a claim of safety nothing checks.</p>
     */
    private void markDropTarget(@Nullable UIElement row) {
        if (outlinedRow == row) return;
        if (outlinedRow != null) outlinedRow.removeClass(DROP_TARGET_CLASS);
        outlinedRow = row;
        if (row != null) row.addClass(DROP_TARGET_CLASS);
    }

    /** The folder a drop on {@code hit} lands in, or null if it is not over a row. */
    @Nullable
    private CgPath dropTargetFor(@Nullable UIElement hit) {
        UIElement row = rowElementFor(hit);
        if (row == null) return null;
        CgPath item = rowItems.get(row);
        if (item == null) return null;
        return source.isDirectory(item) ? item : item.parent();
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
        source.setFilter(query);
        tree.refresh();
        onFilterChanged.emit(source.filter());
        return this;
    }

    public String filter() {
        return source.filter();
    }

    /**
     * Type-to-filter, IntelliJ's speed search.
     *
     * <p>Bound here rather than as commands, and that is the exception rather than a lapse: this is not
     * <em>an</em> action, it is every printable character meaning "narrow to this". A command per letter is
     * not a thing, and a keymap that owned the alphabet would collide with every other binding in the
     * panel.</p>
     *
     * <p><b>Escape clears before it does anything else.</b> A filter you cannot see is a tree that has lost
     * files, so the way out has to be the key everyone already tries.</p>
     */
    private void installTypeToFilter() {
        tree.onKeyDown.attachListener((element, event) -> {
            if (event.getModifiers() != 0) return;     // Ctrl+C is a command, not a letter
            int key = event.getKeyCode();
            if (key == CgKeyCodes.KEY_ESCAPE) {
                if (filter().isEmpty()) return;
                setFilter("");
                event.stopPropagation();
                return;
            }
            if (key == CgKeyCodes.KEY_BACK) {
                if (filter().isEmpty()) return;
                setFilter(filter().substring(0, filter().length() - 1));
                event.stopPropagation();
                return;
            }
            char typed = event.getCharacter();
            // Printable only. A tree that filtered on Delete would eat the delete key, and the arrows have
            // to keep moving the selection.
            if (typed >= ' ' && typed != 127) {
                setFilter(filter() + typed);
                event.stopPropagation();
            }
        }, false, true);
    }

    /**
     * Everything selected, in the order it appears in the tree.
     *
     * <p>{@link #selectedPath()} is the first of these, and remains the right question for the commands
     * that act on exactly one thing — Rename cannot mean anything for four files at once.</p>
     */
    public List<CgPath> selectedPaths() {
        List<CgPath> found = new ArrayList<>();
        List<TreeRow<CgPath>> rows = tree.visibleRows();
        for (int index : tree.getSelectedIndices()) {
            if (index >= 0 && index < rows.size()) found.add(rows.get(index).item());
        }
        return found;
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
        ContextMenu.attach(tree, registry, element -> {
            // SELECT THE ROW FIRST. Every command resolves its target through selectedPath(), so a
            // right-click on an unselected row would otherwise act on whatever was selected before it --
            // which is the single most dangerous thing a file context menu can get wrong.
            int index = tree.indexOfRowElement(rowElementFor(element));
            if (index >= 0) tree.select(index);
            return menu.get();
        });
        return this;
    }

    /** On the floating copy that follows the cursor during a drag. */
    public static final String DRAG_GHOST_CLASS = "__drag-ghost__";

    private final UIElement dragGhost = new UIElement();
    private final UIText ghostLabel = new UIText("");

    /**
     * Builds the floating copy that follows the cursor, <b>once</b>, at construction.
     *
     * <p>It has to be IN THE TREE before a drag can show it: the controller promotes the ghost into the
     * top layer, and promotion needs a window to promote from, so an unparented element handed to
     * {@code setGhost} is silently never shown — no error, just no ghost. Same lesson the row menu learned
     * about popovers, and {@code PropertyPill} records it too.</p>
     *
     * <p>One element reused for every drag, with only its text rewritten. The controller hides it at rest
     * and on drag end, so nothing here has to manage its visibility.</p>
     */
    private void buildDragGhost() {
        dragGhost.addClass(DRAG_GHOST_CLASS);
        dragGhost.addChild(ghostLabel);
        // OUT OF FLOW AND HIDDEN FROM JAVA, not from the stylesheet, and this is not a style choice.
        //
        // UIWindow.init() calls calculateLayout() with no style pass before it, so the FIRST layout of any
        // tree runs before a single rule has matched. UIText latches once, on its first measurement,
        // whether it sizes its own width -- and in that unstyled first pass the ghost is an ordinary
        // in-flow child at the panel's full width, so the label concludes "my parent sizes me" and never
        // asks again. The ghost then becomes absolutely positioned with auto width, the label asks a parent
        // that is itself sizing to content, and the answer is zero: a box of pure padding with the glyphs
        // painting straight out of it. That is the stray blue rectangle beside the name, and no stylesheet
        // rule can prevent it because the damage is done before any stylesheet is consulted.
        //
        // IMPORTANT is the origin the controller itself writes display at, so showGhost/hideGhost still
        // take over cleanly at each end of a drag.
        StyleGroup.importantPipeline(dragGhost.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).display(TaffyDisplay.NONE));
        addInternalChild(dragGhost);
    }

    /** Walks up from whatever was hit to the row element the tree knows about. */
    @Nullable
    private UIElement rowElementFor(@Nullable UIElement hit) {
        for (UIElement element = hit; element != null; element = element.getParent()) {
            if (element.hasClass(ROW_CLASS)) return element;
        }
        return null;
    }

    /** Expands a directory, or reports a file. */
    private void activate(CgPath path) {
        if (source.isDirectory(path)) {
            tree.setExpanded(path, !tree.isExpanded(path));
            // DEFERRED to the next tick, never called here. This runs from the press that expanded the
            // folder, and refreshing re-flattens the model -- which recycles every realised row, including
            // the one under the pointer. recycle() BLURS what it takes back, so the focus that was about
            // to select the clicked row never landed: folding a folder left it unselected while the file
            // rows selected perfectly, which read as folders and files being styled differently.
            //
            // The engine's own rule, stated in DockArea.syncGroups and paid for by the table header: a
            // widget must never rebuild the elements it is being clicked on.
            pendingRefresh = true;
            return;
        }
        onFileChosen.emit(path);
    }

    /** Set by a fold, drained by the ticker — see {@link #activate}. */
    private boolean pendingRefresh;

    private final class RowRenderer implements TreeRenderer<CgPath> {

        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            row.addClass(ROW_CLASS);
            UIText label = new UIText("");
            // The label refuses the click so the press lands on the row. Click targeting takes the exact
            // element hit and never walks up to a handler-bearing ancestor, which is why every composite
            // in this engine does this.
            label.setHitTest(false);
            row.addChild(label);
            // A FOLDER TOGGLES ON ONE CLICK; A FILE OPENS ON TWO. Not one rule for both, and the
            // difference is not a compromise -- the two rows mean different things.
            //
            // Opening a file is destructive of attention: it takes a tab and the focus, which is exactly
            // what double-click protects against and why preview tabs exist in editors that do not have
            // it. Expanding a folder costs nothing and is undone by clicking again, so making it wait for
            // a second click just makes the tree feel broken -- which is precisely how it was reported,
            // after a first pass put the double-click gate in front of both.
            //
            // VS Code's explorer draws the line in the same place. IntelliJ wants the chevron for a single
            // click, which is only better once the chevron is its own hit target; ours is still part of
            // the label's text.
            installRowDrag(row);
            row.onMouseDown.attachListener((element, event) -> {
                CgPath item = rowItems.get(row);
                if (item == null) return;
                // DOUBLE CLICK FOR BOTH, folders included. A folder used to toggle on a single click,
                // which is VS Code's rule and reads well until the tree also has to support selecting --
                // there, one click has to mean "this is the row I am talking about", because a press is
                // how you aim Delete, Rename, a drag, or a Shift-range. Folding on that same press means
                // you cannot select a folder without also opening it, and every attempt to Shift-click a
                // range across one re-flattens the model mid-gesture.
                //
                // IntelliJ, whose Project view this panel is modelled on, resolves it exactly this way:
                // the chevron folds on one click, the ROW folds on two. Ours has no separate chevron hit
                // target yet -- the +/- is part of the label's text -- so the row's double click is the
                // whole affordance for now.
                if (event.getDetail() >= 2) activate(item);
            }, false, false);
            return row;
        }

        @Override
        public void bind(CgPath item, TreeRow<CgPath> row, int index, UIElement template) {
            rowItems.put(template, item);
            String name = item.isProjectRoot() ? source.displayNameOf(item) : item.name();
            ((UIText) template.getChildren().get(0)).setText("  ".repeat(row.depth())
                    + (row.expandable() ? (row.expanded() ? "- " : "+ ") : "   ") + name);
        }

        @Override
        public void unbind(UIElement template) {
            rowItems.remove(template);
        }
    }
}
