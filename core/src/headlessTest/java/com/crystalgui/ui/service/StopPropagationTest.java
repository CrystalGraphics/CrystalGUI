package com.crystalgui.ui.service;

import static com.crystalgui.ui.service.ServiceFixtures.at;
import static com.crystalgui.ui.service.ServiceFixtures.frame;
import static com.crystalgui.ui.service.ServiceFixtures.on;
import static com.crystalgui.ui.service.ServiceFixtures.press;
import static org.junit.Assert.assertEquals;

import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.event.PropagationPhase;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector2f;
import org.junit.Test;

/**
 * DOM propagation, with the old engine's behaviour as the counter-assertion.
 *
 * <p>{@code stopPropagation()} ends the WALK; the remaining listeners on the SAME node still run.
 * The old dispatcher treats it as {@code stopImmediatePropagation}, which is the defect behind
 * "a listener attached to a widget's own event group after its constructor may never run" — the
 * Run console's stack-frame links could not see a press at all, because {@code TextEditor}'s own
 * mouse-down handler ends with an unconditional {@code stopPropagation()} and a widget always
 * subscribes first, in its constructor.</p>
 *
 * <p>The symptom was the opposite of the cause: the caret still moved and double-click still
 * selected a word, so events were plainly arriving, and two rounds of diagnosis went to the click's
 * coordinates before anyone looked at the phase.</p>
 */
public class StopPropagationTest {

    private static final ReadOnlyVec2f ORIGIN = new ReadOnlyVec2f(new Vector2f());

    @Test
    public void stopPropagationEndsTheWalkAndNotTheNodesOwnListeners() {
        UIDocument document = new UIDocument();
        UIElement parent = at("parent", 0, 0, 200, 200);
        UIElement child = at("child", 10, 10, 100, 100);
        parent.append(child);
        document.append(parent);
        frame(document);
        List<String> log = new ArrayList<>();

        on(child, MouseEvent.Down.class, (n, e) -> {
            log.add("widget-own");
            e.stopPropagation();
        });
        on(child, MouseEvent.Down.class, (n, e) -> log.add("attached-later"));
        // On the BUBBLE phase: an ancestor listening in capture hears the press on its way DOWN,
        // before the target, which stopPropagation cannot retroactively undo.
        parent.events.getGroup(MouseEvent.Down.class).attachListener((n, e) -> log.add("ancestor"), false, true);

        press(document, 50, 50);
        assertEquals("the second listener on the same node still runs; the ancestor does not",
                List.of("widget-own", "attached-later"), log);
    }

    @Test
    public void stopImmediatePropagationIsWhatEndsThem() {
        UIDocument document = new UIDocument();
        UIElement parent = at("parent", 0, 0, 200, 200);
        UIElement child = at("child", 10, 10, 100, 100);
        parent.append(child);
        document.append(parent);
        frame(document);
        List<String> log = new ArrayList<>();

        on(child, MouseEvent.Down.class, (n, e) -> {
            log.add("first");
            e.stopImmediatePropagation();
        });
        on(child, MouseEvent.Down.class, (n, e) -> log.add("second"));
        // On the BUBBLE phase: an ancestor listening in capture hears the press on its way DOWN,
        // before the target, which stopPropagation cannot retroactively undo.
        parent.events.getGroup(MouseEvent.Down.class).attachListener((n, e) -> log.add("ancestor"), false, true);

        press(document, 50, 50);
        assertEquals(List.of("first"), log);
    }

    @Test
    public void theOldDispatcherConflatesThem() {
        // The counter-assertion, driven through the OLD emit path on the same shared group: without
        // it, "the new one is right" is a claim about one implementation rather than a difference.
        UIElement node = at("node", 0, 0, 100, 100);
        List<String> log = new ArrayList<>();
        on(node, MouseEvent.Down.class, (n, e) -> {
            log.add("first");
            e.stopPropagation();
        });
        on(node, MouseEvent.Down.class, (n, e) -> log.add("second"));

        MouseEvent.Down event = new MouseEvent.Down(node, ORIGIN, 0, 1);
        event.setPhase(PropagationPhase.TARGET);
        node.events.emitToGroup(event);
        assertEquals("the old path stops the whole phase -- which is what M6 deletes with it",
                List.of("first"), log);
    }

    @Test
    public void aCapturingAncestorCanStopAnEventBeforeItsTarget() {
        UIDocument document = new UIDocument();
        UIElement parent = at("parent", 0, 0, 200, 200);
        UIElement child = at("child", 10, 10, 100, 100);
        parent.append(child);
        document.append(parent);
        frame(document);
        List<String> log = new ArrayList<>();

        parent.events.getGroup(MouseEvent.Down.class).attachListener((n, e) -> {
            log.add("capture");
            e.stopPropagation();
        }, true, false);
        on(child, MouseEvent.Down.class, (n, e) -> log.add("target"));

        press(document, 50, 50);
        assertEquals("a gesture that must work ANYWHERE inside a container takes the press in the "
                + "capture phase -- the bubble phase sees only what nothing else consumed",
                List.of("capture"), log);
    }
}
