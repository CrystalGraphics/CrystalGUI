package com.crystalgui.workbench.toolwindow;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.widget.control.Button;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.workbench.view.ViewContainer;
import com.crystalgui.workbench.region.WorkbenchRegions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A tool document torn out of its region and put in a document — W8.
 *
 * <p>What these pin is <b>ownership</b>, not geometry. A float's size and position are a
 * {@code WindowFrame}'s business and already tested as one; what is new here is that the same
 * container can be in a region or in a frame, that the frame is owned rather than promoted, and that
 * every path back out of a frame puts things where they came from. Each of those fails silently — a
 * leaked owned surface swallows clicks, a lost container loses view state, and neither points at a
 * tool document.</p>
 */
public class ToolWindowFloatTest extends UiDocumentTestBase {

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

    private static final String INSPECTOR = "inspector";

    private WindowFrame workbenchWindow;
    /** A focusable thing outside every document — where focus sits before the gesture under test. */
    private Button elsewhere;
    private WorkbenchRegions regions;
    private ToolWindowManager manager;

    @After
    public void animationsBackOn() {
        Desktop.setAnimationsEnabled(true);
    }

    @Before
    public void setUpWorkbench() {
        // Animations OFF, stated rather than inherited. Every assertion in this fixture reads a
        // geometry or a state straight after a gesture, and a running timeline defers both -- `hide()`
        // detaches and `close()` destroys only once the flight ends, so the assertion reads the state
        // BEFORE the gesture took effect and the numbers it does get are mid-flight fractions.
        Desktop.setAnimationsEnabled(false);
        regions = new WorkbenchRegions(new UIElement());
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        registry.register(DockPanelDescriptor.container(INSPECTOR, "Inspector", DockRegion.AUXILIARY),
                ref -> new UIElement());
        manager = new ToolWindowManager(regions, registry);

        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        elsewhere = new Button("elsewhere");
        root.append(elsewhere);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);

