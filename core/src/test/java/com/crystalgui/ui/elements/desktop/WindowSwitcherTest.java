package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;
import org.joml.Vector2f;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * CrystalOS W10 — the MRU switcher ({@code plan_windowing.md}).
 *
 * <p>Every test here is about a convention rather than a preference, which is why the switcher was
 * ported in shape from GNOME Shell rather than derived: each of these is one line of code, invisible when
 * wrong, and learned by shipping to a lot of people.</p>
 */
public class WindowSwitcherTest extends UiTestBase {

    /** A keyboard whose modifier state a test can move — the default stub reports a constant zero. */
    private static final class HeldModifiers implements CgInputService {
        int held;

        @Override public int getCurrentModifiers() { return held; }
        @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
        @Override public boolean isKeyDown(int localKeyCode) { return false; }
        @Override public int translateMouseCodes(int platformCode) { return platformCode; }
        @Override public boolean isMouseDown(int localMouseCode) { return false; }
        @Override public int howManyMouseButtons() { return 3; }
        @Override public String getClipboard() { return ""; }
        @Override public void setClipboard(String text) { }
    }

    private UIWindow window;
    private Desktop desktop;
    private WindowSwitcher switcher;
    private HeldModifiers keyboard;

    @Before
    public void build() {
        CommandRegistry.global().resetForTesting();
        DesktopCommands.resetForTesting();

        keyboard = new HeldModifiers();
        keyboard.held = CgModifiers.CTRL;
        TestPlatformService.get().input(keyboard);

        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        desktop = window.desktop();
        switcher = desktop.switcher();
        frame();
    }

    private void frame() {
        window.updateWithoutPainting();
    }

    private WindowFrame open(String title) {
        WindowFrame frame = window.openWindow(new WindowFrame(title));
        frame.resizeTo(160, 120);
        frame();
        frame();
        return frame;
    }

    private void cycleForward() {
        switcher.cycle(true, DesktopCommands.SWITCH_WINDOW);
    }

    /**
     * <b>The shipped chord actually reaches the switcher.</b>
     *
     * <p>Every other test here calls {@code cycle} directly, which proves the state machine and nothing
     * about whether a key press can ever get to it. The path in between is real and has three places to
     * fail silently — the command has to be registered, its declared binding has to parse, and the keymap
     * has to resolve {@code Mod+Tab} rather than handing the stroke to Tab-focus traversal. A switcher
     * that works perfectly and cannot be summoned is the failure this covers.</p>
     *
     * <p>Driven through {@code consumeKeyboardEvent} rather than {@code sendInputEvent} deliberately: the
     * latter dispatches straight at an element and skips keymap resolution entirely, which is how sixteen
     * passing tests once shipped a menu-bar bug.</p>
     */
    @Test
    public void theShippedChordOpensTheSwitcher() {
        DesktopCommands.register();
        WindowFrame first = open("First");
        open("Second");

        keyboard.held = CgModifiers.CTRL;
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', CgKeyCodes.KEY_TAB, true, false, 0L));

        assertTrue("Mod+Tab did not reach the switcher", switcher.isOpen());
        assertSame(first, switcher.selectedWindow());
    }

    /**
     * <b>...and it still opens with a TEXT EDITOR focused, which is where it did not.</b>
     *
     * <p>The keymap resolves <em>after</em> the event has bubbled and only on an event nothing consumed —
     * deliberately, so a control gets first refusal on its own keystrokes. The cost is that any widget
     * which eats a chord it has no use for silently denies it to everything above, and {@code TextEditor}
     * ate <b>Ctrl+Tab</b>: its bare-Tab case ran regardless of the modifier, so the chord indented the
     * current line and the switcher never heard it. Nothing failed — an indent is a perfectly good thing
     * for Tab to do — so it reads as the switcher being broken rather than as the editor being greedy.</p>
     *
     * <p>Written against the editor because that is where a user's focus actually is: a window-management
     * chord that works only on an empty desktop is one that works nowhere.</p>
     */
    @Test
    public void theChordStillOpensTheSwitcherWithAnEditorFocused() {
        DesktopCommands.register();
        WindowFrame first = open("First");
        WindowFrame second = open("Second");

        TextEditor editor = new TextEditor("one\ntwo\n");
        second.content().addChild(editor);
        frame();
        window.getInputHandler().requestFocus(editor);
        frame();
        assertSame("the fixture never focused the editor", editor,
                window.getInputHandler().getFocusedElement());

        String before = editor.getText();
        keyboard.held = CgModifiers.CTRL;
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', CgKeyCodes.KEY_TAB, true, false, 0L));

        assertEquals("the editor indented instead of yielding the chord", before, editor.getText());
        assertTrue("Ctrl+Tab was eaten before the keymap could see it", switcher.isOpen());
        assertSame(first, switcher.selectedWindow());
    }

