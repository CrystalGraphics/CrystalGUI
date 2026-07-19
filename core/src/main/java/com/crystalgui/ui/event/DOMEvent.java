package com.crystalgui.ui.event;

import com.crystalgui.ui.UIElement;

public abstract class DOMEvent extends UIEvent{
    protected DOMEvent(UIElement target) {
        super(target, false);
    }

    public static final class ElementAdded extends DOMEvent {
        public ElementAdded(UIElement target) {
            super(target);
        }
    }

    public static final class ElementRemoved extends DOMEvent {
        public ElementRemoved(UIElement target) {
            super(target);
        }
    }
}
