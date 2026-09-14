package com.crystalgui.app.uibuilder.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;

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

    /** The Starters folder draws each snippet as its card, the way a kind's card draws its sample. */
    @Test
    public void aStarterHasACardDrawingItsSnippet() {
        expand(LibraryStarters.FOLDER);
        for (int i = 0; i < 30; i++) frame();

        PreviewCard card = panel.realisedCards().stream()
                .filter(shown -> shown.entry() == LibraryStarters.CARD).findFirst().orElse(null);
        assertNotNull("the Card starter has no card on screen", card);
        assertNotNull("the Card starter's snippet was never built", card.sample());
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
    public void aKindInTwoCategoriesHasACardInEach() {
        for (LibraryPanel.Row row : panel.tree().roots()) {
            if (row instanceof LibraryPanel.Folder folder && folder.label().equals("Controls")) {
                panel.tree().setExpanded(folder, true);
            }
        }
        settle();

        assertEquals("Button is in Common and in Controls", 2,
                kinds().stream().filter(Button.NAME::equals).count());
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

    @Test
    public void aUserGroupListsAfterCommonAndPlacesWhatEitherGroupHolds() {
        UserLibrary mine = UserLibrary.in(null);
        panel.useLibrary(mine);
        mine.createGroup("Favourites");
        mine.addToGroup("Favourites", Button.NAME);
        expand("Favourites");
        settle();

        List<String> folders = new ArrayList<>();
        for (LibraryPanel.Row row : panel.tree().roots()) {
            if (row instanceof LibraryPanel.Folder folder) folders.add(folder.label());
        }
        assertEquals(List.of(LibraryStarters.FOLDER, "Common", "Favourites"), folders.subList(0, 3));
        List<PreviewCard> buttons = panel.realisedCards().stream()
                .filter(card -> card.entry().kind().equals(Button.NAME)).toList();
        assertEquals("a card in Common and one in Favourites", 2, buttons.size());
        for (PreviewCard card : buttons) {
            assertEquals("Button", ((Button) card.entry().build()).getText());
        }
    }

    @Test
    public void aCardsMenuActsOnThatCardInItsOwnGroup() {
        Disposable commands = LibraryActions.register(CommandRegistry.global());
        try {
            UserLibrary mine = UserLibrary.in(null);
            panel.useLibrary(mine);
            mine.createGroup("Favourites");
            mine.addToGroup("Favourites", Button.NAME);
            expand("Favourites");
            settle();

            int removable = 0;
            for (PreviewCard card : panel.realisedCards()) {
                if (!card.entry().kind().equals(Button.NAME)) continue;
                Menu menu = ContextMenu.of(LibraryPanel.CARD_MENU).build(CommandRegistry.global(), card);
                for (MenuItem item : menu.getItems()) {
                    if ("Remove from Group".equals(item.getText()) && item.isEnabled()) removable++;
                }
            }
            assertEquals("only the card inside Favourites can leave it", 1, removable);
        } finally {
            commands.dispose();
        }
    }

    @Test
    public void aCardDraggedOntoAUsersGroupJoinsIt() {
        UserLibrary mine = UserLibrary.in(null);
        panel.useLibrary(mine);
        mine.createGroup("Favourites");
        settle();

        PreviewCard button = panel.realisedCards().stream()
                .filter(card -> card.entry().kind().equals(Button.NAME)).findFirst().orElseThrow();
        UIElement favourites = null;
        for (Map.Entry<Integer, UIElement> realised : panel.tree().realisedRows().entrySet()) {
            TreeRow<LibraryPanel.Row> row = panel.tree().rowAt(realised.getKey());
            if (row != null && row.item() instanceof LibraryPanel.Folder folder && folder.label().equals("Favourites")) {
                favourites = realised.getValue();
            }
        }
        int[] from = centreOf(button);
        int[] onto = centreOf(favourites);
        press(from[0], from[1]);
        move(from[0] + 8, from[1] + 8);
        move(onto[0], onto[1]);
        frame();
        assertTrue("the group does not offer to take the card", favourites.hasClass(LibraryPanel.DROP_CLASS));
        release(onto[0], onto[1]);
        settle();

        assertEquals(List.of(Button.NAME), mine.group("Favourites").kinds());
    }

    private void expand(String label) {
        for (LibraryPanel.Row row : panel.tree().roots()) {
            if (row instanceof LibraryPanel.Folder folder && folder.label().equals(label)) {
                panel.tree().setExpanded(folder, true);
            }
        }
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
