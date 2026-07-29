package com.crystalgui.ui;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link Dialog} — a floating movable panel, the web's {@code <dialog>} in its <b>modeless</b> form.
 *
 * <p>Two halves with very different provenance, and the tests are grouped that way. The
 * <em>container</em> is specified — open/close, focus delegation, focus restore, Escape — and those
 * tests pin a port. <em>Moving</em> is specified nowhere; every draggable window on the web is library
 * code, so those tests pin a design decision instead.</p>
 *
 * <p>Modal is deliberately absent: {@code showModal()} makes everything outside the dialog
 * {@code inert}, and this engine has no inertness concept. That is a separate primitive.</p>
 */
public class DialogTest extends UiTestBase {

    private UIWindow window;
    private UIInputHandler input;
    private UIElement root;
    private Dialog dialog;

    /**
     * One {@code @Before}, deliberately. JUnit 4 does not order multiple {@code @Before} methods, so
     * splitting "register the adapter" from "build the tree" is a coin flip — and
     * {@code UIInputHandler}'s constructor dereferences the adapter immediately. Other tests here get
     * away with the split only because {@code CrystalGuiCore.setAdapter} is static global state that
     * an earlier test class happened to fill in.
     */
    @Before
    public void build() {
        root = new UIElement().layout(l -> l.width(400).height(300));
        dialog = new Dialog("Panel");
        dialog.layout(l -> l.width(120).height(80));
        dialog.getTitleBar().layout(l -> l.height(16));
        root.addChild(dialog);

        window = new UIWindow(Ui.of(root));
        window.init(800, 600); // uiScale 2
        settle();
        input = window.getInputHandler();
        input.beginFrame();
        input.endFrame(); // firstFrameOver
    }

