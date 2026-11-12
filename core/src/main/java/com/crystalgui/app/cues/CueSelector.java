package com.crystalgui.app.cues;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.desktop.app.AppContext;
import com.crystalgui.desktop.app.AppWindow;
import com.crystalgui.desktop.app.WindowSpec;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.composite.SearchField;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.text.UIText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The cue picker, an application: a window with a column per namespace, each its cues as a tree of their paths'
 * folders. A press on a cue plays it where the viewer stands; on a folder, folds it. The cue playing is lit, and the
 * folders over its path. Styled by {@code ua/cues.css}.
 *
 * <pre>{@code
 * // Installed with the rest of the jar's applications; a desktop launches it like any other:
 * desktop.applications().launch(CueSelector.KIND, workspace);
 * }</pre>
 *
 * <ul>
 *   <li>It is a view of {@link CueSelection#client()}, which lists the registry's cues, loads and plays the one
 *       pressed and tracks its hit; a HUD reads the same selection. A cue that fails to load is named in the window.</li>
 *   <li>Hits play at {@link CueSelection#at} or, unset, where the viewer stands.</li>
 * </ul>
 */
public final class CueSelector {

    /** The application's id. */
    public static final String ID = "crystalgui:cue-selector";

    /** The picker as an application: a window per launch. */
    public static final ApplicationKind KIND = ApplicationKind.of(ID, "Cues")
            .icon(ID)
            .keywords("cues", "hits", "kit")
            .windowFraction(0.5f)
            .standalone()
            .launch(Instance::new);

    private CueSelector() {
    }

    /** A folder of a cue path, or a cue; or both, a cue with cues under it. */
    private static final class Node {
        /** The dimmed folders a compacted chain runs through before {@link #name}, each ending in a slash. */
        String prefix = "";
        String name, path;
        List<Node> children = new ArrayList<>();
        /** The cue's id, {@code namespace:path/to/cue}; null for a folder alone. */
        String cue;
        /** The cues at and under this node. */
        int count;

        Node(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    private static void sort(List<Node> level) {
        level.sort((a, b) -> a.name.compareTo(b.name));
        for (Node n : level) sort(n.children);
    }

    /** A copy of {@code n} keeping what matches {@code q}: a node whose name does keeps its whole subtree; null if none. */
    private static Node filter(Node n, String q, boolean all) {
        boolean keep = all || n.name.toLowerCase(Locale.ROOT).contains(q);
        Node c = new Node(n.name, n.path);
        c.cue = n.cue;
        for (Node child : n.children) {
            Node k = filter(child, q, keep);
            if (k != null) c.children.add(k);
        }
        return keep || !c.children.isEmpty() ? c : null;
    }

    /** Folds each chain of folders with one child into a row, as VS Code's compact folders do, and counts the cues. */
    private static void compact(Node n) {
        for (Node child : n.children) compact(child);
        while (n.cue == null && n.children.size() == 1) {
            Node only = n.children.get(0);
            n.prefix = n.prefix + n.name + "/" + only.prefix;
            n.name = only.name;
            n.path = only.path;
            n.cue = only.cue;
            n.children = only.children;
        }
        n.count = n.cue == null ? 0 : 1;
        for (Node child : n.children) n.count += child.count;
    }

    private static void folders(List<Node> level, List<Node> out) {
        for (Node n : level) {
            if (n.children.isEmpty()) continue;
            out.add(n);
            folders(n.children, out);
        }
    }

    /** One namespace's tree: every cue it holds, and the compacted part of them the filter leaves. */
    private static final class Column {
        final List<Node> full;
        List<Node> shown = new ArrayList<>();
        TreeView<Node> tree;
        UIElement panel, chip;
        UIText chipCount;
        /** Whether the kit's chip is pressed in; an off kit has no column. */
        boolean on;
        /** The cues the filter leaves. */
        int matches;

        Column(List<Node> full) {
            this.full = full;
        }

        void show(String query) {
            List<Node> next = new ArrayList<>();
            int total = 0;
            for (Node n : full) {
                Node k = filter(n, query, query.isEmpty());
                if (k == null) continue;
                compact(k);
                next.add(k);
                total += k.count;
            }
            shown = next;
            matches = total;
        }
    }

    private static final class Row extends UIElement {
        final UIElement line = new UIElement();
        final UIElement twisty = new UIElement();
        final UIText prefix = new UIText("");
        final UIText label = new UIText("");
        final UIText count = new UIText("");
        Node node;

        Row() {
            addClass("cue-selector-row");
            twisty.addClass("__twisty__");
            prefix.addClass("cue-selector-prefix");
            count.addClass("cue-selector-count");
            line.addClass("cue-selector-line");
            line.append(twisty, prefix, label, count);
            append(line);
        }
    }

    /** One window: the registry's cues at launch, and the world tick that plays what a press asked for. */
    private static final class Instance implements Application {

        private final AppWindow window;
        private final Map<String, Column> columns = new LinkedHashMap<>();
        private final CueSelection selection = CueSelection.client();
        private final SearchField search = new SearchField();
        private final UIText status = new UIText("");
        private final CgTickFunction tick;
        private int seen = -1;
        private int shownTenths = -1;

        /** Kits open at launch; the rest are one chip press away, so columns stay wide however many kits there are. */
        private static final int MAX_OPEN = 4;
        private static final String HINT = "Click a cue to play it  |  a folder to fold it";

        Instance(AppContext app) {
            Map<String, List<Node>> roots = new LinkedHashMap<>();
            for (String id : selection.refresh().ids()) add(roots, id);
            for (Map.Entry<String, List<Node>> e : roots.entrySet()) {
                sort(e.getValue());
                Column column = new Column(e.getValue());
                column.show("");
                columns.put(e.getKey(), column);
            }
            window = app.windows().open(WindowSpec.titled("Cues").key(ID).content(build()));
            tick = CgWorld.client().schedule().add(CgTickGroup.SUBMIT, ID, dt -> sync());
        }

        /** Files {@code id}, as {@code namespace:path/to/cue}, under its namespace's folders. */
        private static void add(Map<String, List<Node>> roots, String id) {
            int colon = id.indexOf(':');
            List<Node> level = roots.computeIfAbsent(id.substring(0, colon), ns -> new ArrayList<>());
            Node node = null;
            String path = "";
            for (String part : id.substring(colon + 1).split("/")) {
                path = path.isEmpty() ? part : path + "/" + part;
                node = null;
                for (Node n : level) if (n.name.equals(part)) node = n;
                if (node == null) level.add(node = new Node(part, path));
                level = node.children;
            }
            node.cue = id;
        }

        private UIElement build() {
            UIElement screen = new UIElement();
            screen.addClass("cue-selector");
            search.addClass("cue-selector-search");
            search.setPlaceholder("Filter cues");
            search.field().setPlaceholderShownUnfocused(true);
            search.onQueryChanged.connect(this::filter);
            ScrollerView chipScroll = new ScrollerView();
            chipScroll.addClass("cue-selector-chip-scroll");
            UIElement chips = new UIElement();
            chips.addClass("cue-selector-chips");
            chipScroll.append(chips);
            UIElement panels = new UIElement();
            panels.addClass("cue-selector-columns");
            int index = 0;
            for (Map.Entry<String, Column> ns : columns.entrySet()) {
                Column column = ns.getValue();
                column.on = index++ < MAX_OPEN;
                chips.append(chip(ns.getKey(), column));
                panels.append(panel(ns.getKey(), column));
            }
            status.addClass("cue-selector-status");
            screen.append(search, chipScroll, panels, status);
            applyVisibility();
            return screen;
        }

        /** A kit's switch: pressed, its column shows. Beside the name, the cues it holds or, filtered, the ones that match. */
        private UIElement chip(String namespace, Column column) {
            UIElement chip = new UIElement();
            chip.addClass("cue-selector-chip");
            UIText name = new UIText(namespace);
            UIText count = new UIText("");
            count.addClass("cue-selector-chip-count");
            chip.append(name, count);
            chip.onMouseDown.attachListener((element, event) -> {
                column.on = !column.on;
                applyVisibility();
                event.stopPropagation();
            }, false, true);
            column.chip = chip;
            column.chipCount = count;
            return chip;
        }

        /** Shows the columns whose chip is pressed and, under a filter, that still hold a match. */
        private void applyVisibility() {
            boolean filtering = !search.getText().trim().isEmpty();
            for (Column column : columns.values()) {
                boolean empty = filtering && column.matches == 0;
                column.chip.toggleClass("cue-selector-chip-on", column.on);
                column.chip.toggleClass("cue-selector-chip-empty", empty);
                column.chipCount.setText(String.valueOf(column.matches));
                column.panel.toggleClass("cue-selector-hidden", !column.on || empty);
            }
        }

        private UIElement panel(String namespace, Column column) {
            UIElement panel = new UIElement();
            panel.addClass("cue-selector-panel");
            column.panel = panel;
            UIText header = new UIText(namespace);
            header.addClass("cue-selector-header");
            TreeView<Node> tree = new TreeView<>(new TreeDataSource<>() {
                @Override public List<Node> roots() { return column.shown; }
                @Override public List<Node> children(Node parent) { return parent.children; }
                @Override public boolean hasChildren(Node item) { return !item.children.isEmpty(); }
            });
            tree.addClass("cue-selector-tree");
            tree.setItemHeight(22f);
            // Only the twisty folds: a double press on a cue is two plays, not a fold
            tree.setFoldsOnActivate(false);
            tree.setRenderer(new Rows(tree));
            column.tree = tree;
            expandAll(column);
            panel.append(header, tree);
            return panel;
        }

        private static void expandAll(Column column) {
            List<Node> open = new ArrayList<>();
            folders(column.shown, open);
            column.tree.setExpandedItems(open);
        }

        /** The box's query narrowed every column; what it leaves is opened, so a match is never hidden in a fold. */
        private void filter() {
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            for (Column column : columns.values()) {
                column.show(query);
                expandAll(column);
                column.tree.refresh();
            }
            applyVisibility();
        }

        private boolean lit(Node node) {
            String current = selection.current();
            if (node.children.isEmpty() || current.isEmpty()) return false;
            return current.substring(current.indexOf(':') + 1).startsWith(node.path + "/");
        }

        /** On the world's tick: redraws the rows when the selection changed and keeps the status line. */
        private void sync() {
            if (seen != selection.version()) {
                seen = selection.version();
                shownTenths = -1;
                for (Column column : columns.values()) column.tree.refresh();
            }
            int tenths = (int) (shown() * 10) + (finished() ? 1000 : 0);
            if (tenths != shownTenths) {
                shownTenths = tenths;
                status.setText(statusLine());
            }
        }

        private boolean finished() {
            return selection.duration() > 0 && selection.age() >= selection.duration();
        }

        /** The age, held at the duration once the hit has run its length. */
        private double shown() {
            double duration = selection.duration();
            return duration > 0 ? Math.min(selection.age(), duration) : selection.age();
        }

        private String statusLine() {
            if (selection.state() == CueSelection.State.FAILED) return selection.failure();
            if (selection.current().isEmpty()) return HINT;
            if (selection.state() == CueSelection.State.LOADING) return "loading";
            double duration = selection.duration();
            if (finished()) return String.format(Locale.ROOT, "done  |  %.1f s  |  fires %d", duration, selection.fires());
            if (duration <= 0) return String.format(Locale.ROOT, "playing  |  %.1f s  |  fires %d", selection.age(), selection.fires());
            return String.format(Locale.ROOT, "playing  |  %.1f / %.1f s  |  fires %d", shown(), duration, selection.fires());
        }

        private final class Rows implements TreeRenderer<Node> {
            private final TreeView<Node> tree;

            Rows(TreeView<Node> tree) {
                this.tree = tree;
            }

            @Override
            public UIElement createTemplate() {
                Row row = new Row();
                // A press acts, as a game's list does: a cue plays, a folder folds. A cue with cues under it plays, and
                // its chevron folds it
                row.twisty.onMouseDown.attachListener((element, event) -> {
                    if (row.node != null && !row.node.children.isEmpty()) tree.requestToggle(row.node);
                    event.stopPropagation();
                }, false, true);
                row.onMouseDown.attachListener((element, event) -> {
                    Node node = row.node;
                    if (node == null) return;
                    if (node.cue != null) selection.play(node.cue);
                    else if (!node.children.isEmpty()) tree.requestToggle(node);
                    event.stopPropagation();
                }, false, true);
                return row;
            }

            @Override
            public void bind(Node item, TreeRow<Node> row, int index, UIElement template) {
                Row view = (Row) template;
                view.node = item;
                view.prefix.setText(item.prefix);
                view.label.setText(item.name);
                view.count.setText(item.children.isEmpty() ? "" : String.valueOf(item.count));
                boolean current = item.cue != null && item.cue.equals(selection.current());
                view.toggleClass("cue-selector-current", current);
                view.toggleClass("cue-selector-folder", item.cue == null);
                view.toggleClass("cue-selector-lit", lit(item));
                view.toggleClass("cue-selector-failed", current && selection.state() == CueSelection.State.FAILED);
            }
        }

        @Override public ApplicationKind kind() { return KIND; }
        @Override public AppWindow mainWindow() { return window; }
        @Override public boolean open(Resource resource) { return false; }

        @Override
        public void activate() {
            window.activate();
        }

        @Override
        public void dispose() {
            tick.close();
            window.destroy();
        }
    }
}
