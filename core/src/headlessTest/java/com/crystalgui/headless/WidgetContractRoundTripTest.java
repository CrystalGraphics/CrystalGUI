package com.crystalgui.headless;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.ColorSelector;
import com.crystalgui.ui.elements.Dropdown;
import com.crystalgui.ui.elements.ProgressBar;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.Switch;
import com.crystalgui.ui.elements.TabView;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;
import org.junit.Test;

/**
 * <b>M1</b> — a widget's contract carries what its hand-written {@code writeState}/{@code readState}
 * pair carried, and nothing has been lost in the port. {@code plan_ui_rewrite.md} M1.
 *
 * <p>Every test here is the same shape: set a widget up, write it through its contract, apply it to a
 * <em>fresh</em> instance, and assert the second is the first. That is the only assertion that cannot
 * pass against a contract whose slots read one key and write another — checking the encoded map's keys
 * would agree with itself.</p>
 *
 * <p>Headless: no fonts, no GL, no window. State is what a dedicated server writes, so this is where it
 * has to work.</p>
 */
public class WidgetContractRoundTripTest {

    /** Writes {@code from} through its contract and applies it to {@code to}. */
    private static <W extends UIElement> W roundTrip(WidgetContract<W> contract, W from, W to) {
        StateMap<Object> wire = new StateMap<>(PlainOps.INSTANCE);
        contract.write(from, wire);
        contract.read(to, new StateMap<>(PlainOps.INSTANCE, wire.encode()));
        return to;
    }

    // ── Scalars ──────────────────────────────────────────────────────────────

    @Test
    public void button() {
        Button from = new Button("Purge");
        assertEquals("Purge", roundTrip(Button.CONTRACT, from, new Button("")).getText());
    }

    @Test
    public void uiText() {
        assertEquals("hello", roundTrip(UIText.CONTRACT, new UIText("hello"), new UIText("")).getText());
    }

    @Test
    public void checkbox() {
        Checkbox from = new Checkbox("Agree").setChecked(true);
        Checkbox to = roundTrip(Checkbox.CONTRACT, from, new Checkbox(""));
        assertTrue(to.isChecked());
        assertEquals("Agree", to.getLabel());
    }

    @Test
    public void toggleSwitch() {
        Switch from = new Switch();
        from.setChecked(true);
        assertTrue(roundTrip(Switch.CONTRACT, from, new Switch()).isChecked());
    }

    @Test
    public void progressBar() {
        ProgressBar from = new ProgressBar();
        from.setFraction(0.42f);
        assertEquals(0.42f, roundTrip(ProgressBar.CONTRACT, from, new ProgressBar()).fraction(), 0.001f);
    }

    /** Indeterminate is a VALUE, not an absence -- which is why the slot is not omitted at a default. */
    @Test
    public void progressBarKeepsIndeterminate() {
        ProgressBar from = new ProgressBar();
        from.setFraction(-1f);
        ProgressBar to = new ProgressBar();
        to.setFraction(0.5f);
        assertTrue("an indeterminate bar must not arrive as a half-full one",
                roundTrip(ProgressBar.CONTRACT, from, to).fraction() < 0f);
    }

    // ── Ordered state, which is what the contract's declaration order is for ──

    @Test
    public void sliderTakesItsRangeBeforeItsValue() {
        Slider from = new Slider();
        from.setRange(0f, 100f);
        from.setValue(80f);

        // A fresh slider's range is 0..1, so a value applied first would be clamped to 1 and the range
        // applied afterwards could not recover it. This is the test that fails if the slots are reordered.
        Slider to = roundTrip(Slider.CONTRACT, from, new Slider());
        assertEquals(100f, to.getMax(), 0.001f);
        assertEquals(80f, to.getValue(), 0.001f);
    }

    @Test
    public void aSliderRefusesANaNValueFromThePeer() {
        // sanitizedBy. NaN fails every comparison, so a range check written the obvious way passes it
        // through, and it then poisons every layout it reaches.
        Slider to = new Slider();
        StateMap<Object> wire = new StateMap<>(PlainOps.INSTANCE);
        wire.putFloat("min", 0f).putFloat("max", 1f).putFloat("step", 0f).putFloat("value", Float.NaN);
        Slider.CONTRACT.read(to, new StateMap<>(PlainOps.INSTANCE, wire.encode()));
        assertFalse("a NaN must not reach the widget", Float.isNaN(to.getValue()));
    }

    @Test
    public void dropdownTakesItsOptionsBeforeItsIndex() {
        Dropdown from = new Dropdown("");
        from.addOptions("alpha", "beta", "gamma");
        from.select(2);

        Dropdown to = roundTrip(Dropdown.CONTRACT, from, new Dropdown(""));
        assertEquals(List.of("alpha", "beta", "gamma"), to.getOptions());
        assertEquals("an index into a list that had not arrived yet would be refused", 2, to.getSelectedIndex());
    }

    @Test
    public void anAbsentOptionListLeavesTheDropdownAlone() {
        // The guard the hand-written readState had: absent means "leave what is there", not "empty it".
        Dropdown to = new Dropdown("");
        to.addOptions("kept");
        StateMap<Object> wire = new StateMap<>(PlainOps.INSTANCE);
        wire.putInt("selected", 0);
        Dropdown.CONTRACT.read(to, new StateMap<>(PlainOps.INSTANCE, wire.encode()));
        assertEquals(List.of("kept"), to.getOptions());
    }

