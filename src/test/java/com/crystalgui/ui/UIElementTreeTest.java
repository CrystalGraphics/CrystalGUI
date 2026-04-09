package com.crystalgui.ui;

import com.crystalgui.core.tree.TreeTraversal;
import com.crystalgui.ui.UIElement;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class UIElementTreeTest {

    @Test
    public void shouldMaintainParentChildOwnershipAndTraversalOrder() {
        UIElement root = new UIElement();
        UIElement childA = new UIElement();
        UIElement childB = new UIElement();
        UIElement grandChild = new UIElement();

        root.addChild(childA);
        root.addChild(childB);
        childA.addChild(grandChild);

        Assert.assertSame(root, childA.getParent());
        Assert.assertSame(root, childB.getParent());
        Assert.assertSame(childA, grandChild.getParent());
        Assert.assertEquals(2, root.getChildren().size());
        Assert.assertEquals(1, childA.getChildren().size());

        List<UIElement> traversal = TreeTraversal.depthFirst(root);
        Assert.assertEquals(4, traversal.size());
        Assert.assertSame(root, traversal.get(0));
        Assert.assertSame(childA, traversal.get(1));
        Assert.assertSame(grandChild, traversal.get(2));
        Assert.assertSame(childB, traversal.get(3));

        root.removeChild(childA);

        Assert.assertNull(childA.getParent());
        Assert.assertEquals(1, root.getChildren().size());
        Assert.assertSame(childB, root.getChildren().get(0));
    }
}
