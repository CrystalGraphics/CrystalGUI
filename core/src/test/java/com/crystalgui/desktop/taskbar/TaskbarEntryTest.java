package com.crystalgui.desktop.taskbar;

import com.crystalgui.core.async.Progress;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.Desktop;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * What a taskbar entry says about its document, and what pressing it does — W12's remainder.
 *
 * <p>Four things that are each one flag on a {@code WindowFrame} and one class on an entry, and that
 * are grouped because they share the failure mode: the flag is set, the entry never hears about it, and
 * nothing anywhere reports a problem. The registry's change signal is what carries them, so every
 * setter has to announce.</p>
 */
public class TaskbarEntryTest extends UiDocumentTestBase {

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

    private UINode root;

    @After
    public void animationsBackOn() {
        Desktop.setAnimationsEnabled(true);
    }

    @Before
    public void setUpDesktop() {
        // Animations OFF, stated rather than inherited. Every assertion in this fixture reads a
        // geometry or a state straight after a gesture, and a running timeline defers both -- `hide()`
        // detaches and `close()` destroys only once the flight ends, so the assertion reads the state
        // BEFORE the gesture took effect and the numbers it does get are mid-flight fractions.
        Desktop.setAnimationsEnabled(false);
        root = new UINode().layout(l -> l.width(800).height(600));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) frame();
    }

    private Taskbar taskbar() {
        return Desktop.of(document).taskbar();
    }

    private Button entryOf(WindowFrame frame) {
        Button entry = taskbar().entryFor(frame);
        assertNotNull("no taskbar entry for " + frame.getTitle(), entry);
        return entry;
    }

    // ── The no-steal rule ───────────────────────────────────────────────────────────────────────

    /**
     * <b>A document opened in the background appears, takes no focus, and says so.</b>
     *
     * <p>Every windowing system converged here — Win32's foreground lock plus {@code FlashWindowEx},
     * X11's urgency hint, macOS's bouncing icon — because the alternative is a server pushing a UI that
     * takes the keyboard out from under whatever is being typed.</p>
     *
     * <p>All three assertions matter. "Opened" without "took no focus" is the steal; "took no focus"
     * without "asked for attention" is a document nobody knows exists, which is worse than either.</p>
     */
    @Test
    public void aBackgroundWindowOpensWithoutFocusAndAsksForAttention() {
        WindowFrame first = Desktop.of(document).addWindow(new WindowFrame("Editor"));
        settle();
        assertSame(first, Desktop.of(document).activeWindow());

        WindowFrame pushed = Desktop.of(document).addWindow(new WindowFrame("From the server"), false);
        settle();

        assertEquals("a background document did not open at all", WindowState.VISIBLE, pushed.state());
        assertSame("a background document stole focus", first, Desktop.of(document).activeWindow());
        assertTrue("a background document appeared without asking for attention",
                pushed.isDemandingAttention());
        assertTrue("the entry does not show it", entryOf(pushed).hasClass(Taskbar.ATTENTION_CLASS));
    }

    /**
     * <b>...and it must not be raised above the document being worked in either.</b>
     *
     * <p>A background document that jumped to the front of the stack is a focus steal missing only the
     * focus — it covers what is being typed in, which is most of the harm. Asserted on the stack order
     * the compositor actually sorts by.</p>
     */
    @Test
    public void aBackgroundWindowIsNotRaisedOverTheActiveOne() {
        WindowFrame first = Desktop.of(document).addWindow(new WindowFrame("Editor"));
        settle();
        WindowFrame pushed = Desktop.of(document).addWindow(new WindowFrame("From the server"), false);
        settle();

        assertTrue("a background document was raised above the active one",
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
        WindowFrame pushed = Desktop.of(document).addWindow(new WindowFrame("From the server"), false);
        settle();
        assertTrue(pushed.isDemandingAttention());

        Desktop.of(document).activate(pushed);
        settle();

        assertFalse("activating the document left it still asking for attention",
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
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("Editor"));
        settle();
        assertNull("the entry started with a badge slot it never asked for",
                entryOf(frame).getPostIcon());

        frame.setBadge("3");
        settle();
        UINode slot = entryOf(frame).getPostIcon();
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
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("Editor"));
        settle();
        assertFalse("a fresh document is reporting progress", entryOf(frame).hasClass(Taskbar.BUSY_CLASS));

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
     * <b>Middle-clicking an entry closes its document — through the POLICY, never destroy.</b>
     *
     * <p>Every taskbar and every browser tab strip does this. Routing it through {@code requestClose}
     * is what keeps a {@code HIDE_ON_CLOSE} document retained and lets a dirty one refuse; calling
     * {@code destroy()} from the bar would be the one close in the application that ignores both.</p>
     *
     * <p>Driven through {@code consumeMouseEvent} at a point, not {@code sendInputEvent}: the handler is
     * on mouse-DOWN and depends on which button, and a fixture that dispatches straight at the element
     * would skip the whole button-resolution path it is written against.</p>
     */
    @Test
    public void middleClickingAnEntryClosesTheWindow() {
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("Editor"));
        frame.setPolicy(WindowPolicy.HIDE_ON_CLOSE);
        settle();

        pressMiddle(entryOf(frame));

        assertEquals("middle-clicking the entry did not close the document",
                WindowState.HIDDEN, frame.state());
    }

    /**
     * <b>...and a LEFT click on the same entry still means activate, not close.</b>
     *
     * <p>The counter-assertion that makes the one above mean something. A {@code Button}'s activation is
     * button-agnostic, so hanging close off {@code onPressed} would have closed the document on an
     * ordinary click — a taskbar you cannot click.</p>
     */
    @Test
    public void aLeftClickOnAnEntryDoesNotCloseTheWindow() {
        WindowFrame first = Desktop.of(document).addWindow(new WindowFrame("Editor"));
        WindowFrame second = Desktop.of(document).addWindow(new WindowFrame("Other"));
        settle();
        assertNotSame(first, Desktop.of(document).activeWindow());

        pressLeft(entryOf(first));

        assertEquals("a left click closed the document", WindowState.VISIBLE, first.state());
        assertSame("a left click on an entry did not activate its document",
                first, Desktop.of(document).activeWindow());
    }

    private void pressMiddle(UINode target) {
        pressAt(target, CgMouseCodes.MIDDLE_BUTTON);
    }

    private void pressLeft(UINode target) {
        pressAt(target, CgMouseCodes.LEFT_BUTTON);
        // A click is a pair, and a Button activates on the UP.
        var box = target.box();
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(box.worldX() + box.width() / 2f * uiScale()),
                Math.round(box.worldY() + box.height() / 2f * uiScale()),
                0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 0L));
        settle();
    }

    private void pressAt(UINode target, int button) {
        var box = target.box();
        frame();
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(box.worldX() + box.width() / 2f * uiScale()),
                Math.round(box.worldY() + box.height() / 2f * uiScale()),
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
    private static boolean onScreen(UINode element) {
        // ...and NO box is the same answer as a zero-wide one: a hidden node has none at all here.
        return widthOf(element) > 0f;
    }
}
