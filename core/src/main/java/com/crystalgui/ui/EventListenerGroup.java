package com.crystalgui.ui;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.event.UIEvent;

import java.util.HashMap;

public final class EventListenerGroup<T extends UIEvent> {
    private final Signal.Pair<UIElement, T> capture = new Signal.Pair<>();
    private final Signal.Pair<UIElement, T> target = new Signal.Pair<>();
    private final Signal.Pair<UIElement, T> bubble = new Signal.Pair<>();

    private final Signal.Pair<UIElement, T> defaultEvents = new Signal.Pair<>();


    private final UIElement element;
    public EventListenerGroup(UIElement element) {
        this.element = element;
    }

    void attachDefaultListener(Signal.Pair.Listener<UIElement, T> listener) {
        defaultEvents.connect(listener);
    }

    public UIElement attachListener(Signal.Pair.Listener<UIElement, T> listener, boolean capture, boolean bubble) {
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

    public void disconnectAll(boolean capture, boolean bubble) {
        this.target.disconnectAll();
        if (capture)
            this.capture.disconnectAll();
        if (bubble)
            this.bubble.disconnectAll();
    }

    public static final class Map {
        private final HashMap<Class<? extends UIEvent>, EventListenerGroup<?>> lookupMap = new HashMap<>();
        private final UIElement element;

        public Map(UIElement element) {
            this.element = element;
        }

        public boolean hasGroup(Class<? extends UIEvent> clazz) {
            return lookupMap.containsKey(clazz);
        }

        @SuppressWarnings("unchecked")
        public <T extends UIEvent> EventListenerGroup<T> getGroup(Class<T> clazz) {
            return (EventListenerGroup<T>) lookupMap.computeIfAbsent(clazz, c -> new EventListenerGroup<T>(element));
        }

        public void emitToGroup(UIEvent event) {
            if (event == null) return;
            var group = lookupMap.get(event.getClass());
            if (group != null) {
                //noinspection unchecked
                ((EventListenerGroup<UIEvent>) group).emitTarget(event);
            }
        }
    }
}
