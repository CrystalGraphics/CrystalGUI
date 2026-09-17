package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;

/**
 * <b>A press inside a lab belongs to the lab, not to the chip that opened it.</b>
 *
 * <p>The chip listens for a press to open its lab. If the lab is in the chip's own subtree — or if the
 * chip's listener sees presses that landed inside the lab — then every click in the lab re-opens it, the
 * cursor over the lab is the chip's pointer, and nothing a person clicks ever happens.</p>
 */
public class StyleChipReentryTest extends UiDocumentTestBase {

    private StyleChip chip;
    private Property<String> css;
    private int opens;

    @Before
    public void openTheLab() {
        css = Property.of("linear-gradient(180deg, #6AA9FF, #8A8AFF 50%, #C86AFF)");
        chip = new StyleChip(ConfigDescriptor.text("bg", "background"), StylePropertyRegistry.BACKGROUND);
        chip.bind(css);
        chip.onOpen(() -> {
            opens++;
            GradientLab.open(chip, StylePropertyRegistry.BACKGROUND, css);
        });
        chip.layout(l -> l.width(120).height(16));
        document.append(chip);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.update(W, H);
        frame();

        click(centre(chip.box())[0], centre(chip.box())[1]);
        for (int i = 0; i < 6; i++) frame();
        assertEquals("the press opened the lab", 1, opens);
    }

    @Test
    public void pressingInsideTheLabDoesNotReopenIt() {
        Button keyword = keyword("Remove");
        assertNotNull("the lab is up", keyword);

        float[] at = centre(keyword.box());
        click(at[0], at[1]);
        for (int i = 0; i < 4; i++) frame();

        assertEquals("the chip did not see the press", 1, opens);
        assertEquals("and the lab did", "linear-gradient(180deg, #8A8AFF 50%, #C86AFF)", css.get());
    }

    /** The lab is not inside the chip: a popover lives in the top layer, or it inherits the chip's everything. */
    @Test
    public void theLabIsNotAChildOfTheChip() {
        Button keyword = keyword("Remove");
        assertNotNull(keyword);
        for (UIElement each : chip.composedSubtree()) {
            assertTrue("the lab is in the chip's subtree: " + each, each != keyword);
        }
    }

    private static float[] centre(Box box) {
        return new float[]{box.localToWorld().m30() + box.width() / 2f,
                box.localToWorld().m31() + box.height() / 2f};
    }

    private Button keyword(String text) {
        for (UIElement each : document.composedSubtree()) {
            if (each instanceof Button button && text.equals(button.getText())) return button;
        }
        return null;
    }
}
