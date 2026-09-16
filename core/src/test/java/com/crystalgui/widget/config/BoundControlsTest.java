package com.crystalgui.widget.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.ui.dom.ChildList;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.control.ColorControl;
import com.crystalgui.widget.config.control.SliderControl;

/**
 * <b>A control follows everything it was declared with, and nothing has to hold it to push a change.</b>
 */
public class BoundControlsTest extends UiDocumentTestBase {

    /** A range and a unit that are other values reach both halves of a slider while it is on screen. */
    @Test
    public void aLiveRangeAndUnitReachTheSlider() {
        float[] max = {8f};
        String[] unit = {"px"};
        ConfigDescriptor descriptor = ConfigDescriptor.number("stroke", "Stroke")
                .range(() -> new ConfigDescriptor.Range(0f, max[0]))
                .unit(() -> unit[0])
                .step(0.1f).decimals(1);
        SliderControl slider = new SliderControl(descriptor, 2d);
        document.append(slider);
        frame();
        assertTrue(slider.number().field().getText().endsWith("px"));

        max[0] = 13f;
        unit[0] = "%";
        frame();

        assertEquals("the track follows the range", 13f, slider.slider().getMax(), 1e-6);
        assertTrue("and the field the unit: " + slider.number().field().getText(),
                slider.number().field().getText().endsWith("%"));
    }

    /** A part carries every attribute of the number it is part of, suppliers included. */
    @Test
    public void aPartCarriesTheValueAttributes() {
        String[] unit = {"px"};
        ConfigDescriptor whole = ConfigDescriptor.number("size", "Size").range(0f, 96f)
                .unit(() -> unit[0]).step(0.5f).decimals(2).integral(true);
        ConfigDescriptor part = whole.part("size.value", "");

        assertEquals(96f, part.range().max(), 1e-6);
        assertEquals(0.5f, part.step(), 1e-6);
        assertEquals(2, part.decimals());
        assertTrue(part.integral());
        unit[0] = "%";
        assertEquals("the unit is still asked for", "%", part.unit());
    }

    /** Ctrl+Z in a row reaches the history its property's edits go into, with nothing named on the row. */
    @Test
    public void aRowIsUndoneWhereItsPropertyIsEdited() {
        UndoStack history = new UndoStack();
        ConfiguratorPanel panel = new ConfiguratorPanel();
        Configurator row = panel.form().prop(ConfigDescriptor.number("n", "N"), Property.of(1d).editedIn(history));
        assertSame(history, row.getData(UiDataKeys.UNDO_STACK));
    }

    /**
     * <b>A colour dragged in the picker is one undo step</b>, back to the colour before the drag — with the
     * history named on the row alone, as the Element tab names it.
     */
    @Test
    public void aPickerDragIsOneUndoStep() {
        // NO TIME WINDOW, as the builder's document has none: only the drag itself may merge the frames.
        UndoStack history = new UndoStack().setMergeWindowMillis(0L);
        int[] colour = {0xFF000000};
        Property<Integer> value = Property.derived(() -> colour[0], next -> {
            int was = colour[0];
            colour[0] = next;
            history.push(new SetColour(colour, was, next));
        });
        ConfiguratorPanel panel = new ConfiguratorPanel();
        Configurator row = panel.form().prop(ConfigDescriptor.color("c", "Colour"), value).editedIn(history);
        document.append(panel);
        frame();
        ColorControl control = (ColorControl) row.control();

        control.picker().onDragging.emit(true);
        control.picker().onColorChanged.emit(0xFF110000);
        control.picker().onColorChanged.emit(0xFF220000);
        control.picker().onColorChanged.emit(0xFF330000);
        control.picker().onDragging.emit(false);

        assertEquals("one step", 1, history.undoDepth());
        history.undo();
        assertEquals("back to where the drag began", 0xFF000000, colour[0]);
    }

    private record SetColour(int[] cell, int from, int to) implements Edit {
        @Override
        public void apply() {
            cell[0] = to;
        }

        @Override
        public void undo() {
            cell[0] = from;
        }

        @Override
        public Edit mergeWith(Edit next) {
            return next instanceof SetColour later && later.cell == cell ? new SetColour(cell, from, later.to) : null;
        }
    }

    /** Children kept by position survive a resize, so the one under the pointer is never replaced. */
    @Test
    public void aChildListKeepsTheChildrenItHas() {
        UIElement parent = new UIElement();
        ChildList<UIElement> rows = new ChildList<>(parent, index -> new UIElement());
        rows.resize(2);
        UIElement first = rows.get(0);

        rows.resize(3);
        assertSame("growing keeps the first", first, rows.get(0));
        rows.resize(1);
        assertSame("and so does shrinking", first, rows.get(0));
        assertEquals(1, parent.children().size());
    }
}
