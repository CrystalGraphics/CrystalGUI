package com.crystalgui.headless;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.dom.ElementTreeSource;
import com.crystalgui.ui.dom.TreeObserver;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.input.FocusPolicy;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * {@link TreeObserver} — the change feed a networked session drains once per tick, and what it says
 * about <b>widgets</b> rather than about tree shape.
 *
 * <p>The shape half is {@code TreeSourceContractTest}, written purely against the seam so M5's engine
 * must pass it unchanged. This file is the other half and is deliberately not seam-pure: it drives real
 * {@code Button}s, {@code Checkbox}es and {@code Slider}s to pin the <b>attribution</b> rule (an
 * internal label dirties its composite) and the <b>no-op</b> rule (an equality-suppressed setter reports
 * nothing). Both are properties of the widgets, so they belong beside the widgets.</p>
 *
 * <p>Ported from {@code UITreeObserverTest} at M0. Two things changed and both are visible below:
 * installing an observer no longer reports an attach per element, and a reparent is a {@code moved}
 * rather than a detach followed by an attach.</p>
 *
 * <p>Everything here asserts on an exact recorded sequence rather than "something happened". A
 * dirty-tracking layer that over-reports looks correct in a spot check and quietly sends the whole
 * tree every frame.</p>
 */
public class TreeObserverBehaviourTest {

    /** Records what it is told, in order. */
    private static final class Recorder implements TreeObserver<UIElement> {
        final List<String> events = new ArrayList<>();

        @Override public void inserted(UIElement e, UIElement p, int i) { events.add("attach:" + name(e)); }
        @Override public void removed(UIElement e, UIElement p) { events.add("detach:" + name(e)); }
        @Override public void moved(UIElement e, UIElement p, int i) { events.add("move:" + name(e)); }
        @Override public void stateChanged(UIElement e) { events.add("state:" + name(e)); }
        @Override public void attributeChanged(UIElement e) { events.add("identity:" + name(e)); }
        @Override public void inlineStyleChanged(UIElement e) { events.add("inline:" + name(e)); }

        private static String name(UIElement e) {
            return e.getId().isEmpty() ? e.tagName() : e.getId();
        }

        void clear() { events.clear(); }

        List<String> only(String prefix) {
            return events.stream().filter(s -> s.startsWith(prefix + ":")).toList();
        }
    }

    private Recorder recorder;
    private UIElement root;
    private ElementTreeSource source;

    @Before
    public void setUp() {
        recorder = new Recorder();
        root = new UIElement();
        root.setId("root");
        source = new ElementTreeSource(root);
        source.observe(recorder);
        // No clear() needed any more, and that is the change: installing an observer used to emit an
        // attach for every element it walked, so every consumer had to discard its own installation
        // before it could tell a real insertion from being handed the tree.
    }

    // ── Attachment ──────────────────────────────────────────────────────────

    /** A grafted subtree reports every node, parents first — the session never walks the tree itself. */
    @Test
    public void attachingASubtreeReportsTheWholeThingInOrder() {
        UIElement branch = new UIElement();
        branch.setId("branch");
        UIElement leaf = new UIElement();
        leaf.setId("leaf");
        branch.addChild(leaf);

        root.addChild(branch);

        assertEquals(List.of("attach:branch", "attach:leaf"), recorder.only("attach"));
    }

    /**
     * <b>Changed at M0, on purpose.</b> This used to assert {@code [detach:branch, detach:leaf]} -- one
     * message per descendant for a single deletion. A receiver removing a node removes what is under it,
     * so naming the subtree root is complete, and the old shape made deleting a large panel cost a
     * message per element in it.
     */
    @Test
    public void detachingASubtreeNamesOnlyItsRoot() {
        UIElement branch = new UIElement();
        branch.setId("branch");
        branch.addChild(new UIElement().setId("leaf"));
        root.addChild(branch);
        recorder.clear();

        root.removeChild(branch);

        assertEquals(List.of("detach:branch"), recorder.only("detach"));
    }

    /** A detached subtree must stop reporting entirely, or a session leaks updates for dead nodes. */
    @Test
    public void aDetachedSubtreeGoesSilent() {
        Checkbox checkbox = new Checkbox("x");
        root.addChild(checkbox);
        root.removeChild(checkbox);
        recorder.clear();

        checkbox.setChecked(true);
        checkbox.addClass("late");

        assertTrue("a removed element must not keep reporting: " + recorder.events,
                recorder.events.isEmpty());
    }

