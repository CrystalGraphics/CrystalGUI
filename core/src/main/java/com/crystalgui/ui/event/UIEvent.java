package com.crystalgui.ui.event;

import com.crystalgui.core.signal.Signal;
import lombok.Getter;

public abstract class UIEvent {

    @Getter
    private EventTarget target;
    @Getter
    private final boolean bubbles;

    @Getter
    private boolean phasePropagationStopped;
    @Getter
    private boolean propagationStopped;
    @Getter
    private boolean immediatePropagationStopped;
    @Getter
    private boolean defaultPrevented;

    @Getter
    private PropagationPhase phase = PropagationPhase.CAPTURE;

    public UIEvent setPhase(PropagationPhase newPhase) {
        if (newPhase == null) return this;
        this.phase = newPhase;
        return this;
    }

    protected UIEvent(EventTarget target, boolean bubbles) {
        this.target = target;
        this.bubbles = bubbles;
    }

    public void stopPhasePropagation() {
        this.phasePropagationStopped = true;
    }

    /**
     * Stops propagation immediately — remaining listeners on the same element
     * are skipped, and no further elements receive the event.
     */
    public void stopPropagation() {
        this.phasePropagationStopped = true;
        this.propagationStopped = true;
    }

    /** Prevents the default action associated with this event. */
    /**
     * Ends this element's remaining listeners as well as the walk — the DOM's
     * {@code stopImmediatePropagation}.
     *
     * <p>The distinction is only observable under the new engine's dispatcher: the old one treats
     * {@link #stopPropagation()} as this, which is why a listener attached to a widget's own group
     * after its constructor may never run.</p>
     */
    public void stopImmediatePropagation() {
        this.immediatePropagationStopped = true;
        stopPropagation();
    }

    /**
     * Re-points the target as the walk crosses a shadow boundary — the spec's retargeting, so a
     * listener outside a composite is told the host and never the part. The dispatcher's, and
     * nobody else's.
     */
    public void retarget(EventTarget target) {
        this.target = target;
    }

    public void preventDefault() {
        this.defaultPrevented = true;
    }

    /**
     * Redefined {@link Signal.Pair.Listener} so you get proper hints with the IDE.
     * @param <T>
     */
    /**
     * @param <E> what the listener was attached to — the element on the old engine, the node on the
     *            new one; {@code thisElement} is that, never the event's target
     */
    public interface Listener<E extends EventTarget, T extends UIEvent> extends Signal.Pair.Listener<E, T> {
        void accept(E thisElement, T event);
    }
}
