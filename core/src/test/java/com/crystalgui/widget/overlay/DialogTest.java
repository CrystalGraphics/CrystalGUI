package com.crystalgui.widget.overlay;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.property.visual.text.TextOverflow;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.widget.control.Button;
import com.crystalgui.ui.service.Input;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link Dialog} — a floating movable panel, the web's {@code <dialog>} in its <b>modeless</b> form.
 *
 * <p>Two halves with very different provenance, and the tests are grouped that way. The
 * <em>container</em> is specified — open/close, focus delegation, focus restore, Escape — and those
 * tests pin a port. <em>Moving</em> is specified nowhere; every draggable document on the web is library
 * code, so those tests pin a design decision instead.</p>
 *
 * <p>Modal is deliberately absent: {@code showModal()} makes everything outside the dialog
 * {@code inert}, and this engine has no inertness concept. That is a separate primitive.</p>
 */
public class DialogTest extends UiDocumentTestBase {

    private Input input;
    private UIElement root;
    private Dialog dialog;

    /**
     * One {@code @Before}, deliberately. JUnit 4 does not order multiple {@code @Before} methods, so
     * splitting "register the adapter" from "build the tree" is a coin flip — and
     * {@code Input}'s constructor dereferences the adapter immediately. Other tests here get
     * away with the split only because the registered {@code CgPlatformService} is static global state that
     * an earlier test class happened to fill in.
     */
    @Before
    public void build() {
        root = new UIElement().layout(l -> l.width(400).height(300));
        dialog = new Dialog("Panel");
        dialog.layout(l -> l.width(120).height(80));
        dialog.getTitleBar().layout(l -> l.height(16));
        root.append(dialog);

        document.append(root);
        settle();
        input = document.input();
        input.beginFrame();
        input.endFrame(); // firstFrameOver
    }

    private void settle() {
        frame();
    }

    private float left() {
        return (dialog.box().worldX() - root.box().worldX()) / uiScale();
    }
    private float top() {
        return (dialog.box().worldY() - root.box().worldY()) / uiScale();
    }



