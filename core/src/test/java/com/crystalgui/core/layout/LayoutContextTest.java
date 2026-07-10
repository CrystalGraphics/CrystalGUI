package com.crystalgui.core.layout;

import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDimension;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import com.crystalgui.ui.UIContainer;
import com.crystalgui.ui.UIDocument;
import com.crystalgui.ui.UIElement;
import org.junit.Assert;
import org.junit.Test;

public class LayoutContextTest {

    @Test
    public void shouldComputeSimpleRowLayout() {
        UIElement root = new UIElement();
        root.getLayoutStyle().display = TaffyDisplay.FLEX;
        root.getLayoutStyle().flexDirection = FlexDirection.ROW;
        root.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(150.0f), TaffyDimension.length(50.0f));

        UIElement childA = new UIElement();
        childA.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(50.0f), TaffyDimension.length(20.0f));

        UIElement childB = new UIElement();
        childB.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(100.0f), TaffyDimension.length(30.0f));

        root.addChild(childA);
        root.addChild(childB);

        UIContainer container = UIContainer.headless(UIDocument.of(root));
        container.computeLayout(150.0f, 50.0f);

        Assert.assertEquals(0.0f, root.getLayoutState().getLayoutBox().getX(), 0.01f);
        Assert.assertEquals(0.0f, root.getLayoutState().getLayoutBox().getY(), 0.01f);
        Assert.assertEquals(150.0f, root.getLayoutState().getLayoutBox().getWidth(), 0.01f);
        Assert.assertEquals(50.0f, root.getLayoutState().getLayoutBox().getHeight(), 0.01f);

        Assert.assertEquals(0.0f, childA.getLayoutState().getLayoutBox().getX(), 0.01f);
        Assert.assertEquals(0.0f, childA.getLayoutState().getLayoutBox().getY(), 0.01f);
        Assert.assertEquals(50.0f, childA.getLayoutState().getLayoutBox().getWidth(), 0.01f);
        Assert.assertEquals(20.0f, childA.getLayoutState().getLayoutBox().getHeight(), 0.01f);

        Assert.assertEquals(50.0f, childB.getLayoutState().getLayoutBox().getX(), 0.01f);
        Assert.assertEquals(0.0f, childB.getLayoutState().getLayoutBox().getY(), 0.01f);
        Assert.assertEquals(100.0f, childB.getLayoutState().getLayoutBox().getWidth(), 0.01f);
        Assert.assertEquals(30.0f, childB.getLayoutState().getLayoutBox().getHeight(), 0.01f);
    }
}
