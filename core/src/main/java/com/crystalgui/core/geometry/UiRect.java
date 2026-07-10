package com.crystalgui.core.geometry;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public final class UiRect {

    public static final UiRect ZERO = new UiRect(0.0f, 0.0f, 0.0f, 0.0f);

    private final float x;
    private final float y;
    private final float width;
    private final float height;

    public boolean contains(float pointX, float pointY) {
        return pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height;
    }
}