    /**
     * <b>Left and Right walk the selection, and they wrap.</b>
     *
     * <p>GNOME's {@code WindowSwitcherPopup._keyPressHandler} spends Left/Right on previous/next and
     * wraps with a modulo. They are the same movement the chord makes, which is the point — the chord is
     * awkward to repeat precisely and the arrows are how anyone actually lands on a distant window.</p>
     */
    @Test
    public void theArrowsWalkTheSelectionAndWrap() {
        WindowFrame first = open("First");
        WindowFrame second = open("Second");

        cycleForward();
        assertSame(first, switcher.selectedWindow());

        assertTrue(window.routeKeyToWindowSwitcher(CgKeyCodes.KEY_RIGHT));
        assertSame("Right did not advance", second, switcher.selectedWindow());

        assertTrue(window.routeKeyToWindowSwitcher(CgKeyCodes.KEY_RIGHT));
        assertSame("Right does not wrap", first, switcher.selectedWindow());

        assertTrue(window.routeKeyToWindowSwitcher(CgKeyCodes.KEY_LEFT));
        assertSame("Left does not wrap the other way", second, switcher.selectedWindow());
    }

    /**
     * <b>...and they get there with a TEXT EDITOR focused, which is the only reason they need routing.</b>
     *
     * <p>An arrow key reaches the focused element and a focused editor moves its caret with it, so an
     * arrow that went through ordinary dispatch would scroll the document behind the switcher and never
     * touch the selection. GNOME holds a modal grab for the whole gesture for exactly this; ours
     * intercepts the keys it acts on ahead of dispatch, on the rung a live drag already occupies.</p>
     */
    @Test
    public void theArrowsReachTheSwitcherWithAnEditorFocused() {
        DesktopCommands.register();
        WindowFrame first = open("First");
        WindowFrame second = open("Second");

        TextEditor editor = new TextEditor("one\ntwo\nthree\n");
        second.content().addChild(editor);
        frame();
        window.getInputHandler().requestFocus(editor);
        frame();

        cycleForward();
        assertSame(first, switcher.selectedWindow());

        // A PRESENTED FRAME FIRST. consumeKeyboardEvent early-returns until one has been, and
        // updateWithoutPainting deliberately runs no input at all — so without this the arrow is dropped
        // on the floor and the test passes or fails for a reason that has nothing to do with routing.
        int caretBefore = editor.getCaret();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', CgKeyCodes.KEY_RIGHT, true, false, 0L));

        assertSame("the editor took the arrow instead of the switcher", second,
                switcher.selectedWindow());
        assertEquals("the arrow moved the caret as well as the selection",
                caretBefore, editor.getCaret());
    }

    /**
     * <b>Enter commits on the spot, modifier still held.</b>
     *
     * <p>GNOME's base class does this for Space and Return, and it is the only way to finish the gesture
     * at all if somebody rebinds the switcher to a chord with no modifier in it — there would be nothing
     * to let go of.</p>
     */
    @Test
    public void enterCommitsWhileTheModifierIsStillHeld() {
        WindowFrame first = open("First");
        open("Second");

        cycleForward();
        assertTrue(switcher.isOpen());

        assertTrue(window.routeKeyToWindowSwitcher(CgKeyCodes.KEY_RETURN));

        assertFalse("Enter did not finish the gesture", switcher.isOpen());
        assertSame("Enter activated the wrong window", first, desktop.activeWindow());
        assertEquals("the modifier was released to make this happen", CgModifiers.CTRL, keyboard.held);
    }

