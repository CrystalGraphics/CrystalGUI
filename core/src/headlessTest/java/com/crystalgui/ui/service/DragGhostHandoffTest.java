package com.crystalgui.ui.service;

import static com.crystalgui.ui.service.ServiceFixtures.release;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import org.junit.Test;

/**
 * A ghost offered before its drag exists is picked up by the drag that follows.
 *
 * <p><b>The ordering is the whole of it.</b> A ghost is offered from the mouse-DOWN handler and the
 * drag is started from the same handler a few lines later, because a caller has to describe what is
 * being carried before there is a gesture carrying it — which is what {@code DragGhost}'s "call it
 * before startDrag" rule says. So at the moment the offer is made {@code input.mode(Drag.class)} is
 * null. {@code DragGhost.follow} knew that, said so in its own comment, and relied on
 * {@code Drag.start} re-reading the ghost; nothing did. Every drag in the application carried no
 * ghost, and there was no error to explain it.</p>
 */
public class DragGhostHandoffTest {

    @Test
    public void aGhostOfferedBeforeTheDragIsClaimedByIt() {
        UIDocument document = new UIDocument();
        UIElement source = new UIElement();
        UIElement ghost = new UIElement();
        document.append(source);
        document.append(ghost);

        // THE REAL ORDER: offer, then start. Reversing it passes against the bug.
        document.input().offerGhost(ghost, 3f, 4f);
        Drag drag = Drag.start(source, 10f, 10f, CgMouseCodes.LEFT_BUTTON, "payload", 4f,
                new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                    }
                });

        assertSame("the drag did not claim the ghost offered on the way down", ghost, drag.ghost());

        // ...AND IT IS ACTUALLY SHOWN. Claiming it is half the job: a ghost is `display: none` until
        // its drag, so it has NO BOX -- which is why the first version of `withGhost` read `ghost.box()`
        // there, found null, and skipped everything. Nothing displayed it and nothing promoted it, so
        // every drag in the application carried an attached ghost that was never on screen.
        ghost.setDisplayed(false);
        document.frame(0.016f, 100f, 100f);
        drag.pointerMoved(40f, 40f);
        assertTrue("the ghost was never displayed", ghost.isDisplayed());
        assertTrue("the ghost was never promoted to the top layer", document.isPromoted(ghost));
        // WHETHER IT ACTUALLY DRAWS IS ASSERTED ELSEWHERE, over a real DragGhost -- see
        // DragGhostShowsTest. A bare UIElement is hidden only by the `hidden` attribute, so asserting a
        // box here would pass against the bug that mattered: a DragGhost also hides itself in the
        // CASCADE, and clearing the attribute says nothing about that.

        // And it goes back to hidden when the gesture ends -- a ghost left displayed is a stray label
        // sitting in somebody's panel between drags.
        drag.cancel();
        assertFalse("the ghost stayed displayed after the drag", ghost.isDisplayed());
        assertFalse("the ghost stayed promoted after the drag", document.isPromoted(ghost));
    }

    /**
     * ...and an offer no drag claimed goes away with the press.
     *
     * <p>A ghost belongs to one gesture. Left pending, it would be handed to whatever drag started
     * next — which is how the old engine's controller let a ghost outlive its drag and turn up on an
     * unrelated screen, the reason its own rule is "register it per drag".</p>
     */
    @Test
    public void anUnclaimedOfferDoesNotSurviveThePress() {
        UIDocument document = new UIDocument();
        UIElement source = new UIElement();
        UIElement ghost = new UIElement();
        document.append(source);
        document.append(ghost);
        document.frame(0.016f, 100f, 100f);

        // A press that turns out to be an ordinary click: offered, never claimed.
        document.input().offerGhost(ghost, 0f, 0f);
        release(document, 10f, 10f);

        Drag later = Drag.start(source, 20f, 20f, CgMouseCodes.LEFT_BUTTON, "payload", 4f,
                new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                    }
                });
        assertNull("a ghost nobody claimed was handed to the next drag", later.ghost());
    }
}
