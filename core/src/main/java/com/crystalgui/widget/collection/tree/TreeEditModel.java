package com.crystalgui.widget.collection.tree;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgModifiers;

/**
 * What a tree's items are, and how to change them — the half of {@link TreeEditing} only its owner can write.
 *
 * <p>The kit decides <em>what</em> a gesture means: which items, which parent and index, move or copy, which
 * name. The model <em>performs</em> it, synchronously or not, as one undo step per call.</p>
 *
 * <pre>{@code
 * final class NodeModel implements TreeEditModel<Node> {
 *     public boolean isOrdered()                   { return true; }
 *     public Node parentOf(Node node)              { return node.parent(); }
 *     public int indexOf(Node node)                { return node.parent().children().indexOf(node); }
 *     public boolean isContainer(Node node)        { return node.takesChildren(); }
 *     public boolean canEdit(Node node)            { return node.parent() != null; }
 *     public String nameOf(Node node)              { return node.name(); }
 *     public void move(List<Node> nodes, Target<Node> to) { document.apply(moves(nodes, to)); }
 *     public void copy(List<Node> nodes, Target<Node> to) { document.apply(copies(nodes, to)); }
 *     public void delete(List<Node> nodes)         { document.apply(removals(nodes)); }
 *     public void rename(Node node, String name)   { document.apply(new Rename(node, name)); }
 * }
 * }</pre>
 *
 * <pre>{@code
 * // a sorted, lazily listed tree: drops land INTO a folder, and a file means its folder
 * public boolean isOrdered() { return false; }
 * public int indexOf(Path path) { return -1; }
 * }</pre>
 *
 * <ul>
 *   <li>Every list handed in is in tree order with no item inside another.</li>
 *   <li>{@link #canEdit} gates cut, delete, rename and dragging; {@link #canDrop} gates where a drop and a
 *       paste may land. The kit asks both before calling a verb.</li>
 * </ul>
 */
public interface TreeEditModel<T> {

    /** Where a move, copy or paste lands: into {@code parent} at child {@code index}, -1 for the model's choice. */
    record Target<T>(T parent, int index) {
    }

    /**
     * Whether children keep an order the user decides — a document's — rather than one the model sorts. An
     * ordered tree drops before, into or after a row; an unordered one only into, a leaf meaning its parent.
     */
    boolean isOrdered();

    /** The item holding {@code item}, or null for a root. */
    @Nullable
    T parentOf(T item);

    /** {@code item}'s index among its parent's children, or -1 in an unordered tree. */
    int indexOf(T item);

    /** Whether {@code item} may hold children at all. */
    boolean isContainer(T item);

    /** Whether {@code item} may be dragged, cut, deleted or renamed. A root usually may not. */
    boolean canEdit(T item);

    /** Whether {@code items} may land in {@code parent}: a container, and none of them it or above it. */
    default boolean canDrop(List<T> items, T parent) {
        if (!isContainer(parent)) return false;
        for (T item : items) {
            for (T at = parent; at != null; at = parentOf(at)) {
                if (at.equals(item)) return false;
            }
        }
        return true;
    }

    /** The item's name: what rename opens with and edits. */
    String nameOf(T item);

    /** What a drag's ghost and the clipboard's text call the item — its name, unless a name can be empty. */
    default String labelOf(T item) {
        return nameOf(item);
    }


    /** What an item is, for a question about one — {@code "file"}, {@code "element"}. */
    default String noun() {
        return "item";
    }

    /** The modifier that makes a drop a copy. Ctrl, as every file manager; a design canvas may prefer Alt. */
    default int copyModifier() {
        return CgModifiers.CTRL;
    }

    /** Moves {@code items} to {@code to}, as one undo step. */
    void move(List<T> items, Target<T> to);

    /** Puts copies of {@code items} at {@code to}, as one undo step, never overwriting a namesake. */
    void copy(List<T> items, Target<T> to);

    /** Deletes {@code items}, asking first if the model wants to. */
    void delete(List<T> items);

    /** Whether Duplicate is offered for {@code items}. Ordered trees by default, where "beside it" has a place. */
    default boolean canDuplicate(List<T> items) {
        return isOrdered();
    }

    /** Names {@code item} {@code name}, which {@link #acceptsName} passed and is not its current name. */
    void rename(T item, String name);

    /** Where rename's opening selection ends, or -1 to select the whole name — a file's stem, before its extension. */
    default int renameSelectionEnd(T item, String name) {
        return -1;
    }

    /** Whether {@code name} is one {@code item} could have at all. Empty is refused by default. */
    default boolean acceptsName(T item, String name) {
        return !name.isEmpty();
    }

    /** Null when no sibling already holds {@code name}, else the free name to offer instead. */
    @Nullable
    default String freeNameFor(T item, String name) {
        return null;
    }

    /**
     * Where a paste over {@code selection} lands, or null for nowhere. By default an ordered tree pastes after
     * the last selected item in its parent (into a selected root), an unordered one into a selected container
     * or beside a selected leaf.
     */
    @Nullable
    default Target<T> pasteTarget(List<T> selection) {
        if (selection.isEmpty()) return null;
        if (isOrdered()) {
            T last = selection.get(selection.size() - 1);
            T parent = parentOf(last);
            return parent == null ? new Target<>(last, -1) : new Target<>(parent, indexOf(last) + 1);
        }
        T first = selection.get(0);
        if (isContainer(first)) return new Target<>(first, -1);
        T parent = parentOf(first);
        return parent == null ? null : new Target<>(parent, -1);
    }

    /** What Cut and Copy put on the platform clipboard as text: one label per line. */
    default String clipboardText(List<T> items) {
        StringBuilder text = new StringBuilder();
        for (T item : items) {
            if (text.length() > 0) text.append('\n');
            text.append(labelOf(item));
        }
        return text.toString();
    }
}
