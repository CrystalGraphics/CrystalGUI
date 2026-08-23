package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.input.UIInputHandler;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Dragging a shared divider moves everything it separates — Windows' {@code JointResize}.
 *
 * <p>Microsoft has shipped this since Windows 10 build 10547, behind a setting (<i>"When I resize a
 * snapped window, simultaneously resize any adjacent snapped window"</i>) that Windows 11 22H2 removed
 * in favour of always-on. Windows 10 could pair two windows and no more; 11 adapts the whole layout,
 * which is what is tested here.</p>
 *
 * <h3>The maths is driven directly and the wiring is driven through a real drag</h3>
 *
 * <p>{@link Desktop#jointResize} takes the numbers a resize handle would have produced, so the matrix of
 * zones and edges is cheap to cover. That leaves exactly one thing it cannot see — whether a handle is
 * connected to it at all — so one test drags a real {@code __resizer-right__} and the rest do not have
 * to.</p>
 */
public class JointResizeTest extends UiTestBase {

    private UIWindow window;
    private Desktop desktop;

    @Before
    public void setUpDesktop() {
        CommandRegistry.global().resetForTesting();
        WindowCommands.resetForTesting();

        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        desktop = window.desktop();
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    private float areaWidth() {
        return desktop.windowLayer().getRuntimeCache().getWidth();
    }

    private float areaHeight() {
        return desktop.windowLayer().getRuntimeCache().getHeight();
    }

    private WindowFrame snapped(String title, SnapZones.Zone zone) {
        WindowFrame frame = window.openWindow(new WindowFrame(title));
        frame.resizeTo(300, 200).moveTo(60, 60);
        settle();
        desktop.snapFrameTo(frame, zone);
        settle();
        return frame;
    }

    private static float widthOf(WindowFrame frame) {
        return frame.getRuntimeCache().getWidth();
    }

    private static float heightOf(WindowFrame frame) {
        return frame.getRuntimeCache().getHeight();
    }

    // ── The pair ────────────────────────────────────────────────────────────────────────────────

    /** <b>Snapping two windows to facing halves tiles the work area exactly.</b> The premise. */
    @Test
    public void twoFacingHalvesTileTheWorkArea() {
        WindowFrame left = snapped("L", SnapZones.Zone.LEFT);
        WindowFrame right = snapped("R", SnapZones.Zone.RIGHT);

        assertEquals("the halves leave a gap or overlap",
                areaWidth(), widthOf(left) + widthOf(right), 1f);
        assertEquals("the right half does not start where the left ends",
                left.left() + widthOf(left), right.left(), 1f);
    }

    /**
     * <b>Dragging the shared edge gives one window the space the other gives up</b> — {@code n} and
     * {@code 1 − n}.
     */
    @Test
    public void draggingTheDividerResizesBoth() {
        WindowFrame left = snapped("L", SnapZones.Zone.LEFT);
        WindowFrame right = snapped("R", SnapZones.Zone.RIGHT);

        // The left window's RIGHT edge dragged to 200 — the edge facing the middle, so a divider.
        desktop.jointResize(left, 1, 0, 200f, areaHeight());
        settle();

        assertEquals("the dragged window is not the size asked for", 200f, widthOf(left), 1f);
        assertEquals("the neighbour did not follow", 200f, right.left(), 1f);
        assertEquals("the neighbour did not take the remainder",
                areaWidth() - 200f, widthOf(right), 1f);
        assertEquals("the pair stopped tiling", areaWidth(), widthOf(left) + widthOf(right), 1f);
    }

    /**
     * <b>...and an OUTER edge moves nothing else.</b>
     *
     * <p>The counter-assertion the one above needs, and a real distinction rather than a formality:
     * dragging the left edge of a left-snapped window resizes one window. Treating every edge as a
     * divider would make the far side of the desktop jump whenever somebody pulled a window off its own
     * border.</p>
     */
    @Test
    public void draggingAnOuterEdgeMovesNothingElse() {
        WindowFrame left = snapped("L", SnapZones.Zone.LEFT);
        WindowFrame right = snapped("R", SnapZones.Zone.RIGHT);
        float before = right.left();

        desktop.jointResize(left, -1, 0, 200f, areaHeight());
        settle();

        assertEquals("an outer edge dragged the neighbour with it", before, right.left(), 0.01f);
    }

    /** A window that is not snapped at all has no divider to move. */
    @Test
    public void anUnsnappedWindowJointResizesNothing() {
        WindowFrame left = snapped("L", SnapZones.Zone.LEFT);
        WindowFrame loose = window.openWindow(new WindowFrame("Loose"));
        loose.resizeTo(300, 200).moveTo(400, 100);
        settle();
        float before = widthOf(left);

        assertNull("a window placed by hand is not in the group", loose.snappedZone());
        desktop.jointResize(loose, 1, 0, 500f, areaHeight());
        settle();

        assertEquals("an untiled window re-tiled the group", before, widthOf(left), 0.01f);
    }

    // ── The grid ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The corner where four windows meet drags both dividers at once.</b>
     *
     * <p>The case Windows 11 added and Windows 10 could not do — and the one the whole design is shaped
     * around. Because the group stores a <em>cut per axis</em> rather than a rect per window, a corner
     * handle reporting both axes is already two divider moves, and the grid stays a grid with no
     * four-window special case anywhere.</p>
     */
    @Test
    public void theCentreCornerDragsBothDividers() {
        WindowFrame topLeft = snapped("TL", SnapZones.Zone.TOP_LEFT);
        WindowFrame topRight = snapped("TR", SnapZones.Zone.TOP_RIGHT);
        WindowFrame bottomLeft = snapped("BL", SnapZones.Zone.BOTTOM_LEFT);
        WindowFrame bottomRight = snapped("BR", SnapZones.Zone.BOTTOM_RIGHT);

        // The top-left cell's bottom-right corner: both of its edges face the middle.
        desktop.jointResize(topLeft, 1, 1, 200f, 150f);
        settle();

        assertEquals(200f, widthOf(topLeft), 1f);
        assertEquals(150f, heightOf(topLeft), 1f);

        assertEquals("the top-right window did not follow the vertical cut", 200f, topRight.left(), 1f);
        assertEquals("the bottom-left window did not follow the horizontal cut",
                150f, bottomLeft.top(), 1f);
        assertEquals("the diagonal window did not follow either cut", 200f, bottomRight.left(), 1f);
        assertEquals(150f, bottomRight.top(), 1f);
    }

    /**
     * <b>One grid: the vertical cut is shared by BOTH rows.</b>
     *
     * <p>Dragging it between the top pair moves the bottom pair too, which is what "the rest of the
     * windows will be adapted to maintain the design" means. A cut per row would be more general and is
     * not what a tiled desktop is — the moment the rows disagree, the corner where four windows meet
     * stops being one place.</p>
     */
    @Test
    public void theVerticalCutIsSharedByBothRows() {
        snapped("TL", SnapZones.Zone.TOP_LEFT);
        WindowFrame topRight = snapped("TR", SnapZones.Zone.TOP_RIGHT);
        WindowFrame bottomLeft = snapped("BL", SnapZones.Zone.BOTTOM_LEFT);

        // The top-RIGHT cell's LEFT edge — the same vertical divider, approached from the other side,
        // which is also the only LEADING-edge case here. UIResizer moves the origin BEFORE it calls the
        // hook (that is what pins the opposite edge), so a fixture calling jointResize directly has to do
        // the same or it hands the group a position from before the drag. In production `left()` is
        // already current, which is why this is the fixture's job and not a guard in jointResize.
        float wanted = areaWidth() - 250f;
        topRight.moveTo(250f, topRight.top());
        desktop.jointResize(topRight, -1, 0, wanted, areaHeight() / 2f);
        settle();

        assertEquals("the cut is not where the drag put it", 250f, topRight.left(), 1f);
        assertEquals("the other row did not follow the shared cut", 250f, widthOf(bottomLeft), 1f);
    }

    // ── Leaving the group ───────────────────────────────────────────────────────────────────────

    /**
     * <b>Moving a window takes it out of the tile; resizing one does not.</b>
     *
     * <p>The distinction the whole feature rests on. A window carried away from its cell is no longer in
     * the layout and must stop being a partner, or dragging a neighbour's divider would reach out and
     * re-tile a window sitting somewhere else. A resize is the opposite: the cell stays and its edge
     * moves, which is the entire gesture.</p>
     */
    @Test
    public void movingLeavesTheGroupAndResizingDoesNot() {
        WindowFrame left = snapped("L", SnapZones.Zone.LEFT);
        WindowFrame right = snapped("R", SnapZones.Zone.RIGHT);

        desktop.jointResize(left, 1, 0, 200f, areaHeight());
        settle();
        assertNotNull("a joint resize dropped the window out of its tile", left.snappedZone());
        assertNotNull(right.snappedZone());

        dragCaption(right, 40f);
        assertNull("dragging a window away left it in the tiled group", right.snappedZone());

        float parked = right.left();
        desktop.jointResize(left, 1, 0, 300f, areaHeight());
        settle();
        assertEquals("a window that had left the group was still re-tiled", parked, right.left(), 1f);
    }

    /** A maximised window covers everything, so it shares an edge with nothing. */
    @Test
    public void maximisingLeavesTheGroup() {
        WindowFrame left = snapped("L", SnapZones.Zone.LEFT);
        left.maximize();
        settle();

        assertNull("a maximised window stayed in the tiled group", left.snappedZone());
    }

    // ── The cut belongs to the group ────────────────────────────────────────────────────────────

    /**
     * <b>A window snapped into an occupied zone takes that zone's CURRENT size</b>, not half.
     *
     * <p>The layout is the truth: dropping a third window onto a half that has been dragged to 250px
     * gives it 250px. Tiling it against the centre instead would silently undo a layout somebody had
     * arranged, and would leave it overlapping the neighbour it is supposed to sit beside.</p>
     */
    @Test
    public void snappingIntoAnOccupiedZoneUsesTheGroupsCut() {
        WindowFrame left = snapped("L", SnapZones.Zone.LEFT);
        WindowFrame right = snapped("R", SnapZones.Zone.RIGHT);
        desktop.jointResize(left, 1, 0, 250f, areaHeight());
        settle();

        WindowFrame third = snapped("Third", SnapZones.Zone.LEFT);

        assertEquals("a later snap tiled against halves and undid the group's layout",
                250f, widthOf(third), 1f);
        assertEquals("...and no longer meets its neighbour", third.left() + widthOf(third),
                right.left(), 1f);
    }

    /**
     * <b>...but a FRESH group starts at halves.</b>
     *
     * <p>The cut belongs to the group and must not outlive one. Without this, closing a pair that had
     * been dragged to 3:1 and then snapping a single window left hands it that ratio, with nothing left
     * on screen to explain where the number came from.</p>
     */
    @Test
    public void aFreshGroupStartsAtHalves() {
        WindowFrame left = snapped("L", SnapZones.Zone.LEFT);
        snapped("R", SnapZones.Zone.RIGHT);
        desktop.jointResize(left, 1, 0, 250f, areaHeight());
        settle();

        for (WindowFrame frame : new java.util.ArrayList<>(desktop.registry().windows())) {
            frame.destroy();
        }
        settle();

        WindowFrame lone = snapped("Lone", SnapZones.Zone.LEFT);
        assertEquals("a new group inherited the old one's divider",
                Math.floor(areaWidth() / 2f), widthOf(lone), 1f);
    }

    /**
     * <b>Neither cell can be driven to nothing.</b>
     *
     * <p>Windows stops at the smaller window's own minimum size, which is a per-window negotiation.
     * {@link SnapZones#MIN_SPLIT} is the cruder rule and the honest one to start from: it guarantees the
     * failure that matters — a window resized out of existence with no way to get it back — cannot
     * happen, in one number rather than a size argument between two windows.</p>
     */
    @Test
    public void aDividerCannotBeDraggedPastTheMinimumSplit() {
        WindowFrame left = snapped("L", SnapZones.Zone.LEFT);
        WindowFrame right = snapped("R", SnapZones.Zone.RIGHT);

        desktop.jointResize(left, 1, 0, areaWidth() + 400f, areaHeight());
        settle();

        assertTrue("the neighbour was squeezed out of existence", widthOf(right) > 0f);
        assertEquals("the divider went past its clamp",
                Math.floor(areaWidth() * SnapZones.MAX_SPLIT), widthOf(left), 1f);
    }

    // ── The wiring ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A real resize handle reaches the group.</b>
     *
     * <p>The one thing driving {@link Desktop#jointResize} directly cannot see: every test above would
     * pass with nothing at all connected to {@code UIResizer}. It needs the handle because that is the
     * seam — {@code onUserResize} was added to {@code UIElement} for this, and a hook nobody calls looks
     * exactly like a hook that works.</p>
     */
    @Test
    public void draggingTheRealHandleResizesTheNeighbour() {
        WindowFrame left = snapped("L", SnapZones.Zone.LEFT);
        WindowFrame right = snapped("R", SnapZones.Zone.RIGHT);
        float before = right.left();

        UIElement grabber = left.querySelector(".__resizer-right__");
        assertNotNull("the window has no right-edge resize handle", grabber);

        UIInputHandler input = window.getInputHandler();
        input.beginFrame();
        input.endFrame();

        var box = grabber.getRuntimeCache();
        float x = box.getX() + box.getWidth() / 2f;
        float y = box.getY() + box.getHeight() / 2f;
        send(x, y, CgMouseCodes.LEFT_BUTTON, true);
        send(x - 60f, y, -1, false);

        assertTrue("the dragged window did not shrink", widthOf(left) < before);
        assertEquals("the neighbour did not follow a real handle drag",
                left.left() + widthOf(left), right.left(), 1f);
        assertEquals("the pair stopped tiling", areaWidth(), widthOf(left) + widthOf(right), 1f);
    }

    private long clock = 5_000L;

    private void send(float x, float y, int button, boolean down) {
        clock += UIInputHandler.multiClickInterval + 20L;
        UIInputHandler input = window.getInputHandler();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, button, down, 0f, clock));
        input.beginFrame();
        input.endFrame();
        settle();
    }

    /** Drags {@code frame}'s caption {@code by} logical pixels to the right, and releases. */
    private void dragCaption(WindowFrame frame, float by) {
        UIInputHandler input = window.getInputHandler();
        input.beginFrame();
        input.endFrame();

        var bar = frame.titleBar().getRuntimeCache();
        float x = bar.getX() + bar.getWidth() / 2f;
        float y = bar.getY() + bar.getHeight() / 2f;
        send(x, y, CgMouseCodes.LEFT_BUTTON, true);
        send(x + by, y, -1, false);
        send(x + by, y, CgMouseCodes.LEFT_BUTTON, false);
    }
}
