package com.crystalgui.core.event;

import lombok.Getter;

/**
 * Mouse/pointer event with position and button information.
 *
 * <p>Coordinates are in <b>absolute container space</b>.</p>
 */
@Getter
public final class UiMouseEvent extends UiEvent {

    /** Mouse button constants (platform-agnostic). */
    public static final int BUTTON_LEFT = 0;
    public static final int BUTTON_RIGHT = 1;
    public static final int BUTTON_MIDDLE = 2;

    private final float x;
    private final float y;
    private final int button;
    private final int modifiers;
    private final float scrollDelta;
    private final int clickCount;

    private UiMouseEvent(UiEventType type, float x, float y, int button,
                         int modifiers, float scrollDelta, int clickCount) {
        super(type);
        this.x = x;
        this.y = y;
        this.button = button;
        this.modifiers = modifiers;
        this.scrollDelta = scrollDelta;
        this.clickCount = clickCount;
    }

    /** Creates a pointer event (move, down, up, enter, leave, click, double-click). */
    public static UiMouseEvent pointer(UiEventType type, float x, float y) {
        return new UiMouseEvent(type, x, y, BUTTON_LEFT, 0, 0f, 0);
    }

    /** Creates a pointer event with a specific button. */
    public static UiMouseEvent pointer(UiEventType type, float x, float y, int button) {
        return new UiMouseEvent(type, x, y, button, 0, 0f, 0);
    }

    /** Creates a pointer event with button and modifiers. */
    public static UiMouseEvent pointer(UiEventType type, float x, float y, int button, int modifiers) {
        return new UiMouseEvent(type, x, y, button, modifiers, 0f, 0);
    }

    /** Creates a click event with click count. */
    public static UiMouseEvent click(UiEventType type, float x, float y, int button, int clickCount) {
        return new UiMouseEvent(type, x, y, button, 0, 0f, clickCount);
    }

    /** Creates a wheel event. */
    public static UiMouseEvent wheel(float x, float y, float scrollDelta, int modifiers) {
        return new UiMouseEvent(UiEventType.MOUSE_WHEEL, x, y, 0, modifiers, scrollDelta, 0);
    }
}
