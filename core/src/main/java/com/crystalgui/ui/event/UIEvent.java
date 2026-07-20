package com.crystalgui.ui.event;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;
import lombok.Getter;

public abstract class UIEvent {

    @Getter
    private final UIElement target;
    @Getter
    private final boolean bubbles;

    @Getter
    private boolean phasePropagationStopped;
    @Getter
    private boolean propagationStopped;
    @Getter
    private boolean defaultPrevented;

    @Getter
    private PropagationPhase phase = PropagationPhase.CAPTURE;

    public void setPhase(PropagationPhase newPhase) {
        if (newPhase == null) return;
        this.phase = newPhase;
    }

    protected UIEvent(UIElement target, boolean bubbles) {
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
    public void preventDefault() {
        this.defaultPrevented = true;
    }

    /**
     * Redefined {@link Signal.Pair.Listener} so you get proper hints with the IDE.
     * @param <T>
     */
    public interface Listener<T extends UIEvent> extends Signal.Pair.Listener<UIElement, T> {
        void accept(UIElement thisElement, T event);
    }
}
