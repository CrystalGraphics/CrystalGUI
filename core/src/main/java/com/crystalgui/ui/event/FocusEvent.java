package com.crystalgui.ui.event;

import com.crystalgui.ui.UIElement;

public abstract class FocusEvent extends UIEvent {
    protected FocusEvent(UIElement target) {
        super(target, true);
    }

    public static class Focus extends FocusEvent {
        public Focus(UIElement target) {
            super(target);
        }
    }

    public static class Blur extends FocusEvent {
        public Blur(UIElement target) {
            super(target);
        }
    }


}
