package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.tree.TreeRenderer;
import com.crystalgui.ui.elements.chrome.ContextMenu;
import com.crystalgui.ui.elements.tree.TreeRow;
import com.crystalgui.ui.elements.tree.TreeView;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * The project's files, as a tree — click a directory to expand, click a file to open it.
 *
 * <h3>One click, not two</h3>
 *
 * <p>A single click opens. Desktop file managers use double-click because a single click has to mean
 * "select" — there is a whole window of operations that act on the selection. Here there is one thing to
 * do with a file, and a selection that opened nothing would be a state with no purpose. VS Code's explorer
 * makes the same call.</p>
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
        if (source.drainRefresh()) tree.refresh();
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
            tree.refresh();
            return;
        }
        onFileChosen.emit(path);
    }

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
            row.onMouseDown.attachListener((element, event) -> {
                CgPath item = rowItems.get(row);
                if (item == null) return;
                if (source.isDirectory(item) || event.getDetail() >= 2) activate(item);
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