    /**
     * A composite arrives as ONE node. Its internals are scaffolding its own constructor rebuilds on the
     * far side, so describing them would duplicate the structure -- which is the same distinction
     * {@code describedChildren} draws, now visible in the change stream rather than only in the codec.
     */
    @Test
    public void aCompositeArrivesAsOneNode() {
        root.addChild(new Button("hi"));
        assertEquals(List.of("attach:button"), recorder.only("attach"));
    }

    /** The counterpart to the above: a composite's own state still travels, attributed to it. */
    @Test
    public void aReparentIsAMoveRatherThanADetachAndAttach() {
        UIElement from = new UIElement().setId("from");
        UIElement to = new UIElement().setId("to");
        UIElement moving = new UIElement().setId("moving");
        root.addChildren(from, to);
        from.addChild(moving);
        recorder.clear();

        to.addChild(moving);

        assertEquals(List.of("move:moving"), recorder.events);
    }

    // ── State ───────────────────────────────────────────────────────────────

    @Test
    public void aRealStateChangeReportsExactlyOnce() {
        Checkbox checkbox = new Checkbox("agree");
        root.addChild(checkbox);
        recorder.clear();

        checkbox.setChecked(true);

        assertEquals(List.of("state:checkbox"), recorder.only("state"));
    }

    /** Equality-suppressed setters must not report — otherwise idle UIs still generate traffic. */
    @Test
    public void aNoOpMutationReportsNothing() {
        Checkbox checkbox = new Checkbox("agree");
        checkbox.setChecked(true);
        root.addChild(checkbox);
        recorder.clear();

        checkbox.setChecked(true);
        checkbox.setChecked(true);

        assertTrue("setting the same value must not dirty anything: " + recorder.events,
                recorder.only("state").isEmpty());
    }

    /**
     * The attribution rule. A Button's label is an internal {@code UIText} that never travels as an
     * element of its own, so a text change has to be reported against the Button — whose
     * {@code writeState} actually carries the text — not against a child the far side has never
     * heard of.
     */
    @Test
    public void internalChildChangesAreAttributedToTheirComposite() {
        Button button = new Button("before");
        root.addChild(button);
        recorder.clear();

        button.setText("after");

        assertEquals("must name the Button, not its internal label",
                List.of("state:button"), recorder.only("state"));
    }

    @Test
    public void checkboxLabelIsAlsoAttributedToTheCheckbox() {
        Checkbox checkbox = new Checkbox("before");
        root.addChild(checkbox);
        recorder.clear();

        checkbox.setLabel("after");

        assertEquals(List.of("state:checkbox"), recorder.only("state"));
    }

    @Test
    public void sliderReportsValueRangeAndStep() {
        Slider slider = new Slider();
        root.addChild(slider);
        recorder.clear();

        slider.setRange(0f, 10f);
        assertFalse(recorder.only("state").isEmpty());

        recorder.clear();
        slider.setValue(5f);
        assertEquals(List.of("state:slider"), recorder.only("state"));
    }

    // ── Identity ────────────────────────────────────────────────────────────

    @Test
    public void identityMutationsReport() {
        UIElement element = new UIElement();
        element.setId("target");
        root.addChild(element);
        recorder.clear();

        element.addClass("highlighted");
        element.setEnabled(false);
        element.setFocusPolicy(FocusPolicy.FOCUSABLE);
        element.setHitTest(false);

        assertEquals(4, recorder.only("identity").size());
    }

    @Test
    public void redundantIdentityMutationsReportNothing() {
        UIElement element = new UIElement();
        element.setId("target");
        element.addClass("a");
        root.addChild(element);
        recorder.clear();

        element.addClass("a");          // already present
        element.removeClass("missing"); // never present
        element.setId("target");        // unchanged
        element.setEnabled(true);       // already true
        element.setHitTest(true);       // already true

        assertTrue("no-op identity writes must be silent: " + recorder.events,
                recorder.only("identity").isEmpty());
    }

    // ── Cost when unobserved ────────────────────────────────────────────────

    /** A purely client-side UI installs no observer, and must behave exactly as before. */
    @Test
    public void anUnobservedTreeIsUnaffected() {
        UIElement plain = new UIElement();
        Checkbox checkbox = new Checkbox("x");
        plain.addChild(checkbox);

        checkbox.setChecked(true);
        checkbox.addClass("c");
        plain.removeChild(checkbox);

        assertNull(plain.getDomObserver());
        assertNull(checkbox.getDomObserver());
    }
}
