package com.crystalgui.ui.input;

import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.input.SystemInput.Keyboard;
import com.crystalgui.core.input.SystemInput.Mouse;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import lombok.Getter;
import org.joml.Vector2f;

import java.awt.*;

public class UIInputHandler implements SystemInput.Keyboard, SystemInput.Mouse {

    private final static long multiClickInterval = ((Long) Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval"));

    private final UIWindow window;

    @Getter
    private float scrollDelta = 0;
    @Getter
    private final Vector2f mouseFramePositionGlobal = new Vector2f();
    @Getter
    private final Vector2f accumulatedMouseChange = new Vector2f();

    private UIElement hoveredElement;
    private UIElement focusedElement;

    public UIInputHandler(UIWindow window) {
        this.window = window;
    }


    public void endFrame() {

        accumulatedMouseChange.zero();
        scrollDelta = 0;
    }

    @Override
    public boolean consumeKeyboardEvent(Keyboard.Event event) {
        return false;
    }

    @Override
    public boolean consumeMouseEvent(Mouse.Event event) {
        mouseFramePositionGlobal.set(event.x(), event.y());
        accumulatedMouseChange.add(event.dx(), event.dy());
        scrollDelta += event.wheelDelta();
        processMouseButtons(event);
        return false;
    }

    private void processMouseButtons(Mouse.Event event) {

    }
}
