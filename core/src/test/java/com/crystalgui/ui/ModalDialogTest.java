package com.crystalgui.ui;

import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgui.ui.tree.UITreeTraversal;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@code dialog.showModal()} — the HTML spec's modal form.
 *
 * <p>Three things separate it from {@link Dialog#show()}, and the interesting part is that only one of
 * them is new machinery: it joins the <b>top layer</b> (already existed for tooltips), everything outside
 * it becomes <b>inert</b> (the new primitive), and Escape closes it through a <b>close watcher</b>.
 * <em>Focus trapping is not a fourth feature</em> — it falls out of inertness, which is why there is no
 * trap code anywhere.</p>
 *
 * <p>Modal blocking is enforced at three separate points rather than through one shared predicate, and
 * that split is deliberate (see {@link UIElement#isInert()}), so each point is pinned here
 * independently — a shared-predicate refactor that missed one would otherwise look green.</p>
 */
public class ModalDialogTest extends UiTestBase {

    private UIWindow window;
    private UIInputHandler input;
    private UIElement root;
    private UIElement outside;
    private Dialog dialog;
    private Button innerButton;

    @Before
    public void build() {
        root = new UIElement().layout(l -> l.width(400).height(300));

        outside = new UIElement().layout(l -> l.width(100).height(100));
        outside.setId("outside");
        outside.setFocusPolicy(FocusPolicy.CLICK);
        root.addChild(outside);

        dialog = new Dialog("Modal");
        dialog.layout(l -> l.width(120).height(80));
        // Moved clear of `outside`. A dialog is position: absolute with auto offsets until told
        // otherwise, which puts it at the origin — right on top of the element these tests probe for
        // blocked-ness, so every "outside is unreachable" assertion would pass by overlapping instead.
        dialog.moveTo(200f, 150f);
        innerButton = new Button("ok");
        innerButton.layout(l -> l.width(40).height(14));
        dialog.getContent().addChild(innerButton);
        root.addChild(dialog);

        window = new UIWindow(Ui.of(root));
        window.init(800, 600); // uiScale 2
        settle();
        input = window.getInputHandler();
        input.beginFrame();
        input.endFrame();
    }

    private void settle() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }

    private void escape() {
        input.consumeKeyboardEvent(new SystemInput.Keyboard.Event('\0', CgUiKeyCodes.KEY_ESCAPE, true, false, 0L));
        input.consumeKeyboardEvent(new SystemInput.Keyboard.Event('\0', CgUiKeyCodes.KEY_ESCAPE, false, false, 0L));
    }

    // ── Modality bookkeeping ────────────────────────────────────────────────

    @Test
    public void showModalPromotesAndRegistersAsTheActiveModal() {
        assertNull(window.getActiveModal());

        dialog.showModal();
        settle();

        assertTrue(dialog.isModal());
        assertSame(dialog, window.getActiveModal());
        assertTrue("modal dialogs join the top layer; modeless ones do not", dialog.isInTopLayer());
    }

    @Test
    public void plainShowDoesNoneOfThat() {
        dialog.show();
        settle();

        assertFalse(dialog.isModal());
        assertNull("a modeless dialog must not block anything", window.getActiveModal());
        assertFalse("...nor outrank all ordinary content, which is why editor panels use show()",
                dialog.isInTopLayer());
    }

    @Test
    public void closingClearsModalityAndDemotes() {
        dialog.showModal();
        settle();
        dialog.close();
        settle();

        assertFalse(dialog.isModal());
        assertNull(window.getActiveModal());
        assertFalse(dialog.isInTopLayer());
        assertTrue("the backdrop must leave with it", window.getTopLayer().isEmpty());
    }

    @Test
    public void showModalOnAnAlreadyModalDialogIsANoOp() {
        dialog.showModal();
        settle();
        dialog.showModal(); // must not double-push or throw

        assertSame(dialog, window.getActiveModal());
        assertEquals("no duplicate top-layer entries", 2, window.getTopLayer().elements().size());
    }

    /** The spec throws {@code InvalidStateError} for this rather than silently upgrading. */
    @Test
    public void showModalOnAModelessOpenDialogThrows() {
        dialog.show();
        settle();

        try {
            dialog.showModal();
            fail("expected an IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("modelessly"));
        }
    }

    @Test
    public void showModalOnADetachedDialogThrows() {
        Dialog detached = new Dialog("nowhere");
        try {
            detached.showModal();
            fail("expected an IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("attached"));
        }
    }

    // ── Enforcement point 1: inertness ──────────────────────────────────────

    @Test
    public void everythingOutsideAModalIsInert() {
        assertFalse(outside.isInert());

        dialog.showModal();
        settle();

        assertTrue("the spec makes the whole rest of the document inert", outside.isInert());
        assertFalse("the modal itself is the one carve-out", dialog.isInert());
        assertFalse("...and so is its content", innerButton.isInert());
    }

    /** Modal blocking is deliberately kept out of {@code focusable()} so no cached predicate depends on a
     * condition that changes for the entire tree at once. Pinned so the split is not "simplified" away. */
    @Test
    public void modalBlockingIsNotVisibleThroughFocusable() {
        dialog.showModal();
        settle();

        assertTrue("focusable() sees only the inert ATTRIBUTE, by design", outside.focusable());
        assertTrue("...while the full predicate sees the modal", outside.isInert());
    }

    // ── Enforcement point 2: hit testing ────────────────────────────────────

    @Test
    public void hitTestingCannotReachBehindAModal() {
        assertSame(outside, window.getHoveredElement(20f, 20f));

        dialog.showModal();
        settle();

        assertNull("the whole main tree is inert, so nothing there answers",
                window.getHoveredElement(20f, 20f));
    }

    @Test
    public void clickingOutsideAModalDoesNotMoveFocus() {
        dialog.showModal();
        settle();
        UIElement focusedBefore = input.getFocusedElement();

        input.consumeMouseEvent(new SystemInput.Mouse.Event(40, 40, 0, 0, -1, false, 0f, -1L));
        input.beginFrame();
        input.endFrame();
        input.consumeMouseEvent(new SystemInput.Mouse.Event(40, 40, 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();

        assertSame(focusedBefore, input.getFocusedElement());
        assertNotSame(outside, input.getFocusedElement());
    }

    @Test
    public void requestFocusOutsideAModalIsIgnored() {
        dialog.showModal();
        settle();

        input.requestFocus(outside);
        assertNotSame("a modal must not be escapable programmatically either",
                outside, input.getFocusedElement());
    }

    // ── Enforcement point 3: the focus trap ─────────────────────────────────

    /** There is no trap code — this passes because the tab sequence is scoped to the modal, which is what
     * "everything outside is inert" means for sequential navigation. */
    @Test
    public void tabCannotLeaveAModal() {
        dialog.showModal();
        settle();

        for (int i = 0; i < 6; i++) {
            input.consumeKeyboardEvent(
                    new SystemInput.Keyboard.Event('\0', CgUiKeyCodes.KEY_TAB, true, false, 0L));
            UIElement focused = input.getFocusedElement();
            assertNotNull("Tab must always land somewhere inside the modal", focused);
            assertFalse("Tab escaped the modal to " + focused.getId(), focused.isInert());
        }
    }

    @Test
    public void shiftTabCannotLeaveEither() {
        dialog.showModal();
        settle();

        assertSame("the scoped walk must stay inside", dialog,
                commonAncestorWithDialog(UITreeTraversal.lastTabbableIn(dialog)));
        assertNull("and cannot climb out of the modal's own subtree",
                UITreeTraversal.previousTabbable(dialog, dialog));
    }

    private UIElement commonAncestorWithDialog(UIElement element) {
        return UITreeTraversal.commonAncestor(element, dialog);
    }

    @Test
    public void tabIsUnscopedAgainOnceTheModalCloses() {
        dialog.showModal();
        settle();
        dialog.close();
        settle();

        assertFalse(outside.isInert());
        assertSame("the document is whole again", outside, UITreeTraversal.firstTabbableIn(root));
    }

    // ── The close watcher ───────────────────────────────────────────────────

    @Test
    public void escapeClosesAModal() {
        dialog.showModal();
        settle();

        escape();

        assertFalse(dialog.isOpen());
        assertNull(window.getActiveModal());
    }

    /** Escape on a modeless dialog must do nothing — it establishes no close watcher, and browsers behave
     * the same way. Pinned because "Escape closes dialogs" is the intuitive-but-wrong expectation. */
    @Test
    public void escapeDoesNotCloseAModelessDialog() {
        dialog.show();
        settle();

        escape();

        assertTrue("only showModal() establishes a close watcher", dialog.isOpen());
    }

    @Test
    public void theCancelEventCanKeepTheDialogOpen() {
        dialog.showModal();
        settle();

        boolean[] fired = { false };
        dialog.onCancel.attachListener((el, event) -> {
            fired[0] = true;
            event.preventDefault();
        }, false, false);

        escape();

        assertTrue("cancel must fire before closing", fired[0]);
        assertTrue("preventDefault() must keep it open", dialog.isOpen());
        assertSame(dialog, window.getActiveModal());
    }

    @Test
    public void anUnpreventedCancelStillCloses() {
        dialog.showModal();
        settle();

        boolean[] fired = { false };
        dialog.onCancel.attachListener((el, event) -> fired[0] = true, false, false);

        escape();

        assertTrue(fired[0]);
        assertFalse(dialog.isOpen());
    }

    /**
     * The ordering hazard flagged when this was researched: Escape already cancels a drag, and a drag is
     * the innermost live interaction, so it must win.
     */
    @Test
    public void aLiveDragEatsEscapeBeforeTheModalDoes() {
        dialog.showModal();
        settle();

        input.getDragController().startDrag(innerButton, 0f, 0f, (mx, my, sx, sy, dx, dy) -> { });
        assertTrue("precondition: a drag is running", input.getDragController().isDragging());

        escape();

        assertFalse("the drag must be the thing that got cancelled", input.getDragController().isDragging());
        assertTrue("...and the modal must survive it", dialog.isOpen());
    }

    // ── Nesting ─────────────────────────────────────────────────────────────

    @Test
    public void aModalOverAModalBlocksItAndUnwindsInOrder() {
        Dialog second = new Dialog("second");
        second.layout(l -> l.width(100).height(60));
        dialog.getContent().addChild(second);

        dialog.showModal();
        settle();
        second.showModal();
        settle();

        assertSame(second, window.getActiveModal());
        assertTrue("the first modal is now blocked by the second", dialog.isInert());
        assertFalse(second.isInert());

        second.close();
        settle();

        assertSame("closing restores the one beneath", dialog, window.getActiveModal());
        assertFalse(dialog.isInert());
        assertTrue(outside.isInert());
    }

    // ── Focus delegate and restore ──────────────────────────────────────────

    @Test
    public void focusGoesToTheDelegateAndComesBackOnClose() {
        input.requestFocus(outside);
        assertSame(outside, input.getFocusedElement());

        dialog.showModal();
        settle();
        assertSame("the focus delegate is the first focusable descendant of the content",
                innerButton, input.getFocusedElement());

        dialog.close();
        settle();
        assertSame("closing returns focus to whatever held it", outside, input.getFocusedElement());
    }

    // ── The backdrop ────────────────────────────────────────────────────────

    @Test
    public void theBackdropSitsBeneathTheDialogAndIsNotInteractive() {
        dialog.showModal();
        settle();

        var promoted = window.getTopLayer().elements();
        assertEquals(2, promoted.size());
        assertTrue("the backdrop must be first, so it paints behind",
                promoted.get(0).hasClass(Dialog.BACKDROP_CLASS));
        assertSame(dialog, promoted.get(1));

        UIElement backdrop = promoted.get(0);
        assertFalse("decoration, not a control", backdrop.isHitTest());
        assertTrue(backdrop.isInertAttribute());
    }

    /**
     * Detaching a modal without closing it must clear its modality. Otherwise the stack keeps a reference
     * to an element that is no longer in the tree, and the whole window stays inert with nothing left to
     * interact with — unrecoverable from the user's side, which makes it worse than an ordinary leak.
     */
    @Test
    public void detachingAnOpenModalDoesNotWedgeTheWindow() {
        dialog.showModal();
        settle();
        assertTrue(outside.isInert());

        dialog.removeSelf();
        settle();

        assertNull("modality must not outlive the element", window.getActiveModal());
        assertFalse("the rest of the document has to come back to life", outside.isInert());
        assertSame(outside, window.getHoveredElement(20f, 20f));
    }

    /**
     * The backdrop covers the <b>whole window</b>, not the dialog it belongs to.
     *
     * <p>This is the assertion whose absence hid a bug dating back to P1: {@code UIWindow.rootNodeId} was a
     * field nothing ever assigned, so it was permanently null — and both of {@code TopLayer}'s reparenting
     * methods bail out silently on a null root. <b>Promotion never moved a Taffy node at all</b>, meaning
     * the documented divergence it exists to implement (a promoted element's containing block is the initial
     * containing block) was inert from the day it was written.</p>
     *
     * <p>Nothing caught it because every promoted element until now had an explicit pixel size and absolute
     * offsets, so the wrong percentage basis had nothing to show. The backdrop is the first promoted element
     * sized in {@code %}, and it came out the size of its dialog.</p>
     */
    @Test
    public void theBackdropCoversTheWholeWindowNotTheDialog() {
        // The one test here that needs StyleSheet.DEFAULT, because the backdrop's `100%` comes from it —
        // build() deliberately runs without a sheet so everything else asserts on explicit geometry.
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        Dialog d = dialog;
        d.showModal();
        settle();
        settle();

        UIElement backdrop = window.getTopLayer().elements().get(0);
        var rootCache = root.getRuntimeCache();

        assertEquals("full width of the containing block, which for a promoted element is the root",
                rootCache.getWidth(), backdrop.getRuntimeCache().getWidth(), 0.5f);
        assertEquals(rootCache.getHeight(), backdrop.getRuntimeCache().getHeight(), 0.5f);
        assertTrue("...and emphatically not the size of the dialog",
                backdrop.getRuntimeCache().getWidth() > d.getRuntimeCache().getWidth());
    }

    /**
     * The general form of the same thing, stated once so it is not only implied by the backdrop: promotion
     * really does make the root the containing block, so a percentage on a promoted element resolves
     * against the root rather than against its DOM parent.
     */
    @Test
    public void promotionReparentsToTheRootSoPercentagesResolveAgainstIt() {
        UIElement stage = new UIElement().layout(l -> l.width(120).height(60));
        root.addChild(stage);
        UIElement promoted = new UIElement();
        promoted.layout(l -> l.widthPercent(50).heightPercent(50));
        stage.addChild(promoted);
        settle();

        promoted.addToTopLayer();
        settle();
        settle();

        assertEquals("50% of the ROOT (400), not of its 120px DOM parent",
                200f, promoted.getRuntimeCache().getWidth(), 0.5f);
        assertEquals(150f, promoted.getRuntimeCache().getHeight(), 0.5f);
    }

    /**
     * A promoted modal is clamped to the <b>root</b>, not to its DOM parent.
     *
     * <p>The other half of the same trap, and the one reported from the harness as "the modal can't be moved
     * further than this": {@code left}/{@code top} on a promoted element resolve against the root, but
     * {@code getParent()} still answers with the DOM parent. Clamping against the wrong box stopped the drag
     * dead at that parent's edge with most of the window still free.</p>
     */
    @Test
    public void aPromotedModalCanBeMovedAcrossTheWholeWindow() {
        UIElement stage = new UIElement().layout(l -> l.width(140).height(60));
        root.addChild(stage);
        Dialog inStage = new Dialog("in a small stage");
        inStage.layout(l -> l.width(100).height(40));
        stage.addChild(inStage);
        settle();

        inStage.showModal();
        settle();
        inStage.moveTo(250f, 200f);
        settle();

        float left = inStage.getRuntimeCache().getX() - root.getRuntimeCache().getX();
        float top = inStage.getRuntimeCache().getY() - root.getRuntimeCache().getY();
        assertEquals("must not be clamped to the 140px stage it happens to be a child of",
                250f, left, 0.5f);
        assertEquals(200f, top, 0.5f);
    }

    /** ...while a modeless one is still clamped to its DOM parent, because that genuinely is its containing
     * block. The two cases must not be collapsed. */
    @Test
    public void aModelessDialogIsStillClampedToItsDomParent() {
        UIElement stage = new UIElement().layout(l -> l.width(140).height(60));
        root.addChild(stage);
        Dialog inStage = new Dialog("in a small stage");
        inStage.layout(l -> l.width(100).height(40));
        stage.addChild(inStage);
        settle();

        inStage.show();
        settle();
        inStage.moveTo(250f, 200f);
        settle();

        float left = inStage.getRuntimeCache().getX() - stage.getRuntimeCache().getX();
        assertEquals("clamped to 140 - 100", 40f, left, 0.5f);
    }

    /**
     * After a modal has been shown once, going back to {@link Dialog#show()} must not leave a backdrop behind.
     *
     * <p>The backdrop is built lazily and then <b>kept</b> as an internal child. Demotion drops the
     * {@code position: absolute} the top layer forced, which turned it back into an ordinary in-flow child
     * sized {@code 100%} of the <em>dialog</em> — a dark panel painted over the dialog's own content and
     * spilling out below it. Every modeless dialog opened after any modal looked like that.</p>
     */
    @Test
    public void aBackdropDoesNotSurviveIntoAModelessReopen() {
        dialog.showModal();
        settle();
        UIElement backdrop = window.getTopLayer().elements().get(0);
        assertTrue(backdrop.hasClass(Dialog.BACKDROP_CLASS));
        assertNotEquals(TaffyDisplay.NONE, backdrop.getStyle().getTaffyBridge().style.display);

        dialog.close();
        settle();
        dialog.show();
        settle();

        assertTrue("modeless, so nothing may be promoted", window.getTopLayer().isEmpty());
        assertEquals("the kept backdrop must be hidden, not left in flow inside the dialog",
                TaffyDisplay.NONE, backdrop.getStyle().getTaffyBridge().style.display);
    }

    /** ...and showing it modally again brings the same backdrop back. */
    @Test
    public void reShowingModallyRestoresTheBackdrop() {
        dialog.showModal();
        settle();
        dialog.close();
        settle();
        dialog.showModal();
        settle();

        UIElement backdrop = window.getTopLayer().elements().get(0);
        assertTrue(backdrop.hasClass(Dialog.BACKDROP_CLASS));
        assertNotEquals(TaffyDisplay.NONE, backdrop.getStyle().getTaffyBridge().style.display);
    }

    @Test
    public void aModelessDialogNeverBuildsABackdrop() {
        dialog.show();
        settle();

        assertTrue("built lazily, and most dialogs are modeless", window.getTopLayer().isEmpty());
    }
}
