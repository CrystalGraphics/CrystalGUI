package com.crystalgui.desktop;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.Popover;
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
 *   <li>{@code UIDocument.registerElement} — adding any child to a parent that already has a popup open</li>
 *   <li>{@code TopLayer.restoreTaffyNodeToDomParent} — closing one popup while another is still up</li>
 *   <li>and the same on the way out, through {@code unregisterElement}</li>
 * </ul>
 *
 * <p>Promotion moves an element's Taffy node to the root while leaving it a DOM child of its parent, so
 * the parent's DOM child list and its Taffy child list drift apart by one per open popup. Both sites
 * inserted at the <em>DOM</em> index. {@code UINode.taffyChildIndex()} counts only siblings that
 * actually have a node there, and is now the single answer both use.</p>
 *
 * <p>Tested here at the engine level rather than through a menu, because a menu is where it was noticed
 * and not where it lives — any two popups over one parent reproduce it, and so does a plain
 * {@code addChild} beside an open one.</p>
 */
public class TopLayerSiblingIndexTest extends UiDocumentTestBase {

    private UINode host;

    @Before
    public void setUp() {
        host = new UINode().layout(l -> l.widthPercent(100f).height(0).flexGrow(1f)
                .flexDirection(FlexDirection.COLUMN));
        UINode root = new UINode().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.append(host);

        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) frame();
    }

    private Popover openPopupOn(UINode anchor) {
        Menu menu = new Menu();
        menu.addItem("item");
        host.append(menu);
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
        UINode anchor = new UINode().layout(l -> l.width(50).height(20));
        host.append(anchor);
        settle();

        openPopupOn(anchor);

        UINode newcomer = new UINode().layout(l -> l.width(50).height(20));
        host.append(newcomer);       // threw: Index (is 2) should be < child_count (1)
        settle();

        assertSame(host, newcomer.parent());
        assertTrue("the newcomer never laid out", widthOf(newcomer) > 0f);
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
        UINode anchor = new UINode().layout(l -> l.width(50).height(20));
        host.append(anchor);
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
        UINode anchor = new UINode().layout(l -> l.width(50).height(20));
        host.append(anchor);
        settle();

        Popover a = openPopupOn(anchor);
        Popover b = openPopupOn(anchor);
        Popover c = openPopupOn(anchor);

        a.hide();
        b.hide();
        c.hide();
        settle();

        UINode newcomer = new UINode().layout(l -> l.width(50).height(20));
        host.append(newcomer);
        settle();
        assertTrue("the host's child list never recovered", widthOf(newcomer) > 0f);
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
        UINode anchor = new UINode().layout(l -> l.width(50).height(20));
        host.append(anchor);
        settle();

        Menu orphan = new Menu();
        orphan.addItem("item");
        assertNotNull(anchor.document());

        orphan.showFor(anchor, null);  // threw: "must be attached to a document before it can be shown"
        settle();

        assertNotNull("the popover did not attach itself", orphan.parent());
        assertTrue(orphan.isOpen());
    }
}
