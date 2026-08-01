package com.crystalgui.ui;

import com.crystalgui.render.texture.CgUiShape;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Dropdown;
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
public class DropdownChevronTest extends UiTestBase {

    @Test
    public void aDropdownCarriesAChevronShape() {
        UIElement root = new UIElement();
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(400, 600);

        Dropdown dropdown = new Dropdown("Pick one");
        dropdown.addOptions("One", "Two");
        root.addChild(dropdown);
        window.updateWithoutPainting();

        List<UIElement> chevrons = dropdown.querySelectorAll("." + Dropdown.CHEVRON_CLASS);
        assertEquals("exactly one chevron marker", 1, chevrons.size());

        var overlay = chevrons.get(0).getStyle().getGeneralGroup().overlay();
        assertTrue("the marker must actually draw the chevron shape, not just claim the class",
                overlay instanceof CgUiShape
                        && ((CgUiShape) overlay).getKind() == CgUiShape.Kind.CHEVRON_DOWN);
    }
}
