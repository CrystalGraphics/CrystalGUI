package com.crystalgui.ui.input;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.data.CacheCell;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.input.SystemInput.Keyboard;
import com.crystalgui.core.input.SystemInput.Mouse;
import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
import com.crystalgui.core.input.keyboard.Modifiers;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.tree.UITreeTraversal;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.event.*;
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
    private UIElement lastPressedElement;
    private UIElement lastFrameHover;
    private UIElement focusedElement;
    /** Tracks which element a Space-key hold is acting on, so releasing Space always resets that
     * element's pressed state — even if focus moved elsewhere mid-hold — and so the synthesized
     * mouse-up's wasPressTarget can tell "space held over this element, released while still
     * focused here" apart from "focus moved away mid-hold." */
    private UIElement keyboardPressTarget;

    private boolean firstFrameOver = false;

    private final ButtonState[] mouseButtonStates = new ButtonState[CrystalGuiCore.getAdapter().howManyMouseButtons()];

    public UIInputHandler(UIWindow window) {
        this.window = window;
    }

    /**
     * Forces the hover hit-test to recompute this frame, regardless of whether the mouse moved.
     * {@code UIWindow.paintFrame()} calls this AFTER that frame's layout has already been recomputed
     * — without it, the hover cache would only ever refresh on genuine mouse-position changes
     * ({@link HoverFrameData#updatePosition}), so a UI reflow under a perfectly stationary cursor
     * (an element resizing/moving via a transition, a stylesheet re-match, children added/removed)
     * would leave the hovered element stale until the next real mouse movement happened to occur.
     * Unconditional per-frame invalidation is simpler than only invalidating when layout genuinely
     * changed (which would need a new signal threaded out of {@code UIWindow.calculateLayout()}) and
     * matches this engine's existing immediate-mode philosophy of recomputing freely each frame
     * rather than optimizing for skipped work.
     *
     * <p>Hover snapshotting/diffing itself still happens entirely in {@link #endFrame()} via
     * {@link #fireAccumulatedMouseEvents()} — this method only invalidates the cache {@code endFrame()}
     * will then read; it must not also read/snapshot it here (that was the original stuck-hover bug:
     * ordinary mouse-move events already invalidate this same cache before this method runs each
     * frame, so reading it here was really an eager recompute against the *new* position mislabeled
     * as the *old* one).
     */
    public void beginFrame() {
        hoverFrameData.invalidate();
    }

    public void endFrame() {
        fireAccumulatedMouseEvents();
        accumulatedMouseChange.zero();
        scrollDelta = 0;
        firstFrameOver = true;
    }

    public void sendInputEvent(UIElement element, UIEvent event) {
        if (element == null) return;
        UIElement[] path = UITreeTraversal.pathToRoot(element); // root-first, path[path.length - 1] == element

        event.setPhase(PropagationPhase.CAPTURE);
        for (int i = 0; i < path.length - 1; i++) {
            path[i].events.emitToGroup(event);
        }

        event.setPhase(PropagationPhase.TARGET);
        element.events.emitToGroup(event);

        if (!event.isBubbles()) return;
        event.setPhase(PropagationPhase.BUBBLE);
        for (int i = path.length - 2; i >= 0; i--) {
            path[i].events.emitToGroup(event);
        }
    }

    private void fireAccumulatedMouseEvents() {
        final var lastHover = this.lastFrameHover;
        final var currentHover = hoverFrameData.element();

        if (lastHover == currentHover) {
            emitMouseMove(lastHover);
        } else {
            updateHoverChain(lastHover, currentHover);
            emitMouseLeave(lastHover);
            emitMouseEnter(currentHover);
        }

        if (scrollDelta != 0)
            emitMouseScroll(currentHover);

        // Snapshot for next frame's diff — must happen after use above, and must be a plain field
        // write here (not a read of the live hoverFrameData cache at the top of the next frame),
        // otherwise a mouse-move event that arrives before beginFrame() next frame would invalidate
        // the cache first and silently corrupt this "old" value into the "new" one.
        this.lastFrameHover = currentHover;
    }

    private void updateHoverChain(UIElement oldHover, UIElement newHover) {
        final var commonAncestor = UITreeTraversal.commonAncestor(oldHover, newHover);

        for (var e = oldHover; e != null && e != commonAncestor; e = e.getParent()) {
            e.setHovered(false);
        }
        for (var e = newHover; e != null && e != commonAncestor; e = e.getParent()) {
            e.setHovered(true);
        }
    }

    @Override
    public boolean consumeKeyboardEvent(Keyboard.Event event) {
        if (!firstFrameOver) return false;
        CgUiInputAdapter inputAdapter = CrystalGuiCore.getAdapter();
        final int modifiers = inputAdapter.getCurrentModifiers();

        if (focusedElement == null) {
            findFocusableElement(event, modifiers);
            return false;
        }

        if (event.pressed()) {
            var propagationStopped = emitKeyboardDown(event, modifiers);
            if (!propagationStopped) {
                findFocusableElement(event, modifiers);
            }
        } else {
            emitKeyboardUp(event, modifiers);
        }
        handleActivationKey(event);
        return false;
    }

    /**
     * Web-standard keyboard activation: while an element is focused, Space/Enter act like a mouse
     * press over it. Deliberately generic (lives here, not on Button) — it synthesizes the same
     * {@link MouseEvent.Down}/{@link MouseEvent.Up} (with {@code wasPressTarget} correctly computed)
     * that a real mouse click would, so any focusable widget listening for mouse activation (Button's
     * {@code onMouseUp}-based decorator, and later Checkbox's) gets keyboard activation for free with
     * zero widget-specific keyboard code.
     *
     * <p>Enter activates immediately on key-down with no hold state, matching real browsers — Space
     * has press-and-hold semantics mirroring an actual mouse press, so it needs the held-until-release
     * tracking in {@link #keyboardPressTarget}.
     */
    private void handleActivationKey(Keyboard.Event event) {
        if (focusedElement == null) return;
        if (event.key() != CgUiKeyCodes.KEY_SPACE && event.key() != CgUiKeyCodes.KEY_RETURN) return;

        if (event.pressed() && !event.repeat()) {
            keyboardPressTarget = focusedElement;
            focusedElement.setPressed(true);
            sendInputEvent(focusedElement, new MouseEvent.Down(focusedElement, hoverFrameData.eventPosition(), 0, 1));
        } else if (!event.pressed()) {
            boolean wasPressTarget = focusedElement == keyboardPressTarget; // false if focus moved mid-hold
            if (keyboardPressTarget != null) keyboardPressTarget.setPressed(false);
            sendInputEvent(focusedElement, new MouseEvent.Up(focusedElement, hoverFrameData.eventPosition(), 0, 1, wasPressTarget));
            keyboardPressTarget = null;
        }

    }

    private void findFocusableElement(Keyboard.Event event, int modifiers) {
        if (event.key() != CgUiKeyCodes.KEY_TAB) return;
        boolean reverse = Modifiers.hasShift(modifiers);

        UIElement next;
        if (focusedElement == null) {
            next = reverse
                    ? UITreeTraversal.lastFocusableIn(window.ui.rootElement)
                    : UITreeTraversal.firstFocusableIn(window.ui.rootElement);
        } else {
            next = reverse
                    ? UITreeTraversal.previousFocusable(focusedElement)
                    : UITreeTraversal.nextFocusable(focusedElement);
            if (next == null) { // fell off the end — wrap around
                next = reverse
                        ? UITreeTraversal.lastFocusableIn(window.ui.rootElement)
                        : UITreeTraversal.firstFocusableIn(window.ui.rootElement);
            }
        }
        if (next == null) return; // nothing focusable at all

        if (focusedElement != null) emitAndLoseFocus(focusedElement);
        focusedElement = next;
        emitAndSetFocus(focusedElement);
    }

    private boolean emitKeyboardDown(Keyboard.Event event, int modifiers) {
        KeyboardEvent.Down newEvent = new KeyboardEvent.Down(focusedElement, event.key(), event.character(), event.repeat(), modifiers, event.millis());
        sendInputEvent(focusedElement, newEvent);
        return newEvent.isPropagationStopped() || newEvent.isPhasePropagationStopped() || newEvent.isDefaultPrevented();
    }

    private void emitKeyboardUp(Keyboard.Event event, int modifiers) {
        KeyboardEvent.Up newEvent = new KeyboardEvent.Up(focusedElement, event.key(), event.character(), event.repeat(), modifiers, event.millis());
        sendInputEvent(focusedElement, newEvent);
    }

    @Override
    public boolean consumeMouseEvent(Mouse.Event event) {
        if (!firstFrameOver) return false;
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
            if (this.lastPressedElement != null && buttonOrdinal == 0) this.lastPressedElement.setPressed(true);
            emitMouseDown(target, buttonOrdinal, detail);
        } else {
            // Capture before lastPressedElement's pressed flag resets below.
            boolean wasPressTarget = target == lastPressedElement;
            if (this.lastPressedElement != null && buttonOrdinal == 0) this.lastPressedElement.setPressed(false);
            emitMouseUp(target, buttonOrdinal, detail, wasPressTarget);
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
            if (targetElement != null && targetElement.getFocusPolicy() == FocusPolicy.CLICK) {
                emitAndSetFocus(targetElement);
            }
        }

        var event = new MouseEvent.Down(targetElement, hoverFrameData.eventPosition(), buttonId, detail);
        sendInputEvent(targetElement, event);
    }

    private void emitMouseUp(UIElement target, int buttonId, int detail, boolean wasPressTarget) {
        MouseEvent.Up event = new MouseEvent.Up(target, hoverFrameData.eventPosition(), buttonId, detail, wasPressTarget);
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
        this.focusedElement = target;
        if (target != null) target.setFocused(true);
        FocusEvent.Focus event = new FocusEvent.Focus(target);
        sendInputEvent(target, event);
    }

    private void emitAndLoseFocus(UIElement target) {
        this.focusedElement = null;
        if (target == null) return;
        target.setFocused(false);
        target.setPressed(false);
        FocusEvent.Blur event = new FocusEvent.Blur(target);
        sendInputEvent(target, event);
    }

    private class HoverFrameData {
        private final Vector2f position = new Vector2f();
        private final ReadOnlyVec2f sealedVec2f = new ReadOnlyVec2f(position);

        private final CacheCell<UIElement> hoveredElement = new CacheCell<UIElement>()
                .setCalculator(ignored -> UIInputHandler.this.window.getHoveredElement(position.x(), position.y()))
                .set(null);

        boolean positionChanged(int x, int y) {
            return x != position.x() || y != position.y();
        }

        void updatePosition(int x, int y) {
            if (positionChanged(x, y)) hoveredElement.invalidate();
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