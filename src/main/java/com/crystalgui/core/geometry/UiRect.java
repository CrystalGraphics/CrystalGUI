package com.crystalgui.core.geometry;

public final class UiRect {

    public static final UiRect ZERO = new UiRect(0.0f, 0.0f, 0.0f, 0.0f);

    private final float x;
    private final float y;
    private final float width;
    private final float height;

    public UiRect(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public boolean contains(float pointX, float pointY) {
        return pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height;
    }
}
