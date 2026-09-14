package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.insert.BuilderInsert;
import com.crystalgui.app.uibuilder.insert.InsertTarget;
import com.crystalgui.app.uibuilder.library.LibraryCatalog;
import com.crystalgui.app.uibuilder.library.LibraryStarters;
import com.crystalgui.app.uibuilder.library.UserLibrary;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.storage.InMemoryConfigStorage;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.surface.insert.InsertMenu;
import com.crystalgui.widget.surface.insert.Insertable;

/**
 * <b>The Insert menu: where a pick lands, by each route that opens it, and what it remembers.</b>
 */
public class BuilderInsertTest extends UiDocumentTestBase {

    private UiBuilderDocument model;
    private BuilderEditor editor;
    private UIElement parent;
    private UIElement first;
    private UIElement second;
    private Disposable commands;
    private final InMemoryConfigStorage store = new InMemoryConfigStorage();

    @After
    public void releaseCommands() {
        if (commands != null) commands.dispose();
    }

    private void open() {
        UIElementRegistry.bootstrap();
        commands = BuilderCommands.register();
        model = new UiBuilderDocument(UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:x.cgui");
        parent = new UIElement().layout(l -> l.width(200).height(60));
        first = new UIElement().layout(l -> l.width(40).height(20));
        second = new UIElement().layout(l -> l.width(40).height(20));
        parent.append(first, second);
        model.root().append(parent);

        editor = new BuilderEditor(model, store);
        UIElement host = new UIElement().layout(l -> l.width(W).height(H));
        host.append(editor.view());
        document.append(host);
        document.update(W, H);
    }

    private InsertMenu menu() {
        return editor.surface().insertMenu();
    }

    private Insertable offer(String label) {
        for (Insertable offer : editor.insert().offers()) {
            if (offer.label().equals(label) && !offer.browsingOnly()) return offer;
        }
        throw new AssertionError("no offer " + label);
    }

    /** Inside a container at its end, then before and after it; the page has nothing to go beside. */
    @Test
    public void placesAroundANodeInTheOrderTabWalks() {
        open();
        List<InsertTarget> container = InsertTarget.around(model.root(), parent, null);
        assertEquals(InsertTarget.Relation.INSIDE, container.get(0).relation());
        assertEquals(2, container.get(0).index());
        assertEquals(InsertTarget.Relation.BEFORE, container.get(1).relation());
        assertEquals(InsertTarget.Relation.AFTER, container.get(2).relation());

        List<InsertTarget> root = InsertTarget.around(model.root(), model.root(), null);
        assertEquals("the page has no siblings to go beside", 1, root.size());
    }

    /** Shift+Space on the canvas opens under the selection, and a pick lands inside it as one undo step. */
    @Test
    public void shiftSpaceInsertsUnderTheSelection() {
        open();
        editor.selection().selectOnly(parent);
        document.focus().requestFocus(editor.surface());
        assertTrue("Shift+Space was not handled", chord(CgKeyCodes.KEY_SPACE, CgModifiers.SHIFT));
        releaseModifiers();
        document.update(W, H);
        assertNotNull(menu());
        assertTrue(menu().isOpen());
        assertSame(parent, editor.insert().target().parent());

        offer("Button").insert(0f, 0f);
        assertEquals(3, parent.children().size());
        UIElement placed = parent.children().get(2);
        assertTrue(placed instanceof Button);
        assertSame("the pick is selected", placed, editor.selection().node());

        model.history().undo();
        assertEquals("one undo step takes it back", 2, parent.children().size());
    }

    /** Tab in the search box moves the place, and the pick lands at the place shown. */
    @Test
    public void tabCyclesWhereThePickLands() {
        open();
        editor.selection().selectOnly(parent);
        editor.insert().openForSelection();
        document.update(W, H);
        assertSame(menu().searchField(), document.focus().focused());

        keyPress(CgKeyCodes.KEY_TAB);
        InsertTarget before = editor.insert().target();
        assertEquals(InsertTarget.Relation.BEFORE, before.relation());

        offer("Button").insert(0f, 0f);
        assertTrue(model.root().children().get(0) instanceof Button);
        assertSame(parent, model.root().children().get(1));
    }

    /** Right-click on blank page opens the menu at the pointer, and the pick is one undo step. */
    @Test
    public void rightClickOnBlankPageInsertsAtThePointer() {
        open();
        int[] page = centreOf(model.root());
        press(page[0], page[1], CgMouseCodes.RIGHT_BUTTON);
        release(page[0], page[1], CgMouseCodes.RIGHT_BUTTON);
        document.update(W, H);
        assertNotNull("right-click on the page opened no insert menu", menu());
        assertTrue(menu().isOpen());
        InsertTarget target = editor.insert().target();
        assertSame(model.root(), target.parent());

        offer("Button").insert(0f, 0f);
        assertEquals(2, model.root().children().size());
        model.history().undo();
        assertEquals(1, model.root().children().size());
    }

    /** The root is content-sized, so the page under its content is off every node: a pick there ends the page. */
    @Test
    public void rightClickBelowTheContentInsertsAtTheEndOfThePage() {
        open();
        int[] page = centreOf(model.root());
        float below = boxOf(model.root()).height() + 40f;
        press(page[0], page[1] + below, CgMouseCodes.RIGHT_BUTTON);
        release(page[0], page[1] + below, CgMouseCodes.RIGHT_BUTTON);
        document.update(W, H);
        assertNotNull("right-click below the content opened no insert menu", menu());
        assertTrue(menu().isOpen());
        assertSame(model.root(), editor.insert().target().parent());
        assertEquals(1, editor.insert().target().index());
    }

    /** A recent pick is listed first while browsing and not twice in a search, and it outlives the editor. */
    @Test
    public void recentPicksAreKeptAndNotSearchedTwice() {
        open();
        editor.selection().selectOnly(parent);
        editor.insert().openForSelection();
        offer("Button").insert(0f, 0f);

        List<Insertable> offers = editor.insert().offers();
        assertEquals("Button", offers.get(0).label());
        assertTrue(offers.get(0).browsingOnly());
        assertEquals(List.of("Recent"), offers.get(0).path());

        editor.selection().selectOnly(parent);
        editor.insert().openForSelection();
        menu().searchBox().setText("Button");
        menu().body().refresh();
        long buttons = menu().visibleOffers().stream().filter(row -> row.label().equals("Button")).count();
        assertEquals(1, buttons);

        BuilderInsert later = new BuilderInsert(editor.surface(), store);
        assertEquals("Button", later.offers().get(0).label());
    }

    /** A group the user made in the Library is a folder here too, and a search does not list its members twice. */
    @Test
    public void theUsersLibraryGroupsAreListed() {
        open();
        UserLibrary library = UserLibrary.in(store);
        library.createGroup("Favourites");
        library.addToGroup("Favourites", Button.NAME);

        List<Insertable> grouped = editor.insert().offers().stream()
                .filter(offer -> offer.path().equals(List.of("Favourites"))).toList();
        assertEquals(1, grouped.size());
        assertEquals("Button", grouped.get(0).label());
        assertTrue(grouped.get(0).browsingOnly());

        editor.selection().selectOnly(parent);
        editor.insert().openForSelection();
        menu().searchBox().setText("Button");
        menu().body().refresh();
        assertEquals(1, menu().visibleOffers().stream().filter(row -> row.label().equals("Button")).count());
    }

    /** Every starter inflates, and places as a tree. */
    @Test
    public void startersPlace() {
        open();
        for (LibraryCatalog.Entry starter : LibraryStarters.ALL) {
            assertFalse(starter.label() + " is empty", starter.build().children().isEmpty());
        }
        editor.selection().selectOnly(model.root());
        editor.insert().openForSelection();
        offer("Card").insert(0f, 0f);
        UIElement card = model.root().children().get(1);
        assertSame(card, editor.selection().node());
        assertFalse(card.children().isEmpty());
    }
}
