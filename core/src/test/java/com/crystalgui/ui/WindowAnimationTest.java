package com.crystalgui.ui;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.desktop.Desktop;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.desktop.WindowPolicy;
import com.crystalgui.ui.elements.desktop.WindowState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The window open/close/minimise/maximise transitions.
 *
 * <h3>What can and cannot be asserted</h3>
 *
 * <p>Not an intermediate frame: the driver advances on {@code System.nanoTime()}, so a test that watched
 * one would be asserting against wall time on whatever machine ran it. What IS assertable is everything
 * that actually broke — that a timeline is running at all, that it starts from its start value rather
 * than flashing the end one, that a teardown waits for it, and that turning animations off turns off the
 * waiting too.</p>
 *
 * <p><b>The first version of this file tested the wrong thing and passed against a build where nothing
 * moved.</b> It asserted that CSS classes went on and came off, which they faithfully did while the
 * cascade declined to animate any of them. Asking whether an animation is PLAYING is the assertion that
 * can tell those apart.</p>
 *
 * <p>Every other UI test runs with animations off, from {@code UiTestBase} — a live transform is a real
 * transform, and hit-testing walks the same chain the paint does, so a test pressing a caption during an
 * opening animation would miss it by an amount that depends on the machine.</p>
 */
public class WindowAnimationTest extends UiTestBase {

    private UIWindow window;

    /**
     * Back ON, and off again afterwards.
     *
     * <p>The flag is static — a process has one user and one motion preference — so leaving it on would
     * leak into whatever class the runner picks next, and Gradle re-decides that order every run. That is
     * the exact shape of test-order dependence {@code JsLanguageRegistrationTest} is named after.</p>
     */
    @Before
    public void enableAnimations() {
        Desktop.setAnimationsEnabled(true);
        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        frame();
    }

    @After
    public void disableAnimations() {
        Desktop.setAnimationsEnabled(false);
    }

    /**
     * A whole frame, tickers included.
     *
     * <p>{@code updateWithoutPainting} rather than the {@code calculateStyle} + {@code calculateLayout}
     * pair the other desktop tests settle with: the animation IS a {@code UIFrameTicker}, and those only
     * run inside {@code advanceFrame}. Settling the cheap way would never advance one.</p>
     */
    private void frame() {
        window.updateWithoutPainting();
    }

    /**
     * A window on screen and settled, with animations off for the opening.
     *
     * <p>Otherwise the open animation is still running when the gesture under test starts — 250ms is a
     * very long time in a test that runs three in-memory frames — and {@code isAnimating} cannot say
     * WHICH timeline it is reporting. Turning them off for the arrival is the only way to make the next
     * assertion about the gesture rather than about the fixture.</p>
     */
    private WindowFrame open(WindowPolicy policy) {
        Desktop.setAnimationsEnabled(false);
        WindowFrame frame = window.openWindow(new WindowFrame("Anim"));
        frame.setPolicy(policy).resizeTo(200, 140);
        frame();
        frame();
        Desktop.setAnimationsEnabled(true);
        return frame;
    }

    private UITransform transformOf(WindowFrame frame) {
        return frame.getStyle().getComputed(StylePropertyRegistry.TRANSFORM);
    }

    /**
     * <b>Opening a window actually runs a timeline</b> — the assertion the class-based version could not
     * make.
     *
     * <p>It is the whole difference between an animation and a value that was applied instantly, and the
     * previous implementation failed it while looking, by every other measure, completely correct.</p>
     */
    @Test
    public void openingAWindowRunsAnAnimation() {
        WindowFrame frame = window.openWindow(new WindowFrame("Anim"));
        frame.resizeTo(200, 140);

        assertTrue("nothing is playing -- the window just appeared", frame.isAnimating());
    }

    /**
     * <b>It starts AT its start value, not a frame later.</b>
     *
     * <p>A gap between "the animation was asked for" and "the animation is showing its first value" is a
     * frame of the END state, which is a visible flash at the beginning of every gesture. So the driver
     * writes its start value in its constructor rather than on its first tick — and the observable is
     * that the window is already scaled down before any frame has run.</p>
     */
    @Test
    public void anAnimationShowsItsStartValueImmediately() {
        WindowFrame frame = window.openWindow(new WindowFrame("Anim"));
        frame.resizeTo(200, 140);

        assertNotEquals("the window flashed at full size before shrinking to its entry state",
                UITransform.IDENTITY, transformOf(frame));
    }

    /**
     * <b>A closing window outlives its animation.</b>
     *
     * <p>Hiding is DETACHING in CrystalOS and a detached subtree paints nothing, so a window that tore
     * itself down on the press would animate to an empty screen — perfectly correct, entirely invisible.
     * The teardown therefore waits for the timeline to finish.</p>
     */
    @Test
    public void aClosingWindowIsStillThereWhileItPlays() {
        WindowFrame frame = open(WindowPolicy.DESTROY_ON_CLOSE);

        assertTrue(frame.requestClose());

        assertNotEquals("it tore itself down on the press, with nothing left to animate",
                WindowState.DESTROYED, frame.state());
        assertTrue("it is not actually animating, so nothing will ever finish it",
                frame.isAnimating());
    }

