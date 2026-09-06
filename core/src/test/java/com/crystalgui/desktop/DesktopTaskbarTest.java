package com.crystalgui.desktop;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.control.Button;
import com.crystalgui.desktop.taskbar.Taskbar;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.service.Input;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * CrystalOS W4 — the taskbar ({@code plan/shell-windowing.md}).
 *
 * <p>W3 and W4 ship together or not at all: <b>minimise with no discoverable way back is worse than no
 * minimise</b>, and this is the way back. So the assertions here are mostly about the strip being an
 * honest view of the registry — a document that exists has an entry, a hidden one is dimmed rather than
 * dropped, and the entries do not move about.</p>
 */
public class DesktopTaskbarTest extends UiDocumentTestBase {

    /**
     * Animations OFF for the fixture. Several tests below turn them back on for the thing they are
     * about and restore this in a finally; without a @Before the class relied on that restore having
     * run, i.e. on another test having gone first. A window's state change is DEFERRED while a
     * timeline plays, so the assertions here read VISIBLE for a window that has been closed.
     */
    @Before
    public void quietTheCompositor() {
        Desktop.setAnimationsEnabled(false);
    }

    private UIElement root;
    private Desktop desktop;
    private Taskbar taskbar;
    private Input input;

    private void build() {
        // The compositor fills the VIEWPORT, not a node inside it -- see DesktopWindowTest.
        viewport(400f, 300f);
        root = new UIElement().layout(l -> l.width(400).height(300));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        desktop = Desktop.of(document);
        taskbar = desktop.taskbar();
        settle();
        input = document.input();
        input.beginFrame();
        input.endFrame();
    }

    private void settle() {
        for (int pass = 0; pass < 2; pass++) {
        frame();
        }
    }

