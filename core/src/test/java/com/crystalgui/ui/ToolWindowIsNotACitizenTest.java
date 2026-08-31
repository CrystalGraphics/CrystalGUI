package com.crystalgui.ui;

import com.crystalgui.core.window.WindowState;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.desktop.Desktop;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import com.crystalgui.ui.elements.dock.DockRegion;
import com.crystalgui.ui.elements.workbench.ToolWindowFrame;
import com.crystalgui.ui.elements.workbench.ToolWindowManager;
import com.crystalgui.ui.elements.workbench.ToolWindowType;
import com.crystalgui.ui.elements.workbench.WorkbenchRegions;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A tool window is part of the window it belongs to, not a peer of it — Win32's {@code WS_EX_TOOLWINDOW}.
 *
 * <p>What these pin is <b>citizenship</b>, which is three separate facts that have to agree: a tool
 * window is in no taskbar, in no switcher, and goes away when the thing it belongs to does. Each was
 * wrong independently, and each is invisible from the others — the strip along the bottom read
 * <i>Welcome · Geometry · Crystal Editor · Inspector · Notifications</i> while the hide cascade did not
 * exist at all, so minimising the editor left two panels floating over an empty desktop.</p>
 *
 * <p>The counter-assertions matter as much as the assertions. A filter that excluded <em>everything</em>
 * would pass half of these, so every case also names a window that must still be a citizen.</p>
 */
public class ToolWindowIsNotACitizenTest extends UiTestBase {

    private static final String INSPECTOR = "inspector";

    private UIWindow window;
    private WindowFrame workbenchWindow;
    private ToolWindowManager manager;

