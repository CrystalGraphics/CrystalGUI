package com.crystalgui.core.input;

import com.crystalgui.core.event.CgUiDebug;
import com.crystalgui.core.event.UiEventDispatcher;
import com.crystalgui.core.event.UiEventType;
import com.crystalgui.core.event.UiMouseEvent;
import com.crystalgui.ui.UIContainer;
import com.crystalgui.ui.UIElement;

import javax.annotation.Nullable;

/**
 * Pointer input ingress: receives raw mouse events from the platform adapter,
 * performs hit testing, tracks hover/pressed state, and dispatches routed events.
 *
 * <p>Each mouse move performs exactly one hit test. The resolved target drives
 * hover enter/leave synthesis and move routing.</p>
 *
 * <p>Click synthesis requires matching press/release target and button within
 * distance threshold. Double-click resets after emission.</p>
 */
public final class UiInputManager {

    private static final float CLICK_DISTANCE_THRESHOLD = 5.0f;
    private static final long DOUBLE_CLICK_TIME_MS = 400;

    private final UIContainer container;

    @Nullable private UIElement hoveredElement;
    @Nullable private UIElement pressedElement;
    private int pressedButton = -1;
    private float pressX;
    private float pressY;

    // Click tracking
    private int clickCount;
    private long lastClickTimeMs;
    @Nullable private UIElement lastClickTarget;
    private int lastClickButton = -1;

    public UiInputManager(UIContainer container) {
        if (container == null) throw new IllegalArgumentException("container must not be null");
        this.container = container;
    }

    @Nullable
    public UIElement getHoveredElement() {
        return hoveredElement;
    }

    /**
     * Processes a mouse move event. Performs one hit test and uses it for
     * hover update and move routing.
     */
    public void processMouseMove(float x, float y, int modifiers) {
        CgUiDebug.logSyntheticInput("mouseMove", "(" + x + ", " + y + ")");

        UIElement root = container.getRoot();
        UIElement target = UiEventDispatcher.findTarget(root, x, y);

        // Hover enter/leave synthesis
        if (target != hoveredElement) {
            if (hoveredElement != null) {
                UiMouseEvent leave = UiMouseEvent.pointer(UiEventType.MOUSE_LEAVE, x, y);
                UiEventDispatcher.dispatchDirectEvent(hoveredElement, leave);
            }
            hoveredElement = target;
            if (hoveredElement != null) {
                UiMouseEvent enter = UiMouseEvent.pointer(UiEventType.MOUSE_ENTER, x, y);
                UiEventDispatcher.dispatchDirectEvent(hoveredElement, enter);
            }
        }

        // Dispatch MOUSE_MOVE via routed path
        if (target != null) {
            UiMouseEvent moveEvent = UiMouseEvent.pointer(UiEventType.MOUSE_MOVE, x, y, 0, modifiers);
            UiEventDispatcher.dispatchRoutedEvent(target, moveEvent);
        }
    }

    /**
     * Processes a mouse button press.
     */
    public void processMouseDown(float x, float y, int button, int modifiers) {
        CgUiDebug.logSyntheticInput("mouseDown", "(" + x + ", " + y + ") button=" + button);

        UIElement root = container.getRoot();
        UIElement target = UiEventDispatcher.findTarget(root, x, y);
        if (target == null) return;

        pressedElement = target;
        pressedButton = button;
        pressX = x;
        pressY = y;

        UiMouseEvent downEvent = UiMouseEvent.pointer(UiEventType.MOUSE_DOWN, x, y, button, modifiers);
        UiEventDispatcher.dispatchRoutedEvent(target, downEvent);

        // Click-to-focus: walk up from target to find nearest focusable ancestor
        if (container.getFocusManager() != null) {
            FocusManager focusManager = container.getFocusManager();
            UIElement focusTarget = findFocusableAncestor(target);
            if (focusTarget != null) {
                focusManager.requestFocus(focusTarget);
            }
        }
    }

    /**
     * Processes a mouse button release. Synthesizes CLICK/DOUBLE_CLICK if conditions are met.
     */
    public void processMouseUp(float x, float y, int button, int modifiers) {
        CgUiDebug.logSyntheticInput("mouseUp", "(" + x + ", " + y + ") button=" + button);

        UIElement root = container.getRoot();
        UIElement target = UiEventDispatcher.findTarget(root, x, y);
        if (target == null) return;

        UiMouseEvent upEvent = UiMouseEvent.pointer(UiEventType.MOUSE_UP, x, y, button, modifiers);
        UiEventDispatcher.dispatchRoutedEvent(target, upEvent);

        // Click synthesis: matching target, button, and within distance threshold
        if (pressedElement == target && pressedButton == button) {
            float dx = x - pressX;
            float dy = y - pressY;
            if (dx * dx + dy * dy <= CLICK_DISTANCE_THRESHOLD * CLICK_DISTANCE_THRESHOLD) {
                synthesizeClick(target, x, y, button);
            }
        }

        pressedElement = null;
        pressedButton = -1;
    }

    /**
     * Processes a mouse wheel event.
     */
    public void processMouseWheel(float x, float y, float scrollDelta, int modifiers) {
        CgUiDebug.logSyntheticInput("mouseWheel", "(" + x + ", " + y + ") delta=" + scrollDelta);

        UIElement root = container.getRoot();
        UIElement target = UiEventDispatcher.findTarget(root, x, y);
        if (target == null) return;

        UiMouseEvent wheelEvent = UiMouseEvent.wheel(x, y, scrollDelta, modifiers);
        UiEventDispatcher.dispatchRoutedEvent(target, wheelEvent);
    }

    /**
     * Walks up from the target to find the nearest ancestor (inclusive)
     * with a focusable FocusPolicy. Returns null if none found.
     */
    @Nullable
    private static UIElement findFocusableAncestor(UIElement target) {
        UIElement current = target;
        while (current != null) {
            if (current.getFocusPolicy() != FocusPolicy.NONE
                    && current.isVisible() && current.isEnabled()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private void synthesizeClick(UIElement target, float x, float y, int button) {
        long now = System.currentTimeMillis();

        // Check for double-click conditions
        if (target == lastClickTarget && button == lastClickButton
                && (now - lastClickTimeMs) < DOUBLE_CLICK_TIME_MS) {
            clickCount++;
        } else {
            clickCount = 1;
        }

        lastClickTarget = target;
        lastClickButton = button;
        lastClickTimeMs = now;

        // Dispatch CLICK with count
        UiMouseEvent clickEvent = UiMouseEvent.click(UiEventType.CLICK, x, y, button, clickCount);
        UiEventDispatcher.dispatchRoutedEvent(target, clickEvent);

        // Double-click synthesis: emit and reset
        if (clickCount >= 2) {
            UiMouseEvent dblClick = UiMouseEvent.click(UiEventType.DOUBLE_CLICK, x, y, button, clickCount);
            UiEventDispatcher.dispatchRoutedEvent(target, dblClick);
            // Reset click tracking after double-click
            clickCount = 0;
            lastClickTarget = null;
            lastClickButton = -1;
            lastClickTimeMs = 0;
        }
    }
}
