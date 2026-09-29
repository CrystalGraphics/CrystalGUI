package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.trace.CgTraceAggregate;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The call tree of the selection beside the callers of the selected zone, in a split the reader owns.
 *
 * <pre>{@code
 * CallTreeTab tab = new CallTreeTab();
 * tab.onZoneSelected(model::selectZone);
 * tab.show(model.treeOfSelection(), model.selectedZone());
 * }</pre>
 *
 * <h3>Two directions, one selection</h3>
 *
 * <p><b>Callees</b> is the tree top-down: what each zone spent its time in, same-named siblings merged
 * into one row. <b>Callers</b> is the same data bottom-up, rooted at the selected zone: under it, every
 * zone that called it; under each of those, what called <em>that</em>. It answers "why is this being
 * called eight hundred times", which the top-down tree cannot, because the eight hundred instances are
 * scattered through it.</p>
 *
 * <p>Selecting a callee row selects its zone, so the chart and the zones table follow, and the callers
 * side answers for it.</p>
 */
public class CallTreeTab extends UIElement {

    public static final Name NAME = Name.of("calltreetab");

    public static final String ROW_CLASS = "__call-row__";
    public static final String COST_CLASS = "__call-cost__";
    public static final String HEADING_CLASS = "__call-heading__";
    public static final String SIDE_CLASS = "__call-side__";
    public static final String TITLE_CLASS = "__call-title__";
    public static final String TWISTY_CLASS = "__twisty__";

    private final List<CallNode> callees = new ArrayList<>();
    private final List<CallNode> callers = new ArrayList<>();

    private final TreeView<CallNode> calleeTree;
    private final TreeView<CallNode> callerTree;
    private final UIText callersHeading = new UIText(NO_ZONE);
    private final SplitView split = new SplitView();

    @Nullable
    private Consumer<String> zoneSelected;
    private boolean showing;

    private static final String NO_ZONE = "Callers — select a zone";
    private static final float PANE_MIN_PX = 280f;

    public CallTreeTab() {
        super(NAME);

        calleeTree = new TreeView<>(sourceOver(callees));
        calleeTree.setRenderer(new CostRenderer(calleeTree, false));
        callerTree = new TreeView<>(sourceOver(callers));
        callerTree.setRenderer(new CostRenderer(callerTree, true));

        calleeTree.onSelectionChanged.connect(indices -> {
            if (showing || zoneSelected == null || indices.isEmpty()) return;
            TreeRow<CallNode> row = calleeTree.rowAt(indices.iterator().next());
            if (row != null) zoneSelected.accept(row.item().name);
        });

        UIElement calleeSide = new UIElement();
        calleeSide.addClass(SIDE_CLASS);
        calleeSide.append(headingRow(new UIText("Callees"), "Total ms", "Self ms"));
        calleeSide.append(calleeTree);

        UIElement callerSide = new UIElement();
        callerSide.addClass(SIDE_CLASS);
        callerSide.append(headingRow(callersHeading, "Time ms", "Calls"));
        callerSide.append(callerTree);

        split.first(calleeSide);
        split.second(callerSide);
        split.setPercentage(55f);
        // A FLOOR PER PANE: room for a title beside the two 72px columns. Narrower, the title had no
        // width left to ellipsize into and was drawn straight over the column heads.
        split.setPaneSizeLimits(0, PANE_MIN_PX, Float.MAX_VALUE);
        split.setPaneSizeLimits(1, PANE_MIN_PX, Float.MAX_VALUE);
        append(split);
    }

    /** Called with a zone's name when a callee row is chosen. */
    public CallTreeTab onZoneSelected(Consumer<String> listener) {
        this.zoneSelected = listener;
        return this;
    }

    public TreeView<CallNode> calleeTree() {
        return calleeTree;
    }

    public TreeView<CallNode> callerTree() {
        return callerTree;
    }

    public SplitView split() {
        return split;
    }

    /** A section title with the two column heads on the right, over the rows' own columns. */
    private static UIElement headingRow(UIText title, String first, String second) {
        UIElement row = new UIElement();
        row.addClass(HEADING_CLASS);
        title.addClass(TITLE_CLASS);
        row.append(title);
        UIText a = new UIText(first);
        a.addClass(COST_CLASS);
        row.append(a);
        UIText b = new UIText(second);
        b.addClass(COST_CLASS);
        row.append(b);
        return row;
    }

    /** A source over a list this class refills, so {@link #show} never rebuilds the view. */
    private static TreeDataSource<CallNode> sourceOver(List<CallNode> roots) {
        return new TreeDataSource<>() {
            @Override
            public List<CallNode> roots() {
                return roots;
            }

            @Override
            public List<CallNode> children(CallNode parent) {
                return parent.children;
            }

            @Override
            public boolean hasChildren(CallNode item) {
                return !item.children.isEmpty();
            }
        };
    }

