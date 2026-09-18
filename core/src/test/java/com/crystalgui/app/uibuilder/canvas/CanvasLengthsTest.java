package com.crystalgui.app.uibuilder.canvas;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyDimension;

/**
 * <b>A gesture moves a value; it does not re-author it.</b> A {@code top} of 46% follows the parent as it
 * resizes — dragged on the canvas it came back as 589px, a constant, and the element stopped following anything.
 */
public class CanvasLengthsTest extends UiDocumentTestBase {

    private UIElement child() {
        UIElement parent = new UIElement().layout(l -> l.width(1000).height(500));
        UIElement node = new UIElement().layout(l -> l.width(100).height(50));
        parent.append(node);
        document.append(parent);
        frame();
        return node;
    }

    @Test
    public void aPercentageInsetStaysAPercentage() {
        UIElement node = child();
        LiveEdits.setInline(node, LayoutProperties.TOP, "20%");
        frame();

        LengthPercentageAuto written = CanvasLengths.inset(node, LayoutProperties.TOP, 250f, 500f);
        assertEquals(LengthPercentageAuto.Type.PERCENT, written.getType());
        assertEquals("half the parent, not 250 of anything", 0.5f, written.getValue(), 0.001f);
    }

    @Test
    public void aPixelInsetStaysPixels() {
        UIElement node = child();
        LiveEdits.setInline(node, LayoutProperties.TOP, "40px");
        frame();

        LengthPercentageAuto written = CanvasLengths.inset(node, LayoutProperties.TOP, 250f, 500f);
        assertEquals(LengthPercentageAuto.Type.LENGTH, written.getType());
        assertEquals(250f, written.getValue(), 0.001f);
    }

    @Test
    public void aPercentageSizeStaysAPercentage() {
        UIElement node = child();
        LiveEdits.setInline(node, LayoutProperties.WIDTH, "50%");
        frame();

        TaffyDimension written = CanvasLengths.size(node, LayoutProperties.WIDTH, 250f, 1000f);
        assertEquals(TaffyDimension.Type.PERCENT, written.getType());
        assertEquals(0.25f, written.getValue(), 0.001f);
    }

    /** With nothing to measure against, pixels — which is what it always wrote. */
    @Test
    public void nothingToMeasureAgainstIsPixels() {
        UIElement node = child();
        LiveEdits.setInline(node, LayoutProperties.TOP, "20%");
        frame();

        assertEquals(LengthPercentageAuto.Type.LENGTH,
                CanvasLengths.inset(node, LayoutProperties.TOP, 250f, 0f).getType());
    }
}
