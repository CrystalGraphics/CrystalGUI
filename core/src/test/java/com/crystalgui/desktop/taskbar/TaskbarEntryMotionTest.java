package com.crystalgui.desktop.taskbar;

import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The row opens for an arriving entry and closes up behind a leaving one.
 *
 * <p>The intermediate frames are not reachable — {@link TaskbarEntryMotion} advances on
 * {@code System.nanoTime()}, which a test loop cannot step — so this asserts the states that are: that a
 * closing entry is still in the row once the registry has dropped its document (the removal WAITED), that
 * an arriving one is under a cap and not yet a target, and that with animations off neither happens.</p>
 *
 * <p>That last pair is not a formality. Every other desktop test assumes an entry appears and disappears
 * the moment its document does, so a motion that ran regardless of the switch would make the strip
 * asynchronous in a mode where, as far as a caller is concerned, nothing is animating.</p>
 */
public class TaskbarEntryMotionTest extends UiDocumentTestBase {
    /**
     * ANIMATIONS OFF, unless a test turns them on for itself.
     *
     * <p>A window animation defers the thing it animates: `close()` destroys and `hide()`
     * detaches only once the flight has finished, so a test that asserts the state straight
     * after the gesture reads the state BEFORE it. Disabled, the continuation runs
     * synchronously, which is what lets every assertion here be immediate. The tests that are
     * ABOUT the animation enable it themselves and restore this in a finally.</p>
     */
    @Before
    public void quietAnimationsForTheFixture() {
        Desktop.setAnimationsEnabled(false);
    }

    /** AND PUT IT BACK. The flag is STATIC, so leaving it off leaks into every later test in the
     *  run -- a governance test that asks whether every shipped rule still matches something then
     *  finds `taskbar .__entry__.__animating__` matching nothing, because nothing animates. */
    @After
    public void restoreAnimationsAfterTheFixture() {
        Desktop.setAnimationsEnabled(true);
    }


    private WindowFrame first;
    private WindowFrame second;

    @Before
    public void setUpDesktop() {
        Desktop.setAnimationsEnabled(false);
        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        first = new WindowFrame("First");
        second = new WindowFrame("Second");
        Desktop.of(document).addWindow(first);
        Desktop.of(document).addWindow(second);
        settle();
    }

    @After
    public void restoreAnimations() {
        Desktop.setAnimationsEnabled(true);
    }

    private void settle() {
        for (int i = 0; i < 3; i++) frame();
    }

    private Taskbar taskbar() {
        return Desktop.of(document).taskbar();
    }

    private int rowSize() {
        return taskbar().entries().children().size();
    }

    private long collapsing() {
        return taskbar().entries().children().stream()
                .filter(child -> child.hasClass(Taskbar.EXITING_CLASS))
                .count();
    }

    /** The cap the ramp writes; null is the sheet's own sizing, i.e. no motion has touched it. */
    private Object capOf(UIElement entry) {
        return entry.getStyle().getComputed(LayoutProperties.MAX_WIDTH);
    }

    // ── leaving ──────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aClosingEntryStaysInTheRowWhileItCollapses() {
        Desktop.setAnimationsEnabled(true);
        assertEquals(2, rowSize());

        second.destroy();
        settle();

