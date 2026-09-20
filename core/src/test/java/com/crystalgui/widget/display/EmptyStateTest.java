package com.crystalgui.widget.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

/**
 * <b>A note too wide for its panel is clipped evenly at both ends of each line</b>, never wrapped: a reflow on every
 * pixel of a resize is worse than losing the ends of a line. IntelliJ's {@code StatusText}.
 */
public class EmptyStateTest extends UiDocumentTestBase {

    @Test
    public void aNarrowPanelCentresEachLineUnwrapped() {
        withDefaultStyles();
        UIElement panel = sized("panel", 60, 300);
        EmptyState empty = new EmptyState("No UI document is open", "Open a .cgui file to see its elements");
        panel.append(empty);
        document.append(panel);
        frame();
        frame();

        float lineHeight = -1;
        for (UIElement part : empty.composedSubtree()) {
            if (!(part instanceof UIText)) continue;
            Box box = part.box();
            // A LINE WITH NO CHORD HIDES ITS CHORD, and a hidden node has no box at all -- the engine's
            // own rule. It is not a line, so it is not this test's business; the guard at the end is what
            // keeps that from passing vacuously. @see EmptyState#CHORD_PART
            if (box == null) continue;
            float centre = Box.originIn(box, panel.box()).x + box.width() / 2;
            assertEquals(part + " is off centre", 30f, centre, 1f);
            assertTrue(part + " is narrower than the panel, so this proves nothing", box.width() > 60f);
            if (lineHeight < 0) lineHeight = box.height();
            assertEquals(part + " wrapped", lineHeight, box.height(), 0.5f);
        }
        assertTrue("no lines laid out", lineHeight > 0);
    }
}
