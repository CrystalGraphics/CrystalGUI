package com.crystalgui.app.shadergraph.blackboard;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.KeyboardEvent;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * Rename-in-place on a row: a {@link TextField} that appears over a label, commits on Enter or blur and
 * abandons on Escape.
 *
 * <h3>Why this is a class and not eighty lines copied twice</h3>
 * <p>Three separate bugs were fixed in this gesture before it worked, and none of them is visible from
 * reading it — each is a one-line consequence of how something <em>else</em> in the engine behaves. A
 * second copy would not have them, would look correct, and would be fixed a fourth time in one file
 * only. That is the exact failure {@code stroke.glsl} exists to prevent, written down in
 * {@code CrystalGraphics/AGENTS.md}.</p>
 *
 * <p>The three, so they survive the extraction:</p>
 * <ol>
 *   <li><b>Enter on an unchanged name must still close the field.</b> {@code TextField} publishes
 *       through a {@link Signal.Value}, which is <em>equality-suppressing</em> — committing the name
 *       already there emits nothing. A version relying on that listener alone did literally nothing on
 *       Enter: no write (right, there was nothing to write) and no close (wrong). It appeared to work
 *       only if you typed something different.</li>
 *   <li><b>The editor reference is cleared BEFORE the field is detached.</b> Detaching a focused field
 *       fires a blur, the blur handler commits, committing rewrites the document, and that rebuilds the
 *       list <em>while it is still being walked</em> — a {@code ConcurrentModificationException} out of
 *       an ordinary rename. Nulling first makes both the blur and a second {@code end()} no-ops.</li>
 *   <li><b>{@link #onEnded} fires after the detach, not before.</b> It exists so the host can take focus
 *       back: detaching leaves focus at <b>nothing</b> and every command resolves outward from the
 *       focused element, so the whole key set went dead after an Enter until the row was clicked again.
 *       Emitting before the detach would hand focus over and then lose it a line later.</li>
 * </ol>
 *
 * <h3>What it deliberately does not do</h3>
 * <p>It does not write anything. {@link #onCommitted} reports a genuinely new name and the host decides
 * what that means — a property rename is an undoable edit, a category rename rewrites a field on every
 * member. A helper that wrote the document would have to know which.</p>
 */
public final class InlineRename {

    private final UIElement host;
    private final String editorClass;
    private final String renamingClass;

    /** Updates the host's own label. Called before {@link #onCommitted}, so a later blur sees the new value. */
    private final Consumer<String> showName;

    /**
     * Re-runs selector matching on the host, which only the host can ask for —
     * {@code invalidateStyleMatch()} is protected. Without it the renaming class is added and never
     * re-evaluated, so the capsule does not step aside and the editor draws on top of it.
     */
    private final Runnable restyle;

    /** Fires with a name that is non-empty and actually different. The host writes; this does not. */
    public final Signal.Value<String> onCommitted = new Signal.Value<>();

    /** Fires once the gesture is over, however it ended. @see InlineRename */
    public final Signal.Action onEnded = new Signal.Action();

    /** Built on demand — almost no row is ever renamed, and a {@code TextField} per row is not free. */
    @Nullable
    private TextField editor;

    /** What the label reads now. Held locally so the blur that follows Enter cannot re-report it. */
    private String shown;

    public InlineRename(UIElement host, String editorClass, String renamingClass,
                        String initialName, Consumer<String> showName, Runnable restyle) {
        this.host = host;
        this.editorClass = editorClass;
        this.renamingClass = renamingClass;
        this.shown = initialName;
        this.showName = showName;
        this.restyle = restyle;
    }

    /** Keeps the local copy in step when the host's label changes for some other reason. */
    public void setShownName(String name) {
        this.shown = name == null ? "" : name;
    }

    /** Opens the editor over the label. Does nothing if one is already open or the host is detached. */
    public void begin() {
        if (editor != null) return;
        UIDocument window = host.document();
        if (window == null) return;

        editor = new TextField();
        editor.addClass(editorClass);
        editor.setText(shown);

        // The value listener WRITES; Enter and blur END the gesture regardless of whether anything
        // changed. Splitting the two is bug (1) in the class note -- they are not the same event.
        editor.attachListener(this::apply);
        editor.events.getGroup(KeyboardEvent.Down.class).attachListener((element, event) -> {
            if (event.getKeyCode() == CgKeyCodes.KEY_RETURN) {
                apply(editor == null ? null : editor.getText());
                end();
                event.stopPropagation();
            } else if (event.getKeyCode() == CgKeyCodes.KEY_ESCAPE) {
                // Escape abandons, the convention wherever a rename is inline. Consumed so it does not
                // also reach whatever else listens for Escape -- a popover, a modal, the graph.
                end();
                event.stopPropagation();
            }
        }, false, true);
        editor.events.getGroup(FocusEvent.Blur.class).attachListener((element, event) -> {
            apply(editor == null ? null : editor.getText());
            end();
        }, false, true);

        host.addClass(renamingClass);
        host.append(editor);
        restyle.run();

        // requestFocus, not requestPointerFocus: this IS keyboard focus and the ring is wanted -- the
        // field appeared in order to be typed into, which is the case :focus-visible exists for.
        window.focus().requestFocus(editor);
        editor.selectAll();
    }

    /** Whether a rename is in flight — a caller must not rebuild the row under one. */
    public boolean isRunning() {
        return editor != null;
    }

    /**
     * Reports a new name, if there is one. Does <b>not</b> end the gesture.
     *
     * <p>Idempotent, because it is reached from three places that overlap: the value listener, Enter,
     * and the blur Enter itself causes. A name equal to the current one is dropped here rather than by
     * the host, so nothing downstream is handed a rename it would only discard.</p>
     */
    private void apply(@Nullable String value) {
        // No editor means the gesture is already over and this is a late listener arriving during
        // teardown. See end().
        if (editor == null) return;
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.equals(shown)) return;
        shown = trimmed;
        showName.accept(trimmed);
        onCommitted.emit(trimmed);
    }

    /** Takes the editor away and puts the label back. Safe to call when nothing is running. */
    public void end() {
        if (editor == null) return;
        // CLEARED FIRST, then removed -- bug (2) in the class note.
        TextField going = editor;
        editor = null;
        host.remove(going);
        host.removeClass(renamingClass);
        restyle.run();
        // AFTER the removal, because that is what cleared the focus -- bug (3).
        onEnded.emit();
    }
}
