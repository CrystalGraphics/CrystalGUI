package com.crystalgui.core.event;

import com.crystalgui.ui.UIElement;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class UiEventDispatcher {

    private UiEventDispatcher() {
    }

    public static void dispatchPointerEvent(UIElement root, UiEvent event) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }

        UIElement target = findTarget(root, event.getX(), event.getY());
        if (target == null) {
            return;
        }

        event.setTarget(target);
        List<UIElement> path = buildPath(target);

        for (int i = path.size() - 1; i > 0; i--) {
            UIElement element = path.get(i);
            event.setCurrentTarget(element);
            event.setPhase(UiEventPhase.CAPTURE);
            element.invokeCaptureListeners(event);
            if (event.isPropagationStopped()) {
                return;
            }
        }

        event.setCurrentTarget(target);
        event.setPhase(UiEventPhase.TARGET);
        target.invokeBubbleListeners(event);
        if (event.isPropagationStopped()) {
            return;
        }

        for (int i = 1; i < path.size(); i++) {
            UIElement element = path.get(i);
            event.setCurrentTarget(element);
            event.setPhase(UiEventPhase.BUBBLE);
            element.invokeBubbleListeners(event);
            if (event.isPropagationStopped()) {
                return;
            }
        }
    }

    @Nullable
    private static UIElement findTarget(UIElement element, float x, float y) {
        if (!element.containsPoint(x, y)) {
            return null;
        }

        List<UIElement> children = element.getChildren();
        for (int i = children.size() - 1; i >= 0; i--) {
            UIElement target = findTarget(children.get(i), x, y);
            if (target != null) {
                return target;
            }
        }
        return element;
    }

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
