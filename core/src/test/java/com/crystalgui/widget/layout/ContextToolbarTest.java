package com.crystalgui.widget.layout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.control.TextField;

/**
 * <b>One toolbar row whose page follows what is going on.</b>
 *
 * <p>The three things a consumer relies on without looking: the newest claim is what shows, the row does
 * not move what is under it when its page changes, and a page taken away while it holds focus does not
 * take the keyboard with it.</p>
 */
public class ContextToolbarTest extends UiDocumentTestBase {

    private UIElement toolbar;
    private ContextToolbar bar;
    private UIElement canvas;

    @Before
    public void build() {
        withDefaultStyles();
        toolbar = new UIElement();
        bar = new ContextToolbar(toolbar);
        canvas = new UIElement().layout(l -> l.widthPercent(100f).height(100f));
        canvas.setFocusPolicy(FocusPolicy.CLICK);
        UIElement column = new UIElement().layout(l -> l.width(400f).height(300f));
        column.append(bar, canvas);
        document.append(column);
        document.update(W, H);
    }

    @Test
    public void theNewestClaimShowsAndReleasingItRevealsTheOneUnder() {
        UIElement tool = new UIElement();
        UIElement gesture = new UIElement();
        Disposable toolClaim = bar.claim(tool);
        Disposable gestureClaim = bar.claim(gesture);
        assertSame(gesture, bar.shown());
        assertFalse(tool.isDisplayed());
        assertFalse(toolbar.isDisplayed());

        toolClaim.dispose();
        assertSame("releasing a buried claim changes nothing on screen", gesture, bar.shown());

        gestureClaim.dispose();
        gestureClaim.dispose();
        assertSame(toolbar, bar.shown());
        assertTrue(toolbar.isDisplayed());
        assertFalse(gesture.isDisplayed());
    }

    /** <b>The row's height is its own</b>, which is the whole reason the options are not a second row. */
    @Test
    public void aTallerPageDoesNotMoveWhatIsUnderTheBar() {
        float before = canvas.box().y();
        UIElement page = new UIElement();
        page.append(new UIElement().layout(l -> l.height(80f)));
        bar.claim(page);
        document.update(W, H);
        assertEquals("the canvas moved when the bar changed page", before, canvas.box().y(), 0.01f);
    }

    @Test
    public void aPageHiddenWhileFocusedGivesFocusBack() {
        document.focus().requestFocus(canvas);
        TextField field = new TextField();
        UIElement page = new UIElement();
        page.append(field);
        Disposable claim = bar.claim(page);
        document.update(W, H);
        document.focus().requestFocus(field);
        assertSame(field, document.focus().focused());

        claim.dispose();
        assertSame("focus stayed on a field nobody can see", canvas, document.focus().focused());
    }
}
