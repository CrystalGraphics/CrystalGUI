package com.crystalgui.app.uibuilder.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;

/** B.5: an addon's kind is listed with no builder change, and the strips follow the panel's width. */
public class LibraryPanelTest extends UiDocumentTestBase {

    private static final Name GIZMO = Name.of("librarytest", "gizmo");

    private UIElement host;
    private LibraryPanel panel;

    @Before
    public void panel() {
        UIElementRegistry.bootstrap();
        UIElementRegistry.register(GIZMO, UIElement::new, UIElementRegistry.plain(GIZMO, true));
        withDefaultStyles();
        host = sized("host", 300f, 400f);
        panel = new LibraryPanel(LibraryCatalog.current());
        host.append(panel);
        document.append(host);
        settle();
    }

    @Test
    public void anAddonKindIsListedWithNoDeclaration() {
        panel.search().searchBox().setText("gizmo");
        settle();

        assertTrue("the addon's card is on screen", kinds().contains(GIZMO));
    }

    @Test
    public void narrowingThePanelReflowsTheStrips() {
        int wide = panel.cardsPerStrip();
        assertTrue("several cards fit 300px, got " + wide, wide >= 2);

        layout(host, l -> l.width(120f));
        settle();

        assertEquals(1, panel.cardsPerStrip());
        assertTrue("Common's cards are still on screen", kinds().size() >= 1);
    }

    @Test
    public void compactRowsListEachKindOnARowOfItsOwn() {
        panel.setRows(true);
        settle();

        assertTrue(panel.realisedCards().isEmpty());
        assertTrue(panel.tree().visibleRows().stream().anyMatch(row -> row.item() instanceof LibraryPanel.Item));
    }

    @Test
    public void selectingACardDescribesItAtTheFootAndOnHover() {
        PreviewCard button = panel.realisedCards().stream()
                .filter(card -> card.entry().kind().equals(Button.NAME)).findFirst().orElseThrow();
        int[] centre = centreOf(button);
        click(centre[0], centre[1]);
        settle();

        assertEquals(Button.NAME, panel.selected().kind());
        assertEquals("A labelled push button.", panel.detail().descriptionText());
        assertTrue(button.hoverText().contains("A labelled push button."));
    }

    private List<Name> kinds() {
        List<Name> out = new ArrayList<>();
        for (PreviewCard card : panel.realisedCards()) out.add(card.entry().kind());
        return out;
    }

    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }
}
