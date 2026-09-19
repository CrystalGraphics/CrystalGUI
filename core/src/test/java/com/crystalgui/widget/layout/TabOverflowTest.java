package com.crystalgui.widget.layout;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>What a tab strip does when its tabs do not fit</b>, laid out: the rail is a scroller whose content sits in a slot,
 * so squeezing and wrapping only work if the slot lays the tabs out in the rail's width rather than its own.
 */
public class TabOverflowTest extends UiDocumentTestBase {

    private TabView strip(TabView.TabOverflow mode) {
        return strip(mode, 150f);
    }

    private TabView strip(TabView.TabOverflow mode, float width) {
        withDefaultStyles();
        TabView tabs = new TabView();
        tabs.layout(l -> l.width(width).height(200));
        UIElement root = new UIElement().layout(l -> l.width(width).height(200));
        root.append(tabs);
        document.append(root);
        for (String name : new String[]{"alpha.java", "beta.java", "gamma.java", "delta.java", "epsilon.java"}) {
            tabs.addTab(name);
        }
        tabs.setTabOverflow(mode);
        for (int i = 0; i < 4; i++) frame();
        return tabs;
    }

    @Test
    public void scrollingKeepsOneRowAndScrolls() {
        TabView tabs = strip(TabView.TabOverflow.SCROLL);
        assertTrue(tabs.rail().box().maxScrollLeft() > 0f);
        assertEquals(1, rows(tabs));
    }

    @Test
    public void squeezingNarrowsEveryTab() {
        TabView natural = strip(TabView.TabOverflow.SCROLL);
        float naturalWidth = natural.getTabs().get(0).box().width();
        TabView squeezed = strip(TabView.TabOverflow.SQUEEZE);
        assertTrue("the tabs kept their natural width", squeezed.getTabs().get(0).box().width() < naturalWidth);
        assertEquals(1, rows(squeezed));
    }

    /** With room to spare a squeezed tab is its natural width: a share is capped there, never stretched past it. */
    @Test
    public void aSqueezedTabWithRoomKeepsItsNaturalWidth() {
        float natural = strip(TabView.TabOverflow.SCROLL, 800f).getTabs().get(0).box().width();
        assertEquals(natural, strip(TabView.TabOverflow.SQUEEZE, 800f).getTabs().get(0).box().width(), 0.5f);
    }

    /** A squeezed tab's close button is laid over its label, so showing it on hover moves no tab. */
    @Test
    public void hoveringASqueezedTabMovesNoTab() {
        // SQUEEZED PART-WAY, above every tab's minimum, where a tab's width follows its content.
        TabView tabs = strip(TabView.TabOverflow.SQUEEZE, 230f);
        for (Tab tab : tabs.getTabs()) tab.setClosable(true);
        for (int i = 0; i < 4; i++) frame();
        float[] before = widths(tabs);
        tabs.getTabs().get(1).setHovered(true);
        for (int i = 0; i < 4; i++) frame();
        assertArrayEquals(before, widths(tabs), 0.01f);
    }

    private static float[] widths(TabView tabs) {
        float[] out = new float[tabs.getTabs().size()];
        for (int i = 0; i < out.length; i++) out[i] = tabs.getTabs().get(i).box().width();
        return out;
    }

    @Test
    public void wrappingBreaksIntoRowsAndNeverScrolls() {
        TabView tabs = strip(TabView.TabOverflow.WRAP);
        assertEquals(0f, tabs.rail().box().maxScrollLeft(), 0.01f);
        assertTrue("the tabs stayed on one row", rows(tabs) > 1);
    }

    private static int rows(TabView tabs) {
        float top = Float.NaN;
        int rows = 0;
        for (Tab tab : tabs.getTabs()) {
            Box box = tab.box();
            if (box.y() != top) {
                rows++;
                top = box.y();
            }
        }
        return rows;
    }

    /** Selecting a tab in full view leaves the strip where it is, at any uiScale; a cut tab comes just into view. */
    @Test
    public void selectingATabScrollsOnlyWhenItIsCut() {
        document.boxes().setUiScale(2f);
        TabView tabs = strip(TabView.TabOverflow.SCROLL);
        tabs.rail().box().setScroll(40f, 0f);
        frame();
        Tab visible = null;
        for (Tab tab : tabs.getTabs()) {
            float start = tab.box().x() - 40f;
            // THE LAST IN FULL VIEW, furthest along the rail, where a doubled offset lands past its end.
            if (start >= 0f && start + tab.box().width() <= tabs.rail().box().clientWidth() && tab != tabs.getSelectedTab()) {
                visible = tab;
            }
        }
        tabs.selectTab(visible);
        for (int i = 0; i < 4; i++) frame();
        assertEquals("a tab in full view scrolled the strip", 40f, tabs.rail().scrollLeft(), 0.5f);

        Tab last = tabs.getTabs().get(tabs.getTabs().size() - 1);
        tabs.selectTab(last);
        for (int i = 0; i < 60; i++) frame();
        Box box = last.box();
        assertEquals("the cut tab did not come flush to the strip's end",
                box.x() + box.width() - tabs.rail().box().clientWidth(), tabs.rail().scrollLeft(), 0.5f);
    }
}
