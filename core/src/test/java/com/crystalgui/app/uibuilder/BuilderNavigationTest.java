package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
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
     * <b>Escape steps out, and deselects once there is nowhere left to step.</b>
     *
     * <p>It used to only ever deselect: the surface binds Escape to Deselect and a scoped keymap is
     * consulted before a command's own binding, so Select Parent never ran. Rebinding it needs the step
     * to still clear at the root, or one key would do less than it did before.</p>
     */
    @Test
    public void escapeStepsOutThenClears() {
        open();
        editor.selection().selectOnly(first);

        run(BuilderCommands.SELECT_PARENT);
        assertSame(parent, editor.selection().node());

        run(BuilderCommands.SELECT_PARENT);
        assertSame("the root is still part of the document", model.root(), editor.selection().node());

        run(BuilderCommands.SELECT_PARENT);
        assertNull("past the root there is nothing to select, so it clears",
                editor.selection().node());
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
        assertEquals("Escape still deselects instead of stepping out",
                BuilderCommands.SELECT_PARENT, commandFor(keymap, "Escape"));
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

    private void run(String commandId) {
        assertTrue(commandId + " did not run", com.crystalgui.core.command.CommandRegistry.global()
                .run(commandId, com.crystalgui.core.command.CommandContext.of(editor.surface())));
        document.update(W, H);
    }
}
