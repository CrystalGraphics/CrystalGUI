package com.crystalgui.ui;

import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.chrome.StatusBarView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Parts step 6 — the status bar's view half.
 *
 * <p>What these pin is the seam, not the picture: the view shows what the <em>service</em> holds, at the
 * end the <em>writer</em> chose, and it keeps one element per item rather than rebuilding. Nothing here
 * asserts a pixel — the bar's geometry is the stylesheet's business.</p>
 */
public class StatusBarViewTest extends UiTestBase {

    private UIWindow window;
    private StatusBarView bar;

    @Before
    public void setUp() {
        StatusBar.resetForTesting();
        bar = new StatusBarView();
        UIElement root = new UIElement().layout(l -> l.width(400).height(100));
        root.addChild(bar);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(400, 100);
        settle();
    }

    @After
    public void tearDown() {
        StatusBar.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    private List<UIElement> itemsIn(String groupClass) {
        UIElement group = bar.querySelector("." + groupClass);
        assertNotNull("no " + groupClass + " group", group);
        return group.getElementsByClassName(StatusBarView.ITEM_CLASS);
    }

    /** The writer's alignment decides the end. The view never guesses from the id or the text. */
    @Test
    public void anItemLandsAtTheEndItsWriterChose() {
        StatusBar.set("explorer", "created notes.txt");
        StatusBar.set("caret", "Ln 51, Col 39", StatusBar.Align.RIGHT);
        settle();

        assertEquals(1, itemsIn(StatusBarView.LEFT_CLASS).size());
        assertEquals(1, itemsIn(StatusBarView.RIGHT_CLASS).size());
        assertEquals("Ln 51, Col 39",
                ((UIText) itemsIn(StatusBarView.RIGHT_CLASS).get(0)).getText());
    }

    /**
     * <b>An item keeps its element across an update.</b>
     *
     * <p>The engine's standing rule is that a widget must never rebuild the elements it is being clicked
     * on, and status items are written from per-frame paths — the shader graph's line-owner readout fires
     * on every caret move in the generated source. A view that rebuilt per change would be discarding and
     * recreating its whole tree continuously, which no screenshot would ever show.</p>
     */
    @Test
    public void updatingAnItemReusesItsElement() {
        StatusBar.set("caret", "Ln 1, Col 1", StatusBar.Align.RIGHT);
        settle();
        UIElement before = itemsIn(StatusBarView.RIGHT_CLASS).get(0);

        StatusBar.set("caret", "Ln 2, Col 7", StatusBar.Align.RIGHT);
        settle();
        List<UIElement> after = itemsIn(StatusBarView.RIGHT_CLASS);

        assertEquals("still one item", 1, after.size());
        assertSame("the slot was rebuilt rather than updated", before, after.get(0));
        assertEquals("Ln 2, Col 7", ((UIText) after.get(0)).getText());
    }

    /** Clearing a writer's item takes its element with it — the bar is the present, not a log. */
    @Test
    public void clearingAnItemRemovesIt() {
        StatusBar.set("explorer", "created notes.txt");
        settle();
        assertEquals(1, itemsIn(StatusBarView.LEFT_CLASS).size());

        StatusBar.clear("explorer");
        settle();
        assertTrue("the slot outlived the item", itemsIn(StatusBarView.LEFT_CLASS).isEmpty());
    }

    /**
     * Moving an item between ends moves the element, and does not leave a copy behind.
     *
     * <p>The removal pass runs before the placement pass for exactly this: an id that changed ends would
     * otherwise briefly be a child of both groups, and {@code addChild} throws on a second parent.</p>
     */
    @Test
    public void movingAnItemBetweenEndsLeavesNothingBehind() {
        StatusBar.set("compile", "compiled 9n/8e", StatusBar.Align.RIGHT);
        settle();
        assertEquals(1, itemsIn(StatusBarView.RIGHT_CLASS).size());

        StatusBar.set("compile", "compiled 9n/8e", StatusBar.Align.LEFT);
        settle();
        assertEquals("left it in the old group", 0, itemsIn(StatusBarView.RIGHT_CLASS).size());
        assertEquals(1, itemsIn(StatusBarView.LEFT_CLASS).size());
    }

    /**
     * <b>Only the leading item of each group is marked</b>, and the mark moves when the group does.
     *
     * <p>It is what the divider rule keys on: the sheet draws a border before every item and switches it
     * off on this class, because the selector engine has no {@code :first-child}. Stamp it once and never
     * move it and the bar grows a stray rule against its left edge the first time an item is cleared —
     * cosmetic, invisible in a test that only counts items, and permanent.</p>
     */
    @Test
    public void onlyTheLeadingItemOfEachGroupIsMarked() {
        StatusBar.set("a", "first");
        StatusBar.set("b", "second");
        StatusBar.set("caret", "1:1", StatusBar.Align.RIGHT);
        settle();

        List<UIElement> left = itemsIn(StatusBarView.LEFT_CLASS);
        assertTrue("the leading left item carries it", left.get(0).hasClass(StatusBarView.FIRST_CLASS));
        assertFalse("the one after it does not", left.get(1).hasClass(StatusBarView.FIRST_CLASS));
        assertTrue("each group has its own leading item",
                itemsIn(StatusBarView.RIGHT_CLASS).get(0).hasClass(StatusBarView.FIRST_CLASS));

        StatusBar.clear("a");
        settle();
        assertTrue("the mark followed the group's new leader",
                itemsIn(StatusBarView.LEFT_CLASS).get(0).hasClass(StatusBarView.FIRST_CLASS));
    }

    /**
     * <b>A slot's tooltip is attached once and re-texted, never re-attached.</b>
     *
     * <p>{@code Tooltip.attach} adds a hover listener pair per call and {@code detach} leaves them inert
     * rather than removing them — its own javadoc records that a set/clear/set cycle silently accumulates
     * them. The compile summary rewrites its tooltip on every recompile, which for an animated graph is
     * every frame, so getting this wrong is unbounded growth rather than a tidiness question.</p>
     */
    @Test
    public void aSlotKeepsOneTooltipAcrossUpdates() {
        StatusBar.set("compile", "compiled 9n/8e", StatusBar.Align.LEFT, "996 chars");
        settle();
        UIElement slot = itemsIn(StatusBarView.LEFT_CLASS).get(0);
        int attached = slot.getElementsByClassName(Tooltip.LABEL_CLASS).size();
        assertEquals("one tooltip for one slot", 1, attached);

        for (int i = 0; i < 5; i++) {
            StatusBar.set("compile", "compiled " + i + "n/8e", StatusBar.Align.LEFT, i + " chars");
            settle();
        }
        assertSame("the slot was rebuilt", slot, itemsIn(StatusBarView.LEFT_CLASS).get(0));
        assertEquals("a tooltip was attached per update",
                1, slot.getElementsByClassName(Tooltip.LABEL_CLASS).size());
    }
}
