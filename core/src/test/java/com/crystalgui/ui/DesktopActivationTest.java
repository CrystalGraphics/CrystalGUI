package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.desktop.Desktop;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.input.UIInputHandler;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * CrystalOS W2 — stacking and activation ({@code plan_windowing.md}).
 *
 * <p>Two of these are guards rather than checks. <b>Raise must never be a reparent</b>: moving a frame
 * in the child list runs register/unregister over its whole subtree on every click, and the shape of
 * that bug is a window that works and slowly loses its state. And <b>activation must restore focus
 * without stealing it</b>, which is the {@code ListView} rule arriving in a second widget.</p>
 *
 * <p>The press tests go through the <b>real mouse path</b>. {@code sendInputEvent} skips
 * {@code emitMouseDown} entirely — no focus resolution, no blur-before-dispatch — and has now shipped
 * two bugs behind green tests, most recently a menu bar that resolved every command against the wrong
 * element.</p>
 */
public class DesktopActivationTest extends UiTestBase {

    private UIWindow window;
    private UIElement root;
    private Desktop desktop;
    private UIInputHandler input;

    private void build() {
        root = new UIElement().layout(l -> l.width(400).height(300));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        desktop = window.desktop();
        settle();
        input = window.getInputHandler();
        input.beginFrame();
        input.endFrame(); // firstFrameOver — input is dropped before a frame exists
    }

    private void settle() {
        for (int pass = 0; pass < 2; pass++) {
            window.getStyleEngine().calculateStyle(0.016f);
            window.calculateLayout();
        }
    }

    /** A window with one focusable control in it, so focus has somewhere to be. */
    private WindowFrame open(String title, float left, float top) {
        WindowFrame frame = window.openWindow(new WindowFrame(title));
        frame.resizeTo(160, 120).moveTo(left, top);
        frame.content().addChild(new Button(title + " button"));
        settle();
        return frame;
    }

    private Button buttonIn(WindowFrame frame) {
        return (Button) frame.content().getChildren().get(0);
    }

    private void press(float x, float y) {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
    }

    /**
     * Presses an element's CENTRE, never a corner.
     *
     * <p>A window's edges belong to its eight {@code UIResizer} handles, which are absolutely
     * positioned over them and hit-test first — so a press four pixels inside a frame's left edge hits
     * the resize grabber rather than whatever the layout put there. That is correct behaviour and a
     * trap for a fixture: the first version of this test pressed a corner and read the frame taking
     * focus as activation stealing it.</p>
     */
    private void pressCentreOf(UIElement element) {
        press(element.getRuntimeCache().getX() + element.getRuntimeCache().getWidth() / 2f,
                element.getRuntimeCache().getY() + element.getRuntimeCache().getHeight() / 2f);
    }

    /** A point inside a frame's title bar — the chrome, not its content. */
    private void pressTitleBarOf(WindowFrame frame) {
        pressCentreOf(frame.titleBar());
    }

    private int zOf(WindowFrame frame) {
        return frame.getStyle().getGeneralGroup().zIndex();
    }

    // ── Raise ───────────────────────────────────────────────────────────────

    /**
     * <b>The rule the whole design rests on.</b> A raise assigns a {@code z-index}; it must not touch
     * the child list, because {@code removeChild}/{@code addChild} is register/unregister over the
     * entire frame subtree — session state, modal and popover stacks, every Taffy node — and it would
     * run on every click, which is exactly when a widget must not rebuild what is being clicked.
     */
    @Test
    public void raiseAssignsZAndNeverReparents() {
        build();
        WindowFrame first = open("One", 20, 20);
        WindowFrame second = open("Two", 60, 60);

        int firstIndex = first.getSiblingIndex();
        int secondIndex = second.getSiblingIndex();
        assertTrue("the later window starts in front", zOf(second) > zOf(first));

        desktop.raise(first);
        settle();

        assertTrue("raise must change the depth", zOf(first) > zOf(second));
        assertEquals("...and must NOT move it in the child list", firstIndex, first.getSiblingIndex());
        assertEquals(secondIndex, second.getSiblingIndex());
    }

    /**
     * A press in the lower window brings it forward <b>and still lands</b> — Windows' model rather than
     * macOS's click-through carve-out, which is what makes a window usable on its first click.
     */
    @Test
    public void aPressRaisesAndActivatesAndStillReachesItsTarget() {
        build();
        WindowFrame first = open("One", 20, 20);
        WindowFrame second = open("Two", 200, 20);
        assertSame("the newest window opens active", second, desktop.activeWindow());

        Button target = buttonIn(first);
        pressCentreOf(target);
        settle();

        assertSame("the press activates its window", first, desktop.activeWindow());
        assertTrue("...and raises it", zOf(first) > zOf(second));
        assertSame("...and the press still reached what it hit", target, input.getFocusedElement());
    }

    /** Exactly one window carries the active class, and the class is what a theme reads. */
    @Test
    public void onlyOneWindowIsActiveAtATime() {
        build();
        WindowFrame first = open("One", 20, 20);
        WindowFrame second = open("Two", 200, 20);

        assertTrue(second.isActive());
        assertFalse(first.isActive());

        desktop.activate(first);
        assertTrue(first.isActive());
        assertFalse(second.isActive());
    }

    // ── Focus ───────────────────────────────────────────────────────────────

