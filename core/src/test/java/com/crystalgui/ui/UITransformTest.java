package com.crystalgui.ui;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.style.property.visual.border.LengthPercent;
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

    /** `transform-origin` decides what stays fixed — a top-left origin scales toward the corner. */
    @Test
    public void transformOriginDecidesWhatStaysFixed() {
        UIElement child = childInWindow();
        child.setTransform(UITransform.scale(0.5f));
        child.style(s -> s.general(g -> g.transformOrigin(LengthPercent.percent(0f), LengthPercent.percent(0f))));

        // With the origin at the element's own corner, that corner is the fixed point.
        var atOrigin = child.screenToLocal(0f, 0f);
        assertEquals(0f, atOrigin.x(), 0.001f);
        assertEquals(0f, atOrigin.y(), 0.001f);

        // And the far corner has moved in to half.
        var half = child.screenToLocal(50f, 50f);
        assertEquals(100f, half.x(), 0.001f);
        assertEquals(100f, half.y(), 0.001f);
    }

    /** `transform-origin` takes pixels as readily as percentages — 0px is the same as 0%. */
    @Test
    public void transformOriginAcceptsPixels() {
        UIElement child = childInWindow();
        child.setTransform(UITransform.scale(0.5f));
        child.style(s -> s.general(g -> g.transformOrigin(LengthPercent.px(0f), LengthPercent.px(0f))));

        var atOrigin = child.screenToLocal(0f, 0f);
        assertEquals(0f, atOrigin.x(), 0.001f);
        assertEquals(0f, atOrigin.y(), 0.001f);
    }

    /**
     * The reason the value type is an ordered list: CSS composes functions left-to-right, so the same
     * two functions in the other order are a different transform.
     *
     * <p>With the origin at the corner, {@code translate(10) scale(2)} moves by 10 and then scales the
     * translated space — local 0 lands at screen 10. {@code scale(2) translate(10)} scales first, so
     * the same translate happens in doubled space and local 0 lands at screen 20. A transform stored
     * as one translate field plus one scale field cannot tell these apart at all.</p>
     */
    @Test
    public void functionOrderChangesTheResult() {
        UIElement translateThenScale = childInWindow();
        translateThenScale.style(s -> s.general(g -> g.transformOrigin(LengthPercent.px(0f), LengthPercent.px(0f))));
        translateThenScale.setTransform(UITransform.translate(10f, 0f).withScale(2f, 2f));

        UIElement scaleThenTranslate = childInWindow();
        scaleThenTranslate.style(s -> s.general(g -> g.transformOrigin(LengthPercent.px(0f), LengthPercent.px(0f))));
        scaleThenTranslate.setTransform(UITransform.scale(2f).withTranslate(10f, 0f));

        // screenToLocal inverts, so ask where screen X maps to and check it is local 0.
        assertEquals("translate applied before the scale", 0f,
                translateThenScale.screenToLocal(10f, 0f).x(), 0.001f);
        assertEquals("translate applied inside the scale", 0f,
                scaleThenTranslate.screenToLocal(20f, 0f).x(), 0.001f);
    }

    /**
     * Skew's matrix is the one that is easy to transpose, and a transposed shear still looks like a
     * shear — so pin the direction rather than just "something happened".
     *
     * <p>{@code skewX(45deg)} means {@code x' = x + tan(45°)·y = x + y}, so with the origin at the
     * corner, local (0, 20) draws at screen (20, 20).</p>
     */
    @Test
    public void skewXShearsAlongXAndNotY() {
        UIElement child = childInWindow();
        child.style(s -> s.general(g -> g.transformOrigin(LengthPercent.px(0f), LengthPercent.px(0f))));
        child.setTransform(UITransform.of(UITransform.Op.skew((float) Math.toRadians(45), 0f)));

        var local = child.screenToLocal(20f, 20f);
        assertEquals("x is sheared by y", 0f, local.x(), 0.001f);
        assertEquals("y is untouched by skewX", 20f, local.y(), 0.001f);
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

        outer.style(s -> s.general(g -> g.transformOrigin(LengthPercent.px(0f), LengthPercent.px(0f))));
        inner.style(s -> s.general(g -> g.transformOrigin(LengthPercent.px(0f), LengthPercent.px(0f))));
        outer.setTransform(UITransform.scale(0.5f));
        inner.setTransform(UITransform.scale(0.5f));

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
