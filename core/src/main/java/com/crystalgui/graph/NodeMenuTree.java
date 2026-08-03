package com.crystalgui.graph;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a flat list of {@link NodeTypeRegistry.Offer}s into the <b>category tree</b> the create menu
 * browses, by splitting {@link NodeType#category()} on {@code /}.
 *
 * <p>Headless and static, for the same reason {@link NodeTypeRegistry} is: this is the shape of a library,
 * not the shape of a widget. A server assembling a palette, a test, and the menu all want the same answer,
 * and none of them should need a GL context to get it.</p>
 *
 * <h3>Two shapes, and which one you get is decided by the query</h3>
 * <ul>
 *   <li>{@link #categorised} — folders, as Unity's Create Node window shows them when you are
 *       <em>browsing</em>. Six types fit a flat list; six hundred do not, which is the whole reason this
 *       class exists.</li>
 *   <li>{@link #flat} — no folders at all, which is what every search box in every editor does. A result
 *       set is <em>ranked</em>, not filed: burying three matches under two levels of collapsed folder is
 *       strictly worse than listing them, and it is what the user was trying to avoid by typing.</li>
 * </ul>
 *
 * <h3>Folders sort before leaves</h3>
 * <p>Both alphabetically, and folders first — Unity, Windows Explorer, VS Code's explorer and Finder all
 * agree, and the alternative interleaves two kinds of thing that behave differently on click.</p>
 */
public final class NodeMenuTree {

    /** The separator inside {@link NodeType#category()} — {@code "Input/Geometry"} is two levels. */
    public static final char SEPARATOR = '/';

    private NodeMenuTree() {
    }

    /**
     * One row of the menu: either a category (children, no offer) or a choosable entry (an offer, no
     * children).
     *
     * <p><b>Identity is the path, not the contents.</b> This is not a micro-optimisation — it is what makes
     * expansion survive typing. {@code TreeView} tracks which nodes are open in a {@code Set<T>} keyed on
     * {@code equals}, and the tree here is rebuilt wholesale on every keystroke, so a structural
     * {@code equals} over the child list would be both O(subtree) per lookup <em>and</em> would collapse
     * every folder the moment its contents changed. A path is stable, cheap, and unique by construction.</p>
     *
     * @param label    what is drawn — the last path segment for a category, the offer's label for a leaf
     * @param path     the full slash-joined path from the root, unique across the tree
     * @param offer    what choosing this row creates, or null when it is a category
     * @param children a category's contents, already sorted; always empty for a leaf
     */
    public static final class Node {

        private final String label;
        private final String path;
        @Nullable
        private final NodeTypeRegistry.Offer offer;
        private final List<Node> children;

        Node(String label, String path, @Nullable NodeTypeRegistry.Offer offer, List<Node> children) {
            this.label = label;
            this.path = path;
            this.offer = offer;
            this.children = children;
        }

        public String label() {
            return label;
        }

        public String path() {
            return path;
        }

        @Nullable
        public NodeTypeRegistry.Offer offer() {
            return offer;
        }

        public List<Node> children() {
            return children;
        }

        /** A folder. Note this is {@code offer == null} rather than {@code !children.isEmpty()} — an empty
         * category is still a category, and answering from the child list would silently turn one into a
         * leaf that does nothing when chosen. */
        public boolean isCategory() {
            return offer == null;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Node)) return false;
            return path.equals(((Node) other).path);
        }

        @Override
        public int hashCode() {
            return path.hashCode();
        }

        @Override
        public String toString() {
            return path;
        }
    }

    // ── The two shapes ──────────────────────────────────────────────────────

    /**
     * Groups {@code offers} under their categories. An offer whose type declares no category becomes a
     * root-level leaf, which is what makes a library that never bothered with categories degrade into
     * exactly the flat list it had before rather than into a folder called {@code ""}.
     */
    public static List<Node> categorised(List<NodeTypeRegistry.Offer> offers) {
        Folder root = new Folder("", "");
        for (NodeTypeRegistry.Offer offer : offers) {
            folderFor(root, offer.type().category()).leaves.add(offer);
        }
        return root.freeze().children();
    }

    /**
     * Every offer as a root-level leaf, <b>in the order given</b>, each carrying its category for display
     * — the search-result shape.
     *
     * <p><b>Order is the caller's, and that is the point.</b> This used to re-sort alphabetically, which
     * silently discarded whatever ranking produced the list: {@code NodeTypeRegistry} returns offers best
     * first, and the menu pre-selects row 0, so re-sorting here is what made {@code vec} + Enter create
     * <b>Cross Product</b> rather than {@code Vector 2}. A function that reorders its input is the wrong
     * place to decide relevance.</p>
     *
     * <p>The category rides along because a flat result hides <em>why</em> a row matched. Ten of thirteen
     * rows for {@code vec} matched only through {@code Math/Vector}, and dropping the category (this
     * passed {@code ""}) left them looking arbitrary. The menu draws it dimmed after the label.</p>
     */
    public static List<Node> ranked(List<NodeTypeRegistry.Offer> offers) {
        List<Node> leaves = new ArrayList<>(offers.size());
        for (NodeTypeRegistry.Offer offer : offers) leaves.add(leaf(offer, ""));
        return Collections.unmodifiableList(leaves);
    }

    /** @deprecated ranking belongs to whoever searched — use {@link #ranked}. Kept so an existing caller
     * that genuinely wants alphabetical says so. */
    @Deprecated
    public static List<Node> flat(List<NodeTypeRegistry.Offer> offers) {
        List<NodeTypeRegistry.Offer> sorted = new ArrayList<>(offers);
        sorted.sort((a, b) -> compareLabels(a.label(), b.label()));
        return ranked(sorted);
    }

    // ── Counting, for the auto-expand rule ──────────────────────────────────

    /** Total choosable entries anywhere in {@code roots} — categories are not counted, only what they
     * contain. What the menu compares against its auto-expand threshold. */
    public static int leafCount(List<Node> roots) {
        int total = 0;
        for (Node node : roots) {
            total += node.isCategory() ? leafCount(node.children()) : 1;
        }
        return total;
    }

    /** Every category in {@code roots}, depth-first — what the menu opens when the tree is small enough
     * that folders would be pure friction. */
    public static List<Node> categoriesIn(List<Node> roots) {
        List<Node> found = new ArrayList<>();
        collectCategories(roots, found);
        return found;
    }

    private static void collectCategories(List<Node> nodes, List<Node> out) {
        for (Node node : nodes) {
            if (!node.isCategory()) continue;
            out.add(node);
            collectCategories(node.children(), out);
        }
    }

    // ── Building ────────────────────────────────────────────────────────────

    /** Walks (creating as it goes) the folder chain named by a {@code a/b/c} category path. */
    private static Folder folderFor(Folder root, String category) {
        Folder current = root;
        for (String segment : split(category)) {
            Folder parent = current;
            current = parent.subFolders.computeIfAbsent(segment,
                    name -> new Folder(name, join(parent.path, name)));
        }
        return current;
    }

    private static List<String> split(String category) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= category.length(); i++) {
            if (i != category.length() && category.charAt(i) != SEPARATOR) continue;
            String segment = category.substring(start, i).trim();
            // Skips empties, so "Math//Basic", a trailing slash and a blank category are all handled by
            // the same line rather than by three guards at the call sites.
            if (!segment.isEmpty()) segments.add(segment);
            start = i + 1;
        }
        return segments;
    }

    private static String join(String parentPath, String segment) {
        return parentPath.isEmpty() ? segment : parentPath + SEPARATOR + segment;
    }

    private static Node leaf(NodeTypeRegistry.Offer offer, String parentPath) {
        String label = offer.label();
        return new Node(label, join(parentPath, label), offer, Collections.emptyList());
    }

    /**
     * A type's category as a reader sees it — {@code math/vector} becomes {@code [Math, Vector]}.
     *
     * <p><b>Segments, not one joined string</b>, and the separator is a drawn shape rather than a
     * character. Two reasons, and both bit:</p>
     * <ul>
     *   <li>The bundled {@code MinecraftRegular.otf} has no {@code U+25B8}, and a missing glyph draws a
     *       <b>blank advance</b> rather than failing — so a joined {@code Math ▸ Vector} rendered as
     *       "Math&nbsp;&nbsp;&nbsp;Vector", which reads as a spacing bug. {@code UIText}'s own ellipsis
     *       fallback documents the same trap for {@code U+2026}. {@code CgUiShape}'s
     *       {@code triangle-right} draws it with no font involved.</li>
     *   <li>Segments make the match tint <b>exact for free</b>. Highlighting a joined string means
     *       offsets computed against text whose separators may differ in length from the source's, and
     *       the tint creeps sideways after the first segment. Matching each segment on its own has no
     *       offset arithmetic to get wrong.</li>
     * </ul>
     */
    public static List<String> categorySegments(String category) {
        if (category == null || category.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String segment : split(category)) {
            if (segment.isEmpty()) continue;
            out.add(Character.toUpperCase(segment.charAt(0)) + segment.substring(1));
        }
        return Collections.unmodifiableList(out);
    }

    private static int compareLabels(String a, String b) {
        int byName = a.compareToIgnoreCase(b);
        // Ties broken case-sensitively rather than left to the sort's stability, so the order does not
        // depend on the order the library happened to be registered in.
        return byName != 0 ? byName : a.compareTo(b);
    }

    /** Mutable while grouping; {@link #freeze()} produces the immutable {@link Node} tree. */
    private static final class Folder {

        private final String name;
        private final String path;
        private final Map<String, Folder> subFolders = new LinkedHashMap<>();
        private final List<NodeTypeRegistry.Offer> leaves = new ArrayList<>();

        Folder(String name, String path) {
            this.name = name;
            this.path = path;
        }

        Node freeze() {
            List<Node> folders = new ArrayList<>(subFolders.size());
            for (Folder sub : subFolders.values()) folders.add(sub.freeze());
            folders.sort((a, b) -> compareLabels(a.label(), b.label()));

            List<NodeTypeRegistry.Offer> sortedLeaves = new ArrayList<>(leaves);
            sortedLeaves.sort((a, b) -> compareLabels(a.label(), b.label()));

            List<Node> children = new ArrayList<>(folders.size() + sortedLeaves.size());
            children.addAll(folders);
            for (NodeTypeRegistry.Offer offer : sortedLeaves) children.add(leaf(offer, path));

            return new Node(name, path, null, Collections.unmodifiableList(children));
        }
    }

    /** Depth-first path lookup, for a caller restoring expansion or a test naming a node directly. */
    @Nullable
    public static Node find(List<Node> roots, String path) {
        for (Node node : roots) {
            if (Objects.equals(node.path(), path)) return node;
            Node inside = find(node.children(), path);
            if (inside != null) return inside;
        }
        return null;
    }

    /** Every leaf anywhere in {@code roots}, in display order. */
    public static List<Node> leavesIn(List<Node> roots) {
        List<Node> found = new ArrayList<>();
        collectLeaves(roots, found);
        return found;
    }

    private static void collectLeaves(List<Node> nodes, List<Node> out) {
        for (Node node : nodes) {
            if (node.isCategory()) collectLeaves(node.children(), out);
            else out.add(node);
        }
    }
}
