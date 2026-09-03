package com.crystalgui.ui.service;

import static org.junit.Assert.assertEquals;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import org.junit.Test;

/**
 * A frozen subtree matches no selector — the half of {@code FreezeTest} that needs real CSS.
 *
 * <p>It lives here rather than beside the rest because {@code StyleSheet}'s class initializer reads
 * {@code default.css} through {@code CgIO}, so the whole class is unloadable headlessly: CSS text
 * belongs in {@code test}, never in {@code headlessTest}.</p>
 */
public class FreezeStyleTest extends UiDocumentTestBase {

    @Test
    public void aFrozenSubtreeMatchesNoSelectorAndCatchesUpOnTheWayBack() {
        UIDocument document = new UIDocument();
        document.styles().addStylesheet(StyleSheet.parse(".lit { opacity: 0.25 }"));
        UIElement panel = new UIElement().setId("panel");
        StyleGroup.inlinePipeline(panel.getStyle().getLayoutGroup(), l -> l.width(200f).height(200f));
        document.append(panel);
        document.update(800f, 600f);

        document.lifecycle().freeze(panel);
        panel.addClass("lit");
        document.update(800f, 600f);
        assertEquals("re-matching a subtree nobody can see is work for nothing",
                1f, panel.computedStyle().get(StylePropertyRegistry.OPACITY), 0.001f);

        document.lifecycle().thaw(panel);
        document.update(800f, 600f);
        assertEquals("and it picks up what changed while it was away, on the way back",
                0.25f, panel.computedStyle().get(StylePropertyRegistry.OPACITY), 0.001f);
    }
}
