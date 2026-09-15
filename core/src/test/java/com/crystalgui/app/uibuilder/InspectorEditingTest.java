package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.vfyjxf.taffy.style.FlexDirection;
import org.joml.Vector2f;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.inspect.BoxModelEditor;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.app.uibuilder.inspect.NodeFields;
import com.crystalgui.app.uibuilder.live.LiveSubject;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.config.ConfigControl;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.config.control.ClassChips;
import com.crystalgui.widget.config.inspector.Inspector;
import com.crystalgui.widget.composite.ColorSelector;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.layout.Tab;

/**
 * <b>L4.9 — the inspector edits the document.</b>
 *
 * <p>What is pinned is the spine: every control writes one undoable edit, a live pick writes nothing, and an
 * edit made through a control does not rebuild the form it was made in.</p>
 */
public class InspectorEditingTest extends UiDocumentTestBase {

    private static final String SOURCE = "{\n"
            + "  \"cgui\": 1,\n"
            + "  \"root\": { \"kind\": \"element\", \"id\": \"root\",\n"
            + "    \"children\": [\n"
            + "      { \"kind\": \"button\", \"id\": \"ok\", \"state\": { \"text\": \"OK\" } },\n"
            + "      { \"kind\": \"slider\", \"id\": \"volume\", \"state\": { \"min\": 0, \"max\": 10, \"value\": 2 } }\n"
            + "    ] }\n"
            + "}\n";

    private BuilderEditor editor;
    private Inspector inspector;
    private Disposable sections;
    private Button ok;
    private UIElement volume;

