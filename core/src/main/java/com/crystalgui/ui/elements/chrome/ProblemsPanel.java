package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.DiagnosticTag;
import com.crystalgui.text.diagnostic.Markers;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.tree.TreeRenderer;
import com.crystalgui.ui.elements.tree.TreeRow;
import com.crystalgui.ui.elements.tree.TreeView;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Every problem in the workspace, grouped by file — the Problems panel.
 *
 * <p>VS Code's {@code vs/workbench/contrib/markers} view, over our {@link Markers} index.</p>
 *
 * <h3>Workspace-wide, which is what the resource index was for</h3>
 *
 * <p>This used to bind to a single {@code DiagnosticSet} — the active document's — so it could only ever
 * show the file already on screen, which is the one case where the editor's own error stripe already tells
 * you everything. Its own javadoc said as much while it had no way to do better. {@link Markers} gives it
 * the list of every file with something to report, so the panel is now the thing you consult to find out
 * <em>where</em> the problems are rather than a second opinion about the file you are looking at.</p>
 *
 * <h3>It reports a choice; it still does not navigate</h3>
 *
 * <p>{@link #onProblemChosen} fires with a {@link ProblemNode} and that is all. The panel has no editor and
 * deliberately cannot get one: the problem you clicked is now routinely in a file that is <b>not open</b>,
 * so "navigate" means <em>open the document, then reveal the row</em> — a workspace-level act. The node
 * carries its resource for exactly that reason.</p>
 *
 * <h3>Filtering lives in the source</h3>
 *
 * <p>Because it changes the tree's shape rather than its paint — see {@link ProblemsTreeSource}. A file
 * whose only error is filtered out has to stop being a row.</p>
 */
public class ProblemsPanel extends UIElement {

    public static final String PANEL_CLASS = "__problems__";
    public static final String CONTENT_CLASS = "__content__";
    public static final String LIST_CLASS = "__problem-list__";
    /** One row — a file heading or a problem under it. */
    public static final String ROW_CLASS = "__problem__";
    /** On a file heading, so the sheet can weight it against the problems beneath. */
    public static final String FILE_CLASS = "__problem-file__";
    /** The severity glyph, or the file's icon — a class the sheet turns into a drawable. */
    public static final String ICON_CLASS = "__severity__";
    public static final String MESSAGE_CLASS = "__message__";
    /** The trailing {@code :591}, or a file's folder. Dimmer: where to look, not what is wrong. */
    public static final String LINE_CLASS = "__line__";
    /** How many problems are in a file, on its heading. */
    public static final String COUNT_CLASS = "__problem-count__";
    /** Severity, as a class. Same convention as the notification cards. */
    public static final String SEVERITY_PREFIX = "severity-";
    /** The chevron. Its own hit target, so a file folds on one click. */
    public static final String TWISTY_CLASS = "__twisty__";
    /** The file heading's icon, from the same theme the project tree uses. */
    public static final String FILETYPE_PREFIX = "filetype-";
    public static final String EMPTY_CLASS = "__problems-empty__";
    /** Rendering, not severity — @see DiagnosticTag */
    public static final String TAG_UNNECESSARY = "tag-unnecessary";
    public static final String TAG_DEPRECATED = "tag-deprecated";

    /**
     * The row a user chose — a double click, or Enter on the selection.
     *
     * <p>Never fired for a mere selection change: arrowing through a list is not a decision to go
     * somewhere. Carries the node rather than the diagnostic, so a listener knows which file to open.</p>
     */
    public final Signal.Value<ProblemNode> onProblemChosen = new Signal.Value<>();

    private final UIElement content = new UIElement();
    private final UIText empty = new UIText("No problems have been detected in the workspace");

    @Nullable
    private ProblemsTreeSource source;
    @Nullable
    private TreeView<ProblemNode> tree;

    private final ConnectionGroup binding = new ConnectionGroup();

    public ProblemsPanel() {
        addClass(PANEL_CLASS);
        content.addClass(CONTENT_CLASS);
        // Marked internal exactly ONCE, while empty. markAsInternal() RECURSES, and the tree adds and
        // recycles its own rows -- stamping a populated subtree marks those internal too, after which
        // removeChild silently refuses them.
        addInternalChild(content);

        empty.addClass(EMPTY_CLASS);
        empty.setHitTest(false);
        content.addChild(empty);
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /** The tree, once something has been bound. Null before that. */
    @Nullable
    public TreeView<ProblemNode> tree() {
        return tree;
    }

    /** The filter this panel is showing through, or null before binding. */
    @Nullable
    public ProblemsTreeSource source() {
        return source;
    }

    /**
     * Points the panel at a workspace's problems. Safe to call repeatedly.
     *
     * <p>The previous index's listener is dropped — hygiene rather than correctness, since a refresh reads
     * from the current source either way, but a leaked one retains an abandoned workspace and does a full
     * rebuild per bind on every change to any index this panel has ever shown.</p>
     */
    public ProblemsPanel bindTo(@Nullable Markers markers) {
        binding.disconnectAll();
        if (markers == null) {
            source = null;
            if (tree != null) {
                tree.removeSelf();
                tree = null;
            }
            refresh();
            return this;
        }
        source = new ProblemsTreeSource(markers);
        // REBUILT RATHER THAN RE-POINTED: a TreeView takes its source at construction and offers no way to
        // swap one. Rebinding is a rare, deliberate act — a workspace opening or closing — so the cost is a
        // tree that is thrown away roughly never, and the alternative is a setter on TreeView whose only
        // caller would be this line.
        if (tree != null) tree.removeSelf();
        tree = new TreeView<>(source);
        tree.addClass(LIST_CLASS);
        tree.setItemHeight(16f);
        // A PROBLEM IS NOT WORTH HALF-READING. A driver's message names a line, a symbol and a reason,
        // and the part truncated in a narrow panel is the end -- which is the part that says what is
        // wrong. The project tree gives a long filename the same answer.
        tree.setHorizontalScrolling(true);
        tree.setRenderer(new ProblemRenderer());
        tree.onRowActivated.connect(this::chooseRow);
        content.addChild(tree);
        binding.add(markers.onDidChange.connect(resource -> refresh()));
        refresh();
        return this;
    }

    /** Restricts the tree to one file — VS Code's "Show Active File Only". Null shows the workspace. */
    public ProblemsPanel showOnly(@Nullable Resource resource) {
        if (source == null) return this;
        if (java.util.Objects.equals(source.onlyResource(), resource)) return this;
        source.setOnlyResource(resource);
        refresh();
        return this;
    }

    /** Shows or hides one severity across the whole tree. */
    public ProblemsPanel setSeverityShown(DiagnosticSeverity severity, boolean shown) {
        if (source == null || source.isShown(severity) == shown) return this;
        source.setShown(severity, shown);
        refresh();
        return this;
    }

    /** Substring filter against every message. */
    public ProblemsPanel setTextFilter(@Nullable String text) {
        if (source == null) return this;
        source.setTextFilter(text);
        refresh();
        return this;
    }

    /** Every problem currently shown, in tree order. The surface a test asserts on. */
    public List<Diagnostic> visibleProblems() {
        List<Diagnostic> shown = new java.util.ArrayList<>();
        if (tree == null) return shown;
        for (TreeRow<ProblemNode> row : tree.visibleRows()) {
            if (!row.item().isFile()) shown.add(row.item().diagnostic());
        }
        return shown;
    }

    /** Every file currently shown, in tree order. */
    public List<Resource> visibleFiles() {
        List<Resource> shown = new java.util.ArrayList<>();
        if (tree == null) return shown;
        for (TreeRow<ProblemNode> row : tree.visibleRows()) {
            if (row.item().isFile()) shown.add(row.item().resource());
        }
        return shown;
    }

    /** Drops the listener on whatever this was bound to. */
    public void dispose() {
        bindTo(null);
    }

    private void chooseRow(int index) {
        if (tree == null || index < 0) return;
        TreeRow<ProblemNode> row = tree.rowAt(index);
        if (row == null) return;
        // A FILE HEADING IS NOT A DESTINATION. Activating one opens it, which is what a tree already does
        // with a twisty -- so choosing it would be a second way to spell "expand".
        if (row.item().isFile()) {
            tree.toggleExpanded(row.item());
            return;
        }
        onProblemChosen.emit(row.item());
    }

    private void refresh() {
        boolean anything = source != null && source.shownCount() > 0;
        if (tree != null) {
            tree.refresh();
            tree.setDisplayed(anything);
        }
        empty.setDisplayed(!anything);
        // A FILTERED-TO-NOTHING TREE IS NOT AN EMPTY WORKSPACE, and saying so is what stops a filter
        // reading as "everything got fixed".
        empty.setText(source != null && isFiltering(source)
                ? "No problems match the current filter"
                : "No problems have been detected in the workspace");
    }

    private static boolean isFiltering(ProblemsTreeSource source) {
        if (!source.textFilter().isEmpty() || source.onlyResource() != null) return true;
        for (DiagnosticSeverity severity : DiagnosticSeverity.values()) {
            if (!source.isShown(severity)) return true;
        }
        return false;
    }

    /**
     * One row, serving both levels.
     *
     * <p><b>One template, not two.</b> The view pools and recycles a single element per slot, so a row is a
     * file heading one frame and a problem the next — which means every slot a row can ever need is built in
     * {@code createTemplate} and only shown or hidden in {@code bind}. Creating one during bind lands it
     * after that frame's layout pass, which this engine has paid for three times over.</p>
     */
    private final class ProblemRenderer implements TreeRenderer<ProblemNode> {

        /**
         * What each realised row is currently showing.
         *
         * <p>Read by the twisty <b>at press time</b>, never captured into its listener: rows recycle as the
         * tree scrolls and a listener may only be attached once, so a captured node would keep folding
         * whichever file its slot was first used for. The same trap the editor's gutter arrows document.</p>
         */
        private final java.util.Map<UIElement, ProblemNode> rowItems = new java.util.IdentityHashMap<>();

        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            row.addClass(ROW_CLASS);

            // THE ONE PART THAT KEEPS THE POINTER. Everything else refuses it so a press lands on the row —
            // click targeting takes the exact element hit and never walks up to a handler-bearing ancestor.
            // A chevron is a control in its own right, which is what lets a file fold on ONE click while
            // choosing a problem still takes two.
            UIElement twisty = new UIElement();
            twisty.addClass(TWISTY_CLASS);
            twisty.onMouseDown.attachListener((element, event) -> {
                ProblemNode node = rowItems.get(row);
                if (node == null || !node.isFile() || tree == null) return;
                event.stopPropagation();
                tree.setExpanded(node, !tree.isExpanded(node));
            }, false, false);

            UIElement icon = new UIElement();
            icon.addClass(ICON_CLASS);
            icon.setHitTest(false);

            UIText label = new UIText("");
            label.addClass(MESSAGE_CLASS);
            label.setHitTest(false);

            UIText detail = new UIText("");
            detail.addClass(LINE_CLASS);
            detail.setHitTest(false);
            detail.forceSelfSizeWidth();

            UIText count = new UIText("");
            count.addClass(COUNT_CLASS);
            count.setHitTest(false);
            count.forceSelfSizeWidth();

            row.addChild(twisty);
            row.addChild(icon);
            row.addChild(label);
            row.addChild(detail);
            row.addChild(count);
            return row;
        }

        @Override
        public void bind(ProblemNode item, TreeRow<ProblemNode> row, int index, UIElement template) {
            rowItems.put(template, item);
            List<UIElement> parts = template.getChildren();
            UIElement icon = parts.get(1);
            UIText label = (UIText) parts.get(2);
            UIText detail = (UIText) parts.get(3);
            UIText count = (UIText) parts.get(4);

            if (item.isFile()) {
                template.addClass(FILE_CLASS);
                // THE FILE'S OWN ICON, from the theme the project tree already uses -- a heading naming
                // water.glsl should look like water.glsl does everywhere else. The severity slot is
                // emptied rather than left, or a row recycled from a problem keeps its error glyph.
                icon.swapPrefixedClass(SEVERITY_PREFIX, SEVERITY_PREFIX + "file");
                String name = item.resource().name();
                FileIconTheme theme = FileIconTheme.getDefault();
                CgUiDrawable glyph = theme.drawableFor(name, false, false);
                // DEFAULT origin, exactly as the project tree writes it: the theme's JSON is a default the
                // cascade can beat, and writing it inline would make the icon the one part of a row a
                // stylesheet cannot touch. It also means the severity rules — which are STYLESHEET origin —
                // still win on a row recycled from a problem, so there is nothing to clear.
                StyleGroup.defaultPipeline(icon.getStyle().getGeneralGroup(),
                        g -> g.overlay(glyph == null ? CgUiDrawable.EMPTY : glyph));
                icon.swapPrefixedClass(FILETYPE_PREFIX, theme.classFor(name, false));
                label.setText(item.resource().name());
                detail.setText(folderOf(item.resource()));
                count.setDisplayed(true);
                int problems = source == null ? 0 : source.matching(item.resource()).size();
                count.setText(String.valueOf(problems));
                setTag(template, TAG_UNNECESSARY, false);
                setTag(template, TAG_DEPRECATED, false);
                return;
            }
            // The filetype class goes with the file row, or a problem row inherits its heading's glyph.
            icon.swapPrefixedClass(FILETYPE_PREFIX, "");
            template.removeClass(FILE_CLASS);
            Diagnostic diagnostic = item.diagnostic();
            // SWAPPED, never added: a template is a different row every time the view recycles it, so
            // adding `severity-error` without removing `severity-file` leaves both on the element and the
            // cascade resolves whichever happens to win.
            icon.swapPrefixedClass(SEVERITY_PREFIX, SEVERITY_PREFIX + severityClass(diagnostic));
            label.setText(diagnostic.message());
            // OMITTED, not dashed, when there is nothing to point at: a graph's node-level problem simply
            // ends after its message.
            detail.setText(diagnostic.hasPosition() ? ":" + (diagnostic.start().row() + 1) : "");
            count.setDisplayed(false);
            setTag(template, TAG_UNNECESSARY, diagnostic.hasTag(DiagnosticTag.UNNECESSARY));
            setTag(template, TAG_DEPRECATED, diagnostic.hasTag(DiagnosticTag.DEPRECATED));
        }
    }

    private static void setTag(UIElement row, String cls, boolean present) {
        if (present) row.addClass(cls);
        else row.removeClass(cls);
    }

    /** The folder a file sits in, shown dim beside its name — VS Code's second column. */
    private static String folderOf(Resource resource) {
        String path = resource.path();
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    /** The class the sheet keys the glyph and the colour off. */
    private static String severityClass(Diagnostic diagnostic) {
        DiagnosticSeverity severity = diagnostic.severity();
        if (severity == DiagnosticSeverity.ERROR) return "error";
        if (severity == DiagnosticSeverity.WARNING) return "warning";
        return "info";
    }
}
