package com.crystalgui.core.data;

import com.crystalgui.ui.UIElement;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * What is being acted on, here — the answers assembled by walking outward from an element.
 *
 * <h3>The pull, and why it is the whole point</h3>
 *
 * <p>A command asks {@code context.get(RESOURCE)} and the walk finds whoever can answer, starting at
 * the focused element and moving outward. So one {@code Delete} works in the file tree, the node graph
 * and the editor without naming any of them, and a widget added later joins in by implementing
 * {@link DataProvider}. IntelliJ calls this a {@code DataContext}; VS Code inverts it into context keys
 * that the current state publishes. Both exist to stop a command from knowing its callers.</p>
 *
 * <h3>Innermost wins</h3>
 *
 * <p>First non-null answer, from the inside out. That is the same rule the keymap already uses to
 * resolve a binding and the same one {@code UndoScope.nearest} uses to find a stack — deliberately, so
 * that a keystroke, an undo and a command all agree about which thing they are addressing. Two open
 * editors are two subjects, and focus decides.</p>
 *
 * <h3>Internal children are walked too</h3>
 *
 * <p>Click-focus targets the <b>exact element hit</b>, which in a composite widget is one of its
 * internal parts — a tab's label, a row's icon. Walking only public parents would therefore lose the
 * subject for precisely the widgets that are built properly. {@code UIElement.getParent()} returns the
 * real parent regardless of how the child was added, so the walk gets this for free; it is written down
 * because a future "skip internal children" optimisation would silently break every composite.</p>
 *
 * <h3>Do not keep one</h3>
 *
 * <p>A context is a snapshot of one question-asking pass. The answers it caches are valid for that
 * pass and no longer: the tree moves, selections change, and a context held across frames answers with
 * whatever was true when it was built. Build one, use it, drop it.</p>
 */
public final class DataContext {

    /** Answers nothing. What a command sees when it is invoked from nowhere — the palette with no focus. */
    public static final DataContext EMPTY = new DataContext(null);

    @Nullable
    private final UIElement source;

    /**
     * Answers found during this pass, including the nulls.
     *
     * <p>Cached because enablement asks the same key repeatedly while a menu is built, and each ask is
     * a walk. Null answers are cached too — {@code containsKey} rather than a null check — since "no
     * provider anywhere knows this" is the expensive answer and the one worth not repeating.</p>
     */
    private final Map<DataKey<?>, Object> answered = new HashMap<>();

    private DataContext(@Nullable UIElement source) {
        this.source = source;
    }

    /** A context that asks {@code source} and everything above it. */
    public static DataContext from(@Nullable UIElement source) {
        return source == null ? EMPTY : new DataContext(source);
    }

    /** The element the walk starts at, or null. */
    @Nullable
    public UIElement source() {
        return source;
    }

    /** The answer to {@code key}, or null when nothing between here and the root knows. */
    @Nullable
    public <T> T get(DataKey<T> key) {
        if (answered.containsKey(key)) return key.cast(answered.get(key));
        Object found = walk(key);
        answered.put(key, found);
        return key.cast(found);
    }

    public boolean has(DataKey<?> key) {
        return get(key) != null;
    }

    /**
     * The answer, or a failure naming the key.
     *
     * <p>For a caller that has already established the answer exists — typically a command whose
     * {@code enabledWhen} asked {@link #has}. The name is in the message because the alternative is a
     * {@code NullPointerException} three frames away from the key that was actually missing.</p>
     */
    public <T> T require(DataKey<T> key) {
        T value = get(key);
        if (value == null) throw new IllegalStateException("no " + key.name() + " in this context");
        return value;
    }

    /**
     * The first answer that is both present <b>and of the right type</b>.
     *
     * <p>The type check belongs in the walk rather than after it. Accepting a wrong-typed answer and
     * casting it to null afterwards would let one mistaken provider shadow a correct one further out —
     * the command then reports "nothing selected" in a widget that plainly has a selection, and the
     * provider at fault is the one place nobody looks.</p>
     *
     * <p>Skipped silently rather than thrown, because this runs while menus are built and palettes are
     * filtered. A wrong type is a bug, but a bug that costs one provider its answer is better than one
     * that takes the frame down.</p>
     */
    @Nullable
    private Object walk(DataKey<?> key) {
        for (UIElement element = source; element != null; element = element.getParent()) {
            if (!(element instanceof DataProvider provider)) continue;
            Object answer = provider.getData(key);
            if (answer != null && key.cast(answer) != null) return answer;
        }
        return null;
    }

    @Override
    public String toString() {
        return "DataContext(" + (source == null ? "empty" : source.getClass().getSimpleName()) + ")";
    }
}
