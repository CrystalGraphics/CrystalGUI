package com.crystalgui.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.dnd.DragGhost;
import org.junit.Test;

/**
 * A real {@link DragGhost} gets a box while its drag runs — which is the only thing "on screen" means.
 *
 * <p><b>Driven with a real ghost on purpose.</b> The same test over a plain {@code UINode} passes
 * against the bug: a bare node is hidden only by the {@code hidden} attribute, so showing it is
 * showing it. A {@code DragGhost} hides itself at construction as well, and the two halves used to
 * disagree — it hid with a cascade {@code display: none} and was shown by clearing the attribute, so
 * it came out promoted, unhidden, and still resolving {@code display: none}. The box tree gives no box
 * for either reason, so there was nothing to draw while every observable said it was being shown.</p>
 *
 * <p>That is why this asserts on the BOX rather than on {@code isDisplayed()} or {@code isPromoted()}:
 * a node can pass both and still not exist as far as the painter is concerned.</p>
 */
public class DragGhostShowsTest extends UiDocumentTestBase {

    @Test
    public void aGhostHasABoxWhileItsDragRuns() {
        UINode source = new UINode();
        document.append(source);
        DragGhost ghost = new DragGhost();
        document.append(ghost);
        frame();

        assertNull("a ghost takes a box before any drag has started", ghost.box());

        ghost.follow(document, null, "carrying");
        Drag drag = Drag.start(source, 10f, 10f, CgMouseCodes.LEFT_BUTTON, "payload",
                Drag.DEFAULT_THRESHOLD_PX, new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                    }
                });
        // PAST THE THRESHOLD, because a ghost belongs to a drag and a press that never moved is a click.
        drag.pointerMoved(60f, 60f);
        frame();

        assertNotNull("the ghost has no box while its drag runs, so there is nothing to draw",
                ghost.box());

        drag.cancel();
        frame();
        assertNull("the ghost kept its box after the drag ended", ghost.box());
        assertFalse("the ghost stayed promoted after the drag", document.isPromoted(ghost));
    }
}
