package com.crystalgui.ui.service;

import static com.crystalgui.ui.service.ServiceFixtures.at;
import static com.crystalgui.ui.service.ServiceFixtures.frame;
import static com.crystalgui.ui.service.ServiceFixtures.key;
import static com.crystalgui.ui.service.ServiceFixtures.press;
import static com.crystalgui.ui.service.ServiceFixtures.release;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.headless.ClassReferences;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.input.FocusPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * The mode stack: a drag eats Escape before a switcher, which eats it before a modal grab — and the
 * input service names none of them.
 *
 * <p>That last clause is the whole point, and it is the one thing a behavioural test cannot show. The
 * old {@code consumeKeyboardEvent} opened with four hard-coded rungs — cancel the drag,
 * {@code routeKeyToWindowSwitcher}, {@code routeKeyToKeyboardMove}, the close watcher — so the order
 * lived nowhere but the order of the branches, and adding a fifth gesture meant editing the input
 * handler. Here the order IS the stack, so it is asserted twice: once by driving it, and once by
 * reading the service's constant pool for the widgets it must not know about.</p>
 */
public class ModeStackTest {

    /** A mode that records what it was offered and takes only what it is told to. */
    private static final class Recorder implements InputMode {
        private final String name;
        private final int takes;
        final List<String> log;
        boolean ended;

        Recorder(String name, int takes, List<String> log) {
            this.name = name;
            this.takes = takes;
            this.log = log;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean keyPressed(int key, int modifiers, boolean repeat) {
            log.add(name);
            return key == takes;
        }

        @Override
        public void ended() {
            ended = true;
        }
    }

    @Test
    public void theInnermostLiveInteractionIsAskedFirst() {
        UIDocument document = new UIDocument();
        document.append(at("content", 0, 0, 100, 100));
        frame(document);
        List<String> log = new ArrayList<>();

        Recorder modal = new Recorder("modal-grab", CgKeyCodes.KEY_ESCAPE, log);
        Recorder switcher = new Recorder("switcher", CgKeyCodes.KEY_ESCAPE, log);
        Recorder drag = new Recorder("drag", CgKeyCodes.KEY_ESCAPE, log);
        document.input().pushMode(modal);
        document.input().pushMode(switcher);
        document.input().pushMode(drag);

        assertTrue(key(document, CgKeyCodes.KEY_ESCAPE, true));
        assertEquals("the drag alone -- it took it before the switcher was offered anything",
                List.of("drag"), log);

        log.clear();
        document.input().popMode(drag);
        assertTrue(key(document, CgKeyCodes.KEY_ESCAPE, true));
        assertEquals("then the switcher, and the modal behind it still hears nothing",
                List.of("switcher"), log);

        log.clear();
        document.input().popMode(switcher);
        assertTrue(key(document, CgKeyCodes.KEY_ESCAPE, true));
        assertEquals(List.of("modal-grab"), log);
        assertTrue("a popped mode is told", drag.ended && switcher.ended);
    }

    @Test
    public void aModeThatDoesNotWantAKeyLetsItThrough() {
        UIDocument document = new UIDocument();
        UIElement node = at("node", 0, 0, 100, 100).setFocusPolicy(FocusPolicy.FOCUSABLE);
        document.append(node);
        frame(document);
        document.focus().requestFocus(node);

        List<String> log = new ArrayList<>();
        document.input().pushMode(new Recorder("mode", CgKeyCodes.KEY_ESCAPE, log));
        node.events.getGroup(KeyboardEvent.Down.class).attachListener((n, e) -> log.add("content"), true, true);

        key(document, CgKeyCodes.KEY_TAB, true);
        assertEquals("swallowing everything is much closer to a modal grab than a mode needs, and a "
                + "mode nobody remembers entering would then eat the keyboard with no way out",
                List.of("mode", "content"), log);
    }

    @Test
    public void modesAreReportedInnermostFirst() {
        UIDocument document = new UIDocument();
        List<String> log = new ArrayList<>();
        InputMode outer = new Recorder("outer", -1, log);
        InputMode inner = new Recorder("inner", -1, log);
        document.input().pushMode(outer);
        document.input().pushMode(inner);

        assertEquals(List.of(inner, outer), document.input().modes());
        assertTrue(document.input().hasMode(outer));
        document.input().popMode(outer);
        assertFalse(document.input().hasMode(outer));
    }

    // ── The drag, as a mode ──────────────────────────────────────────────────