    @Before
    public void setUpWorkbench() {
        WorkbenchRegions regions = new WorkbenchRegions(new UIElement());
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        registry.register(DockPanelDescriptor.container(INSPECTOR, "Inspector", DockRegion.AUXILIARY),
                ref -> new UIElement());
        manager = new ToolWindowManager(regions, registry);

        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        workbenchWindow = window.openWindow(new WindowFrame("Workbench"));
        workbenchWindow.resizeTo(600, 400).moveTo(20, 20);
        workbenchWindow.setContent(regions.root());
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    /** Opens the Inspector as a top-level tool window, owned by the workbench's frame. */
    private ToolWindowFrame openWindowed() {
        manager.floatPanel(INSPECTOR, 40f, 40f, ToolWindowType.WINDOWED);
        settle();
        ToolWindowFrame frame = manager.frameOf(INSPECTOR);
        assertNotNull("the fixture did not open a windowed tool window", frame);
        return frame;
    }

    // ── Citizenship ─────────────────────────────────────────────────────────────────────────────

    /**
     * <b>It is a live window, and it is in neither of the lists a live window is normally shown in.</b>
     *
     * <p>All three assertions are needed. Being absent from the registry entirely would satisfy the
     * second and third and break eviction, key lookup and the desktop's own "is anything open" — which
     * is why the filtering is on the presentation views rather than on membership.</p>
     */
    @Test
    public void aWindowedToolWindowIsLiveButIsNotAnEntry() {
        ToolWindowFrame frame = openWindowed();
        var registry = window.desktop().registry();

        assertTrue("a tool window is still a live window", registry.windows().contains(frame));
        assertFalse("a tool window must not have a taskbar entry",
                registry.taskbarOrder().contains(frame));
        assertFalse("a tool window must not be offered by the switcher",
                registry.switcherOrder().contains(frame));

        // ...and the filter is not simply eating everything.
        assertTrue("an ordinary window lost its taskbar entry",
                registry.taskbarOrder().contains(workbenchWindow));
        assertTrue("an ordinary window lost its place in the switcher",
                registry.switcherOrder().contains(workbenchWindow));
    }

    /**
     * <b>A plain frame is a citizen, so the flag is what decides and not the fact of being owned.</b>
     *
     * <p>This is the torn-out editor case in miniature: a window that came out of another window and
     * still deserves an entry. If citizenship were derived from having an owner instead of from the
     * flag, a torn-out editor would silently vanish from the taskbar the moment it was given one.</p>
     */
    @Test
    public void anOwnedWindowThatIsNotAToolWindowKeepsItsEntry() {
        WindowFrame torn = window.openWindow(new WindowFrame("Torn-out editor"));
        torn.setOwnerWindow(workbenchWindow);
        settle();

        assertFalse("the fixture accidentally made it a tool window", torn.isToolWindow());
        assertTrue("an owned NON-tool window must keep its taskbar entry",
                window.desktop().registry().taskbarOrder().contains(torn));
    }

    // ── The hide cascade ────────────────────────────────────────────────────────────────────────

    /**
     * <b>Hiding the owner takes its tool windows with it, and showing it puts them back.</b>
     *
     * <p>Win32's owner/owned rule. A {@code FLOATING} tool window gets this for nothing by being a child
     * of the overlay slot; a {@code WINDOWED} one is genuinely top-level and has a life of its own to
     * suspend, which is the whole reason the cascade exists.</p>
     */
    @Test
    public void hidingTheOwnerHidesItsToolWindowAndShowingBringsItBack() {
        ToolWindowFrame frame = openWindowed();
        assertEquals(WindowState.VISIBLE, frame.state());

        workbenchWindow.hide();
        settle();
        assertEquals("minimising the workbench left its tool window on screen",
                WindowState.HIDDEN, frame.state());

        workbenchWindow.show(true);
        settle();
        assertEquals("restoring the workbench did not bring its tool window back",
                WindowState.VISIBLE, frame.state());
    }

    /**
     * <b>...but a tool window the user had ALREADY put away stays away.</b>
     *
     * <p>The case a re-walk of the owner group cannot get right, and the reason the cascade remembers
     * what it took rather than recomputing it: by the time the owner comes back, "hidden because the
     * owner went" and "hidden because somebody closed it" are indistinguishable. Resurrecting the second
     * kind is the same defect as a session restore reopening a panel that was deliberately closed.</p>
     *
     * <p><b>Asserted at the PANEL, not at the frame</b>, and the first version of this test got that
     * wrong. A user hide routes through {@code onHidden} into {@code hideFrame}, which
     * <em>destroys</em> the frame — the frame is built per show and destroyed per hide, because what
     * survives a hide is the placement record and not the window. So the frame's state after a real
     * close is {@code DESTROYED} rather than {@code HIDDEN}, and asking it anything is asking the wrong
     * object. What must be true is that the panel is still closed and no frame has come back.</p>
     */
    @Test
    public void aToolWindowClosedBeforeItsOwnerStaysClosed() {
        ToolWindowFrame frame = openWindowed();

        frame.hide();
        settle();
        assertFalse("the fixture did not actually close the panel", manager.isPanelOpen(INSPECTOR));

        workbenchWindow.hide();
        settle();
        workbenchWindow.show(true);
        settle();

        assertFalse("restoring the owner reopened a panel the user had closed",
                manager.isPanelOpen(INSPECTOR));
        assertNull("restoring the owner built a frame for a closed panel", manager.frameOf(INSPECTOR));
    }

    /**
     * <b>Minimising the owner starts the panels' own flight, at the moment of the gesture.</b>
     *
     * <p>The cascade first lived in {@code hide()} alone, and for a minimise {@code hide()} is the
     * animation's <em>continuation</em> — so it ran 400ms late and detached with no animation at all.
     * On screen the window sailed into the taskbar and its panels blinked out of existence once it
     * landed, which reads as the panels not being part of the gesture.</p>
     *
     * <p><b>Asserted on whether a timeline is RUNNING</b>, because that is the only thing that separates
     * "animating" from "applied instantly" — a test that checked the panel was on its way out would pass
     * against the synchronous version too. Intermediate frames are unreachable (the driver advances on
     * {@code System.nanoTime()}), and the two facts that matter are reachable: it is playing, and it has
     * not gone yet.</p>
     *
     * <p>This is the one case here that needs animations <b>on</b>. With them disabled the continuation
     * runs synchronously and the two orderings are indistinguishable, which is exactly why the ordering
     * bug survived a green suite.</p>
     */
    @Test
    public void minimisingTheOwnerAnimatesItsToolWindowsOut() {
        // OPENED WITH ANIMATIONS OFF, which is load-bearing rather than tidy: with them on, the tool
        // window's own OPEN timeline is still running when the gesture starts, so isAnimating() answers
        // true whatever the minimise did and the test passes against no fix at all. Caught by a mutant,
        // exactly as the standing note about this fixture shape warns.
        ToolWindowFrame frame = openWindowed();

        Desktop.setAnimationsEnabled(true);
        try {
            workbenchWindow.minimize();

            assertTrue("the tool window did not start its own flight when the owner was minimised",
                    frame.isAnimating());
            assertEquals("the tool window was detached instead of being animated out",
                    WindowState.VISIBLE, frame.state());
        } finally {
            Desktop.setAnimationsEnabled(false);
        }
    }

    /**
     * <b>The owner's own hide landing must not cut short a flight it started.</b>
     *
     * <p>A minimise cascades at gesture time and then hides itself as the animation's continuation — so
     * the owner's {@code hide()} runs a second cascade while the first is still in the air. Without a
     * marker for "already leaving with us" that second pass hard-hides every panel at exactly the moment
     * the owner's flight ends, which is bit-for-bit the bug the animated cascade was written to fix,
     * reintroduced from the other side.</p>
     */
    @Test
    public void theOwnersOwnHideDoesNotCutShortTheFlightItStarted() {
        ToolWindowFrame frame = openWindowed();

        Desktop.setAnimationsEnabled(true);
        try {
            workbenchWindow.minimize();
            assertTrue(frame.isAnimating());

            // What the animation's continuation does when it lands.
            workbenchWindow.hide();

            assertEquals("the owner's hide detached a tool window that was still flying",
                    WindowState.VISIBLE, frame.state());
            assertTrue("the tool window's flight was cancelled", frame.isAnimating());
        } finally {
            Desktop.setAnimationsEnabled(false);
        }
    }

    /**
     * <b>A cascade is not a close, so the panel is still open as far as anything asking is concerned.</b>
     *
     * <p>{@code onHidden} has exactly one listener and it reads the signal as the user closing the panel
     * — it calls {@code hidePanel}, which drops the frame and records the tool window shut. Firing it
     * during a cascade would mean minimising the workbench marked every panel on it closed, and the very
     * next session save would write that down: the window comes back, the panels do not. Asserted
     * through {@code isPanelOpen} because that is what the session record actually reads.</p>
     */
    @Test
    public void aCascadeDoesNotRecordThePanelAsClosed() {
        openWindowed();
        assertTrue(manager.isPanelOpen(INSPECTOR));

        workbenchWindow.hide();
        settle();
        assertTrue("minimising the workbench recorded its tool window as closed",
                manager.isPanelOpen(INSPECTOR));

        workbenchWindow.show(true);
        settle();
        assertTrue(manager.isPanelOpen(INSPECTOR));
    }

    /**
     * <b>A user-driven hide still IS a close, or the Hide button stops working.</b>
     *
     * <p>The other half of the row above, and the one that a too-broad suppression would break silently:
     * if {@code onHidden} were suppressed generally rather than only for the duration of a cascade, the
     * frame's own Hide button would take the window off screen and leave the manager believing the panel
     * was still open — so the stripe button would not un-press and reopening would be a no-op.</p>
     */
    @Test
    public void hidingAToolWindowDirectlyStillClosesThePanel() {
        ToolWindowFrame frame = openWindowed();

        frame.hide();
        settle();

        assertFalse("hiding a tool window by hand did not record the panel closed",
                manager.isPanelOpen(INSPECTOR));
    }
}
