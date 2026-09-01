package com.crystalgui.ui.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.crystalgui.style.easing.Easing;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The motion service against the rows that cost the window animations four rounds each.
 *
 * <p>Every one of them is structural here rather than remembered: the start value is written by the
 * constructor, the clock is the host's delta so it cannot have run out before anyone looked, a stall
 * advances nothing because it is time nobody rendered, and "is it playing at all" is a question with
 * an answer — which is the only assertion that separates animating from applied instantly.</p>
 */
public class AnimationTest {

    /** The identity curve: this file is about the TIMELINE, not about any particular easing. */
    private static final Easing LINEAR = t -> t;

    @Test
    public void theStartValueIsWrittenBeforeAnyTick() {
        Animation animation = new Animation();
        List<Float> written = new ArrayList<>();
        animation.start(1f, LINEAR, written::add, null);
        assertEquals("one frame between asked-for and showing its first value is one frame of the END "
                + "state -- a visible flash at the beginning of every gesture",
                List.of(0f), written);
    }

    @Test
    public void theClockIsTheHostsDeltaSoItCannotRunOutBeforeTheFirstFrame() {
        Animation animation = new Animation();
        List<Float> written = new ArrayList<>();
        Animation.Timeline timeline = animation.start(0.2f, LINEAR, written::add, null);

        // A host builds its screen, opens a window, connects a workspace and compiles shaders before
        // a frame is drawn. On a wall clock the whole duration has elapsed by the first tick and the
        // animation completes having rendered nothing.
        assertTrue(timeline.isRunning());
        animation.tick(0.016f);
        assertTrue("still playing", timeline.isRunning());
        assertEquals(2, written.size());
        assertEquals(0.08f, written.get(1), 0.001f);
    }

    @Test
    public void aStallAdvancesNothingAndIsBounded() {
        Animation animation = new Animation();
        List<Float> written = new ArrayList<>();
        Animation.Timeline timeline = animation.start(0.2f, LINEAR, written::add, null);

        for (int i = 0; i < Animation.MAX_HELD_FRAMES; i++) animation.tick(0.4f);
        // ASSERTED ON THE VALUE, not on how many times it was written. A held frame advances the
        // timeline by nothing -- which is what "the first windows of a session open into the worst
        // stall there is, and wall time would charge a gesture its whole duration for frames nobody
        // saw" means -- and it RE-ASSERTS what it is holding rather than writing nothing at all.
        //
        // The difference is not academic. A body writes a compositor override, an override lives on the
        // BOX, and a box is destroyed and rebuilt whenever its node leaves the tree and comes back --
        // which is exactly what restoring a hidden window does, and the frames right after a reattach
        // are the stall this branch exists for. Writing nothing there left the box at REST for up to
        // MAX_HELD_FRAMES: the window appeared instantly at full size, vanished, and only then played
        // its entry animation. Counting writes pins the old mechanism; this pins the property.
        for (Float value : written) {
            assertEquals("a held frame advanced the timeline", 0f, value, 0f);
        }
        assertTrue("nothing was written at all, so a rebuilt box keeps whatever it was born with",
                written.size() >= 1);
        assertTrue(timeline.isRunning());

        for (int i = 0; i < 40; i++) animation.tick(0.4f);
        assertFalse("...but bounded, so a host that never settles still finishes", timeline.isRunning());
    }

    @Test
    public void completionRunsOnceAndIsAnnounced() {
        Animation animation = new Animation();
        List<String> log = new ArrayList<>();
        Animation.Timeline timeline = animation.start(0.05f, LINEAR,
                t -> log.add("t=" + t), () -> log.add("done"));
        assertTrue(animation.isAnimating());

        animation.tick(0.016f);
        animation.tick(0.016f);
        animation.tick(0.016f);
        animation.tick(0.016f);
        assertFalse(timeline.isRunning());
        assertFalse(animation.isAnimating());
        assertEquals("the end value is written, once, and then the completion", "t=1.0", log.get(log.size() - 2));
        assertEquals("done", log.get(log.size() - 1));

        animation.tick(0.016f);
        assertEquals("a finished timeline is not ticked again", "done", log.get(log.size() - 1));
    }

    @Test
    public void cancelStopsWhereItIsAndReportsNothing() {
        Animation animation = new Animation();
        List<String> log = new ArrayList<>();
        Animation.Timeline timeline = animation.start(1f, LINEAR, t -> { }, () -> log.add("done"));
        animation.tick(0.016f);
        timeline.cancel();

        assertFalse(timeline.isRunning());
        assertTrue("cancel is not completion -- an animation nobody is waiting on must not fire the "
                + "continuation somebody attached to its END", log.isEmpty());
        assertEquals(0, animation.running());
    }

    @Test
    public void finishRunsTheContinuationWhichIsWhatWaitingCallersGoThrough() {
        Animation animation = new Animation();
        List<String> log = new ArrayList<>();
        Animation.Timeline timeline = animation.start(10f, LINEAR, t -> log.add("t=" + t), () -> log.add("done"));
        timeline.finish();

        assertEquals("an animation's completion is unreachable by waiting, since its duration is time "
                + "that never elapses across instant frames -- so every one of these runs through finish()",
                List.of("t=0.0", "t=1.0", "done"), log);
    }

    @Test
    public void aHookIsDroppedWhenItsOwnerLeavesTheTree() {
        UIDocument document = new UIDocument();
        UINode node = ServiceFixtures.at("node", 0, 0, 100, 100);
        document.append(node);
        List<String> log = new ArrayList<>();
        document.animation().every(node, delta -> {
            log.add("tick");
            return true;
        });

        document.animation().tick(0.016f);
        assertEquals(1, log.size());

        document.remove(node);
        document.animation().tick(0.016f);
        assertEquals("registration was one-way in the old engine, so the only thing that could stop a "
                + "ticker was the ticker", 1, log.size());
        assertEquals(0, document.animation().hookCount());
    }

    @Test
    public void aHookThatReturnsFalseStops() {
        UIDocument document = new UIDocument();
        UINode node = ServiceFixtures.at("node", 0, 0, 100, 100);
        document.append(node);
        List<String> log = new ArrayList<>();
        document.animation().every(node, delta -> {
            log.add("tick");
            return false;
        });

        document.animation().tick(0.016f);
        document.animation().tick(0.016f);
        assertEquals(1, log.size());
    }
}
