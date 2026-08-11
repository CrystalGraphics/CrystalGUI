package com.crystalgui.style;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.input.FocusPolicy;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code :focus-within} — true for the focused element and every ancestor of it.
 *
 * <p>The state a container needs to say "the focus is in me": which editor group a keystroke goes
 * to, which tool window is current. Registered as a real pseudo-class rather than emulated with a
 * class each container maintains, because the second is the same behaviour with two copies to keep
 * in step and no answer for the next container that wants it.</p>
 */
public class FocusWithinTest extends UiTestBase {

    private UIWindow window;
    private UIElement outer;
    private UIElement inner;
    private UIElement sibling;

    @Before
    public void setUp() {
        inner = new UIElement().layout(l -> l.width(10).height(10));
        inner.setFocusPolicy(FocusPolicy.CLICK);
        outer = new UIElement().layout(l -> l.width(50).height(50));
        outer.addClass("host");
        outer.addChild(inner);

        sibling = new UIElement().layout(l -> l.width(50).height(50));
        sibling.addClass("host");

        UIElement root = new UIElement().layout(l -> l.width(200).height(200));
        root.addChildren(outer, sibling);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(
                StyleSheet.parse(".host:focus-within { opacity: 0.25; }"));
        window.init(200, 200);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    private float opacityOf(UIElement e) {
        return e.getStyle().getGeneralGroup().opacity();
    }

    /** The predicate itself: an ANCESTOR of the focus owner is within, a sibling is not. */
    @Test
    public void anAncestorOfTheFocusOwnerIsWithin() {
        assertFalse(outer.isFocusWithin());

        window.getInputHandler().requestFocus(inner);
        settle();

        assertTrue("the focused element is within itself", inner.isFocusWithin());
        assertTrue("...and so is its ancestor", outer.isFocusWithin());
        assertFalse("but not an unrelated subtree", sibling.isFocusWithin());
    }

    /**
     * <b>The ancestor RE-MATCHES, which is the half that does not come for free.</b>
     *
     * <p>{@code setFocused} invalidates the element it is called on. For {@code :focus-within} that
     * is never the element whose style changed — it is every ancestor above it, none of which the
     * engine had any reason to touch. Without walking the chain the rule resolves correctly the
     * first time anything happens to re-match the container and never again, which reads as the
     * pseudo-class working intermittently.</p>
     */
    @Test
    public void focusingADescendantRestylesTheContainer() {
        assertEquals(1f, opacityOf(outer), 0.001f);

        window.getInputHandler().requestFocus(inner);
        settle();
        assertEquals("the container must restyle when focus enters it",
                0.25f, opacityOf(outer), 0.001f);

        window.getInputHandler().blurIfFocused(inner);
        settle();
        assertEquals("...and again when focus leaves", 1f, opacityOf(outer), 0.001f);
    }

    /**
     * <b>A tool window claims focus when clicked, so its tab can be tinted at all.</b>
     *
     * <p>The case that exposed this was an EMPTY Inspector: a press landing on an unfocusable body
     * clears focus outright ({@code emitMouseDown} blurs before it dispatches), so the container's
     * {@code :focus-within} was correctly false while the panel looked current. A press that lands
     * on something focusable inside must still leave it there — clicking a tree row focuses the ROW.</p>
     */
    @Test
    public void aViewContainerClaimsFocusOnlyWhenThePressLandsOnNothing() {
        com.crystalgui.ui.elements.workbench.ViewContainer container =
                new com.crystalgui.ui.elements.workbench.ViewContainer("probe", "Probe");
        UIElement focusable = new UIElement().layout(l -> l.width(10).height(10));
        focusable.setFocusPolicy(FocusPolicy.CLICK);
        container.content().addChild(focusable);

        UIElement root = new UIElement().layout(l -> l.width(200).height(200));
        root.addChild(container);
        UIWindow w = new UIWindow(Ui.of(root));
        w.init(200, 200);
        for (int i = 0; i < 3; i++) w.updateWithoutPainting();

        w.getInputHandler().requestPointerFocus(focusable);
        for (int i = 0; i < 3; i++) w.updateWithoutPainting();
        assertTrue("focus inside the container counts as within it", container.isFocusWithin());
        assertEquals("and the container must not steal it from its own content",
                focusable, w.getInputHandler().getFocusedElement());
    }

    /** A sheet naming it must parse — the guard being that an UNKNOWN pseudo-class takes the whole
     * sheet down, which is what happened to this exact name before it was registered. */
    @Test
    public void theSelectorParses() {
        assertFalse(StyleSheet.parse(".x:focus-within { opacity: 0.5; }").getRules().isEmpty());
    }
}
