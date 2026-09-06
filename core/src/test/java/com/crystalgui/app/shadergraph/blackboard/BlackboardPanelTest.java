package com.crystalgui.app.shadergraph.blackboard;

import com.crystalgui.app.shadergraph.ShaderPropertyForm;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.3.14 — the Blackboard.
 */
public class BlackboardPanelTest extends UiDocumentTestBase {

    private GraphDocument graphDocument;
    private UndoStack undo;
    private BlackboardPanel board;

    private void mount() {
        graphDocument = new GraphDocument();
        undo = new UndoStack();
        board = new BlackboardPanel(graphDocument, "test", undo);

        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        root.append(board);
        document.append(root);
        frame();
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
                "Vector 4", BlackboardPanel.displayTypeOf(colour.withOption(ShaderPropertyForm.KIND_OPTION, null)));

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
        assertEquals("Color", added.option(ShaderPropertyForm.KIND_OPTION));
    }

    // ── Adding ──────────────────────────────────────────────────────────────

    @Test
    public void addingDeclaresAPropertyAndSelectsIt() {
        mount();
        GraphProperty added = board.addProperty("Vector 2");

        assertNotNull(added);
        assertEquals(1, graphDocument.propertyCount());
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

    /**
     * {@code Category} is not a property type, and {@code addProperty} must keep refusing it.
     *
     * <p>The {@code +} menu routes that label to {@link BlackboardPanel#addCategory()} instead. This is
     * the guard on the other path: a label reaching {@code addProperty} that {@code TYPES} does not know
     * declares nothing rather than declaring something typeless.</p>
     */
    @Test
    public void theCategoryEntryIsNotAPropertyType() {
        mount();
        assertNull(board.addProperty(BlackboardPanel.CATEGORY_LABEL));
        assertNull(board.addProperty(null));
        assertEquals(0, graphDocument.propertyCount());
    }

    /** Adding is undoable, like every other graphDocument change. */
    @Test
    public void addingUndoes() {
        mount();
        board.addProperty("Color");
        assertEquals(1, graphDocument.propertyCount());
        undo.undo();
        assertEquals(0, graphDocument.propertyCount());
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

    /** The board follows the graphDocument, whoever changed it. */
    @Test
    public void theListFollowsTheDocument() {
        mount();
        assertEquals(0, board.pills().size());

        graphDocument.addProperty(GraphProperty.of("Tint", "vec4", "(1,1,1,1)"));
        assertEquals("a change made behind the panel's back still shows", 1, board.pills().size());
        assertEquals("Tint", board.pills().get(0).displayName());

        graphDocument.addProperty(GraphProperty.of("Amount", "float", "0"));
        assertEquals(List.of("Tint", "Amount"),
                board.pills().stream().map(PropertyPill::displayName).toList());
    }

    /** A rebuild keeps the selection, since every edit to the selected property causes one. */
    @Test
    public void aRebuildKeepsTheSelection() {
        mount();
        GraphProperty tint = board.addProperty("Color");
        assertEquals(tint.id(), board.selectedPropertyId());

        graphDocument.replaceProperty(graphDocument.property(tint.id()).withName("Renamed"));
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
        UIElement list = board.pills().isEmpty() ? null : board.pills().get(0).parentElement();

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
        for (UIElement child : board.children()) {
            if (child.hasClass(BlackboardPanel.BODY_CLASS)) body = child;
        }
        assertNotNull("the panel must have a body", body);
        int found = 0;
        for (UIElement child : body.children()) {
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
     * <p>Detaching the editor drops the document's focus to nothing, and every command resolves outward
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
        frame();

        assertSame("the board must hold focus, or its whole key set is inert",
                board, document.focus().focused());
    }

    // ── Removing ────────────────────────────────────────────────────────────

    @Test
    public void removingTheSelectionUndoes() {
        mount();
        GraphProperty tint = board.addProperty("Color");
        assertTrue(board.removeSelected());
        assertEquals(0, graphDocument.propertyCount());
        assertNull("and nothing is selected afterwards", board.selectedPropertyId());

        undo.undo();
        assertEquals(1, graphDocument.propertyCount());
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
        graphDocument.removeProperty(tint.id());
        assertNull(board.selectedPropertyId());
    }

    // ── Reorder ─────────────────────────────────────────────────────────────

    private List<String> names() {
        List<String> out = new ArrayList<>();
        for (GraphProperty property : graphDocument.properties()) out.add(property.name());
        return out;
    }

    /**
     * <b>A drop slot is not a destination index, and the difference is a real off-by-one.</b>
     *
     * <p>{@code moveProperty} takes a slot counted against the list <em>as the user sees it</em>, while
     * {@code GraphDocument.moveProperty} takes the index the property ends at <em>after</em> being lifted
     * out. Dragging downward crosses its own vacated slot, so the two differ by one in that direction and
     * agree in the other — and the failure is quiet: the property lands one place short, which looks like
     * an imprecise drag rather than a bug.</p>
     */
    @Test
    public void aSlotBelowIsOneLessOnceTheRowIsLiftedOut() {
        mount();
        GraphProperty a = board.addProperty("Float");
        GraphProperty b = board.addProperty("Vector 2");
        GraphProperty c = board.addProperty("Color");
        assertEquals(List.of(a.name(), b.name(), c.name()), names());

        // The first row dropped in the LAST slot -- three pills, so the slot below all of them is 3.
        board.moveProperty(a.id(), 3);
        assertEquals(List.of(b.name(), c.name(), a.name()), names());

        // And back to the top, where slot and index agree because nothing was crossed.
        board.moveProperty(a.id(), 0);
        assertEquals(List.of(a.name(), b.name(), c.name()), names());
    }

    /** Dropping a row back where it already was is not an edit, and must not push an undo step. */
    @Test
    public void droppingARowInItsOwnSlotDoesNothing() {
        mount();
        board.addProperty("Float");
        GraphProperty b = board.addProperty("Vector 2");
        board.addProperty("Color");
        int depth = undo.undoDepth();

        assertFalse("its own slot", board.moveProperty(b.id(), 1));
        // The slot BELOW itself is the same position -- lifting it out closes the gap it would fill.
        assertFalse("the slot below itself", board.moveProperty(b.id(), 2));
        assertEquals("and neither is undoable", depth, undo.undoDepth());
    }

    /** Reorder undoes, like every other graphDocument change. */
    @Test
    public void reorderingUndoes() {
        mount();
        GraphProperty a = board.addProperty("Float");
        GraphProperty b = board.addProperty("Vector 2");

        board.moveProperty(b.id(), 0);
        assertEquals(List.of(b.name(), a.name()), names());
        undo.undo();
        assertEquals(List.of(a.name(), b.name()), names());
    }

    /** A property dragged after being deleted mid-drag moves nothing rather than throwing. */
    @Test
    public void movingAPropertyThatIsGoneIsHarmless() {
        mount();
        assertFalse(board.moveProperty("no-such-property", 0));
    }

    // ── Categories ──────────────────────────────────────────────────────────

    private GraphProperty filed(String menuLabel, String category) {
        GraphProperty added = board.addProperty(menuLabel);
        board.dropProperty(added.id(), graphDocument.indexOfProperty(added.id()), category);
        return graphDocument.property(added.id());
    }

    /**
     * <b>A heading appears wherever the category field changes, and nowhere else.</b>
     *
     * <p>There is no category entity to enumerate — the grouping is read off the list order. Which also
     * means uncategorised properties get no heading at all, rather than one called "Uncategorised": the
     * empty string is the absence of a category, not a category named nothing.</p>
     */
    @Test
    public void headingsComeFromTheFieldAndOnlyWhereItChanges() {
        mount();
        board.addProperty("Float");                 // uncategorised -- no heading
        filed("Vector 2", "Surface");
        filed("Color", "Surface");                  // same run -- still one heading

        assertEquals(List.of("Surface"), board.categories());
        assertEquals("every property still has a row", 3, board.pills().size());
    }

    /** Folding hides a group's rows, keeps its heading, and changes nothing in the graphDocument. */
    @Test
    public void foldingHidesTheRowsAndNotTheProperties() {
        mount();
        filed("Float", "Surface");
        filed("Vector 2", "Surface");
        assertEquals(2, board.pills().size());

        board.setCategoryCollapsed("Surface", true);
        assertEquals("the rows are gone", 0, board.pills().size());
        assertEquals("the heading is not", List.of("Surface"), board.categories());
        assertEquals("and neither are the properties", 2, graphDocument.propertyCount());
    }

    /**
     * <b>Folding is view state — it must never reach the undo stack.</b>
     *
     * <p>The same boundary the editor's own folding draws, and the reason {@code Ctrl+Z} does not unfold.
     * Get it wrong and the first undo after collapsing a group re-opens it instead of undoing the edit
     * the user actually wants back.</p>
     */
    @Test
    public void foldingIsNotUndoable() {
        mount();
        filed("Float", "Surface");
        int depth = undo.undoDepth();

        board.setCategoryCollapsed("Surface", true);
        board.setCategoryCollapsed("Surface", false);
        assertEquals(depth, undo.undoDepth());
    }

    /**
     * An empty category exists on the panel, because a field has nowhere to record one.
     *
     * <p>The documented cost of "a field, not a tree": it is view state until something joins it, so it
     * does not survive a reload. Asserted so the behaviour is a decision rather than a surprise.</p>
     */
    @Test
    public void anEmptyCategoryIsAHeadingWithNoDocumentTrace() {
        mount();
        String created = board.addCategory();

        assertEquals(List.of(created), board.categories());
        assertEquals("nothing was declared", 0, graphDocument.propertyCount());
    }

    /** Two categories created in a row do not collide, the same way two properties do not. */
    @Test
    public void aSecondCategoryIsNamedApart() {
        mount();
        assertNotEquals(board.addCategory(), board.addCategory());
        assertEquals(2, board.categories().size());
    }

    /**
     * Renaming a category rewrites the field on every member — as <b>one</b> undo step.
     *
     * <p>The user performed one rename. Undoing it one property at a time would walk back through
     * half-renamed states that were never a thing anybody chose.</p>
     */
    @Test
    public void renamingACategoryMovesEveryMemberInOneStep() {
        mount();
        GraphProperty a = filed("Float", "Surface");
        GraphProperty b = filed("Color", "Surface");
        int depth = undo.undoDepth();

        assertTrue(board.renameCategory("Surface", "Albedo"));
        assertEquals("Albedo", graphDocument.property(a.id()).category());
        assertEquals("Albedo", graphDocument.property(b.id()).category());
        assertEquals("one step for one gesture", depth + 1, undo.undoDepth());

        undo.undo();
        assertEquals("Surface", graphDocument.property(a.id()).category());
        assertEquals("Surface", graphDocument.property(b.id()).category());
    }

    /** A folded category stays folded when renamed, rather than springing open. */
    @Test
    public void renamingKeepsTheFoldState() {
        mount();
        filed("Float", "Surface");
        board.setCategoryCollapsed("Surface", true);

        board.renameCategory("Surface", "Albedo");
        assertTrue(board.isCategoryCollapsed("Albedo"));
        assertFalse(board.isCategoryCollapsed("Surface"));
    }

    /**
     * <b>Deleting a category cannot delete its properties</b> — it clears a field.
     *
     * <p>The whole payoff of "a field, not a tree": there is no containment, so there is no "deleting a
     * category deletes its contents" rule to get wrong. The rows move up into the ungrouped region.</p>
     */
    @Test
    public void removingACategoryKeepsItsProperties() {
        mount();
        GraphProperty a = filed("Float", "Surface");

        assertTrue(board.removeCategory("Surface"));
        assertEquals("the property is untouched", 1, graphDocument.propertyCount());
        assertEquals("and simply ungrouped", "", graphDocument.property(a.id()).category());
        assertTrue(board.categories().isEmpty());
    }

    /**
     * <b>A drop that crosses a heading moves AND re-files, in one undo step.</b>
     *
     * <p>One gesture, so one step: splitting it would make the first {@code Ctrl+Z} leave the property
     * somewhere the user never put it — the new group at the old position, or the reverse.</p>
     */
    @Test
    public void dropCrossingAHeadingIsOneStep() {
        mount();
        GraphProperty loose = board.addProperty("Float");
        GraphProperty filed = filed("Color", "Surface");
        int depth = undo.undoDepth();

        // Into Surface, after the property already there.
        board.dropProperty(loose.id(), graphDocument.indexOfProperty(filed.id()) + 1, "Surface");
        assertEquals("Surface", graphDocument.property(loose.id()).category());
        assertEquals("it moved too", 1, graphDocument.indexOfProperty(loose.id()));
        assertEquals("one step", depth + 1, undo.undoDepth());

        undo.undo();
        assertEquals("", graphDocument.property(loose.id()).category());
        assertEquals(0, graphDocument.indexOfProperty(loose.id()));
    }
}
