package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.panel.HierarchyPanel;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>Walking the tree from the canvas, and what Select All is allowed to take.</b>
 */
public class BuilderNavigationTest extends UiDocumentTestBase {

    private UiBuilderDocument model;
    private BuilderEditor editor;
    private UIElement parent;
    private UIElement first;
    private UIElement second;
    private UIElement third;

    private com.crystalgui.core.dispose.Disposable commands;

    @org.junit.After
    public void releaseCommands() {
        if (commands != null) commands.dispose();
    }

    private void open() {
        UIElementRegistry.bootstrap();
        // The extension does this in the application; a bare fixture has to say so itself.
        commands = BuilderCommands.register();
        model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:x.cgui");
        parent = new UIElement().layout(l -> l.width(200).height(60));
        first = new UIElement().layout(l -> l.width(40).height(20));
        second = new UIElement().layout(l -> l.width(40).height(20));
        third = new UIElement().layout(l -> l.width(40).height(20));
        parent.append(first, second, third);
        model.root().append(parent);

        editor = new BuilderEditor(model);
        UIElement host = new UIElement().layout(l -> l.width(400).height(300));
        host.append(editor.view());
        document.append(host);
        document.update(W, H);
    }

    /** The third direction a tree has, and the one that was missing: parent and child walk a spine. */
    @Test
    public void theArrowsWalkAlongTheSiblings() {
        open();
        editor.selection().selectOnly(second);

        run(BuilderCommands.SELECT_PREVIOUS_SIBLING);
        assertSame(first, editor.selection().node());

        run(BuilderCommands.SELECT_NEXT_SIBLING);
        run(BuilderCommands.SELECT_NEXT_SIBLING);
        assertSame(third, editor.selection().node());

        // CLAMPED, not wrapped: the last child's "next" wrapping to the first reads as the selection
        // jumping backwards for no reason.
        run(BuilderCommands.SELECT_NEXT_SIBLING);
        assertSame("the selection wrapped around the row", third, editor.selection().node());
    }

    /**
     * <b>Up and Down walk the other two directions, and stop at the ends.</b>
     *
     * <p>They were on Escape and Enter, which cost Escape its only job and took Enter from every text
     * field — a bare key declared on a command is application-wide. A tree has four directions and the
     * arrows have four keys.</p>
     */
    @Test
    public void theArrowsWalkUpAndDown() {
        open();
        editor.selection().selectOnly(first);

        run(BuilderCommands.SELECT_PARENT);
        assertSame(parent, editor.selection().node());

        run(BuilderCommands.SELECT_CHILD);
        assertSame("down goes to the first child", first, editor.selection().node());

        run(BuilderCommands.SELECT_PARENT);
        run(BuilderCommands.SELECT_PARENT);
        assertSame("the root is still part of the document", model.root(), editor.selection().node());

        // CLAMPED, like the siblings. Escape is the key that clears; an arrow that empties the selection
        // when it runs out of tree is a different verb wearing a navigation key.
        run(BuilderCommands.SELECT_PARENT);
        assertSame("up past the root should stop, not deselect",
                model.root(), editor.selection().node());
    }

    /**
     * <b>Escape clears a selection, including one of several.</b>
     *
     * <p>It was rebound to Select Parent, which asks for THE selected node — a set has none — so Escape
     * did nothing at all on a multi-selection, and nothing but step on a single one.</p>
     */
    @Test
    public void escapeDeselects() {
        open();
        editor.selection().replaceWith(java.util.List.of(first, second));
        assertEquals(2, editor.selection().nodes().size());

        run(com.crystalgui.widget.surface.SurfaceCommands.DESELECT);
        assertTrue("Escape left the set selected", editor.selection().nodes().isEmpty());
    }

    /**
     * <b>Select All takes the things beside you, not the whole document.</b>
     *
     * <p>A selection holding both an ancestor and a descendant makes the next action apply twice — a move
     * shifts the parent, carrying the child, then shifts the child again — so "everything" is a hazard
     * rather than a feature. Scoping to the container also makes the key useful more than once: at the
     * top level and inside a row it should not give the same answer.</p>
     */
    @Test
    public void selectAllIsScopedToWhereYouAre() {
        open();
        // INSIDE a container: the siblings, not the document.
        editor.selection().selectOnly(second);
        run(BuilderCommands.SELECT_ALL);
        assertEquals("inside a row, Select All should take that row's children",
                3, editor.selection().nodes().size());
        assertTrue(editor.selection().nodes().contains(first));
        assertFalse("it must not climb out to the top level",
                editor.selection().nodes().contains(parent));

        // NOTHING SELECTED: there is no container to be in, so the top level.
        editor.selection().clear();
        run(BuilderCommands.SELECT_ALL);
        assertTrue("with nothing selected it should take the top level",
                editor.selection().nodes().contains(parent));
    }

