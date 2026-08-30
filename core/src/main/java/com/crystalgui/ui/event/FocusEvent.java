package com.crystalgui.ui.event;


public abstract class FocusEvent extends UIEvent {
    protected FocusEvent(EventTarget target) {
        super(target, true);
    }

    public static class Focus extends FocusEvent {
        public Focus(EventTarget target) {
            super(target);
        }
    }

    public static class Blur extends FocusEvent {
        public Blur(EventTarget target) {
            super(target);
        }
    }


}
