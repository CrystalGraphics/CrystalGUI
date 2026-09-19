package com.crystalgui.ui.box;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * <b>A corner cut away by {@code border-radius} is not the element.</b>
 *
 * <p>The pointer landed on the rectangle, so a pill answered for the four square corners it does not draw — its
 * cursor, its hover and, in the builder, its selection. Blink asks the same question in
 * {@code LayoutBox::HitTestClippedOutByBorder}, against the same rounded border box it paints.</p>
 */
public class RoundedHitTestTest extends UiDocumentTestBase {

    /** A node under a world point, or null over nothing. */
    private UIElement at(float x, float y) {
        Box box = document.boxes().hitTest(x, y);
        return box == null ? null : box.node();
    }

    private static UIElement pill(float width, float height, float radius) {
        UIElement node = new UIElement();
        node.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(0).top(0).width(width).height(height));
        node.generalStyle(g -> g.borderRadius(radius));
        return node;
    }

    @Test
    public void aCornerOutsideTheCurveIsNotTheElement() {
        UIElement node = pill(120f, 60f, 30f);
        document.append(node);
        document.update(W, H);

        assertEquals("the middle is the element", node, at(60f, 30f));
        assertEquals("and so is the top edge between the curves", node, at(60f, 1f));
        // THE DOCUMENT, not nothing: the pointer falls through to whatever is behind, as it does off any edge.
        assertEquals("but the square corner it does not draw is not", document, at(1f, 1f));
        assertEquals("nor any of the other three", document, at(119f, 1f));
        assertEquals("", document, at(119f, 59f));
        assertEquals("", document, at(1f, 59f));
    }

    /** The curve is the one the painter draws: a radius past half the side is scaled down, not clamped per axis. */
    @Test
    public void theCurveIsTheOneDrawn() {
        UIElement node = pill(120f, 60f, 999f);
        document.append(node);
        document.update(W, H);

        // 999px on a 120x60 box is a pill: every radius scaled by the one factor that fits, so 30px each way.
        assertEquals("the centre of the cap", node, at(30f, 30f));
        assertEquals("outside it", document, at(2f, 2f));
    }

    /**
     * A child reaching into the corner is still there — the radius says what THIS box is, not what is inside it.
     * With {@code overflow: hidden} the corner is clipped away and the child goes with it, which is what the
     * painter's mask does.
     */
    @Test
    public void aChildInTheCornerSurvivesUnlessTheBoxClips() {
        UIElement parent = pill(120f, 60f, 30f);
        UIElement child = new UIElement();
        child.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(0).top(0).width(20).height(20));
        parent.append(child);
        document.append(parent);
        document.update(W, H);

        assertEquals("the child overflows into the corner and is hit there", child, at(1f, 1f));

        parent.generalStyle(g -> g.overflow(Overflow.HIDDEN));
        document.update(W, H);
        assertEquals("clipped, the corner holds neither of them", document, at(1f, 1f));
    }
}
