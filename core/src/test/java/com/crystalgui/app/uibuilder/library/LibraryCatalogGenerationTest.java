package com.crystalgui.app.uibuilder.library;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.crystalgui.ui.dom.KindInfo;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import java.util.List;
import org.junit.Test;

/** The catalog is derived from the registered kinds once per generation of them, and shared. */
public class LibraryCatalogGenerationTest {

    private static final Name ADDED = Name.of("generationtest", "added");

    @Test
    public void theCatalogIsBuiltOncePerGenerationOfTheKinds() {
        UIElementRegistry.bootstrap();
        LibraryCatalog first = LibraryCatalog.current();
        assertSame("every reader shares one", first, LibraryCatalog.current());
        assertNull(first.entry(ADDED));

        UIElementRegistry.register(ADDED, () -> new UIElement(ADDED), UIElementRegistry.plain(ADDED, true),
                KindInfo.derived());

        LibraryCatalog next = LibraryCatalog.current();
        assertNotSame("a new kind is a new generation", first, next);
        assertNotNull(next.entry(ADDED));
        assertSame(next, LibraryCatalog.current());
    }

    @Test
    public void aUsersGroupsAreLaidOverTheSharedCatalogWithoutRebuildingIt() {
        LibraryCatalog shipped = LibraryCatalog.current();
        LibraryCatalog mine = LibraryCatalog.current(List.of(new LibraryCatalog.Group("Mine", List.of(), true)));

        assertSame(shipped.entries(), mine.entries());
        assertNotSame(shipped.groups(), mine.groups());
    }
}
