package com.crystalgui.ui.elements;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Which part of an over-long string a {@link TextField} shows.
 *
 * <p>In this package rather than {@code com.crystalgui.ui} so it can reach
 * {@link TextField#scrollOffsetFor} — the rule lives inside a method that only runs from
 * {@code paintOverlay}, and painting needs a GL context.</p>
 */
public class TextFieldScrollTest {

    private static final float INNER = 40f;   // usable width
    private static final float LONG = 100f;   // a string far wider than the box

    /**
     * <b>The bug this exists for.</b> {@code setText} puts the caret at the end, so an unfocused field
     * handed a number wider than its box arrived scrolled to the right — showing {@code …5005} instead of
     * {@code -0.6…}, i.e. losing the sign and the leading digits, which are the parts that carry the
     * meaning. Browsers show the start of an unfocused input for the same reason.
     */
    @Test
    public void anUnfocusedFieldShowsTheStartEvenWithTheCaretAtTheEnd() {
        assertEquals(0f, TextField.scrollOffsetFor(false, LONG, LONG, INNER, 0f), 0f);
    }

    /** ...and is dragged back to the start even if it was already scrolled — a blur resets the view. */
    @Test
    public void anUnfocusedFieldIsResetRatherThanLeftWhereItWas() {
        assertEquals(0f, TextField.scrollOffsetFor(false, LONG, LONG, INNER, 60f), 0f);
    }

    /** A focused field still follows its caret, or typing past the right edge would type into nothing. */
    @Test
    public void aFocusedFieldFollowsTheCaret() {
        float offset = TextField.scrollOffsetFor(true, LONG, LONG, INNER, 0f);
        assertEquals("the caret must end up on the right edge", LONG - INNER, offset, 0.001f);
    }

    /** The other direction: a caret moved back to the start pulls the view with it. */
    @Test
    public void aCaretAtTheStartScrollsBack() {
        assertEquals(0f, TextField.scrollOffsetFor(true, 0f, LONG, INNER, 60f), 0f);
    }

    /** A caret already in view moves nothing — otherwise every keystroke would jolt the text sideways. */
    @Test
    public void aCaretAlreadyInViewLeavesTheOffsetAlone() {
        assertEquals(30f, TextField.scrollOffsetFor(true, 50f, LONG, INNER, 30f), 0f);
    }

    /** Text shorter than the box is never scrolled, whatever the offset used to be. */
    @Test
    public void shortTextIsNeverScrolled() {
        assertEquals(0f, TextField.scrollOffsetFor(true, 10f, 20f, INNER, 60f), 0f);
    }
}
