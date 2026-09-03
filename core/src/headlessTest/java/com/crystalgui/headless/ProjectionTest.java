package com.crystalgui.headless;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.layout.SplitView;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.widget.control.ProgressBar;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.net.projection.AutoProjection;
import com.crystalgui.net.projection.Projections;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The projection engine on its own terms — no session, no wire.
 *
 * <p>What it is replacing is a hand-written {@code mirror(model)} called every tick, whose four failure
 * modes were each silent. The assertions here are aimed at those, not at the happy path.</p>
 */
public class ProjectionTest {

    /** A model nobody rewrote to suit a UI engine. Plain fields, plain getters, no engine types. */
    private static final class Machine {
        boolean running;
        float throughput;
        String label = "idle";
        Engine engine = new Engine();
        int revision;

        boolean isRunning() { return running; }
        public float throughput() { return throughput; }
        public String label() { return label; }
        Engine engine() { return engine; }
        int revision() { return revision; }
    }

    private static final class Engine {
        float coolant = 0.5f;
        float coolant() { return coolant; }
    }

    // ── The basics ───────────────────────────────────────────────────────────

    @Test
    public void aProjectionWritesOnlyWhenTheValueChanged() {
        Machine model = new Machine();
        UIText label = new UIText("");
        Projections projections = Projections.create().of(model::label, label::setText);

        assertEquals("the first run always writes -- the widget's default may LOOK right",
                1, projections.run());
        assertEquals("idle", label.getText());

        assertEquals("an unchanged model writes nothing", 0, projections.run());
        assertEquals(0, projections.run());

        model.label = "running";
        assertEquals(1, projections.run());
        assertEquals("running", label.getText());
    }

    /**
     * The first run writes even when the value equals the widget's default.
     *
     * <p>Not pedantry: a bare equality test would leave the widget at a default that merely resembles
     * the model, and the panel would look correct until the first change. The same {@code applied} flag
     * {@code ProgressBar.setFraction} needs, for the same reason.</p>
     */
    @Test
    public void theFirstRunWritesEvenIfTheValueMatchesTheDefault() {
        Machine model = new Machine();
        model.label = "";
        UIText label = new UIText("");
        assertEquals(1, Projections.create().of(model::label, label::setText).run());
    }

    @Test
    public void nestingNeedsNoFeature() {
        Machine model = new Machine();
        ProgressBar coolant = new ProgressBar();
        Projections projections = Projections.create()
                .of(() -> model.engine().coolant(), coolant::setFraction);

        projections.run();
        assertEquals(0.5f, coolant.fraction(), 1e-6f);
        model.engine.coolant = 0.75f;
        projections.run();
        assertEquals(0.75f, coolant.fraction(), 1e-6f);
    }

    /** A null in a chained getter is "nothing to show yet", never an exception out of the tick. */
    @Test
    public void aNullInAChainSkipsRatherThanThrowingTheFrame() {
        Machine model = new Machine();
        model.engine = null;
        ProgressBar coolant = new ProgressBar();
        UIText label = new UIText("");

        Projections projections = Projections.create()
                .of(() -> model.engine().coolant(), coolant::setFraction)
                .of(model::label, label::setText);

        // No throw, AND the sibling projection still ran -- one bad accessor must not take the rest of
        // the panel with it, or it presents as the panel being dead.
        assertEquals(1, projections.run());
        assertEquals("idle", label.getText());

        model.engine = new Engine();
        assertEquals("and it recovers once the chain is whole again", 1, projections.run());
    }

    // ── The epoch gate ───────────────────────────────────────────────────────

    @Test
    public void anEpochGateSkipsEverythingWhileItIsUnchanged() {
        Machine model = new Machine();
        UIText label = new UIText("");
        Projections projections = Projections.create()
                .of(model::label, label::setText)
                .gatedBy(model::revision);

        assertEquals(1, projections.run());
        model.label = "changed but unannounced";
        assertEquals("the gate is closed, so nothing is even read", 0, projections.run());
        assertEquals("idle", label.getText());

        model.revision++;
        assertEquals(1, projections.run());
        assertEquals("changed but unannounced", label.getText());
    }

    // ── Collections ──────────────────────────────────────────────────────────

    private record Row(int id, String text) { }

    private static Projections rows(List<Row> items, UIElement into) {
        return Projections.create().each(() -> items, into, Row::id,
                item -> new UIText(item.text()),
                (element, item) -> ((UIText) element).setText(item.text()));
    }

    @Test
    public void anInsertKeepsEveryOtherRowsInstance() {
        List<Row> items = new ArrayList<>(List.of(new Row(1, "one"), new Row(2, "two")));
        UIElement list = new UIElement();
        Projections projections = rows(items, list);
        projections.run();

        UIElement firstBefore = list.children().get(0);
        UIElement secondBefore = list.children().get(1);

        items.add(1, new Row(3, "inserted"));
        projections.run();

        assertEquals(3, list.children().size());
        assertSame("an untouched row must keep its ELEMENT -- over a wire this is one insert op "
                        + "rather than a rebuilt child list", firstBefore,
                list.children().get(0));
        assertEquals("inserted", ((UIText) list.children().get(1)).getText());
        assertSame(secondBefore, list.children().get(2));
    }