    /** <b>The page is not a thing you can select.</b> Ctrl+A used to hand back the artboard. */
    @Test
    public void selectAllLeavesThePageAlone() {
        open();
        run(BuilderCommands.SELECT_ALL);

        assertFalse("Select All took nothing at all", editor.selection().nodes().isEmpty());
        assertFalse("Select All took the page the document is drawn on",
                editor.selection().nodes().contains(editor.artboard()));
        assertTrue("...and it should have taken what is IN the document",
                editor.selection().nodes().contains(parent));
    }

    /**
     * <b>The builder's keys actually reach the builder's commands.</b>
     *
     * <p>{@code Keymap.bind} APPENDS and the EARLIER binding wins — it says so, and warns about the
     * conflict — so binding over a surface default silently does nothing. Escape went on deselecting and
     * Mod+A went on running the engine's Select All, which after being taught to skip the page had
     * nothing left to take. Both keys read as broken by the change meant to fix them.</p>
     *
     * <p>Asserted on the keymap rather than by pressing a key, because what went wrong is which command
     * a chord resolves to — pressing it would pass the moment either command ran.</p>
     */
    @Test
    public void theBuilderKeysResolveToTheBuilderCommands() {
        open();
        var keymap = editor.surface().keymapOrNull();
        assertEquals("Escape must keep its own job: clearing the selection",
                com.crystalgui.widget.surface.SurfaceCommands.DESELECT, commandFor(keymap, "Escape"));
        assertEquals(BuilderCommands.SELECT_PARENT, commandFor(keymap, "Up"));
        assertEquals(BuilderCommands.SELECT_CHILD, commandFor(keymap, "Down"));
        assertEquals("Mod+A still runs the engine's Select All, which takes nothing here",
                BuilderCommands.SELECT_ALL, commandFor(keymap, "Mod+A"));
        assertEquals(BuilderCommands.SELECT_NEXT_SIBLING, commandFor(keymap, "Right"));
    }

    /** The command the chord resolves to — the FIRST binding, which is the one that wins. */
    private static String commandFor(com.crystalgui.ui.input.keymap.Keymap keymap, String chord) {
        var parsed = com.crystalgui.ui.input.keymap.KeyChord.parse(chord);
        for (var binding : keymap.bindings()) {
            if (binding.getChord().equals(parsed)) return binding.getCommandId();
        }
        return null;
    }

    /**
     * <b>A plain click on one of several selected collapses to it.</b>
     *
     * <p>The press cannot decide: collapsing there would make dragging a set impossible, since the press
     * that starts the drag would first throw the set away. So it defers — and nothing was settling it, so
     * shift-clicking three and then clicking one left all three picked with no way back to one.</p>
     *
     * <p>Release settles it, unless a real drag ran. {@code isActivated}, not {@code isDragging}: a drag
     * is armed on mouse-down, so testing the latter would suppress the collapse always.</p>
     */
    @Test
    public void aPlainClickCollapsesAMultiSelection() {
        open();
        var tool = editor.surface().modes().current();

        clickOn(tool, first, false);
        assertEquals(1, editor.selection().nodes().size());

        clickOn(tool, second, true);
        assertEquals("shift did not add to the selection", 2, editor.selection().nodes().size());

        // THE PRESS KEEPS THE SET, so a drag from here would move both.
        press(tool, first, false);
        assertEquals("the press collapsed the set, so a set could never be dragged",
                2, editor.selection().nodes().size());

        // AND THE RELEASE COLLAPSES IT.
        release(tool, first);
        assertEquals("a plain click left the whole set selected", 1, editor.selection().nodes().size());
        assertSame(first, editor.selection().node());
    }

    private void clickOn(com.crystalgui.widget.surface.mode.Tool tool, UIElement node, boolean shift) {
        press(tool, node, shift);
        release(tool, node);
    }

