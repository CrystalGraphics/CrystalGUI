package com.crystalgui.ui;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.event.EventTarget;
import com.crystalgui.ui.event.UIEvent;

import java.util.HashMap;

public final class EventListenerGroup<E extends EventTarget, T extends UIEvent> {

    private final Signal.Pair<E, T> capture = new Signal.Pair<>();
    private final Signal.Pair<E, T> target = new Signal.Pair<>();
    private final Signal.Pair<E, T> bubble = new Signal.Pair<>();
    private final Signal.Pair<E, T> defaultEvents = new Signal.Pair<>();

    /** What the listeners were attached to, handed to each of them as {@code thisElement}. */
    private final E element;

    public EventListenerGroup(E element) {
        this.element = element;
    }

    /**
     * The widget's OWN behaviour — what {@code preventDefault()} suppresses.
     *
     * <p>Public since M6.0: the old engine reached it through a {@code protected} hop on
     * {@code UIElement}, which works while every widget IS a {@code UIElement} and stops working the
     * moment a widget is a {@code UIElement} in another package. {@code UIElement} keeps the same
     * {@code protected} hop for the same discoverability, and both engines call this.</p>
     */
    public void attachDefaultListener(UIEvent.Listener<E, T> listener) {
        defaultEvents.connect(listener);
    }

    /**
     * Subscribes. <b>Always the target phase</b>; the two booleans are additive, not a mode selector —
     * {@code (false, false)} is target-only, so a container hears nothing a descendant was targeted
     * with.
     */
    public E attachListener(UIEvent.Listener<E, T> listener, boolean capture, boolean bubble) {
        this.target.connect(listener);
        if (bubble)
            this.bubble.connect(listener);
        if (capture)
            this.capture.connect(listener);
        return element;
    }

    public void emitTarget(T event) {
        switch (event.getPhase()) {
            case CAPTURE -> capture.continueEmittingUnderCondition(element, event, UIEvent::isPropagationStopped);
            case TARGET -> {
                target.continueEmittingUnderCondition(element, event, UIEvent::isPropagationStopped);
                if (!event.isDefaultPrevented())
                    defaultEvents.emit(element, event);
            }
            case BUBBLE -> bubble.continueEmittingUnderCondition(element, event, UIEvent::isPropagationStopped);
        }
    }

    /**
     * Emits with the DOM's propagation semantics: {@code stopPropagation} ends the WALK (which is
     * the dispatcher's business) and the remaining listeners on THIS element still run; only
     * {@code stopImmediatePropagation} ends those.
     *
     * <p>Beside {@link #emitTarget} rather than replacing it: the old engine's dispatcher conflates
     * the two, which several widgets are written against, and M6 deletes that path with it.</p>
     */
    public void emitTargetDom(T event) {
        switch (event.getPhase()) {
            case CAPTURE -> capture.continueEmittingUnderCondition(element, event, UIEvent::isImmediatePropagationStopped);
            case TARGET -> {
                target.continueEmittingUnderCondition(element, event, UIEvent::isImmediatePropagationStopped);
                if (!event.isDefaultPrevented())
                    defaultEvents.emit(element, event);
            }
            case BUBBLE -> bubble.continueEmittingUnderCondition(element, event, UIEvent::isImmediatePropagationStopped);
        }
    }

    public void disconnectAll(boolean capture, boolean bubble) {
        this.target.disconnectAll();
        if (capture)
            this.capture.disconnectAll();
        if (bubble)
            this.bubble.disconnectAll();
    }

    /** One group per event type, created on first use. */
    public static final class Map<E extends EventTarget> {
        private final HashMap<Class<? extends UIEvent>, EventListenerGroup<E, ?>> lookupMap = new HashMap<>();
        private final E element;

        public Map(E element) {
            this.element = element;
        }

        @SuppressWarnings("unchecked")
        public <T extends UIEvent> EventListenerGroup<E, T> getGroup(Class<T> clazz) {
            return (EventListenerGroup<E, T>) lookupMap.computeIfAbsent(clazz, c -> new EventListenerGroup<E, T>(element));
        }

        /** {@link EventListenerGroup#emitTargetDom} for whichever group this event belongs to. */
        public void emitToGroupDom(UIEvent event) {
            if (event == null) return;
            var group = lookupMap.get(event.getClass());
            if (group != null) {
                //noinspection unchecked
                ((EventListenerGroup<E, UIEvent>) group).emitTargetDom(event);
            }
        }

        public void emitToGroup(UIEvent event) {
            if (event == null) return;
            var group = lookupMap.get(event.getClass());
            if (group != null) {
                //noinspection unchecked
                ((EventListenerGroup<E, UIEvent>) group).emitTarget(event);
            }
        }
    }
}
