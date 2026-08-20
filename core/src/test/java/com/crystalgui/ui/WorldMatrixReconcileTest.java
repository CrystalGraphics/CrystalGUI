package com.crystalgui.ui;

import com.crystalgui.core.data.Transform2D;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import org.joml.Matrix4f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * The seam between what was <b>drawn</b> and what is <b>hit-tested</b>.
 *
 * <p>Both halves of this were live for as long as the top layer has existed, and they only became
 * visible when something needed a pointer position inside a promoted, scrolled subtree: a link in the
 * documentation popup could not be hovered until it had been clicked at some fifteen times, after
 * which it worked and kept working. The trace that caught it showed the run's world origin reading
 * y=-10628 where the layout put it at y=506 — for two hundred frames, with the pointer stationary —
 * and then correcting itself between one frame and the next with no input at all.</p>
 *
 * <p>Neither half is reachable through a paint in a test: {@code drawSubtree} needs a GL context.
 * {@code reconcileWorldMatrix} is package-visible for exactly that reason, and these drive it
 * directly.</p>
 */
public class WorldMatrixReconcileTest extends UiTestBase {

    /**
     * <b>The cached matrix must not alias the caller's.</b>
     *
     * <p>{@code CacheCell.set} stores the reference it is handed, and the pose an element is drawn
     * with is a live, shared, mutable {@code Matrix4f} — an element with an identity transform does
     * not push its own, so siblings share one instance that the walk goes on mutating. Storing it
     * made an already-drawn element's transform change underneath it.</p>
     *
     * <p>Reverting to {@code localToWorld.set(drawn)} fails on the last assertion, which is the one
     * that matters: equality right after the call proves nothing, because an alias is equal too.</p>
     */
    @Test
    public void reconcilingCopiesThePoseRatherThanAliasingIt() {
        UIElement element = new UIElement().layout(l -> l.width(100).height(40));
        UIWindow window = settledWindow(element);
        assertTrue("the fixture never laid out", element.getRuntimeCache().getWidth() > 0f);

        Matrix4f drawn = new Matrix4f().translate(11f, 22f, 0f);
        element.reconcileWorldMatrix(drawn);

        Matrix4f tracked = element.getRuntimeCache().localToWorld.get();
        assertEquals("the pose was not taken up at all", drawn, tracked);
        assertNotSame("the cache aliases the caller's matrix", drawn, tracked);

        // The caller goes on mutating its own matrix, exactly as the PoseStack does.
        drawn.translate(500f, 900f, 0f);
        assertEquals("the cached matrix followed the caller's later edits -- it is an alias",
                new Matrix4f().translate(11f, 22f, 0f),
                element.getRuntimeCache().localToWorld.get());

        window.updateWithoutPainting();
    }

    /**
     * <b>A matrix already read — and therefore CLEAN — must still be corrected.</b>
     *
     * <p>This is the half that made the defect permanent rather than momentary. Any {@code get()}
     * runs the calculator and marks the cell clean, and nothing invalidates it on a scroll or a
     * relayout, so the old {@code if (isDirty())} gate meant a value computed once from a stale
     * parent chain was never revisited. A hit test is itself a {@code get()}, so merely asking where
     * something was froze the wrong answer in place.</p>
     */
    @Test
    public void aCleanMatrixIsStillCorrectedFromThePose() {
        UIElement element = new UIElement().layout(l -> l.width(100).height(40));
        UIWindow window = settledWindow(element);

        // Read it first -- this is what a hit test does, and it marks the cell clean.
        Matrix4f before = new Matrix4f(element.getRuntimeCache().localToWorld.get());
        assertTrue("the cell should be clean after a read",
                !element.getRuntimeCache().localToWorld.isDirty());

        Matrix4f drawn = new Matrix4f(before).translate(37f, 73f, 0f);
        element.reconcileWorldMatrix(drawn);

        assertEquals("a clean cell was left holding the stale matrix",
                drawn, element.getRuntimeCache().localToWorld.get());

        // And the inverse must have been rebuilt, or screenToLocal keeps answering in the old space.
        // Derived rather than hand-computed: the matrix carries the root uiScale as well as the
        // translation, so the world point of the local origin is the only honest thing to ask about.
        var origin = Transform2D.apply(drawn, 0f, 0f);
        var local = element.screenToLocal(origin.x(), origin.y());
        assertEquals("worldToLocal was not invalidated alongside it", 0f, local.x(), 0.001f);
        assertEquals("worldToLocal was not invalidated alongside it", 0f, local.y(), 0.001f);

        window.updateWithoutPainting();
    }

    /** A window with this element attached and its layout settled. */
    private UIWindow settledWindow(UIElement element) {
        UIElement root = new UIElement().layout(l -> l.width(400).height(200));
        root.addChild(element);
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(400, 200);
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
        return window;
    }
}
