package com.crystalgui.ui.input;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.data.CacheCell;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.input.SystemInput.Keyboard;
import com.crystalgui.core.input.SystemInput.Mouse;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.event.PropagationPhase;
import com.crystalgui.ui.event.UIEvent;
import lombok.Getter;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;

public final class UIInputHandler implements SystemInput.Keyboard, SystemInput.Mouse {
    public final static long multiClickInterval = SystemInput.multiClickInterval.get();

    private final UIWindow window;

    /**
     * Needed in case InputHandler starts processing mouse movements before elements cached their transforms.
     */
    private boolean firstFrameOver = false;

    @Getter
    private float scrollDelta = 0;
    @Getter
    private final Vector2f accumulatedMouseChange = new Vector2f();

    private final HoverFrameData hoverFrameData = new HoverFrameData();
    private UIElement lastPressedElement;
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
        accumulatedMouseChange.zero();
        scrollDelta = 0;
        firstFrameOver = true;
    }

    public void sendInputEvent(UIElement element, UIEvent event) {
        var target = element;

        if (target == null) return;
        ArrayList<UIElement> path = new ArrayList<>();
        path.add(target);
        while (target.getParent() != null) {
            target = target.getParent();
            path.add(target);
        }

        Class<? extends UIEvent> eventClass = event.getClass();
        event.setPhase(PropagationPhase.CAPTURE);
        for (int i = path.size()-1; i > 0; i--) {
            var eventListeners = path.get(i).events;
            if (eventListeners.hasGroup(eventClass))
                eventListeners.emitToGroup(event);
        }

        event.setPhase(PropagationPhase.TARGET);
        element.events.emitToGroup(event);

        if (!event.isBubbles()) return;
        event.setPhase(PropagationPhase.BUBBLE);
        for (int i = 1; i < path.size(); i++) {
            var eventListeners = path.get(i).events;
            if (eventListeners.hasGroup(eventClass))
                eventListeners.emitToGroup(event);
        }


    }

    private void fireAccumulatedMouseEvents() {
        final var lastHover = this.lastFrameHover;
        final var currentHover = hoverFrameData.element();
        if (lastHover == currentHover){
            emitMouseMove(lastHover);
        } else {
            emitMouseLeave(lastHover);
            emitMouseEnter(currentHover);
        }
        if (scrollDelta != 0)
            emitMouseScroll(currentHover);
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
        updateButtonState(event, target);

        final int buttonOrdinal = event.button();
        final ButtonState buttonState = getMouseButtonState(buttonOrdinal);
        final int detail = buttonState == null ? 1 : buttonState.getDetail();

        if (event.state()) {
            this.lastPressedElement = target;
            emitMouseDown(target, buttonOrdinal, detail);
        } else {
            emitMouseUp(target, buttonOrdinal, detail);
        }

    }

    private @Nullable ButtonState getMouseButtonState(int button) {
        if (button >= mouseButtonStates.length) return null;
        if (button < 0) return null;
        if (mouseButtonStates[button] == null) {
            mouseButtonStates[button] = new ButtonState();
        }

        return mouseButtonStates[button];
    }

    private void updateButtonState(Mouse.Event event, UIElement target) {
        final int buttonOrdinal = event.button();
        final ButtonState buttonState = getMouseButtonState(buttonOrdinal);
        if (buttonState == null) return;
        if (event.state() && target != lastPressedElement) buttonState.resetDetail();
        buttonState.setState(event.state(), event.millis());
    }

    private void emitMouseDown(UIElement targetElement, int buttonId, int detail) {
        if (targetElement != focusedElement) {
            if (focusedElement != null) {
                emitAndLoseFocus(focusedElement);
            }
            if (targetElement.getFocusPolicy() == FocusPolicy.CLICK) {
                emitAndSetFocus(targetElement);
            }
        }

        var event = new MouseEvent.Down(targetElement, hoverFrameData.eventPosition(), buttonId, detail);
        sendInputEvent(targetElement, event);
    }

    private void emitMouseUp(UIElement target, int buttonId, int detail) {
        MouseEvent.Up event = new MouseEvent.Up(target, hoverFrameData.eventPosition(), buttonId, detail);
        sendInputEvent(target, event);
    }

    private void emitMouseScroll(UIElement target) {
        MouseEvent.Scroll event = new MouseEvent.Scroll(target, hoverFrameData.eventPosition(), scrollDelta);
        sendInputEvent(target, event);
    }

    private void emitMouseEnter(UIElement target) {
        emitMouseMove(target);
        MouseEvent.Enter event = new MouseEvent.Enter(target, hoverFrameData.eventPosition());
        sendInputEvent(target, event);
    }

    private void emitMouseLeave(UIElement target) {
        emitMouseMove(target);
        MouseEvent.Leave event = new MouseEvent.Leave(target, hoverFrameData.eventPosition());
        sendInputEvent(target, event);
    }

    private void emitMouseMove(UIElement target) {
        MouseEvent.Move event = new MouseEvent.Move(target, hoverFrameData.eventPosition());
        sendInputEvent(target, event);
    }


    private void emitAndSetFocus(UIElement target) {
        FocusEvent.Focus event = new FocusEvent.Focus(target);
        sendInputEvent(target, event);
    }

    private void emitAndLoseFocus(UIElement target) {
        FocusEvent.Blur event = new FocusEvent.Blur(target);
        sendInputEvent(target, event);
    }


    private class HoverFrameData {
        private final Vector2f position = new Vector2f();
        private final ReadOnlyVec2f sealedVec2f = new ReadOnlyVec2f(position); /* GC-safe, read only representation of position. Used for mouse events */

        private final CacheCell<UIElement> hoveredElement = new CacheCell<UIElement>()
                .setCalculator(ignored -> UIInputHandler.this.window.getHoveredElement(position.x(), position.y()))
                .set(null);

        boolean positionChanged(int x, int y) {
            return x != position.x() || y != position.y();
        }

        void updatePosition(int x, int y) {
            if (UIInputHandler.this.firstFrameOver && positionChanged(x, y)) hoveredElement.invalidate();
            position.set(x, y);
        }

        UIElement element() {
            return hoveredElement.get();
        }

        void invalidate() {
            hoveredElement.invalidate();
        }

        ReadOnlyVec2f eventPosition() {
            return sealedVec2f;
        }
    }

    public void resetHandler() {
        firstFrameOver = false;
    }
}
