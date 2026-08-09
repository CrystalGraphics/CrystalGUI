package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.Command;
import com.crystalgui.ui.ClipboardActions;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UiDataKeys;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.DragGhost;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.workbench.decoration.FileDecoration;
import com.crystalgui.ui.elements.workbench.decoration.FileDecorations;
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
import com.crystalgui.core.undo.UndoScope;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.ui.elements.list.SelectionMode;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIInputHandler;

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
public class ProjectFileTree extends UIElement implements UndoScope {

    /**
     * The explorer's bare keys, on the tree — which is the whole reason they are scoped here.
     *
     * <p>{@code Delete} and {@code F2} must be live only while focus is inside the panel. Declaring them
     * on the commands would make them application-wide, and a bare key at that scope fires while typing
     * into any editor sharing the window. The explorer's <em>chords</em> ({@code Mod+N}, {@code F5},
     * {@code Mod+P}, {@code Alt+Shift+S}) are the opposite case and are declared on the commands.</p>
     *
     * <p>On the tree rather than on {@code Workbench}: the dock — and therefore every open editor — is
     * inside the workbench too, so binding there would recreate exactly the problem this avoids.</p>
     */
    @Override
    protected void bindKeys() {
        ExplorerCommands.bindDefaults(keymap());
    }

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
    private static final String FILETYPE_PREFIX = "filetype-";
    private static final String DECORATION_PREFIX = "decoration-";

    /**
     * The decorations shown on rows. Empty until something registers a provider, which is why a tree with
     * no version control and no diagnostics costs nothing for the feature.
     */
    private final FileDecorations decorations = new FileDecorations();

    {
        // A provider changing state has to reach the rows, and nothing else would carry it: decorations
        // are read during bind(), so a tree that is already bound shows the state from whenever it last
        // was. Routed through pendingRefresh rather than calling tree.refresh() straight away, for the
        // reason activate() spells out -- a provider may well fire from inside a click handler on a row,
        // and a widget must never rebuild the elements it is being clicked on.
        decorations.onChanged.connect(() -> pendingRefresh = true);
    }

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

