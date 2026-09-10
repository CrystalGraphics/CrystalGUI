package com.crystalgui.ui.service;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.testsupport.UiDocumentTestBase;

/**
 * <b>Cursor art is owned, and letting go of yours cannot take somebody else's.</b>
 *
 * <p>What is asserted here is the LIFETIME, not the picture. A decoration is drawn every frame from
 * whoever set it last, and the two ways it goes wrong are both invisible on screen until they are not:
 * one left behind by a gesture that ended, and one torn down by a consumer that had already been
 * replaced. The shape it draws is {@code RotationCursor}'s business and an eye's.</p>
 */
public class CursorDecorationTest extends UiDocumentTestBase {

    private static final CursorDecoration ARROW = (ctx, x, y) -> { };
    private static final CursorDecoration OTHER = (ctx, x, y) -> { };

    @Test
    public void whatWasSetIsWhatIsDrawn() {
        document.input().setCursorDecoration(ARROW);

        assertSame(ARROW, document.input().cursorDecoration());
    }

    @Test
    public void theHandleTakesItDownAgain() {
        Disposable art = document.input().setCursorDecoration(ARROW);

        art.dispose();

        assertNull("a gesture that ended left its art on screen", document.input().cursorDecoration());
    }

    /** Passing null is the per-frame idiom: a gesture that decides afresh every frame holds no handle. */
    @Test
    public void nullClearsIt() {
        document.input().setCursorDecoration(ARROW);

        document.input().setCursorDecoration(null);

        assertNull(document.input().cursorDecoration());
    }

    /**
     * The one that is silent: a consumer releasing a handle it has already been displaced from.
     *
     * <p>Cursor art is a single slot, so a second consumer setting one displaces the first without
     * telling it. If the first then disposes and the handle cleared by slot rather than by identity, the
     * live gesture's art would vanish and the one that vanished it is already gone.</p>
     */
    @Test
    public void aStaleHandleDoesNotClearWhatReplacedIt() {
        Disposable stale = document.input().setCursorDecoration(ARROW);
        document.input().setCursorDecoration(OTHER);

        stale.dispose();

        assertSame("the live decoration was cleared by a handle it had already displaced",
                OTHER, document.input().cursorDecoration());
    }
}
