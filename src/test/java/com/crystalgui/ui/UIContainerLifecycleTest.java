package com.crystalgui.ui;

import com.crystalgui.ui.UIContainer;
import com.crystalgui.ui.UIDocument;
import com.crystalgui.ui.UIElement;
import org.junit.Assert;
import org.junit.Test;

public class UIContainerLifecycleTest {

    @Test
    public void shouldAttachAndDetachWholeTree() {
        TrackingElement root = new TrackingElement();
        TrackingElement child = new TrackingElement();
        root.addChild(child);

        UIDocument document = UIDocument.of(root);
        UIContainer container = UIContainer.headless(document);

        Assert.assertSame(container, root.getContainer());
        Assert.assertSame(container, child.getContainer());
        Assert.assertEquals(1, root.attachedCount);
        Assert.assertEquals(1, child.attachedCount);

        container.detachDocument();

        Assert.assertNull(root.getContainer());
        Assert.assertNull(child.getContainer());
        Assert.assertEquals(1, root.detachedCount);
        Assert.assertEquals(1, child.detachedCount);
    }

    private static final class TrackingElement extends UIElement {
        private int attachedCount;
        private int detachedCount;

        @Override
        protected void onAttached(UIContainer container) {
            attachedCount++;
        }

        @Override
        protected void onDetached(UIContainer container) {
            detachedCount++;
        }
    }
}
