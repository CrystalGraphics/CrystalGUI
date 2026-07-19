package com.crystalgui.ui.input;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.data.CacheCell;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.input.SystemInput.Keyboard;
import com.crystalgui.core.input.SystemInput.Mouse;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import lombok.Getter;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

public final class UIInputHandler implements SystemInput.Keyboard, SystemInput.Mouse {
    public final static long multiClickInterval = SystemInput.multiClickInterval.get();

    private final UIWindow window;

    @Getter
    private float scrollDelta = 0;
    @Getter
    private final Vector2f accumulatedMouseChange = new Vector2f();

    private final HoverFrameData hoverFrameData = new HoverFrameData();
    private UIElement lastFrameHover;
    private UIElement focusedElement;

    private final ButtonState[] mouseButtonStates = new ButtonState[CrystalGuiCore.getAdapter().howManyMouseButtons()];

    public UIInputHandler(UIWindow window) {
        this.window = window;
    }

    public void beginFrame() {
        this.lastFrameHover = hoverFrameData.element();
        hoverFrameData.invalidate();
    }


    public void endFrame() {
        fireAccumulatedMouseEvents();
        updateHoverState();
        accumulatedMouseChange.zero();
        scrollDelta = 0;
    }

    private void fireAccumulatedMouseEvents() {
        // TODO: Mouse move, enter, leave, scroll events.
    }

    @Override
    public boolean consumeKeyboardEvent(Keyboard.Event event) {
        return false;
    }

    @Override
    public boolean consumeMouseEvent(Mouse.Event event) {
        hoverFrameData.updatePosition(event.x(), event.y());
        accumulatedMouseChange.add(event.dx(), event.dy());
        scrollDelta += event.wheelDelta();
        if (event.button() != -1) processMouseButtons(event);
        return false;
    }

    private void processMouseButtons(Mouse.Event event) {
        final UIElement target = hoverFrameData.element();
        updateButtonState(event);

        final int buttonOrdinal = event.button();
        final ButtonState buttonState = getMouseButtonState(buttonOrdinal);
        final int detail = buttonState == null ? 1 : buttonState.getDetail();

        if (event.state() && target != null)
            target.clicked();

        // TODO Capture, target, bubble. (Update focus as well)

    }

    private void updateButtonState(Mouse.Event event) {
        final int buttonOrdinal = event.button();
        final ButtonState buttonState = getMouseButtonState(buttonOrdinal);
        if (buttonState == null) return;
        buttonState.setState(event.state(), event.millis());
    }

    private void updateHoverState() {
        if (hoverFrameData.element() == this.lastFrameHover) return;
        // TODO: Hover pseudo-state update for the old & new hover element.
    }

    private @Nullable ButtonState getMouseButtonState(int button) {
        if (button >= mouseButtonStates.length) return null;
        if (button < 0) return null;
        if (mouseButtonStates[button] == null) {
            mouseButtonStates[button] = new ButtonState();
        }

        return mouseButtonStates[button];
    }

    private void emitMouseClickEvent(int button) {
    }

    private void emitMouseReleaseEvent(int button) {

    }

    private class HoverFrameData {
        private final Vector2f position = new Vector2f();

        private final CacheCell<UIElement> hoveredElement = new CacheCell<UIElement>().setCalculator(ignored -> UIInputHandler.this.window.getHoveredElement(position.x(), position.y()));

        void updatePosition(int x, int y) {
            if (x != position.x() || y != position.y()) hoveredElement.invalidate();
            position.set(x, y);
        }

        UIElement element() {
            return hoveredElement.get();
        }

        void invalidate() {
            hoveredElement.invalidate();
        }
    }
}