        // The workbench lives in a document, which is what makes a float OWNED rather than top-level.
        workbenchWindow = Desktop.of(document).addWindow(new WindowFrame("Workbench"));
        workbenchWindow.resizeTo(600, 400).moveTo(20, 20);
        workbenchWindow.setContent(regions.root());
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) frame();
    }

    /**
     * A real press at a point, through {@code consumeMouseEvent} — <b>not</b> {@code sendInputEvent}.
     *
     * <p>The difference is the whole reason the first version of these tests passed against the bug.
     * {@code sendInputEvent} dispatches straight at an element and skips {@code emitMouseDown}, which is
     * where click-focus happens: the real path walks up from whatever was hit to the nearest ancestor
     * that focuses on click and focuses it <em>before</em> anything is dispatched. For a document frame
     * that ancestor is the frame, and everything downstream then sees a document that already "has" focus.
     * A fixture that skips it can never see that.</p>
     */
    private void pressAt(float x, float y) {
        frame();
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * uiScale()), Math.round(y * uiScale()), 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 0L));
        settle();
        frame();
        settle();
    }

    /** Presses a frame's caption, where a drag begins. */
    private void pressCaption(ToolWindowFrame frame) {
        var box = frame.box();
        // The frame's position ON SCREEN, not inside its host. An owned float is parented onto its
        // owner, so `box.x()` is an offset within that window and pressing it aims somewhere near the
        // top-left of the desktop -- the press lands on bare surface and nothing is focused, which
        // reads as the focus delegate not running.
        float scale = document.boxes().uiScale();
        pressAt(box.worldX() / scale + box.width() / 2f, box.worldY() / scale + 8f);
    }

    /** Whether focus is inside the panel's own container — what lights its rail button. */
    private boolean panelHasFocus() {
        // Through shadow boundaries: `parent()` stops at a shadow root, so a focus owner that is a
        // widget's own part -- which most focusable things now are -- never reaches the container
        // holding it. The host is the step that continues the walk.
        for (UIElement e = document.focus().focused(); e != null; e = outward(e)) {
            if (e instanceof ViewContainer container) return INSPECTOR.equals(container.containerId());
        }
        return false;
    }

    private static UIElement outward(UIElement node) {
        UIElement parent = node.parent();
        if (parent != null) return parent;
        return node instanceof ShadowRoot shadow ? shadow.host() : null;
    }

    /**
     * <b>Grabbing a float focuses the PANEL, which is what lights its rail button.</b>
     *
     * <p>A drag never completes a click, so nothing downstream of a mouse-up can be relied on — the
     * selection a click would have made never happens. The press is the moment.</p>
     */
    @Test
    public void pressingAFloatFocusesItsPanel() {
        manager.floatPanel(INSPECTOR, 40f, 40f, ToolWindowType.FLOATING);
        settle();
        ToolWindowFrame frame = manager.frameOf(INSPECTOR);
        assertNotNull(frame);

        // SOMEWHERE OUTSIDE EVERY WINDOW. Not the workbench frame: focusing a frame now delegates
        // into its content, and the float is OWNED by that frame -- so focus would land right back in
        // the panel and the precondition would be describing the thing under test.
        document.focus().requestFocus(elsewhere);
        settle();
        assertFalse("the fixture starts with the panel already focused", panelHasFocus());

        pressCaption(frame);

        assertTrue("grabbing a float by its caption did not focus the panel inside it",
                panelHasFocus());
    }

    /**
     * <b>...and so does grabbing a WINDOWED one, which reaches it by a different route.</b>
     *
     * <p>Worth asserting separately rather than assuming: a windowed tool document is a desktop citizen, so
     * a press goes through {@code Desktop.activate} and focus arrives via the frame's focus delegate. A
     * float is in no registry at all and there is nothing to activate — the two ends meet at the same
     * place through code that has nothing in common, which is exactly the shape where one quietly works
     * and the other does not.</p>
     */
    @Test
    public void pressingAWindowedToolWindowFocusesItsPanel() {
        manager.floatPanel(INSPECTOR, 40f, 40f, ToolWindowType.WINDOWED);
        settle();
        ToolWindowFrame frame = manager.frameOf(INSPECTOR);
        assertNotNull(frame);

        document.focus().requestFocus(elsewhere);
        settle();
        assertFalse("the fixture starts with the panel already focused", panelHasFocus());

        pressCaption(frame);

        assertTrue("grabbing a windowed tool document by its caption did not focus the panel inside it",
                panelHasFocus());
    }

    // ── Ownership ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Owned, not promoted.</b> The failure this replaces is one {@code FloatingDock} shipped with:
     * {@code document.promote(this)}, which paints after the whole main tree, so a float torn out of one
     * document hovers over whichever document is raised next.
     */
    @Test
    public void aFloatIsOwnedByTheWindowItCameOutOf() {
        manager.floatPanel(INSPECTOR, 40f, 40f, ToolWindowType.FLOATING);
        settle();

        ToolWindowFrame frame = manager.frameOf(INSPECTOR);
        assertNotNull("the float has a frame", frame);
        assertSame("parented into its owner's overlay slot",
                workbenchWindow.overlaySlot(), frame.parent());
        assertTrue("and the owner knows it is there", workbenchWindow.hasOwnedWindows());
        assertFalse("it is not a taskbar citizen",
                Desktop.of(document).registry().windows().contains(frame));
    }

    /**
     * <b>An owned document that is not modal must not block its owner.</b> The slot is sized to the whole
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

        ToolWindowFrame float_ = manager.frameOf(INSPECTOR);
        assertTrue("the owner still holds it", workbenchWindow.hasOwnedWindows());

        // THERE IS NO SLOT TO MEASURE ANY MORE, and the bug it was measuring cannot occur. The old
        // engine gave an owner a dedicated overlay ELEMENT that took a full-size box whenever it held
        // something, so an open float made a transparent sheet over the whole window and every click
        // outside the panel died on it -- reported as the panel "opening as a modal". `attachOwned`
        // parents the float onto the frame itself now (`overlaySlot()` answers `this`), so there is no
        // intermediate box to be sized wrongly: what covers the owner is exactly the float's own box.
        // So the modern statement of the same guarantee is the SYMPTOM the old one reasoned back from
        // -- the owner is still reachable beside the float.
        Box ownerBox = workbenchWindow.box();
        Box floatBox = float_.box();
        assertNotNull("the owner has no box to be covered", ownerBox);
        assertNotNull("the float never got one", floatBox);
        assertTrue("the float covers its whole owner, which is a modal by another name",
                floatBox.width() < ownerBox.width() && floatBox.height() < ownerBox.height());
    }

    /**
     * <b>And the tear-out produces a TOP-LEVEL document, not an owned one.</b> IntelliJ's gesture produces
     * its Float mode, which stays above the IDE frame; on a desktop that already has windows that is the
     * more confining answer and it does not match what the gesture looks like it is doing — an owned
     * document is clamped inside its owner, so a panel dragged out onto the desktop springs back into the
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
                Desktop.of(document).registry().windows().contains(frame));
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
        assertEquals("exactly one of it in the whole document", 1,
                countMatching(document, container.captionChrome()));
    }

    /**
     * <b>The round trip is lossless — and it was not.</b>
     *
     * <p>Asserted against the panel's own <em>before</em> geometry rather than against pixel constants:
     * the claim is that floating and docking changes nothing, so the two measurements are the test.</p>
     *
     * <p>What it caught is a cascade bug rather than a tool-document one. {@code invalidateStyleMatch()}
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
        float headerHeight = header.box().height();
        float titleIndent = header.children().get(0).box().worldX() - header.box().worldX();
        assertTrue("setup: the header has a box", headerHeight > 0f);
        assertTrue("setup: the header indents its title", titleIndent > 0f);

        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();
        manager.dockPanel(INSPECTOR);
        settle();

        assertEquals("the header came back a different height",
                headerHeight, header.box().height(), 0.01f);
        assertEquals("the header came back with the caption's indent",
                titleIndent, header.children().get(0).box().worldX() - header.box().worldX(), 0.01f);
    }

    /**
     * <b>The adopted header keeps its panel styling.</b>
     *
     * <p>Every rule for it used to be scoped through {@code .__view-container__}, which stops matching
     * the instant {@code WindowChrome} moves the element into a caption — so a floated panel lost its
     * row direction and wrapped its tabs onto a second line, lost the title's weight and colour, and
     * lost the selected tab's focus tint. Nothing was wrong with any of those rules; they were
     * describing an element that had moved.</p>
     *
     * <p>Asserted against the docked measurement rather than a constant: the claim is that adoption
     * changes where the header IS and nothing about what it looks like.</p>
     */
    @Test
    public void theAdoptedHeaderKeepsItsPanelStyling() {
        manager.showPanel(INSPECTOR);
        settle();
        ViewContainer container = manager.containerOf(INSPECTOR);
        UIElement header = container.captionChrome();
        float dockedHeight = header.box().height();
        float dockedIndent = header.children().get(0).box().worldX() - header.box().worldX();
        assertTrue("setup: the docked header has a box", dockedHeight > 0f);
        assertTrue("setup would prove nothing if the docked indent were also zero", dockedIndent > 0f);

        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();

        assertTrue("the header is in the caption",
                isInside(header, manager.frameOf(INSPECTOR).titleBar()));
        assertEquals("it lost its height when it left the container",
                dockedHeight, header.box().height(), 0.01f);
        // The indent is deliberately DROPPED in a caption (ua/desktop.css), so this asserts the one
        // difference is the one that was asked for rather than that nothing changed at all.
        assertEquals("a caption drops the panel indent", 0f,
                header.children().get(0).box().worldX() - header.box().worldX(), 0.01f);
    }

    /**
     * <b>The header lays its parts out in a row that does not overlap.</b>
     *
     * <p>A {@code UIText} has no Taffy {@code MeasureFunc}, so in a flex row it contributes nothing and
     * settles at zero width — while still painting its text, because nothing clips it. The title
     * therefore drew across whatever the view contributed beside it, which reads as a z-order or
     * padding fault rather than as a box that was never asked for. Asserted as "the next thing starts
     * after the title ends", which is the property, rather than as a pixel width.</p>
     */
    @Test
    public void theHeadersPartsDoNotOverlap() {
        manager.showPanel(INSPECTOR);
        settle();
        UIElement header = manager.containerOf(INSPECTOR).captionChrome();
        UIElement title = header.children().get(0);

        float titleEnd = title.box().worldX() + title.box().width();
        assertTrue("the title claimed no width, so anything beside it draws over it",
                widthOf(title) > 0f);
        for (int at = 1; at < header.children().size(); at++) {
            UIElement sibling = header.children().get(at);
            assertTrue("a header part starts before the title ends: " + sibling.classes(),
                    sibling.box().worldX() >= titleEnd);
        }
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

        assertSame("home", container, header.parent());
        // THE FLAG IS GONE, and with it the state this asserted. The old engine stored
        // "is an internal child" as a bit and `removeChild` refused anything carrying it, so a
        // round trip had to put the bit back or the header became publicly detachable. Here what
        // makes a part a part is that the widget PUT IT THERE -- `insertStructuralAt` sets the flag
        // for the duration of one insertion and restores it -- so there is nothing on the node to
        // check and nothing that could have been left wrong. The two assertions above are the whole
        // of what the round trip has to get right now.
    }

    // ── Closing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The owned surface must be released.</b> The slot is sized only while something is live on it,
     * and a frame destroyed without being released leaves a full-size transparent box over the owner's
     * content — every click on the document the float came out of goes nowhere, and nothing about that
     * symptom names a tool document.
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

    /** Closing a float hides the tool document; it does not throw the container away. */
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
        assertSame("the float should be owned by the workbench's document",
                workbenchWindow, floated.ownerWindow());

        Desktop.of(document).raise(workbenchWindow);
        settle();

        assertTrue("clicking the editor buried the panel torn out of it",
                z(floated) > z(workbenchWindow));
    }

    /** And raising the panel brings its owner forward too, rather than lifting it out of the group. */
    @Test
    public void raisingAToolWindowBringsItsOwnerWithIt() {
        WindowFrame other = Desktop.of(document).addWindow(new WindowFrame("Other"));
        manager.floatPanel(INSPECTOR, 40f, 40f);
        settle();
        ToolWindowFrame floated = manager.frameOf(INSPECTOR);

        Desktop.of(document).raise(other);
        settle();
        assertTrue("setup: the unrelated document is on top",
                z(other) > z(floated));

        Desktop.of(document).raise(floated);
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
                manager.frameOf(INSPECTOR).box().width(), 0.01f);

        manager.dockPanel(INSPECTOR);
        settle();
        manager.floatPanel(INSPECTOR, 55f, 65f);
        settle();

        ToolWindowFrame frame = manager.frameOf(INSPECTOR);
        assertNotNull(frame);
        assertEquals("the remembered width was lost across the dock",
                420f, frame.box().width(), 0.01f);
        assertEquals("the remembered height was lost across the dock",
                300f, frame.box().height(), 0.01f);
        assertEquals("a tear-out places the document where it was dropped",
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
                frame.box().width(), 0.01f);
        assertEquals("the height was measured after the detach", 280f,
                frame.box().height(), 0.01f);
        assertEquals("and the position went with it", 75f, frame.getWantedLeft(), 0.01f);
    }

    /**
     * <b>Changing the mode of a CLOSED tool document must not open it.</b> "Next time, float it" is a
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
        for (UIElement walk = element; walk != null; walk = walk.parent()) {
            if (walk == ancestor) return true;
        }
        return false;
    }

    private static int countMatching(UIElement from, UIElement target) {
        int found = from == target ? 1 : 0;
        for (UIElement child : from.children()) found += countMatching(child, target);
        return found;
    }
}
