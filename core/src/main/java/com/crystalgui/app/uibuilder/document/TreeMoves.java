package com.crystalgui.app.uibuilder.document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.crystalgui.text.DerivedNames;
import com.crystalgui.ui.dom.UIElement;

/**
 * The edits that move nodes to a place in the tree, or put copies there — what a drop commits.
 *
 * <pre>{@code
 * List<BuilderEdit> edits = TreeMoves.move(container, 2, selection);
 * document.applyAll("move", edits);   // one undo step, however many nodes
 *
 * List<BuilderEdit> copies = TreeMoves.duplicate(document, container, 2, selection);
 * document.applyAll("duplicate", copies);
 * }</pre>
 *
 * <p>{@code index} is where a drop inserts among {@code target}'s children <b>as they are now</b>, before
 * anything is moved. Ported from GrapesJS's {@code ComponentSorter.handleNodeAddition} (BSD-3-Clause): the
 * nodes land in document order from that index, a node moved later in its own parent takes one off it —
 * removing the node shifts everything after it — and a node already where it would land is left alone.</p>
 *
 * <ul>
 *   <li>A node inside another of the nodes is dropped: it travels with its ancestor.</li>
 *   <li>Nothing checks that the move is allowed; that is {@link TreeDropRules}, before this is asked.</li>
 * </ul>
 */
public final class TreeMoves {

    private TreeMoves() {
    }

    /** The {@link BuilderEdit.Move}s that put {@code nodes} at {@code index} in {@code target}; empty when nothing moves. */
    public static List<BuilderEdit> move(UIElement target, int index, Collection<UIElement> nodes) {
        Map<UIElement, List<UIElement>> lists = new IdentityHashMap<>();
        Map<UIElement, UIElement> parents = new IdentityHashMap<>();
        List<BuilderEdit> edits = new ArrayList<>();
        int at = index;
        for (UIElement node : outermost(nodes)) {
            UIElement from = parents.computeIfAbsent(node, UIElement::parentElement);
            if (from == null) continue;
            List<UIElement> fromList = lists.computeIfAbsent(from, TreeMoves::childrenOf);
            List<UIElement> toList = lists.computeIfAbsent(target, TreeMoves::childrenOf);
            int fromIndex = fromList.indexOf(node);
            int landing = Math.max(0, Math.min(at, toList.size()));
            if (from == target && fromIndex < landing) landing--;
            if (from == target && fromIndex == landing) {
                at = landing + 1;
                continue;
            }
            fromList.remove(fromIndex);
            toList.add(landing, node);
            parents.put(node, target);
            edits.add(new BuilderEdit.Move(node, from, fromIndex, target, landing));
            at = landing + 1;
        }
        return edits;
    }

    /**
     * {@link BuilderEdit.Insert}s of copies of {@code nodes} at {@code index} in {@code target}, the
     * originals untouched. Each edit's {@code node()} is its copy.
     *
     * <p>Every id in a copy is made free against the document and against the other copies — a shared id
     * would style both nodes through one rule and make {@code #id} find either.</p>
     */
    public static List<BuilderEdit> duplicate(UiBuilderDocument document, UIElement target, int index,
                                              Collection<UIElement> nodes) {
        Set<String> taken = new HashSet<>();
        collectIds(document.root(), taken);
        List<BuilderEdit> edits = new ArrayList<>();
        int at = Math.max(0, Math.min(index, target.children().size()));
        for (UIElement node : outermost(nodes)) {
            UIElement copy = document.copyOf(node);
            freeIds(copy, taken);
            edits.add(new BuilderEdit.Insert(target, copy, at++));
        }
        return edits;
    }

    /** {@code nodes} in document order, without any that sit inside another of them. */
    public static List<UIElement> outermost(Collection<UIElement> nodes) {
        Set<UIElement> all = Collections.newSetFromMap(new IdentityHashMap<>());
        all.addAll(nodes);
        List<UIElement> kept = new ArrayList<>();
        for (UIElement node : nodes) {
            if (!hasAncestorIn(node, all) && !kept.contains(node)) kept.add(node);
        }
        Map<UIElement, List<Integer>> paths = new HashMap<>();
        for (UIElement node : kept) paths.put(node, pathOf(node));
        kept.sort((a, b) -> compare(paths.get(a), paths.get(b)));
        return kept;
    }

    private static boolean hasAncestorIn(UIElement node, Set<UIElement> nodes) {
        for (UIElement at = node.parentElement(); at != null; at = at.parentElement()) {
            if (nodes.contains(at)) return true;
        }
        return false;
    }

    /** The child indices from the root down to {@code node}. */
    private static List<Integer> pathOf(UIElement node) {
        List<Integer> path = new ArrayList<>();
        for (UIElement at = node; at.parentElement() != null; at = at.parentElement()) {
            path.add(0, at.parentElement().indexOf(at));
        }
        return path;
    }

    private static int compare(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int step = Integer.compare(a.get(i), b.get(i));
            if (step != 0) return step;
        }
        return Integer.compare(a.size(), b.size());
    }

    private static List<UIElement> childrenOf(UIElement parent) {
        return new ArrayList<>(parent.children());
    }

    private static void collectIds(UIElement node, Set<String> taken) {
        if (!node.id().isEmpty()) taken.add(node.id());
        for (UIElement child : node.children()) collectIds(child, taken);
    }

    private static void freeIds(UIElement node, Set<String> taken) {
        if (!node.id().isEmpty()) {
            String free = DerivedNames.derive(node.id(), taken, Set.of());
            taken.add(free);
            if (!free.equals(node.id())) node.setId(free);
        }
        for (UIElement child : node.children()) freeIds(child, taken);
    }
}
