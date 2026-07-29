package com.crystalgui.ui;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.CgUiInputAdapter;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Per-element transforms, and specifically that <b>hit-testing follows them</b>.
 *
 * <p>That is the whole risk in this feature. Rendering pushes the transform onto the {@code PoseStack}
 * while pointer maths inverts {@code RuntimeCache.localToWorld}; if those two ever disagree, a click
 * lands somewhere other than what the user sees, and nothing about the rendering looks wrong. Both go
 * through {@link UITransform#applyTo} precisely so they cannot diverge — these tests pin that.</p>
 *
 * <p>Layout-freeness is the other half: a transform must not reflow anything, which is what makes it
 * usable for a zoomable canvas.</p>
 */
public class UITransformTest {

    /** {@code UIWindow}'s constructor builds a {@code UIInputHandler}, which asks the adapter how
     * many mouse buttons exist — a window cannot be constructed without one. */
    @Before
    public void registerStubAdapter() {
        CrystalGuiCore.setAdapter(new CgUiInputAdapter() {
            @Override public int getCurrentModifiers() { return 0; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
        });
    }

    /** A 100x100 child at the origin of a window whose uiScale is 1, so logical == physical. */
    private static UIElement childInWindow() {
        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        UIElement child = new UIElement().layout(l -> l.width(100).height(100));
        root.addChild(child);
        UIWindow window = new UIWindow(Ui.of(root));
        window.setUiScale(1f);
        window.init(400, 400);
        return child;
    }

    @Test
    public void identityIsTheDefaultAndChangesNothing() {
        UIElement child = childInWindow();
        assertTrue(child.getTransform().isIdentity());
        assertEquals(UITransform.IDENTITY, child.getTransform());
    }

    /**
     * The core guarantee: a point that is inside the element on screen maps back inside its local box.
     *
     * <p>With a 0.5 scale about the centre, the element covers half its former area on screen — so a
     * screen point near its original edge is now OUTSIDE it, and a point near the centre is still in.
     * Both are checked, because a transform that was silently ignored would pass the second alone.</p>
     */
    @Test
    public void scaleIsInvertedByHitTesting() {
        UIElement child = childInWindow();
        child.setTransform(UITransform.scale(0.5f));

        // Centre stays put under a centred scale.
        var atCentre = child.screenToLocal(50f, 50f);
        assertEquals(50f, atCentre.x(), 0.001f);
        assertEquals(50f, atCentre.y(), 0.001f);

        // Screen (30, 30) is inside the shrunken box; it maps to local (10, 10) — still inside.
        var inside = child.screenToLocal(30f, 30f);
        assertEquals(10f, inside.x(), 0.001f);
        assertEquals(10f, inside.y(), 0.001f);
        assertTrue(child.isMouseOverElement(inside.x(), inside.y()));

        // Screen (10, 10) is now OUTSIDE the shrunken box; it maps to local (-30, -30).
        var outside = child.screenToLocal(10f, 10f);
        assertEquals(-30f, outside.x(), 0.001f);
        assertTrue("a point outside the scaled box must not hit it",
                !child.isMouseOverElement(outside.x(), outside.y()));
    }

    /** Translation is in the parent's units — it is applied outside the scale, matching CSS. */
    @Test
    public void translationIsInvertedByHitTesting() {
        UIElement child = childInWindow();
        child.setTransform(UITransform.translate(25f, 40f));

        var local = child.screenToLocal(75f, 90f);
        assertEquals(50f, local.x(), 0.001f);
        assertEquals(50f, local.y(), 0.001f);
    }

    /** The pivot is normalised, so a top-left pivot scales toward the corner rather than the centre. */
    @Test
    public void pivotDecidesWhatStaysFixed() {
        UIElement child = childInWindow();
        child.setTransform(UITransform.scale(0.5f).withPivot(0f, 0f));

        // With the pivot at the element's own origin, that origin is the fixed point.
        var atOrigin = child.screenToLocal(0f, 0f);
        assertEquals(0f, atOrigin.x(), 0.001f);
        assertEquals(0f, atOrigin.y(), 0.001f);

        // And the far corner has moved in to half.
        var half = child.screenToLocal(50f, 50f);
        assertEquals(100f, half.x(), 0.001f);
        assertEquals(100f, half.y(), 0.001f);
    }

    /** Nested transforms compose — a child inside a scaled container is scaled by both. */
    @Test
    public void transformsCompoundThroughTheTree() {
        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        UIElement outer = new UIElement().layout(l -> l.width(200).height(200));
        UIElement inner = new UIElement().layout(l -> l.width(100).height(100));
        outer.addChild(inner);
        root.addChild(outer);
        UIWindow window = new UIWindow(Ui.of(root));
        window.setUiScale(1f);
        window.init(400, 400);

        outer.setTransform(UITransform.scale(0.5f).withPivot(0f, 0f));
        inner.setTransform(UITransform.scale(0.5f).withPivot(0f, 0f));

        // 0.5 * 0.5 = 0.25, so local (100, 100) sits at screen (25, 25).
        var local = inner.screenToLocal(25f, 25f);
        assertEquals(100f, local.x(), 0.001f);
        assertEquals(100f, local.y(), 0.001f);
    }

    /** Layout-free: transforming an element must not move or resize it as far as Taffy is concerned. */
    @Test
    public void aTransformDoesNotReflowAnything() {
        UIElement child = childInWindow();
        float w = child.getRuntimeCache().getWidth();
        float h = child.getRuntimeCache().getHeight();
        float x = child.getRuntimeCache().getX();

        child.setTransform(UITransform.scale(3f).withTranslate(50f, 50f));

        assertEquals("width is layout's, not the transform's", w, child.getRuntimeCache().getWidth(), 0.001f);
        assertEquals(h, child.getRuntimeCache().getHeight(), 0.001f);
        assertEquals(x, child.getRuntimeCache().getX(), 0.001f);
    }

    /** Setting an equal-valued transform must not churn the subtree's matrix caches. */
    @Test
    public void settingAnEqualTransformIsANoOp() {
        UIElement child = childInWindow();
        child.setTransform(UITransform.scale(2f));
        UITransform first = child.getTransform();
        child.setTransform(UITransform.scale(2f));
        assertEquals(first, child.getTransform());
    }

    /** Null resets rather than throwing — the same shape as clearing an icon or a group. */
    @Test
    public void nullResetsToIdentity() {
        UIElement child = childInWindow();
        child.setTransform(UITransform.scale(2f));
        child.setTransform(null);
        assertTrue(child.getTransform().isIdentity());
    }
}
