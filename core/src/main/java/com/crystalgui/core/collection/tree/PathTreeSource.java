package com.crystalgui.core.collection.tree;

import com.crystalgui.core.collection.tree.TreeDataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A tree over <b>dotted ids</b> — {@code editor.general.autoImport} becomes {@code editor › general}.
 *
 * <h3>It knows nothing about what the ids mean</h3>
 *
 * <p>Settings are the first consumer and not the shape of this class: a prefix-grouped command palette, a
 * package explorer and a property browser all want the same thing. So the two questions that <em>are</em>
 * domain knowledge are asked of the caller:</p>
 *
 * <ul>
 *   <li>{@code isNode} — which levels get a node of their own. Everything below the deepest node is that
 *       node's business, not the tree's.</li>
 *   <li>{@code titleOf} — what a level is called. Falls back to prettifying the segment.</li>
 * </ul>
 *
 * <p>That is what keeps the tree shape <b>authored rather than derived</b>, which matters more than it
 * sounds: with a purely derived tree, one deeply-named id silently grows a new node in somebody's
 * navigation. Here the caller says where the tree stops.</p>
 *
 * <h3>Items are the paths themselves</h3>
 *
 * <p>{@code "editor.general"}, not a wrapper object — so a selection is a value that can be stored in a
 * session record, compared, and handed straight to whatever builds the page. The alternative is an
 * identity-keyed node type that cannot survive a rebuild.</p>
 */
public final class PathTreeSource implements TreeDataSource<String> {

    public static final char SEPARATOR = '.';

    private final Predicate<String> isNode;
    private final Function<String, String> titleOf;

    /** parent path → its child node paths, in first-seen order. Root is under {@code ""}. */
    private final Map<String, List<String>> children = new LinkedHashMap<>();

    /** Every id supplied, so a consumer can ask what belongs to a node. */
    private final List<String> ids = new ArrayList<>();

    public PathTreeSource(Collection<String> ids, Predicate<String> isNode,
                          Function<String, String> titleOf) {
        this.isNode = isNode;
        this.titleOf = titleOf;
        setIds(ids);
    }

    /** Rebuilds from a new id set — a mod loading, a filter changing what exists. */
    public void setIds(Collection<String> newIds) {
        ids.clear();
        children.clear();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : newIds) {
            if (id == null || id.isEmpty()) continue;
            ids.add(id);
            // Every ANCESTOR that the caller calls a node, deepest last. The id's own last segment is the
            // leaf value and never a node itself -- `editor.general.autoImport` contributes `editor` and
            // `editor.general`, not a node named after the setting.
            String path = "";
            for (String segment : id.split("[.]")) {
                String candidate = path.isEmpty() ? segment : path + SEPARATOR + segment;
                if (!isNode.test(candidate)) break;
                if (seen.add(candidate)) children.computeIfAbsent(path, key -> new ArrayList<>())
                        .add(candidate);
                path = candidate;
            }
        }
    }

    @Override
    public List<String> roots() {
        return new ArrayList<>(children.getOrDefault("", List.of()));
    }

    @Override
    public List<String> children(String parent) {
        return new ArrayList<>(children.getOrDefault(parent, List.of()));
    }

    @Override
    public boolean hasChildren(String item) {
        return !children.getOrDefault(item, List.of()).isEmpty();
    }

    /** What to draw for a node. */
    public String title(String path) {
        String given = titleOf.apply(path);
        return given != null && !given.isEmpty() ? given : prettify(lastSegment(path));
    }

    /** Every id whose nearest enclosing node is exactly {@code path} — what that node's page shows. */
    public List<String> idsDirectlyUnder(String path) {
        List<String> found = new ArrayList<>();
        for (String id : ids) {
            if (path.equals(nodeOf(id))) found.add(id);
        }
        return found;
    }

    /**
     * The deepest node path enclosing {@code id}, or {@code ""} when none does.
     *
     * <p>This is the whole placement rule in one method: an id belongs to the last ancestor the caller
     * calls a node, and everything below that is section structure inside its page.</p>
     */
    public String nodeOf(String id) {
        String path = "";
        String deepest = "";
        for (String segment : id.split("[.]")) {
            path = path.isEmpty() ? segment : path + SEPARATOR + segment;
            if (!isNode.test(path)) break;
            deepest = path;
        }
        return deepest;
    }

    /** The part of {@code id} below its node — the section path, empty when it sits directly on the page. */
    public String sectionOf(String id) {
        String node = nodeOf(id);
        if (node.isEmpty()) return "";
        String rest = id.length() > node.length() ? id.substring(node.length() + 1) : "";
        int lastDot = rest.lastIndexOf(SEPARATOR);
        // The last segment is the id's own name, never a section.
        return lastDot < 0 ? "" : rest.substring(0, lastDot);
    }

    public static String lastSegment(String path) {
        int dot = path.lastIndexOf(SEPARATOR);
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /** {@code uiOptions} reads as "UI options" — a segment is an identifier, not a heading. */
    public static String prettify(String segment) {
        if (segment == null || segment.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(segment.charAt(i - 1))) {
                out.append(' ').append(Character.toLowerCase(c));
            } else {
                out.append(i == 0 ? Character.toUpperCase(c) : c);
            }
        }
        return out.toString();
    }
}
