package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Fullscreen, Alt-drag and drag-to-edge snap — CrystalOS <b>W13b</b>.
 *
 * <p>The snap arithmetic is pinned separately in {@link SnapZonesTest}, which needs no desktop at all.
 * What is here is everything that needs one, and the split is worth stating because the first version
 * of this file had only the arithmetic covered and shipped three defects underneath it: the conversion
 * feeding {@code SnapZones} was wrong, the preview drew behind the windows it described, and the window
 * did not animate into its half. <b>Every one of them lives between the pure function and the screen</b>,
 * so the tests that catch them drive a real drag through {@code consumeMouseEvent} rather than calling
 * the geometry.</p>
 *
 * <p>Two of them also need animations <em>on</em>, which {@code UiTestBase} turns off for the whole
 * suite — a timeline test that forgets to re-enable them reads the settled value and passes against a
 * build that animates nothing.</p>
 */
public class WindowGesturesTest extends UiTestBase {

    private UIWindow window;
    private WindowFrame frame;

    @Before
    public void setUpDesktop() {
        CommandRegistry.global().resetForTesting();
        WindowCommands.resetForTesting();

        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        frame = window.openWindow(new WindowFrame("Editor"));
        frame.resizeTo(300, 200).moveTo(40, 40);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    private boolean barVisible() {
        return window.desktop().taskbar().getRuntimeCache().getHeight() > 0f;
    }

    // ── Fullscreen ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Fullscreen is maximise plus a hidden strip — and it needs no third geometry.</b>
     *
     * <p>A frame is placed against the window layer and the layer's box <em>is</em> the work area, so
     * hiding the taskbar re-flows the layer to full height and a maximised window follows it. That is
     * Windows' model exactly: maximise respects the taskbar, fullscreen covers it.</p>
     */
    @Test
    public void fullscreenMaximisesAndHidesTheStrip() {
        assertTrue("the fixture started with no taskbar", barVisible());

        frame.enterFullscreen();
        settle();

        assertTrue(frame.isFullscreen());
        assertTrue("fullscreen did not maximise the window", frame.isMaximized());
        assertFalse("fullscreen left the taskbar on screen", barVisible());
    }

    /**
     * <b>Leaving fullscreen returns to the state it was entered from — restored stays restored.</b>
     *
     * <p>A browser does exactly this, and getting it wrong only shows up the second time somebody uses
     * it: F11 from a restored window that came back maximised would have quietly resized their
     * window.</p>
     */
    @Test
    public void leavingFullscreenFromARestoredWindowRestoresIt() {
        assertFalse(frame.isMaximized());

        frame.toggleFullscreen();
        settle();
        frame.toggleFullscreen();
        settle();

        assertFalse("a restored window came back maximised", frame.isMaximized());
        assertTrue("the taskbar did not come back", barVisible());
    }

    /** <b>...and maximised stays maximised.</b> The other half, which a single test cannot cover. */
    @Test
    public void leavingFullscreenFromAMaximisedWindowKeepsItMaximised() {
        frame.maximize();
        settle();

        frame.toggleFullscreen();
        settle();
        frame.toggleFullscreen();
        settle();

        assertTrue("a maximised window came back restored", frame.isMaximized());
        assertTrue("the taskbar did not come back", barVisible());
    }

    /**
     * <b>The strip stays hidden while ANY window is fullscreen.</b>
     *
     * <p>Asked of the whole set rather than tracked as one window: two can be fullscreen at once, and a
     * field holding "the fullscreen window" would need every exit to know whether it was the one being
     * remembered. The registry can simply be asked.</p>
     */
    @Test
    public void theStripStaysHiddenWhileASecondWindowIsStillFullscreen() {
        WindowFrame other = window.openWindow(new WindowFrame("Other"));
        other.resizeTo(300, 200).moveTo(360, 40);
        settle();

        frame.enterFullscreen();
        other.enterFullscreen();
        settle();
        frame.exitFullscreen();
        settle();

        assertFalse("the strip came back while another window was still fullscreen", barVisible());

        other.exitFullscreen();
        settle();
        assertTrue("the strip never came back", barVisible());
    }

    /** A tool window is offered neither Maximize nor Full Screen, for the same reason. */
    @Test
    public void aToolWindowIsNotOfferedFullscreen() {
        frame.setToolWindow(true);
        settle();

        Command fullscreen = CommandRegistry.global().get(WindowCommands.FULLSCREEN);
        assertNotNull(fullscreen);
        assertFalse("a tool window was offered Full Screen",
                fullscreen.isEnabled(CommandContext.of(frame)));
    }

    /** F11 is the chord, and unlike Alt+Space it is not the host's. */
    @Test
    public void fullscreenIsOnF11() {
        Command fullscreen = CommandRegistry.global().get(WindowCommands.FULLSCREEN);
        assertNotNull(fullscreen);
        assertTrue("Full Screen lost its chord", fullscreen.bindings().contains("F11"));
    }

    // ── Alt-drag ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Holding the move modifier and pressing the window's CONTENT starts a move.</b>
     *
     * <p>The gesture shipped doing nothing, and the reason is worth keeping: {@code beginMove} carried a
     * guard refusing any press that was not on the title bar. It was right where it was written — a
     * synthesized Space/Enter press carries the cursor's position, which may be nowhere near the bar, and
     * honouring one teleports the window — and it silently disabled Alt-drag the moment that arrived,
     * because Alt-drag presses the content <em>by definition</em>. Nothing failed. The guard now lives on
     * the caption listener, which is the path it is a statement about.</p>
     *
     * <p>Driven through {@code consumeMouseEvent} at a point inside the content, with the modifier
     * reported by a stub: the listener is in the CAPTURE phase and reads the live modifier state, and a
     * fixture that dispatched straight at an element would skip both.</p>
     */
    @Test
    public void altDraggingTheContentStartsAMove() {
        UIElement inside = new UIElement().layout(l -> l.width(120).height(60));
        frame.content().addChild(inside);
        settle();

        withModifier(CgModifiers.ALT, () -> pressAt(inside));

        assertTrue("Alt-dragging a window's content started no move",
                window.getInputHandler().getDragController().isDragging());
    }

    /**
     * <b>The modifier is a setting, and changing it changes the gesture.</b>
     *
     * <p>The plan asks for this chord to be keymap-resolved and it cannot be — a {@code KeyStroke} is a
     * key plus modifiers, so there is no way to spell a modifier-only binding. {@code moveModifier} is
     * the substance of that requirement instead: one place, changeable at runtime. <b>That claim needs a
     * test or it is only a comment</b> — a setter nothing exercises is indistinguishable from a
     * hardcoded constant with a public mutator in front of it.</p>
     */
    @Test
    public void theMoveModifierIsRebindable() {
        UIElement inside = new UIElement().layout(l -> l.width(120).height(60));
        frame.content().addChild(inside);
        settle();
        window.desktop().setMoveModifier(CgModifiers.CTRL);

        withModifier(CgModifiers.ALT, () -> pressAt(inside));
        assertFalse("the old modifier still dragged after it was changed",
                window.getInputHandler().getDragController().isDragging());

        withModifier(CgModifiers.CTRL, () -> pressAt(inside));
        assertTrue("the new modifier does not drag", window.getInputHandler().getDragController().isDragging());
    }

    /**
     * <b>...and without the modifier the same press does nothing to the window.</b>
     *
     * <p>The counter-assertion that gives the one above meaning: a listener that ignored the modifier
     * would make every press anywhere in any window start a drag, which is a far worse bug than the
     * gesture not working.</p>
     */
    @Test
    public void pressingTheContentWithoutTheModifierStartsNoMove() {
        UIElement inside = new UIElement().layout(l -> l.width(120).height(60));
        frame.content().addChild(inside);
        settle();

        pressAt(inside);

        assertFalse("a plain press inside a window started a window move",
                window.getInputHandler().getDragController().isDragging());
    }

    /**
     * Runs {@code body} with {@code mask} reported as held.
     *
     * <p>Through the test platform's own input slot rather than a whole replacement service: the modifier
     * is <b>polled</b>, not carried on the event — which is what lets the gesture survive a modifier
     * pressed after the mouse went down, and what makes it untestable without a stub.</p>
     */
    private void withModifier(int mask, Runnable body) {
        TestPlatformService.get().input(new CgInputService() {
            @Override public int getCurrentModifiers() { return mask; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public int translateMouseCodes(int platformCode) { return platformCode; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
            @Override public String getClipboard() { return ""; }
            @Override public void setClipboard(String text) { }
        });
        try {
            body.run();
        } finally {
            TestPlatformService.get().input(TestPlatformService.STUB_INPUT);
        }
    }

    private void pressAt(UIElement target) {
        var box = target.getRuntimeCache();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round((box.getX() + box.getWidth() / 2f) * 2f),
                Math.round((box.getY() + box.getHeight() / 2f) * 2f),
                0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 0L));
        settle();
    }

    // ── Snap ────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The snap preview takes no box at all until a snap is being previewed.</b>
     *
     * <p>A full-size element over the work area is this codebase's most-repeated failure — it swallows
     * every click that misses a window and nothing on screen says why. What makes one safe is that it is
     * {@code display: none} for every frame it is not drawn, which is also what keeps it out of the
     * layer-FBO path.</p>
     */
    @Test
    public void theSnapPreviewIsAbsentUntilItIsNeeded() {
        // UiTestBase already runs with animations OFF, which is what this test wants: the preview now
        // morphs into and out of place on a nanoTime-driven timeline that a settle loop cannot step, and
        // this test is about presence rather than motion.
        assertEquals("the snap preview is taking up space before any drag", 0f,
                previewBox().getWidth(), 0.01f);

        window.desktop().showSnapPreview(SnapZones.Zone.LEFT, frame);
        settle();
        assertTrue("the preview did not appear", previewBox().getWidth() > 0f);

        window.desktop().hideSnapPreview();
        settle();
        assertEquals("the preview stayed on screen", 0f, previewBox().getWidth(), 0.01f);
    }

    /**
     * <b>It ANIMATES into place rather than appearing there — and it starts from the window.</b>
     *
     * <p>The only assertion that separates "animating" from "applied instantly" is whether the first
     * frame shows the <em>start</em> value: a timeline that jumped to its destination would satisfy any
     * test written against the end state, which is what every one of the tests around this does.
     * {@code WindowGeometryAnimation} writes its start rect in its constructor for exactly that reason —
     * a frame's gap between "asked for" and "showing its first value" is a visible flash of the end
     * state at the beginning of every gesture.</p>
     *
     * <p>Intermediate frames stay untestable, since the driver advances on {@code System.nanoTime()}.
     * "Did it start where it should" is reachable, and it is the half that breaks.</p>
     */
    @Test
    public void theSnapPreviewMorphsOutOfTheWindow() {
        frame.resizeTo(300, 200).moveTo(120, 90);
        settle();
        UIElement.RuntimeCache area = window.desktop().windowLayer().getRuntimeCache();

        // ANIMATIONS ON, and it has to be turned on rather than off: UiTestBase disables them for every
        // test in the suite, so a test asserting on a timeline that forgot this reads the settled value
        // and passes against a build that animates nothing at all.
        Desktop.setAnimationsEnabled(true);
        try {
            window.desktop().showSnapPreview(SnapZones.Zone.LEFT, frame);
            settle();

            // NEAR THE START, not exactly at it. Three settle passes take a couple of milliseconds of a
            // 150ms timeline, so the rect has already eased a fraction of the way — asserting equality
            // with the start value fails on a build that is working. What separates a timeline from an
            // instant apply is which END it is near, so that is what is asked.
            assertNearer("width", previewBox().getWidth(), 300f, Math.floor(area.getWidth() / 2f));
            assertNearer("height", previewBox().getHeight(), 200f, area.getHeight());
            assertNearer("left", previewBox().getX(), area.getX() + 120f, area.getX());
        } finally {
            Desktop.setAnimationsEnabled(false);
            window.desktop().hideSnapPreviewNow();
        }

        // ...and the counter-assertion: with animations off it IS the half at once, so the assertions
        // above are reading a timeline rather than an unrelated failure to size the preview at all.
        window.desktop().showSnapPreview(SnapZones.Zone.LEFT, frame);
        settle();
        assertEquals("animations off did not settle the preview immediately",
                (float) Math.floor(area.getWidth() / 2f), previewBox().getWidth(), 1f);
    }

    /** <b>A LEFT snap covers the left half of the work area, and stops at the middle.</b> */
    @Test
    public void theSnapPreviewCoversTheHalfItNames() {
        window.desktop().showSnapPreview(SnapZones.Zone.LEFT, frame);
        settle();

        var area = window.desktop().windowLayer().getRuntimeCache();
        var preview = previewBox();
        assertEquals("a left snap is not half the work area",
                Math.floor(area.getWidth() / 2f), preview.getWidth(), 1f);
        assertEquals("a left snap is not full height", area.getHeight(), preview.getHeight(), 1f);
    }

    /** <b>...and a quarter covers half of each axis</b>, which nothing else here asserts about a rect. */
    @Test
    public void theSnapPreviewCoversTheQuarterItNames() {
        window.desktop().showSnapPreview(SnapZones.Zone.BOTTOM_RIGHT, frame);
        settle();

        var area = window.desktop().windowLayer().getRuntimeCache();
        var preview = previewBox();
        assertEquals("a corner preview is not half wide",
                Math.floor(area.getWidth() / 2f), preview.getWidth(), 1f);
        assertEquals("a corner preview is not half tall",
                Math.floor(area.getHeight() / 2f), preview.getHeight(), 1f);
        assertTrue("a bottom-right preview is not in the bottom-right",
                preview.getX() > area.getX() && preview.getY() > area.getY());
    }

    // ── Snap, driven through a real drag ────────────────────────────────────────────────────────

    /**
     * <b>A drag that is nowhere near an edge arms nothing.</b>
     *
     * <p>This is the shape of the defect that shipped, and {@link SnapZonesTest} could not see it: that
     * tests the arithmetic, and the fault was in the <em>conversion</em>. The caller added the title
     * bar's own origin to a pointer that was already in absolute layout coordinates, so both terms grew
     * together as the window was dragged — the reported position ran at roughly double speed, and a
     * window in the middle of the desktop armed RIGHT after about twenty pixels of travel.</p>
     *
     * <p>Twenty is not an arbitrary nudge. With the window opened at 300 and grabbed at its caption's
     * centre, the doubled sum crosses the old right-hand band at nineteen — so this fails against the
     * old code and passes against any correct one.</p>
     */
    @Test
    public void aDragToTheMiddleOfTheDesktopArmsNoSnap() {
        // OPENED WELL TO THE RIGHT, and that is what makes this test able to fail. The double-count was
        // the title bar's own origin, so the error it introduces is the size of however far along the
        // desktop the window sits — a window near the left arms nothing wrong, and one near the right
        // arms RIGHT from the middle of the screen. Starting at the origin would pass either way.
        frame.resizeTo(300, 200).moveTo(650, 200);
        settle();
        UIElement.RuntimeCache area = window.desktop().windowLayer().getRuntimeCache();
        float midWidth = area.getWidth() / 2f;
        float midHeight = area.getHeight() / 2f;

        float[] grab = captionCentre();
        pressPoint(grab[0], grab[1]);
        dragToArea(midWidth, midHeight);

        assertEquals("the middle of the desktop armed a snap",
                0f, previewBox().getWidth(), 0.01f);

        releaseAt(area.getX() + midWidth, area.getY() + midHeight);
        assertEquals("and it resized the window on release",
                300f, frame.getRuntimeCache().getWidth(), 1f);
    }

    /** <b>Contacting the left edge tiles to the left half</b>, through the same resize/move a caller uses. */
    @Test
    public void draggingToTheLeftEdgeSnapsToTheLeftHalf() {
        frame.resizeTo(300, 200).moveTo(300, 200);
        settle();
        UIElement.RuntimeCache area = window.desktop().windowLayer().getRuntimeCache();
        float midHeight = area.getHeight() / 2f;

        float[] grab = captionCentre();
        pressPoint(grab[0], grab[1]);
        dragToArea(0f, midHeight);

        assertTrue("no snap preview appeared at the edge", previewBox().getWidth() > 0f);

        releaseAt(area.getX(), area.getY() + midHeight);

        assertEquals("the window is not at the left edge", 0f, frame.left(), 1f);
        assertEquals("the window is not half the work area",
                (float) Math.floor(area.getWidth() / 2f), frame.getRuntimeCache().getWidth(), 1f);
        assertEquals("the window is not full height",
                area.getHeight(), frame.getRuntimeCache().getHeight(), 1f);
    }

    /**
     * <b>...and a corner tiles to the quarter</b> — the half of W13b that was missing entirely.
     *
     * <p>The corner is the outer {@link SnapZones#CORNER_RATIO} of the side edge, which is KWin's
     * {@code ElectricBorderCornerRatio}. It is also the case that used to resolve to maximise, so a
     * build with no quadrants passes every other test here and fails this one.</p>
     */
    @Test
    public void draggingToACornerSnapsToTheQuarter() {
        frame.resizeTo(300, 200).moveTo(300, 200);
        settle();
        UIElement.RuntimeCache area = window.desktop().windowLayer().getRuntimeCache();

        float[] grab = captionCentre();
        pressPoint(grab[0], grab[1]);
        dragToArea(0f, 0f);
        releaseAt(area.getX(), area.getY());

        assertFalse("a corner maximised the window instead of quartering it", frame.isMaximized());
        assertEquals("not at the left", 0f, frame.left(), 1f);
        assertEquals("not at the top", 0f, frame.top(), 1f);
        assertEquals("not half wide",
                (float) Math.floor(area.getWidth() / 2f), frame.getRuntimeCache().getWidth(), 1f);
        assertEquals("not half tall",
                (float) Math.floor(area.getHeight() / 2f), frame.getRuntimeCache().getHeight(), 1f);
    }

    /**
     * Asserts {@code actual} is still within a quarter of the way from {@code start} toward {@code end}.
     *
     * <p>A quarter is the widest band that cannot be satisfied by a jump to the destination and is wide
     * enough that a slow machine's settle loop does not fail it — the animation is 150ms and a settle is
     * measured in single-digit milliseconds, so the real value sits within a percent or two of the
     * start.</p>
     */
    private static void assertNearer(String what, double actual, double start, double end) {
        double travelled = Math.abs(actual - start);
        double whole = Math.abs(end - start);
        assertTrue("the preview's " + what + " began at " + actual + ", which is " + travelled
                        + " of the " + whole + " it had to travel — it jumped rather than animating",
                whole <= 0.001 || travelled < whole * 0.25);
    }

    /**
     * <b>The WINDOW animates into its half too — the preview is not the only thing that moves.</b>
     *
     * <p>It did not, and the gap is the kind only a user notices: {@code commitSnap} finished with
     * {@code resizeTo} + {@code moveTo}, both of which are instant, while the MAXIMIZE branch went
     * through {@code maximize()} and had animated since W9. So dragging to the top eased and dragging to
     * a side teleported — one gesture behaving two ways, and it became unmissable once the preview
     * itself started morphing into place and then handed over to a window that simply appeared.</p>
     *
     * <p>Asserted the same way the preview's timeline is, and for the same reason: three settle passes
     * are a couple of milliseconds of a 250ms animation, so what separates "animating" from "applied"
     * is which END the rect is near. Its counter-assertion is
     * {@link #draggingToTheLeftEdgeSnapsToTheLeftHalf}, which runs with animations off and checks the
     * window really does arrive.</p>
     */
    @Test
    public void aSnappedWindowAnimatesIntoItsHalf() {
        frame.resizeTo(300, 200).moveTo(300, 200);
        settle();
        UIElement.RuntimeCache area = window.desktop().windowLayer().getRuntimeCache();
        float midHeight = area.getHeight() / 2f;

        Desktop.setAnimationsEnabled(true);
        try {
            float[] grab = captionCentre();
            pressPoint(grab[0], grab[1]);
            dragToArea(0f, midHeight);
            float draggedX = frame.getRuntimeCache().getX();
            releaseAt(area.getX(), area.getY() + midHeight);

            // ON THE RUNTIME CACHE, never frame.left(): the animation writes the layout directly and
            // never goes through applyPosition, so `placedLeft` holds the drag's last value for the whole
            // timeline and then steps to the destination. It would read as a jump on a build that eases.
            assertNearer("width", frame.getRuntimeCache().getWidth(),
                    300f, Math.floor(area.getWidth() / 2f));
            assertNearer("height", frame.getRuntimeCache().getHeight(), 200f, area.getHeight());
            assertNearer("left", frame.getRuntimeCache().getX(), draggedX, area.getX());
        } finally {
            Desktop.setAnimationsEnabled(false);
        }
    }

    /**
     * <b>A zone already holding a window still accepts another.</b>
     *
     * <p>Tiling is not a claim: Windows lets a second window take a half that is already occupied, and
     * the first simply ends up underneath it. Anything else would make the gesture depend on what is
     * already on screen, which is not something a hand reaching for an edge is thinking about.</p>
     */
    @Test
    public void aZoneAlreadyOccupiedStillAcceptsAnotherWindow() {
        frame.resizeTo(300, 200).moveTo(300, 200);
        settle();
        UIElement.RuntimeCache area = window.desktop().windowLayer().getRuntimeCache();
        float half = (float) Math.floor(area.getWidth() / 2f);
        float midHeight = area.getHeight() / 2f;

        snapToLeftEdge(frame, midHeight);
        assertEquals("the first window did not snap", half,
                frame.getRuntimeCache().getWidth(), 1f);

        WindowFrame other = window.openWindow(new WindowFrame("Other"));
        other.resizeTo(300, 200).moveTo(360, 40);
        settle();

        snapToLeftEdge(other, midHeight);

        assertEquals("a second window would not take an occupied half", 0f, other.left(), 1f);
        assertEquals(half, other.getRuntimeCache().getWidth(), 1f);
        assertEquals("...and the first window was disturbed", half,
                frame.getRuntimeCache().getWidth(), 1f);
    }

    /**
     * <b>The preview draws above every other window — otherwise an occupied zone gives no feedback.</b>
     *
     * <p>The snap itself was never broken, which is what made this so hard to describe: a second window
     * takes an occupied half perfectly well (the test above), and what was missing was the preview.
     * {@code Desktop.raise} hands out a {@code z-index} from a counter that only goes up, and the preview
     * was hosted with none at all — so it sat at zero, behind every window that had ever been clicked.
     * Larger than the windows it hid under, it read as working; put a window in the very half being
     * previewed and it is entirely behind it.</p>
     *
     * <p>And <b>below the dragged window</b>, which is the half a "just put it on top" fix gets wrong:
     * the wash would be drawn over the window in your hand, greying out the thing being acted on.</p>
     */
    @Test
    public void theSnapPreviewDrawsAboveTheWindowsItCovers() {
        UIElement.RuntimeCache area = window.desktop().windowLayer().getRuntimeCache();
        WindowFrame other = window.openWindow(new WindowFrame("Other"));
        other.resizeTo(300, 200).moveTo(360, 40);
        settle();
        window.desktop().activate(frame);
        settle();

        window.desktop().showSnapPreview(SnapZones.Zone.LEFT, frame);
        settle();

        UIElement preview = window.desktop().windowLayer()
                .querySelector("." + Desktop.SNAP_PREVIEW_CLASS);
        assertNotNull("the preview was never built", preview);
        int previewZ = preview.getStyle().getGeneralGroup().zIndex();

        assertTrue("the preview draws under a window it is describing — it would be invisible behind"
                        + " anything already filling that zone",
                previewZ > other.getStyle().getGeneralGroup().zIndex());
        assertTrue("the preview draws OVER the window being dragged, greying out what is in your hand",
                previewZ < frame.getStyle().getGeneralGroup().zIndex());
    }

    /** Drags {@code target} by its caption into the work area's left edge and releases. */
    private void snapToLeftEdge(WindowFrame target, float atHeight) {
        UIElement.RuntimeCache area = window.desktop().windowLayer().getRuntimeCache();
        float[] grab = captionCentreOf(target);
        pressPoint(grab[0], grab[1]);
        dragToArea(0f, atHeight);
        releaseAt(area.getX(), area.getY() + atHeight);
    }

    /** The centre of the frame's caption, in logical coordinates. */
    private float[] captionCentre() {
        return captionCentreOf(frame);
    }

    private float[] captionCentreOf(WindowFrame target) {
        UIElement.RuntimeCache bar = target.titleBar().getRuntimeCache();
        return new float[] {bar.getX() + bar.getWidth() / 2f, bar.getY() + bar.getHeight() / 2f};
    }

    private long clock = 5_000L;

    private void pressPoint(float x, float y) {
        // A FRAME MUST HAVE BEEN PRESENTED FIRST. consumeMouseEvent drops everything until
        // `firstFrameOver`, deliberately — hover has nothing to be relative to before a frame exists —
        // so without this the press is silently swallowed and the drag never begins. It presents as the
        // window simply not moving, which reads as the gesture being broken rather than the fixture.
        UIInputHandler handler = window.getInputHandler();
        handler.beginFrame();
        handler.endFrame();
        // Past the multi-click interval, or a second press in one test reads as a double-click and
        // toggles maximise instead of starting a move.
        clock += UIInputHandler.multiClickInterval + 50L;
        sendMouse(x, y, CgMouseCodes.LEFT_BUTTON, true);
    }

    /** A pointer move with no button transition — {@code button = -1} is what says "no button news". */
    private void dragTo(float x, float y) {
        clock += 10L;
        sendMouse(x, y, -1, false);
    }

    /**
     * Moves the pointer to a point given in the <b>work area's</b> own coordinates.
     *
     * <p>Which is not the pointer's, and the difference is the whole reason this helper exists: in this
     * fixture the root is styled larger than the viewport, so the window layer's origin is a long way
     * negative and logical {@code (0, 0)} is nowhere near its top-left corner. A test that dragged to
     * literal zero moved the window a plausible distance to a place that was not an edge, and read as
     * the snap being broken.</p>
     */
    private void dragToArea(float areaX, float areaY) {
        UIElement.RuntimeCache area = window.desktop().windowLayer().getRuntimeCache();
        dragTo(area.getX() + areaX, area.getY() + areaY);
    }

    private void releaseAt(float x, float y) {
        clock += 10L;
        sendMouse(x, y, CgMouseCodes.LEFT_BUTTON, false);
    }

    private void sendMouse(float x, float y, int button, boolean down) {
        UIInputHandler handler = window.getInputHandler();
        handler.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, button, down, 0f, clock));
        handler.beginFrame();
        handler.endFrame();
        settle();
    }

    private UIElement.RuntimeCache previewBox() {
        UIElement found = window.desktop().windowLayer()
                .querySelector("." + Desktop.SNAP_PREVIEW_CLASS);
        // Never built is the same observable as never shown, which is what the assertion means.
        return found == null ? new UIElement().getRuntimeCache() : found.getRuntimeCache();
    }
}
