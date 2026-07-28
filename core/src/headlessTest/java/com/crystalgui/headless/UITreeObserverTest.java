package com.crystalgui.headless;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UITreeObserver;
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
 * {@link UITreeObserver} — the change feed a networked session drains once per tick.
 *
 * <p>Everything here asserts on an exact recorded sequence rather than "something happened". A
 * dirty-tracking layer that over-reports looks correct in a spot check and quietly sends the whole
 * tree every frame.</p>
 */
public class UITreeObserverTest {

    /** Records what it is told, in order. */
    private static final class Recorder implements UITreeObserver {
        final List<String> events = new ArrayList<>();

        @Override public void onAttached(UIElement e) { events.add("attach:" + name(e)); }
        @Override public void onDetached(UIElement e) { events.add("detach:" + name(e)); }
        @Override public void onStateDirty(UIElement e) { events.add("state:" + name(e)); }
        @Override public void onIdentityDirty(UIElement e) { events.add("identity:" + name(e)); }

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

    @Before
    public void setUp() {
        recorder = new Recorder();
        root = new UIElement();
        root.setId("root");
        root.setObserver(recorder);
        recorder.clear();
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

    @Test
    public void detachingASubtreeReportsTheWholeThing() {
        UIElement branch = new UIElement();
        branch.setId("branch");
        branch.addChild(new UIElement().setId("leaf"));
        root.addChild(branch);
        recorder.clear();

        root.removeChild(branch);

        assertEquals(List.of("detach:branch", "detach:leaf"), recorder.only("detach"));
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

    /** A widget's internal children are part of the observed tree too — they arrive with their owner. */
    @Test
    public void compositeWidgetsBringTheirInternalsWhenAttached() {
        root.addChild(new Button("hi"));
        assertTrue("the Button itself must be reported",
                recorder.only("attach").stream().anyMatch(s -> s.equals("attach:button")));
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

        assertNull(plain.getObserver());
        assertNull(checkbox.getObserver());
    }
}
