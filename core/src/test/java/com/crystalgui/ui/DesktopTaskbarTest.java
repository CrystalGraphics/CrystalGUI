package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.desktop.Desktop;
import com.crystalgui.ui.elements.desktop.Taskbar;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.desktop.WindowState;
import com.crystalgui.ui.input.UIInputHandler;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * CrystalOS W4 — the taskbar ({@code plan_windowing.md}).
 *
 * <p>W3 and W4 ship together or not at all: <b>minimise with no discoverable way back is worse than no
 * minimise</b>, and this is the way back. So the assertions here are mostly about the strip being an
 * honest view of the registry — a window that exists has an entry, a hidden one is dimmed rather than
 * dropped, and the entries do not move about.</p>
 */
public class DesktopTaskbarTest extends UiTestBase {

    private UIWindow window;
    private UIElement root;
    private Desktop desktop;
    private Taskbar taskbar;
    private UIInputHandler input;

    private void build() {
        root = new UIElement().layout(l -> l.width(400).height(300));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        desktop = window.desktop();
        taskbar = desktop.taskbar();
        settle();
        input = window.getInputHandler();
        input.beginFrame();
        input.endFrame();
    }

    private void settle() {
        for (int pass = 0; pass < 2; pass++) {
            window.getStyleEngine().calculateStyle(0.016f);
            window.calculateLayout();
        }
    }

    private WindowFrame open(String title) {
        WindowFrame frame = window.openWindow(new WindowFrame(title));
        frame.resizeTo(120, 90);
        settle();
        return frame;
    }

    private Button entry(WindowFrame frame) {
        Button entry = taskbar.entryFor(frame);
        assertNotNull("every live window has an entry", entry);
        return entry;
    }

    // ── The strip is the registry, rendered ─────────────────────────────────

    @Test
    public void everyLiveWindowHasAnEntryAndADestroyedOneDoesNot() {
        build();
        WindowFrame first = open("One");
        WindowFrame second = open("Two");

        assertEquals(2, taskbar.entries().getChildren().size());
        assertEquals("One", entry(first).getText());
        assertEquals("Two", entry(second).getText());

        second.destroy();
        settle();

        assertEquals(1, taskbar.entries().getChildren().size());
        assertNull("a destroyed window's entry goes with it", taskbar.entryFor(second));
    }

    /**
     * <b>A hidden window keeps its entry.</b> That is the whole reason the strip renders the registry
     * rather than the tree: the entry is how a minimised window comes back, so dropping it would make
     * minimise a one-way trip.
     */
    @Test
    public void aHiddenWindowIsDimmedRatherThanDropped() {
        build();
        WindowFrame frame = open("One");

        frame.hide();
        settle();

        Button entry = entry(frame);
        assertTrue("still on the strip", entry.getParent() != null);
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

        assertEquals(0, entry(first).getSiblingIndex());
        assertEquals(1, entry(second).getSiblingIndex());
        assertEquals(2, entry(third).getSiblingIndex());
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
     * <b>The toggle is what makes a taskbar a taskbar.</b> Clicking the entry of the window you are
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
                target.getRuntimeCache().getWidth() > 0f && target.getRuntimeCache().getHeight() > 0f);

        float x = target.getRuntimeCache().getX() + target.getRuntimeCache().getWidth() / 2f;
        float y = target.getRuntimeCache().getY() + target.getRuntimeCache().getHeight() / 2f;
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, 0, false, 0f, 1L));
        input.beginFrame();
        input.endFrame();
        settle();

        assertSame("the press reached the entry and activated its window", first, desktop.activeWindow());
        assertFalse(second.isActive());
    }

    /**
     * <b>Nothing is highlighted when nothing is active.</b>
     *
     * <p>Two ways to get there and both must hold: minimising the last window, and pressing bare
     * desktop. The highlight is a claim about where the keyboard is going, so a stale one is a lie —
     * and a lie that persists, because the strip only re-renders when the registry says something
     * changed, and "the active window went away" is precisely the kind of change that used not to
     * announce itself.</p>
     */
    @Test
    public void noEntryIsHighlightedOnceNoWindowIsActive() {
        build();
        WindowFrame first = open("One");
        WindowFrame second = open("Two");

        second.hide();
        settle();
        assertSame("the window behind takes over", first, desktop.activeWindow());
        assertTrue(entry(first).hasClass(Taskbar.ACTIVE_CLASS));
        assertFalse("a hidden window is not the active one", entry(second).hasClass(Taskbar.ACTIVE_CLASS));

        first.hide();
        settle();
        assertNull("nothing left to be active", desktop.activeWindow());
        assertFalse("so nothing is highlighted", entry(first).hasClass(Taskbar.ACTIVE_CLASS));
        assertFalse(entry(second).hasClass(Taskbar.ACTIVE_CLASS));
    }

    /** The other route to no-active-window: a press on bare desktop. Same claim, same requirement that
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
     * <b>Minimising every window must leave the strip on screen.</b>
     *
     * <p>The one state where the taskbar is the <em>only</em> thing there is, so losing it there loses
     * every window at once — which is the failure W3 and W4 ship together to prevent, arriving through
     * the back door. It did: the desktop's "am I live?" test read the window LAYER, so minimising the
     * last window emptied it and collapsed the whole desktop to 0×0 at the origin, taking the taskbar
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
        assertEquals("and it still fills the root", 400f, desktop.getRuntimeCache().getWidth(), 0.01f);
        assertTrue("the strip is still there", taskbar.getRuntimeCache().getHeight() > 0f);
        assertTrue("...and its entries are still clickable",
                entry(first).getRuntimeCache().getWidth() > 0f);

        // AND THE SURFACE STILL GOES BACK once the windows are genuinely gone, which is the half the
        // layer-based version got right and must not be lost in fixing the half it got wrong.
        first.destroy();
        second.destroy();
        settle();
        assertFalse(desktop.isLive());
        assertEquals(0f, desktop.getRuntimeCache().getWidth(), 0.01f);
    }

    /**
     * <b>The strip never spills off the desktop.</b> Entries accumulate — the plan's own harness key
     * opens one per press — and a row that simply grows would push its ends off both sides, since the
     * band centres its island. Off-screen is the worst failure available to a taskbar: the entry is the
     * only way back to a minimised window, so a window whose entry has left the screen is a window that
     * cannot be recovered at all.
     */
    @Test
    public void theStripStaysOnTheDesktopHoweverManyWindowsThereAre() {
        build();
        for (int i = 0; i < 20; i++) open("Window " + i);
        settle();

        float deskWidth = desktop.getRuntimeCache().getWidth();
        float islandWidth = taskbar.entries().getRuntimeCache().getWidth();
        assertTrue("twenty entries must not overflow a " + deskWidth + "px desktop: island is "
                + islandWidth, islandWidth <= deskWidth + 0.51f);

        float left = taskbar.entries().getRuntimeCache().getX() - desktop.getRuntimeCache().getX();
        assertTrue("...and must not hang off the left either: " + left, left >= -0.51f);
    }

    /** The strip is laid out, never overlaid — so the work area is simply what is left above it, and
     * every geometry rule that reads it needs no bar-shaped special case. */
    @Test
    public void theTaskbarTakesItsSpaceOutOfTheWorkArea() {
        build();
        open("One");
        settle();

        float barHeight = taskbar.getRuntimeCache().getHeight();
        assertTrue("the bar has to occupy something", barHeight > 0f);
        assertEquals("the work area is the rest of the desktop",
                desktop.getRuntimeCache().getHeight() - barHeight,
                desktop.windowLayer().getRuntimeCache().getHeight(), 0.51f);
    }
}
