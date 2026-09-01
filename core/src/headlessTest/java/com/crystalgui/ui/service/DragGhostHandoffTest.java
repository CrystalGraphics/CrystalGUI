package com.crystalgui.ui.service;

import static com.crystalgui.ui.service.ServiceFixtures.release;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
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
        UINode source = new UINode();
        UINode ghost = new UINode();
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
        UINode source = new UINode();
        UINode ghost = new UINode();
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
