package com.crystalgui.widget.overlay;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>A popover built per opening leaves the tree once it is hidden.</b>
 *
 * <p>{@code hide} keeps a popover parented so it can be shown again, so a palette built on every press stayed
 * in the tree, hidden, one more per press. No stylesheet, so there is no fade to wait for.</p>
 */
public class PopoverRemovalTest extends UiDocumentTestBase {

    @Test
    public void aSingleUsePopoverIsGoneOnceHidden() {
        UIElementRegistry.bootstrap();
        UIElement anchor = new UIElement().layout(l -> l.width(40).height(16));
        document.append(anchor);
        frame();

        Popover popover = new Popover().removeWhenHidden();
        popover.append(new UIElement().layout(l -> l.width(60).height(40)));
        document.topLayerNode().append(popover);
        popover.showFor(anchor, anchor);
        frame();
        assertNotNull(popover.parent());

        popover.hide();
        frame();
        frame();
        assertNull("it removed itself", popover.parent());
    }
}
