package com.crystalgui.widget.layout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgKeyCodes;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.control.TextField;

/**
 * <b>One toolbar row whose page follows what is going on.</b>
 *
 * <p>The things a consumer relies on without looking: the newest claim is what shows, the row does not move
 * what is under it when its page changes or overflows, a page taken away while it holds focus does not take
 * the keyboard with it, and a field on the row is a stop the keyboard leaves again.</p>
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

    /** <b>Enter lands a field and Escape drops what was typed</b>, and both hand the keyboard back. */
    @Test
    public void enterLandsAFieldAndEscapeDropsItAndBothHandTheKeyboardBack() {
        document.focus().requestFocus(canvas);
        TextField field = new TextField();
        UIElement page = new UIElement();
        page.append(field);
        bar.claim(page);
        document.update(W, H);

        document.focus().requestFocus(field);
        field.insertChar('7');
        key(CgKeyCodes.KEY_RETURN, true);
        assertEquals("Enter lands what was typed", "7", field.getValue());
        assertSame("and gives the keyboard back", canvas, document.focus().focused());

        document.focus().requestFocus(field);
        field.insertChar('8');
        key(CgKeyCodes.KEY_ESCAPE, true);
        assertEquals("Escape drops what was typed", "7", field.getText());
        assertSame(canvas, document.focus().focused());
    }

    /** <b>What does not fit folds from the end behind the »</b>, and what is under the bar does not move. */
    @Test
    public void whatDoesNotFitFoldsFromTheEndBehindTheOverflowButton() {
        UIElement[] items = sixWideItems();
        UIElement page = new UIElement();
        page.append(items);
        float before = canvas.box().y();

        bar.claim(page);
        document.frame(0f, W, H);

        assertTrue("six 100px items in a 400px row", bar.isOverflowing());
        assertTrue(bar.overflowButton().isDisplayed());
        assertTrue("the first item fits", items[0].isDisplayed());
        assertFalse("and the last is folded", items[5].isDisplayed());
        assertEquals("the row moved what is under it", before, canvas.box().y(), 0.01f);
    }

    /** <b>» lends what was folded to its popover</b>, and closing it puts every item back where it was. */
    @Test
    public void theOverflowButtonLendsTheFoldedItemsAndTakesThemBack() {
        UIElement[] items = sixWideItems();
        UIElement page = new UIElement();
        page.append(items);
        bar.claim(page);
        document.frame(0f, W, H);

        int[] at = centreOf(bar.overflowButton());
        click(at[0], at[1]);
        assertTrue(bar.overflowPanel().isOpen());
        assertSame("the last item is in the popover", bar.overflowPanel(), items[5].parent());

        bar.overflowPanel().hide();
        assertSame("and back on its page", page, items[5].parent());
        assertEquals("in the place it left", 5, page.indexOf(items[5]));
    }

    /** <b>A separator never ends the row</b> — it folds with what followed it — <b>nor opens the popover.</b> */
    @Test
    public void aSeparatorNeverEndsTheRowNorOpensThePopover() {
        UIElement[] items = sixWideItems();
        UIElement separator = ContextToolbar.separator();
        UIElement page = new UIElement();
        page.append(items[0], items[1], items[2], separator, items[3], items[4]);
        bar.claim(page);
        document.frame(0f, W, H);

        assertTrue("three items fit", items[2].isDisplayed());
        assertFalse("and the separator after them folds with the rest", separator.isDisplayed());

        int[] at = centreOf(bar.overflowButton());
        click(at[0], at[1]);
        assertSame("the popover opens on an item", bar.overflowPanel(), items[3].parent());
        assertSame("and the separator stays behind", page, separator.parent());
    }

    private static UIElement[] sixWideItems() {
        UIElement[] items = new UIElement[6];
        for (int i = 0; i < items.length; i++) {
            items[i] = new UIElement().layout(l -> l.width(100f).height(10f));
        }
        return items;
    }
}
