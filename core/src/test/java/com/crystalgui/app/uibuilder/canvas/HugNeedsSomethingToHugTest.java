package com.crystalgui.app.uibuilder.canvas;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * <b>Double-click-to-hug refuses a node with nothing to hug.</b>
 *
 * <p>The answer for an empty box is zero, which is arithmetically right and unusable: a zero box puts all
 * eight handles on the same point, so the gesture cannot be reversed by the gesture. Asserted on the
 * predicate rather than the double-click because the gesture needs a live pointer, and this is the whole
 * of the decision.</p>
 */
public class HugNeedsSomethingToHugTest extends UiDocumentTestBase {

    @Test
    public void anEmptyBoxHasNothingToHug() {
        UIElement empty = new UIElement().layout(l -> l.width(80f).height(40f));
        document.append(empty);
        document.update(W, H);

        assertFalse("hugging this collapses it to zero and buries its own handles",
                ResizeHandles.hasContentToHug(empty));
    }

    /** A widget that draws its own content has no child nodes and must not read as empty. */
    @Test
    public void aWidgetThatMeasuresItselfCounts() {
        UIText text = new UIText();
        text.setText("Crystal");
        document.append(text);
        document.update(W, H);

        assertTrue("a Measurable node answers its own size and is exactly what hug asks",
                ResizeHandles.hasContentToHug(text));
    }

    /** An out-of-flow child contributes nothing to a content size, so it is not content. */
    @Test
    public void anOutOfFlowChildIsNotContent() {
        UIElement holder = new UIElement().layout(l -> l.width(80f).height(40f));
        UIElement floating = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(0f).top(0f).width(20f).height(20f));
        UIElement inFlow = new UIElement().layout(l -> l.width(20f).height(20f));
        holder.append(floating);
        document.append(holder);
        document.update(W, H);

        assertFalse("a positioned child adds nothing to the content size it would hug to",
                ResizeHandles.hasContentToHug(holder));

        holder.append(inFlow);
        document.update(W, H);
        assertTrue(ResizeHandles.hasContentToHug(holder));
    }
}