    @Test
    public void aDragEatsEscapeAndTellsItsTargetAndItsSource() {
        UIDocument document = new UIDocument();
        UIElement source = at("source", 0, 0, 100, 100);
        UIElement target = at("target", 200, 0, 100, 100);
        document.append(source).append(target);
        frame(document);

        List<String> log = new ArrayList<>();
        ServiceFixtures.on(target, DragEvent.Enter.class, (n, e) -> log.add("enter"));
        ServiceFixtures.on(target, DragEvent.Leave.class, (n, e) -> log.add("leave"));
        ServiceFixtures.on(source, DragEvent.Cancel.class, (n, e) -> log.add("cancel"));

        press(document, 20, 20);
        Drag drag = Drag.startWithPayload(source, 20, 20, "payload", new Drag.Listener() {
            @Override
            public void onDragUpdate(float x, float y, float sx, float sy, float dx, float dy) {
                log.add("update");
            }

            @Override
            public void onDragCancel() {
                log.add("listener-cancel");
            }
        });
        ServiceFixtures.move(document, 250, 20);
        assertTrue("past the threshold", drag.isActivated());
        assertSame(target, drag.dropTarget());

        assertTrue("a drag is the innermost live interaction, so Escape is what it means",
                key(document, CgKeyCodes.KEY_ESCAPE, true));
        assertEquals(List.of("update", "enter", "leave", "cancel", "listener-cancel"), log);
        assertFalse(document.input().hasMode(drag));
    }

    @Test
    public void rejectionIsTheDefaultAndAcceptanceIsReReadEveryFrame() {
        UIDocument document = new UIDocument();
        UIElement source = at("source", 0, 0, 100, 100);
        UIElement target = at("target", 200, 0, 100, 100);
        document.append(source).append(target);
        frame(document);
        List<String> drops = new ArrayList<>();
        ServiceFixtures.on(target, DragEvent.Drop.class, (n, e) -> drops.add("drop"));

        press(document, 20, 20);
        Drag drag = Drag.startWithPayload(source, 20, 20, "payload", (x, y, sx, sy, dx, dy) -> { });
        ServiceFixtures.move(document, 250, 20);
        assertFalse("HTML5 drag-and-drop's one genuinely good idea, kept", drag.isDropAccepted());

        ServiceFixtures.on(target, DragEvent.Over.class, (n, e) -> e.preventDefault());
        ServiceFixtures.move(document, 251, 20);
        assertTrue(drag.isDropAccepted());

        release(document, 251, 20);
        assertEquals(List.of("drop"), drops);
        assertFalse(document.input().hasMode(drag));
    }

    @Test
    public void theSourceAndItsSubtreeAreNeverADropTarget() {
        UIDocument document = new UIDocument();
        UIElement source = at("source", 0, 0, 200, 200);
        UIElement inside = at("inside", 10, 10, 50, 50);
        source.append(inside);
        document.append(source);
        frame(document);

        press(document, 100, 150);
        Drag drag = Drag.startWithPayload(source, 100, 150, "payload", (x, y, sx, sy, dx, dy) -> { });
        ServiceFixtures.move(document, 30, 30);
        assertSame("conflating the drag's own subtree with a target makes every drop land on the "
                + "thing being dragged", null, drag.dropTarget());
    }

    @Test
    public void aDragEndsOnTheButtonThatStartedIt() {
        UIDocument document = new UIDocument();
        UIElement source = at("source", 0, 0, 100, 100);
        document.append(source);
        frame(document);
        List<String> log = new ArrayList<>();

        press(document, 20, 20, 2);
        Drag drag = Drag.start(source, 20, 20, 2, null, 0f, new Drag.Listener() {
            @Override
            public void onDragUpdate(float x, float y, float sx, float sy, float dx, float dy) {
            }

            @Override
            public void onDragEnd(float x, float y) {
                log.add("end");
            }
        });

        release(document, 40, 20, 0);
        assertTrue("a middle-button pan is not ended by the left button coming up", log.isEmpty());
        assertTrue(document.input().hasMode(drag));

        release(document, 40, 20, 2);
        assertEquals(List.of("end"), log);
    }

    // ── ...and the service names none of them ────────────────────────────────

    @Test
    public void theInputServiceNamesNoWidgetAndNoGesture() throws IOException {
        Path root = ClassReferences.mainClassesRoot(ModeStackTest.class);
        Set<String> named = ClassReferences.referencesOf(root.resolve("com/crystalgui/ui/service/Input.class"));
        for (String forbidden : List.of("com/crystalgui/ui/elements/", "com/crystalgui/ui/UIElement",
                "com/crystalgui/ui/UIWindow", "com/crystalgui/ui/input/UIDragController")) {
            for (String reference : named) {
                assertFalse("Input references " + reference + " -- the rungs are back",
                        forbidden.endsWith("/") ? reference.startsWith(forbidden) : reference.equals(forbidden));
            }
        }
        assertFalse("not even the drag it hosts: a mode is pushed INTO the stack, never known by it",
                named.contains("com/crystalgui/ui/service/Drag"));
    }
}
