package com.crystalgui.core.input;

import com.crystalgui.core.data.CacheCell;

import java.awt.*;

/**
 * Interface responsible for consuming <b>raw</b> System input events.<br>
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
    CacheCell<Long> multiClickInterval = new CacheCell<Long>().setCalculator((ignore) -> {
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

        record Event(int x, int y, int dx, int dy, int button, boolean state, int wheelDelta, long nanos) {}

        /**
         * Process event
         * @param event event to be processed
         * @return should event propagate
         */
        boolean consumeMouseEvent(SystemInput.Mouse.Event event);

    }

    @FunctionalInterface
    interface Keyboard {

        record Event(int character, int key, boolean pressed, boolean repeat, long nanos) {}

        /**
         * Process event
         * @param event event to be processed
         * @return should event propagate
         */
        boolean consumeKeyboardEvent(SystemInput.Keyboard.Event event);

    }
}
