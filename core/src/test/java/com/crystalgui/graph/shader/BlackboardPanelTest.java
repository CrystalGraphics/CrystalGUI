package com.crystalgui.graph.shader;

import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.3.14 — the Blackboard.
 */
public class BlackboardPanelTest extends UiTestBase {

    private GraphDocument document;
    private UndoStack undo;
    private BlackboardPanel board;
    private UIWindow window;

    private void mount() {
        document = new GraphDocument();
        undo = new UndoStack();
        board = new BlackboardPanel(document, "test", undo);

        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        root.addChild(board);
        window = new UIWindow(Ui.of(root));
        window.init(600, 400);
        window.updateWithoutPainting();
    }

    // ── The type list ───────────────────────────────────────────────────────

    /**
     * The menu is the scope decision, stated as data.
     *
     * <p>Ten of Unity's sixteen. A type reaching this map that the shader stack cannot declare would
     * produce a property that silently never becomes a uniform, which is why the list is asserted rather
     * than trusted.</p>
     */
    @Test
    public void theTypeListIsTheScopedSet() {
        assertEquals(10, BlackboardPanel.TYPES.size());
        assertTrue(BlackboardPanel.TYPES.containsKey("Float"));
        assertTrue(BlackboardPanel.TYPES.containsKey("Vector 3"));
        assertTrue(BlackboardPanel.TYPES.containsKey("Cubemap"));
        assertFalse("matrices have no property type in the parser",
                BlackboardPanel.TYPES.containsKey("Matrix 4"));
        assertFalse("a gradient is not a uniform", BlackboardPanel.TYPES.containsKey("Gradient"));
    }

    /** Every offered type must be one the emitter can actually declare. */
    @Test
    public void everyOfferedTypeCanBeDeclared() {
        for (String typeId : BlackboardPanel.TYPES.values()) {
            var type = com.crystalgraphics.shadergraph.CgShaderType.parse(typeId);
            assertNotNull(typeId + " must parse as a shader type", type);
            assertNotNull(typeId + " must have a property form", type.propertyDeclarationType());
        }
    }

    /**
     * <b>A pill says which menu entry made it, not which wire type it carries.</b>
     *
     * <p>Color and Vector 4 are both {@code vec4} — Unity models a Color property's data type as a
     * four-component vector too — so the type alone cannot say which was chosen, and every Color
     * property would have shown as {@code Vector 4}.</p>
     */
    @Test
    public void aPillNamesTheMenuEntryThatMadeIt() {
        mount();
        GraphProperty colour = board.addProperty("Color");
        assertEquals("without a recorded kind it reads as the first entry using that type",
                "Vector 4", BlackboardPanel.displayTypeOf(colour.withOption(BlackboardPanel.KIND_OPTION, null)));

        assertEquals("but it was added as Color, so that is what it says",
                "Color", BlackboardPanel.displayTypeOf(colour));

        assertEquals("a property written without the option still reads sensibly",
                "Vector 2", BlackboardPanel.displayTypeOf(GraphProperty.of("V", "vec2", "(0,0)")));
    }

    /** Adding through the menu records the kind, so the pill shows what was picked. */
    @Test
    public void addingRecordsTheMenuKind() {
        mount();
        GraphProperty added = board.addProperty("Color");
        assertEquals("vec4", added.typeId());
        assertEquals("Color", added.option(BlackboardPanel.KIND_OPTION));
    }

    // ── Adding ──────────────────────────────────────────────────────────────

    @Test
    public void addingDeclaresAPropertyAndSelectsIt() {
        mount();
        GraphProperty added = board.addProperty("Vector 2");

        assertNotNull(added);
        assertEquals(1, document.propertyCount());
        assertEquals("Vector 2", added.name());
        assertEquals("_Vector_2", added.reference());
        assertEquals("the next thing anyone does is rename it, and the form follows the selection",
                added.id(), board.selectedPropertyId());
    }

    /** Two of a kind get distinct names — two identical pills help nobody. */
    @Test
    public void asecondPropertyOfATypeIsNamedApart() {
        mount();
        board.addProperty("Float");
        GraphProperty second = board.addProperty("Float");
        assertEquals("Float (1)", second.name());
    }

    /** The Category entry has nothing to create yet — see 6.3.14 on categories being a field. */
    @Test
    public void theCategoryEntryAddsNothing() {
        mount();
        assertNull(board.addProperty(BlackboardPanel.CATEGORY_LABEL));
        assertNull(board.addProperty(null));
        assertEquals(0, document.propertyCount());
    }

    /** Adding is undoable, like every other document change. */
    @Test
    public void addingUndoes() {
        mount();
        board.addProperty("Color");
        assertEquals(1, document.propertyCount());
        undo.undo();
        assertEquals(0, document.propertyCount());
    }

    /** A new property is born with a value its type can actually take. */
    @Test
    public void everyTypeGetsAUsableDefault() {
        for (String typeId : BlackboardPanel.TYPES.values()) {
            String value = BlackboardPanel.defaultValueFor(typeId);
            assertNotNull(typeId, value);
            assertFalse(typeId + " must not start blank", value.isEmpty());
        }
        assertEquals("a sampler default is a quoted fallback name",
                "\"white\"", BlackboardPanel.defaultValueFor("sampler2D"));
        assertEquals("a Vector 3 default is written FOUR-wide, because it is declared as vec4",
                "(0,0,0,0)", BlackboardPanel.defaultValueFor("vec3"));
    }

    // ── The list ────────────────────────────────────────────────────────────

