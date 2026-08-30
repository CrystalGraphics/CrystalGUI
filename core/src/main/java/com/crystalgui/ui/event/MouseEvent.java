package com.crystalgui.ui.event;

import com.crystalgui.core.data.ReadOnlyVec2f;
import lombok.Getter;

@Getter
public abstract class MouseEvent extends UIEvent {
    private final ReadOnlyVec2f position;

    protected MouseEvent(EventTarget target, boolean bubbles, ReadOnlyVec2f pos) {
        super(target, bubbles);
        this.position = pos;
    }

    @Getter
    protected static abstract class Click extends MouseEvent {
        private final int detail;
        private final int buttonId;

        protected Click(EventTarget target, ReadOnlyVec2f pos, int buttonId, int detail) {
            super(target, true, pos);
            this.buttonId = buttonId;
            this.detail = detail;
        }
    }

    public final static class Down extends Click {
        public Down(EventTarget target, ReadOnlyVec2f pos, int buttonId, int detail) {
            super(target, pos, buttonId, detail);
        }
    }

    @Getter
    public final static class Up extends Click {
        /** True iff this Up's target is the same element that received the matching Down — lets a
         * "click"-style decorator (e.g. Button's {@code ButtonEvent.Pressed}) tell a press-then-release
         * over the same element apart from a press that was released elsewhere. */
        private final boolean wasPressTarget;

        public Up(EventTarget target, ReadOnlyVec2f pos, int buttonId, int detail, boolean wasPressTarget) {
            super(target, pos, buttonId, detail);
            this.wasPressTarget = wasPressTarget;
        }
    }

    @Getter
    public final static class Scroll extends MouseEvent {
        /**
         * Mouse notches moved. <br>
         * Unlike Windows / LWJGL 2, normalized to 1 or -1.
         */
        private final float scroll;

        public Scroll(EventTarget target, ReadOnlyVec2f pos, float deltaScroll) {
            super(target, true, pos);
            this.scroll = deltaScroll;
        }
    }

    public final static class Move extends MouseEvent {
        public Move(EventTarget target, ReadOnlyVec2f pos) {
            super(target, true, pos);
        }
    }

    public final static class Enter extends MouseEvent {
        public Enter(EventTarget target, ReadOnlyVec2f pos) {
            super(target, false, pos);
        }
    }

    public final static class Leave extends MouseEvent {
        public Leave(EventTarget target, ReadOnlyVec2f pos) {
            super(target, false, pos);
        }
    }


}
