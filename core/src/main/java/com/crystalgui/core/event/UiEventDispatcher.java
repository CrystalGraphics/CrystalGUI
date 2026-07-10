package com.crystalgui.core.event;

import com.crystalgui.ui.UIElement;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless dispatcher for DOM-style three-phase routed events and
 * direct-target events.
 *
 * <p>Pointer event coordinates are in <b>absolute container space</b>.</p>
 */
public final class UiEventDispatcher {

    private UiEventDispatcher() {
    }

    // ── Public hit testing ──────────────────────────────────────────────

    /**
     * Finds the topmost hit-testable element at the given point.
     *
     * <p>Children are tested in reverse child order (last child = topmost).
     * Invisible or hit-test-disabled elements are not candidates.
     * Disabled elements remain hit-testable.</p>
     *
     * @param root the root element to begin searching from
     * @param x    absolute container-space X
     * @param y    absolute container-space Y
     * @return the topmost target, or null if nothing was hit
     */
    @Nullable
    public static UIElement findTarget(UIElement root, float x, float y) {
        if (!root.containsPoint(x, y)) {
            return null;
        }

        List<UIElement> children = root.getChildren();
        for (int i = children.size() - 1; i >= 0; i--) {
            UIElement target = findTarget(children.get(i), x, y);
            if (target != null) {
                return target;
            }
        }
        CgUiDebug.logHitTest(root, x, y);
        return root;
    }

    // ── Routed (bubbling) dispatch ──────────────────────────────────────

    /**
     * Dispatches a bubbling event using hit-testing from the root.
     *
     * <p>Performs hit testing to find the target, then routes through
     * capture → target → bubble phases.</p>
     *
     * @param root  the root element to hit-test from
     * @param event the event to dispatch (must have bubbles() == true)
     */
    public static void dispatchPointerEvent(UIElement root, UiEvent event) {
        if (root == null) throw new IllegalArgumentException("root must not be null");
        if (event == null) throw new IllegalArgumentException("event must not be null");

        UIElement target = null;
        if (event instanceof UiMouseEvent) {
            UiMouseEvent me = (UiMouseEvent) event;
            target = findTarget(root, me.getX(), me.getY());
        }
        if (target == null) return;

        dispatchRoutedEvent(target, event);
    }

    /**
     * Dispatches a bubbling event to a pre-resolved target.
     *
     * <p>Use this when the caller has already resolved the target (e.g.,
     * the input manager resolved it via a single hit test and needs to
     * dispatch multiple events to the same target without re-testing).</p>
     *
     * @param target the already-resolved target element
     * @param event  the event to dispatch (must have bubbles() == true)
     */
    public static void dispatchRoutedEvent(UIElement target, UiEvent event) {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        if (event == null) throw new IllegalArgumentException("event must not be null");

        event.setTarget(target);
        List<UIElement> path = buildPath(target);

        // Capture phase: root → target parent
        for (int i = path.size() - 1; i > 0; i--) {
            UIElement element = path.get(i);
            event.setCurrentTarget(element);
            event.setPhase(UiEventPhase.CAPTURE);
            CgUiDebug.logEventDispatch(event, element, UiEventPhase.CAPTURE);
            element.invokeCaptureListeners(event);
            if (event.isPropagationStopped()) return;
        }

        // Target phase
        event.setCurrentTarget(target);
        event.setPhase(UiEventPhase.TARGET);
        CgUiDebug.logEventDispatch(event, target, UiEventPhase.TARGET);
        target.invokeBubbleListeners(event);
        if (event.isPropagationStopped()) return;

        // Bubble phase: target parent → root
        for (int i = 1; i < path.size(); i++) {
            UIElement element = path.get(i);
            event.setCurrentTarget(element);
            event.setPhase(UiEventPhase.BUBBLE);
            CgUiDebug.logEventDispatch(event, element, UiEventPhase.BUBBLE);
            element.invokeBubbleListeners(event);
            if (event.isPropagationStopped()) return;
        }
    }

    // ── Direct-target dispatch (non-bubbling) ───────────────────────────

    /**
     * Dispatches a non-bubbling event directly to the target element only.
     *
     * <p>Used for {@code MOUSE_ENTER}, {@code MOUSE_LEAVE}, {@code FOCUS_IN},
     * and {@code FOCUS_OUT}. No capture or bubble phases are executed.</p>
     *
     * @param target the target element
     * @param event  the event (should have bubbles() == false)
     */
    public static void dispatchDirectEvent(UIElement target, UiEvent event) {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        if (event == null) throw new IllegalArgumentException("event must not be null");

        event.setTarget(target);
        event.setCurrentTarget(target);
        event.setPhase(UiEventPhase.TARGET);
        CgUiDebug.logEventDispatch(event, target, UiEventPhase.TARGET);
        target.invokeBubbleListeners(event);
    }

    // ── Path helpers ────────────────────────────────────────────────────

    private static List<UIElement> buildPath(UIElement target) {
        List<UIElement> path = new ArrayList<UIElement>();
        UIElement current = target;
        while (current != null) {
            path.add(current);
            current = current.getParent();
        }
        return path;
    }
}
