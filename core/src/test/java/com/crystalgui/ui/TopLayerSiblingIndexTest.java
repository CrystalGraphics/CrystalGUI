package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.Popover;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>A promoted element holds a DOM slot and no Taffy slot, and every index computation has to know.</b>
 *
 * <p>This is one defect that surfaced from three unrelated-looking places in a single afternoon, each of
 * which looked like a bug in whatever widget happened to be on screen:</p>
 *
 * <pre>Index (is 2) should be &lt; child_count (1) for parent node NodeId[value=33]</pre>
 *
 * <ul>
 *   <li>{@code UIWindow.registerElement} — adding any child to a parent that already has a popup open</li>
 *   <li>{@code TopLayer.restoreTaffyNodeToDomParent} — closing one popup while another is still up</li>
 *   <li>and the same on the way out, through {@code unregisterElement}</li>
 * </ul>
 *
 * <p>Promotion moves an element's Taffy node to the root while leaving it a DOM child of its parent, so
 * the parent's DOM child list and its Taffy child list drift apart by one per open popup. Both sites
 * inserted at the <em>DOM</em> index. {@code UIElement.taffyChildIndex()} counts only siblings that
 * actually have a node there, and is now the single answer both use.</p>
 *
 * <p>Tested here at the engine level rather than through a menu, because a menu is where it was noticed
 * and not where it lives — any two popups over one parent reproduce it, and so does a plain
 * {@code addChild} beside an open one.</p>
 */
public class TopLayerSiblingIndexTest extends UiTestBase {

    private UIWindow window;
    private UIElement host;

    @Before
    public void setUp() {
        host = new UIElement().layout(l -> l.widthPercent(100f).height(0).flexGrow(1f)
                .flexDirection(FlexDirection.COLUMN));
        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(host);

        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    private Popover openPopupOn(UIElement anchor) {
        Menu menu = new Menu();
        menu.addItem("item");
        host.addChild(menu);
        settle();
        menu.showFor(anchor, null);
        settle();
        return menu;
    }

    /**
     * <b>Adding a child beside an open popup.</b>
     *
     * <p>The popup's node has left the host's Taffy children, so the newcomer's DOM index is one past the
     * end. This is what crashed on the <em>second</em> right-click, and it is not about menus at all.</p>
     */
    @Test
    public void aChildCanBeAddedBesideAnOpenPopup() {
        UIElement anchor = new UIElement().layout(l -> l.width(50).height(20));
        host.addChild(anchor);
        settle();

        openPopupOn(anchor);

        UIElement newcomer = new UIElement().layout(l -> l.width(50).height(20));
        host.addChild(newcomer);       // threw: Index (is 2) should be < child_count (1)
        settle();

        assertSame(host, newcomer.getParent());
        assertTrue("the newcomer never laid out", newcomer.getRuntimeCache().getWidth() > 0f);
    }

    /**
     * <b>Closing one popup while another is still open.</b>
     *
     * <p>Demotion reinserts into the host's Taffy children, and the surviving popup's node is still parked
     * under the root — so the DOM index is again past the end. This is the one that came back through
     * {@code lightDismiss}, which closes the whole chain and therefore hits it every time.</p>
     */
    @Test
    public void onePopupCanCloseWhileAnotherIsStillOpen() {
        UIElement anchor = new UIElement().layout(l -> l.width(50).height(20));
        host.addChild(anchor);
        settle();

        Popover first = openPopupOn(anchor);
        Popover second = openPopupOn(anchor);
        assertTrue(first.isOpen() && second.isOpen());

        first.hide();                  // threw out of restoreTaffyNodeToDomParent
        settle();
        second.hide();
        settle();

        assertTrue(!first.isOpen() && !second.isOpen());
    }

    /** And the whole chain at once, which is what light dismiss does. */
    @Test
    public void severalPopupsCanAllCloseInOneGo() {
        UIElement anchor = new UIElement().layout(l -> l.width(50).height(20));
        host.addChild(anchor);
        settle();

        Popover a = openPopupOn(anchor);
        Popover b = openPopupOn(anchor);
        Popover c = openPopupOn(anchor);

        a.hide();
        b.hide();
        c.hide();
        settle();

        UIElement newcomer = new UIElement().layout(l -> l.width(50).height(20));
        host.addChild(newcomer);
        settle();
        assertTrue("the host's child list never recovered", newcomer.getRuntimeCache().getWidth() > 0f);
    }

    /**
     * <b>A popover attaches itself when shown for an anchor that is in the tree.</b>
     *
     * <p>The other half of the same class of defect: promotion needs a node in the tree, so every caller
     * had to remember to parent one first — and {@code Menu.addSubmenu} deliberately does not parent its
     * child, so a submenu had to be attached separately or hovering its row threw a frame later from
     * inside a ticker.</p>
     */
    @Test
    public void aPopoverAttachesItselfWhenShownForAnAnchor() {
        UIElement anchor = new UIElement().layout(l -> l.width(50).height(20));
        host.addChild(anchor);
        settle();

        Menu orphan = new Menu();
        orphan.addItem("item");
        assertNotNull(anchor.getAttachedWindow());

        orphan.showFor(anchor, null);  // threw: "must be attached to a window before it can be shown"
        settle();

        assertNotNull("the popover did not attach itself", orphan.getParent());
        assertTrue(orphan.isOpen());
    }
}
