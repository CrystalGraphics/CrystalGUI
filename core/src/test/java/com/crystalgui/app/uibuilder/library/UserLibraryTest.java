package com.crystalgui.app.uibuilder.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgui.core.storage.InMemoryConfigStorage;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Slider;

/** B.9: a user's groups and view are theirs, kept in their private store. */
public class UserLibraryTest {

    @Test
    public void groupsAndTheViewSurviveANewLibraryOnTheSameStore() {
        InMemoryConfigStorage store = new InMemoryConfigStorage();
        UserLibrary first = UserLibrary.in(store);
        first.createGroup("Forms");
        first.addToGroup("Forms", Button.NAME);
        first.addToGroup("Forms", Slider.NAME);
        first.setRows(true);

        UserLibrary again = UserLibrary.in(store);
        assertEquals(List.of(Button.NAME, Slider.NAME), again.group("Forms").kinds());
        assertTrue(again.group("Forms").user());
        assertTrue(again.isRows());
    }

    @Test
    public void aKindMaySitInSeveralGroupsAndOnceInEach() {
        UserLibrary library = UserLibrary.in(null);
        library.createGroup("Forms");
        library.createGroup("Toolbar");

        assertTrue(library.addToGroup("Forms", Button.NAME));
        assertTrue(library.addToGroup("Toolbar", Button.NAME));
        assertFalse("a kind twice in one group", library.addToGroup("Forms", Button.NAME));
    }

    @Test
    public void aNameIsUniqueAndNeverAShippedGroups() {
        UserLibrary library = UserLibrary.in(null);
        assertTrue(library.createGroup("Forms"));

        assertFalse(library.createGroup("Forms"));
        assertFalse(library.createGroup(LibraryGroups.COMMON.label()));
        assertFalse(library.createGroup("  "));
        assertFalse("renamed onto a taken name", library.renameGroup("Forms", LibraryGroups.COMMON.label()));
    }

    @Test
    public void renamingKeepsTheKindsAndDeletingLeavesTheOthers() {
        UserLibrary library = UserLibrary.in(null);
        library.createGroup("Forms");
        library.createGroup("Toolbar");
        library.addToGroup("Forms", Button.NAME);

        assertTrue(library.renameGroup("Forms", "Inputs"));
        assertEquals(List.of(Button.NAME), library.group("Inputs").kinds());
        assertTrue(library.deleteGroup("Inputs"));
        assertEquals(List.of("Toolbar"), library.groups().stream().map(LibraryCatalog.Group::label).toList());
    }

    @Test
    public void anUnreadableRecordStartsEmptyRatherThanThrowing() {
        InMemoryConfigStorage store = new InMemoryConfigStorage();
        store.write(UserLibrary.FILE, "{\"groups\": [ not json");

        assertTrue(UserLibrary.in(store).groups().isEmpty());
    }

    @Test
    public void aKindNoLongerRegisteredStaysInItsGroup() {
        InMemoryConfigStorage store = new InMemoryConfigStorage();
        Name gone = Name.of("uninstalled", "gizmo");
        UserLibrary library = UserLibrary.in(store);
        library.createGroup("Mod");
        library.addToGroup("Mod", gone);

        assertEquals(List.of(gone), UserLibrary.in(store).group("Mod").kinds());
    }
}
