package com.crystalgui.widget.overlay;

import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.render.texture.CgUiShape;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * <b>A dropdown must carry a visible closed-state marker.</b>
 *
 * <p>{@code dropdown { justify-content: space-between }} in {@code default.css} had nothing to space
 * against until {@link Dropdown} claimed {@code Button}'s post-icon slot for a chevron — without a
 * second child the rule was silently inert, which is exactly the class of bug this repo's own
 * {@code AGENTS.md} keeps recording: a property that resolves but changes nothing looks identical to
 * one that was never written.</p>
 */
public class DropdownChevronTest extends UiDocumentTestBase {

    @Test
    public void aDropdownCarriesAChevronShape() {
        UIElement root = new UIElement();
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);

        Dropdown dropdown = new Dropdown("Pick one");
        dropdown.addOptions("One", "Two");
        root.append(dropdown);
        frame();

        UIElement chevron = dropdown.getPostIcon();
        assertNotNull("a dropdown must carry its disclosure arrow", chevron);
        assertEquals("and it is the button's post-icon slot, by that name",
                Dropdown.CHEVRON_PART, chevron.get(Attribute.PART));
        List<UIElement> chevrons = List.of(chevron);

        var overlay = chevrons.get(0).getStyle().getGeneralGroup().overlay();
        assertTrue("the marker must actually draw the chevron shape, not just claim the class",
                overlay instanceof CgUiShape
                        && ((CgUiShape) overlay).kind() == CgUiShape.Kind.CHEVRON_DOWN);
    }
}
