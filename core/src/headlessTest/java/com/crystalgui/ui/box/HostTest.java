package com.crystalgui.ui.box;

import static com.crystalgui.ui.box.BoxFixtures.absolute;
import static com.crystalgui.ui.box.BoxFixtures.box;
import static com.crystalgui.ui.box.BoxFixtures.hit;
import static com.crystalgui.ui.box.BoxFixtures.layout;
import static com.crystalgui.ui.box.BoxFixtures.sized;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import java.util.List;
import org.junit.Test;

/**
 * Hosting: a box laid out somewhere other than under its parent's box, and the node tree none the
 * wiser. What the old engine did in four places (Taffy parent, {@code getX/getY},
 * {@code localToWorld}, paint+hit entry) is one field on the box (5.3, D5.1).
 */
public class HostTest {

    @Test
    public void aHostedBoxLaysOutAgainstItsHostAndTheNodeTreeIsUntouched() {
        UIDocument document = new UIDocument();
        UIElement panel = absolute(200, 200, 300, 300);
        UIElement popup = absolute(10, 10, 50, 50);
        panel.append(popup);
        document.append(panel);
        document.update(800, 600);
        assertEquals("under its parent's box", 210f, box(popup).worldX(), 0.001f);

        Box root = document.boxes().root();
        assertNotNull(root);
        box(popup).setHost(root);
        document.layout(800, 600);
        assertEquals("the containing block is now the root", 10f, box(popup).worldX(), 0.001f);
        assertSame("and the node tree has not moved", panel, popup.getParent());
        List<Box> order = root.children();
        assertSame("hosted after the natural children, so it paints on top", box(popup), order.get(order.size() - 1));
        assertSame("and hit-tests where it is drawn", popup, hit(document, 20, 20));
        assertSame("its old place is the document's now", document, hit(document, 190, 190));

        box(popup).setHost(null);
        document.layout(800, 600);
        assertEquals("home again", 210f, box(popup).worldX(), 0.001f);
    }

    @Test
    public void anOwnedWindowIsHostedOnItsOwnersOverlaySlot() {
        UIDocument document = new UIDocument();
        UIElement window = absolute(100, 100, 400, 400);
        UIElement overlay = absolute(0, 0, 400, 400);
        UIElement content = sized(400, 400);
        UIElement dialog = absolute(20, 30, 100, 100);
        content.append(dialog);
        window.append(content).append(overlay);
        document.append(window);
        document.update(800, 600);

        box(dialog).setHost(box(overlay));
        document.layout(800, 600);
        assertEquals(120f, box(dialog).worldX(), 0.001f);
        assertEquals(130f, box(dialog).worldY(), 0.001f);
        assertSame(box(overlay), box(dialog).host());
        assertTrue(box(overlay).children().contains(box(dialog)));
        assertFalse(box(content).children().contains(box(dialog)));
    }

    @Test
    public void twoBoxesHostedOnTheSameHostStackInTheOrderTheyArrived() {
        UIDocument document = new UIDocument();
        UIElement first = absolute(0, 0, 100, 100);
        UIElement second = absolute(0, 0, 100, 100);
        UIElement holder = sized(800, 600);
        holder.append(first).append(second);
        document.append(holder);
        document.update(800, 600);
        Box root = document.boxes().root();

        box(second).setHost(root);
        box(first).setHost(root);
        document.layout(800, 600);
        assertSame("the one hosted last is on top -- top-layer stacking is insertion order", first, hit(document, 10, 10));
    }

    @Test
    public void aMirrorIsASecondBoxWithItsOwnPlaceInTheHitOrder() {
        UIDocument document = new UIDocument();
        UIElement window = absolute(0, 0, 200, 100);
        UIElement label = absolute(10, 10, 20, 20);
        window.append(label);
        UIElement strip = absolute(0, 500, 800, 100);
        document.append(window).append(strip);
        document.update(800, 600);

        Box thumb = document.boxes().mirror(window, box(strip));
        document.layout(800, 600);
        assertTrue(thumb.isMirror());
        assertEquals("the copy is laid out under the strip", 500f, thumb.worldY(), 0.001f);
        assertEquals("the original did not move", 0f, box(window).worldY(), 0.001f);
        Box hitThumb = document.boxes().hitTest(15, 515);
        assertNotNull(hitThumb);
        assertTrue("the copy is hit where the copy is", hitThumb.isMirror());
        assertSame(label, hitThumb.node());
        Box hitOriginal = document.boxes().hitTest(15, 15);
        assertNotNull(hitOriginal);
        assertFalse("and the original where the original is", hitOriginal.isMirror());
        assertSame(label, hitOriginal.node());
        assertSame("the node's OWN box is never the mirror", hitOriginal, label.box());

        document.boxes().unmirror(thumb);
        document.layout(800, 600);
        assertSame(strip, hit(document, 15, 515));
    }

    @Test
    public void displayNoneHasNoBoxAndNeitherDoesAnythingUnderIt() {
        UIDocument document = new UIDocument();
        UIElement hidden = sized(100, 100);
        UIElement inside = sized(10, 10);
        hidden.append(inside);
        document.append(hidden);
        layout(hidden, l -> l.display(TaffyDisplay.NONE));
        document.update(800, 600);
        assertNull(hidden.box());
        assertNull(inside.box());

        layout(hidden, l -> l.display(TaffyDisplay.FLEX));
        document.update(800, 600);
        assertNotNull("a display toggle is a structure change, and the box is back", hidden.box());
        assertNotNull(inside.box());

        document.remove(hidden);
        document.update(800, 600);
        assertNull("off the tree, no box", hidden.box());
    }

    @Test
    public void aHostMustBelongToTheSameTree() {
        UIDocument a = new UIDocument();
        UIDocument b = new UIDocument();
        UIElement inA = sized(10, 10);
        UIElement inB = sized(10, 10);
        a.append(inA);
        b.append(inB);
        a.update(100, 100);
        b.update(100, 100);
        try {
            box(inA).setHost(box(inB));
            fail("hosting across trees must be refused");
        } catch (IllegalArgumentException expected) {
            // the box tree is per document
        }
    }
}
