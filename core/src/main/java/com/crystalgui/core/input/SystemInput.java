package com.crystalgui.core.input;

import com.crystalgui.core.data.LongCacheCell;
import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
import com.crystalgui.core.input.mouse.CgUiMouseCodes;

import java.awt.*;

/**
 * Interface responsible for consuming <b>raw</b> System input events.<br>
 * <h3>An instance of this interface has to be registered to {@link com.crystalgui.core.CrystalGuiCore}</h3>
 * <br>
 * This class does not dictate when the event pool is gonna be drained. <br>
 * This is just a listener for the drained events, dispatched from a parent.<br>
 * <br>
 * <h2>This interface inputs to be translated to their {@link CgUiKeyCodes} / {@link CgUiMouseCodes} equivalents!! </h2>
 */
public interface SystemInput {
    long DEFAULT_MULTI_CLICK_INTERVAL_MS = 300L;
    /**
     * Change from CacheCell to something else.
     * Cache cell at least lets the end user to modify this?... idk shitty code.
     */
    LongCacheCell multiClickInterval = new LongCacheCell().setCalculator((ignore) -> {
        if (GraphicsEnvironment.isHeadless()) {
            return DEFAULT_MULTI_CLICK_INTERVAL_MS;
        }
        try {
            Object value = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
            if (value instanceof Number n) {
                return n.longValue();
            }
        } catch (Throwable t) {
            // AWT unavailable, headless, or platform-specific failure — fall back safely
        }

        return DEFAULT_MULTI_CLICK_INTERVAL_MS;
    });


    @FunctionalInterface
    interface Mouse {

        /**
         * Low-level mouse event representation
         * @param x Screen/Window X-coordinate of the mouse event (Top-Left origin)
         * @param y Screen/Window Y-coordinate of the mouse event (Top-Left origin)
         * @param dx Delta X
         * @param dy Delta Y
         * @param button {@link CgUiMouseCodes} button ID of the event or -1 for no click events
         * @param state state of the selected button. (True for pressed)
         * @param wheelDelta
         * @param millis Timestamp for click/release events, -1 for move events.
         */
        record Event(int x, int y, int dx, int dy, int button, boolean state, int wheelDelta, long millis) {}

        /**
         * Process event
         * @param event event to be processed
         * @return should event propagate
         */
        boolean consumeMouseEvent(SystemInput.Mouse.Event event);

    }

    @FunctionalInterface
    interface Keyboard {

        /**
         * Low-level mouse event representation
         * @param character character code
         * @param key {@link CgUiKeyCodes} keycode
         * @param pressed Whether the button was pressed (true) or released (false)
         * @param repeat Was this a repeated event or a unique event.
         * @param millis Timestamp of the event.
         */
        record Event(char character, int key, boolean pressed, boolean repeat, long millis) {}

        /**
         * Process event
         * @param event event to be processed
         * @return should event propagate
         */
        boolean consumeKeyboardEvent(SystemInput.Keyboard.Event event);

    }
}