        assertNull("the registry has dropped it", taskbar().entryFor(second));
        assertEquals("the row must keep the box open while it collapses, or the entries jump",
                2, rowSize());
        assertEquals("the entry that is leaving is the one marked", 1, collapsing());
    }

    @Test
    public void aCollapsingEntryIsNoLongerATarget() {
        Desktop.setAnimationsEnabled(true);
        UIElement entry = taskbar().entryFor(second);

        second.destroy();
        settle();

        // It is out of the taskbar's map, so a press on it would resolve against a document the strip no
        // longer lists — and a dying button that still swallows presses is worse than one that vanished.
        assertFalse("a collapsing entry must stop hit-testing", entry.isHitTest());
        assertTrue(entry.hasClass(Taskbar.EXITING_CLASS));
    }

    @Test
    public void withAnimationsOffAClosingEntryIsGoneAtOnce() {
        second.destroy();
        settle();

        assertEquals("animations off must turn off the WAITING too, not merely the motion",
                1, rowSize());
        assertEquals(0, collapsing());
    }

    // ── arriving ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void anArrivingEntryRampsUnderACapAndIsNotYetATarget() {
        Desktop.setAnimationsEnabled(true);

        WindowFrame third = new WindowFrame("Third");
        Desktop.of(document).addWindow(third);
        settle();

        UIElement entry = taskbar().entryFor(third);
        assertNotNull("the entry is built immediately; only its width is animated", entry);
        assertNotNull("an arriving entry ramps a max-width cap", capOf(entry));
        // A sliver of a button is not something anyone can aim at. Handed back by finish().
        assertFalse("an arriving entry must not be a target while it is a sliver", entry.isHitTest());
    }

    /**
     * <b>A cap alone cannot close the row</b> — {@code border-box} will not compress an element's own
     * padding, so an entry capped at zero still occupies 6 + 9 px and the detach takes those 15 away in
     * one frame. Reported exactly that way: <em>"the closing one chokes at the very end, as if it
     * snaps"</em>. The padding has to ramp with the cap.
     */
    @Test
    public void aCapAloneLeavesThePaddingBehind() {
        UIElement entry = taskbar().entryFor(second);
        StyleGroup.inlinePipeline(entry.getStyle().getLayoutGroup(), l -> l.maxWidth(0f));
        settle();

        assertTrue("a capped entry still occupies its padding — this is what the motion must also ramp",
                widthOf(entry) > 0f);
    }

    /**
     * <b>An arrival that settles must put the entry back the way it found it — and settling is what no
     * test could reach.</b>
     *
     * <p>The hole this fell through: an arrival completes after 150ms of WALL time, which never elapses
     * across instant frames, so the opening branch of {@code finish()} had no coverage at all and took
     * the harness down the first time a real one settled — {@code NullPointerException} from the
     * {@code overflow} listener, because withdrawing the only candidate a property had resolved it to
     * null. Closing the document mid-arrival cancels it, which runs exactly that branch.</p>
     *
     * <p>The assertion is that the sequence completes, because the failure mode IS an exception: it
     * escapes {@code resolveTouched} into the frame loop. The two checks after it are the handover.</p>
     */
    @Test
    public void anArrivalThatSettlesRestoresTheEntryInsteadOfUnsettingIt() {
        Desktop.setAnimationsEnabled(true);
        WindowFrame third = new WindowFrame("Third");
        Desktop.of(document).addWindow(third);
        settle();
        assertFalse("precondition: the arrival is still running", taskbar().entryFor(third).isHitTest());

        UIElement entry = taskbar().entryFor(third);
        third.destroy();
        settle();

        assertNotNull("overflow must still resolve — withdrawing its only candidate is what threw",
                entry.getStyle().getComputed(StylePropertyRegistry.OVERFLOW));
        assertEquals("the arrival handed over to a collapse rather than being abandoned",
                1, collapsing());
    }

    /**
     * The positive control for {@link #anArrivingEntryRampsUnderACapAndIsNotYetATarget}: with the switch
     * off there is no cap and no dead button, so a cap seen there is the ramp running rather than
     * something the sheet does to every entry.
     */
    @Test
    public void withAnimationsOffAnArrivingEntryIsFullSizeAtOnce() {
        WindowFrame third = new WindowFrame("Third");
        Desktop.of(document).addWindow(third);
        settle();

        UIElement entry = taskbar().entryFor(third);
        assertNotNull(entry);
        assertNull("nothing may cap an entry when animations are off", capOf(entry));
        assertTrue("an entry that never animated must be clickable immediately", entry.isHitTest());
    }
}