    /**
     * <b>Focus memory.</b> Win32 records the focus owner per window and restores it on activation;
     * without it, coming back to a window puts the caret wherever the focus delegate happens to be
     * rather than where the user left it.
     */
    @Test
    public void activationRestoresTheFocusThisWindowLastHad() {
        build();
        WindowFrame first = open("One", 20, 20);
        WindowFrame second = open("Two", 200, 20);

        Button remembered = buttonIn(first);
        input.requestFocus(remembered);
        assertSame(remembered, input.getFocusedElement());

        desktop.activate(second);
        assertFalse("focus moved to the other window", remembered == input.getFocusedElement());

        desktop.activate(first);
        assertSame("and came back to where it was", remembered, input.getFocusedElement());
    }

    /**
     * <b>Restore, never steal</b> — the {@code ListView} rule. Activation runs on every press, and
     * {@code emitMouseDown} has already focused whatever was pressed by the time it does: a version
     * that focused the remembered element unconditionally would pull focus off the control the user
     * just clicked, one frame after clicking it.
     */
    @Test
    public void activatingAWindowThatAlreadyHasFocusMovesNothing() {
        build();
        WindowFrame frame = open("One", 20, 20);
        Button target = buttonIn(frame);
        input.requestFocus(target);

        desktop.activate(frame);
        assertSame("focus was never lost, so there is nothing to restore", target, input.getFocusedElement());
    }

    /**
     * <b>Dragging a window must not make it forget where the caret was.</b>
     *
     * <p>A press on the title bar focuses the FRAME — {@code emitMouseDown} walks up to the nearest
     * ancestor that focuses on click, and a title bar is not focusable — so a memory that recorded every
     * focus event inside the frame would record the frame itself and lose the control the user had been
     * using. {@code onFocus} therefore refuses a target that <em>is</em> this frame.</p>
     *
     * <p><b>The press then hands focus straight back</b>, and this test used to assert the opposite. The
     * press dispatch reaches {@code installActivation}, which activates the window, which restores its
     * focus owner — and {@code restoreFocus} deliberately does not count the frame ITSELF as "focus is
     * already inside this window", precisely because click-focus lands there before anything has been
     * dispatched. Reading it as focused-inside is what left a floating tool window looking focused with
     * its content cold. So the frame holding focus is a state that exists for the width of one dispatch,
     * and asserting on it was asserting on the gap.</p>
     */
    @Test
    public void draggingTheTitleBarDoesNotClobberTheFocusMemory() {
        build();
        WindowFrame first = open("One", 20, 20);
        WindowFrame second = open("Two", 200, 20);

        Button remembered = buttonIn(first);
        input.requestFocus(remembered);
        pressTitleBarOf(first);
        settle();
        assertSame("the press left focus on the caption instead of handing it back",
                remembered, input.getFocusedElement());

        desktop.activate(second);
        desktop.activate(first);
        assertSame("and the memory still points at the control", remembered, input.getFocusedElement());
    }

    /**
     * The other half, and the one that gives the test above its meaning: a caption press <b>does</b>
     * move focus into the window.
     *
     * <p>Without it, a build where the press moved no focus at all would satisfy the assertion above —
     * focus would simply still be sitting where {@code requestFocus} put it, having never left.</p>
     *
     * <p>The window is opened with <b>no</b> focus owner of its own, so there is nothing remembered to
     * restore and the delegate answers: {@code firstFocusableIn} the content, which is the button. That a
     * window with a control in it never comes to rest holding focus <em>itself</em> is the point — the
     * frame is where focus lands for the width of one dispatch and then moves on.</p>
     */
    @Test
    public void aCaptionPressPutsFocusInsideTheWindow() {
        build();
        WindowFrame frame = open("One", 20, 20);
        input.blurIfFocused(input.getFocusedElement());

        pressTitleBarOf(frame);
        settle();

        assertSame("a caption press left focus on the frame rather than in the window",
                buttonIn(frame), input.getFocusedElement());
    }

    // ── The desktop itself ──────────────────────────────────────────────────

    /** A press on bare desktop leaves no window active — the state clicking the background produces
     * on every desktop there is, and a legal one here. */
    @Test
    public void aPressOnBareDesktopDeactivates() {
        build();
        WindowFrame frame = open("One", 20, 20);
        assertSame(frame, desktop.activeWindow());

        press(350f, 260f); // past the window's bottom-right corner
        settle();

        assertNull("no window is active", desktop.activeWindow());
        assertFalse(frame.isActive());
    }

    /** Closing the front window activates the next one down rather than leaving the desktop pointing
     * at a frame that is no longer in the tree. */
    @Test
    public void closingTheActiveWindowActivatesTheOneBehindIt() {
        build();
        WindowFrame first = open("One", 20, 20);
        WindowFrame second = open("Two", 200, 20);
        assertSame(second, desktop.activeWindow());

        second.requestClose();
        settle();

        assertSame("the window behind takes over", first, desktop.activeWindow());
        assertTrue(first.isActive());
    }

    /**
     * The counter has a ceiling — the pinned band (W14) sits directly above it — so it renormalises.
     * Called directly, because reaching it by hand takes a million raises and a renormalisation that
     * has never run is one that reorders the desktop the first time it does.
     */
    @Test
    public void renormalisingTheStackPreservesOrder() {
        build();
        WindowFrame first = open("One", 20, 20);
        WindowFrame second = open("Two", 60, 60);
        WindowFrame third = open("Three", 100, 100);
        desktop.raise(second);   // order is now first < third < second

        desktop.renormaliseStack();
        settle();

        assertTrue("relative order survives", zOf(first) < zOf(third));
        assertTrue(zOf(third) < zOf(second));
        assertTrue("and the band above stays free", zOf(second) < (1 << 20));
    }
}
