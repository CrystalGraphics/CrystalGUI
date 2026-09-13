package com.crystalgui.widget.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.control.AnchorControl;
import com.crystalgui.widget.config.control.BooleanControl;
import com.crystalgui.widget.config.control.InfoControl;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.config.control.SliderControl;

/**
 * <b>A control bound to a {@link Property} is a view of it</b>: an edit writes it, a change made anywhere
 * else shows up, a field holding an edit is left alone, and a gesture is one step of the property's history.
 */
public class BoundControlTest extends UiDocumentTestBase {

    private NumberControl numberOver(Property<Double> value) {
        NumberControl control = new NumberControl(ConfigDescriptor.number("n", "N"), 0d);
        control.bind(value);
        document.append(control);
        document.frame(0f, W, H);
        return control;
    }

    @Test
    public void anEditWritesTheProperty() {
        Property<Double> stored = Property.of(1d);
        NumberControl control = numberOver(stored);
        List<Object> emitted = new ArrayList<>();
        control.changed.connect(emitted::add);

        control.field().setText("5");

        assertEquals(5d, stored.get(), 0d);
        assertEquals("an edit is reported with what the property kept", List.of(5d), emitted);
    }

    @Test
    public void aStoredChangeShowsAtOnceAndIsNotAnEdit() {
        Property<Double> stored = Property.of(1d);
        NumberControl control = numberOver(stored);
        List<Object> emitted = new ArrayList<>();
        control.changed.connect(emitted::add);

        stored.set(7d);

        assertEquals("7", control.field().getText());
        assertTrue("following the property is not an edit", emitted.isEmpty());
    }

    /** Nothing tells a derived property that its model moved, so the control re-reads it after layout. */
    @Test
    public void aModelThatMovesOnItsOwnShowsAfterTheNextLayout() {
        double[] model = {2d};
        NumberControl control = numberOver(Property.derived(() -> model[0], v -> model[0] = v));

        model[0] = 9d;
        assertEquals("2", control.field().getText());
        document.frame(0f, W, H);
        assertEquals("9", control.field().getText());
    }

    /** A field nobody can see is not read every frame, and shows what moved once it is seen again. */
    @Test
    public void aHiddenFieldIsNotPolledAndCatchesUpWhenShown() {
        withDefaultStyles();
        ConfiguratorPanel panel = new ConfiguratorPanel();
        ConfiguratorGroup group = panel.group("Computed", true);
        panel.append(group);
        int[] reads = {0};
        String[] model = {"a"};
        Configurator row = panel.propTo(group.content(), ConfigDescriptor.info("n", "N"),
                Property.derived(() -> {
                    reads[0]++;
                    return model[0];
                }));
        document.append(panel);
        document.frame(0f, W, H);

        int settled = reads[0];
        model[0] = "b";
        for (int i = 0; i < 5; i++) document.frame(0f, W, H);
        assertEquals("a collapsed group's rows were read every frame", settled, reads[0]);

        group.setCollapsed(false);
        document.frame(0f, W, H);
        document.frame(0f, W, H);
        assertEquals("b", ((InfoControl) row.control()).text().getText());
    }

    /** A model that clamps keeps less than it was offered, and the field says what it kept. */
    @Test
    public void aClampingModelIsShownWhatItKept() {
        double[] model = {0d};
        NumberControl control = numberOver(Property.derived(() -> model[0], v -> model[0] = Math.min(v, 10d)));

        control.field().setText("50");

        assertEquals(10d, model[0], 0d);
        assertEquals("10", control.field().getText());
    }

    /** Dear ImGui's rule and Unity's: what is being typed is the user's until it lands. */
    @Test
    public void aFieldHoldingAnEditIsNotOverwritten() {
        Property<Double> stored = Property.of(1d);
        NumberControl control = numberOver(stored);

        control.field().insert("4");
        stored.set(8d);
        document.frame(0f, W, H);
        assertEquals("the change elsewhere wrote over the typing", "14", control.field().getText());

        control.field().commit();
        assertEquals("and what was typed lands when it is entered", 14d, stored.get(), 0d);
    }

    @Test
    public void aReadOnlyPropertyIsShownAndNeverWritten() {
        Property<Double> fact = Property.derived(() -> 3d);
        NumberControl control = numberOver(fact);

        control.field().setText("6");

        assertEquals(3d, fact.get(), 0d);
    }

