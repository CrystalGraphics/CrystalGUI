package com.crystalgui.headless;

import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoStack;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * A {@link Property}: what it reads, what it writes, when it says so, and what follows it.
 *
 * <p>Every config control sits on one of these, so an extra emit is a loop in a panel and a missing one is
 * a field that ignores undo.</p>
 */
public class PropertyTest {

    /** A model that clamps, the way a real one does. */
    private static final class Model {
        double value;
        final Signal.Action onChanged = new Signal.Action();

        void set(double next) {
            value = Math.max(0d, Math.min(100d, next));
            onChanged.emit();
        }
    }

    @Test
    public void aDerivedPropertyReadsBackWhatTheModelKept() {
        Model model = new Model();
        Property<Double> property = Property.derived(() -> model.value, model::set);
        List<Double> seen = new ArrayList<>();
        property.changed.connect((was, now) -> seen.add(now));

        property.set(250d);

        assertEquals(100d, model.value, 0d);
        assertEquals("the value the model kept, not the one it was offered", List.of(100d), seen);
    }

    @Test
    public void aModelMovingOnItsOwnIsHeardOnlyWhenRefreshed() {
        Model model = new Model();
        Property<Double> property = Property.derived(() -> model.value, model::set);
        List<Double> seen = new ArrayList<>();
        property.changed.connect((was, now) -> seen.add(now));

        assertFalse("the first refresh has nothing to have moved from", property.refresh());
        model.value = 40d;
        assertTrue(property.isPolled());
        assertEquals("a derived property reads through", 40d, property.get(), 0d);
        assertTrue(seen.isEmpty());

        assertTrue(property.refresh());
        assertFalse("and does not report the same move twice", property.refresh());
        assertEquals(List.of(40d), seen);
    }

    @Test
    public void anAnnouncedPropertyEmitsOnceForItsOwnWrite() {
        Model model = new Model();
        Property<Double> property = Property.derived(() -> model.value, model::set)
                .announcedBy(refresh -> model.onChanged.connect(refresh::run));
        assertFalse(property.isPolled());
        Connection watching = property.watchSource();
        List<Double> seen = new ArrayList<>();
        property.changed.connect((was, now) -> seen.add(now));

        property.set(10d);
        assertEquals("the model announcing the write must not be heard as a second change",
                List.of(10d), seen);

        model.set(20d);
        assertEquals("a change made elsewhere arrives through the announcement", List.of(10d, 20d), seen);

        watching.disconnect();
        model.set(30d);
        assertEquals("nothing after the watch is dropped", List.of(10d, 20d), seen);
    }

    @Test(expected = IllegalStateException.class)
    public void aReadOnlyPropertyRefusesAWrite() {
        Property<String> fact = Property.derived(() -> "64 x 24");
        assertTrue(fact.isReadOnly());
        fact.set("other");
    }

    @Test
    public void aMapWritesThroughAndFollowsItsUpstream() {
        String[] stored = {"1.5"};
        UndoStack history = new UndoStack();
        Property<String> text = Property.derived(() -> stored[0], next -> stored[0] = next).editedIn(history);
        Property<Double> number = text.map(Double::parseDouble, String::valueOf);

        assertSame("a map records where its upstream does", history, number.history());
        assertTrue("and is polled when its upstream is", number.isPolled());

        number.set(2.5d);
        assertEquals("2.5", stored[0]);

        List<Double> seen = new ArrayList<>();
        number.changed.connect((was, now) -> seen.add(now));
        number.refresh();
        stored[0] = "7.0";
        assertTrue(number.refresh());
        assertEquals(List.of(7d), seen);
    }

    @Test
    public void aStoredMapHearsItsUpstreamWithoutPolling() {
        Property<Integer> count = Property.of(3);
        Property<String> label = count.map(String::valueOf, Integer::parseInt);
        assertFalse(label.isPolled());
        label.refresh();
        Connection watching = label.watchSource();
        List<String> seen = new ArrayList<>();
        label.changed.connect((was, now) -> seen.add(now));

        count.set(4);
        label.set("9");

        assertEquals(9, (int) count.get());
        assertEquals(List.of("4", "9"), seen);
        watching.disconnect();
    }

    // ── Binding ───────────────────────────────────────────────────────────────────────────────────

    @Test
    public void bindToWithTransformAppliesImmediately() {
        Property<Integer> source = new Property<>(5);
        Property<String> target = new Property<>("");

        target.bindTo(source, i -> "n=" + i);

        assertEquals("n=5", target.get());
    }

    @Test
    public void bindToWithTransformPropagatesSourceChanges() {
        Property<Integer> source = new Property<>(5);
        Property<String> target = new Property<>("");

        target.bindTo(source, i -> "n=" + i);
        source.set(9);

        assertEquals("n=9", target.get());
    }

    @Test
    public void bindToWithTransformDisconnectStopsPropagation() {
        Property<Integer> source = new Property<>(1);
        Property<String> target = new Property<>("");

        Connection connection = target.bindTo(source, i -> "n=" + i);
        connection.disconnect();
        source.set(42);

        assertEquals("n=1", target.get());
    }

    @Test
    public void bindToWithTransformRejectsNullArguments() {
        Property<Integer> source = new Property<>(1);
        Property<String> target = new Property<>("");

        try {
            target.bindTo(null, i -> "n=" + i);
            fail("expected IllegalArgumentException for null source");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            target.bindTo(source, null);
            fail("expected IllegalArgumentException for null transform");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
