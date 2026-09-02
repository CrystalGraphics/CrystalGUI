package com.crystalgui.widget.layout;

import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.workbench.dock.DockGroup;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@code clearTabs()} must empty the rail, not just the list.
 *
 * <p>{@code getTabs()} reads {@code TabView}'s own list; what is drawn is the rail's children. A leak
 * between the two is invisible to every existing assertion and shows up only as tabs on screen that
 * nothing can click, because they are detached from the model that would answer.</p>
 */
public class TabViewRailLeakTest extends UiDocumentTestBase {

    /**
     * Every {@link Tab} element anywhere under the view, however it got there.
     *
     * <p>Counting the rail's children instead is wrong and was my first attempt: the rail is a
     * {@code ScrollerView} and carries three internal children of its own, so it reads 5 for two tabs.
     * What matters is how many Tabs exist, not how many boxes the rail holds.</p>
     */
    private static int tabElements(TabView view) {
        return countTabs(view);
    }

    private static int countTabs(UINode element) {
        int count = element instanceof Tab ? 1 : 0;
        for (UINode child : element.children()) count += countTabs(child);
        return count;
    }

    @Test
    public void clearTabsEmptiesTheRail() {
        TabView view = new TabView();
        view.addTab("one");
        view.addTab("two");
        assertEquals("baseline: two tabs exist", 2, tabElements(view));

        view.clearTabs();

        assertEquals("no Tab element survives anywhere under the view", 0, tabElements(view));
    }

    @Test
    public void removeTabTakesItsElementOutOfTheRail() {
        TabView view = new TabView();
        Tab first = view.addTab("one");
        view.addTab("two");

        view.removeTab(first);

        assertEquals(1, tabElements(view));
    }

    /**
     * <b>DockGroup's exact rebuild sequence</b> — clear each tab's content, then clear the tabs.
     *
     * <p>The only step the plain clear-and-repopulate test does not do, and the dock is the only caller
     * that does it.</p>
     */
    @Test
    public void clearingTabContentFirstStillLetsClearTabsWork() {
        TabView view = new TabView();
        view.addTab("one").content().append(new UINode());
        view.addTab("two").content().append(new UINode());

        for (Tab tab : view.getTabs()) tab.content().removeAll();
        view.clearTabs();
        view.addTab("only");

        assertEquals(1, view.getTabCount());
        assertEquals("a Tab survived the clear", 1, tabElements(view));
    }

    /** Is a Tab marked internal? removeChild refuses internal children outright. */
    @Test
    public void aTabIsNotAnInternalChild() {
        TabView view = new TabView();
        Tab tab = view.addTab("one");
        org.junit.Assert.assertFalse("an internal Tab could never be removed from the rail",
                tab.get(Attribute.PART).isEmpty() == false);
    }

    /** The dock's rebuild shape: clear everything, then add fewer than were there. */
    @Test
    public void clearThenRepopulateLeavesOnlyTheNewTabs() {
        TabView view = new TabView();
        view.addTab("one");
        view.addTab("two");

        view.clearTabs();
        view.addTab("only");

        assertEquals(1, view.getTabCount());
        assertEquals("the element tree must agree with the tab list", 1, tabElements(view));
    }
}
