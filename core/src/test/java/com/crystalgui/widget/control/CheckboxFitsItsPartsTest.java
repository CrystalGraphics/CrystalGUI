package com.crystalgui.widget.control;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>A checkbox is exactly as big as what it draws.</b>
 *
 * <p>Two defects it had from the day it was written, both invisible at a control's own size and both
 * obvious on a canvas that draws a selection box around one.</p>
 *
 * <p>The root carried {@code height: 12px} — the MARK's number, applied to the whole control — with
 * {@code padding-all: 1px} inside it. So the mark filled the root and spilled over its own padding, and
 * a 13px label sat at y = -0.5 and overflowed the control top and bottom.</p>
 *
 * <p>And an empty label was laid out as a zero-wide box rather than not at all, which still opens the
 * row's gap before it and still spends the trailing padding after it: 16px of control for a 12px mark,
 * three dead pixels to the right of the only thing it draws.</p>
 */
public class CheckboxFitsItsPartsTest extends UiDocumentTestBase {

    @Test
    public void anUnlabelledCheckboxReservesNothingForItsLabel() {
        Checkbox box = laidOut(new Checkbox());

        assertNull("an empty label must be out of the layout, not a zero-wide box in it",
                labelOf(box).box());
        // The mark plus the padding either side of it, and nothing else.
        Box mark = markOf(box).box();
        assertTrue("dead space to the right of the mark: root " + box.box().width()
                        + " for a mark ending at " + (mark.x() + mark.width()),
                box.box().width() <= mark.x() + mark.width() + mark.x() + 0.01f);
    }

    /** Nothing a checkbox draws may fall outside it — the label used to, in both directions. */
    @Test
    public void everyPartSitsInsideTheControl() {
        Checkbox box = laidOut(new Checkbox("Enabled"));
        for (UIElement part : box.composedChildren()) {
            Box at = part.box();
            if (at == null) continue;
            assertTrue(part.get(com.crystalgui.ui.dom.Attribute.PART) + " starts above the control: y="
                    + at.y(), at.y() >= -0.01f);
            assertTrue(part.get(com.crystalgui.ui.dom.Attribute.PART) + " runs past its bottom: "
                            + (at.y() + at.height()) + " of " + box.box().height(),
                    at.y() + at.height() <= box.box().height() + 0.01f);
            assertTrue(part.get(com.crystalgui.ui.dom.Attribute.PART) + " runs past its right edge: "
                            + (at.x() + at.width()) + " of " + box.box().width(),
                    at.x() + at.width() <= box.box().width() + 0.01f);
        }
    }

    private Checkbox laidOut(Checkbox box) {
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        UIElement root = new UIElement().layout(l -> l.width(300).height(120));
        root.append(box);
        document.append(root);
        document.update(W, H);
        assertNotNull("nothing laid out", box.box());
        return box;
    }

    private static UIElement markOf(Checkbox box) {
        return partNamed(box, Checkbox.MARK_PART);
    }

    private static UIElement labelOf(Checkbox box) {
        return partNamed(box, Checkbox.LABEL_PART);
    }

    private static UIElement partNamed(Checkbox box, String part) {
        for (UIElement child : box.composedChildren()) {
            if (part.equals(child.get(com.crystalgui.ui.dom.Attribute.PART))) return child;
        }
        throw new AssertionError("no part named " + part);
    }
}