    private void escape() {
        input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(
                '\0', CgKeyCodes.KEY_ESCAPE, true, false, 3L));
    }

    /** Grabs the title bar at its current position and drags by a logical delta. */
    private void dragTitleBarBy(float dx, float dy) {
        dialog.show();
        settle();
        float hx = dialog.getTitleBar().box().worldX() + 4f * uiScale();
        float hy = dialog.getTitleBar().box().worldY() + 4f * uiScale();
        press(hx, hy);
        move(hx + dx * uiScale(), hy + dy * uiScale());
        settle();
    }

    // ── Container: the ported half ──────────────────────────────────────────

    /** A closed dialog is `display: none` — out of layout, unpainted, unhittable, in one property. */
    @Test
    public void aClosedDialogIsOutOfLayout() {
        assertFalse(dialog.isOpen());
        assertNull("a closed dialog is display:none, so it has no box at all",
                dialog.box());

        dialog.show();
        settle();

        assertTrue(dialog.isOpen());
        assertEquals(80f, dialog.box().height(), 0.5f);
    }

    /** Spec's focus order, as far as this engine expresses it: the focus delegate — the first
     * focusable descendant — else the dialog itself. */
    @Test
    public void showingFocusesTheFirstFocusableDescendant() {
        Button inside = new Button("ok");
        dialog.getContent().append(inside);
        settle();

        dialog.show();
        settle();

        assertSame("the focus delegate should take focus", inside, document.focus().focused());
    }

    @Test
    public void aDialogWithNothingFocusableFocusesItself() {
        dialog.show();
        settle();

        assertSame(dialog, document.focus().focused());
    }

    /** "If a previously focused element exists, focus returns to it" — without this, closing a
     * dialog drops the user's place in the page entirely. */
    @Test
    public void closingRestoresTheFocusThatPrecededIt() {
        Button outside = new Button("outside");
        root.append(outside);
        settle();
        document.focus().requestFocus(outside);

        dialog.show();
        settle();
        assertNotSame(outside, document.focus().focused());

        dialog.close();
        settle();

        assertSame("focus must go back where it was", outside, document.focus().focused());
    }

    /**
     * <b>Escape closes a modeless dialog while focus is inside it — a DELIBERATE divergence from the web.</b>
     *
     * <p>The HTML spec is the opposite: only {@code showModal()} establishes a close watcher, and a
     * modeless {@code <dialog>} ignores Escape entirely. This test asserted that, and it was right about
     * the spec.</p>
     *
     * <p>The divergence is a product decision. A browser dialog is a page element; ours is a floating
     * document in an IDE, and every floating document in every IDE closes on Escape. Making it modal to get
     * that back would be worse — modality makes the rest of the workbench inert, which is exactly what a
     * settings document watching its own change take effect must not do.</p>
     *
     * <p>The safeguard is that it is scoped to <b>focus</b> rather than to the document: a bubbling listener
     * only fires when the focused element is inside the dialog, so a modeless dialog cannot eat Escape
     * from the editor behind it. See {@code Dialog.installEscapeToClose}.</p>
     */
    public void escapeClosesAFocusedModelessDialog() {
        dialog.show();
        settle();
        document.focus().requestFocus(dialog);

        escape();

        assertFalse("Escape did not close a focused modeless dialog", dialog.isOpen());
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
        float cx = close.box().x() + 2f;
        float cy = close.box().y() + 2f;
        press(cx, cy);
        move(cx + 40f, cy + 40f);
        settle();

        assertFalse("a press on the close button must not begin dragging the dialog",
                document.input().mode(Drag.class) != null);
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
        assertThrows(RuntimeException.class, () -> dialog.append(new UIElement()));
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

        float hx = dialog.getTitleBar().box().worldX() + 4f * uiScale();
        float hy = dialog.getTitleBar().box().worldY() + 4f * uiScale();
        press(hx, hy);
        move(hx + 20f, hy);
        settle();
        move(hx + 40f, hy); // same drag, twice as far from the grab point
        settle();

        assertEquals("total travel must equal total drag, not the sum of per-frame deltas",
                60f, left(), 0.5f);
    }

    /**
     * Clamping is <b>ours</b> — no spec covers it, because the web has no movable document. It matches
     * OS document managers, and the alternative (proportional re-anchoring) can drift a document
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
        frame();
        frame();

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
        frame();

        dialog.close();
        settle();
        frame();

        dialog.show();
        frame(); // the frame that used to clobber it
        frame();

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
        frame();
        dialog.show();
        frame();
        settle();

        dragTitleBarBy(25f, 15f);

        assertEquals(85f, left(), 0.5f);
        assertEquals(55f, top(), 0.5f);
    }

    /**
     * Clicking the chrome activates the document — and the focus ring that follows must be reachable
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
        root.append(elsewhere);
        settle();
        document.focus().requestFocus(elsewhere);
        assertSame(elsewhere, document.focus().focused());

        float hx = dialog.getTitleBar().box().worldX() + 4f * uiScale();
        float hy = dialog.getTitleBar().box().worldY() + 4f * uiScale();
        press(hx, hy);

        assertSame("clicking a document's chrome should activate it", dialog, document.focus().focused());
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

    // ── Title chrome ────────────────────────────────────────────────────────

    /*
     * These two are the only tests here that install StyleSheet.DEFAULT, because they are the only ones
     * asserting on what the user-agent sheet DOES. Everything above sets its own geometry explicitly and
     * would change behaviour if the sheet were added to build(), so it stays local — and the sheet is
     * genuinely not automatic (see StyleSheet.DEFAULT's javadoc), which is exactly the trap: without it
     * a CSS assertion here quietly tests nothing and passes.
     */
    private Dialog withUserAgentSheet(String title) {
        root = new UIElement().layout(l -> l.width(400).height(300));
        dialog = new Dialog(title);
        dialog.layout(l -> l.width(120).height(80));
        root.append(dialog);

        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        // show() is not optional. A closed dialog is `display: none`, so every box in it measures 0 and
        // any "did it fit?" assertion passes against 0 <= 0 — which is exactly how the first version of
        // this test went green while the sheet it was checking was not even installed.
        dialog.show();
        settle();
        settle();
        return dialog;
    }

    /**
     * A short title is left <b>completely alone</b>, and a long one truncates with an ellipsis.
     *
     * <p>Both halves in one test on purpose, because the interesting failure is the pair: an earlier
     * version of this rule sized the label by shrinking it from its own intrinsic width, which meant a
     * title fitting by a fraction of a pixel truncated anyway and silently lost a real character to an
     * ellipsis it never needed. Asserting only that long titles truncate would have called that a pass.</p>
     *
     * <p>The label's width is now the leftover — bar content minus the close button — so it does not
     * depend on its own glyphs at all. That is the web's canonical `flex: 1 1 0; min-width: 0` recipe.</p>
     */
    @Test
    public void shortTitlesAreUntouchedAndLongOnesEllipsize() {
        Dialog d = withUserAgentSheet("Panel");
        UIText label = d.getTitleLabel();

        float barContent = d.getTitleBar().box().contentBoxWidth();
        float closeWidth = d.getCloseButton().box().width();
        assertEquals("the label must be exactly what is left of the bar",
                barContent - closeWidth, label.box().width(), 0.5f);
        assertEquals("a title that fits must not lose a character to an ellipsis",
                "Panel", label.displayedText());

        d.setTitle("a title far longer than one hundred and twenty pixels of dialog");
        settle();
        settle();

        String shown = label.displayedText();
        assertNotEquals("a title that cannot fit must be shortened", label.getText(), shown);
        assertTrue("...and must end in an ellipsis, was '" + shown + "'",
                shown.endsWith("…") || shown.endsWith("..."));
        assertTrue("the box itself never grows to fit the text",
                label.box().width() <= barContent - closeWidth + 0.5f);
    }

    /** The ellipsis is a default, not a policy — a caller who would rather see the whole title can turn
     * it off, which is what makes exposing the label worth doing. */
    @Test
    public void theTitleEllipsisIsOverridable() {
        Dialog d = withUserAgentSheet("Panel");
        assertEquals(TextOverflow.ELLIPSIS, d.getTitleLabel().getStyle().getGeneralGroup().textOverflow());

        d.getTitleLabel().generalStyle(g -> g.textOverflow(TextOverflow.CLIP));
        settle();

        assertEquals("an inline write outranks the user-agent sheet", TextOverflow.CLIP,
                d.getTitleLabel().getStyle().getGeneralGroup().textOverflow());
    }
}
