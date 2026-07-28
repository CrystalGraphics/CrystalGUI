package com.crystalgui.headless;

import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.Switch;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;
import com.google.gson.JsonElement;
import org.junit.Test;

import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Each widget's {@code writeState}/{@code readState} pair must be symmetric: mutate a widget, encode,
 * decode into a <em>fresh</em> instance, and get the same values back.
 *
 * <p>Deliberately in the headless source set. Widget state is what a server sends, so it has to be
 * writable and readable with no CrystalGraphics — and {@code UIText}/{@code TextField} would reach
 * the font stack if anything here accidentally triggered layout.</p>
 */
public class WidgetStateRoundTripTest {

    /** Mutate → write → read into a fresh instance → assert. The shape every case below uses. */
    private <E extends UIElement> E roundTrip(Supplier<E> factory, Consumer<E> mutate) {
        E original = factory.get();
        mutate.accept(original);

        StateMap<JsonElement> written = new StateMap<>(JsonOps.INSTANCE);
        original.writeStateTo(written);

        E restored = factory.get();
        restored.readStateFrom(new StateMap<>(JsonOps.INSTANCE, written.encode()));
        return restored;
    }

    @Test
    public void uiTextRoundTripsItsContent() {
        assertEquals("hello world", roundTrip(() -> new UIText(""), t -> t.setText("hello world")).getText());
    }

    /** The label is an internal child that never travels — but its content is authored state. */
    @Test
    public void buttonRoundTripsItsLabel() {
        assertEquals("Click me", roundTrip(() -> new Button(""), b -> b.setText("Click me")).getText());
    }

    @Test
    public void checkboxRoundTripsCheckedAndLabel() {
        Checkbox restored = roundTrip(Checkbox::new, c -> c.setChecked(true).setLabel("I agree"));
        assertTrue(restored.isChecked());
        assertEquals("I agree", restored.getLabel());
    }

    @Test
    public void switchRoundTripsChecked() {
        assertTrue(roundTrip(Switch::new, s -> s.setChecked(true)).isChecked());
    }

    /**
     * The ordering trap. {@code setValue} clamps and snaps against the <em>current</em> range and
     * step, so restoring value-before-range clamps 42 into the default 0..1 and silently yields 1.
     */
    @Test
    public void sliderRoundTripsRangeStepAndValueInThatOrder() {
        Slider restored = roundTrip(Slider::new, s -> s.setRange(0f, 100f).setStep(5f).setValue(42f));
        // 42 snaps to 40, the nearest multiple of 5. The number that matters is that it is NOT 1.0 —
        // that is what restoring the value before the range would give, clamping into the default
        // 0..1 range and losing it silently.
        assertEquals("range must be restored before the value, or the value is clamped away",
                40f, restored.getValue(), 0.001f);
        assertEquals(5f, restored.getStep(), 0.001f);
        assertEquals(100f, restored.getMax(), 0.001f);
    }

    @Test
    public void textFieldRoundTripsBothStringsAndItsConfiguration() {
        TextField restored = roundTrip(TextField::new, f -> {
            f.setMode(TextField.Mode.INTEGER);
            f.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
            f.setPlaceholder("enter a number");
            f.setText("42");
        });
        assertEquals("42", restored.getText());
        assertEquals("enter a number", restored.getPlaceholder());
        assertEquals(TextField.Mode.INTEGER, restored.getMode());
        assertEquals(TextField.UpdateMode.IMMEDIATE, restored.getUpdateMode());
    }

    @Test
    public void tabRoundTripsItsLabel() {
        assertEquals("Settings", roundTrip(() -> new Tab(""), t -> t.setText("Settings")).getText());
    }

    // ── Properties of the mechanism itself ──────────────────────────────────

    /** A default-valued widget should carry nothing, so the common node stays small on the wire. */
    @Test
    public void anUnmodifiedWidgetWritesNoState() {
        StateMap<JsonElement> out = new StateMap<>(JsonOps.INSTANCE);
        new Checkbox().writeStateTo(out);
        assertTrue("a default Checkbox should serialize to nothing at all", out.isEmpty());

        StateMap<JsonElement> textOut = new StateMap<>(JsonOps.INSTANCE);
        new UIText("").writeStateTo(textOut);
        assertTrue(textOut.isEmpty());
    }

    /** Reading a description written before a key existed must not wipe the widget's defaults. */
    @Test
    public void readingAnEmptyStateLeavesDefaultsIntact() {
        Slider slider = new Slider();
        slider.setRange(0f, 10f).setValue(7f);
        slider.readStateFrom(new StateMap<>(JsonOps.INSTANCE,
                new StateMap<JsonElement>(JsonOps.INSTANCE).encode()));
        // Absent keys fall back to the documented defaults rather than throwing.
        assertEquals(0f, slider.getValue(), 0.001f);
    }

    /** The base element has no state of its own — it is pure structure. */
    @Test
    public void aPlainElementHasNoState() {
        StateMap<JsonElement> out = new StateMap<>(JsonOps.INSTANCE);
        new UIElement().writeStateTo(out);
        assertTrue(out.isEmpty());
    }
}
