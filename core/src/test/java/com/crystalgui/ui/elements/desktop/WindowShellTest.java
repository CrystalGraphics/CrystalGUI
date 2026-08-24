package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.command.MenuSection;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.elements.Dialog;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Show desktop, the modal-blocked pulse, and keyboard Move/Size — CrystalOS <b>W13c</b>.
 *
 * <p>Three shell conveniences that share one property: each is invisible when it fails. A show-desktop
 * that restored the wrong set, a blocked click that says nothing, and a keyboard mode that eats the
 * keyboard all look like the application misbehaving rather than like a feature being absent.</p>
 */
public class WindowShellTest extends UiTestBase {

    private UIWindow window;
    private WindowFrame first;
    private WindowFrame second;

    @Before
    public void setUpDesktop() {
        CommandRegistry.global().resetForTesting();
        WindowCommands.resetForTesting();
        DesktopCommands.resetForTesting();

        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        first = window.openWindow(new WindowFrame("First"));
        first.resizeTo(300, 200).moveTo(40, 40);
        second = window.openWindow(new WindowFrame("Second"));
        second.resizeTo(300, 200).moveTo(380, 40);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    private Desktop desktop() {
        return window.desktop();
    }

    // ── Show desktop ────────────────────────────────────────────────────────────────────────────

    /** <b>It takes everything down, and puts back exactly what it took.</b> */
    @Test
    public void showDesktopMinimisesEverythingAndPutsItBack() {
        desktop().toggleShowDesktop();
        settle();
        assertEquals(WindowState.HIDDEN, first.state());
        assertEquals(WindowState.HIDDEN, second.state());

        desktop().toggleShowDesktop();
        settle();
        assertEquals(WindowState.VISIBLE, first.state());
        assertEquals(WindowState.VISIBLE, second.state());
    }

    /**
     * <b>A window that was ALREADY minimised stays minimised.</b>
     *
     * <p>"Restore everything" would be the obvious implementation and is wrong: a desktop with three
     * windows already put away comes back with three nobody asked for. Putting back exactly the set it
     * took is also the only definition under which pressing it twice is a no-op.</p>
     */
    @Test
    public void showDesktopDoesNotRestoreWhatItDidNotMinimise() {
        second.minimize();
        settle();

        desktop().toggleShowDesktop();
        settle();
        desktop().toggleShowDesktop();
        settle();

        assertEquals("the window it took down did not come back", WindowState.VISIBLE, first.state());
        assertEquals("show-desktop restored a window it never minimised",
                WindowState.HIDDEN, second.state());
    }

    /**
     * <b>...and it forgets the moment a window is activated in between.</b>
     *
     * <p>Once the user has gone and used a window, "put it back how it was" no longer describes anything
     * they would recognise — so a second press means a fresh minimise-all rather than resurrecting a set
     * from before whatever they just did. Windows drops its own memory on the same event.</p>
     */
    @Test
    public void activatingAWindowForgetsTheShowDesktopMemory() {
        desktop().toggleShowDesktop();
        settle();
        assertTrue(desktop().isShowingDesktop());

        desktop().activate(first);
        settle();

        assertFalse("the memory survived a window being activated", desktop().isShowingDesktop());
        desktop().toggleShowDesktop();
        settle();
        assertEquals("the second press did not minimise afresh", WindowState.HIDDEN, first.state());
    }

    /**
     * A modal belonging to {@code frame} — <b>parented inside it, which is what scopes it</b>.
     *
     * <p>{@code Dialog.showModal} reads its owner from where it sits ({@code WindowFrame.of(this)}), so
     * a dialog attached to the frame's OWNED surface by hand is not the same thing: that surface is for
     * windows a frame owns, and a dialog placed there answered no owner at all — which made it a
     * WINDOW-level modal blocking every window on the desktop. The first version of these tests did
     * exactly that and reported it as a scoping bug.</p>
     */
    private Dialog openModalIn(WindowFrame frame) {
        Dialog dialog = new Dialog("Blocked");
        window.addOverlay(dialog, frame.content());
        dialog.showModal();
        settle();
        return dialog;
    }

    /**
     * <b>Show Desktop has an affordance, not just a command id.</b>
     *
     * <p>It shipped reachable only from the command palette — registered, enabled, working, and findable
     * by nobody, which was reported as the feature not existing. A verb with no affordance is not
     * shipped, and the taskbar's own context menu is where Windows puts this one.</p>
     */
    @Test
    public void showDesktopIsOnTheTaskbarContextMenu() {
        CommandContext context = CommandContext.of(desktop().taskbar());
        boolean found = false;
        for (MenuSection section : CommandRegistry.global()
                .sections(MenuId.TASKBAR_CONTEXT, context)) {
            for (MenuEntry entry : section.entries()) {
                if (entry instanceof MenuEntry.Item item
                        && DesktopCommands.SHOW_DESKTOP.equals(item.command().getId())) {
                    found = true;
                }
            }
        }
        assertTrue("Show Desktop has no menu to be found in", found);
    }

    // ── The modal-blocked pulse ─────────────────────────────────────────────────────────────────

    /**
     * <b>A press on a window its own modal is blocking pulses that modal.</b>
     *
     * <p>Without it, window-scoped modality's failure mode reads as <em>this window ignores my
     * clicks</em>, which is indistinguishable from a hang. Windows pulses the dialog and dings; the class
     * is the visible half.</p>
     *
     * <p>Asked of the <b>pointer</b> rather than of "is a modal open", because with per-window modality a
     * press on one window must pulse that window's dialog and not whichever is topmost elsewhere.</p>
     */
    @Test
    public void aBlockedPressPulsesTheModalResponsible() {
        Dialog dialog = openModalIn(first);

        UIElement blocking = modalBlamedAt(first);

        assertNotNull("a press on a blocked window found no modal to blame", blocking);
        assertEquals("it blamed the wrong element", dialog, blocking);
    }

    /**
     * <b>With a modal in EACH window, a press blames the one in the window pressed.</b>
     *
     * <p>The case that separates a scoped lookup from a global one, and nothing else does: with a single
     * modal open, "the topmost anywhere" and "the topmost in this window's scope" are the same object, so
     * every other test here passes against a build that ignores the scope entirely. Blaming the wrong
     * dialog would pulse a window the user is not looking at while the one they clicked stays silent —
     * worse than saying nothing, because it points somewhere.</p>
     */
    @Test
    public void withAModalInEachWindowTheRightOneIsBlamed() {
        Dialog inFirst = openModalIn(first);
        Dialog inSecond = openModalIn(second);

        assertEquals("a press on the first window blamed the wrong dialog",
                inFirst, modalBlamedAt(first));
        assertEquals("a press on the second window blamed the wrong dialog",
                inSecond, modalBlamedAt(second));
    }

    /** <b>A press on an UNBLOCKED window blames nothing</b> — the counter-assertion. */
    @Test
    public void aPressOnAnUnblockedWindowBlamesNothing() {
        openModalIn(first);

        assertEquals("a modal in one window blocked a press in another", null,
                modalBlamedAt(second));
    }

    /**
     * Which modal would swallow a press on {@code frame}'s caption.
     *
     * <p><b>Converted to surface pixels.</b> A hit test takes raw pointer coordinates while a runtime
     * box is in layout units, and at the default {@code uiScale} of 2 the two are a factor apart — so an
     * unconverted probe aimed at the second window landed inside the FIRST one and reported it blocked.
     * The same factor-of-two the restore-drag and the popup-placement rules both record, from a third
     * direction.</p>
     */
    private UIElement modalBlamedAt(WindowFrame frame) {
        var box = frame.titleBar().getRuntimeCache();
        return window.modalBlockingAt((box.getX() + 4f) * 2f, (box.getY() + 4f) * 2f);
    }

    // ── The desktop's own menu ──────────────────────────────────────────────────────────────────

    /**
     * <b>A right-click on bare desktop opens the desktop menu; one inside a window does not.</b>
     *
     * <p>The second half is the one that breaks silently. {@code ContextMenu.attach} subscribes the
     * BUBBLE phase, so a press on a window's bare content — anything with no menu of its own to consume
     * it — walks straight out to the window layer, and the desktop's menu opens on top of the window
     * that was clicked. The guard is a builder answering null, which makes attach return before it
     * builds or consumes anything.</p>
     *
     * <p>Driven through {@code consumeMouseEvent} rather than {@code sendInputEvent}, because dispatching
     * at an element skips the phase walk entirely — which is the only thing this is about.</p>
     */
    @Test
    public void theDesktopMenuOpensOnBareDesktopOnly() {
        first.resizeTo(300, 200).moveTo(60, 60);
        settle();

        UIElement content = new UIElement().layout(l -> l.width(120).height(60));
        first.content().addChild(content);
        settle();

        rightClick(content);
        assertEquals("a right-click inside a window opened the DESKTOP's menu", 0, openMenus());

        UIElement.RuntimeCache area = desktop().windowLayer().getRuntimeCache();
        // Below the window, which is at 60,60 and 300x200 — so this is bare layer.
        rightClickAt(area.getX() + 40f, area.getY() + area.getHeight() - 40f);
        assertTrue("a right-click on bare desktop opened no menu", openMenus() > 0);
    }

    private int openMenus() {
        return window.ui.rootElement.querySelectorAll("menu").size();
    }

    private void rightClick(UIElement target) {
        UIElement.RuntimeCache box = target.getRuntimeCache();
        rightClickAt(box.getX() + box.getWidth() / 2f, box.getY() + box.getHeight() / 2f);
    }

    private void rightClickAt(float x, float y) {
        UIInputHandler input = window.getInputHandler();
        input.beginFrame();
        input.endFrame();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0,
                CgMouseCodes.RIGHT_BUTTON, true, 0f, ++menuClock));
        input.beginFrame();
        input.endFrame();
        settle();
    }

    private long menuClock = 900_000L;

    // ── Keyboard Move/Size ──────────────────────────────────────────────────────────────────────

    /** <b>Arrows nudge, and Enter keeps the result.</b> */
    @Test
    public void arrowsNudgeAndEnterCommits() {
        float startLeft = first.getWantedLeft();
        assertTrue(desktop().keyboardMove().begin(first, WindowKeyboardMove.Mode.MOVE));

        assertTrue(window.routeKeyToKeyboardMove(CgKeyCodes.KEY_RIGHT, false));
        settle();
        assertEquals("an arrow did not nudge the window",
                startLeft + WindowKeyboardMove.STEP, first.getWantedLeft(), 0.01f);

        assertTrue(window.routeKeyToKeyboardMove(CgKeyCodes.KEY_RETURN, false));
        assertFalse("Enter did not end the mode", desktop().keyboardMove().isActive());
        assertEquals("Enter did not keep the result",
                startLeft + WindowKeyboardMove.STEP, first.getWantedLeft(), 0.01f);
    }

    /**
     * <b>Escape puts the window back exactly where it was.</b>
     *
     * <p>From the captured origin rather than by unwinding the steps: a nudge that hit the clamp moved
     * the window less than it asked for, so replaying the requested deltas backwards would not land
     * where it started.</p>
     */
    @Test
    public void escapeRestoresTheOriginalPosition() {
        float startLeft = first.getWantedLeft();
        float startTop = first.getWantedTop();
        desktop().keyboardMove().begin(first, WindowKeyboardMove.Mode.MOVE);

        window.routeKeyToKeyboardMove(CgKeyCodes.KEY_RIGHT, false);
        window.routeKeyToKeyboardMove(CgKeyCodes.KEY_DOWN, false);
        settle();
        window.routeKeyToKeyboardMove(CgKeyCodes.KEY_ESCAPE, false);
        settle();

        assertEquals("Escape did not put the window back", startLeft, first.getWantedLeft(), 0.01f);
        assertEquals(startTop, first.getWantedTop(), 0.01f);
        assertFalse(desktop().keyboardMove().isActive());
    }

    /**
     * <b>An unrelated key ends the mode and is NOT eaten.</b>
     *
     * <p>Windows ends the mode on the next unrelated action, which is what stops a mode nobody remembers
     * entering from swallowing the keyboard. Returning false is the half that matters: the keystroke
     * still does whatever it was going to do.</p>
     */
    @Test
    public void anUnrelatedKeyCommitsAndPassesThrough() {
        desktop().keyboardMove().begin(first, WindowKeyboardMove.Mode.MOVE);

        assertFalse("an unrelated key was swallowed by the move mode",
                window.routeKeyToKeyboardMove(CgKeyCodes.KEY_A, false));
        assertFalse("the mode outlived an unrelated keystroke", desktop().keyboardMove().isActive());
    }

    /** <b>Nothing is intercepted while no mode is running</b> — or every arrow in the application dies. */
    @Test
    public void keysPassThroughWhenNoModeIsRunning() {
        assertFalse(window.routeKeyToKeyboardMove(CgKeyCodes.KEY_LEFT, false));
        assertFalse(window.routeKeyToKeyboardMove(CgKeyCodes.KEY_ESCAPE, false));
    }

    /**
     * <b>Two arrows held move the window diagonally.</b>
     *
     * <p>Driven the way a keyboard actually behaves rather than by sending two events: with two keys
     * down a keyboard repeats <b>only the last one pressed</b>, so holding Left and then Up delivers a
     * stream of Up and nothing at all about Left. A test that sent one of each would pass against the
     * one-axis-per-event version this replaces — it would simply take two steps to arrive.</p>
     */
    @Test
    public void holdingTwoArrowsMovesDiagonally() {
        float startLeft = first.getWantedLeft();
        float startTop = first.getWantedTop();
        desktop().keyboardMove().begin(first, WindowKeyboardMove.Mode.MOVE);

        withHeld(key -> key == CgKeyCodes.KEY_LEFT,
                () -> window.routeKeyToKeyboardMove(CgKeyCodes.KEY_UP, false));
        settle();

        assertEquals("the HELD arrow moved nothing — only the event's own key was read",
                startLeft - WindowKeyboardMove.STEP, first.getWantedLeft(), 0.01f);
        assertEquals("the arriving arrow moved nothing",
                startTop - WindowKeyboardMove.STEP, first.getWantedTop(), 0.01f);
    }

    /**
     * <b>...and the SIZE mode is diagonal too</b>, since a corner resize is the one people reach for.
     */
    @Test
    public void holdingTwoArrowsResizesOnBothAxes() {
        float startWidth = first.recordedWidth();
        float startHeight = first.recordedHeight();
        desktop().keyboardMove().begin(first, WindowKeyboardMove.Mode.SIZE);

        withHeld(key -> key == CgKeyCodes.KEY_RIGHT,
                () -> window.routeKeyToKeyboardMove(CgKeyCodes.KEY_DOWN, false));
        settle();

        assertEquals(startWidth + WindowKeyboardMove.STEP, first.recordedWidth(), 0.01f);
        assertEquals(startHeight + WindowKeyboardMove.STEP, first.recordedHeight(), 0.01f);
    }

    /**
     * <b>Opposite arrows cancel</b> — the counter-assertion that gives the two above their meaning.
     *
     * <p>Without it, an implementation that simply moved on both axes whenever any arrow arrived would
     * satisfy them. It is also what a user holding Left and Right means.</p>
     */
    @Test
    public void oppositeArrowsCancel() {
        float startLeft = first.getWantedLeft();
        desktop().keyboardMove().begin(first, WindowKeyboardMove.Mode.MOVE);

        withHeld(key -> key == CgKeyCodes.KEY_LEFT,
                () -> window.routeKeyToKeyboardMove(CgKeyCodes.KEY_RIGHT, false));
        settle();

        assertEquals("two opposing arrows moved the window", startLeft, first.getWantedLeft(), 0.01f);
    }

    /**
     * Runs {@code body} with {@code down} deciding which keys report as held.
     *
     * <p>Through the platform's input slot because the held arrows are <b>polled</b> — an event names
     * one key, and the whole point is the keys nobody is sending events about.</p>
     */
    private void withHeld(java.util.function.IntPredicate down, Runnable body) {
        TestPlatformService.get().input(new CgInputService() {
            @Override public int getCurrentModifiers() { return 0; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return down.test(localKeyCode); }
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

    /** Shift is the fine step — a window is placed by eye, so the coarse one is the common case. */
    @Test
    public void shiftNudgesFinely() {
        float startLeft = first.getWantedLeft();
        desktop().keyboardMove().begin(first, WindowKeyboardMove.Mode.MOVE);

        window.routeKeyToKeyboardMove(CgKeyCodes.KEY_RIGHT, true);
        settle();

        assertEquals(startLeft + WindowKeyboardMove.FINE_STEP, first.getWantedLeft(), 0.01f);
    }

    /**
     * <b>A maximised window is refused.</b>
     *
     * <p>Its geometry belongs to the compositor, and nudging one would leave a window that claims to be
     * maximised and is not. Win32 greys its own Move and Size in exactly that state.</p>
     */
    @Test
    public void aMaximisedWindowCannotBeNudged() {
        first.maximize();
        settle();

        assertFalse("a maximised window accepted a keyboard move",
                desktop().keyboardMove().begin(first, WindowKeyboardMove.Mode.MOVE));

        Command move = CommandRegistry.global().get(WindowCommands.MOVE);
        assertNotNull(move);
        assertFalse("the menu offered Move on a maximised window",
                move.isEnabled(CommandContext.of(first)));
    }
}
