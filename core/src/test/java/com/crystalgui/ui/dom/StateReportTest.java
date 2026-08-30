package com.crystalgui.ui.dom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.TextNode;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * <b>A widget's state change reaches the observer, attributed to a node the far side has heard of.</b>
 *
 * <h3>The gap this was written for</h3>
 *
 * <p>{@link TreeObserver#stateChanged} has been on the seam since M0 and is honoured by
 * {@code ServerTreeMirror} — and for the whole of M5 and the first widget of M6, <b>nothing ever
 * called it</b>. A shadow subtree inherits a {@code null} observer, which is right for structure and
 * attributes and wrong for state: {@code button.setText(...)} changes a node inside the shadow root,
 * the report was dropped at the boundary, and a viewer kept whatever the description said, forever.</p>
 *
 * <p>Every observable was correct while it was broken — the widget, the server's tree, the flush's
 * own return value — which is the same silence {@code AGENTS.md} records one step later as <i>"a
 * dirty set that is cleared without being encoded is indistinguishable from one that was never
 * filled"</i>. Here the set was never filled at all.</p>
 *
 * <h3>Assert on the ATTRIBUTION, not on the count</h3>
 *
 * <p>A test that only counted reports would pass against a version that dirtied the shadow label —
 * which is the wrong node and travels to nobody. What separates a working walk from a broken one is
 * <em>which</em> node comes back.</p>
 */
public class StateReportTest extends UiDocumentTestBase {

    /** Records who was reported, in order. */
    private static final class Recorder extends TreeObserver.Adapter<UINode> {
        final List<UINode> stateChanges = new ArrayList<>();

        @Override
        public void stateChanged(UINode node) {
            stateChanges.add(node);
        }
    }

    private Recorder observing(UINode root) {
        Recorder recorder = new Recorder();
        new UINodeTreeSource(root).observe(recorder);
        return recorder;
    }

    /**
     * A composite's label is in its shadow tree, so the report has to come out as the COMPOSITE.
     *
     * <p>The button's contract carries the text; the {@link TextNode} holding it is not a node any
     * peer has ever been told about, so a report naming it is a report naming nothing.</p>
     */
    @Test
    public void aShadowLabelsTextChangeIsAttributedToTheWidget() {
        Button button = new Button("Save");
        document.append(button);
        Recorder recorder = observing(document);

        button.setText("Save as…");

        assertEquals("exactly one report", 1, recorder.stateChanges.size());
        assertEquals("and it names the BUTTON, not the label inside it",
                button, recorder.stateChanges.get(0));
    }

    /** The same for a widget whose state is its own field rather than a child's text. */
    @Test
    public void aWidgetsOwnStateChangeIsReported() {
        Checkbox checkbox = new Checkbox("Wrap lines");
        document.append(checkbox);
        Recorder recorder = observing(document);

        checkbox.setChecked(true);

        assertEquals(1, recorder.stateChanges.size());
        assertEquals(checkbox, recorder.stateChanges.get(0));
    }

    /**
     * A plain {@link TextNode} in the LIGHT tree reports as itself — it is a described node with a
     * text state of its own, so there is nothing to walk out of.
     *
     * <p>The counter-assertion to the two above: a walk written as "always report the parent" would
     * satisfy both of them and be wrong here.</p>
     */
    @Test
    public void aLightTextNodeReportsAsItself() {
        TextNode text = new TextNode("hello");
        document.append(text);
        Recorder recorder = observing(document);

        text.setText("goodbye");

        assertEquals(1, recorder.stateChanges.size());
        assertEquals(text, recorder.stateChanges.get(0));
    }

    /**
     * Nested shadow trees walk ALL the way out, not one hop.
     *
     * <p>A widget built out of other widgets puts its parts inside <em>their</em> shadow roots, so
     * stopping at the first host reports a node that is itself invisible to a peer. A button inside
     * another widget's shadow tree is the smallest case that shows it, and it is the ordinary case
     * the moment a composite composes anything.</p>
     */
    @Test
    public void nestedShadowTreesWalkAllTheWayOut() {
        UINode outer = new UINode();
        Button inner = new Button("inner");
        outer.attachShadow().append(inner);
        document.append(outer);
        Recorder recorder = observing(document);

        inner.setText("changed");

        assertEquals(1, recorder.stateChanges.size());
        assertEquals("the outer node, not the button one level in",
                outer, recorder.stateChanges.get(0));
    }

    /**
     * An unchanged value reports nothing — which is what lets a panel mirror its model every tick.
     *
     * <p>{@code ProgressBar.setFraction} was the one setter in the old engine for which this was
     * false: it notified unconditionally, so a panel following the documented "mirror each tick"
     * shape sent a delta per tick carrying a value nobody had moved. Assert on the TRAFFIC, never on
     * the state, or the test passes against exactly that bug.</p>
     */
    @Test
    public void anUnchangedValueReportsNothing() {
        Checkbox checkbox = new Checkbox("Wrap lines");
        checkbox.setChecked(true);
        document.append(checkbox);
        Recorder recorder = observing(document);

        checkbox.setChecked(true);
        checkbox.setLabel("Wrap lines");

        assertTrue("neither setter moved anything: " + recorder.stateChanges,
                recorder.stateChanges.isEmpty());
    }
}
