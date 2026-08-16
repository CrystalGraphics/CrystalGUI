package com.crystalgui.language.run;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link TailFollow} — the console's scroll lock.
 *
 * <p>Every test here is written from a version that actually shipped. The logic was four lines inside
 * {@code RunConsoleView} and was wrong twice, in two different ways, and neither could be caught: it
 * needed a laid-out {@code TextEditor}, and this source set has no Taffy, so a {@code UIElement} cannot
 * be constructed at all. Two floats and a boolean can be.</p>
 */
public class TailFollowTest {

    /** One frame of the console's loop: read the position, then place the view if the lock is armed. */
    private static void frame(TailFollow follow, float[] view, float max) {
        follow.sample(view[0], max);
        if (follow.isFollowing() && max > 0f) {
            view[0] = max;
            follow.applied(max);
        }
    }

    /**
     * <b>An unmeasured viewport does not get a vote — the reported bug.</b>
     *
     * <p>The Run command opens the panel and the first drain happens before the editor has been through a
     * layout, so {@code getMaxScrollTop()} answers 0. A position-only rule then sees the offset at 0 and
     * the maximum large on the very next frame, concludes the reader has scrolled away, and never follows
     * again: the console opens at the top and stays there, on the one console nobody has touched.</p>
     */
    @Test
    public void aViewportThatHasNotBeenMeasuredCannotDisarmTheLock() {
        TailFollow follow = new TailFollow();
        float[] view = {0f};

        frame(follow, view, 0f);        // not laid out yet
        assertTrue("an unmeasured frame voted", follow.isFollowing());

        frame(follow, view, 500f);      // layout settles, offset still at the top
        assertTrue("the first measured frame read the stale offset as a reader gesture",
                follow.isFollowing());
        assertTrue("and so never scrolled", view[0] >= 499f);
    }

    /**
     * <b>The document growing under a still viewport is not the reader scrolling.</b>
     *
     * <p>The two are identical if position is all you look at, which is why the lock compares against
     * what the console itself last wrote rather than against the bottom.</p>
     */
    @Test
    public void outputArrivingKeepsTheLock() {
        TailFollow follow = new TailFollow();
        float[] view = {0f};

        float last = 0f;
        for (float max = 100f; max <= 2000f; max += 137f) {
            frame(follow, view, max);
            last = max;
        }

        assertTrue("a growing document disarmed the lock", follow.isFollowing());
        assertEquals("the view fell behind the tail", last, view[0], 0.5f);
    }

    /** <b>The reader scrolling away stops the follow — the half that was always right.</b> */
    @Test
    public void scrollingAwayReleasesTheLock() {
        TailFollow follow = new TailFollow();
        float[] view = {0f};
        frame(follow, view, 1000f);

        view[0] = 300f;                 // the reader drags upward
        frame(follow, view, 1000f);

        assertFalse("scrolling up did not release the lock", follow.isFollowing());
        assertTrue("and the view was dragged back down anyway", view[0] < 400f);
    }

    /**
     * <b>Scrolling back to the bottom re-arms — even when the document has stopped growing.</b>
     *
     * <p>The hole in the second attempt. While disarmed it compared against the last written offset too,
     * so a reader returning to exactly that offset looked like no movement at all. Only reachable on a
     * static document, which is precisely when somebody scrolls down to wait for the next run.</p>
     */
    @Test
    public void scrollingBackToTheBottomReArmsOnAStaticDocument() {
        TailFollow follow = new TailFollow();
        float[] view = {0f};
        frame(follow, view, 1000f);     // armed, parked at 1000

        view[0] = 300f;
        frame(follow, view, 1000f);
        assertFalse(follow.isFollowing());

        view[0] = 1000f;                // back to the bottom, the same offset the lock last wrote
        frame(follow, view, 1000f);
        assertTrue("returning to the tail did not re-arm", follow.isFollowing());
    }

    /**
     * <b>A non-finite offset is never voted on.</b>
     *
     * <p>{@code TextEditor.getScrollTop()} can be NaN — its own note says so — and NaN loses every
     * comparison, so a rule that compared it would disarm the lock for a reason the reader never caused.
     * Silent, and indistinguishable from the follow simply not being implemented.</p>
     */
    @Test
    public void aNonFiniteOffsetIsIgnored() {
        TailFollow follow = new TailFollow();
        follow.applied(500f);

        follow.sample(Float.NaN, 1000f);
        assertTrue("NaN disarmed the lock", follow.isFollowing());

        follow.sample(Float.POSITIVE_INFINITY, 1000f);
        assertTrue("an infinite offset disarmed the lock", follow.isFollowing());
    }

    /** <b>Scroll to End re-arms from anywhere</b> — the gesture a reader far up the transcript lacks. */
    @Test
    public void theButtonReArmsFromAnywhere() {
        TailFollow follow = new TailFollow();
        float[] view = {0f};
        frame(follow, view, 5000f);
        view[0] = 12f;
        frame(follow, view, 5000f);
        assertFalse(follow.isFollowing());

        follow.rearm();
        assertTrue(follow.isFollowing());

        // And the frame after it must not read the position being left as a fresh reader gesture.
        frame(follow, view, 5000f);
        assertTrue("the lock was released again on the very next frame", follow.isFollowing());
        assertTrue("the view was not taken to the tail", view[0] >= 4999f);
    }
}