    /** The board follows the document, whoever changed it. */
    @Test
    public void theListFollowsTheDocument() {
        mount();
        assertEquals(0, board.pills().size());

        document.addProperty(GraphProperty.of("Tint", "vec4", "(1,1,1,1)"));
        assertEquals("a change made behind the panel's back still shows", 1, board.pills().size());
        assertEquals("Tint", board.pills().get(0).displayName());

        document.addProperty(GraphProperty.of("Amount", "float", "0"));
        assertEquals(List.of("Tint", "Amount"),
                board.pills().stream().map(PropertyPill::displayName).toList());
    }

    /** A rebuild keeps the selection, since every edit to the selected property causes one. */
    @Test
    public void aRebuildKeepsTheSelection() {
        mount();
        GraphProperty tint = board.addProperty("Color");
        assertEquals(tint.id(), board.selectedPropertyId());

        document.replaceProperty(document.property(tint.id()).withName("Renamed"));
        assertEquals("the selection must survive its own edit", tint.id(), board.selectedPropertyId());
        assertTrue(board.pillFor(tint.id()).isSelected());
    }

    /**
     * <b>A refresh REPLACES the list; it must never append to it.</b>
     *
     * <p>{@code clearAllChildren()} deliberately skips internal children and a {@link PropertyPill} marks
     * itself internal — so the clear removed nothing and every refresh stacked another copy of the list.
     * On screen the empty placeholder appeared twice, then three times. The identical bug
     * {@code ConfiguratorPanel.clearRows} already records, hit again by a second panel, which is why this
     * one removes what it added rather than asking for a sweep.</p>
     */
    @Test
    public void refreshingReplacesTheListRatherThanAppending() {
        mount();
        UIElement list = board.pills().isEmpty() ? null : board.pills().get(0).getParent();

        board.refresh();
        board.refresh();
        board.refresh();
        assertEquals("the empty placeholder must appear once, not once per refresh",
                1, countBodyChildren());

        board.addProperty("Float");
        board.addProperty("Color");
        int withTwo = countBodyChildren();
        assertEquals("two properties, two rows", 2, withTwo);

        board.refresh();
        board.refresh();
        assertEquals("and refreshing does not grow it", withTwo, countBodyChildren());
    }

    /** Removing the last property puts the placeholder back exactly once. */
    @Test
    public void theEmptyPlaceholderComesAndGoesCleanly() {
        mount();
        board.addProperty("Float");
        assertEquals(1, countBodyChildren());
        board.removeSelected();
        assertEquals("one placeholder, not a placeholder beside a stale row", 1, countBodyChildren());
    }

    /** Whatever the body is, count everything in it — pills are internal, the placeholder is not. */
    private int countBodyChildren() {
        UIElement body = null;
        for (UIElement child : board.getChildren()) {
            if (child.hasClass(BlackboardPanel.BODY_CLASS)) body = child;
        }
        assertNotNull("the panel must have a body", body);
        int found = 0;
        for (UIElement child : body.getChildren()) {
            if (child instanceof PropertyPill || child.hasClass("__empty__")) found++;
        }
        return found;
    }

    // ── Selection ───────────────────────────────────────────────────────────

    /** Selecting emits, and clearing emits too — a listener has to be able to hide the form. */
    @Test
    public void selectionEmitsBothWays() {
        mount();
        List<String> heard = new ArrayList<>();
        board.onPropertySelected.connect(heard::add);

        GraphProperty tint = board.addProperty("Color");
        assertEquals(List.of(tint.id()), heard);

        board.select(null);
        assertEquals("clearing must be announced, not merely applied", 2, heard.size());
        assertNull(heard.get(1));
    }

    /** Re-selecting what is already selected is not a change. */
    @Test
    public void reSelectingIsQuiet() {
        mount();
        GraphProperty tint = board.addProperty("Color");
        List<String> heard = new ArrayList<>();
        board.onPropertySelected.connect(heard::add);
        board.select(tint.id());
        assertTrue(heard.isEmpty());
    }

    /**
     * <b>Focus comes back to the board when a rename ends.</b>
     *
     * <p>Detaching the editor drops the window's focus to nothing, and every command resolves outward
     * from the focused element — so after pressing Enter the row stayed highlighted while Delete, F2 and
     * Mod+D were all dead until it was clicked again. Highlighted-but-inert is the worst version of this:
     * it looks like the selection simply does not work.</p>
     */
    @Test
    public void endingARenameGivesFocusBackToTheBoard() {
        mount();
        GraphProperty added = board.addProperty("Float");
        PropertyPill pill = board.pillFor(added.id());
        assertNotNull(pill);
        assertTrue("a fresh property opens in a rename", pill.isRenaming());

        pill.endRename();
        window.updateWithoutPainting();

        assertSame("the board must hold focus, or its whole key set is inert",
                board, window.getInputHandler().getFocusedElement());
    }

    // ── Removing ────────────────────────────────────────────────────────────

    @Test
    public void removingTheSelectionUndoes() {
        mount();
        GraphProperty tint = board.addProperty("Color");
        assertTrue(board.removeSelected());
        assertEquals(0, document.propertyCount());
        assertNull("and nothing is selected afterwards", board.selectedPropertyId());

        undo.undo();
        assertEquals(1, document.propertyCount());
    }

    @Test
    public void removingWithNothingSelectedDoesNothing() {
        mount();
        assertFalse(board.removeSelected());
    }

    /**
     * A property deleted from under the panel clears the selection.
     *
     * <p>Otherwise the inspector goes on showing a form bound to a property that no longer exists, and
     * every field in it writes into nothing.</p>
     */
    @Test
    public void deletingTheSelectedPropertyElsewhereClearsTheSelection() {
        mount();
        GraphProperty tint = board.addProperty("Color");
        document.removeProperty(tint.id());
        assertNull(board.selectedPropertyId());
    }
}