    /**
     * <b>Up and Down move by a ROW, so in a single row they do nothing.</b>
     *
     * <p>Which is what every grid does, and is why they are not simply aliases for Left and Right. Our
     * panel wraps into a grid — GNOME's is one line and spends Up/Down on an app's window sub-list we
     * have no equivalent of — so the vertical arrows navigate rows or they navigate nothing.</p>
     *
     * <p>The wrapped case is left to the harness on purpose: how many tiles fit on a row depends on each
     * window's shape and on what the sheet's {@code max-width} leaves, and a headless tile has no picture
     * at all, so a test that forced a wrap would be asserting against an arrangement no user ever sees.</p>
     */
    @Test
    public void theVerticalArrowsDoNothingInASingleRow() {
        WindowFrame first = open("First");
        open("Second");

        cycleForward();
        assertSame(first, switcher.selectedWindow());

        assertTrue("Down should still be consumed, it simply has nowhere to go",
                window.routeKeyToWindowSwitcher(CgKeyCodes.KEY_DOWN));
        assertSame(first, switcher.selectedWindow());

        assertTrue(window.routeKeyToWindowSwitcher(CgKeyCodes.KEY_UP));
        assertSame(first, switcher.selectedWindow());
    }

    /**
     * <b>Hovering a tile selects it and clicking one activates it.</b>
     *
     * <p>Windows' switcher, and the reason the overlay is hittable at all — it covers the whole desktop
     * so its panel can be centred by flexbox rather than by arithmetic. What keeps that safe is that it
     * is {@code display: none} for every frame it is not drawn, which includes the whole of the 150ms
     * delay and therefore the whole of a fast tap: there is no window in which an invisible switcher can
     * swallow a click.</p>
     */
    @Test
    public void hoveringATileSelectsItAndPressingOneActivatesIt() {
        WindowFrame first = open("First");
        WindowFrame second = open("Second");

        cycleForward();
        revealPanel();
        assertSame(first, switcher.selectedWindow());

        UIElement secondTile = tileFor(second);
        hover(secondTile);
        assertSame("hovering a tile did not select it", second, switcher.selectedWindow());

        press(secondTile);
        assertFalse("pressing a tile did not finish the gesture", switcher.isOpen());
        assertSame("pressing a tile activated the wrong window", second, desktop.activeWindow());
    }

    /**
     * <b>A press on the backdrop cancels rather than committing.</b>
     *
     * <p>The pointer is nowhere near a tile, so nothing says the user meant whatever happened to be
     * selected — which is how a press outside any menu behaves, and the safer of the two readings.</p>
     */
    @Test
    public void pressingTheBackdropCancels() {
        open("First");
        WindowFrame second = open("Second");

        cycleForward();
        revealPanel();

        press(switcher);

        assertFalse(switcher.isOpen());
        assertSame("a cancelled switch still changed the active window", second, desktop.activeWindow());
    }

    /**
     * <b>A tile's close button closes that window and takes it out of the offer.</b>
     *
     * <p>Through {@code requestClose}, so a window's policy still decides what closing means. The entry
     * goes optimistically because a close ANIMATES — the frame is not destroyed until its 150ms has
     * played, so waiting for the registry to agree would leave a tile on screen for the window the user
     * just dismissed.</p>
     */
    @Test
    public void closingATileRemovesItFromTheOffer() {
        WindowFrame first = open("First");
        WindowFrame second = open("Second");
        WindowFrame third = open("Third");

        cycleForward();
        revealPanel();
        assertEquals(3, switcher.offered().size());

        closeButtonOf(first).onPressed.emit();

        assertTrue("the switcher went away entirely", switcher.isOpen());
        assertFalse("the closed window is still on offer", switcher.offered().contains(first));
        assertEquals(2, switcher.offered().size());
        assertTrue(switcher.offered().contains(second) && switcher.offered().contains(third));
    }

