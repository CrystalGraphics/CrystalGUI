package com.crystalgui.ui.input;

import lombok.Getter;

public final class ButtonState {
    @Getter
    private boolean pressed = false;
    private long lastPressedMillis = 0L;
    @Getter
    private int detail = 0;

    public void setState(boolean newPressedState, long millis) {
        if (newPressedState) {
            setPressed(millis);
        } else {
            this.pressed = false;
        }
    }

    private void setPressed(long millis) {
        pressed = true;
        final long millisDiff = millis - lastPressedMillis;
        lastPressedMillis = millis;
        if (millisDiff <= UIInputHandler.multiClickInterval) {
            detail++;
        } else {
            detail = 1;
        }
    }

    public void resetDetail() {
        this.detail = 0;
    }
}
