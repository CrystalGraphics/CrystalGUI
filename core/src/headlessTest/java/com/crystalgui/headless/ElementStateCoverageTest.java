package com.crystalgui.headless;

import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 4 <b>C4</b> — every registered tag has had its state question <em>answered</em>.
 *
 * <p>The failure this exists for is silent by construction: a stateful widget with no {@code writeState}
 * does not throw, it <b>arrives blank</b>, and a blank widget reads as a rendering bug in the client
 * rather than a missing method on the server. Nothing links {@code ElementRegistry} to
 * {@code writeState}, so the gap is invisible from either file.</p>
 *
 * <p>So this is the same anti-rot shape {@code AGENTS.md} already prescribes for the CSS property list —
 * <i>"This list goes stale silently … If you add a property, add it here in the same edit."</i> The map
 * below must name <b>every</b> registered tag. Adding a tag fails this test until somebody writes down
 * which side of the line it is on, which is the only moment the question is cheap to answer.</p>
 */
public class ElementStateCoverageTest {

    /**
     * Tag → does this widget carry authored state across the wire?
     *
     * <p><b>false is a decision, not an omission.</b> Each one is a claim that the widget's whole
     * observable condition is either structure (which the description carries) or style (which the
     * cascade carries) or view state (which deliberately does not travel — the same document/view
     * boundary that keeps scroll position out of {@code UndoStack}).</p>
     */
    private static final Map<String, Boolean> STATEFUL = new LinkedHashMap<>();

    static {
        // Carries state.
        STATEFUL.put("button", true);          // label
        STATEFUL.put("checkbox", true);        // checked
        STATEFUL.put("colorselector", true);   // colour, original colour, mode
        STATEFUL.put("dropdown", true);        // selected index
        STATEFUL.put("progressbar", true);     // fraction, where -1 is indeterminate
        STATEFUL.put("slider", true);          // range, step, value
        STATEFUL.put("splitview", true);       // divider weights
        STATEFUL.put("switch", true);          // checked
        STATEFUL.put("tab", true);             // its label text
        STATEFUL.put("tabview", true);         // selected tab
        STATEFUL.put("text", true);            // the text itself
        STATEFUL.put("textfield", true);       // text, and the caret that goes with it

        // Carries none, and why.
        STATEFUL.put("element", false);        // the base container: structure only
        STATEFUL.put("dialog", false);         // open/closed is imperative, like top-layer promotion
        STATEFUL.put("scroller", false);       // scroll position is VIEW state and must not travel
        STATEFUL.put("scrollerview", false);   // ditto
        STATEFUL.put("tooltip", false);        // its text is its child's, and it is transient
        STATEFUL.put("popover", false);        // shown/hidden is imperative and transient
        STATEFUL.put("menu", false);           // built by its owner each time it opens
        STATEFUL.put("menuitem", false);       // label is structure; enablement is resolved live
        // CrystalOS. The desktop is engine-owned compositor host -- an internal child of the window's
        // root, which UIDescriptionCodec skips by construction, so it is never described at all.
        STATEFUL.put("desktop", false);
        // And a window frame is CLIENT CHROME (plan_windowing.md): the server ships a window's CONTENT
        // tree and the client wraps it in a frame, so a description never carries this tag. Its title
        // comes from whatever opened it, and its geometry is per-window client config (W12) rather than
        // element state -- the same document/view line that keeps scroll position out of a description.
        STATEFUL.put("window", false);
    }

    /**
     * Tag → something that changes its state, for the tags marked stateful.
     *
     * <p>Needed because {@code writeState} <b>omits defaults on purpose</b> — {@code UIElement} says
     * "every key should be written conditionally", which keeps a delta to what actually changed. A
     * default instance therefore writes nothing quite correctly, and a test asserting otherwise fails on
     * every well-behaved widget. That was the first version of this file, and it failed on {@code button}
     * for exactly the right reason.</p>
     *
     * <p>So each stateful tag supplies a mutation, and the assertions below drive a real value through:
     * write, read into a fresh instance, write again, compare. That is the only version that can catch
     * the failure this test exists for — a key written under one name and read under another.</p>
     */
    private static final Map<String, Consumer<UIElement>> MUTATORS = new LinkedHashMap<>();

    static {
        MUTATORS.put("button", e -> ((com.crystalgui.ui.elements.Button) e).setText("Pressed"));
        MUTATORS.put("checkbox", e -> ((com.crystalgui.ui.elements.Checkbox) e).setChecked(true));
        MUTATORS.put("colorselector", e -> ((com.crystalgui.ui.elements.ColorSelector) e).setColor(0xFF3366CC));
        MUTATORS.put("dropdown", e -> {
            com.crystalgui.ui.elements.Dropdown d = (com.crystalgui.ui.elements.Dropdown) e;
            d.addOptions("a", "b", "c");
            d.select(2);
        });
        MUTATORS.put("progressbar", e -> ((com.crystalgui.ui.elements.ProgressBar) e).setFraction(0.42f));
        MUTATORS.put("slider", e -> {
            com.crystalgui.ui.elements.Slider slider = (com.crystalgui.ui.elements.Slider) e;
            slider.setRange(0f, 10f);
            slider.setValue(7f);
        });
        MUTATORS.put("splitview", e -> ((com.crystalgui.ui.elements.SplitView) e).setWeights(0.3f, 0.7f));
        MUTATORS.put("switch", e -> ((com.crystalgui.ui.elements.Switch) e).setChecked(true));
        // A Tab's state is its TEXT, which it inherits from Button -- it has no title of its own.
        MUTATORS.put("tab", e -> ((com.crystalgui.ui.elements.Tab) e).setText("Renamed"));
        MUTATORS.put("tabview", e -> { });
        MUTATORS.put("text", e -> ((com.crystalgui.ui.elements.UIText) e).setText("hello"));
        MUTATORS.put("textfield", e -> ((com.crystalgui.ui.elements.TextField) e).setText("typed"));
    }