    /**
     * <b>...and pressing that button must not also activate the tile under it.</b>
     *
     * <p>The tile listens on the bubble phase so a press anywhere in it activates, which reaches the
     * close button too — and activating the window somebody just asked to close is the one outcome that
     * cannot be right.</p>
     */
    @Test
    public void pressingCloseDoesNotActivateTheTile() {
        WindowFrame first = open("First");
        open("Second");

        cycleForward();
        revealPanel();

        // THROUGH REAL DISPATCH, or the bubble to the tile never happens and the test cannot see the
        // thing it exists for -- the tile's activation listener is on the BUBBLE phase.
        press(closeButtonOf(first));

        assertTrue("the press fell through to the tile and finished the gesture", switcher.isOpen());
    }

    private void press(UIElement target) {
        window.getInputHandler().sendInputEvent(target, new MouseEvent.Down(
                target, new ReadOnlyVec2f(new Vector2f(0f, 0f)), 0, 1));
    }

    private void hover(UIElement target) {
        window.getInputHandler().sendInputEvent(target, new MouseEvent.Enter(
                target, new ReadOnlyVec2f(new Vector2f(0f, 0f))));
    }

    /** Ticks until the panel is drawn — the tiles do not exist to be pressed until it is. */
    private void revealPanel() {
        long until = System.nanoTime() + 500L * 1_000_000L;
        while (System.nanoTime() < until && !switcher.isVisible()) {
            frame();
        }
        assertTrue("the panel never appeared", switcher.isVisible());
    }

    private UIElement tileFor(WindowFrame frame) {
        int index = switcher.offered().indexOf(frame);
        assertTrue("no tile for " + frame.getTitle(), index >= 0);
        return switcher.tileAt(index);
    }

    private Button closeButtonOf(WindowFrame frame) {
        return switcher.closeButtonAt(switcher.offered().indexOf(frame));
    }

    /**
     * <b>Putting a window away for the first time says how to get it back — naming the LIVE chord.</b>
     *
     * <p>W10's other half, and the reason it exists: a keybinding nobody can discover is a keybinding
     * that does not exist. The taskbar is the safety net and is visible; the switcher is the fast path
     * and is invisible until you already know about it, so the moment a window first disappears is the
     * one moment the offer is both relevant and unmissable.</p>
     *
     * <p><b>The assertion is on the chord's text.</b> A literal in the message would be a promise the
     * notification cannot keep the moment anything rebinds the command, and it fails silently — the
     * message goes on confidently naming a key that does nothing. Asserting that the accelerator appears
     * is what separates "read from the keymap" from "spelled and happens to match today".</p>
     */
    @Test
    public void theFirstHideAnnouncesTheSwitcherChord() {
        DesktopCommands.register();
        Notifications.resetForTesting();
        Desktop.resetSwitcherAnnouncementForTesting();

        open("First");
        WindowFrame second = open("Second");
        second.hide();
        frame();

        KeyChord chord = Keymap.acceleratorFor(desktop, DesktopCommands.SWITCH_WINDOW);
        assertNotNull("the switcher is unbound, so there was nothing to announce", chord);
        assertEquals("the first hide said nothing at all", 1, Notifications.history().size());
        assertTrue("the announcement does not name the live chord: "
                        + Notifications.history().get(0).getDetail(),
                Notifications.history().get(0).getDetail().contains(chord.toString()));
    }

