package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import com.crystalgui.ui.elements.dock.DockRegion;
import com.crystalgui.ui.elements.dock.RegionSide;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.workbench.ToolWindowFrame;
import com.crystalgui.ui.elements.workbench.ToolWindowManager;
import com.crystalgui.ui.elements.workbench.ToolWindowType;
import com.crystalgui.ui.elements.workbench.ViewContainer;
import com.crystalgui.ui.elements.workbench.WorkbenchRegions;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A tool window torn out of its region and put in a window — W8.
 *
 * <p>What these pin is <b>ownership</b>, not geometry. A float's size and position are a
 * {@code WindowFrame}'s business and already tested as one; what is new here is that the same
 * container can be in a region or in a frame, that the frame is owned rather than promoted, and that
 * every path back out of a frame puts things where they came from. Each of those fails silently — a
 * leaked owned surface swallows clicks, a lost container loses view state, and neither points at a
 * tool window.</p>
 */
public class ToolWindowFloatTest extends UiTestBase {

    private static final String INSPECTOR = "inspector";

    private UIWindow window;
    private WindowFrame workbenchWindow;
    private WorkbenchRegions regions;
    private ToolWindowManager manager;

    @Before
    public void setUpWorkbench() {
        regions = new WorkbenchRegions(new UIElement());
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        registry.register(DockPanelDescriptor.container(INSPECTOR, "Inspector", DockRegion.AUXILIARY),
                ref -> new UIElement());
        manager = new ToolWindowManager(regions, registry);

        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        // The workbench lives in a window, which is what makes a float OWNED rather than top-level.
        workbenchWindow = window.openWindow(new WindowFrame("Workbench"));
        workbenchWindow.resizeTo(600, 400).moveTo(20, 20);
        workbenchWindow.setContent(regions.root());
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    // ── Ownership ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Owned, not promoted.</b> The failure this replaces is one {@code FloatingDock} shipped with:
     * {@code addToTopLayer()}, which paints after the whole main tree, so a float torn out of one
     * window hovers over whichever window is raised next.
     */
    @Test
    public void aFloatIsOwnedByTheWindowItCameOutOf() {
        manager.floatPanel(INSPECTOR, 40f, 40f, ToolWindowType.FLOATING);
        settle();

        ToolWindowFrame frame = manager.frameOf(INSPECTOR);
        assertNotNull("the float has a frame", frame);
        assertSame("parented into its owner's overlay slot",
                workbenchWindow.overlaySlot(), frame.getParent());
        assertTrue("and the owner knows it is there", workbenchWindow.hasOwnedWindows());
        assertFalse("it is not a taskbar citizen",
                window.desktop().registry().windows().contains(frame));
    }

    /**
     * <b>An owned window that is not modal must not block its owner.</b> The slot is sized to the whole
     * frame while it holds something — right for a modal's backdrop, and for a float it is a bug wearing
     * modality's clothes: the panel works, and every click anywhere else in the owner lands on a
     * transparent slot and does nothing. It was reported exactly that way, as the panel "opening as a
     * modal". Asserted on the slot's box rather than by dispatching a click, because the box is the
     * cause and a click is one of the symptoms.
     */
    @Test
    public void anOwnedFloatDoesNotCoverItsOwner() {
        manager.floatPanel(INSPECTOR, 40f, 40f, ToolWindowType.FLOATING);
        settle();

        UIElement slot = workbenchWindow.overlaySlot();
        assertTrue("the owner still holds it", workbenchWindow.hasOwnedWindows());
        assertEquals("the slot took a box and swallowed the owner's clicks",
                0f, slot.getRuntimeCache().getWidth(), 0.01f);
        assertEquals(0f, slot.getRuntimeCache().getHeight(), 0.01f);
    }

    /**
     * <b>And the tear-out produces a TOP-LEVEL window, not an owned one.</b> IntelliJ's gesture produces
     * its Float mode, which stays above the IDE frame; on a desktop that already has windows that is the
     * more confining answer and it does not match what the gesture looks like it is doing — an owned
     * window is clamped inside its owner, so a panel dragged out onto the desktop springs back into the
     * editor it came from.
     */
    @Test
    public void tearingOutProducesADesktopWindow() {
        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();

        ToolWindowFrame frame = manager.frameOf(INSPECTOR);
        assertNotNull(frame);
        assertEquals(ToolWindowType.WINDOWED, manager.typeOf(INSPECTOR));
        assertFalse("it should not be owned by the editor", workbenchWindow.hasOwnedWindows());
        assertTrue("and it is a taskbar citizen of its own",
                window.desktop().registry().windows().contains(frame));
    }

    /**
     * <b>The container is the same instance in both presentations.</b> It carries every piece of view
     * state a panel has — a tree's expansion, a scroll, a sort — so a float that rebuilt it would look
     * identical and quietly throw all of that away on every tear-out.
     */
    @Test
    public void theSameContainerMovesBetweenRegionAndFrame() {
        manager.showPanel(INSPECTOR);
        settle();
        ViewContainer docked = manager.containerOf(INSPECTOR);
        assertNotNull(docked);

        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();
        assertSame("floated", docked, manager.containerOf(INSPECTOR));
        assertTrue("and it is inside the frame", isInside(docked, manager.frameOf(INSPECTOR)));

        manager.dockPanel(INSPECTOR);
        settle();
        assertSame("docked again", docked, manager.containerOf(INSPECTOR));
        assertTrue("back in its region", isInside(docked, regions.root()));
    }

    /**
     * <b>One header.</b> The container brings its own, so a frame that did not adopt it would draw a
     * caption with a second title row underneath — the fault client-side decorations exist to fix, and
     * the one the editor already paid for at W7.
     */
    @Test
    public void theContainersHeaderIsAdoptedIntoTheCaption() {
        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();

        ToolWindowFrame frame = manager.frameOf(INSPECTOR);
        ViewContainer container = manager.containerOf(INSPECTOR);
        assertSame("the header is the frame's caption chrome",
                container.captionChrome(), frame.adoptedChrome());
        assertTrue("and it is in the title bar", isInside(frame.adoptedChrome(), frame.titleBar()));
        assertEquals("exactly one of it in the whole window", 1,
                countMatching(window.ui.rootElement, container.captionChrome()));
    }

    /**
     * <b>The round trip is lossless — and it was not.</b>
     *
     * <p>Asserted against the panel's own <em>before</em> geometry rather than against pixel constants:
     * the claim is that floating and docking changes nothing, so the two measurements are the test.</p>
     *
     * <p>What it caught is a cascade bug rather than a tool-window one. {@code invalidateStyleMatch()}
     * runs on an id, a class or a state change and <b>not</b> on being reparented, and the engine used
     * to forget which slots it had applied the moment an element detached — so an element moved out
     * from under a descendant selector kept those candidates, at their specificity, for good. The
     * header came home from the caption still carrying {@code padding-left: 0} and {@code flex-grow: 1}:
     * a 30px header where the sheet says 22, with the panel's content squeezed into what was left. Both
     * rules were correct, and the panel was broken only <em>after a round trip</em>.</p>
     */
    @Test
    public void dockingBackRestoresThePanelExactly() {
        manager.showPanel(INSPECTOR);
        settle();
        ViewContainer container = manager.containerOf(INSPECTOR);
        UIElement header = container.captionChrome();
        float headerHeight = header.getRuntimeCache().getHeight();
        float titleIndent = header.getChildren().get(0).getWindowX() - header.getWindowX();
        assertTrue("setup: the header has a box", headerHeight > 0f);
        assertTrue("setup: the header indents its title", titleIndent > 0f);

        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();
        manager.dockPanel(INSPECTOR);
        settle();

        assertEquals("the header came back a different height",
                headerHeight, header.getRuntimeCache().getHeight(), 0.01f);
        assertEquals("the header came back with the caption's indent",
                titleIndent, header.getChildren().get(0).getWindowX() - header.getWindowX(), 0.01f);
    }

    /** And docking gives it back, or the panel returns headerless. */
    @Test
    public void dockingReturnsTheHeader() {
        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();
        ViewContainer container = manager.containerOf(INSPECTOR);
        UIElement header = container.captionChrome();

        manager.dockPanel(INSPECTOR);
        settle();

        assertSame("home", container, header.getParent());
        assertTrue("and internal again", header.isInternalUI());
    }

    // ── Closing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The owned surface must be released.</b> The slot is sized only while something is live on it,
     * and a frame destroyed without being released leaves a full-size transparent box over the owner's
     * content — every click on the window the float came out of goes nowhere, and nothing about that
     * symptom names a tool window.
     */
    @Test
    public void closingAFloatReleasesItsOwnersSurface() {
        manager.floatPanel(INSPECTOR, 40f, 40f, ToolWindowType.FLOATING);
        settle();
        assertTrue(workbenchWindow.hasOwnedWindows());

        manager.hidePanel(INSPECTOR);
        settle();

        assertFalse("the slot is empty again", workbenchWindow.hasOwnedWindows());
        assertNull("and the frame is gone", manager.frameOf(INSPECTOR));
    }

    /**
     * <b>Re-entrant.</b> The frame's own ✕ reaches the manager through {@code onHidden}, and destroying
     * the frame emits {@code onHidden} — so the hide path calls itself every single time a user closes
     * a float. Taking the frame out of the map first is what stops the second pass re-reading a frame
     * that is mid-destroy and releasing the owner twice.
     */
    @Test
    public void theFramesOwnCloseButtonUnwindsCleanly() {
        manager.floatPanel(INSPECTOR, 40f, 40f, ToolWindowType.FLOATING);
        settle();

        manager.frameOf(INSPECTOR).requestClose();
        settle();

        assertNull("the frame is gone", manager.frameOf(INSPECTOR));
        assertFalse("the surface is released", workbenchWindow.hasOwnedWindows());
        assertFalse("and the model agrees it is shut", manager.isPanelOpen(INSPECTOR));
    }

    /** Closing a float hides the tool window; it does not throw the container away. */
    @Test
    public void closingHidesRatherThanDestroys() {
        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();
        ViewContainer container = manager.containerOf(INSPECTOR);

        manager.hidePanel(INSPECTOR);
        settle();

        assertSame("the container survives", container, manager.containerOf(INSPECTOR));
        assertEquals("and it is still remembered as windowed",
                ToolWindowType.WINDOWED, manager.typeOf(INSPECTOR));
    }

    // ── Stacking ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Raising the editor carries the panel torn out of it.</b> Win32's owner/owned rule, expressed as
     * a z-index over a group rather than as an always-on-top band.
     *
     * <p>The reported symptom is the whole of it: clicking the editor buried the Inspector that had just
     * been pulled out of it, which is behaviour no desktop has. A band would also fix it and claim far
     * too much — "above everything" puts the panel over windows it has nothing to do with, and the
     * pinned band is reserved for the HUD case where that is the point.</p>
     */
    @Test
    public void raisingTheOwnerCarriesItsToolWindows() {
        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();
        ToolWindowFrame floated = manager.frameOf(INSPECTOR);
        assertNotNull(floated);
        assertSame("the float should be owned by the workbench's window",
                workbenchWindow, floated.ownerWindow());

        window.desktop().raise(workbenchWindow);
        settle();

        assertTrue("clicking the editor buried the panel torn out of it",
                z(floated) > z(workbenchWindow));
    }

    /** And raising the panel brings its owner forward too, rather than lifting it out of the group. */
    @Test
    public void raisingAToolWindowBringsItsOwnerWithIt() {
        WindowFrame other = window.openWindow(new WindowFrame("Other"));
        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();
        ToolWindowFrame floated = manager.frameOf(INSPECTOR);

        window.desktop().raise(other);
        settle();
        assertTrue("setup: the unrelated window is on top",
                z(other) > z(floated));

        window.desktop().raise(floated);
        settle();

        assertTrue("the panel did not come forward", z(floated) > z(other));
        assertTrue("its owner was left behind", z(workbenchWindow) > z(other));
        assertTrue("and the panel is still above its owner",
                z(floated) > z(workbenchWindow));
    }

    // ── The mode is remembered ──────────────────────────────────────────────────────────────────

    /**
     * <b>The stripe button honours the remembered mode.</b> The whole reason the mode lives on the
     * placement record rather than on a frame: the frame is destroyed on every hide, so anything that
     * asked the frame would reopen a float as a docked panel.
     */
    @Test
    public void theStripeToggleReopensAFloatAsAFloat() {
        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();

        assertFalse("toggled shut", manager.togglePanel(INSPECTOR));
        settle();
        assertTrue("toggled open", manager.togglePanel(INSPECTOR));
        settle();

        assertEquals(ToolWindowType.WINDOWED, manager.typeOf(INSPECTOR));
        assertNotNull("in a frame, not in its region", manager.frameOf(INSPECTOR));
        assertNull("and the region is empty",
                regions.host(DockRegion.AUXILIARY).showing(RegionSide.PRIMARY));
    }

    /**
     * <b>A float comes back where it was left.</b> Geometry is the one thing a destroyed frame cannot be
     * asked for afterwards, which is why the record carries it.
     */
    @Test
    public void aFloatRemembersWhereItWas() {
        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();
        manager.frameOf(INSPECTOR).moveTo(90f, 70f);
        settle();

        manager.hidePanel(INSPECTOR);
        settle();
        manager.showPanel(INSPECTOR);
        settle();

        ToolWindowFrame frame = manager.frameOf(INSPECTOR);
        assertEquals(90f, frame.getWantedLeft(), 0.01f);
        assertEquals(70f, frame.getWantedTop(), 0.01f);
    }

    /**
     * <b>Geometry survives the whole round trip, not just hide/show.</b> float → resize → hide → show →
     * dock → float, which is the sequence a user actually performs.
     */
    @Test
    public void aFloatKeepsItsSizeThroughHideAndDock() {
        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();
        manager.frameOf(INSPECTOR).resizeTo(420f, 300f).moveTo(70f, 90f);
        settle();

        manager.hidePanel(INSPECTOR);
        settle();
        manager.showPanel(INSPECTOR);
        settle();
        assertEquals("lost across hide/show", 420f,
                manager.frameOf(INSPECTOR).getRuntimeCache().getWidth(), 0.01f);

        manager.dockPanel(INSPECTOR);
        settle();
        manager.floatPanel(INSPECTOR, 55f, 65f);
        settle();

        ToolWindowFrame frame = manager.frameOf(INSPECTOR);
        assertNotNull(frame);
        assertEquals("the remembered width was lost across the dock",
                420f, frame.getRuntimeCache().getWidth(), 0.01f);
        assertEquals("the remembered height was lost across the dock",
                300f, frame.getRuntimeCache().getHeight(), 0.01f);
        assertEquals("a tear-out places the window where it was dropped",
                55f, frame.getWantedLeft(), 0.01f);
    }

    /**
     * <b>And it survives being hidden by the frame's OWN button, which is a different path.</b>
     *
     * <p>{@code hidePanel} called directly reads the frame's box while it is still in the tree. The Hide
     * button does not: it runs {@code hide()}, which <em>detaches first</em> and only then emits
     * {@code onHidden} — so by the time the manager is told, the frame has left the tree and its Taffy
     * node is gone. Measuring there records a zero, and a zero is what the next tear-out restores.</p>
     */
    @Test
    public void aFloatHiddenByItsOwnButtonKeepsItsGeometry() {
        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();
        manager.frameOf(INSPECTOR).resizeTo(360f, 280f).moveTo(75f, 95f);
        settle();

        // THE REAL PATH: through the frame, not through the manager.
        manager.frameOf(INSPECTOR).requestClose();
        settle();
        assertNull("setup: the frame should be gone", manager.frameOf(INSPECTOR));

        manager.showPanel(INSPECTOR);
        settle();

        ToolWindowFrame frame = manager.frameOf(INSPECTOR);
        assertNotNull(frame);
        assertEquals("the width was measured after the detach", 360f,
                frame.getRuntimeCache().getWidth(), 0.01f);
        assertEquals("the height was measured after the detach", 280f,
                frame.getRuntimeCache().getHeight(), 0.01f);
        assertEquals("and the position went with it", 75f, frame.getWantedLeft(), 0.01f);
    }

    /**
     * <b>Changing the mode of a CLOSED tool window must not open it.</b> "Next time, float it" is a
     * legitimate thing to say about something you are not looking at, and a mode switch that popped a
     * panel open would make the setting unusable from a menu.
     */
    @Test
    public void settingTheModeOfAClosedPanelLeavesItClosed() {
        assertFalse(manager.isPanelOpen(INSPECTOR));

        manager.setType(INSPECTOR, ToolWindowType.FLOATING);
        settle();

        assertEquals(ToolWindowType.FLOATING, manager.typeOf(INSPECTOR));
        assertFalse("still shut", manager.isPanelOpen(INSPECTOR));
        assertNull("and no frame was built", manager.frameOf(INSPECTOR));
    }

    /**
     * <b>The region is vacated on the way out.</b> {@code setType} hides in the OLD presentation before
     * it repoints the record — writing the mode first would send the hide to dismantle a frame that
     * does not exist yet and leave the region still showing the panel.
     */
    @Test
    public void floatingAnOpenPanelEmptiesItsRegion() {
        manager.showPanel(INSPECTOR);
        settle();
        assertEquals(INSPECTOR, regions.host(DockRegion.AUXILIARY).showing(RegionSide.PRIMARY));

        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();

        assertNull("the region let go", regions.host(DockRegion.AUXILIARY).showing(RegionSide.PRIMARY));
        assertTrue("and it is open as a float", manager.isPanelOpen(INSPECTOR));
    }

    /**
     * <b>A region gives its space back even when the record disagrees with the host.</b>
     *
     * <p>The two can diverge — a placement read back from a session names one half while the host is
     * showing the panel in the other — and the old {@code hidePanel} guarded on the record, so it
     * refused to clear anything. Nothing failed loudly: the panel went into its frame regardless
     * (setContent reparents it out from under the host), and the region was left recording an occupant
     * it no longer contained. {@code isEmpty()} stayed false, so the region stayed in the split and its
     * whole width remained as a blank column beside the editor.</p>
     *
     * <p>The mismatch is arranged directly here rather than through a session, because the session is
     * only one of the ways to produce it and the rule under test is about the disagreement itself.</p>
     */
    @Test
    public void aRegionIsReleasedEvenWhenTheRecordNamesTheWrongHalf() {
        manager.showPanel(INSPECTOR);
        settle();
        assertTrue("setup: the region is in the split", regions.isVisible(DockRegion.AUXILIARY));

        // The host holds it in PRIMARY; tell the record it is in SECONDARY.
        manager.toolWindows().put(manager.toolWindows().getOrCreate(
                INSPECTOR, DockRegion.AUXILIARY, RegionSide.PRIMARY).withSide(RegionSide.SECONDARY));

        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();

        assertNull("the host still records a panel it no longer holds",
                regions.host(DockRegion.AUXILIARY).showing(RegionSide.PRIMARY));
        assertFalse("the region kept its width with nothing in it",
                regions.isVisible(DockRegion.AUXILIARY));
        assertTrue("and the panel is in its frame", manager.isPanelOpen(INSPECTOR));
    }

    /** Its place in the stack, read the way the desktop writes it. */
    private static int z(WindowFrame frame) {
        return frame.getStyle().getGeneralGroup().zIndex();
    }

    private static boolean isInside(UIElement element, UIElement ancestor) {
        for (UIElement walk = element; walk != null; walk = walk.getParent()) {
            if (walk == ancestor) return true;
        }
        return false;
    }

    private static int countMatching(UIElement from, UIElement target) {
        int found = from == target ? 1 : 0;
        for (UIElement child : from.getChildren()) found += countMatching(child, target);
        return found;
    }
}
