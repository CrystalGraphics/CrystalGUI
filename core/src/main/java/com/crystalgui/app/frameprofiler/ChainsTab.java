package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.trace.CgTraceSnapshot;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * Spans — the chains that are not frames: opening a file is a command, a picker, a search per
 * keystroke, an accept, a dock open, a read and a parse, and what matters is the order and where it
 * stalled.
 *
 * <pre>{@code
 * ChainsTab tab = new ChainsTab();
 * tab.onStartSelected(model::selectFrameAtNanos);
 * tab.show(model.snapshot().spans());
 * }</pre>
 *
 * <p>Not bound to the selected frame: a chain is the one thing in this window that outlives one, so the
 * tab lists every chain the ring holds, newest first. Where a step waited between its own steps a
 * <b>waited</b> row says for how long, and the longest step or wait in each chain is marked — the stall.
 * Choosing a row selects the frame it started in.</p>
 */
public class ChainsTab extends UIElement {

    public static final Name NAME = Name.of("chainstab");

    public static final String ROW_CLASS = "__chain-row__";
    public static final String NAME_CLASS = "__chain-name__";
    public static final String FIGURE_CLASS = "__chain-figure__";
    public static final String WAIT_CLASS = "__wait__";
    public static final String STALL_CLASS = "__stall__";
    public static final String TWISTY_CLASS = "__twisty__";
    public static final String HEADING_CLASS = "__chain-heading__";
    public static final String EMPTY_CLASS = "__empty__";

    /** A wait worth a row: at least a millisecond, and a tenth of its parent. */
    private static final long WAIT_FLOOR_NANOS = 1_000_000L;

    /** The most chains listed; the rest are older and the ring holds them for export. */
    private static final int MAX_CHAINS = 200;

    private final List<Step> roots = new ArrayList<>();
    private final TreeView<Step> tree;
    private final UIText empty = new UIText("No chains recorded. Chains are spans on crystalgui.flow: "
            + "opening a file, a search, anything that outlives a frame.");
    private LongConsumer startSelected = nanos -> { };
    private boolean showing;
    @Nullable
    private String shownKey;

    public ChainsTab() {
        super(NAME);
        tree = new TreeView<>(new TreeDataSource<>() {
            @Override
            public List<Step> roots() {
                return roots;
            }

            @Override
            public List<Step> children(Step parent) {
                return parent.children;
            }

            @Override
            public boolean hasChildren(Step item) {
                return !item.children.isEmpty();
            }
        });
        tree.setRenderer(new StepRenderer());
        tree.onSelectionChanged.connect(indices -> {
            if (showing || indices.isEmpty()) return;
            TreeRow<Step> row = tree.rowAt(indices.iterator().next());
            if (row != null) startSelected.accept(row.item().start);
        });

        UIElement heading = new UIElement();
        heading.addClass(HEADING_CLASS);
        UIText title = new UIText("Chain");
        title.addClass(NAME_CLASS);
        heading.append(title);
        for (String label : List.of("Starts", "Took")) {
            UIText figure = new UIText(label);
            figure.addClass(FIGURE_CLASS);
            heading.append(figure);
        }
        append(heading);
        empty.addClass(EMPTY_CLASS);
        append(empty);
        append(tree);
    }

    /** Called with a chosen step's start, on the ring's clock — what selects the frame it began in. */
    public ChainsTab onStartSelected(LongConsumer listener) {
        startSelected = listener;
        return this;
    }

    public TreeView<Step> tree() {
        return tree;
    }

    public List<Step> chains() {
        return List.copyOf(roots);
    }

    /** Rebuilds from {@code spans}, only when they changed — a live window hands the same list often. */
    public void show(List<CgTraceSnapshot.SpanView> spans) {
        String key = spans.size() + ":" + (spans.isEmpty() ? 0L : spans.get(spans.size() - 1).id())
                + ":" + openCount(spans);
        if (key.equals(shownKey)) return;
        shownKey = key;
        showing = true;
        try {
            roots.clear();
            roots.addAll(build(spans));
            empty.setDisplayed(roots.isEmpty());
            tree.setDisplayed(!roots.isEmpty());
            tree.refresh();
        } finally {
            showing = false;
        }
    }

    private static int openCount(List<CgTraceSnapshot.SpanView> spans) {
        int open = 0;
        for (CgTraceSnapshot.SpanView span : spans) if (span.isOpen()) open++;
        return open;
    }

    /** The spans as trees, newest chain first, with the waits between steps made rows of their own. */
    static List<Step> build(List<CgTraceSnapshot.SpanView> spans) {
        Map<Long, Step> byId = new HashMap<>();
        for (CgTraceSnapshot.SpanView span : spans) byId.put(span.id(), new Step(span));
        List<Step> out = new ArrayList<>();
        for (Step step : byId.values()) {
            Step parent = byId.get(step.span.parent());
            // A PARENT THE RING NO LONGER HOLDS makes this a root: the chain's head was overwritten, and
            // what is left of it is still worth reading.
            if (parent == null || parent == step) out.add(step);
            else parent.children.add(step);
        }
        for (Step step : byId.values()) step.settle();
        out.sort(Comparator.comparingLong((Step step) -> step.start).reversed());
        List<Step> kept = out.size() > MAX_CHAINS ? new ArrayList<>(out.subList(0, MAX_CHAINS)) : out;
        for (Step root : kept) root.markStall(root.start);
        return kept;
    }