    @Test
    public void aRemoveTakesOnlyTheRowItNames() {
        List<Row> items = new ArrayList<>(List.of(new Row(1, "one"), new Row(2, "two")));
        UIElement list = new UIElement();
        Projections projections = rows(items, list);
        projections.run();
        UIElement secondBefore = list.children().get(1);

        items.remove(0);
        projections.run();

        assertEquals(1, list.children().size());
        assertSame(secondBefore, list.children().get(0));
    }

    /** A reorder moves the same elements; it does not rebuild them. */
    @Test
    public void aReorderMovesRatherThanRebuilds() {
        List<Row> items = new ArrayList<>(List.of(new Row(1, "one"), new Row(2, "two")));
        UIElement list = new UIElement();
        Projections projections = rows(items, list);
        projections.run();
        UIElement one = list.children().get(0);
        UIElement two = list.children().get(1);

        items.clear();
        items.add(new Row(2, "two"));
        items.add(new Row(1, "one"));
        projections.run();

        assertSame(two, list.children().get(0));
        assertSame(one, list.children().get(1));
    }

    @Test
    public void anUnchangedListDoesNothing() {
        List<Row> items = new ArrayList<>(List.of(new Row(1, "one")));
        UIElement list = new UIElement();
        Projections projections = rows(items, list);
        projections.run();
        assertEquals("a settled list must not churn its children every tick", 0, projections.run());
    }

    @Test
    public void aNullListIsEmptyRatherThanAFailure() {
        UIElement list = new UIElement();
        Projections projections = Projections.create().each(() -> null, list, Object::toString,
                item -> new UIText(""), (element, item) -> { });
        projections.run();
        assertTrue(list.children().isEmpty());
    }

    /**
     * Duplicate keys are refused rather than quietly losing a row.
     *
     * <p>Left alone they are silently destructive: both items map to one element, so the list comes out
     * SHORTER than the model, and the ordering pass moves that one element to two places with the
     * second move undoing the first. On screen a row simply vanishes, which reads as a rendering bug
     * anywhere except here.</p>
     */
    @Test
    public void duplicateKeysAreRefused() {
        List<Row> items = new ArrayList<>(List.of(new Row(1, "one"), new Row(1, "again")));
        UIElement list = new UIElement();
        Projections projections = rows(items, list);

        // Refused, and -- per the no-throw rule -- it does not take the frame with it.
        assertEquals(0, projections.run());
        assertTrue("nothing may be half-built from a refused list", list.children().size() <= 1);
    }

    /**
     * An unchanged row is not rewritten — <b>but only when the model handed over a new instance.</b>
     *
     * <p>The rule is deliberately conservative and the reason is the whole point of this engine. Given
     * the SAME instance twice, a value comparison cannot tell "nothing changed" from "the object was
     * mutated in place": a record is immutable and a plain POJO is not, and `equals` on a mutable row
     * object is usually identity, so it answers "equal" for an item whose fields have just changed.
     * Skipping there would freeze the row silently, which is the failure projections exist to remove.
     * So a fresh-but-equal instance is proof the model re-snapshotted and is skipped; the same instance
     * is re-applied, landing on idempotent widget setters and therefore costing comparisons, not
     * traffic.</p>
     */
    @Test
    public void anUnchangedRowIsNotRewrittenWhenTheModelReSnapshots() {
        List<Row> items = new ArrayList<>(List.of(new Row(1, "one")));
        UIElement list = new UIElement();
        int[] applies = { 0 };
        Projections projections = Projections.create().each(() -> items, list, Row::id,
                item -> new UIText(item.text()),
                (element, item) -> { applies[0]++; ((UIText) element).setText(item.text()); });

        projections.run();
        assertEquals(1, applies[0]);

        // A NEW record, equal to the old one -- what a model that rebuilds its list every tick hands
        // over. Equality is meaningful here, so the row is left alone.
        items.set(0, new Row(1, "one"));
        projections.run();
        items.set(0, new Row(1, "one"));
        projections.run();
        assertEquals("an equal-but-fresh item must not rewrite its row", 1, applies[0]);

        items.set(0, new Row(1, "renamed"));
        projections.run();
        assertEquals(2, applies[0]);
        assertEquals("renamed", ((UIText) list.children().get(0)).getText());
    }

    /** The mirror of the rule above, stated so a future "optimisation" has to argue with it. */
    @Test
    public void theSameInstanceIsReAppliedBecauseItMayHaveMutated() {
        Row shared = new Row(1, "one");
        List<Row> items = new ArrayList<>(List.of(shared));
        UIElement list = new UIElement();
        int[] applies = { 0 };
        Projections projections = Projections.create().each(() -> items, list, Row::id,
                item -> new UIText(item.text()),
                (element, item) -> { applies[0]++; ((UIText) element).setText(item.text()); });

        projections.run();
        projections.run();
        projections.run();
        assertEquals("the same instance may have been mutated in place, and skipping it would freeze "
                + "the row silently", 3, applies[0]);
    }

