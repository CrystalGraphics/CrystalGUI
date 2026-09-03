package com.crystalgui.ui.box;

import static com.crystalgui.ui.box.BoxFixtures.absolute;
import static com.crystalgui.ui.box.BoxFixtures.box;
import static com.crystalgui.ui.box.BoxFixtures.general;
import static com.crystalgui.ui.box.BoxFixtures.hit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import org.junit.Test;

/**
 * A click lands on what layout put there — with no paint having happened, ever.
 *
 * <p>The old engine's hit-test walked a {@code localToWorld} cache that painting reconciled, so an
 * unpainted tree hit-tested against stale matrices and a subtree drawn twice corrupted it unless the
 * pass said it was a copy. Here the matrices are composed by the layout pass from the same inputs a
 * painter will read, and hit order is paint order read backwards (5.3).</p>
 */
public class HitTestBeforePaintTest {

    @Test
    public void theTopmostBoxIsFoundInReversePaintOrder() {
        UIDocument document = new UIDocument();
        UIElement a = absolute(10, 10, 100, 100);
        UIElement inner = absolute(25, 25, 50, 50);
        a.append(inner);
        UIElement b = absolute(50, 50, 100, 100);
        document.append(a).append(b);
        document.update(800, 600);

        assertSame("a descendant is above its ancestor", inner, hit(document, 40, 40));
        assertSame(a, hit(document, 15, 15));
        assertSame("of two overlapping siblings the later one is on top", b, hit(document, 100, 100));
        assertSame("nothing there but the document", document, hit(document, 300, 300));
    }

    @Test
    public void zIndexReordersWhatIsOnTop() {
        UIDocument document = new UIDocument();
        UIElement a = absolute(10, 10, 100, 100);
        UIElement b = absolute(50, 50, 100, 100);
        document.append(a).append(b);
        general(a, g -> g.zIndex(1));
        document.update(800, 600);
        assertSame("the higher z-index wins over document order", a, hit(document, 100, 100));

        box(b).setZIndex(2);
        document.layout(800, 600);
        assertSame("and a compositor's z, above the cascade's, wins over that", b, hit(document, 100, 100));
    }

    @Test
    public void scrollingMovesWhatIsUnderThePointer() {
        UIDocument document = new UIDocument();
        UIElement container = absolute(0, 0, 100, 100);
        general(container, g -> g.overflow(Overflow.HIDDEN));
        UIElement tall = absolute(0, 0, 100, 300);
        container.append(tall);
        document.append(container);
        document.update(800, 600);
        assertSame(tall, hit(document, 10, 10));
        assertSame("clipped at the container's edge, so the document is under the pointer", document, hit(document, 10, 120));

        box(container).setScroll(0, 150);
        document.layout(800, 600);
        assertEquals("the content moved up by the scroll", -150f, box(tall).worldY(), 0.001f);
        assertSame(tall, hit(document, 10, 10));
    }

    @Test
    public void aTransformMovesTheHitWithTheDrawing() {
        UIDocument document = new UIDocument();
        UIElement node = absolute(0, 0, 50, 50);
        general(node, g -> g.transform(Transform.translate(100, 0)));
        document.append(node);
        document.update(800, 600);

        assertEquals(100f, box(node).worldX(), 0.001f);
        assertSame("where it was laid out there is nothing", document, hit(document, 10, 10));
        assertSame("where it is drawn there it is", node, hit(document, 110, 10));

        box(node).setTransform(Transform.translate(0, 200));
        document.layout(800, 600);
        assertSame("a compositor's transform, above the cascade's", node, hit(document, 10, 210));
    }

    @Test
    public void hitTestOffPassesOverTheWholeSubtree() {
        UIDocument document = new UIDocument();
        UIElement a = absolute(0, 0, 100, 100);
        UIElement inner = absolute(0, 0, 50, 50);
        a.append(inner);
        document.append(a);
        a.set(Attribute.HIT_TEST, false);
        document.update(800, 600);

        assertSame("like pointer-events: none -- the subtree is transparent to the pointer", document, hit(document, 10, 10));
        a.set(Attribute.HIT_TEST, true);
        assertSame("read live: no layout needed to turn it back on", inner, hit(document, 10, 10));
    }
}
