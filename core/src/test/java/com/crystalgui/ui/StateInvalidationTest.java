package com.crystalgui.ui;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;

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
public class StateInvalidationTest extends UiTestBase {

    private static final String SHEET = ""
            + ".__panel__ { background-color: #000000; }\n"
            + ".__panel__:hover .__mark__ { background-color: #FF0000; }\n"
            + ".__mark__ { background-color: #00FF00; }\n"
            + ".__plain__ { background-color: #0000FF; }\n";

    private UIWindow window;
    private UIElement panel;
    private UIElement mark;
    private UIElement plain;

    private void build() {
        panel = new UIElement().addClass("__panel__");
        mark = new UIElement().addClass("__mark__");
        plain = new UIElement().addClass("__plain__");
        panel.addChild(mark);
        panel.addChild(plain);

        UIElement root = new UIElement().layout(l -> l.width(200).height(200));
        root.addChild(panel);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.parse(SHEET));
        window.init(200, 200);
        window.updateWithoutPainting();
    }

    private int colourOf(UIElement element) {
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
        window.updateWithoutPainting();

        assertEquals("hovering the ancestor did not reach the mark -- the invalidation set is too narrow",
                0xFFFF0000, colourOf(mark));

        panel.setHovered(false);
        window.updateWithoutPainting();
        assertEquals("un-hovering did not reach it either", 0xFF00FF00, colourOf(mark));
    }

    /**
     * <b>...and a descendant nothing keys on is not re-matched at all.</b>
     *
     * <p>The half that makes it a saving rather than a rename. Asserted by counting what the cascade
     * was asked to re-match, because the observable outcome of re-matching an element whose answer has
     * not changed is — by construction — that nothing changes.</p>
     */
    @Test
    public void aDescendantNothingKeysOnIsNotReMatched() {
        build();
        window.getStyleEngine().resetRematchCountForTesting();

        panel.setHovered(true);
        window.updateWithoutPainting();

        assertTrue("nothing was re-matched, so the test is not exercising the path",
                window.getStyleEngine().rematchCountForTesting() > 0);
        assertTrue("an unkeyed sibling was re-matched: " + window.getStyleEngine().rematchedForTesting(),
                !window.getStyleEngine().rematchedForTesting().contains(plain));
        assertTrue("the keyed descendant was not re-matched",
                window.getStyleEngine().rematchedForTesting().contains(mark));
    }
}
