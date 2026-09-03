package com.crystalgui.ui.dom;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;



import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Cascade ordering <em>between</em> stylesheets at the same origin.
 *
 * <p>CSS breaks an origin/specificity tie by document order, so the later-registered sheet wins.
 * {@code StyleSheet.parse} restarts {@code sourceOrder} at 0 for every sheet, so before the engine
 * packed each sheet's registration index above the rule index, a big sheet's rule #40 beat a
 * later sheet's rule #2 purely because it had more rules in front of it — the exact opposite.</p>
 */
public class CrossSheetOrderTest extends UiDocumentTestBase {

    /**
     * The regression this exists for. The first sheet's matching rule sits at a high rule index
     * (padded with unrelated rules in front of it); the second sheet's sits at index 0. Same origin,
     * same selector, therefore same specificity — so only sheet order may decide, and the second
     * sheet must win.
     */
    @Test
    public void laterSheetWinsAtEqualSpecificity() {
        StringBuilder padded = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            padded.append(".filler").append(i).append(" { width: 1px; }\n");
        }
        padded.append(".target { width: 111px; }\n");

        float width = resolveWidth(
                StyleSheet.parse(padded.toString()),          // matching rule at index 40
                StyleSheet.parse(".target { width: 222px; }") // matching rule at index 0
        );

        assertEquals("the later-registered sheet must win at equal specificity", 222f, width, 0.5f);
    }

    /** ...and symmetrically, so the result tracks registration order rather than a fixed bias. */
    @Test
    public void swappingRegistrationOrderSwapsTheWinner() {
        float width = resolveWidth(
                StyleSheet.parse(".target { width: 222px; }"),
                StyleSheet.parse(".target { width: 111px; }")
        );
        assertEquals(111f, width, 0.5f);
    }

    /** Ordering within a single sheet must still work — the later rule wins. */
    @Test
    public void withinOneSheetTheLaterRuleStillWins() {
        float width = resolveWidth(StyleSheet.parse(
                ".target { width: 111px; }\n.target { width: 222px; }"));
        assertEquals(222f, width, 0.5f);
    }

    /** Specificity must still outrank sheet order: an earlier sheet with a more specific selector
     * beats a later sheet with a less specific one. Sheet order is only a tie-break. */
    @Test
    public void specificityStillOutranksSheetOrder() {
        float width = resolveWidth(
                StyleSheet.parse("#it.target { width: 111px; }"),  // id + class
                StyleSheet.parse(".target { width: 222px; }")      // class only, but later
        );
        assertEquals("specificity must beat sheet order", 111f, width, 0.5f);
    }

    private float resolveWidth(StyleSheet... sheets) {
        UIElement target = new UIElement();
        target.setId("it");
        target.addClass("target");

        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        root.append(target);

        document.append(root);
        for (StyleSheet sheet : sheets) {
            document.styleEngine().addStylesheet(sheet);
        }
        frame();

        return target.box().width();
    }

    /** Guards the packing arithmetic itself: sheet index must dominate rule index, which is what the
     * long-widened sourceOrder is for. A sheet big enough to overflow an int stride would silently
     * wrap and reverse the ordering. */
    @Test
    public void sheetOrderDominatesEvenAVeryLargeEarlierSheet() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            huge.append(".filler").append(i).append(" { width: 1px; }\n");
        }
        huge.append(".target { width: 111px; }\n");

        assertEquals(222f, resolveWidth(
                StyleSheet.parse(huge.toString()),
                StyleSheet.parse(".target { width: 222px; }")), 0.5f);
        assertNotNull(StylePropertyRegistry.class);
    }
}