    /** However many values a drag writes, the history holds one step for it. */
    @Test
    public void aGestureIsOneStepOfTheHistory() {
        UndoStack history = new UndoStack();
        double[] model = {0d};
        Property<Double> value = Property.derived(() -> model[0], v -> {
            history.push(new SetValue(model, model[0], v));
            model[0] = v;
        }).editedIn(history);
        NumberControl control = numberOver(value);

        control.interacting.emit(true);
        control.setValue(1d);
        control.setValue(2d);
        control.setValue(3d);
        control.interacting.emit(false);

        assertEquals(1, history.undoDepth());
        assertTrue(history.undo());
        assertEquals("one undo puts the whole drag back", 0d, model[0], 0d);
        assertFalse("and the next edit is a step of its own", history.isMergeRunHeld());
    }

    /** A drag along the track is one gesture of the slider's, from the press to the release. */
    @Test
    public void aSliderDragIsOneGesture() {
        withDefaultStyles();
        SliderControl control = new SliderControl(ConfigDescriptor.number("s", "S").range(0f, 1f), 0d);
        document.append(control);
        document.frame(0f, W, H);
        List<Boolean> gesture = new ArrayList<>();
        control.interacting.connect(gesture::add);

        int[] at = centreOf(control.slider());
        press(at[0], at[1]);
        move(at[0] + 10, at[1]);
        move(at[0] + 20, at[1]);
        release(at[0] + 20, at[1]);

        assertEquals(List.of(true, false), gesture);
    }

    /** Rebinding reads the new property and stops following the old one. */
    @Test
    public void rebindingFollowsOnlyTheNewProperty() {
        Property<Double> first = Property.of(1d);
        Property<Double> second = Property.of(2d);
        NumberControl control = numberOver(first);

        control.bind(second);
        assertEquals("2", control.field().getText());
        first.set(5d);
        assertEquals("2", control.field().getText());
        second.set(6d);
        assertEquals("6", control.field().getText());
    }

    @Test
    public void anAnchorLightsTheCellItsValueSitsOnAndWritesTheOneChosen() {
        withDefaultStyles();
        Property<double[]> pivot = Property.of(new double[] {0.5d, 0.5d});
        AnchorControl anchor = new AnchorControl(ConfigDescriptor.anchor("pivot", "Pivot"), null);
        anchor.bind(pivot);
        document.append(anchor);
        document.frame(0f, W, H);
        assertEquals("the centre", 4, anchor.litCell());

        pivot.set(new double[] {0.3d, 0.5d});
        assertEquals("between cells, none is lit", -1, anchor.litCell());

        UIElement bottomRight = anchor.cells().get(8);
        int[] at = centreOf(bottomRight);
        press(at[0], at[1]);
        release(at[0], at[1]);
        assertArrayEquals(new double[] {1d, 1d}, pivot.get(), 0d);
        assertTrue(bottomRight.hasClass(AnchorControl.ON_CLASS));
    }

    @Test
    public void aToggleIsAButtonThatStaysPressed() {
        Property<Boolean> linked = Property.of(false);
        BooleanControl toggle = new BooleanControl(ConfigDescriptor.bool("link", "Link").toggle(true), false);
        toggle.bind(linked);
        document.append(toggle);

        toggle.toggleButton().onPressed.emit();
        assertTrue(linked.get());
        assertTrue(toggle.toggleButton().hasClass(BooleanControl.ON_CLASS));

        linked.set(false);
        assertFalse("and follows the property back", toggle.toggleButton().hasClass(BooleanControl.ON_CLASS));
    }

    /** A cell shows the short label and hints the full one, and the form reports edits by id. */
    @Test
    public void aToolbarCellShowsItsLetterAndReportsById() {
        UIElement page = new UIElement();
        ToolbarForm form = ToolbarForm.into(page);
        Property<Double> width = Property.of(100d);
        Configurator cell = form.prop(ConfigDescriptor.number("w", "Width").shortLabel("W").unit("%"), width);
        document.append(page);
        document.frame(0f, W, H);
        List<String> ids = new ArrayList<>();
        form.changed.connect((id, value) -> ids.add(id));

        ((NumberControl) cell.control()).field().setText("50");

        assertEquals("W", cell.label().getText());
        assertEquals("Width", cell.hint().getText());
        assertEquals(50d, width.get(), 0d);
        assertEquals(List.of("w"), ids);
    }

    /** Sets one value; consecutive ones merge, keeping the first before and the last after. */
    private record SetValue(double[] model, double before, double after) implements Edit {
        @Override
        public void apply() {
            model[0] = after;
        }

        @Override
        public void undo() {
            model[0] = before;
        }

        @Override
        public Edit mergeWith(Edit next) {
            return next instanceof SetValue later && later.model == model ? new SetValue(model, before, later.after) : null;
        }
    }
}
