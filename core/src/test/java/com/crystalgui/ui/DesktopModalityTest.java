package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.elements.desktop.Desktop;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.input.UIInputHandler;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * CrystalOS W5 — modality scoped to the window ({@code plan_windowing.md}).
 *
 * <p>Before this, a modal made the <b>whole document</b> inert: a dialog in one window froze every
 * other window and the taskbar with it. On a desktop modality is per-application — a sheet blocks its
 * window (macOS), an owned dialog blocks its owner (Win32) — and CrystalOS's application is the frame.</p>
 *
 * <h3>One test per enforcement point, and that is not padding</h3>
 * <p>Inertness is enforced at <b>four</b> places on purpose — hit-testing, Tab scoping,
 * {@code requestFocus} and the top-layer hit-test skip — because the modal condition changes for
 * nearly every element the instant a modal opens, so anything cached that depended on one predicate
 * would need mass invalidation. The engine's own invariant table warns that a "simplify to one
 * predicate" refactor which misses one of them <em>still looks green</em>. So each gets its own test,
 * mirroring the four that already guard the unscoped version.</p>
 */
public class DesktopModalityTest extends UiTestBase {

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
        input.endFrame();
    }

    private void settle() {
        for (int pass = 0; pass < 2; pass++) {
            window.getStyleEngine().calculateStyle(0.016f);
            window.calculateLayout();
        }
    }

    /** A window with one focusable control, placed so the two fixtures never overlap. */
    private WindowFrame open(String title, float left) {
        WindowFrame frame = window.openWindow(new WindowFrame(title));
        frame.resizeTo(150, 110).moveTo(left, 20);
        frame.content().addChild(new Button(title));
        settle();
        return frame;
    }

    private Button buttonIn(WindowFrame frame) {
        // The slot is a scroll view whose bars come first in the raw list; ask for the CONTENT.
        return (Button) frame.content().describedChildren().get(0);
    }

    /** A modal dialog belonging to {@code frame} — parented inside it, which is what scopes it. */
    private Dialog openModalIn(WindowFrame frame) {
        Dialog dialog = new Dialog("Modal");
        window.addOverlay(dialog, frame.content());
        dialog.getContent().addChild(new Button("OK"));
        dialog.showModal();
        settle();
        return dialog;
    }

    private void press(float x, float y) {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
    }

    private void pressEscape() {
        input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(
                '\0', CgKeyCodes.KEY_ESCAPE, true, false, 0L));
    }

    private void pressTab() {
        input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(
                '\0', CgKeyCodes.KEY_TAB, true, false, 0L));
    }

    /**
     * Hit-tests an element's centre.
     *
     * <p>Two things a fixture has to get right here, and the first version got neither. The coordinates
     * are <b>surface</b> pixels — {@code getHoveredElement} is fed straight from the raw mouse position,
     * so a logical one lands at half the distance and quietly hits whatever is up and to the left. And
     * the <b>centre</b>, never a corner: a window's edges belong to its eight resize handles, which are
     * positioned over them and hit-test first.</p>
     */
    private UIElement hitCentreOf(UIElement element) {
        float x = element.getRuntimeCache().getX() + element.getRuntimeCache().getWidth() / 2f;
        float y = element.getRuntimeCache().getY() + element.getRuntimeCache().getHeight() / 2f;
        return window.getHoveredElement(x * 2f, y * 2f);
    }

    // ── The point of the whole thing ────────────────────────────────────────

    /** A dialog belongs to the window that opened it, not to the screen. */
    @Test
    public void aModalIsScopedToItsOwnWindow() {
        build();
        WindowFrame blocked = open("Blocked", 20);
        WindowFrame other = open("Other", 210);
        Dialog dialog = openModalIn(blocked);

        assertTrue("its own window is blocked", window.isModalBlocked(buttonIn(blocked)));
        assertFalse("the window beside it is not", window.isModalBlocked(buttonIn(other)));
        assertFalse("nor is the taskbar", window.isModalBlocked(desktop.taskbar()));
        assertFalse("nor the dialog itself", window.isModalBlocked(dialog));
    }

    /** A modal opened outside every window still blocks the whole screen — the behaviour this engine
     * had before frames existed, and what desktop chrome's own dialogs need. */
    @Test
    public void aModalOutsideEveryWindowStillBlocksEverything() {
        build();
        WindowFrame frame = open("One", 20);

        Dialog dialog = new Dialog("Screen modal");
        window.addOverlay(dialog, null);
        dialog.showModal();
        settle();

        assertTrue(window.isModalBlocked(buttonIn(frame)));
        assertTrue(window.isModalBlocked(desktop.taskbar()));
        assertFalse(window.isModalBlocked(dialog));
    }

    // ── Enforcement point 1: hit-testing ────────────────────────────────────

    /**
     * A blocked window answers <b>nothing</b> to the pointer, and does not fall through to whatever is
     * behind it — hit-testing an inert node acts as {@code pointer-events: none}, not as a hole.
     */
    @Test
    public void hitTestingSkipsABlockedWindowAndStillReachesTheOthers() {
        build();
        WindowFrame blocked = open("Blocked", 20);
        WindowFrame other = open("Other", 210);
        openModalIn(blocked);

        // A POINT THE DIALOG DOES NOT COVER, low in the blocked window and clear of its resize edges.
        // Aiming at the button's own centre proves nothing: the dialog sits over it, so the hit lands on
        // the dialog and is correct. What has to be true is that the window UNDER the modal answers
        // nothing anywhere.
        float x = blocked.getRuntimeCache().getX() + 14f;
        float y = blocked.getRuntimeCache().getY() + blocked.getRuntimeCache().getHeight() - 14f;
        assertNull("the blocked window is unhittable", window.getHoveredElement(x * 2f, y * 2f));
        assertSame("its neighbour is untouched", buttonIn(other), hitCentreOf(buttonIn(other)));
    }

    // ── Enforcement point 2: Tab ────────────────────────────────────────────

    /**
     * Tab is trapped inside the modal when focus is in the window it blocks, and — the half that is new
     * — never lands on blocked content when focus is somewhere else. {@code tabbable()} sees only the
     * inert <em>attribute</em>, deliberately, so nothing in the walk itself objects.
     */
    @Test
    public void tabNeverLandsOnBlockedContent() {
        build();
        WindowFrame blocked = open("Blocked", 20);
        WindowFrame other = open("Other", 210);
        Dialog dialog = openModalIn(blocked);

        // Focus starts inside the dialog (the focusing steps put it there). Tab must stay in it.
        for (int i = 0; i < 6; i++) {
            pressTab();
            assertFalse("Tab escaped the modal onto blocked content",
                    window.isModalBlocked(input.getFocusedElement()));
        }

        // And from the unblocked window, Tab may roam but must never land inside the blocked one.
        input.requestFocus(buttonIn(other));
        for (int i = 0; i < 6; i++) {
            pressTab();
            assertFalse("Tab walked into the blocked window",
                    window.isModalBlocked(input.getFocusedElement()));
        }
        assertNotNull(dialog);
    }

    // ── Enforcement point 3: requestFocus ───────────────────────────────────

    @Test
    public void requestFocusRefusesBlockedContentAndAllowsTheRest() {
        build();
        WindowFrame blocked = open("Blocked", 20);
        WindowFrame other = open("Other", 210);
        openModalIn(blocked);

        UIElement before = input.getFocusedElement();
        input.requestFocus(buttonIn(blocked));
        assertSame("a programmatic focus call must respect a modal", before, input.getFocusedElement());

        input.requestFocus(buttonIn(other));
        assertSame("...and must not refuse anything else", buttonIn(other), input.getFocusedElement());
    }

    // ── Enforcement point 4: the top layer ──────────────────────────────────

    /** A promoted element belonging to blocked content stays blocked — a tooltip over a frozen window
     * must not remain clickable just because it sits in the top layer. */
    @Test
    public void aPromotedElementInsideABlockedWindowIsStillBlocked() {
        build();
        WindowFrame blocked = open("Blocked", 20);
        WindowFrame other = open("Other", 210);

        UIElement promotedInBlocked = new UIElement().layout(l -> l.width(30).height(20));
        window.addOverlay(promotedInBlocked, blocked.content());
        promotedInBlocked.addToTopLayer();

        UIElement promotedInOther = new UIElement().layout(l -> l.width(30).height(20));
        window.addOverlay(promotedInOther, other.content());
        promotedInOther.addToTopLayer();

        openModalIn(blocked);

        assertTrue(window.isModalBlocked(promotedInBlocked));
        assertFalse(window.isModalBlocked(promotedInOther));
    }

    // ── Owned, not promoted ─────────────────────────────────────────────────

    /**
     * <b>A modal lives inside the window that opened it.</b> Win32's rule: an owned window stays above
     * its owner and travels with it. In the global top layer it would paint above every window — so
     * raising another one would leave the dialog floating over the wrong window.
     */
    @Test
    public void aWindowsModalIsParentedInThatWindow() {
        build();
        WindowFrame frame = open("One", 20);
        Dialog dialog = openModalIn(frame);

        assertSame("parented on the owner's own surface", frame.overlaySlot(), dialog.getParent());
        assertFalse("and NOT promoted to the global top layer", dialog.isInTopLayer());
        assertTrue(frame.hasOwnedWindows());
    }

    /**
     * The owned surface only has a box while something is showing on it. A full-size slot hit-tests, so
     * one left open with nothing in it swallows every click on the window's own content.
     */
    @Test
    public void theOwnedSurfaceTakesNoSpaceOnceTheDialogCloses() {
        build();
        WindowFrame frame = open("One", 20);
        Dialog dialog = openModalIn(frame);
        settle();
        assertTrue(frame.overlaySlot().getRuntimeCache().getWidth() > 0f);

        dialog.close();
        settle();

        assertFalse(frame.hasOwnedWindows());
        assertSame("the dialog stays in the tree, ready to be shown again",
                frame.overlaySlot(), dialog.getParent());
        assertTrue("but the surface lets go of its box",
                frame.overlaySlot().getRuntimeCache().getWidth() <= 0.01f);
        assertSame("so the window's own content is hittable again",
                buttonIn(frame), hitCentreOf(buttonIn(frame)));
    }

    // ── Escape ──────────────────────────────────────────────────────────────

    /**
     * <b>Escape is asked of the ACTIVE window's cascade.</b> One global stack closes whatever was
     * opened last anywhere — so a dialog left open in a background window would swallow the Escape
     * aimed at the window in front.
     */
    @Test
    public void escapeAsksTheActiveWindowsCascadeFirst() {
        build();
        WindowFrame background = open("Background", 20);
        Dialog stale = openModalIn(background);

        WindowFrame front = open("Front", 210);
        desktop.activate(front);
        settle();

        pressEscape();
        settle();

        assertTrue("the background window's dialog is untouched", stale.isOpen());
        // AND THE FRONT WINDOW DOES NOT TAKE IT EITHER, which is the half that changed: a window is no
        // longer its own close watcher, so with nothing transient open in it the Escape falls through to
        // the host. This used to assert the window had closed itself.
        assertNotNull("a window closed itself on Escape", front.getAttachedWindow());
    }

    /**
     * <b>Escape closes a window's modal, and then stops — it does not go on to close the window.</b>
     *
     * <h3>The second half is a deliberate reversal, not a regression</h3>
     *
     * <p>This asserted that a second Escape "reaches the window's own policy" and put the window away.
     * That is not what Escape means anywhere: it dismisses what is TRANSIENT — a menu, a popover, a
     * dialog, a live drag — and no desktop closes an application window with it. Windows and GNOME want
     * Alt+F4, macOS wants Cmd+W, and IntelliJ spends plain Escape on returning focus to the editor and
     * asks for Shift+Escape before it will even hide a tool window.</p>
     *
     * <p>It surfaced the moment the compositor became somewhere to live rather than a frame around one
     * application: Escape in the editor put the editor away and a second Escape left the desktop — two
     * presses to get out, the first of which did something nobody asked for. In a Minecraft host the two
     * rules then agree, which is what makes this right rather than merely conventional: Escape means
     * "give me the game back", and one press now does it from anywhere on the desktop.</p>
     */
    @Test
    public void escapeClosesAWindowsModalAndStopsThere() {
        build();
        WindowFrame frame = open("One", 20);
        Dialog dialog = openModalIn(frame);

        pressEscape();
        settle();
        assertFalse("the modal goes first", dialog.isOpen());
        assertNotNull("and the window is still there", frame.getAttachedWindow());

        pressEscape();
        settle();
        assertNotNull("a second Escape closed the window itself", frame.getAttachedWindow());
    }
}
