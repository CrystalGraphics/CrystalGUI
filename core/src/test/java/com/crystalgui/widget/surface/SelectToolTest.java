package com.crystalgui.widget.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.surface.extension.SurfaceExtensions;
import com.crystalgui.widget.surface.mode.SelectExtension;

/**
 * The engine's own gestures on a bare surface: click selects, band selects what it touches, drag moves
 * what is selected, and the whole drag is one undo step.
 *
 * <p>Against no consumer at all — the graph runs the same three classes, so a failure here is the engine
 * rather than a graph. That is what a second consumer buys.</p>
 */
public class SelectToolTest {

    private UIDocument window;
    private SurfaceEditor surface;
    private final UndoStack history = new UndoStack();
    private int recorded;

    @Before
    public void open() {
        UIElementRegistry.bootstrap();
        SurfaceExtensions.resetForTesting();
        recorded = 0;
        surface = new SurfaceEditor(policy(), List.of(SelectExtension.ID));
        window = new UIDocument();
        window.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.append(surface);
    }

    @After
    public void close() {
        surface.dispose();
        SurfaceExtensions.resetForTesting();
    }

    /** Items are plain elements at known world positions, which is all the engine needs one to be. */
    private SurfacePolicy policy() {
        return new SurfacePolicy() {
            @Override
            public UndoStack history() {
                return history;
            }

            @Override
            public UIElement itemFor(UIElement hit) {
                for (UIElement each = hit; each != null; each = each.parentElement()) {
                    if (each.hasClass("item")) return each;
                }
                return null;
            }

            @Override
            public PressOwner ownerOf(UIElement hit) {
                return PressOwner.SURFACE;
            }

            @Override
            public void markSelected(UIElement item, boolean selected) {
            }

            @Override
            public Edit moveEdit(List<Move> moves) {
                recorded++;
                return new Edit() {
                    @Override
                    public void apply() {
                    }

                    @Override
                    public void undo() {
                    }
                };
            }
        };
    }

    private UIElement item(String id, float x, float y) {
        UIElement element = new UIElement();
        element.setId(id);
        element.addClass("item");
        com.crystalgui.style.StyleGroup.inlinePipeline(element.getStyle().getLayoutGroup(),
                l -> l.width(60f).height(40f));
        surface.surface().place(element, x, y);
        return element;
    }

    private void frame() {
        window.update(800f, 600f);
    }

    @Test
    public void theSelectToolIsCurrentAsSoonAsTheSurfaceOpens() {
        assertEquals(1, surface.tools().size());
        assertNotNull("a surface with no tool would take no clicks at all", surface.modes().current());
        assertEquals(SelectExtension.TOOL, surface.modes().currentId());
    }

    @Test
    public void thePickerFindsAnItemUnderThePoint() {
        UIElement a = item("a", 10f, 10f);
        frame();

        assertEquals(a, surface.picking().itemAt(20f, 20f));
        assertEquals("nothing out there", null, surface.picking().itemAt(500f, 500f));
    }

    @Test
    public void aBandSelectsWhatItTouches() {
        UIElement a = item("a", 10f, 10f);
        UIElement far = item("far", 400f, 400f);
        frame();

        List<UIElement> caught = surface.picking()
                .touching(com.crystalgui.widget.canvas.WorldRect.of(0f, 0f, 30f, 30f));

        assertTrue("touched, not enclosed", caught.contains(a));
        assertTrue(!caught.contains(far));
    }

    /** I5: one gesture, one undo step — however many items it moved. */
    @Test
    public void aMoveIsOneUndoStep() {
        UIElement a = item("a", 10f, 10f);
        UIElement b = item("b", 100f, 10f);
        frame();
        surface.selection().replaceWith(List.of(a, b));

        int before = history.undoDepth();
        surface.edits().gesture("move", () ->
                surface.edits().record(surface.surfacePolicy().moveEdit(List.of(
                        new SurfacePolicy.Move(a, 10f, 10f, 40f, 10f),
                        new SurfacePolicy.Move(b, 100f, 10f, 130f, 10f)))));

        assertEquals("two items moved, one step", before + 1, history.undoDepth());
        assertEquals("and the consumer was asked once", 1, recorded);
    }

    /** Deleting asks the consumer what it means, and clears what it removed. */
    @Test
    public void deletingClearsTheSelection() {
        UIElement a = item("a", 10f, 10f);
        frame();
        surface.selection().selectOnly(a);

        com.crystalgui.core.command.CommandRegistry.global().run(SurfaceCommands.DELETE,
                com.crystalgui.core.command.CommandContext.of(surface));

        assertTrue(surface.selection().isEmpty());
    }

    /** The surface answers its own key, so a command resolved from focus finds it. */
    @Test
    public void theSurfaceAnswersItsDataKey() {
        assertEquals(surface, surface.getData(SurfaceEditor.SURFACE));
        assertEquals(history, surface.undoStack());
    }
}
