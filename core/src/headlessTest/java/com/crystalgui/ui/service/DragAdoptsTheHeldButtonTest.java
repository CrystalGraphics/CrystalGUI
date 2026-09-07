package com.crystalgui.ui.service;

import static com.crystalgui.ui.service.ServiceFixtures.at;
import static com.crystalgui.ui.service.ServiceFixtures.frame;
import static com.crystalgui.ui.service.ServiceFixtures.press;
import static com.crystalgui.ui.service.ServiceFixtures.release;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgraphics.platform.input.CgMouseCodes;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>A drag begun from a button other than the left still ends when that button comes up.</b>
 *
 * <p>{@code Drag.start}'s convenience overloads used to declare LEFT. A gesture begins from a mouse-down
 * handler and none of them checks the button, so a right press on a resize handle or a split view's
 * divider started a drag that then waited for a left release which was never coming: it stayed live with
 * nothing held, eating every move. Reported against both widgets, and a fault in neither.</p>
 */
public class DragAdoptsTheHeldButtonTest {

    private final List<String> log = new ArrayList<>();

    private Drag begin(UIDocument document, UIElement source) {
        return Drag.start(source, 20, 20, new Drag.Listener() {
            @Override
            public void onDragUpdate(float x, float y, float sx, float sy, float dx, float dy) {
            }

            @Override
            public void onDragEnd(float x, float y) {
                log.add("end");
            }
        });
    }

    /** The reported case: right press, right release, and the drag is over. */
    @Test
    public void aDragBegunFromTheRightButtonEndsOnIt() {
        UIDocument document = new UIDocument();
        UIElement source = at("source", 0, 0, 100, 100);
        document.append(source);
        frame(document);

        press(document, 20, 20, CgMouseCodes.RIGHT_BUTTON);
        Drag drag = begin(document, source);
        assertTrue(document.input().hasMode(drag));

        release(document, 20, 20, CgMouseCodes.RIGHT_BUTTON);
        assertEquals("the button that started it came up", List.of("end"), log);
    }

    /** And the ordinary case is untouched. */
    @Test
    public void aLeftDragStillEndsOnTheLeftButton() {
        UIDocument document = new UIDocument();
        UIElement source = at("source", 0, 0, 100, 100);
        document.append(source);
        frame(document);

        press(document, 20, 20, CgMouseCodes.LEFT_BUTTON);
        begin(document, source);
        release(document, 20, 20, CgMouseCodes.RIGHT_BUTTON);
        assertTrue("some other button coming up is not this drag's business", log.isEmpty());

        release(document, 20, 20, CgMouseCodes.LEFT_BUTTON);
        assertEquals(List.of("end"), log);
    }
}
