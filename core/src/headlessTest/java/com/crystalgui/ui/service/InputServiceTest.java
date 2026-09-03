package com.crystalgui.ui.service;

import static com.crystalgui.ui.service.ServiceFixtures.at;
import static com.crystalgui.ui.service.ServiceFixtures.frame;
import static com.crystalgui.ui.service.ServiceFixtures.key;
import static com.crystalgui.ui.service.ServiceFixtures.move;
import static com.crystalgui.ui.service.ServiceFixtures.on;
import static com.crystalgui.ui.service.ServiceFixtures.press;
import static com.crystalgui.ui.service.ServiceFixtures.release;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The input service against the invariant rows it inherits — each test named for the row it pins.
 *
 * <p>These are the rows the old {@code UIInputHandler} learned the hard way, restated as
 * assertions rather than as prose: the enter/leave chain, the capture that pins hover, the
 * hover cache that must be invalidated and never read at frame start, activation from the keyboard,
 * and the reporting of consumption back to the host.</p>
 */
public class InputServiceTest {

    // ── Enter/Leave dispatch to every node in the chain ──────────────────────

    @Test
    public void enterAndLeaveReachEveryNodeInTheChainThoughNeitherBubbles() {
        UIDocument document = new UIDocument();
        UIElement row = at("row", 0, 0, 200, 100);
        UIElement label = at("label", 10, 10, 100, 50);
        row.append(label);
        document.append(row);
        List<String> log = new ArrayList<>();
        // AT THE TARGET ONLY, which is what a widget subscribes. A non-bubbling event still runs the
        // CAPTURE phase -- `bubbles: false` withholds the bubble phase and nothing else -- so an
        // ancestor listening in every phase legitimately hears its descendant's Enter too.
        ServiceFixtures.onTarget(row, MouseEvent.Enter.class, (n, e) -> log.add("enter:row"));
        ServiceFixtures.onTarget(label, MouseEvent.Enter.class, (n, e) -> log.add("enter:label"));
        ServiceFixtures.onTarget(row, MouseEvent.Leave.class, (n, e) -> log.add("leave:row"));
        ServiceFixtures.onTarget(label, MouseEvent.Leave.class, (n, e) -> log.add("leave:label"));

        move(document, 50, 30);
        frame(document);
        assertEquals("outermost first on the way in -- a container hears about the pointer at all",
                List.of("enter:row", "enter:label"), log);

        log.clear();
        move(document, 400, 400);
        frame(document);
        assertEquals("innermost first on the way out", List.of("leave:label", "leave:row"), log);
    }

    @Test
    public void hoverStateFollowsTheSameChainTheEventsDo() {
        UIDocument document = new UIDocument();
        UIElement row = at("row", 0, 0, 200, 100);
        UIElement label = at("label", 10, 10, 100, 50);
        row.append(label);
        document.append(row);

        move(document, 50, 30);
        frame(document);
        assertTrue("the label is hovered", label.isHovered());
        assertTrue("and so is the row it is in -- :hover and the events must agree", row.isHovered());

        move(document, 400, 400);
        frame(document);
        assertFalse(label.isHovered());
        assertFalse(row.isHovered());
    }

    // ── The hover cache ──────────────────────────────────────────────────────

    @Test
    public void aReflowUnderAStillPointerUpdatesHover() {
        UIDocument document = new UIDocument();
        UIElement a = at("a", 0, 0, 100, 100);
        UIElement b = at("b", 0, 0, 100, 100);
        document.append(a).append(b);
        move(document, 50, 50);
        frame(document);
        assertSame("the later sibling is on top", b, document.input().hoverTarget());

        // Nothing moved the pointer; the LAYOUT moved out from under it.
        ServiceFixtures.layout(b, l -> l.left(400f));
        frame(document);
        assertSame("beginFrame invalidates, so the hit test runs against THIS frame's layout",
                a, document.input().hoverTarget());
    }

    // ── Pointer capture ──────────────────────────────────────────────────────