    public void show(List<CgTraceAggregate.Node> tree, @Nullable String selectedZone) {
        showing = true;
        try {
            callees.clear();
            callees.addAll(calleesOf(tree));
            calleeTree.refresh();
            if (calleeTree.expandedItems().isEmpty()) openHotPath(calleeTree, callees);
            // THE CHART'S SELECTION, shown here only if the row already chosen is not that zone: a zone
            // reached through two paths has two rows, and the one the reader clicked is the one to keep.
            CallNode chosen = selectedItem(calleeTree);
            if (selectedZone != null && (chosen == null || !chosen.name.equals(selectedZone))) {
                selectVisible(calleeTree, selectedZone);
            }

            callers.clear();
            if (selectedZone != null) {
                CallNode root = callersOf(tree, selectedZone);
                callers.add(root);
                callersHeading.setText("Callers of " + selectedZone);
            } else {
                callersHeading.setText(NO_ZONE);
            }
            callerTree.refresh();
            if (!callers.isEmpty()) openHotPath(callerTree, callers);
        } finally {
            showing = false;
        }
    }

    @Nullable
    private static CallNode selectedItem(TreeView<CallNode> view) {
        List<CallNode> selected = view.selectedItems();
        return selected.isEmpty() ? null : selected.get(0);
    }