    /** close() drops what a projection holds -- the model, the widgets and every realised row. */
    @Test
    public void closingDropsEverythingItHeld() {
        List<Row> items = new ArrayList<>(List.of(new Row(1, "one")));
        UIElement list = new UIElement();
        Projections projections = rows(items, list);
        projections.run();
        assertEquals(1, projections.size());

        projections.close();
        assertEquals(0, projections.size());
        assertEquals("a closed set must do nothing at all", 0, projections.run());
    }

    // ── Auto-projection, and the report that is the point of it ──────────────

    /** A panel whose widget fields are named the way a panel's are. */
    private static final class Panel {
        Slider throughput = new Slider();
        UIText label = new UIText("");
        Switch power = new Switch();          // the model says isRunning(), so this cannot be matched
    }

    @Test
    public void autoProjectionWiresWhatMatchesByName() {
        Panel panel = new Panel();
        Machine model = new Machine();
        Projections projections = Projections.create();

        AutoProjection.Report report =
                AutoProjection.wire(panel, model, projections);

        assertTrue("throughput() meets the field named throughput", report.wired().containsKey("throughput"));
        assertTrue(report.wired().containsKey("label"));

        model.throughput = 0.4f;
        model.label = "wired";
        projections.run();
        assertEquals(0.4f, panel.throughput.getValue(), 1e-6f);
        assertEquals("wired", panel.label.getText());
    }

    /**
     * <b>The assertion the whole tier turns on.</b>
     *
     * <p>A convention that silently skips a field leaves the widget at whatever it was built with — which
     * usually looks right, and then never moves. So the miss has to be reported, and the report has to
     * say something a reader can act on.</p>
     */
    @Test
    public void autoProjectionReportsWhatItCouldNotWire() {
        Panel panel = new Panel();
        AutoProjection.Report report = AutoProjection.wire(panel, new Machine(), Projections.create());

        assertFalse("power must NOT be wired -- the model says isRunning(), and inventing isPower() "
                + "would be a convention making things up", report.wired().containsKey("power"));

        // UNMATCHED rather than SKIPPED, and the distinction is what keeps the report readable: the
        // model has no accessor for `power`, so it was never a candidate. A `skipped` entry means the
        // data EXISTS and the wiring still could not happen, which is the only kind a reader must act
        // on -- see aWidgetWithNoPrimaryStateIsReportedRatherThanGuessedAt for that half.
        assertTrue("a field with no model accessor must still be accounted for, not vanish",
                report.unmatched().contains("power"));
        assertFalse("...but it is not an actionable skip", report.skipped().containsKey("power"));
    }

    /**
     * A widget already projected onto is left alone — <b>and the order does not matter</b>.
     *
     * <p>The first version matched by field NAME and required explicit projections to be declared
     * first. Both rules were invisible, and breaking either produced a widget written twice a tick by
     * two projections that may disagree, the later one winning. Identity has no such rule, so this
     * asserts the automatic pass running BEFORE the explicit one.</p>
     */
    @Test
    public void aWidgetAlreadyProjectedIsLeftAloneInEitherOrder() {
        Panel panel = new Panel();
        Machine model = new Machine();
        Projections projections = Projections.create();

        projections.onto(panel.throughput, model::throughput, panel.throughput::setValue);
        AutoProjection.Report report = AutoProjection.wire(panel, model, projections);

        assertFalse("already projected -- not a gap and not a second projection",
                report.wired().containsKey("throughput"));
        assertFalse(report.skipped().containsKey("throughput"));
        assertEquals("throughput must not gain a SECOND projection; label is the one auto adds",
                2, projections.size());
        assertTrue("and the automatic pass still did its own job", report.wired().containsKey("label"));
    }

    /**
     * A widget whose contract names no primary state is reported, never guessed at.
     *
     * <p>{@code SplitView} carries {@code WEIGHTS}, {@code Dialog} carries {@code TITLE} — neither is
     * "what the widget is" the way a slider's value is, so there is nothing for a convention to choose
     * and it must say so instead of choosing.</p>
     */
    @Test
    public void aWidgetWithNoPrimaryStateIsReportedRatherThanGuessedAt() {
        final class WithSplit {
            SplitView weights = new SplitView();
        }
        final class Model {
            public List<Float> weights() { return List.of(0.5f, 0.5f); }
        }
        AutoProjection.Report report = AutoProjection.wire(new WithSplit(), new Model(), Projections.create());

        assertFalse(report.wired().containsKey("weights"));
        String why = report.skipped().get("weights");
        assertNotNull(why);
        assertTrue("the reason must point at the missing primary, got: " + why,
                why.contains("primary"));
    }
}