    /**
     * <b>Off means off, including the waiting.</b>
     *
     * <p>An animation that merely finished instantly would still defer the teardown by a frame, and every
     * caller of {@code requestClose} would have to learn to wait for a window that — as far as the user
     * is concerned — is not animating at all. Removing the deferral too is what lets every other test in
     * the suite go on asserting synchronously.</p>
     */
    @Test
    public void withAnimationsOffACloseTakesEffectOnTheSameCall() {
        WindowFrame frame = open(WindowPolicy.DESTROY_ON_CLOSE);
        Desktop.setAnimationsEnabled(false);

        assertTrue(frame.requestClose());

        assertEquals("the close was still deferred", WindowState.DESTROYED, frame.state());
    }

    /**
     * <b>A refused close never plays.</b>
     *
     * <p>The veto is asked <em>before</em> anything is animated: a window that faded out and then stayed
     * because a guard said no has told the user the close happened. Worse than no animation.</p>
     */
    @Test
    public void aVetoedCloseIsNotAnimatedAtAll() {
        WindowFrame frame = open(WindowPolicy.DESTROY_ON_CLOSE);
        frame.setDiscardGuard(() -> false);

        assertTrue("a refusal is still a handled request", frame.requestClose());

        assertFalse("it played a close that is not going to happen", frame.isAnimating());
        // AND STILL THERE SEVERAL FRAMES LATER, which is the half that would catch an animation that
        // was started and then quietly ran its teardown anyway.
        for (int i = 0; i < 4; i++) frame();
        assertEquals(WindowState.VISIBLE, frame.state());
    }

    /**
     * <b>A second gesture cancels the first rather than fighting it.</b>
     *
     * <p>Maximise then immediately restore-down is the ordinary case. Two drivers writing the same slot
     * would trade frames with each other for as long as both ran, which is the visible stutter that made
     * the previous implementation's maximise unusable.</p>
     */
    @Test
    public void asecondGestureReplacesTheAnimationInFlight() {
        WindowFrame frame = open(WindowPolicy.DESTROY_ON_CLOSE);

        frame.maximize();
        assertTrue(frame.isAnimating());
        frame.restore();

        assertTrue("the restore did not take over", frame.isAnimating());
        assertFalse("it is still maximised", frame.isMaximized());
    }

    /**
     * <b>An animation asked for OUTSIDE the frame loop still plays.</b>
     *
     * <p>The clock used to be stamped in the constructor, which assumes construction and the first frame
     * are adjacent. A host builds its screen and opens its first window before any frame is drawn — and
     * then constructs an editor, connects a workspace and compiles shaders — so by the first tick the
     * whole duration had elapsed and the animation completed having drawn nothing. Reported as an open
     * that "just opens instantly"; the probe read {@code ticks=0 over 0ms}.</p>
     *
     * <p>Every later gesture was unaffected, because those begin with the frame loop already running,
     * which is what made it look like the open animation specifically was broken.</p>
     */
    @Test
    public void anAnimationStartedBeforeTheFirstFrameStillPlays() throws InterruptedException {
        WindowFrame frame = window.openWindow(new WindowFrame("Late"));
        frame.resizeTo(200, 140);

        // LONGER THAN THE OPEN ANIMATION, and deliberately with no frame in between: this is the gap
        // between a host's initGui and its first render.
        Thread.sleep(400L);

        frame();
        assertTrue("the animation ran out its whole duration before it was ever advanced -- it was "
                + "constructed with a clock that had already expired", frame.isAnimating());
    }

    /**
     * <b>A stalled frame cannot swallow a whole animation.</b>
     *
     * <p>The first window of a session opens while the editor is still being constructed and its shaders
     * compiled, so the frame loop is stalled: the probe measured two frames 154ms apart for a 150ms
     * animation, which completed on its second tick having drawn a single frame. Nobody saw that 154ms —
     * nothing was drawn during it — so charging the animation for it animates against time the user never
     * observed.</p>
     *
     * <p>An animation advances by RENDERED time, in capped steps, which is the same frame-loop guard that
     * stops physics spiralling on a slow frame.</p>
     */
    @Test
    public void aStalledFrameDoesNotConsumeTheWholeAnimation() throws InterruptedException {
        WindowFrame frame = window.openWindow(new WindowFrame("Stall"));
        frame.resizeTo(200, 140);

        frame();                 // the clock starts here
        Thread.sleep(400L);      // ...and the loop stalls for longer than the animation lasts
        frame();

        assertTrue("one stalled frame ran the animation to completion -- it is advancing on wall time "
                + "rather than on rendered time", frame.isAnimating());
    }
}
