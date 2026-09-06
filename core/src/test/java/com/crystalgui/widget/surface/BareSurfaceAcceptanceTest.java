package com.crystalgui.widget.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector2f;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.mode.SelectExtension;

/**
 * <b>L2.10 — a surface with nothing but Select is a working editor.</b>
 *
 * <p>The engine was extracted from the shader graph, so the question that matters is whether anything of
 * the graph came with it. A bare {@link SurfaceEditor} opens an empty plane, selects, marquees and moves
 * — and every registry except the one Select fills is empty.</p>
 */
public class BareSurfaceAcceptanceTest extends UiDocumentTestBase {

    private SurfaceEditor surface;

    private final UndoStack history = new UndoStack();

    /** Items the policy was told to mark, so "selected" is observable without a widget. */
    private final List<UIElement> marked = new ArrayList<>();

    private UIElement first;
    private UIElement second;

    @Before
    public void openABareSurface() {
        surface = new SurfaceEditor(policy(), List.of(SelectExtension.ID));
        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        root.append(surface);
        document.append(root);

        first = new UIElement().layout(l -> l.width(60).height(40));
        second = new UIElement().layout(l -> l.width(60).height(40));
        surface.surface().place(first, 20f, 20f);
        surface.surface().place(second, 140f, 20f);
        document.update(W, H);
    }

    private SurfacePolicy policy() {
        return new SurfacePolicy() {
            @Override
            public UndoStack history() {
                return history;
            }

            @Override
            public UIElement itemFor(UIElement hit) {
                for (UIElement each = hit; each != null; each = each.parentElement()) {
                    if (each.parentElement() == surface.content()) return each;
                }
                return null;
            }

            @Override
            public PressOwner ownerOf(UIElement hit) {
                return PressOwner.SURFACE;
            }

            @Override
            public void markSelected(UIElement item, boolean selected) {
                if (selected) marked.add(item);
                else marked.remove(item);
            }

            @Override
            public Edit moveEdit(List<Move> moves) {
                List<Move> made = List.copyOf(moves);
                return new Edit() {
                    @Override public void apply() {
                        for (Move m : made) surface.surface().move(m.item(), m.toX(), m.toY());
                    }

                    @Override public void undo() {
                        for (Move m : made) surface.surface().move(m.item(), m.fromX(), m.fromY());
                    }

                    @Override public String label() {
                        return "move";
                    }
                };
            }
        };
    }

    /**
     * <b>Select fills one registry and nothing else does.</b>
     *
     * <p>The engine ships no feature of its own: everything a graph or a builder adds arrives through an
     * extension it asked for by id. A tool appearing here that nobody enabled would mean the engine had
     * kept something of the graph's on the way out.</p>
     */
    @Test
    public void selectIsTheOnlyThingASurfaceOpensWith() {
        assertEquals("one tool, and it is Select", 1, surface.tools().size());
        assertEquals(SelectExtension.TOOL, surface.tools().get(0).id());
        assertTrue("no overlays", surface.overlayKinds().isEmpty());
        assertTrue("no insert sources", surface.insertSources().isEmpty());
        assertTrue("no drop handlers", surface.dropHandlers().isEmpty());
    }

    @Test
    public void aPressSelectsWhatIsUnderIt() {
        click(physicalCentreOf(first));

        assertEquals(List.of(first), surface.selection().items());
        assertEquals("the policy was told, which is how a widget draws it", List.of(first), marked);
    }

    @Test
    public void aBandSelectsWhatItTouches() {
        Vector2f from = physicalOfWorld(0f, 0f);
        Vector2f to = physicalOfWorld(220f, 90f);
        press(from);
        frame();
        moveTo(new Vector2f((from.x() + to.x()) / 2f, (from.y() + to.y()) / 2f));
        frame();
        moveTo(to);
        frame();
        release(to);
        frame();

        assertEquals("both items were inside the band", 2, surface.selection().size());
    }

    @Test
    public void aMoveIsOneUndoStep() {
        click(physicalCentreOf(first));
        float startX = surface.surface().boundsOf(first).x();

        Vector2f grab = physicalCentreOf(first);
        press(grab);
        frame();
        moveTo(new Vector2f(grab.x() + 40f, grab.y()));
        frame();
        moveTo(new Vector2f(grab.x() + 80f, grab.y()));
        frame();
        release(new Vector2f(grab.x() + 80f, grab.y()));
        frame();

        float moved = surface.surface().boundsOf(first).x();
        assertTrue("the drag moved it", moved > startX);

        assertTrue("and left exactly one entry to undo", history.canUndo());
        history.undo();
        document.update(W, H);
        assertEquals("one undo puts the whole gesture back", startX,
                surface.surface().boundsOf(first).x(), 0.5f);
        assertFalse("a drag is ONE step, however many frames it took", history.canUndo());
    }

    // ── Driving the pointer, as GraphEditingTest does ────────────────────────────────────────────

    private Vector2f physicalCentreOf(UIElement element) {
        var box = element.box();
        return Transform2D.apply(box.localToWorld(), box.width() * 0.5f, box.height() * 0.5f);
    }

    private Vector2f physicalOfWorld(float worldX, float worldY) {
        Vector2f local = surface.worldToViewport(worldX, worldY);
        var box = surface.box();
        return Transform2D.apply(box.localToWorld(), local.x(), local.y());
    }

    private void click(Vector2f at) {
        press(at);
        frame();
        release(at);
        frame();
    }

    private void press(Vector2f p) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(p.x()), Math.round(p.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
    }

    private void moveTo(Vector2f p) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(p.x()), Math.round(p.y()), 0, 0, -1, false, 0f, -1L));
    }

    private void release(Vector2f p) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(p.x()), Math.round(p.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
    }
}
