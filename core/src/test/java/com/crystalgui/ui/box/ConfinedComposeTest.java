package com.crystalgui.ui.box;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * A compose visits only the boxes whose matrix inputs changed and what they host. These pin that every such input
 * still reaches the matrices of what sits below it.
 */
public class ConfinedComposeTest extends UiDocumentTestBase {

    /** A widget dragging a thumb writes the node's offsets directly; the content must move with it. */
    @Test
    public void aDirectScrollWriteMovesWhatTheBoxHosts() {
        UIElement scroller = new UIElement().layout(l -> l.width(40).height(20));
        scroller.generalStyle(g -> g.overflow(Overflow.SCROLL));
        UIElement content = new UIElement().layout(l -> l.width(40).height(200));
        scroller.append(content);
        document.append(scroller);
        document.update(W, H);

        float was = worldY(content);
        scroller.setScrollOffsets(0f, 10f);
        document.update(W, H);
        assertEquals(was - 10f, worldY(content), 0.001f);
    }

    /** A box moved by a sibling growing carries its children, though nothing about them changed. */
    @Test
    public void aMovedHostCarriesItsChildren() {
        UIElement before = new UIElement().layout(l -> l.width(40).height(10));
        UIElement parent = new UIElement().layout(l -> l.width(40).height(20));
        UIElement child = new UIElement().layout(l -> l.width(10).height(10));
        parent.append(child);
        UIElement root = new UIElement().layout(l -> l.width(40).height(100));
        root.append(before);
        root.append(parent);
        document.append(root);
        document.update(W, H);

        float was = worldY(child);
        before.layout(l -> l.height(30));
        document.update(W, H);
        assertEquals(was + 20f, worldY(child), 0.001f);
    }

    /** A compositor transform is layout-free, and still moves the whole subtree. */
    @Test
    public void aTransformOverrideMovesTheSubtree() {
        UIElement parent = new UIElement().layout(l -> l.width(40).height(20));
        UIElement child = new UIElement().layout(l -> l.width(10).height(10));
        parent.append(child);
        document.append(parent);
        document.update(W, H);

        float was = child.box().localToWorld().m30();
        parent.box().setTransform(Transform.translate(5f, 0f));
        document.update(W, H);
        assertEquals(was + 5f, child.box().localToWorld().m30(), 0.001f);
    }

    private static float worldY(UIElement element) {
        return element.box().localToWorld().m31();
    }
}
