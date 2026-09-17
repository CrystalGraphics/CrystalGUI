package com.crystalgui.widget.config.control;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.testsupport.UiDocumentTestBase;

/** A soft range is where a slider's track ends; a typed number is held only to the range. */
public class SoftRangeTest extends UiDocumentTestBase {

    private final ConfigDescriptor radius = ConfigDescriptor.number("r", "Radius")
            .range(0f, 9999f).softRange(0f, 45f).integral(true);

    @Test
    public void theTrackIsTheSoftRange() {
        SliderControl control = new SliderControl(radius, 0d);
        assertEquals(45f, control.slider().getMax(), 0f);
    }

    @Test
    public void aTypedNumberPassesTheSoftRange() {
        NumberControl control = new NumberControl(radius, 0d);
        document.append(control);
        frame();
        control.field().setText("999");
        frame();
        assertEquals(999d, control.getValue(), 0d);
    }

    @Test
    public void noSoftRangeIsTheRange() {
        ConfigDescriptor plain = ConfigDescriptor.number("p", "Plain").range(0f, 8f);
        assertEquals(8f, plain.softRange().max(), 0f);
    }
}