    @Test
    public void aCaptureSubstitutesTheHitTestAndFiresNoBoundaryEvents() {
        UIDocument document = new UIDocument();
        UIElement source = at("source", 0, 0, 100, 100);
        UIElement other = at("other", 200, 0, 100, 100);
        document.append(source).append(other);
        frame(document);
        List<String> log = new ArrayList<>();
        ServiceFixtures.onTarget(other, MouseEvent.Enter.class, (n, e) -> log.add("enter:other"));
        ServiceFixtures.onTarget(source, MouseEvent.Leave.class, (n, e) -> log.add("leave:source"));

        press(document, 50, 50);
        frame(document);
        document.input().setPointerCapture(source);

        move(document, 250, 50);
        frame(document);
        assertSame("every pointer event targets the capturing node, as if the pointer were over it",
                source, document.input().hoverTarget());
        assertTrue("so nothing enters or leaves and :hover stays pinned", log.isEmpty());
        assertTrue(source.isHovered());

        release(document, 250, 50);
        frame(document);
        assertNull("the release is implicit, AFTER the up was delivered", document.input().pointerCaptureTarget());
        assertSame(other, document.input().hoverTarget());
    }

    @Test
    public void aCaptureWithNoButtonHeldIsRefused() {
        UIDocument document = new UIDocument();
        UIElement source = at("source", 0, 0, 100, 100);
        document.append(source);
        frame(document);
        document.input().setPointerCapture(source);
        assertNull("a capture nothing can release would wedge input -- the spec fails it silently",
                document.input().pointerCaptureTarget());
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    @Test
    public void theThreePhasesRunRootToTargetToRoot() {
        UIDocument document = new UIDocument();
        UIElement outer = at("outer", 0, 0, 200, 200);
        UIElement inner = at("inner", 10, 10, 100, 100);
        outer.append(inner);
        document.append(outer);
        frame(document);
        List<String> log = new ArrayList<>();
        outer.events.getGroup(MouseEvent.Down.class).attachListener((n, e) -> log.add("capture:outer"), true, false);
        outer.events.getGroup(MouseEvent.Down.class).attachListener((n, e) -> log.add("bubble:outer"), false, true);
        on(inner, MouseEvent.Down.class, (n, e) -> log.add("target:inner"));

        press(document, 50, 50);
        assertEquals(List.of("capture:outer", "target:inner", "bubble:outer"), log);
    }

    @Test
    public void aListenerOutsideAShadowTreeIsToldTheHostAndNeverThePart() {
        UIDocument document = new UIDocument();
        UIElement host = at("host", 0, 0, 200, 200);
        ShadowRoot shadow = host.attachShadow();
        UIElement part = at("part", 10, 10, 100, 100);
        shadow.append(part);
        document.append(host);
        frame(document);
        List<UIElement> outside = new ArrayList<>();
        List<UIElement> inside = new ArrayList<>();
        // Bubble only: a listener in EVERY phase hears a descendant's press twice, once on the way
        // down and once on the way up, which is the DOM and is not what this test is about.
        document.events.getGroup(MouseEvent.Down.class)
                .attachListener((n, e) -> outside.add((UIElement) e.getTarget()), false, true);
        on(part, MouseEvent.Down.class, (n, e) -> inside.add((UIElement) e.getTarget()));

        press(document, 50, 50);
        assertEquals(1, outside.size());
        assertSame("retargeted at the boundary -- encapsulation, from the event's side", host, outside.get(0));
        assertEquals(1, inside.size());
        assertSame("and the part still knows it was the target", part, inside.get(0));
    }

    // ── Press, click, activation ─────────────────────────────────────────────

    @Test
    public void anUpKnowsWhetherItLandedOnWhatWasPressed() {
        UIDocument document = new UIDocument();
        UIElement a = at("a", 0, 0, 100, 100);
        UIElement b = at("b", 200, 0, 100, 100);
        document.append(a).append(b);
        frame(document);
        List<Boolean> onA = new ArrayList<>();
        on(a, MouseEvent.Up.class, (n, e) -> onA.add(e.isWasPressTarget()));

        press(document, 50, 50);
        release(document, 50, 50);
        assertEquals(List.of(true), onA);

        onA.clear();
        press(document, 50, 50);
        release(document, 250, 50);
        assertTrue("the up landed elsewhere, so a's listener never ran", onA.isEmpty());
    }

    @Test
    public void spaceOverAFocusedNodeSynthesizesTheSameClickAMouseWould() {
        UIDocument document = new UIDocument();
        UIElement button = at("button", 0, 0, 100, 100);
        button.setFocusPolicy(FocusPolicy.CLICK);
        document.append(button);
        frame(document);
        document.focus().requestFocus(button);

        List<Integer> details = new ArrayList<>();
        on(button, MouseEvent.Down.class, (n, e) -> details.add(e.getDetail()));
        key(document, CgKeyCodes.KEY_SPACE, true);
        assertEquals("a widget gets keyboard activation with no keyboard code at all",
                List.of(Input.KEYBOARD_DETAIL), details);
        assertTrue(button.isPressed());

        key(document, CgKeyCodes.KEY_SPACE, false);
        assertFalse(button.isPressed());
    }

    @Test
    public void aTextInputTakesSpaceAsACharacterAndIsNotActivated() {
        UIDocument document = new UIDocument();
        UIElement field = new UIElement() {
            @Override
            public boolean consumesTextInput() {
                return true;
            }
        };
        field.setId("field").setFocusPolicy(FocusPolicy.CLICK);
        ServiceFixtures.layout(field, l -> l.width(100f).height(30f));
        document.append(field);
        frame(document);
        document.focus().requestFocus(field);

        List<String> log = new ArrayList<>();
        on(field, MouseEvent.Down.class, (n, e) -> log.add("press"));
        key(document, CgKeyCodes.KEY_SPACE, true);
        assertTrue("synthesizing a press would fire its handlers every time somebody typed a space",
                log.isEmpty());
    }

    // ── Consumption is reported ──────────────────────────────────────────────

    @Test
    public void aConsumedKeystrokeIsReportedToTheHost() {
        UIDocument document = new UIDocument();
        UIElement node = at("node", 0, 0, 100, 100);
        node.setFocusPolicy(FocusPolicy.FOCUSABLE);
        document.append(node);
        frame(document);
        document.focus().requestFocus(node);

        assertFalse("nothing wanted it", key(document, CgKeyCodes.KEY_ESCAPE, true));

        node.events.getGroup(KeyboardEvent.Down.class)
                .attachListener((n, e) -> e.stopPropagation(), true, true);
        assertTrue("the platform acts on what is left over -- a GuiScreen closes on an Escape nobody wanted",
                key(document, CgKeyCodes.KEY_ESCAPE, true));
    }

    // ── Inertness reaches hit-testing ────────────────────────────────────────

    @Test
    public void anInertSubtreeFallsThroughToWhatIsBehindIt() {
        UIDocument document = new UIDocument();
        UIElement behind = at("behind", 0, 0, 200, 200);
        UIElement front = at("front", 0, 0, 200, 200);
        document.append(behind).append(front);
        frame(document);
        assertSame(front, document.input().hoverTarget());

        front.set(Attribute.INERT, true);
        frame(document);
        assertSame("pointer-events: none passes the pointer OVER a node; it does not punch a hole",
                behind, document.input().hoverTarget());
    }

    // ── Forgetting ───────────────────────────────────────────────────────────

    @Test
    public void aNodeThatLeavesTheTreeIsForgotten() {
        UIDocument document = new UIDocument();
        UIElement node = at("node", 0, 0, 100, 100);
        document.append(node);
        press(document, 50, 50);
        frame(document);
        assertSame(node, document.input().hoverTarget());

        document.input().forget(node);
        document.remove(node);
        frame(document);
        assertSame("what is left under the pointer is the document itself -- holding the detached node "
                + "would ask for a common ancestor across two trees, a walk that never converges",
                document, document.input().hoverTarget());
        assertFalse(node.isHovered());
    }
}