    private void press(com.crystalgui.widget.surface.mode.Tool tool, UIElement node, boolean shift) {
        org.joml.Vector2f at = com.crystalgui.core.data.Transform2D.apply(
                node.box().localToWorld(), node.box().width() * 0.5f, node.box().height() * 0.5f);
        tool.pointerDown(at.x(), at.y(), com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON,
                shift ? com.crystalgraphics.platform.input.CgModifiers.SHIFT : 0);
        document.update(W, H);
    }

    private void release(com.crystalgui.widget.surface.mode.Tool tool, UIElement node) {
        org.joml.Vector2f at = com.crystalgui.core.data.Transform2D.apply(
                node.box().localToWorld(), node.box().width() * 0.5f, node.box().height() * 0.5f);
        tool.pointerUp(at.x(), at.y(), com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON, 0);
        document.update(W, H);
    }

    /**
     * <b>The Design panel holds a set, both ways.</b>
     *
     * <p>Nothing had to move upstream for this: {@code ListView} implements MULTIPLE in full — Ctrl to
     * toggle, Shift for a range — which is why the project tree gets it from one line. This panel simply
     * never opted in, so the canvas could hold a set while the tree showed one of it.</p>
     */
    @Test
    public void thePanelAndTheCanvasAgreeAboutASet() {
        open();
        HierarchyPanel panel = new HierarchyPanel(editor.surface());
        UIElement side = new UIElement().layout(l -> l.width(200).height(300));
        side.append(panel);
        document.append(side);
        document.update(W, H);

        // CANVAS -> PANEL.
        editor.selection().replaceWith(java.util.List.of(first, second));
        document.update(W, H);
        assertEquals("the tree showed one of a set", 2, panel.tree().getSelectedIndices().size());

        // PANEL -> CANVAS, which has always taken a set of indices.
        panel.tree().onSelectionChanged.emit(java.util.Set.of(
                rowOf(panel, second), rowOf(panel, third)));
        document.update(W, H);
        assertEquals(2, editor.selection().nodes().size());
        assertTrue(editor.selection().nodes().contains(third));

        // AND A CLEARED CANVAS CLEARS THE PANEL, which used to leave a stale highlight naming a node
        // nothing was selecting any more.
        editor.selection().clear();
        document.update(W, H);
        assertTrue("the tree kept a highlight after the canvas cleared",
                panel.tree().getSelectedIndices().isEmpty());
    }

    private static int rowOf(HierarchyPanel panel, UIElement node) {
        var rows = panel.tree().visibleRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item() == node) return i;
        }
        throw new AssertionError("no row for " + node);
    }

    /**
     * <b>The canvas keeps modifier presses, so the desktop's Alt+drag does not take them.</b>
     *
     * <p>That gesture is a capture-phase listener on the window frame — which is what makes "drag
     * anywhere inside the window" true, and what makes it reach content before content does. The canvas
     * spends Alt on resizing from the centre and on suspending snap, so holding Alt and pressing a resize
     * handle moved the WINDOW: the only way to resize from the centre was to press first and add Alt
     * afterwards.</p>
     *
     * <p>Asserted on the declaration because the gesture it guards needs a desktop, a frame and a real
     * pointer to exercise. The line is what a refactor would drop; the behaviour is verified by using
     * it.</p>
     */
    @Test
    public void theCanvasClaimsModifierPresses() {
        open();
        assertTrue("the desktop's Alt+drag will take presses meant for the canvas",
                Boolean.TRUE.equals(editor.surface().get(
                        com.crystalgui.ui.dom.Attribute.KEEPS_MODIFIER_PRESS)));

        // Found by walking OUT from whatever was pressed, so it covers the handles and every widget on
        // the page rather than a list of individual controls that would go stale.
        boolean foundFromChild = false;
        for (UIElement at = first; at != null; at = at.parentElement()) {
            if (Boolean.TRUE.equals(at.get(com.crystalgui.ui.dom.Attribute.KEEPS_MODIFIER_PRESS))) {
                foundFromChild = true;
                break;
            }
        }
        assertTrue("a press on something in the document does not reach the claim", foundFromChild);
    }

    private void run(String commandId) {
        assertTrue(commandId + " did not run", com.crystalgui.core.command.CommandRegistry.global()
                .run(commandId, com.crystalgui.core.command.CommandContext.of(editor.surface())));
        document.update(W, H);
    }
}
