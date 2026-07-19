package com.crystalgui.ui.event;

import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.ui.UIElement;
import lombok.Getter;

@Getter
public abstract class MouseEvent extends UIEvent {
    private final ReadOnlyVec2f position;

    protected MouseEvent(UIElement target, boolean bubbles, ReadOnlyVec2f pos) {
        super(target, bubbles);
        this.position = pos;
    }

    @Getter
    protected static abstract class Click extends MouseEvent {
        private final int detail;
        private final int buttonId;

        protected Click(UIElement target, ReadOnlyVec2f pos, int buttonId, int detail) {
            super(target, true, pos);
            this.buttonId = buttonId;
            this.detail = detail;
        }
    }

    public final static class Down extends Click {
        public Down(UIElement target, ReadOnlyVec2f pos, int buttonId, int detail) {
            super(target, pos, buttonId, detail);
        }
    }

    public final static class Up extends Click {
        public Up(UIElement target, ReadOnlyVec2f pos, int buttonId, int detail) {
            super(target, pos, buttonId, detail);
        }
    }

    @Getter
    public final static class Scroll extends MouseEvent {
        private final float scroll;

        public Scroll(UIElement target, ReadOnlyVec2f pos, float deltaScroll) {
            super(target, true, pos);
            this.scroll = deltaScroll;
        }
    }

    public final static class Move extends MouseEvent {
        public Move(UIElement target, ReadOnlyVec2f pos) {
            super(target, true, pos);
        }
    }

    public final static class Enter extends MouseEvent {
        public Enter(UIElement target, ReadOnlyVec2f pos) {
            super(target, false, pos);
        }
    }

    public final static class Leave extends MouseEvent {
        public Leave(UIElement target, ReadOnlyVec2f pos) {
            super(target, false, pos);
        }
    }


}
