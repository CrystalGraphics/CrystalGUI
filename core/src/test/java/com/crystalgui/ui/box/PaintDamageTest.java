package com.crystalgui.ui.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

/**
 * <b>What changed since the last frame, which is the only thing that has to be drawn again.</b>
 *
 * <p>A subtree's revision is what lets a flattened layer be kept: same number, same picture, so the
 * frame owes it one composited quad instead of a clear, a walk and every draw underneath. The value
 * itself means nothing — these assert the two properties a cache depends on, that it moves when
 * something changed and <em>does not</em> when nothing did.</p>
 */
public class PaintDamageTest extends UiDocumentTestBase {

    /** The property everything else rests on: a frame in which nothing happened damages nothing. */
    @Test
    public void aStillFrameChangesNoRevision() {
        UIElement parent = new UIElement().layout(l -> l.width(40).height(20));
        UIElement child = new UIElement().layout(l -> l.width(10).height(10));
        parent.append(child);
        document.append(parent);
        document.update(W, H);

        long was = parent.box().subtreeRevision();
        document.update(W, H);
        document.update(W, H);
        assertEquals(was, parent.box().subtreeRevision());
    }

    /** Moving a child reaches every ancestor, because a layer above it holds its picture. */
    @Test
    public void movingAChildDamagesItsAncestors() {
        UIElement parent = new UIElement().layout(l -> l.width(40).height(20));
        UIElement child = new UIElement().layout(l -> l.width(10).height(10));
        parent.append(child);
        document.append(parent);
        document.update(W, H);

        long was = parent.box().subtreeRevision();
        child.layout(l -> l.width(20));
        document.update(W, H);

        assertNotEquals("the parent still thinks it holds the old picture",
                was, parent.box().subtreeRevision());
    }

    /** And reaches nothing else, or retention would be worth nothing. */
    @Test
    public void aSiblingIsLeftAlone() {
        UIElement left = new UIElement().layout(l -> l.width(10).height(10));
        UIElement right = new UIElement().layout(l -> l.width(10).height(10));
        UIElement root = new UIElement().layout(l -> l.width(40).height(20));
        root.append(left);
        root.append(right);
        document.append(root);
        document.update(W, H);

        long wasRight = right.box().subtreeRevision();
        left.generalStyle(g -> g.backgroundColor(0xFF00FF00));
        document.update(W, H);

        assertEquals(wasRight, right.box().subtreeRevision());
        assertNotEquals(wasRight, left.box().subtreeRevision());
    }

    /**
     * A repaint the engine cannot see is one the widget has to declare.
     *
     * @see UIElement#repaint
     */
    @Test
    public void anExplicitRepaintDamages() {
        UIElement parent = new UIElement().layout(l -> l.width(40).height(20));
        UIElement child = new UIElement().layout(l -> l.width(10).height(10));
        parent.append(child);
        document.append(parent);
        document.update(W, H);

        long was = parent.box().subtreeRevision();
        child.repaint();
        document.update(W, H);
        assertNotEquals(was, parent.box().subtreeRevision());
    }

    /** Everything dirtied between two paints shares one tick, so a busy frame is still one comparison. */
    @Test
    public void everythingDamagedBetweenTwoPaintsSharesATick() {
        UIElement left = new UIElement().layout(l -> l.width(10).height(10));
        UIElement right = new UIElement().layout(l -> l.width(10).height(10));
        UIElement root = new UIElement().layout(l -> l.width(40).height(20));
        root.append(left);
        root.append(right);
        document.append(root);
        document.update(W, H);

        left.repaint();
        right.repaint();
        document.update(W, H);
        assertEquals(left.box().subtreeRevision(), right.box().subtreeRevision());
    }

    /**
     * A subtree is keepable only while nothing in it paints out of state the tree cannot see.
     *
     * @see UIElement#paintsDynamically
     */
    @Test
    public void aHandPaintingNodeMakesItsWholeAncestryUnretainable() {
        UIElement parent = new UIElement().layout(l -> l.width(40).height(20));
        UIElement plain = new UIElement().layout(l -> l.width(10).height(10));
        parent.append(plain);
        document.append(parent);
        document.update(W, H);
        assertTrue("plain elements are keepable", parent.box().retainable());

        parent.append(new HandPainted().layout(l -> l.width(10).height(10)));
        document.update(W, H);
        assertFalse("a hand painter reaches every ancestor", parent.box().retainable());
    }

    /** A label is the exception that makes retention worth having — it is in nearly every subtree. */
    @Test
    public void aLabelIsStillKeepable() {
        UIElement parent = new UIElement().layout(l -> l.width(40).height(20));
        parent.append(new UIText("hello"));
        document.append(parent);
        document.update(W, H);

        assertFalse("a label paints its own content", new UIText("x").paintsDynamically());
        assertTrue(parent.box().retainable());
    }

    /** …and changing its text damages it, even when the words measure the same. */
    @Test
    public void changingTextOfTheSameWidthStillDamages() {
        UIElement parent = new UIElement().layout(l -> l.width(40).height(20));
        UIText label = new UIText("abc");
        parent.append(label);
        document.append(parent);
        document.update(W, H);

        long was = parent.box().subtreeRevision();
        label.setText("abd");
        document.update(W, H);
        assertNotEquals(was, parent.box().subtreeRevision());
    }

    /**
     * <b>Fading an element does not repaint it.</b>
     *
     * <p>Its own opacity is applied when it is composited, so what changed is the picture above it. A
     * window fading in over a still desktop paints its contents once and is re-composited at a new
     * opacity every frame after.</p>
     */
    @Test
    public void aCompositorOpacityDamagesTheHostAndNotTheElement() {
        UIElement root = new UIElement().layout(l -> l.width(40).height(20));
        UIElement faded = new UIElement().layout(l -> l.width(10).height(10));
        root.append(faded);
        document.append(root);
        document.update(W, H);

        long wasFaded = faded.box().subtreeRevision();
        long wasRoot = root.box().subtreeRevision();
        faded.box().setOpacity(0.5f);
        document.update(W, H);

        assertEquals("the faded element's own picture is unchanged",
                wasFaded, faded.box().subtreeRevision());
        assertNotEquals("what contains it composites it differently",
                wasRoot, root.box().subtreeRevision());
    }

    private static final class HandPainted extends UIElement {
        @Override
        public void paintContent(CgUiPaintContext ctx, Box box) {
        }
    }
}