    private WindowFrame open(String title) {
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame(title));
        frame.resizeTo(120, 90);
        settle();
        return frame;
    }

    private Button entry(WindowFrame frame) {
        Button entry = taskbar.entryFor(frame);
        assertNotNull("every live document has an entry", entry);
        return entry;
    }

    // ── The strip is the registry, rendered ─────────────────────────────────

    @Test
    public void everyLiveWindowHasAnEntryAndADestroyedOneDoesNot() {
        build();
        WindowFrame first = open("One");
        WindowFrame second = open("Two");

        assertEquals(2, taskbar.entries().children().size());
        assertEquals("One", entry(first).getText());
        assertEquals("Two", entry(second).getText());

        second.destroy();
        settle();

        assertEquals(1, taskbar.entries().children().size());
        assertNull("a destroyed document's entry goes with it", taskbar.entryFor(second));
    }

    /**
     * <b>A hidden document keeps its entry.</b> That is the whole reason the strip renders the registry
     * rather than the tree: the entry is how a minimised document comes back, so dropping it would make
     * minimise a one-way trip.
     */
    @Test
    public void aHiddenWindowIsDimmedRatherThanDropped() {
        build();
        WindowFrame frame = open("One");

        frame.hide();
        settle();

        Button entry = entry(frame);
        assertTrue("still on the strip", entry.parent() != null);
        assertTrue("and says it is put away", entry.hasClass(Taskbar.HIDDEN_CLASS));
    }

    /**
     * <b>Open order, and it does not move.</b> A bar whose entries jump on every activation is the
     * "never in the same place twice" menu bug wearing a strip — you can never learn where anything is.
     */
    @Test
    public void entriesKeepOpenOrderThroughActivation() {
        build();
        WindowFrame first = open("One");
        WindowFrame second = open("Two");
        WindowFrame third = open("Three");

        desktop.activate(first);
        settle();

        assertEquals(0, entry(first).parent().indexOf(entry(first)));
        assertEquals(1, entry(second).parent().indexOf(entry(second)));
        assertEquals(2, entry(third).parent().indexOf(entry(third)));
    }

    /**
     * <b>Entries are reconciled, never rebuilt.</b> Refresh runs on activation, and activation runs on
     * a press — so a strip that rebuilt itself would destroy the element being clicked, which is the
     * trap that froze the table header.
     */
    @Test
    public void refreshKeepsTheSameEntryElements() {
        build();
        WindowFrame first = open("One");
        Button before = entry(first);

        open("Two");
        desktop.activate(first);
        first.hide();
        desktop.activate(first);
        settle();

        assertSame("the entry survived four refreshes", before, taskbar.entryFor(first));
    }

    @Test
    public void exactlyOneEntryIsMarkedActive() {
        build();
        WindowFrame first = open("One");
        WindowFrame second = open("Two");

        assertTrue(entry(second).hasClass(Taskbar.ACTIVE_CLASS));
        assertFalse(entry(first).hasClass(Taskbar.ACTIVE_CLASS));

        desktop.activate(first);
        settle();

        assertTrue(entry(first).hasClass(Taskbar.ACTIVE_CLASS));
        assertFalse(entry(second).hasClass(Taskbar.ACTIVE_CLASS));
    }

    // ── What a click does ───────────────────────────────────────────────────

    @Test
    public void clickingAHiddenWindowsEntryBringsItBack() {
        build();
        WindowFrame frame = open("One");
        frame.hide();
        settle();

        entry(frame).onPressed.emit();
        settle();

        assertEquals(WindowState.VISIBLE, frame.state());
        assertSame(frame, desktop.activeWindow());
    }

    @Test
    public void clickingAnInactiveWindowsEntryActivatesIt() {
        build();
        WindowFrame first = open("One");
        WindowFrame second = open("Two");
        assertSame(second, desktop.activeWindow());

        entry(first).onPressed.emit();
        settle();

        assertSame(first, desktop.activeWindow());
        assertEquals(WindowState.VISIBLE, first.state());
    }

    /**
     * <b>The toggle is what makes a taskbar a taskbar.</b> Clicking the entry of the document you are
     * already in minimises it; without that every entry restores, nothing puts anything away, and the
     * strip is a one-way trip.
     */
    @Test
    public void clickingTheActiveWindowsEntryMinimisesIt() {
        build();
        WindowFrame frame = open("One");
        assertSame(frame, desktop.activeWindow());

        entry(frame).onPressed.emit();
        settle();

        assertEquals(WindowState.HIDDEN, frame.state());
    }

    /**
     * <b>...and it minimises the same way the caption's button does — animation included.</b>
     *
     * <p>The caption played the flight into the taskbar and then hid; this called {@code hide()}
     * straight out and so did the same thing with no animation at all. One gesture, two call sites, and
     * only one of them looked like a minimise — invisible to the test above, which runs with animations
     * off and therefore cannot tell an animated hide from a bare one.</p>
     *
     * <p>Animations are turned on <em>after</em> the document is opened, so the only timeline that can be
     * running when this asserts is the one the press started.</p>
     */
    @Test
    public void theTaskbarMinimisesWithTheSameAnimationTheCaptionButtonUses() {
        build();
        WindowFrame frame = open("One");
        assertSame(frame, desktop.activeWindow());

        Desktop.setAnimationsEnabled(true);
        try {
            entry(frame).onPressed.emit();

            assertTrue("the taskbar put the document away with no animation at all", frame.isAnimating());
            assertEquals("it hid on the press, leaving nothing to animate",
                    WindowState.VISIBLE, frame.state());
        } finally {
            Desktop.setAnimationsEnabled(false);
        }
    }

    /**
     * And it is clickable <b>where it is drawn</b> — through the real mouse path, which is the half a
     * signal-driven test cannot answer. A strip that is laid out under the work area, or behind it, or
     * with no hit-testable box, passes every assertion above and cannot be used.
     */
    @Test
    public void anEntryIsHittableWhereItIsPainted() {
        build();
        WindowFrame first = open("One");
        WindowFrame second = open("Two");
        settle();

        Button target = entry(first);
        assertTrue("the entry has to have a box for this to mean anything",
                widthOf(target) > 0f && heightOf(target) > 0f);

        // WORLD, not parent-relative: an entry's `x()`/`y()` are its offset inside the STRIP, so
        // scaling them as if they were page coordinates aims at the top-left of the screen. The world
        // pair is already in surface pixels, which is what `consumeMouseEvent` takes -- so only the
        // half-extent is scaled, and the `* uiScale()` on the whole expression below goes.
        float x = target.box().worldX() + target.box().width() / 2f * uiScale();
        float y = target.box().worldY() + target.box().height() / 2f * uiScale();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x), Math.round(y), 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x), Math.round(y), 0, 0, 0, false, 0f, 1L));
        input.beginFrame();
        input.endFrame();
        settle();

        assertSame("the press reached the entry and activated its document", first, desktop.activeWindow());
        assertFalse(second.isActive());
    }

    /**
     * <b>Nothing is highlighted when nothing is active.</b>
     *
     * <p>Two ways to get there and both must hold: minimising the last document, and pressing bare
     * desktop. The highlight is a claim about where the keyboard is going, so a stale one is a lie —
     * and a lie that persists, because the strip only re-renders when the registry says something
     * changed, and "the active document went away" is precisely the kind of change that used not to
     * announce itself.</p>
     */
    @Test
    public void noEntryIsHighlightedOnceNoWindowIsActive() {
        build();
        WindowFrame first = open("One");
        WindowFrame second = open("Two");

        second.hide();
        settle();
        assertNull("minimising hands activation to nobody", desktop.activeWindow());
        assertFalse("so the entry of the document put away goes quiet",
                entry(second).hasClass(Taskbar.ACTIVE_CLASS));
        assertFalse("...and the one behind is not promoted into the highlight",
                entry(first).hasClass(Taskbar.ACTIVE_CLASS));

        desktop.activate(first);
        settle();
        assertTrue(entry(first).hasClass(Taskbar.ACTIVE_CLASS));

        first.hide();
        settle();
        assertNull("nothing left to be active", desktop.activeWindow());
        assertFalse("so nothing is highlighted", entry(first).hasClass(Taskbar.ACTIVE_CLASS));
        assertFalse(entry(second).hasClass(Taskbar.ACTIVE_CLASS));
    }

    /** The other route to no-active-document: a press on bare desktop. Same claim, same requirement that
     * the strip hears about it. */
    @Test
    public void pressingBareDesktopClearsTheHighlight() {
        build();
        WindowFrame frame = open("One");
        assertTrue(entry(frame).hasClass(Taskbar.ACTIVE_CLASS));

        desktop.deactivate();
        settle();

        assertNull(desktop.activeWindow());
        assertFalse(entry(frame).hasClass(Taskbar.ACTIVE_CLASS));
    }

    /**
     * <b>Minimising every document must leave the strip on screen.</b>
     *
     * <p>The one state where the taskbar is the <em>only</em> thing there is, so losing it there loses
     * every document at once — which is the failure W3 and W4 ship together to prevent, arriving through
     * the back door. It did: the desktop's "am I live?" test read the document LAYER, so minimising the
     * last document emptied it and collapsed the whole desktop to 0×0 at the origin, taking the taskbar
     * with it. Reported from the harness as "the bar goes off screen to the top left", which is exactly
     * what that looks like.</p>
     */
    @Test
    public void minimisingEveryWindowLeavesTheTaskbarOnScreen() {
        build();
        WindowFrame first = open("One");
        WindowFrame second = open("Two");

        first.hide();
        second.hide();
        settle();

        assertTrue("a desktop with retained windows is a desktop in use", desktop.isLive());
        assertEquals("and it still fills the root", 400f, desktop.box().width(), 0.01f);
        assertTrue("the strip is still there", heightOf(taskbar) > 0f);
        assertTrue("...and its entries are still clickable",
                entry(first).box().width() > 0f);

        // AND THE SURFACE STILL GOES BACK once the windows are genuinely gone, which is the half the
        // layer-based version got right and must not be lost in fixing the half it got wrong.
        first.destroy();
        second.destroy();
        settle();
        assertFalse(desktop.isLive());
        assertEquals(0f, desktop.box().width(), 0.01f);
    }

    /**
     * <b>The strip never spills off the desktop.</b> Entries accumulate — the plan's own harness key
     * opens one per press — and a row that simply grows would push its ends off both sides, since the
     * band centres its island. Off-screen is the worst failure available to a taskbar: the entry is the
     * only way back to a minimised document, so a document whose entry has left the screen is a document that
     * cannot be recovered at all.
     */
    @Test
    public void theStripStaysOnTheDesktopHoweverManyWindowsThereAre() {
        build();
        for (int i = 0; i < 20; i++) open("Window " + i);
        settle();

        float deskWidth = desktop.box().width();
        float islandWidth = taskbar.entries().box().width();
        assertTrue("twenty entries must not overflow a " + deskWidth + "px desktop: island is "
                + islandWidth, islandWidth <= deskWidth + 0.51f);

        float left = taskbar.entries().box().x() - desktop.box().x();
        assertTrue("...and must not hang off the left either: " + left, left >= -0.51f);
    }

    /** The strip is laid out, never overlaid — so the work area is simply what is left above it, and
     * every geometry rule that reads it needs no bar-shaped special case. */
    @Test
    public void theTaskbarTakesItsSpaceOutOfTheWorkArea() {
        build();
        open("One");
        settle();

        float barHeight = taskbar.box().height();
        assertTrue("the bar has to occupy something", barHeight > 0f);
        assertEquals("the work area is the rest of the desktop",
                desktop.box().height() - barHeight,
                desktop.windowLayer().box().height(), 0.51f);
    }
}
