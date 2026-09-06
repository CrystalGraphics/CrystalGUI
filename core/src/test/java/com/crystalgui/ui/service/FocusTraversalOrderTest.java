package com.crystalgui.ui.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;

/**
 * <b>L3.2 / E7 — the Tab sequence, read without walking it.</b>
 *
 * <p>Stepping through with {@code nextTabbable} to find out what the order is means MOVING focus to read
 * it: focus events fire, things scroll into view, and the inspector asking the question re-points itself
 * at the answer. The order is destroyed by the act of asking for it that way.</p>
 */
public class FocusTraversalOrderTest extends UiDocumentTestBase {

    private UIElement stop(String id) {
        UIElement element = new UIElement().layout(l -> l.width(20).height(10));
        element.setId(id);
        element.setFocusPolicy(FocusPolicy.CLICK);
        return element;
    }

    @Test
    public void itAnswersTheTabSequenceInOrder() {
        UIElement scope = new UIElement().layout(l -> l.width(200).height(100));
        UIElement first = stop("first");
        UIElement second = stop("second");
        UIElement third = stop("third");
        scope.append(first);
        scope.append(second);
        scope.append(third);
        document.append(scope);
        document.update(W, H);

        List<UIElement> order = document.focus().traversalOrder(scope);

        assertEquals(List.of(first, second, third), order);
        assertNull("and reading it moved nothing", document.focus().focused());
    }

    @Test
    public void theScopeItselfIsNotAStopAndUnfocusableChildrenAreSkipped() {
        UIElement scope = new UIElement().layout(l -> l.width(200).height(100));
        scope.setFocusPolicy(FocusPolicy.CLICK);
        UIElement label = new UIElement().layout(l -> l.width(20).height(10));
        UIElement stop = stop("stop");
        scope.append(label);
        scope.append(stop);
        document.append(scope);
        document.update(W, H);

        List<UIElement> order = document.focus().traversalOrder(scope);

        assertEquals("only the one that takes Tab", List.of(stop), order);
        assertFalse("the container is what was asked ABOUT", order.contains(scope));
    }

    /** Empty is an ordinary answer — a panel of labels takes no Tab at all. */
    @Test
    public void aScopeWithNothingTabbableIsEmptyRatherThanNull() {
        UIElement scope = new UIElement().layout(l -> l.width(200).height(100));
        scope.append(new UIElement().layout(l -> l.width(20).height(10)));
        document.append(scope);
        document.update(W, H);

        assertTrue(document.focus().traversalOrder(scope).isEmpty());
    }
}
