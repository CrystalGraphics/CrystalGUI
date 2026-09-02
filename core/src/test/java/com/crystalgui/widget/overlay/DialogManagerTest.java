package com.crystalgui.widget.overlay;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.DialogManager;
import com.crystalgui.ui.service.Input;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link DialogManager} — stacking, activation and placement for a set of dialogs sharing a stage.
 *
 * <p><b>Entirely ours.</b> The web has no document manager, so unlike the top layer or {@code resize}
 * there is no spec these tests pin — only the CSS stacking model underneath, which the engine already
 * implements as a per-parent z-descending sort. Everything here is a policy decision, and the tests
 * are written to say which.</p>
 */
public class DialogManagerTest extends UiDocumentTestBase {

    private Input input;
    private UINode root, stage;
    private DialogManager manager;
    private Dialog a, b, c;

    @Before
    public void build() {
        root = new UINode().layout(l -> l.width(400).height(300));
        stage = new UINode().layout(l -> l.width(400).height(300));
        root.append(stage);

        document.append(root);
        document.boxes().setUiScale(2f); // uiScale 2
        settle();
        input = document.input();
        input.beginFrame();
        input.endFrame();

        manager = new DialogManager(stage);
        a = manager.manage(sized(new Dialog("a")));
        b = manager.manage(sized(new Dialog("b")));
        c = manager.manage(sized(new Dialog("c")));
        manager.showAll();
        settle();
    }

    private Dialog sized(Dialog d) {
        d.layout(l -> l.width(120).height(80));
        d.getTitleBar().layout(l -> l.height(16));
        return d;
    }

    private void settle() {
        frame();
    }

    private int z(Dialog d) {
        return d.getStyle().getGeneralGroup().zIndex();
    }

    private float left(Dialog d) {
        return d.box().x() - stage.box().x();
    }

    private void pressInside(UINode e) {
        float x = e.box().x() + 3f;
        float y = e.box().y() + 3f;
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
    }

    // ── Stacking ────────────────────────────────────────────────────────────

    /** The last one added is on top — the same rule the top layer uses, applied through z-index. */
    @Test
    public void theMostRecentlyAddedDialogStartsOnTop() {
        assertTrue(z(c) > z(b));
        assertTrue(z(b) > z(a));
        assertSame(c, manager.getActive());
    }

    @Test
    public void raisingBringsADialogToTheFrontAndMakesItActive() {
        manager.raise(a);

        assertTrue("raised above every sibling", z(a) > z(b) && z(a) > z(c));
        assertSame(a, manager.getActive());
    }

    /** Monotonic z rather than a renumbering pass: only relative order matters, so there is nothing
     * to gain from compacting and a renumber would touch every sibling on every click. */
    @Test
    public void raisingTheFrontMostDialogIsANoOp() {
        int before = z(c);

        manager.raise(c);

        assertEquals("already in front — nothing to write", before, z(c));
        assertSame(c, manager.getActive());
    }

    @Test
    public void raisingIsTransitiveAcrossSeveralSwaps() {
        manager.raise(a);
        manager.raise(b);
        manager.raise(c);
        manager.raise(a);

        assertTrue(z(a) > z(b));
        assertTrue(z(a) > z(c));
    }

    // ── Activation ──────────────────────────────────────────────────────────

    /**
     * Clicking anywhere in a document activates it — including on a control that consumes the press for
     * its own purposes. That is why the listener is capture-phase: only capture sees the event before
     * a descendant can stop it.
     */
    @Test
    public void clickingAnywhereInADialogRaisesIt() {
        assertSame(c, manager.getActive());

        pressInside(a.getTitleBar());

        assertSame(a, manager.getActive());
        assertTrue(z(a) > z(c));
    }

    @Test
    public void clickingAControlInsideADialogStillRaisesIt() {
        Button inner = new Button("ok");
        a.getContent().append(inner);
        settle();

        pressInside(inner);

        assertSame("a consuming child must not prevent activation", a, manager.getActive());
        assertTrue(z(a) > z(c));
    }

    // ── Placement ───────────────────────────────────────────────────────────

    /** New windows cascade so a freshly opened one is visibly new, rather than pixel-perfectly hiding
     * the previous one. */
    @Test
    public void dialogsCascadeRatherThanStackingExactly() {
        assertEquals(0f, left(a), 0.5f);
        assertEquals(DialogManager.DEFAULT_CASCADE_STEP, left(b), 0.5f);
        assertEquals(DialogManager.DEFAULT_CASCADE_STEP * 2f, left(c), 0.5f);
    }

    /** Dialogs clamp themselves to their container, so a long cascade stops at the edge instead of
     * marching a document out of reach. No wrap logic needed in the manager. */
    @Test
    public void aLongCascadeClampsInsteadOfLeavingTheStage() {
        Dialog last = null;
        for (int i = 0; i < 40; i++) last = manager.manage(sized(new Dialog("extra" + i)));
        manager.showAll();

        // updateWithoutPainting, not settle(): placement runs at manage() time, before the dialog has
        // ever been laid out, so its own width is still 0 and the clamp has nothing to subtract. The
        // per-frame re-clamp ticker is what corrects it once the box is real — the same reason the
        // container-shrink case needs frames rather than a bare layout pass.
        frame();
        frame();

        assertTrue("must stay inside the stage, was " + left(last),
                left(last) + 120f <= 400f + 0.5f);
    }

    // ── Membership ──────────────────────────────────────────────────────────

    @Test
    public void managingIsIdempotent() {
        int before = manager.getDialogs().size();

        manager.manage(a);

        assertEquals(before, manager.getDialogs().size());
    }

    @Test
    public void unmanagingRemovesItFromTheStageAndTheSet() {
        manager.unmanage(c);

        assertFalse(manager.getDialogs().contains(c));
        assertNull("and it leaves the tree", c.parent());
        assertNotSame("active must not be left pointing at something unmanaged", c, manager.getActive());
    }

    @Test
    public void raisingSomethingUnmanagedDoesNothing() {
        Dialog stray = sized(new Dialog("stray"));
        stage.append(stray);
        settle();

        manager.raise(stray);

        assertNotSame(stray, manager.getActive());
    }

    @Test
    public void closeAllClosesEveryDialog() {
        manager.closeAll();

        assertFalse(a.isOpen());
        assertFalse(b.isOpen());
        assertFalse(c.isOpen());
    }
}
