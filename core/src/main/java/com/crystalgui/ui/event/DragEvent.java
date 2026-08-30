package com.crystalgui.ui.event;

import com.crystalgui.core.data.ReadOnlyVec2f;
import lombok.Getter;

import javax.annotation.Nullable;

/**
 * Drag-and-drop, dispatched to whatever is <b>under the pointer</b> while a drag is active.
 *
 * <h3>This part is ours, deliberately</h3>
 * <p>Everything else in this engine's input layer is ported from a spec. This is not, because there
 * is nothing to port: the web's only drop-target protocol is HTML5 drag-and-drop
 * ({@code dragover}/{@code dataTransfer}/{@code dropEffect}), which is a bad API that the web itself
 * moved away from. Modern pointer-based drag libraries hit-test themselves on every move
 * ({@code document.elementFromPoint}) and dispatch their own events. So does this.</p>
 *
 * <p><b>The interaction with pointer capture is the load-bearing bit.</b> While a drag runs, the
 * pointer is captured by the drag <em>source</em> — "as if the pointer is always over the capturing
 * target" — so ordinary hit testing reports the source no matter where the cursor is. That is correct
 * for mouse events and useless for finding a drop target. The two therefore run on different
 * information: capture routes {@link MouseEvent}s to the source, while
 * {@code UIDragController} separately asks the window what is <em>geometrically</em> under the
 * pointer and dispatches these events there. {@code UIWindow.getHoveredElement} is left purely
 * geometric precisely so this stays possible.</p>
 *
 * <h3>Which of these bubble, and why</h3>
 * <ul>
 *   <li>{@link Over} and {@link Drop} <b>bubble</b> — a drop is an action, and an ancestor is often
 *       the thing that wants to handle it (a panel accepting anything dropped into its rows).</li>
 *   <li>{@link Enter} and {@link Leave} <b>do not</b>, matching {@code MouseEvent.Enter}/{@code Leave}
 *       and the DOM's {@code mouseenter}/{@code mouseleave}. Like those, one is still dispatched to
 *       <em>every</em> element in the entered/left chain, so a container hears about a drag crossing
 *       its children without a bubbling event re-firing on every ancestor for every move.</li>
 *   <li>{@link Cancel} does not bubble — it goes to the source, which is the only thing that can
 *       meaningfully undo a drag it started.</li>
 * </ul>
 */
@Getter
public abstract class DragEvent extends UIEvent {

    private final ReadOnlyVec2f position;

    /** The element the drag started from. Never null while a drag is active. */
    private final EventTarget source;

    /** Caller-supplied payload, or {@code null} for a positional drag that carries no data (which is
     * what Slider, Scroller and SplitView do). */
    @Nullable
    private final Object payload;

    protected DragEvent(EventTarget target, boolean bubbles, ReadOnlyVec2f pos,
                        EventTarget source, @Nullable Object payload) {
        super(target, bubbles);
        this.position = pos;
        this.source = source;
        this.payload = payload;
    }

    /** The drag has entered this element. Fired once per element entered, outermost first. */
    public static final class Enter extends DragEvent {
        public Enter(EventTarget target, ReadOnlyVec2f pos, EventTarget source, @Nullable Object payload) {
            super(target, false, pos, source, payload);
        }
    }

    /** The drag has left this element. Fired once per element left, innermost first. */
    public static final class Leave extends DragEvent {
        public Leave(EventTarget target, ReadOnlyVec2f pos, EventTarget source, @Nullable Object payload) {
            super(target, false, pos, source, payload);
        }
    }

    /**
     * The drag is over this element — fired every frame it stays there.
     *
     * <p>{@link UIEvent#preventDefault()} is how a target says "I will accept a drop here". The
     * inversion is HTML5 DnD's one genuinely good idea and it is kept: the default behaviour of a
     * drag over an arbitrary element is to reject it, so an element that has never heard of dragging
     * cannot silently become a drop target.</p>
     */
    public static final class Over extends DragEvent {
        public Over(EventTarget target, ReadOnlyVec2f pos, EventTarget source, @Nullable Object payload) {
            super(target, true, pos, source, payload);
        }
    }

    /** The drag was released over this element, and it had accepted the drop. */
    public static final class Drop extends DragEvent {
        public Drop(EventTarget target, ReadOnlyVec2f pos, EventTarget source, @Nullable Object payload) {
            super(target, true, pos, source, payload);
        }
    }

    /**
     * The drag was aborted rather than dropped — Escape, or the source leaving the tree.
     *
     * <p>Modelled on {@code pointercancel}, which exists so cleanup has one defined path instead of
     * every listener inventing its own idea of "the drag didn't finish".</p>
     */
    public static final class Cancel extends DragEvent {
        public Cancel(EventTarget target, ReadOnlyVec2f pos, EventTarget source, @Nullable Object payload) {
            super(target, false, pos, source, payload);
        }
    }
}
