package com.crystalgui.mc.client;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.dom.UIDocument;

import net.minecraft.client.Minecraft;

/**
 * One 1.20.x screen callback into one engine event.
 *
 * <p>Nothing here is a pump in the 1.7.10 sense. {@code GuiEventListener} delivers events as they
 * happen and each returns whether it was consumed, which is exactly what {@code Input} already answers —
 * so there is no queue to drain, no tick-rate problem, and no mixin.</p>
 *
 * <h3>Coordinates come from MouseHandler, not from the callback</h3>
 *
 * <p>A {@code Screen} callback's doubles are GUI-SCALED: {@code MouseHandler} multiplies by
 * {@code getGuiScaledWidth() / getScreenWidth()} before dispatching. The engine wants RAW surface pixels
 * and applies its own scale on the box tree's root transform, so at the default GUI Scale of 2 the
 * callback values would put every click at half the distance. {@code xpos()}/{@code ypos()} are the raw
 * ones.</p>
 */
public final class CgUiInput1201 {

    private CgUiInput1201() {}

    /**
     * GLFW scrolls positive UP; a positive {@code MouseEvent.Scroll} means the wheel rolled DOWN. The
     * only statement of that convention in the engine is {@code ScrollerView.setScrollTop(before + delta)}.
     */
    private static final float SCROLL_SIGN = -1f;

    /** No button, and the value the engine reads as "this is a move". */
    private static final int NO_BUTTON = -1;

    private static int lastX;
    private static int lastY;

    private static int rawX() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.mouseHandler == null ? 0 : (int) mc.mouseHandler.xpos();
    }

    private static int rawY() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.mouseHandler == null ? 0 : (int) mc.mouseHandler.ypos();
    }

    /** @return whether the desktop consumed it */
    public static boolean mouseButton(UIDocument window, int button, boolean pressed) {
        int x = rawX();
        int y = rawY();
        lastX = x;
        lastY = y;
        return send(window, x, y, 0, 0, button, pressed, 0f, System.currentTimeMillis());
    }

    /**
     * A move carries no button and no timestamp — a click time on a move drifts the multi-click counter
     * and turns a slow double-click into a triple.
     */
    public static void mouseMoved(UIDocument window) {
        int x = rawX();
        int y = rawY();
        int dx = x - lastX;
        int dy = y - lastY;
        lastX = x;
        lastY = y;
        send(window, x, y, dx, dy, NO_BUTTON, false, 0f, -1L);
    }

    /** @return whether the desktop consumed it */
    public static boolean scrolled(UIDocument window, double delta) {
        return send(window, rawX(), rawY(), 0, 0, NO_BUTTON, false,
                (float) delta * SCROLL_SIGN, -1L);
    }

    /**
     * A key with no character. GLFW splits the two, so a press sends the key and {@code charTyped} sends
     * the character; synthesising one here would be layout-dependent and GLFW has already done it.
     *
     * @return whether the desktop consumed it
     */
    public static boolean key(UIDocument window, int glfwKey, boolean pressed) {
        int local = CgPlatform.input().translateKeyboardCodes(glfwKey);
        return window.input().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(
                (char) 0, local, pressed, false, System.currentTimeMillis()));
    }

    /** A character with no key. @return whether the desktop consumed it */
    public static boolean character(UIDocument window, char typed) {
        return window.input().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(
                typed, CgKeyCodes.KEY_NONE, true, false, System.currentTimeMillis()));
    }

    private static boolean send(UIDocument window, int x, int y, int dx, int dy,
                                int button, boolean pressed, float wheel, long millis) {
        return window.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, dx, dy, button, pressed, wheel, millis));
    }
}
