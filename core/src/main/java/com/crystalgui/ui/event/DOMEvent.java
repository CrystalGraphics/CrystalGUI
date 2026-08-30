package com.crystalgui.ui.event;


public abstract class DOMEvent extends UIEvent{
    protected DOMEvent(EventTarget target) {
        super(target, false);
    }

    public static final class ElementAdded extends DOMEvent {
        public ElementAdded(EventTarget target) {
            super(target);
        }
    }

    public static final class ElementRemoved extends DOMEvent {
        public ElementRemoved(EventTarget target) {
            super(target);
        }
    }
}