    private void settle() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }

    private float left() { return dialog.getRuntimeCache().getX() - root.getRuntimeCache().getX(); }
    private float top()  { return dialog.getRuntimeCache().getY() - root.getRuntimeCache().getY(); }

    private void press(float x, float y) {
        input.consumeMouseEvent(new SystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
    }

    private void move(float x, float y) {
        input.consumeMouseEvent(new SystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, -1, false, 0f, -1L));
        input.beginFrame();
        input.endFrame();
    }

    private void escape() {
        input.consumeKeyboardEvent(new SystemInput.Keyboard.Event(
                '\0', CgUiKeyCodes.KEY_ESCAPE, true, false, 3L));
    }

    /** Grabs the title bar at its current position and drags by a logical delta. */
    private void dragTitleBarBy(float dx, float dy) {
        dialog.show();
        settle();
        float hx = dialog.getTitleBar().getRuntimeCache().getX() + 4f;
        float hy = dialog.getTitleBar().getRuntimeCache().getY() + 4f;
        press(hx, hy);
        move(hx + dx, hy + dy);
        settle();
    }

    // ── Container: the ported half ──────────────────────────────────────────

    /** A closed dialog is `display: none` — out of layout, unpainted, unhittable, in one property. */
    @Test
    public void aClosedDialogIsOutOfLayout() {
        assertFalse(dialog.isOpen());
        assertEquals(0f, dialog.getRuntimeCache().getHeight(), 0.001f);

        dialog.show();
        settle();

        assertTrue(dialog.isOpen());
        assertEquals(80f, dialog.getRuntimeCache().getHeight(), 0.5f);
    }

    /** Spec's focus order, as far as this engine expresses it: the focus delegate — the first
     * focusable descendant — else the dialog itself. */
    @Test
    public void showingFocusesTheFirstFocusableDescendant() {
        Button inside = new Button("ok");
        dialog.getContent().addChild(inside);
        settle();

        dialog.show();
        settle();

        assertSame("the focus delegate should take focus", inside, input.getFocusedElement());
    }

    @Test
    public void aDialogWithNothingFocusableFocusesItself() {
        dialog.show();
        settle();

        assertSame(dialog, input.getFocusedElement());
    }

    /** "If a previously focused element exists, focus returns to it" — without this, closing a
     * dialog drops the user's place in the page entirely. */
    @Test
    public void closingRestoresTheFocusThatPrecededIt() {
        Button outside = new Button("outside");
        root.addChild(outside);
        settle();
        input.requestFocus(outside);

        dialog.show();
        settle();
        assertNotSame(outside, input.getFocusedElement());

        dialog.close();
        settle();

        assertSame("focus must go back where it was", outside, input.getFocusedElement());
    }

    /**
     * <b>Escape must NOT close a modeless dialog.</b> Only {@code showModal()} "establishes a close
     * watcher" — the machinery that turns a close request into a {@code cancel} event and then a
     * close. {@code show()}'s algorithm has no close watcher, so browsers do not close a modeless
     * dialog on Escape either.
     *
     * <p>An earlier revision did close on Escape, and only when focus happened to be inside the
     * dialog — neither the web's behaviour nor a coherent one of its own. Pinned as an assertion so
     * it does not get "helpfully" re-added; Escape-to-close arrives with modal support or not at all.</p>
     */
    @Test
    public void escapeDoesNotCloseAModelessDialog() {
        dialog.show();
        settle();
        input.requestFocus(dialog);

        escape();

        assertTrue("modeless dialogs have no close watcher", dialog.isOpen());
    }

    /** The affordance a floating panel actually needs. Browsers ship no dialog chrome at all, so this
     * is ours — but a panel with a draggable title bar and no way to dismiss it is just broken. */
    @Test
    public void theCloseButtonClosesTheDialog() {
        dialog.show();
        settle();

        dialog.getCloseButton().onPressed.emit();

        assertFalse(dialog.isOpen());
    }

    /** Clicking the close button must not also start a move: the title bar listens on its own target
     * phase only, so an event whose target is the button never reaches it. */
    @Test
    public void pressingCloseDoesNotStartAMove() {
        dialog.moveTo(20f, 20f);
        dialog.show();
        settle();

        UIElement close = dialog.getCloseButton();
        float cx = close.getRuntimeCache().getX() + 2f;
        float cy = close.getRuntimeCache().getY() + 2f;
        press(cx, cy);
        move(cx + 40f, cy + 40f);
        settle();

        assertFalse("a press on the close button must not begin dragging the dialog",
                input.getDragController().isDragging());
    }

    @Test
    public void closingEmitsOnClosedOnceAndIsIdempotent() {
        int[] closes = {0};
        dialog.onClosed.connect(() -> closes[0]++);
        dialog.show();
        settle();

        dialog.close();
        dialog.close();

        assertEquals("a second close must be a no-op", 1, closes[0]);
    }

    /** A dialog owns its structure; content goes in the named slot, like every other composite here. */
    @Test
    public void aDialogRefusesPublicChildren() {
        assertFalse(dialog.acceptsPublicChildren());
        assertThrows(RuntimeException.class, () -> dialog.addChild(new UIElement()));
    }

    // ── Moving: the designed half ───────────────────────────────────────────

    @Test
    public void draggingTheTitleBarMovesTheDialog() {
        dialog.moveTo(20f, 20f);
        settle();

        dragTitleBarBy(40f, 30f);

        assertEquals(60f, left(), 0.5f);
        assertEquals(50f, top(), 0.5f);
    }

    /** Accumulates from the position at grab time — reading the live box each frame would compound
     * the delta and the dialog would race away from the cursor. */
    @Test
    public void movingIsRelativeToTheGrabPositionNotCompounded() {
        dialog.moveTo(20f, 20f);
        settle();
        dialog.show();
        settle();

        float hx = dialog.getTitleBar().getRuntimeCache().getX() + 4f;
        float hy = dialog.getTitleBar().getRuntimeCache().getY() + 4f;
        press(hx, hy);
        move(hx + 20f, hy);
        settle();
        move(hx + 40f, hy); // same drag, twice as far from the grab point
        settle();

        assertEquals("total travel must equal total drag, not the sum of per-frame deltas",
                60f, left(), 0.5f);
    }

    /**
     * Clamping is <b>ours</b> — no spec covers it, because the web has no movable window. It matches
     * OS window managers, and the alternative (proportional re-anchoring) can drift a window
     * somewhere the user never put it.
     */
    @Test
    public void aDialogCannotBeDraggedOutOfItsContainer() {
        dialog.moveTo(0f, 0f);
        settle();

        dragTitleBarBy(9999f, 9999f);

        assertEquals("clamped to the right edge", 400f - 120f, left(), 0.5f);
        assertEquals("clamped to the bottom edge", 300f - 80f, top(), 0.5f);
    }

    @Test
    public void aDialogCannotBeDraggedAboveOrLeftOfItsContainer() {
        dialog.moveTo(30f, 30f);
        settle();

        dragTitleBarBy(-9999f, -9999f);

        assertEquals(0f, left(), 0.5f);
        assertEquals(0f, top(), 0.5f);
    }

    /** A dialog parked near an edge must stay reachable when the container shrinks under it. */
    @Test
    public void shrinkingTheContainerReClampsTheDialog() {
        dialog.show();
        dialog.moveTo(280f, 220f); // hard against the bottom-right of a 400x300 root
        settle();

        root.layout(l -> l.width(200).height(150));
        // updateWithoutPainting, not settle(): re-clamping is carried by a per-frame ticker, because
        // shrinking the CONTAINER does not change this element's own box and so never fires its
        // layout callback. settle() runs style + layout but no tickers, so it cannot see this.
        window.updateWithoutPainting();
        window.updateWithoutPainting();

        assertTrue("must not be stranded outside the shrunken container, was " + left(),
                left() + 120f <= 200f + 0.5f);
        assertTrue("…vertically too, was " + top(), top() + 80f <= 150f + 0.5f);
    }

    /**
     * <b>Reopening must not move the dialog.</b>
     *
     * <p>The clamp ticker runs during {@code advanceFrame}, which is <em>before</em>
     * {@code calculateLayout} — so on the first frame after a reopen the box is still the zero-sized
     * {@code display: none} one. An earlier revision derived the position by reading that box back,
     * so every reopened dialog snapped to (0,0). With two of them stacked exactly on top of each
     * other, only the upper one appeared to drag and the other looked frozen.</p>
     *
     * <p>The position is a field now, never re-derived from resolved geometry.</p>
     */
    @Test
    public void reopeningKeepsThePositionItWasClosedAt() {
        dialog.moveTo(60f, 40f);
        dialog.show();
        settle();
        window.updateWithoutPainting();

        dialog.close();
        settle();
        window.updateWithoutPainting();

        dialog.show();
        window.updateWithoutPainting(); // the frame that used to clobber it
        window.updateWithoutPainting();

        assertEquals("a reopened dialog must stay where it was", 60f, left(), 0.5f);
        assertEquals(40f, top(), 0.5f);
    }

    /** And it must still be movable afterwards — the symptom that surfaced this. */
    @Test
    public void aReopenedDialogIsStillDraggable() {
        dialog.moveTo(60f, 40f);
        dialog.show();
        settle();
        dialog.close();
        settle();
        window.updateWithoutPainting();
        dialog.show();
        window.updateWithoutPainting();
        settle();

        dragTitleBarBy(25f, 15f);

        assertEquals(85f, left(), 0.5f);
        assertEquals(55f, top(), 0.5f);
    }

    /**
     * Clicking the chrome activates the window — and the focus ring that follows must be reachable
     * by hand.
     *
     * <p>{@code show()} and {@code close()} both move focus programmatically (the spec's focusing
     * steps, and its focus-restore on close), but {@code FocusPolicy.FOCUSABLE} excludes click. The
     * ring therefore appeared only when some <em>other</em> dialog closed and handed focus back,
     * which looked like a rendering glitch because nothing the user did could reproduce it.</p>
     */
    @Test
    public void clickingTheTitleBarFocusesTheDialog() {
        dialog.show();
        settle();
        Button elsewhere = new Button("elsewhere");
        root.addChild(elsewhere);
        settle();
        input.requestFocus(elsewhere);
        assertSame(elsewhere, input.getFocusedElement());

        float hx = dialog.getTitleBar().getRuntimeCache().getX() + 4f;
        float hy = dialog.getTitleBar().getRuntimeCache().getY() + 4f;
        press(hx, hy);

        assertSame("clicking a window's chrome should activate it", dialog, input.getFocusedElement());
    }

    // ── Origin, shared with resize ──────────────────────────────────────────

    /**
     * Position is written at {@code INLINE}, matching what CSS {@code resize} mandates for the size
     * it writes. One rule covers both: user-driven geometry is inline, so an author's
     * {@code !important} still wins.
     */
    @Test
    public void positionIsWrittenAtInlineOriginSoImportantStillWins() {
        dialog.moveTo(20f, 20f);
        settle();

        assertFalse("nothing may land at IMPORTANT",
                dialog.getStyle().containsCandidate(LayoutProperties.LEFT,
                        slot -> slot.origin() == StyleOrigin.IMPORTANT));

        StyleGroup.importantPipeline(dialog.getStyle().getLayoutGroup(), l -> l.left(5));
        settle();
        dragTitleBarBy(50f, 0f);

        assertEquals("!important must pin the dialog against a user drag", 5f, left(), 0.5f);
    }
}
