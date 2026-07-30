package com.crystalgui.ui;

import com.crystalgui.core.input.SystemInput;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgui.ui.tree.UITreeTraversal;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The HTML {@code inert} attribute — "makes this subtree non-interactive without hiding it".
 *
 * <p>Three behaviours, and all three have to hold together or the primitive is worse than useless:
 * inert content must be <b>unhittable</b> (the spec: hit-testing "must act as if the
 * {@code pointer-events} CSS property were set to {@code none}"), <b>unfocusable</b>, and <b>outside the
 * tab sequence</b>. What it must <em>not</em> do is stop laying out or painting — that is
 * {@code display: none}, and the whole reason both exist is that they differ.</p>
 *
 * <p>Modal blocking — the other half of the spec's inertness definition — is covered in
 * {@code ModalDialogTest}, because it is enforced at different points on purpose (see
 * {@link UIElement#isInert()}).</p>
 */
public class InertTest extends UiTestBase {

    private UIWindow window;
    private UIInputHandler input;
    private UIElement root;
    private UIElement outside;
    private UIElement box;
    private UIElement inside;

    /** Two 100x100 columns; {@code box} has a focusable child, so subtree behaviour is observable. */
    @Before
    public void build() {
        root = new UIElement().layout(l -> l.width(400).height(400));

        box = new UIElement().layout(l -> l.width(100).height(100));
        box.setId("box");
        box.setFocusPolicy(FocusPolicy.CLICK);
        inside = new UIElement().layout(l -> l.width(50).height(50));
        inside.setId("inside");
        inside.setFocusPolicy(FocusPolicy.CLICK);
        box.addChild(inside);

        outside = new UIElement().layout(l -> l.width(100).height(100));
        outside.setId("outside");
        outside.setFocusPolicy(FocusPolicy.CLICK);

        root.addChild(box);
        root.addChild(outside);

        window = new UIWindow(Ui.of(root));
        window.init(800, 800); // uiScale 2
        settle();
        input = window.getInputHandler();
        input.beginFrame();
        input.endFrame(); // firstFrameOver — consumeMouseEvent drops everything before this
    }

    private void settle() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }

    private UIElement hoverAt(float logicalX, float logicalY) {
        return window.getHoveredElement(logicalX * 2f, logicalY * 2f);
    }

    // ── Hit testing ─────────────────────────────────────────────────────────

    @Test
    public void inertIsUnhittableAndTakesItsSubtreeWithIt() {
        assertSame("baseline: the child is hittable before anything is inert", inside, hoverAt(10, 10));

        box.setInert(true);
        settle();

        // Falls THROUGH to the root rather than returning nothing, and that distinction is the spec:
        // `pointer-events: none` makes the pointer pass over a node to whatever is behind it, it does not
        // punch a hole in the document. Asserting null here would have been asserting the wrong thing.
        assertSame("the inert element must not be hit...", root, hoverAt(10, 10));
        assertSame("...nor its child, which carries no flag of its own", root, hoverAt(80, 80));
    }

    /** Inertness only propagates down. Marking a child must not make its parent unhittable. */
    @Test
    public void inertDoesNotLeakUpwards() {
        inside.setInert(true);
        settle();

        assertSame("the parent stays hittable", box, hoverAt(10, 10));
        assertSame("and so does an unrelated sibling", outside, hoverAt(10, 150));
    }

    @Test
    public void unsettingInertRestoresEverything() {
        box.setInert(true);
        settle();
        assertSame(root, hoverAt(10, 10));

        box.setInert(false);
        settle();

        assertSame(inside, hoverAt(10, 10));
        assertTrue(inside.focusable());
    }

    // ── Focus ───────────────────────────────────────────────────────────────

    @Test
    public void inertIsNotFocusableAndNeitherAreItsDescendants() {
        box.setInert(true);
        settle();

        assertFalse(box.focusable());
        assertFalse("the flag is a subtree property, not an element one", inside.focusable());
        assertTrue("...and strictly a subtree one", outside.focusable());
    }

    @Test
    public void requestFocusIsIgnoredForInertContent() {
        box.setInert(true);
        settle();

        input.requestFocus(inside);
        assertNull("the web ignores focus() on an inert element", input.getFocusedElement());

        input.requestFocus(outside);
        assertSame(outside, input.getFocusedElement());
    }

    /** Focus already held when the subtree goes inert. The spec blurs it; at minimum it must not remain
     * reachable, which is what {@code focusable()} returning false expresses. */
    @Test
    public void anAlreadyFocusedElementBecomesUnfocusableWhenItGoesInert() {
        input.requestFocus(inside);
        assertSame(inside, input.getFocusedElement());

        box.setInert(true);
        settle();

        assertFalse(inside.focusable());
        assertNull("and Tab must not be able to come back to it",
                UITreeTraversal.firstTabbableIn(box));
    }

    @Test
    public void clickingInertContentDoesNotFocusIt() {
        box.setInert(true);
        settle();

        input.consumeMouseEvent(new SystemInput.Mouse.Event(20, 20, 0, 0, -1, false, 0f, -1L));
        input.beginFrame();
        input.endFrame();
        input.consumeMouseEvent(new SystemInput.Mouse.Event(20, 20, 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();

        assertNull(input.getFocusedElement());
    }

    // ── Tab sequence ────────────────────────────────────────────────────────

    @Test
    public void tabSkipsAnInertSubtreeEntirely() {
        box.setInert(true);
        settle();

        assertSame("Tab must land past the whole inert subtree", outside,
                UITreeTraversal.firstTabbableIn(root));
        assertNull("and there must be nothing to come back to inside it",
                UITreeTraversal.firstTabbableIn(box));
    }

    @Test
    public void shiftTabSkipsItToo() {
        box.setInert(true);
        settle();

        assertSame(outside, UITreeTraversal.lastTabbableIn(root));
        assertNull("nothing tabbable precedes `outside` any more",
                UITreeTraversal.previousTabbable(outside));
    }

    /** The focusable-descendant cache is what the walkers prune on, so it must react to the flag —
     * otherwise Tab keeps descending into a subtree that no longer has anything to offer. */
    @Test
    public void theFocusableDescendantCacheReactsToInert() {
        assertTrue(box.getRuntimeCache().hasFocusableDescendant.get());

        box.setInert(true);
        settle();

        assertFalse("setInert must invalidate the chain, not leave a stale true",
                box.getRuntimeCache().hasFocusableDescendant.get());
    }

    // ── What inert must NOT do ──────────────────────────────────────────────

    /**
     * The whole point of {@code inert} existing alongside {@code display: none}: it keeps its box.
     * If this ever fails, {@code inert} has become a worse spelling of hiding.
     */
    @Test
    public void inertContentStillLaysOutAndStillPaints() {
        float widthBefore = box.getRuntimeCache().getWidth();
        float outsideYBefore = outside.getRuntimeCache().getY();

        box.setInert(true);
        settle();

        assertEquals("still has its box", widthBefore, box.getRuntimeCache().getWidth(), 0.01f);
        assertEquals("still occupies space, so siblings do not reflow",
                outsideYBefore, outside.getRuntimeCache().getY(), 0.01f);
        assertNotEquals("and is emphatically not display:none",
                dev.vfyjxf.taffy.style.TaffyDisplay.NONE,
                box.getStyle().getTaffyBridge().style.display);
    }

    @Test
    public void inertIsOffByDefaultSoNothingExistingChanges() {
        assertFalse(box.isInertAttribute());
        assertFalse(box.isInert());
        assertTrue(box.focusable());
        assertSame(inside, hoverAt(10, 10));
    }

    /** {@code isInertAttribute()} is the element's own flag; {@code isInert()} is the spec predicate that
     * also answers for ancestors. Conflating them is how a subtree check silently becomes an element one. */
    @Test
    public void theAttributeAndThePredicateAreDifferentQuestions() {
        box.setInert(true);
        settle();

        assertTrue(box.isInertAttribute());
        assertTrue(box.isInert());
        assertFalse("the child carries no attribute of its own", inside.isInertAttribute());
        assertTrue("...but it is inert all the same", inside.isInert());
    }
}