    @Test
    public void colorSelectorTakesItsModeAndOriginalFirst() {
        ColorSelector from = new ColorSelector();
        from.setInitialColor(0xFF102030);
        from.setColor(0xFF405060);

        ColorSelector to = roundTrip(ColorSelector.CONTRACT, from, new ColorSelector());
        assertEquals(0xFF102030, to.getOriginalColor());
        assertEquals("setInitialColor moves the live colour, so applying it AFTER the colour would "
                + "overwrite what was actually sent", 0xFF405060, to.getColor());
    }

    @Test
    public void textFieldTakesItsModeBeforeItsText() {
        TextField from = new TextField();
        from.setPlaceholder("search");
        from.setText("42");

        TextField to = roundTrip(TextField.CONTRACT, from, new TextField());
        assertEquals("42", to.getText());
        assertEquals("search", to.getPlaceholder());
    }

    // ── Collections ──────────────────────────────────────────────────────────

    @Test
    public void splitViewWeights() {
        SplitView from = new SplitView();
        from.setWeights(new float[] { 0.3f, 0.7f });
        assertArrayEquals(new float[] { 0.3f, 0.7f },
                roundTrip(SplitView.CONTRACT, from, new SplitView()).getWeights(), 0.001f);
    }

    @Test
    public void tabViewSelection() {
        TabView from = new TabView();
        from.addTab("one");
        from.addTab("two");
        from.selectIndex(1);

        TabView to = new TabView();
        to.addTab("one");
        to.addTab("two");
        assertEquals(1, roundTrip(TabView.CONTRACT, from, to).getSelectedIndex());
    }

    // ── Omission, which is what keeps a description hashable ─────────────────

    @Test
    public void aDefaultValuedWidgetWritesNothing() {
        StateMap<Object> wire = new StateMap<>(PlainOps.INSTANCE);
        Button.CONTRACT.write(new Button(""), wire);
        assertTrue("an empty button must carry NO state, not state that happens to be empty -- the "
                + "description is content-hashed, so present-and-default and absent are different bytes",
                wire.isEmpty());
    }

    // ── Events ───────────────────────────────────────────────────────────────

    @Test
    public void anEventAttachesThroughTheContractAndCarriesItsPayload() {
        // The whole point of Event.attach: no instanceof, no switch, and it works for a widget the
        // networking layer has never heard of.
        Slider slider = new Slider();
        slider.setRange(0f, 10f);
        AtomicReference<Float> heard = new AtomicReference<>();

        Event<Slider, Float> event = (Event<Slider, Float>) Slider.CONTRACT.event("value");
        event.attach(slider, heard::set);
        slider.setValue(7f);

        assertEquals(7f, heard.get(), 0.001f);

        StateMap<Object> payload = event.encode(PlainOps.INSTANCE, heard.get());
        assertEquals(7f, event.decode(new StateMap<>(PlainOps.INSTANCE, payload.encode())), 0.001f);
    }

    @Test
    public void theFiveWidgetsThatCouldNotReportNowCan() {
        // plan_ui_rewrite.md M1. Before contracts these five had no kind at all: the client's wiring was
        // a switch over four kinds with an instanceof chain in each arm, and a widget outside it hit a
        // default that logged and carried on.
        assertTrue(Dropdown.CONTRACT.eventKinds().contains("select"));
        assertTrue(TabView.CONTRACT.eventKinds().contains("select"));
        assertTrue(ColorSelector.CONTRACT.eventKinds().contains("change"));
        assertTrue(SplitView.CONTRACT.eventKinds().contains("value"));
        assertTrue(com.crystalgui.ui.elements.Tab.CONTRACT.eventKinds()
                .contains("closeRequested"));
    }

    @Test
    public void aDropdownReportsWhatWasChosen() {
        Dropdown dropdown = new Dropdown("");
        dropdown.addOptions("alpha", "beta");
        AtomicInteger heard = new AtomicInteger(-99);

        Event<Dropdown, Integer> event = (Event<Dropdown, Integer>) Dropdown.CONTRACT.event("select");
        event.attach(dropdown, heard::set);
        dropdown.select(1);

        assertEquals(1, heard.get());
    }

    // ── Rate policy ──────────────────────────────────────────────────────────

    @Test
    public void aWidgetDeclaresItsOwnTempo() {
        // The right answer is a property of the interaction, not of the application: a handler author
        // has no way to know a TextField fires per keystroke without reading the widget.
        assertEquals(RatePolicy.TYPING, TextField.CONTRACT.event("text").rate());
        assertEquals(RatePolicy.DRAGGING, Slider.CONTRACT.event("value").rate());
        assertEquals(RatePolicy.IMMEDIATE, Button.CONTRACT.event("activate").rate());

        assertTrue("a throttled event must always deliver its FINAL value -- dropping intermediates is "
                        + "fine, dropping the last one is data loss",
                Slider.CONTRACT.event("value").rate().commitOnRelease());
    }
}