    /**
     * <b>...once, and never on a desktop with nothing to switch between.</b>
     *
     * <p>Two separate guards. Repeating it turns a helpful message into a nag on every minimise, and
     * advertising a switcher to somebody with one window teaches a chord that will appear broken the
     * first time it is pressed.</p>
     */
    @Test
    public void theAnnouncementIsMadeOnceAndNotForALoneWindow() {
        DesktopCommands.register();
        Notifications.resetForTesting();
        Desktop.resetSwitcherAnnouncementForTesting();

        WindowFrame only = open("Only");
        only.hide();
        frame();
        assertEquals("a lone window has nothing to switch to", 0, Notifications.history().size());

        only.show(true);
        WindowFrame second = open("Second");
        frame();
        second.hide();
        frame();
        assertEquals(1, Notifications.history().size());

        only.hide();
        frame();
        // ON THE REPEAT COUNT, not the history size. Notifications COALESCES an identical message into
        // the entry already there and bumps its counter, so a switcher that announced itself on every
        // hide would leave the history exactly one long -- and this test passed against precisely that
        // mutant until it asked the question the mechanism can actually answer.
        assertEquals("the announcement repeated", 1, Notifications.history().size());
        assertEquals("the announcement was made again on a later hide",
                1, Notifications.history().get(0).getRepeats());
    }

    /** A key nothing in the switcher acts on falls through, or the gesture would deaden the keyboard. */
    @Test
    public void anUnrelatedKeyIsNotSwallowed() {
        open("First");
        open("Second");

        cycleForward();

        assertFalse("the switcher is eating keys it has no use for",
                window.routeKeyToWindowSwitcher(CgKeyCodes.KEY_A));
        assertTrue("...and it should still be open", switcher.isOpen());
    }

    /**
     * <b>The gesture opens on the SECOND entry, so a tap swaps to the last window.</b>
     *
     * <p>The first MRU entry is the window you are already in. Starting there makes the commonest gesture
     * anyone performs — tap the chord, bounce to the last thing — a no-op that re-activates what is
     * already in front, and nothing about that reads as an off-by-one: it reads as the switcher not
     * working. GNOME's {@code _initialSelection} takes index 1 going forward for exactly this.</p>
     */
    @Test
    public void theGestureOpensOnThePreviouslyUsedWindow() {
        WindowFrame first = open("First");
        WindowFrame second = open("Second");
        assertSame("the fixture never made Second the active window", second, desktop.activeWindow());

        cycleForward();

        assertTrue(switcher.isOpen());
        assertSame("the switcher offers the window already in front, so a tap does nothing",
                first, switcher.selectedWindow());
    }

    /** Backwards starts at the END, which is the same rule read the other way. */
    @Test
    public void cyclingBackwardsOpensOnTheLeastRecentlyUsedWindow() {
        WindowFrame first = open("First");
        open("Second");
        WindowFrame third = open("Third");

        switcher.cycle(false, DesktopCommands.SWITCH_WINDOW_BACK);

        assertSame("backwards should reach the oldest window in one press", first,
                switcher.selectedWindow());
        assertSame("MRU order is wrong at the front", third, switcher.offered().get(0));
    }

    /**
     * <b>Releasing the modifier is what commits, and it is POLLED rather than listened for.</b>
     *
     * <p>A key-up listener has to know that Alt is two keys and that a modifier can be released while the
     * focus owner is somewhere else entirely. Masking the live modifier state against the mask the chord
     * carried answers both without knowing either — which is what GNOME does with its
     * {@code modifier-change} handler, and what the codebase's own "the fix is PULL, not a hop" rule
     * points at.</p>
     */
    @Test
    public void lettingGoOfTheModifierActivatesTheSelectedWindow() {
        WindowFrame first = open("First");
        WindowFrame second = open("Second");

        cycleForward();
        frame();
        assertTrue("committed while the modifier was still down", switcher.isOpen());
        assertSame("the window changed before the gesture was finished", second, desktop.activeWindow());

        keyboard.held = CgModifiers.NONE;
        frame();

        assertFalse("the switcher is still up after the modifier was released", switcher.isOpen());
        assertSame("releasing the modifier did not activate what was selected",
                first, desktop.activeWindow());
    }