    @BeforeClass
    public static void bootstrap() {
        ElementRegistry.bootstrapBuiltins();
    }

    /**
     * The map covers exactly the registry — no more, no less.
     *
     * <p>This is the half that catches the next widget. A new tag is not in the map, so this fails with
     * its name, and the person adding it decides then rather than shipping a blank one.</p>
     */
    @Test
    public void everyRegisteredTagHasAnAnswer() {
        Set<String> registered = new TreeSet<>(ElementRegistry.tags());
        Set<String> answered = new TreeSet<>(STATEFUL.keySet());

        List<String> unanswered = new ArrayList<>(registered);
        unanswered.removeAll(answered);
        List<String> stale = new ArrayList<>(answered);
        stale.removeAll(registered);

        if (!unanswered.isEmpty()) {
            fail("registered but not listed in STATEFUL: " + unanswered
                    + " — decide whether each carries state and record it, or it ships arriving blank");
        }
        if (!stale.isEmpty()) {
            fail("listed in STATEFUL but no longer registered: " + stale + " — remove them");
        }
        assertEquals(registered, answered);
    }

    /**
     * Everything marked stateful actually writes something.
     *
     * <p>Constructed through the registry, so this reads a widget the way the decoder does. A default
     * instance is used deliberately: a widget whose {@code writeState} writes only non-default values
     * would pass vacuously here, which is why the map's entries name the fields they expect and why the
     * symmetry test below drives real values through.</p>
     */
    @Test
    public void everyStatefulTagHasAMutator() {
        for (Map.Entry<String, Boolean> entry : STATEFUL.entrySet()) {
            if (!entry.getValue()) continue;
            assertTrue("<" + entry.getKey() + "> is marked stateful but has no mutator, so nothing "
                            + "below actually exercises it",
                    MUTATORS.containsKey(entry.getKey()));
        }
    }

    /**
     * A mutated widget writes something — the assertion that catches a missing {@code writeState}.
     *
     * <p>{@code tabview} is exempt and is the one interesting case: its state is <em>which tab is
     * selected</em>, and a fresh one has no tabs to select, so there is nothing to change without
     * building structure. C3 is where that gets exercised.</p>
     */
    @Test
    public void aMutatedStatefulTagWritesSomething() {
        for (Map.Entry<String, Boolean> entry : STATEFUL.entrySet()) {
            if (!entry.getValue()) continue;
            String tag = entry.getKey();
            if ("tabview".equals(tag)) continue;
            UIElement element = ElementRegistry.create(tag);
            MUTATORS.get(tag).accept(element);
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            element.writeStateTo(out);
            assertTrue("<" + tag + "> is marked stateful and wrote nothing after being changed",
                    !out.isEmpty());
        }
    }

    /**
     * {@code writeState} and {@code readState} are symmetric — the invariant {@code UIElement} states and
     * nothing enforced.
     *
     * <p>Write, read the result back into a fresh instance, write again, and the two maps must agree. An
     * asymmetric pair — a key written under one name and read under another, or written and never read —
     * survives every other test in the suite, because both halves are individually plausible and the
     * value simply does not arrive.</p>
     */
    @Test
    public void writeAndReadAreSymmetric() {
        for (Map.Entry<String, Boolean> entry : STATEFUL.entrySet()) {
            if (!entry.getValue()) continue;
            String tag = entry.getKey();

            UIElement source = ElementRegistry.create(tag);
            // Driven through a REAL value, or the comparison below is two empty maps agreeing.
            MUTATORS.get(tag).accept(source);
            StateMap<Object> first = new StateMap<>(PlainOps.INSTANCE);
            source.writeStateTo(first);

            UIElement target = ElementRegistry.create(tag);
            target.readStateFrom(new StateMap<>(PlainOps.INSTANCE, first.encode()));
            StateMap<Object> second = new StateMap<>(PlainOps.INSTANCE);
            target.writeStateTo(second);

            assertEquals("<" + tag + "> is not symmetric: what it wrote did not survive being read back",
                    first.encode(), second.encode());
        }
    }

    /** Nothing marked stateless writes anything — the other half of the claim. */
    @Test
    public void nothingStatelessWritesState() {
        for (Map.Entry<String, Boolean> entry : STATEFUL.entrySet()) {
            if (entry.getValue()) continue;
            UIElement element = ElementRegistry.create(entry.getKey());
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            element.writeStateTo(out);
            assertTrue("<" + entry.getKey() + "> is marked stateless but wrote state — either the "
                            + "map is wrong or the widget gained some",
                    out.isEmpty());
        }
    }
}
