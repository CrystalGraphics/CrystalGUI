package com.crystalgui.core.event;

import com.crystalgui.ui.UIElement;

import javax.annotation.Nullable;

public final class UiEvent {

    private final UiEventType type;
    private final float x;
    private final float y;

    @Nullable
    private UIElement target;
    @Nullable
    private UIElement currentTarget;
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

    public UiEventType getType() {
        return type;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    @Nullable
    public UIElement getTarget() {
        return target;
    }

    public void setTarget(@Nullable UIElement target) {
        this.target = target;
    }

    @Nullable
    public UIElement getCurrentTarget() {
        return currentTarget;
    }

    public void setCurrentTarget(@Nullable UIElement currentTarget) {
        this.currentTarget = currentTarget;
    }

    public UiEventPhase getPhase() {
        return phase;
    }

    public void setPhase(UiEventPhase phase) {
        this.phase = phase;
    }

    public boolean isPropagationStopped() {
        return propagationStopped;
    }

    public void stopPropagation() {
        this.propagationStopped = true;
    }

    public boolean isDefaultPrevented() {
        return defaultPrevented;
    }

    public void preventDefault() {
        this.defaultPrevented = true;
    }
}
