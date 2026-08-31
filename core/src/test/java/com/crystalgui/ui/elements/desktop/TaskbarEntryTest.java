package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.elements.Button;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * What a taskbar entry says about its window, and what pressing it does — W12's remainder.
 *
 * <p>Four things that are each one flag on a {@code WindowFrame} and one class on an entry, and that
 * are grouped because they share the failure mode: the flag is set, the entry never hears about it, and
 * nothing anywhere reports a problem. The registry's change signal is what carries them, so every
 * setter has to announce.</p>
 */
public class TaskbarEntryTest extends UiTestBase {

    private UIWindow window;
    private UIElement root;

    @Before
    public void setUpDesktop() {
        root = new UIElement().layout(l -> l.width(800).height(600));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    private Taskbar taskbar() {
        return window.desktop().taskbar();
    }

    private Button entryOf(WindowFrame frame) {
        Button entry = taskbar().entryFor(frame);
        assertNotNull("no taskbar entry for " + frame.getTitle(), entry);
        return entry;
    }

    // ── The no-steal rule ───────────────────────────────────────────────────────────────────────

    /**
     * <b>A window opened in the background appears, takes no focus, and says so.</b>
     *
     * <p>Every windowing system converged here — Win32's foreground lock plus {@code FlashWindowEx},
     * X11's urgency hint, macOS's bouncing icon — because the alternative is a server pushing a UI that
     * takes the keyboard out from under whatever is being typed.</p>
     *
     * <p>All three assertions matter. "Opened" without "took no focus" is the steal; "took no focus"
     * without "asked for attention" is a window nobody knows exists, which is worse than either.</p>
     */
    @Test
    public void aBackgroundWindowOpensWithoutFocusAndAsksForAttention() {
        WindowFrame first = window.openWindow(new WindowFrame("Editor"));
        settle();
        assertSame(first, window.desktop().activeWindow());

        WindowFrame pushed = window.openWindowInBackground(new WindowFrame("From the server"));
        settle();

        assertEquals("a background window did not open at all", WindowState.VISIBLE, pushed.state());
        assertSame("a background window stole focus", first, window.desktop().activeWindow());
        assertTrue("a background window appeared without asking for attention",
                pushed.isDemandingAttention());
        assertTrue("the entry does not show it", entryOf(pushed).hasClass(Taskbar.ATTENTION_CLASS));
    }

    /**
     * <b>...and it must not be raised above the window being worked in either.</b>
     *
     * <p>A background window that jumped to the front of the stack is a focus steal missing only the
     * focus — it covers what is being typed in, which is most of the harm. Asserted on the stack order
     * the compositor actually sorts by.</p>
     */
    @Test
    public void aBackgroundWindowIsNotRaisedOverTheActiveOne() {
        WindowFrame first = window.openWindow(new WindowFrame("Editor"));
        settle();
        WindowFrame pushed = window.openWindowInBackground(new WindowFrame("From the server"));
        settle();

        assertTrue("a background window was raised above the active one",
                pushed.stackOrder() < first.stackOrder());
    }

    /**
     * <b>Activation is what clears the flash — never a timer.</b>
     *
     * <p>A flash that gives up after a few seconds is a notification you can miss by looking away, which
     * is the thing the entry exists to prevent. The only event that means "the user has seen it" is
     * their looking at it.</p>
     */
    @Test
    public void activatingTheWindowClearsTheAttentionFlash() {
        WindowFrame pushed = window.openWindowInBackground(new WindowFrame("From the server"));
        settle();
        assertTrue(pushed.isDemandingAttention());

        window.desktop().activate(pushed);
        settle();

        assertFalse("activating the window left it still asking for attention",
                pushed.isDemandingAttention());
        assertFalse("the entry is still flashing", entryOf(pushed).hasClass(Taskbar.ATTENTION_CLASS));
    }

    // ── Badge and progress ──────────────────────────────────────────────────────────────────────

    /**
     * <b>A badge reaches the entry, and disappears again.</b>
     *
     * <p>The disappearing half is the one that rots: a badge slot built on first use and then only ever
     * shown is a badge that says "3 errors" for the rest of the session after they are fixed.</p>
     */
    @Test
    public void aBadgeReachesTheEntryAndCanBeTakenAway() {
        WindowFrame frame = window.openWindow(new WindowFrame("Editor"));
        settle();
        assertNull("the entry started with a badge slot it never asked for",
                entryOf(frame).getPostIcon());

        frame.setBadge("3");
        settle();
        UIElement slot = entryOf(frame).getPostIcon();
        assertNotNull("a badge did not reach the entry", slot);
        assertTrue("the badge is not on screen", onScreen(slot));

        frame.setBadge(null);
        settle();
        assertFalse("a cleared badge is still on screen",
                onScreen(entryOf(frame).getPostIcon()));
    }

    /**
     * <b>Progress is off until something reports it, and NaN counts as off.</b>
     *
     * <p>{@code !(x >= 0)} rather than {@code x < 0}, because NaN fails every comparison — the guard
     * that reads as protective and lets through the one value that matters. A NaN here would be
     * multiplied into a percentage width and poison the entry's layout, which is the same trap that
     * once put every row of an editor at the same y.</p>
     */
    @Test
    public void progressIsAbsentUntilReportedAndNaNIsNotProgress() {
        WindowFrame frame = window.openWindow(new WindowFrame("Editor"));
        settle();
        assertFalse("a fresh window is reporting progress", entryOf(frame).hasClass(Taskbar.BUSY_CLASS));

        frame.setProgress(0.5f);
        settle();
        assertTrue("progress did not reach the entry", entryOf(frame).hasClass(Taskbar.BUSY_CLASS));

        frame.setProgress(Float.NaN);
        settle();
        assertFalse("NaN was accepted as a progress value",
                entryOf(frame).hasClass(Taskbar.BUSY_CLASS));
        assertTrue("NaN was stored rather than refused", frame.progress() < 0f);

        frame.setProgress(4f);
        assertEquals("progress was not clamped", 1f, frame.progress(), 0.001f);
    }

    // ── Middle-click ────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Middle-clicking an entry closes its window — through the POLICY, never destroy.</b>
     *
     * <p>Every taskbar and every browser tab strip does this. Routing it through {@code requestClose}
     * is what keeps a {@code HIDE_ON_CLOSE} window retained and lets a dirty one refuse; calling
     * {@code destroy()} from the bar would be the one close in the application that ignores both.</p>
     *
     * <p>Driven through {@code consumeMouseEvent} at a point, not {@code sendInputEvent}: the handler is
     * on mouse-DOWN and depends on which button, and a fixture that dispatches straight at the element
     * would skip the whole button-resolution path it is written against.</p>
     */
    @Test
    public void middleClickingAnEntryClosesTheWindow() {
        WindowFrame frame = window.openWindow(new WindowFrame("Editor"));
        frame.setPolicy(WindowPolicy.HIDE_ON_CLOSE);
        settle();

        pressMiddle(entryOf(frame));

        assertEquals("middle-clicking the entry did not close the window",
                WindowState.HIDDEN, frame.state());
    }

    /**
     * <b>...and a LEFT click on the same entry still means activate, not close.</b>
     *
     * <p>The counter-assertion that makes the one above mean something. A {@code Button}'s activation is
     * button-agnostic, so hanging close off {@code onPressed} would have closed the window on an
     * ordinary click — a taskbar you cannot click.</p>
     */
    @Test
    public void aLeftClickOnAnEntryDoesNotCloseTheWindow() {
        WindowFrame first = window.openWindow(new WindowFrame("Editor"));
        WindowFrame second = window.openWindow(new WindowFrame("Other"));
        settle();
        assertNotSame(first, window.desktop().activeWindow());

        pressLeft(entryOf(first));

        assertEquals("a left click closed the window", WindowState.VISIBLE, first.state());
        assertSame("a left click on an entry did not activate its window",
                first, window.desktop().activeWindow());
    }

    private void pressMiddle(UIElement target) {
        pressAt(target, CgMouseCodes.MIDDLE_BUTTON);
    }

    private void pressLeft(UIElement target) {
        pressAt(target, CgMouseCodes.LEFT_BUTTON);
        // A click is a pair, and a Button activates on the UP.
        var box = target.getRuntimeCache();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round((box.getX() + box.getWidth() / 2f) * 2f),
                Math.round((box.getY() + box.getHeight() / 2f) * 2f),
                0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 0L));
        settle();
    }

    private void pressAt(UIElement target, int button) {
        var box = target.getRuntimeCache();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round((box.getX() + box.getWidth() / 2f) * 2f),
                Math.round((box.getY() + box.getHeight() / 2f) * 2f),
                0, 0, button, true, 0f, 0L));
        settle();
    }

    /**
     * Whether an element is actually drawn.
     *
     * <p>Asked as a measured box rather than as a computed {@code display}, for the reason the engine
     * already records: {@code getComputed} answers <b>null</b> for a property nothing has written, so a
     * slot that has never been shown or hidden reports neither value and a test comparing against
     * {@code NONE} passes by accident. A box is a question every state can answer.</p>
     */
    private static boolean onScreen(UIElement element) {
        return element != null && element.getRuntimeCache().getWidth() > 0f;
    }
}