    ProjectFileTree setUndoStack(UndoStack stack) {
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
        tree.setSelectionMode(SelectionMode.MULTIPLE);
        // A DEEP TREE IS THE CASE horizontal scrolling exists for: every level of nesting spends indent
        // the name then has to fit inside, so the panel width a name has to survive shrinks as you go
        // down. Truncating alone left a name unreadable with no way to reach the rest of it.
        tree.setHorizontalScrolling(true);
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
        addInternalChild(content);
        content.addChild(tree);
        dragGhost.parkIn(this);
        installTypeToFilter();
        buildFindBar();
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
    protected void onWindowChanged(@Nullable UIWindow previous, @Nullable UIWindow current) {
        super.onWindowChanged(previous, current);
        if (current != null) pendingRefresh = true;
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
            if (event.getDetail() == UIInputHandler.KEYBOARD_DETAIL) return;
            UIWindow window = getAttachedWindow();
            CgPath item = rowItems.get(row);
            if (window == null || item == null || item.isProjectRoot()) return;

            // The whole SELECTION when the pressed row is part of it, otherwise just this row -- the
            // same rule the graph uses for node drags, and for the same reason: "drag the five I
            // selected" is the common gesture, and a press that collapsed the selection breaks it.
            List<CgPath> dragged = selectedPaths().contains(item) ? selectedPaths() : List.of(item);

            // NO ICON for a multi-selection: "3 items" has no one glyph, and picking the first file's
            // would claim the drag is about that file.
            dragGhost.follow(window, dragged.size() == 1
                    ? FileIconTheme.getDefault().iconFor(dragged.get(0).name(), false, false)
                    : null,
                    dragged.size() == 1 ? dragged.get(0).name() : dragged.size() + " items");

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
        applyFindBar();
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

    /**
     * The capsule that follows the cursor while files are being moved.
     *
     * <p>Was thirty-odd lines here — park it, write {@code position: absolute} and {@code display: none}
     * at IMPORTANT from Java, keep a label field, re-register per drag — with a comment explaining each.
     * {@code StripeView} then needed the same thirty, verbatim, which is the point at which a body stops
     * being a call site and becomes a duplicated implementation. All three rules and all three
     * explanations now live in {@link DragGhost}.</p>
     */
    private final DragGhost dragGhost = new DragGhost();

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

    /**
     * A recycled row's writable parts.
     *
     * <p>Held in a map rather than reached through {@code getChildren().get(n)}: four slots addressed by
     * index is one insertion away from silently writing the badge into the label, and the indices would
     * live at the call site where nothing explains them.</p>
     */
    private record RowParts(UIElement twisty, UIElement icon, UIText label, UIText badge,
                            TextField editor) {
    }

    private final java.util.Map<UIElement, RowParts> rowParts = new java.util.HashMap<>();

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
    private static void swapPrefixedClass(UIElement element, String prefix, String next) {
        element.swapPrefixedClass(prefix, next);
    }

    // -- Find ---------------------------------------------------------------------------------------

    /** The bar along the top of the panel while a search is live. */
    public static final String FIND_BAR_CLASS = "__find-bar__";

    /** The button that switches Filter and Highlight. */
    public static final String FIND_MODE_CLASS = "__find-mode__";

    /** The "3 of 12" readout. */
    public static final String FIND_COUNT_CLASS = "__find-count__";

    /** On a row whose own name matches, in Highlight mode. */
    public static final String MATCH_CLASS = "__match__";

    /** On a row that matches nothing and contains nothing that does. */
    public static final String DIMMED_CLASS = "__dimmed__";

    private final UIElement findBar = new UIElement();
    private final UIText findQuery = new UIText("");
    private final UIText findCount = new UIText("");
    private final Button findMode = new Button("Highlight");

    /**
     * The bar that says a search is on.
     *
     * <h3>Why this is the feature and the modes are the detail</h3>
     *
     * <p>This panel's own javadoc named the defect before the bar existed: "a filter with nothing saying
     * it is on is a tree that has mysteriously lost half its files, which is IntelliJ's one real weakness
     * with speed search." Typing narrowed the tree and nothing on screen explained why. Two modes are
     * worth having; <b>being able to see that one of them is running</b> is what was actually missing.</p>
     */
    private void buildFindBar() {
        findBar.addClass(FIND_BAR_CLASS);
        findQuery.setHitTest(false);
        findCount.addClass(FIND_COUNT_CLASS);
        findCount.setHitTest(false);
        findMode.addClass(FIND_MODE_CLASS);
        findMode.onPressed.connect(this::toggleFindMode);
        findBar.addChild(findQuery);
        findBar.addChild(findCount);
        findBar.addChild(findMode);
        // FIRST, not appended. The tree is already in `content` and grows, so adding the bar after it
        // put the bar below the list and overlapping its last row -- VS Code's find widget is at the top
        // of the view and there is nothing for it to cover there.
        content.addChildAt(findBar, 0);
        applyFindBar();
    }

    /** Filter <-> Highlight. Kept on the source, which is what both the rows and the model read. */
    public void toggleFindMode() {
        source.setFindMode(source.findMode() == WorkspaceTreeSource.FindMode.HIGHLIGHT
                ? WorkspaceTreeSource.FindMode.FILTER
                : WorkspaceTreeSource.FindMode.HIGHLIGHT);
        tree.refresh();
        applyFindBar();
    }

    /** Shows or hides the bar, and writes what it says. */
    private void applyFindBar() {
        boolean searching = !filter().isEmpty();
        StyleGroup.importantPipeline(findBar.getStyle().getLayoutGroup(),
                l -> l.display(searching ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        if (!searching) return;
        findQuery.setText(filter());
        findMode.setText(source.findMode() == WorkspaceTreeSource.FindMode.FILTER
                ? "Filter" : "Highlight");
        int matches = 0;
        for (TreeRow<CgPath> row : tree.visibleRows()) {
            if (source.isMatch(row.item())) matches++;
        }
        // WHAT IS ON SCREEN, not what the workspace holds. The tree is listed a directory at a time, so a
        // total would be a number about the parts that happen to have been opened -- which reads as a
        // search result and is not one. See WorkspaceTreeSource.descendantMatches.
        findCount.setText(matches == 0 ? "no matches here" : matches + " shown");
    }

    // -- Inline editing ---------------------------------------------------------------------------

    /** On the row's inline input, hidden unless that row is being edited. */
    public static final String EDITOR_CLASS = "__row-editor__";

    /** On the row while it is being edited, so a theme can quiet the rest of it. */
    public static final String EDITING_CLASS = "__editing__";

    /**
     * An edit in progress -- VS Code's {@code editableData}.
     *
     * @param path     the row being edited; for a new entry, the placeholder the source inserted
     * @param onCommit handed the typed name, only when it is valid and changed
     */
    private record Editing(CgPath path, java.util.function.Consumer<String> onCommit) {
    }

    @Nullable
    private Editing editing;

    /** True while a commit or cancel is running, so a blur raised by either cannot re-enter. */
    private boolean finishingEdit;

    /**
     * Renames {@code path} in place -- F2.
     *
     * <p>An input <b>in the row</b>, not a dialog over it. A dialog is what this used to do and what file
     * managers stopped doing decades ago: it hides the folder you are naming inside, it puts the answer
     * somewhere other than where the question is, and it cannot show the icon change as you type.</p>
     */
    public void beginRename(CgPath path, java.util.function.Consumer<String> onCommit) {
        if (path == null || path.isProjectRoot()) return;
        startEditing(new Editing(path, onCommit));
    }

    /**
     * Adds a placeholder row under {@code parent} and edits it -- VS Code's {@code NewExplorerItem}.
     *
     * <p>The row exists before the file does, which is the whole point: you see where it will land, in
     * the folder you chose, before committing to a name.</p>
     */
    public void beginNew(CgPath parent, boolean directory,
                         java.util.function.Consumer<String> onCommit) {
        if (parent == null) return;
        // EXPANDED FIRST, or the placeholder is a child of a folded folder and nothing appears at all --
        // which reads as New File doing nothing.
        if (!tree.isExpanded(parent)) tree.setExpanded(parent, true);
        CgPath placeholder = source.beginPendingNew(parent, directory);
        startEditing(new Editing(placeholder, onCommit));
    }

    private void startEditing(Editing next) {
        cancelEdit();
        editing = next;
        // Deferred rather than immediate: this is routinely called from a menu row's activation or a key
        // press, and refreshing now would rebuild the element that dispatch is still walking.
        pendingRefresh = true;
    }

    /** Whether a row is being edited. */
    public boolean isEditing() {
        return editing != null;
    }

    /** The row being edited, or null. */
    @Nullable
    public CgPath editingPath() {
        return editing == null ? null : editing.path();
    }

    /** Drops the edit, and any placeholder with it. */
    public void cancelEdit() {
        if (editing == null || finishingEdit) return;
        finishingEdit = true;
        editing = null;
        source.endPendingNew();
        pendingRefresh = true;
        finishingEdit = false;
        returnFocusToTree();
    }

    /**
     * Accepts what was typed, if it is usable.
     *
     * <p><b>An invalid name cancels rather than commits.</b> That is the same answer a blur gives, and the
     * only one that cannot destroy anything: committing a name the validator has already refused is
     * worse, and the alternative to both is trapping the user in a row they cannot leave.</p>
     */
    private void commitEdit(String typed) {
        Editing current = editing;
        if (current == null || finishingEdit) return;
        String name = typed == null ? "" : typed.trim();
        boolean usable = isValidName(current.path(), name);
        finishingEdit = true;
        editing = null;
        source.endPendingNew();
        pendingRefresh = true;
        finishingEdit = false;
        returnFocusToTree();
        // The unchanged case is not a failure and must not be reported as one -- pressing F2 then Enter is
        // how people check what a file is called.
        if (usable && !name.equals(current.path().name())) current.onCommit().accept(name);
    }

    /**
     * Whether {@code name} may be committed for {@code path}.
     *
     * <p>Three refusals, each a real one rather than a guess: empty, a path separator (which would
     * silently create the entry in another directory), and a sibling that already exists. The last is
     * checked against what has been <b>listed</b>, so it catches the case that matters -- a name you can
     * see on screen.</p>
     */
    boolean isValidName(CgPath path, String name) {
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        CgPath parent = path.parent();
        if (parent == null) return true;
        for (CgPath sibling : source.listedChildren(parent)) {
            if (!sibling.equals(path) && sibling.name().equalsIgnoreCase(name)) return false;
        }
        return true;
    }

    /** Focus goes back to the tree, or the next key press lands nowhere. */
    private void returnFocusToTree() {
        UIWindow window = getAttachedWindow();
        if (window != null && tree.getAttachedWindow() != null) {
            window.getInputHandler().requestPointerFocus(tree);
        }
    }

    /**
     * Wires one row's input. Once, in {@code createTemplate} -- a listener may only be attached once, and
     * a recycled row keeps the one it was built with.
     */
    private void installEditor(UIElement row, TextField editor) {
        editor.onSubmit.connect(this::commitEdit);
        editor.onBlur.attachListener((element, event) -> {
            // BLUR COMMITS, as VS Code's does. Cancelling on blur means clicking away from a name you have
            // finished typing throws it away, which is the more expensive of the two mistakes.
            CgPath item = rowItems.get(row);
            if (editing != null && item != null && item.equals(editing.path())) {
                commitEdit(editor.getText());
            }
        }, false, true);
        editor.onKeyDown.attachListener((element, event) -> {
            if (event.getKeyCode() == CgKeyCodes.KEY_ESCAPE) {
                cancelEdit();
                event.stopPropagation();
            }
        }, false, true);
    }

    /**
     * Puts {@code row} into or out of edit mode.
     *
     * <p>Driven from {@code bind}, because a recycled row may arrive still showing the previous
     * occupant's editor -- the same reason every other data-driven class here is swapped rather than
     * added.</p>
     */
    private void applyEditing(UIElement row, RowParts parts, CgPath item) {
        boolean active = editing != null && editing.path().equals(item);
        if (active) row.addClass(EDITING_CLASS);
        else row.removeClass(EDITING_CLASS);
        StyleGroup.importantPipeline(parts.editor().getStyle().getLayoutGroup(),
                l -> l.display(active ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        StyleGroup.importantPipeline(parts.label().getStyle().getLayoutGroup(),
                l -> l.display(active ? TaffyDisplay.NONE : TaffyDisplay.FLEX));
        if (!active) return;

        String current = source.isPendingNew(item) ? "" : item.name();
        parts.editor().setText(current);
        UIWindow window = getAttachedWindow();
        if (window != null) window.getInputHandler().requestFocus(parts.editor());
        // THE STEM IS SELECTED, NOT THE WHOLE NAME, which is what F2 does everywhere: the extension is
        // almost never what you are changing, and selecting it means the first keystroke destroys it.
        int dot = current.lastIndexOf('.');
        if (dot > 0) parts.editor().setSelection(0, dot);
        else parts.editor().selectAll();
    }

    /**
     * Marks a row for the live search — Highlight mode only.
     *
     * <p>Three states rather than two, and the third is the one that makes highlight usable: a row that
     * matches is <b>marked</b>, a row that contains a match is left alone so the path to it stays
     * readable, and a row that is neither is <b>dimmed</b>. Marking alone leaves the eye hunting through
     * full-strength noise; removing the rest is the other mode.</p>
     *
     * <p>Classes are SWAPPED, never merely added: a template is a different row every time the view
     * recycles it, and a stale mark would leave the previous file's highlight on this one.</p>
     */
    private void applyFindMarks(UIElement row, RowParts parts, CgPath item, TreeRow<CgPath> treeRow) {
        boolean searching = !filter().isEmpty()
                && source.findMode() == WorkspaceTreeSource.FindMode.HIGHLIGHT;
        boolean match = searching && source.isMatch(item);
        int beneath = searching && treeRow.expandable() ? source.descendantMatches(item) : 0;

        if (match) row.addClass(MATCH_CLASS);
        else row.removeClass(MATCH_CLASS);
        if (searching && !match && beneath == 0) row.addClass(DIMMED_CLASS);
        else row.removeClass(DIMMED_CLASS);

        // THE FOLDER COUNT GOES IN THE BADGE, which already exists and already sits beside the name. A
        // second element would be a slot that is empty in every other state the row can be in.
        if (beneath > 0) parts.badge().setText(String.valueOf(beneath));
        else if (searching) parts.badge().setText("");
    }

    private final class RowRenderer implements TreeRenderer<CgPath> {

        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            row.addClass(ROW_CLASS);

            // FOUR SLOTS, BUILT ONCE HERE. Not in bind(): an element created during bind lands after the
            // layout pass that frame, which is how the command palette's key chips shipped squashed and
            // how the editor's gutter arrows ended up toggling whichever row their slot was first used
            // for. A recycled row keeps its slots and bind() only ever writes into them.
            UIElement twisty = new UIElement();
            twisty.addClass(TWISTY_CLASS);
            UIElement icon = new UIElement();
            icon.addClass(ICON_CLASS);
            UIText label = new UIText("");
            UIText badge = new UIText("");
            badge.addClass(BADGE_CLASS);
            // THE INLINE EDITOR, built here and hidden -- VS Code's FilesRenderer.renderInputBox, which
            // puts a real input INTO the row rather than opening a dialog over it.
            //
            // In createTemplate for the reason the four slots above are: an element created during bind
            // lands after that frame's layout pass. It is also the only way this can work at all, since
            // the edit begins from a KEY PRESS on the row -- building the field then would rebuild the
            // element the press is being dispatched through.
            TextField editor = new TextField();
            editor.addClass(EDITOR_CLASS);

            // Every part refuses the click so the press lands on the row. Click targeting takes the exact
            // element hit and never walks up to a handler-bearing ancestor, which is why every composite
            // in this engine does this.
            // THE TWISTY IS THE ONE PART THAT KEEPS THE POINTER. Everything else refuses it so the press
            // lands on the row -- click targeting takes the exact element hit and never walks up to a
            // handler-bearing ancestor. The chevron is a control in its own right, which is what lets a
            // folder fold on ONE click while the row still needs two. bind() turns it off again for a
            // file, where there is nothing to fold.
            icon.setHitTest(false);
            label.setHitTest(false);
            badge.setHitTest(false);

            // THE LABEL MUST REPORT ITS OWN WIDTH, or there is nothing for the row to overflow with and
            // the horizontal range is always exactly the viewport. UIText latches whether it self-sizes
            // from its FIRST measurement, which happens before any rule here has matched -- so it has to
            // be told, in Java, at construction. Same call, same reason, as the Blackboard's type column.
            label.forceSelfSizeWidth();
            // The BADGE follows the name rather than the row's trailing edge while the list scrolls
            // sideways -- see the stylesheet. `margin-left: auto` puts it at the row's right edge by
            // construction, which is off-screen the moment the row is wider than the viewport: badges
            // simply vanished until scrolled to, and the row's measured content width was its own width,
            // so the label could never be seen to stick out past it at all.

            row.addChild(twisty);
            row.addChild(icon);
            row.addChild(label);
            row.addChild(badge);
            row.addChild(editor);
            rowParts.put(row, new RowParts(twisty, icon, label, badge, editor));
            installEditor(row, editor);
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
            // ONE CLICK ON THE CHEVRON FOLDS, which is IntelliJ's rule and the half this panel was
            // missing -- its own comment said so: "IntelliJ wants the chevron for a single click, which is
            // only better once the chevron is its own hit target". It is one now.
            //
            // Deliberately does NOT select. A chevron press is about the fold and nothing else, so it
            // leaves the selection alone -- and because the row's own listeners are target-phase only, a
            // press that lands here reaches neither the row's double-click nor its drag.
            twisty.onMouseDown.attachListener((element, event) -> {
                if (event.getDetail() == UIInputHandler.KEYBOARD_DETAIL) return;
                CgPath item = rowItems.get(row);
                if (item == null || !source.isDirectory(item)) return;
                tree.setExpanded(item, !tree.isExpanded(item));
                // Deferred, for the reason activate() spells out: this runs from the press that folded
                // the row, and refreshing recycles every realised row including the one under the pointer.
                pendingRefresh = true;
            }, false, false);

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
            RowParts parts = rowParts.get(template);
            if (parts == null) return;

            // ONE QUESTION, asked of the source: a project's name, a plain name, or the whole chain a
            // compacted row stands for. The view cannot work the last one out -- by the time a row exists
            // the swallowed directories are not in the tree at all.
            applyEditing(template, parts, item);
            applyFindMarks(template, parts, item, row);
            String name = source.rowLabel(item);
            // No manual indent and no "+ "/"- " prefix any more: TreeView already writes padding-left from
            // the depth and puts __expanded__/__collapsed__/__leaf__ on the row, so doing either here
            // indented every row TWICE and spelled the twisty in text where CSS can draw it.
            parts.label().setText(name);

            // Icon and filetype class are read from the theme PER BIND, never captured, because a template
            // is a different row every time it is recycled.
            boolean directory = row.expandable();
            // A FILE HAS NO CHEVRON TO PRESS, so its twisty gives the pointer back to the row -- otherwise
            // a click that happened to land in the leading slot would do nothing at all, which reads as a
            // dead strip down the left of the panel.
            parts.twisty().setHitTest(directory);
            FileIconTheme theme = FileIconTheme.getDefault();
            // THE ITEM'S OWN NAME, never the row label: a compacted row reads "main/java/com" and asking
            // the theme about that string would look up an extension of "/com".
            CgUiDrawable glyph = theme.drawableFor(item.name(), directory, row.expanded());
            // EMPTY, never null: null is how the cascade spells "nobody set this", so writing it would
            // leave the previous file's icon in place on a recycled row rather than clearing it.
            //
            // DEFAULT origin, matching what TreeView already does for the row's indent. The theme JSON is
            // a default the cascade can beat -- write it INLINE and `.filetype-java { overlay: icon(...) }`
            // in a stylesheet silently does nothing, which makes the icon the one part of a row a theme
            // cannot touch.
            StyleGroup.defaultPipeline(parts.icon().getStyle().getGeneralGroup(),
                    g -> g.overlay(glyph == null ? CgUiDrawable.EMPTY : glyph));
            swapPrefixedClass(parts.icon(), FILETYPE_PREFIX, theme.classFor(name, directory));

            FileDecoration decoration = decorations.resolve(item, directory);
            swapPrefixedClass(template, DECORATION_PREFIX,
                    decoration == null ? null : decoration.styleClass());
            parts.badge().setText(decoration == null || decoration.letter() == null
                    ? "" : decoration.letter());
        }

        @Override
        public void unbind(UIElement template) {
            rowItems.remove(template);
        }
    }

    /**
     * Routes {@link UiDataKeys#UNDO_STACK} through the same walk everything else uses.
     *
     * <p>Without this the key would answer null for this widget while {@code UndoScope.nearest} found a
     * stack — two mechanisms disagreeing about the same question, which is the thing {@code DataContext}
     * exists to stop.</p>
     */
    /**
     * What Cut/Copy/Paste mean in a file tree — <b>files</b>, not text.
     *
     * <p>The whole reason {@code ClipboardActions} exists: this and the editor's are both correct and
     * neither can be the other, so the menu asks the position rather than naming one of them.</p>
     *
     * <p><b>Reported rather than performed.</b> The tree does not own the file service — {@link Workbench}
     * does — so these run the registered explorer commands, which is also what keeps one undo step per
     * gesture and one place where a conflict is resolved.</p>
     */
    private final ClipboardActions clipboardActions = new ClipboardActions() {
        @Override
        public boolean canCut() {
            return isEnabled(ExplorerCommands.CUT);
        }

        @Override
        public void cut() {
            run(ExplorerCommands.CUT);
        }

        @Override
        public boolean canCopy() {
            return isEnabled(ExplorerCommands.COPY);
        }

        @Override
        public void copy() {
            run(ExplorerCommands.COPY);
        }

        @Override
        public boolean canPaste() {
            return isEnabled(ExplorerCommands.PASTE);
        }

        @Override
        public void paste() {
            run(ExplorerCommands.PASTE);
        }

        private boolean isEnabled(String id) {
            Command command = CommandRegistry.global().get(id);
            return command != null && command.isEnabled(CommandContext.of(ProjectFileTree.this));
        }

        private void run(String id) {
            CommandRegistry.global().run(id, CommandContext.of(ProjectFileTree.this));
        }
    };

    @Override
    public Object getData(DataKey<?> key) {
        if (key == UiDataKeys.CLIPBOARD) return clipboardActions;
        Object undo = undoScopeData(key);
        return undo != null ? undo : super.getData(key);
    }

}
