package com.crystalgui.core.event;

import com.crystalgui.ui.UIElement;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;

@Getter
public final class UiEvent {

    private final UiEventType type;
    private final float x;
    private final float y;

    @Setter @Nullable
    private UIElement target;
    @Setter @Nullable
    private UIElement currentTarget;
    @Setter
    private UiEventPhase phase = UiEventPhase.TARGET;
    private boolean propagationStopped;
    private boolean defaultPrevented;

    private UiEvent(UiEventType type, float x, float y) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        this.type = type;
        this.x = x;
        this.y = y;
    }

    public static UiEvent pointer(UiEventType type, float x, float y) {
        return new UiEvent(type, x, y);
    }

    public void stopPropagation() {
        this.propagationStopped = true;
    }

    public void preventDefault() {
        this.defaultPrevented = true;
    }
}
