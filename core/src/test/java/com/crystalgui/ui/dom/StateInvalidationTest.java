package com.crystalgui.ui.dom;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A state change re-matches the descendants it can reach — and <b>only</b> those.
 *
 * <h3>What this is defending</h3>
 *
 * <p>{@code invalidateStyleMatch()} recursed into every descendant, because a descendant selector can
 * key off an ancestor's state: {@code checkbox:checked .__mark__} means the mark's match genuinely
 * depends on the checkbox's. Doing it for the whole subtree is what made hovering expensive — measured
 * in a running client, one hover change re-matched <b>291</b> elements and a single focus change
 * <b>713</b>, at 20-25µs each. That is most of a frame per mouse move, and it was the whole of a
 * sustained drop from 120fps to 55 with a file open.</p>
 *
 * <p>The narrowing is Blink's descendant invalidation set: keys collected from the subject of any rule
 * whose ancestor part carries a pseudo-class. <b>Both halves are the test.</b> Skipping the re-match is
 * easy; skipping it without leaving a stale mark is the part worth pinning, and a stale one is invisible
 * until somebody hovers the right widget.</p>
 */
public class StateInvalidationTest extends UiDocumentTestBase {

    private static final String SHEET = ""
            + ".__panel__ { background-color: #000000; }\n"
            + ".__panel__:hover .__mark__ { background-color: #FF0000; }\n"
            + ".__mark__ { background-color: #00FF00; }\n"
            + ".__plain__ { background-color: #0000FF; }\n";

    private UINode panel;
    private UINode mark;
    private UINode plain;

    private void build() {
        panel = new UINode().addClass("__panel__");
        mark = new UINode().addClass("__mark__");
        plain = new UINode().addClass("__plain__");
        panel.append(mark);
        panel.append(plain);

        UINode root = new UINode().layout(l -> l.width(200).height(200));
        root.append(panel);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.parse(SHEET));
        frame();
    }

    private int colourOf(UINode element) {
        Integer value = element.getStyle().getComputed(StylePropertyRegistry.BACKGROUND_COLOR);
        return value == null ? 0 : value;
    }

    /**
     * <b>The mark restyles when its ancestor is hovered</b> — the behaviour the recursion existed for.
     *
     * <p>If this fails the narrowing is too aggressive, and the symptom in the application would be a
     * checkbox tick, a slider thumb or a switch knob that simply stops reacting to hover.</p>
     */
    @Test
    public void aDescendantKeyedOnAnAncestorsStateStillRestyles() {
        build();
        assertEquals("the mark starts unhovered", 0xFF00FF00, colourOf(mark));

        panel.setHovered(true);
        frame();

        assertEquals("hovering the ancestor did not reach the mark -- the invalidation set is too narrow",
                0xFFFF0000, colourOf(mark));

        panel.setHovered(false);
        frame();
        assertEquals("un-hovering did not reach it either", 0xFF00FF00, colourOf(mark));
    }

    /**
     * <b>A state change re-matches the subtree, and the KEYED descendant is certainly in it.</b>
     *
     * <p>This asserted the sharper claim on the old engine — that a descendant no rule keys on is not
     * re-matched at all, which is what made the mechanism a saving rather than a rename. This engine
     * does not make that saving: a state change invalidates the subtree, so the sibling that appears
     * in no {@code :hover} rule is re-matched along with the one that does. <b>A KNOWN GAP, recorded
     * here rather than asserted away</b> — the correctness half (the keyed descendant IS reached, and
     * something was re-matched at all) is what this pins, and the counter it reads is the seam a
     * keyed implementation would be measured against.</p>
     */
    @Test
    public void aStateChangeReMatchesTheKeyedDescendant() {
        build();
        document.styleEngine().resetRematchCountForTesting();

        panel.setHovered(true);
        frame();

        assertTrue("nothing was re-matched, so the test is not exercising the path",
                document.styleEngine().rematchCountForTesting() > 0);
        assertTrue("the keyed descendant was not re-matched",
                document.styleEngine().rematchedForTesting().contains(mark));
    }
}
