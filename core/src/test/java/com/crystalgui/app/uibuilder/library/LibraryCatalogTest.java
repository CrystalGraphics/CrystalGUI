package com.crystalgui.app.uibuilder.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgui.app.uibuilder.glyph.KindGlyphs;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.taskbar.Taskbar;
import com.crystalgui.ui.dom.GlyphRole;
import com.crystalgui.ui.dom.KindInfo;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.Preview;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.dnd.DragGhost;
import com.crystalgui.widget.dnd.Resizer;
import com.crystalgui.widget.graph.GraphView;
import com.crystalgui.widget.graph.NodePort;
import com.crystalgui.widget.texteditor.suggest.CompletionPopup;
import com.crystalgui.workbench.chrome.status.StatusBarView;
import com.crystalgui.workbench.dock.DockGroup;

public class LibraryCatalogTest {

    private static final Name GIZMO = Name.of("testmod", "gizmo");
    private static final Name GRIP = Name.of("testmod", "grip");
    private static final Name DIAL = Name.of("testmod", "dial");

    @Test
    public void aKindThatDeclaredNothingIsListedUnderItsNamespace() {
        LibraryCatalog catalog = catalog(Map.of(GIZMO, KindInfo.derived()));

        LibraryCatalog.Node folder = catalog.tree().get(0);
        assertEquals("testmod", folder.label());
        assertEquals(GIZMO, folder.children().get(0).entry().kind());
    }

    /** A starter is listed ahead of every group and category, found by a search, and known by its id. */
    @Test
    public void startersComeFirstAndAreSearched() {
        UIElement snippet = new UIElement();
        LibraryCatalog.Entry panel = LibraryCatalog.Entry.starter("testmod:starters/panel", "Panel",
                KindInfo.named("Panel").inCategory(LibraryStarters.FOLDER).synonyms("box")
                        .glyph("testmod:nodes/ui/panel", GlyphRole.LAYOUT).starter(() -> snippet));
        LibraryCatalog catalog = catalog(Map.of(GIZMO, KindInfo.derived())).withStarters(List.of(panel));

        LibraryCatalog.Node first = catalog.tree().get(0);
        assertEquals(LibraryStarters.FOLDER, first.label());
        assertSame(panel, first.children().get(0).entry());
        assertEquals("testmod", catalog.tree().get(1).label());

        assertSame(panel, catalog.search("box").get(0).entry());
        assertSame(panel, catalog.entry("starter:testmod:starters/panel"));
        assertNull(panel.kind());
        assertSame(snippet, panel.build());
        assertEquals("testmod:nodes/ui/panel", panel.glyph().icon());
    }

    /** A starter that declares no glyph draws the component mark rather than failing wherever it is listed. */
    @Test
    public void aStarterWithNoGlyphDrawsTheComponentMark() {
        LibraryCatalog.Entry bare = LibraryCatalog.Entry.starter("testmod:starters/bare", "Bare",
                KindInfo.named("Bare").starter(UIElement::new));

        assertEquals(KindGlyphs.COMPONENT_ICON, bare.glyph().icon());
        assertEquals(GlyphRole.LAYOUT, bare.glyph().role());
    }

    @Test
    public void aHiddenKindIsNotListed() {
        LibraryCatalog catalog = catalog(Map.of(GIZMO, KindInfo.derived(), GRIP, KindInfo.hidden()));

        assertNotNull(catalog.entry(GIZMO));
        assertNull(catalog.entry(GRIP));
        assertTrue(catalog.search("grip").isEmpty());
    }

    @Test
    public void aCardShowsTheSampleAndPlacementInsertsTheStarter() {
        UIElement dressed = new UIElement();
        UIElement starter = new UIElement();
        Preview sample = Preview.sample(() -> dressed);
        LibraryCatalog catalog = catalog(Map.of(DIAL, KindInfo.of("Controls").preview(sample).starter(() -> starter)));

        LibraryCatalog.Entry entry = catalog.entry(DIAL);
        assertSame(sample, entry.preview());
        assertSame("a card shows the sample", dressed, entry.sample());
        assertSame("a placement inserts the starter, never the sample", starter, entry.build());
        assertEquals(List.of("Controls"), entry.path());
    }

    @Test
    public void withNoSampleACardShowsTheStarter() {
        UIElement starter = new UIElement();
        LibraryCatalog catalog = catalog(Map.of(DIAL, KindInfo.of("Controls").starter(() -> starter)));

        assertSame(starter, catalog.entry(DIAL).sample());
    }

    @Test
    public void aQueryFindsAKindByItsSynonym() {
        LibraryCatalog catalog = catalog(Map.of(
                GIZMO, KindInfo.named("Gizmo"),
                DIAL, KindInfo.named("Dial").synonyms("knob")));

        List<LibraryCatalog.Node> found = catalog.roots("knob");
        assertEquals(1, found.size());
        assertEquals(DIAL, found.get(0).entry().kind());
    }

    @Test
    public void noMachineryIsListed() {
        LibraryCatalog catalog = LibraryCatalog.current();
        for (Name machinery : List.of(DragGhost.NAME, Resizer.NAME, GraphView.NAME, NodePort.NAME,
                CompletionPopup.NAME, Taskbar.NAME, Desktop.NAME, StatusBarView.NAME, DockGroup.NAME,
                UIDocument.NAME)) {
            assertNull(machinery + " is machinery and must not be listed", catalog.entry(machinery));
        }
    }

    @Test
    public void everyListedShippedKindHasACategory() {
        for (LibraryCatalog.Entry entry : LibraryCatalog.current().entries()) {
            if (!Name.DEFAULT_NAMESPACE.equals(entry.kind().namespace())) continue;
            assertTrue(entry.kind() + " is listed with no category", !entry.info().category().isEmpty());
        }
    }

    /** The shipped starters, then Common: what is reached for most, ahead of the categories. */
    @Test
    public void startersThenCommonComeFirst() {
        List<LibraryCatalog.Node> roots = LibraryCatalog.current().tree();
        assertEquals(LibraryStarters.FOLDER, roots.get(0).label());
        assertEquals(LibraryStarters.ALL.size(), roots.get(0).children().size());
        assertEquals("Common", roots.get(1).label());
        assertEquals(Button.NAME, roots.get(1).children().get(2).entry().kind());
    }

    private static LibraryCatalog catalog(Map<Name, KindInfo> kinds) {
        return LibraryCatalog.of(kinds.keySet(), kinds::get, List.of());
    }
}