    /**
     * One row: a span, or the wait between two of a span's steps. Equal by span id and place, so what
     * is open and what is selected survive a rebuild.
     */
    public static final class Step {
        @Nullable
        final CgTraceSnapshot.SpanView span;
        final String name;
        final long start;
        final long end;
        final boolean open;
        final String key;
        final List<Step> children = new ArrayList<>();
        /** From its chain's start, for the Starts column. */
        long offset;
        boolean stall;

        Step(CgTraceSnapshot.SpanView span) {
            this.span = span;
            this.name = span.name();
            this.start = span.startNanos();
            this.open = span.isOpen();
            this.end = open ? System.nanoTime() : span.endNanos();
            this.key = "span:" + span.id();
        }

        private Step(String key, long start, long end) {
            this.span = null;
            this.name = "waited";
            this.start = start;
            this.end = end;
            this.open = false;
            this.key = key;
        }

        public String name() {
            return name;
        }

        public long durationNanos() {
            return Math.max(0L, end - start);
        }

        public boolean isWait() {
            return span == null;
        }

        public boolean isStall() {
            return stall;
        }

        public List<Step> children() {
            return children;
        }

        /** Orders the steps and puts a wait row wherever the parent sat idle between them. */
        void settle() {
            if (isWait() || children.isEmpty()) return;
            children.sort(Comparator.comparingLong(step -> step.start));
            List<Step> withWaits = new ArrayList<>();
            long floor = Math.max(WAIT_FLOOR_NANOS, durationNanos() / 10L);
            long cursor = start;
            int index = 0;
            for (Step child : children) {
                if (child.start - cursor >= floor) withWaits.add(new Step(key + ":wait" + index++, cursor, child.start));
                withWaits.add(child);
                cursor = Math.max(cursor, child.end);
            }
            if (!open && end - cursor >= floor) withWaits.add(new Step(key + ":wait" + index, cursor, end));
            children.clear();
            children.addAll(withWaits);
        }

        /** Offsets from the chain's start, and the stall: the longest step or wait under each span. */
        void markStall(long chainStart) {
            offset = start - chainStart;
            Step longest = null;
            for (Step child : children) {
                child.markStall(chainStart);
                if (longest == null || child.durationNanos() > longest.durationNanos()) longest = child;
            }
            if (longest != null && children.size() > 1) longest.stall = true;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Step step && step.key.equals(key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    /** Twisty, name, when it started in its chain, and how long it took. */
    private final class StepRenderer implements TreeRenderer<Step> {

        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            row.addClass(ROW_CLASS);
            UIElement twisty = new UIElement();
            twisty.addClass(TWISTY_CLASS);
            twisty.onMouseDown.attachListener((element, event) -> {
                int index = tree.indexOfRowElement(row);
                TreeRow<Step> at = index < 0 ? null : tree.rowAt(index);
                if (at == null || !at.expandable()) return;
                event.stopPropagation();
                event.preventDefault();
                tree.requestToggleAt(index);
            }, false, false);
            row.append(twisty);
            UIText name = new UIText("");
            name.addClass(NAME_CLASS);
            row.append(name);
            UIText starts = new UIText("");
            starts.addClass(FIGURE_CLASS);
            row.append(starts);
            UIText took = new UIText("");
            took.addClass(FIGURE_CLASS);
            row.append(took);
            return row;
        }

        @Override
        public void bind(Step item, TreeRow<Step> row, int index, UIElement template) {
            List<UIElement> parts = template.children();
            if (parts.size() < 4) return;
            set(parts.get(1), item.isWait() ? "— waited —" : item.name);
            set(parts.get(2), row.depth() == 0 ? "" : "+" + millis(item.offset));
            set(parts.get(3), item.open ? millis(item.durationNanos()) + " (open)" : millis(item.durationNanos()));
            toggle(template, WAIT_CLASS, item.isWait());
            toggle(template, STALL_CLASS, item.stall);
        }

        private void set(UIElement part, String text) {
            UIText label = (UIText) part;
            if (!Objects.equals(label.getText(), text)) label.setText(text);
        }

        private void toggle(UIElement element, String name, boolean on) {
            if (on == element.hasClass(name)) return;
            if (on) element.addClass(name);
            else element.removeClass(name);
        }

        @Override
        public String copyTextFor(Step item) {
            return (item.isWait() ? "waited" : item.name) + "  " + millis(item.durationNanos());
        }
    }

    private static String millis(long nanos) {
        double ms = nanos / 1_000_000d;
        return ms >= 100d ? String.format("%.0f ms", ms) : String.format("%.2f ms", ms);
    }
}
