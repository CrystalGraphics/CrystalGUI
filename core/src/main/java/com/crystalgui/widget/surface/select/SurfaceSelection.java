package com.crystalgui.widget.surface.select;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.inspector.InspectorRegistry;

/**
 * What is selected on a surface: any number of items, and at most one secondary thing beside them.
 *
 * <p>One writer, many readers — a marquee, a delete command and the inspector all read this rather than
 * walking the plane asking each item, which is what three consumers disagreeing looks like. Reached
 * through the surface context; a consumer never builds one.</p>
 *
 * <pre>{@code
 * SurfaceSelection selection = ctx.selection();
 * selection.selectOnly(node);                    // a plain click
 * selection.toggle(node);                        // shift-click
 * selection.replaceWith(inside);                 // a marquee
 * selection.onChanged.connect(this::refresh);
 * }</pre>
 *
 * <p>The <b>secondary</b> is for the one thing a surface can select that is not an item on the plane —
 * a graph's wire. Selecting one clears the items and vice versa, because the two are never operated on
 * together.</p>
 *
 * <h3>Selection is not undoable</h3>
 *
 * <p>Deliberately, and it is a live disagreement rather than an obvious call: Blender records selection
 * in its undo history and is criticised for it in nearly the same words every time. The answer here is
 * VS Code's — selection stays view state, and an <em>edit</em> restores the selection it applied to,
 * because the edit knows what it touched.</p>
 *
 * <h3>Insertion-ordered</h3>
 *
 * <p>A {@code LinkedHashSet}, so "the first one you picked" is answerable. Alignment, distribution and
 * "make this the active one" all key off it, and none can be added later to a {@code HashSet} without
 * changing behaviour nobody wrote down.</p>
 */
public class SurfaceSelection {

    /**
     * How a selected item is made to look selected — a graph node draws a ring from a pseudo-class, a
     * described element gets handles.
     *
     * <p>The model is the engine's and the appearance is not, so the consumer answers this and nothing
     * else. Called once per item that changed.</p>
     */
    @FunctionalInterface
    public interface Marking {
        void mark(UIElement item, boolean selected);
    }

    private final Marking marking;

    private final Set<UIElement> items = new LinkedHashSet<>();

    /** At most one: nothing yet wants to operate on several, and one is enough to delete it. */
    @Nullable
    private Object secondary;

    /**
     * Fires after any change. One signal, because every consumer re-reads the whole selection.
     *
     * <p>Also announces to {@link InspectorRegistry}, so a contribution never has to wire that itself —
     * forgetting the line produced a surface whose inspector tab never appeared while an identical one
     * beside it worked. Cheap: the inspector defers to the next frame and drops the rebuild when its
     * subject key has not moved.</p>
     */
    public final Signal.Action onChanged = new Signal.Action();

    public SurfaceSelection(Marking marking) {
        this.marking = marking;
        onChanged.connect(InspectorRegistry::subjectChanged);
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** The selected items, in the order they were added. */
    public List<UIElement> items() {
        return List.copyOf(items);
    }

    public Set<UIElement> itemSet() {
        return Collections.unmodifiableSet(items);
    }

    /** The one non-item thing selected, or null. @see SurfaceSelection */
    @Nullable
    public Object secondary() {
        return secondary;
    }

    public boolean contains(UIElement item) {
        return items.contains(item);
    }

    public boolean isEmpty() {
        return items.isEmpty() && secondary == null;
    }

    /** Items plus the secondary, so "one thing is selected" is one comparison. */
    public int size() {
        return items.size() + (secondary == null ? 0 : 1);
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /** Replaces the whole selection with one item. */
    public void selectOnly(UIElement item) {
        if (items.size() == 1 && items.contains(item) && secondary == null) return;
        clearSilently();
        items.add(item);
        marking.mark(item, true);
        onChanged.emit();
    }

    /** Replaces the whole selection with the secondary thing — which deselects every item. */
    public void selectSecondary(@Nullable Object what) {
        clearSilently();
        secondary = what;
        onChanged.emit();
    }

    public void add(UIElement item) {
        if (!items.add(item)) return;
        marking.mark(item, true);
        onChanged.emit();
    }

    public void remove(UIElement item) {
        if (!items.remove(item)) return;
        marking.mark(item, false);
        onChanged.emit();
    }

    /** Adds if absent, removes if present — what shift-click does. */
    public void toggle(UIElement item) {
        if (items.contains(item)) remove(item);
        else add(item);
    }

    /** Replaces the selection, in one signal rather than one per item. */
    public void replaceWith(Collection<? extends UIElement> replacement) {
        if (items.equals(new LinkedHashSet<>(replacement)) && secondary == null) return;
        clearSilently();
        for (UIElement item : replacement) {
            if (items.add(item)) marking.mark(item, true);
        }
        onChanged.emit();
    }

    /** Adds several at once — a marquee with Shift held. */
    public void addAll(Collection<? extends UIElement> more) {
        boolean changed = false;
        for (UIElement item : more) {
            if (items.add(item)) {
                marking.mark(item, true);
                changed = true;
            }
        }
        if (changed) onChanged.emit();
    }

    /** Removes several at once — a marquee with Alt held. */
    public void removeAll(Collection<? extends UIElement> fewer) {
        boolean changed = false;
        for (UIElement item : fewer) {
            if (items.remove(item)) {
                marking.mark(item, false);
                changed = true;
            }
        }
        if (changed) onChanged.emit();
    }

    public void clear() {
        if (isEmpty()) return;
        clearSilently();
        onChanged.emit();
    }

    /**
     * Drops anything no longer there — an item that was deleted, a secondary that was disconnected.
     *
     * <p>Call after any structural change. Without it the selection pins a detached widget: it is out of
     * the tree and still "selected", so the next move re-places it somewhere nobody asked for and a
     * delete tries to remove it twice.</p>
     *
     * @param keepItem      whether an item is still on the surface
     * @param keepSecondary whether the secondary is still there; only asked when there is one
     */
    public void retain(Predicate<UIElement> keepItem, Predicate<Object> keepSecondary) {
        boolean changed = items.removeIf(item -> !keepItem.test(item));
        if (secondary != null && !keepSecondary.test(secondary)) {
            secondary = null;
            changed = true;
        }
        if (changed) onChanged.emit();
    }

    private void clearSilently() {
        for (UIElement item : items) marking.mark(item, false);
        items.clear();
        secondary = null;
    }
}
