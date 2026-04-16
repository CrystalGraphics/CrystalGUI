package com.crystalgui.core.input;

import com.crystalgui.core.event.CgUiDebug;
import com.crystalgui.core.event.CgUiKeyCodes;
import com.crystalgui.core.event.Modifiers;
import com.crystalgui.core.event.UiEvent;
import com.crystalgui.core.event.UiEventDispatcher;
import com.crystalgui.core.event.UiEventType;
import com.crystalgui.core.event.UiKeyEvent;
import com.crystalgui.ui.UIContainer;
import com.crystalgui.ui.UIElement;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages focus for a single UIContainer.
 *
 * <p>Focus belongs to one element per container. Key events target the focused
 * element if present; otherwise they target the root.</p>
 *
 * <p>Focus is cleared automatically when the focused element detaches,
 * becomes invisible, or becomes disabled.</p>
 */
public final class FocusManager {

    private final UIContainer container;
    @Nullable private UIElement focusedElement;

    public FocusManager(UIContainer container) {
        if (container == null) throw new IllegalArgumentException("container must not be null");
        this.container = container;
    }

    @Nullable
    public UIElement getFocusedElement() {
        return focusedElement;
    }

    /**
     * Requests focus for the given element. If another element is focused,
     * it receives FOCUS_OUT first, then the new element receives FOCUS_IN.
     *
     * @param element the element to focus (must be focusable, visible, enabled)
     */
    public void requestFocus(@Nullable UIElement element) {
        if (element != null && element.getFocusPolicy() == FocusPolicy.NONE) return;
        if (element != null && (!element.isVisible() || !element.isEnabled())) return;
        if (element == focusedElement) return;

        CgUiDebug.logFocusTransition(focusedElement, element);

        UIElement oldFocus = focusedElement;
        focusedElement = element;

        if (oldFocus != null) {
            UiEvent focusOut = UiEvent.of(UiEventType.FOCUS_OUT);
            UiEventDispatcher.dispatchDirectEvent(oldFocus, focusOut);
            oldFocus.markRenderDirty();
        }
        if (element != null) {
            UiEvent focusIn = UiEvent.of(UiEventType.FOCUS_IN);
            UiEventDispatcher.dispatchDirectEvent(element, focusIn);
            element.markRenderDirty();
        }
    }

    /** Clears focus without emitting events (used during container teardown). */
    public void clearFocusSilently() {
        focusedElement = null;
    }

    /**
     * Validates that the focused element is still eligible. If it detached,
     * became invisible, or became disabled, clears focus.
     */
    public void validateFocus() {
        if (focusedElement == null) return;
        if (!focusedElement.isAttached() || !focusedElement.isVisible() || !focusedElement.isEnabled()) {
            requestFocus(null);
        }
    }

    /**
     * Advances focus to the next focusable element in document order.
     *
     * @param reverse true for shift+tab (reverse order)
     */
    public void moveFocus(boolean reverse) {
        List<UIElement> focusable = collectFocusable();
        if (focusable.isEmpty()) return;

        int currentIndex = focusedElement != null ? focusable.indexOf(focusedElement) : -1;

        int nextIndex;
        if (reverse) {
            nextIndex = currentIndex <= 0 ? focusable.size() - 1 : currentIndex - 1;
        } else {
            nextIndex = currentIndex >= focusable.size() - 1 ? 0 : currentIndex + 1;
        }

        requestFocus(focusable.get(nextIndex));
    }

    /**
     * Dispatches a key event to the focused element (or root if none).
     */
    public void dispatchKeyEvent(UiKeyEvent event) {
        // Tab/Shift-Tab focus traversal
        if (event.getType() == UiEventType.KEY_DOWN && event.getKeyCode() == CgUiKeyCodes.KEY_TAB) {
            moveFocus(event.hasShift());
            event.preventDefault();
            return;
        }

        UIElement target = focusedElement != null ? focusedElement : container.getRoot();
        UiEventDispatcher.dispatchRoutedEvent(target, event);
    }

    private List<UIElement> collectFocusable() {
        List<UIElement> all = new ArrayList<>();
        container.getRoot().collectSubtreeDocumentOrder(all);

        List<UIElement> focusable = new ArrayList<>();
        for (UIElement el : all) {
            if (el.getFocusPolicy() != FocusPolicy.NONE && el.isVisible() && el.isEnabled()) {
                focusable.add(el);
            }
        }
        return focusable;
    }
}