    private static void selectVisible(TreeView<CallNode> view, String name) {
        List<TreeRow<CallNode>> rows = view.visibleRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item().name.equals(name)) {
                view.select(i);
                view.scrollToIndex(i);
                return;
            }
        }
    }

    /**
     * Opens the heaviest chain from the heaviest root down — the rows a reader would open first.
     *
     * <p>Stops at a child worth under a fifth of its parent: past that point the time is spread out and
     * there is no one path to follow. Only when nothing is open, so a tree the reader has folded stays
     * folded while the same frame is on screen.</p>
     */
    private static void openHotPath(TreeView<CallNode> view, List<CallNode> roots) {
        if (roots.isEmpty()) return;
        CallNode node = roots.get(0);
        while (!node.children.isEmpty()) {
            view.setExpanded(node, true);
            CallNode heaviest = node.children.get(0);
            if (heaviest.totalNanos * 5L < node.totalNanos) break;
            node = heaviest;
        }
    }

    // ── Building ─────────────────────────────────────────────────────────────────────────────

    /**
     * The top-down tree with same-named siblings folded into one row, heaviest first.
     *
     * <p>Folded because what a call tree answers is "what did this path cost in total", which only one
     * row per path can say: across a range of twenty frames the root level otherwise lists
     * {@code paint:tree} twenty times.</p>
     */
    static List<CallNode> calleesOf(List<CgTraceAggregate.Node> roots) {
        List<CallNode> out = new ArrayList<>();
        mergeInto(out, "", roots);
        heaviestFirst(out);
        return out;
    }

    private static void mergeInto(List<CallNode> level, String parentPath, List<CgTraceAggregate.Node> nodes) {
        Map<String, List<CgTraceAggregate.Node>> byName = new LinkedHashMap<>();
        for (CgTraceAggregate.Node node : nodes) {
            byName.computeIfAbsent(node.name(), ignored -> new ArrayList<>()).add(node);
        }
        for (Map.Entry<String, List<CgTraceAggregate.Node>> same : byName.entrySet()) {
            CallNode merged = new CallNode(parentPath + PATH_SEPARATOR + same.getKey(), same.getKey());
            List<CgTraceAggregate.Node> children = new ArrayList<>();
            for (CgTraceAggregate.Node node : same.getValue()) {
                merged.calls++;
                merged.totalNanos += node.durationNanos();
                merged.selfNanos += node.selfNanos();
                children.addAll(node.children());
            }
            mergeInto(merged.children, merged.path, children);
            level.add(merged);
        }
    }

    /**
     * The bottom-up tree for {@code zone}: the zone, then its callers, then theirs.
     *
     * <p>Each row's time is the time spent in {@code zone} when reached through that chain, which is
     * what makes the rows under one caller add up to that caller. A zone nested inside itself is counted
     * at its outermost call only, or recursion would count the same time twice.</p>
     */
    static CallNode callersOf(List<CgTraceAggregate.Node> roots, String zone) {
        CallNode root = new CallNode(CALLERS_PATH + PATH_SEPARATOR + zone, zone);
        List<CgTraceAggregate.Node> chain = new ArrayList<>();
        for (CgTraceAggregate.Node node : roots) collectCallers(node, zone, chain, root);
        heaviestFirst(root.children);
        return root;
    }

    private static void collectCallers(CgTraceAggregate.Node node, String zone,
                                       List<CgTraceAggregate.Node> chain, CallNode root) {
        if (node.name().equals(zone)) {
            long time = node.durationNanos();
            root.calls++;
            root.totalNanos += time;
            root.selfNanos += node.selfNanos();
            CallNode at = root;
            for (int i = chain.size() - 1; i >= 0; i--) {
                at = at.child(chain.get(i).name());
                at.calls++;
                at.totalNanos += time;
            }
            return;
        }
        chain.add(node);
        for (CgTraceAggregate.Node child : node.children()) collectCallers(child, zone, chain, root);
        chain.remove(chain.size() - 1);
    }

    private static void heaviestFirst(List<CallNode> level) {
        level.sort(Comparator.comparingLong((CallNode node) -> node.totalNanos).reversed());
        for (CallNode node : level) heaviestFirst(node.children);
    }

    /** Between a path's names. A character no zone name contains. */
    private static final String PATH_SEPARATOR = "\u0000";
    /** Keeps the callers tree's paths apart from the callees', should both ever share a view. */
    private static final String CALLERS_PATH = "\u0001callers";

    /**
     * One row: a call path, merged across every call that took it.
     *
     * <p><b>Equal by path</b>, so a row rebuilt from the next snapshot is the same item to the tree —
     * which is what keeps what is open and what is selected across a live refresh.</p>
     */
    public static final class CallNode {
        final String path;
        final String name;
        final List<CallNode> children = new ArrayList<>();
        int calls;
        long totalNanos;
        long selfNanos;

        CallNode(String path, String name) {
            this.path = path;
            this.name = name;
        }

        public String name() {
            return name;
        }

        public int calls() {
            return calls;
        }

        public long totalNanos() {
            return totalNanos;
        }

        public long selfNanos() {
            return selfNanos;
        }

        public List<CallNode> children() {
            return children;
        }

        private CallNode child(String childName) {
            for (CallNode child : children) {
                if (child.name.equals(childName)) return child;
            }
            CallNode child = new CallNode(path + PATH_SEPARATOR + childName, childName);
            children.add(child);
            return child;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CallNode node && node.path.equals(path);
        }

        @Override
        public int hashCode() {
            return path.hashCode();
        }

        @Override
        public String toString() {
            return path.replace(PATH_SEPARATOR, " > ");
        }
    }

    /** Twisty, name, and two figures — total and self for callees, time and calls for callers. */
    private static final class CostRenderer implements TreeRenderer<CallNode> {

        private final TreeView<CallNode> view;
        private final boolean callers;

        CostRenderer(TreeView<CallNode> view, boolean callers) {
            this.view = view;
            this.callers = callers;
        }

        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            row.addClass(ROW_CLASS);
            UIElement twisty = new UIElement();
            twisty.addClass(TWISTY_CLASS);
            // ITS OWN HIT TARGET, claimed before the list's row handler: opening a branch is not choosing it.
            twisty.events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
                int index = view.indexOfRowElement(row);
                TreeRow<CallNode> at = index < 0 ? null : view.rowAt(index);
                if (at == null || !at.expandable()) return;
                event.stopPropagation();
                event.preventDefault();
                view.requestToggleAt(index);
            }, false, false);
            row.append(twisty);
            UIText name = new UIText("");
            name.addClass(TITLE_CLASS);
            row.append(name);
            UIText first = new UIText("");
            first.addClass(COST_CLASS);
            row.append(first);
            UIText second = new UIText("");
            second.addClass(COST_CLASS);
            row.append(second);
            return row;
        }

        @Override
        public void bind(CallNode item, TreeRow<CallNode> row, int index, UIElement template) {
            List<UIElement> parts = template.children();
            if (parts.size() < 4) return;
            String label = !callers && item.calls > 1 ? item.name + COUNT_MARK + item.calls : item.name;
            setText(parts.get(1), label);
            setText(parts.get(2), millis(item.totalNanos));
            setText(parts.get(3), callers ? Integer.toString(item.calls) : millis(item.selfNanos));
        }

        private static void setText(UIElement part, String text) {
            UIText label = (UIText) part;
            if (!Objects.equals(label.getText(), text)) label.setText(text);
        }

        @Override
        public String copyTextFor(CallNode item) {
            return callers
                    ? String.format("%s  %.3f ms  %d calls", item.name, item.totalNanos / 1e6, item.calls)
                    : String.format("%s  %.3f ms total  %.3f ms self", item.name, item.totalNanos / 1e6,
                            item.selfNanos / 1e6);
        }
    }

    private static String millis(long nanos) {
        return String.format("%.3f", nanos / 1_000_000d);
    }

    /** Between a merged row's name and how many calls it folds — {@code layer:blit  ×3}. */
    static final String COUNT_MARK = "  ×";
}