    /**
     * <b>A fast tap never draws the panel at all.</b>
     *
     * <p>GNOME's {@code POPUP_DELAY_TIMEOUT}: the switcher is invisible for 150ms, and a release inside
     * that window commits having shown nothing. That is the whole of the tap-to-bounce gesture — without
     * it, every bounce between two windows flashes a panel on screen, which reads as flickering rather
     * than as fast.</p>
     */
    @Test
    public void aFastTapNeverShowsThePanel() {
        open("First");
        open("Second");

        cycleForward();
        assertTrue(switcher.isOpen());

        // A FRAME WITH THE MODIFIER STILL DOWN, which is the whole assertion. Checking visibility
        // straight after `cycle` proves nothing -- the reveal happens on a tick, so a switcher with no
        // delay at all would also read as invisible here, and this test passed against exactly that
        // mutant until the frame was added.
        frame();
        assertTrue("the gesture ended on its own", switcher.isOpen());
        assertFalse("the panel was drawn on the first frame, so there is no tap gesture",
                switcher.isVisible());

        keyboard.held = CgModifiers.NONE;
        frame();

        assertFalse(switcher.isOpen());
        assertFalse("the panel became visible on the way out", switcher.isVisible());
    }

    /**
     * <b>...and holding past the delay is what asks to see the list.</b>
     *
     * <p>The other half, without which "never visible" is satisfied by a panel that is never drawn at
     * all. Wall-clock, because the delay is measured against {@code System.nanoTime()} and there is no
     * way to step it — the same constraint every transition test in this codebase works under.</p>
     */
    @Test
    public void holdingPastTheDelayRevealsThePanel() {
        open("First");
        open("Second");

        cycleForward();
        long until = System.nanoTime() + 400L * 1_000_000L;
        while (System.nanoTime() < until && !switcher.isVisible()) {
            frame();
        }

        assertTrue("the panel never appeared, however long the chord was held", switcher.isVisible());
    }

    /**
     * <b>Escape abandons the gesture and activates nothing.</b>
     *
     * <p>Not through a close watcher, and the test is written against the real route for that reason: the
     * watcher cascade asks the ACTIVE FRAME's stack first and a frame registers as its own last watcher,
     * so a desktop-scoped watcher is unreachable whenever a window is active — Escape would minimise the
     * window behind the switcher instead of dismissing it.</p>
     */
    @Test
    public void escapeCancelsWithoutActivatingAnything() {
        open("First");
        WindowFrame second = open("Second");

        cycleForward();
        assertTrue(switcher.isOpen());

        assertTrue("nothing consumed the Escape",
                window.routeKeyToWindowSwitcher(CgKeyCodes.KEY_ESCAPE));

        assertFalse(switcher.isOpen());
        assertSame("a cancelled switch still changed the active window", second, desktop.activeWindow());
    }

    /**
     * <b>Minimised windows are offered — this is the whole reason the switcher reads MRU and not z.</b>
     *
     * <p>A hidden window has left the stacking order entirely, so anything z-ordered either omits it or
     * invents a place for it. The registry keeps activation order precisely so a window you put away a
     * moment ago is still the next thing the switcher offers.</p>
     */
    @Test
    public void aMinimisedWindowIsStillOffered() {
        WindowFrame first = open("First");
        open("Second");

        first.hide();
        frame();
        assertEquals(WindowState.HIDDEN, first.state());

        cycleForward();

        assertTrue("a minimised window is missing from the switcher",
                switcher.offered().contains(first));
    }

    /** One window is nothing to switch between, and a panel saying so is a panel in the way. */
    @Test
    public void aLoneWindowOpensNoSwitcher() {
        open("Only");

        cycleForward();

        assertFalse(switcher.isOpen());
        assertNull(switcher.selectedWindow());
    }

    /**
     * <b>Repeating the chord advances rather than reopening, and it wraps.</b>
     *
     * <p>One entry point for both because from the keymap's side they are the same keystroke — the
     * command fires again on every repeat. A second press that re-opened would pin the selection to the
     * second entry for ever, so the switcher could reach exactly one window.</p>
     */
    @Test
    public void repeatingTheChordAdvancesAndWrapsAround() {
        WindowFrame first = open("First");
        WindowFrame second = open("Second");

        cycleForward();
        assertSame(first, switcher.selectedWindow());
        cycleForward();
        assertSame("the second press did not advance", second, switcher.selectedWindow());
        cycleForward();
        assertSame("the selection does not wrap", first, switcher.selectedWindow());
    }
}