    @Before
    public void openTheDocument() {
        UIElementRegistry.bootstrap();
        sections = BuilderInspectorSections.register();
        editor = new BuilderEditor(new UiBuilderDocument(SOURCE.getBytes(StandardCharsets.UTF_8), "test:page"));
        // SIDE BY SIDE, as a workbench docks them: stacked, the inspector's lower rows fell outside the window
        // and a press aimed at them landed on the document.
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).width(W).height(H));
        UIElement root = new UIElement().layout(l -> l.width(500).height(500));
        root.append(editor.view());
        row.append(root);
        inspector = new Inspector();
        inspector.layout(l -> l.width(300).height(H));
        row.append(inspector);
        document.append(row);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.update(W, H);
        frame();

        ok = (Button) editor.document().root().getElementById("ok");
        volume = editor.document().root().getElementById("volume");
    }

    @After
    public void release() {
        sections.dispose();
    }

    private UiBuilderDocument model() {
        return editor.document();
    }

    private void inspect(UIElement... nodes) {
        editor.selection().replaceWith(List.of(nodes));
        inspector.inspect(editor.view());
        frame();
        frame();
    }

    private ConfigControl control(String id) {
        for (UIElement each : inspector.composedSubtree()) {
            if (each instanceof ConfigControl control && id.equals(control.descriptor().id())) return control;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> Property<T> property(String id) {
        ConfigControl control = control(id);
        assertNotNull("no control " + id + "; tabs=" + inspector.tabNames(), control);
        return ((ValueControl<T>) control).property();
    }

    // ── Identity ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void anIdIsOneEditAndATakenOneIsRefused() {
        inspect(ok);
        Property<String> id = property("id");

        id.set("confirm");
        assertEquals("confirm", ok.id());
        assertEquals(1, model().history().undoDepth());

        id.set("volume");
        assertEquals("taken: refused rather than duplicated", "confirm", ok.id());
        assertEquals(1, model().history().undoDepth());
        assertFalse("the field marks it invalid as it is typed", control("id").descriptor().validator().test("volume"));
    }

    /** A live pick has no document behind it, so its rows describe and nothing is written. */
    @Test
    public void aLivePickIsDescribedAndNotEdited() {
        UIElement elsewhere = new UIElement();
        elsewhere.setId("elsewhere");
        document.append(elsewhere);
        LiveSubject.on(document).pick(elsewhere);
        inspector.inspect(elsewhere);
        frame();
        frame();

        assertNull(NodeFields.of(DataContext.from(elsewhere)));
        ConfigControl id = control("id");
        assertNotNull(id);
        assertNull("an info row, not a field", id.descriptor().validator());
    }

    @Test
    public void classChipsEditAuthoredClassesAndLeaveTheEnginesOut() {
        inspect(ok);
        ClassChips chips = (ClassChips) control("classes");
        assertNotNull(chips);
        assertFalse(chips.chipNames().contains(Button.LABELLED_CLASS));

        chips.add("primary");
        assertTrue(ok.hasClass("primary"));
        assertTrue(ok.hasClass(Button.LABELLED_CLASS));
        assertEquals(1, model().history().undoDepth());
        String saved = new String(model().encode(), StandardCharsets.UTF_8);
        assertTrue(saved.contains("primary"));
        assertFalse(saved.contains(Button.LABELLED_CLASS));
    }

    // ── Attributes and state ────────────────────────────────────────────────────────────────────

    @Test
    public void anAttributeAtItsInitialIsListedAndSettingItIsOneEdit() {
        inspect(ok);
        Property<Boolean> hitTest = property("attr.hit-test");
        assertTrue(hitTest.get());

        hitTest.set(false);
        assertFalse(ok.get(Attribute.HIT_TEST));
        assertEquals(1, model().history().undoDepth());
        model().history().undo();
        assertTrue(ok.get(Attribute.HIT_TEST));
    }

    /**
     * <b>Mod+Z pressed in the inspector undoes what the inspector did.</b> The panel sits beside the editor,
     * so the walk from a focused row has to be answered by the panel with its subject's history.
     */
    @Test
    public void modZInTheInspectorUndoesTheDocumentEdit() {
        inspect(ok);
        ConfigControl row = control("attr.hit-test");
        property("attr.hit-test").set(false);
        assertFalse(ok.get(Attribute.HIT_TEST));

        assertSame(model().history(), DataContext.from(row).get(UiDataKeys.UNDO_STACK));
        UIElement focusable = document.focus().firstFocusableIn(row);
        document.focus().requestFocus(focusable == null ? row : focusable);
        assertTrue("Mod+Z was not handled", chord(CgKeyCodes.KEY_Z, CgModifiers.CTRL));
        releaseModifiers();
        frame();

        assertTrue(ok.get(Attribute.HIT_TEST));
    }

    /** A flex row writes the node's inline style as one step; undo takes the declaration away again. */
    @Test
    public void aFlexRowWritesInlineAsOneStep() {
        UIElement page = editor.document().root();
        inspect(page);
        Property<String> direction = property("style.flex-direction");
        assertEquals("Column", direction.get());

        direction.set("Row");
        frame();
        assertEquals(FlexDirection.ROW, page.getStyle().computed().get(LayoutProperties.FLEX_DIRECTION));
        assertTrue(LiveEdits.hasInline(page, LayoutProperties.FLEX_DIRECTION));
        assertEquals(1, model().history().undoDepth());

        direction.set("Column");
        frame();
        assertFalse("choosing what the sheets already give removes the declaration",
                LiveEdits.hasInline(page, LayoutProperties.FLEX_DIRECTION));
        assertEquals(2, model().history().undoDepth());

        model().history().undo();
        frame();
        assertTrue(LiveEdits.hasInline(page, LayoutProperties.FLEX_DIRECTION));
        model().history().undo();
        frame();
        assertFalse(LiveEdits.hasInline(page, LayoutProperties.FLEX_DIRECTION));
    }

    /** A slider drag in the form is one undo step, however many frames it wrote. */
    @Test
    public void aScrubOfAStateSlotIsOneUndoStep() {
        inspect(volume);
        Property<Double> value = property("state.value");
        assertEquals(2d, value.get(), 1e-6);

        model().history().beginMergeRun();
        for (int i = 3; i <= 9; i++) value.set((double) i);
        model().history().endMergeRun();

        assertEquals(1, model().history().undoDepth());
        model().history().undo();
        assertEquals(2d, value.get(), 1e-6);
    }

    /** A slider's value scrubs across the slider's own span, and follows the span when max changes. */
    @Test
    public void aSpanHintedSlotScrubsAtAHundredthOfItsSpan() {
        inspect(volume);
        assertEquals(0.1d, control("state.value").descriptor().scrubRate(), 1e-9);

        property("state.max").set(100d);
        assertEquals(1d, control("state.value").descriptor().scrubRate(), 1e-9);
    }

    /** An enum with its own label keeps it in the dropdown, and the label reads back as the constant. */
    @Test
    public void anEnumsOwnLabelIsWhatTheDropdownShows() {
        assertEquals(List.of("RGB 0-255", "RGB 0-1.0", "HSV"),
                NodeFields.descriptorFor("mode", "Mode", ColorSelector.Mode.class, null).options());
        assertEquals(List.of("On commit", "Immediate").size(),
                NodeFields.descriptorFor("u", "U", TextField.UpdateMode.class, null).options().size());
        assertTrue(NodeFields.descriptorFor("u", "U", TextField.UpdateMode.class, null).options().contains("On commit"));
    }

    /** The control an edit was made in is the one still on screen afterwards. */
    @Test
    public void anEditDoesNotRebuildTheFormItWasMadeIn() {
        inspect(volume);
        ConfigControl before = control("state.value");
        property("state.value").set(5d);
        frame();
        frame();

        assertSame(before, control("state.value"));
    }

    // ── Box model ───────────────────────────────────────────────────────────────────────────────

    @Test
    public void aBoxEditIsOneStepAndEscapeWritesNothing() {
        inspect(ok);
        BoxModelEditor box = null;
        for (UIElement each : inspector.composedSubtree()) {
            if (each instanceof BoxModelEditor found) box = found;
        }
        assertNotNull(box);
        BoxModelEditor.Cell left = box.cellFor(LayoutProperties.PADDING_LEFT);

        UIElement focusable = document.focus().firstFocusableIn(inspector);
        document.focus().requestFocus(focusable);
        box.beginEdit(left);
        box.field().setText("6");
        box.step(1, 0);
        box.step(1, 0);
        box.commit();
        assertEquals(1, model().history().undoDepth());
        assertTrue(LiveEdits.hasInline(ok, LayoutProperties.PADDING_LEFT));

        assertNotNull("focus is handed back, so Ctrl+Z still reaches the document", document.focus().focused());

        box.beginEdit(box.cellFor(LayoutProperties.PADDING_TOP));
        box.field().setText("30");
        box.step(1, 0);
        box.cancel();
        assertEquals("nothing recorded", 1, model().history().undoDepth());
        assertFalse(LiveEdits.hasInline(ok, LayoutProperties.PADDING_TOP));
    }

    /** Dragging a box value scrubs it as one undo step; Escape mid-drag writes nothing. */
    @Test
    public void aBoxValueScrubsAsOneStepAndEscapeRestoresIt() {
        inspect(ok);
        for (Tab tab : inspector.tabs().getTabs()) {
            if (tab.getText().equals(BuilderInspectorSections.LAYOUT_TAB)) inspector.tabs().selectTab(tab);
        }
        frame();
        frame();
        BoxModelEditor box = null;
        for (UIElement each : inspector.composedSubtree()) {
            if (each instanceof BoxModelEditor found) box = found;
        }
        assertNotNull(box);
        BoxModelEditor.Cell left = box.cellFor(LayoutProperties.PADDING_LEFT);
        double before = Double.parseDouble(left.shown());
        int[] at = centreOf(left.element());

        press(at[0], at[1]);
        frame();
        move(at[0] + 10, at[1]);
        frame();
        assertTrue(box.isScrubbing());
        move(at[0] + 40, at[1]);
        frame();
        release(at[0] + 40, at[1]);
        frame();
        frame();

        assertEquals(1, model().history().undoDepth());
        assertTrue("dragging right grows it", Double.parseDouble(box.cellFor(LayoutProperties.PADDING_LEFT).shown()) > before);

        at = centreOf(left.element());
        press(at[0], at[1]);
        frame();
        move(at[0] + 30, at[1]);
        frame();
        keyPress(CgKeyCodes.KEY_ESCAPE);
        release(at[0] + 30, at[1]);
        frame();
        assertEquals("cancelled, nothing recorded", 1, model().history().undoDepth());
    }

    /** A hidden node has no box: the diagram shows its style's values, greyed, and opens nothing for typing. */
    @Test
    public void aHiddenNodesBoxShowsItsStyleAndEditsNothing() {
        inspect(ok);
        LiveEdits.setInline(ok, LayoutProperties.PADDING_LEFT, "7px");
        editor.document().apply(new BuilderEdit.SetAttribute<>(ok, Attribute.HIDDEN, false, true));
        frame();
        frame();
        BoxModelEditor box = null;
        for (UIElement each : inspector.composedSubtree()) {
            if (each instanceof BoxModelEditor found) box = found;
        }
        assertNotNull(box);

        assertNull(ok.box());
        assertTrue(box.hasClass(BoxModelEditor.NO_BOX_CLASS));
        assertEquals("7", box.cellFor(LayoutProperties.PADDING_LEFT).shown());
        box.beginEdit(box.cellFor(LayoutProperties.PADDING_LEFT));
        assertNull("nothing to type into", box.field());
    }

    /** A rebuild takes the previous subject's diagram with it, rather than stacking one per selection. */
    @Test
    public void selectingAnotherNodeReplacesTheBoxDiagram() {
        inspect(ok);
        inspect(volume);
        inspect(ok);

        int diagrams = 0;
        for (UIElement each : inspector.composedSubtree()) {
            if (each instanceof BoxModelEditor) diagrams++;
        }
        assertEquals(1, diagrams);
    }

    // ── Several nodes ───────────────────────────────────────────────────────────────────────────

    @Test
    public void aClassAddedToSeveralNodesIsOneStep() {
        inspect(ok, volume);
        ClassChips shared = (ClassChips) control("multi.classes");
        assertNotNull("tabs=" + inspector.tabNames(), shared);

        shared.add("group");
        assertTrue(ok.hasClass("group"));
        assertTrue(volume.hasClass("group"));
        assertEquals(1, model().history().undoDepth());
    }

    // ── The document ────────────────────────────────────────────────────────────────────────────

    @Test
    public void aClickOnBlankPageShowsTheDocumentTab() {
        Box board = editor.artboard().box();
        Vector2f blank = Transform2D.apply(board.localToWorld(), board.width() * 0.5f, board.height() * 0.8f);
        pointer(blank, true);
        pointer(blank, false);
        inspector.inspect(editor.view());
        frame();
        frame();

        assertTrue(editor.selection().canvasSelected());
        assertTrue("tabs=" + inspector.tabNames(), inspector.tabNames().contains(BuilderInspectorSections.DOCUMENT_TAB));

        editor.selection().selectOnly(ok);
        assertFalse("picking a node leaves the canvas", editor.selection().canvasSelected());
    }

    @Test
    public void aHeaderEditIsOneStepAndUndoes() {
        editor.selection().selectCanvas(true);
        inspector.inspect(editor.view());
        frame();
        frame();
        String before = new String(model().encode(), StandardCharsets.UTF_8);

        Property<String> packageName = property("export.package");
        packageName.set("mymod.ui");
        assertTrue(new String(model().encode(), StandardCharsets.UTF_8).contains("\"package\": \"mymod.ui\""));
        assertEquals(1, model().history().undoDepth());

        model().history().undo();
        assertEquals(before, new String(model().encode(), StandardCharsets.UTF_8));
    }

    private void pointer(Vector2f at, boolean down) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, down, 0f, down ? 1L : 2L));
        frame();
    }
}
