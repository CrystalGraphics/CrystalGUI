package com.crystalgui.headless;

import com.crystalgui.net.mirror.UINodeMirror;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.UITransform;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Value-level and wire-level coverage of {@code transform} / {@code transform-origin}.
 *
 * <p>Headless, i.e. with no CrystalGraphics on the classpath — which is the point: a dedicated server
 * builds a tree, sets a transform, and ships it. If {@link UITransform} or its codec ever picked up a
 * rendering dependency, this test set is what fails.</p>
 *
 * <p>The CSS-facing half lives in {@code TransformCssTest} in the ordinary test set, because
 * {@code StyleSheet} cannot class-load headlessly.</p>
 */
public class TransformStylePropertiesTest {

    private static final float EPS = 1e-4f;

    @Test
    public void defaultsAreIdentityAndCentred() {
        UINode element = new UINode();
        assertTrue(element.getStyle().getGeneralGroup().transform().isIdentity());
        assertEquals(LengthPercent.percent(0.5f), element.getStyle().getGeneralGroup().transformOriginX());
        assertEquals(LengthPercent.percent(0.5f), element.getStyle().getGeneralGroup().transformOriginY());
    }

    /** Matching CSS. Also required: inheritance is pull-based and would skip the listener that dirties
     * the subtree's matrices — and a transform already reaches descendants through the matrix chain. */
    @Test
    public void noneOfTheThreeInherit() {
        assertFalse(StylePropertyRegistry.TRANSFORM.isInheritable());
        assertFalse(StylePropertyRegistry.TRANSFORM_ORIGIN_X.isInheritable());
        assertFalse(StylePropertyRegistry.TRANSFORM_ORIGIN_Y.isInheritable());
    }

    @Test
    public void allThreeCanAnimate() {
        assertTrue(StylePropertyRegistry.TRANSFORM.isAllowTransition());
        assertTrue(StylePropertyRegistry.TRANSFORM_ORIGIN_X.isAllowTransition());
        assertTrue(StylePropertyRegistry.TRANSFORM_ORIGIN_Y.isAllowTransition());
    }

    /** Matching function lists interpolate component-wise, not by snapping — the default interpolator
     * is BINARY, so a property that forgot to set one would silently pass everything except this. */
    @Test
    public void matchingListsInterpolateComponentWise() {
        UITransform mid = StylePropertyRegistry.TRANSFORM.getInterpolator()
                .interpolate(UITransform.scale(1f), UITransform.scale(3f), 0.5f);
        assertEquals(2f, mid.ops().get(0).fx(), EPS);
        assertEquals(2f, mid.ops().get(0).fy(), EPS);
    }

    @Test
    public void translationsInterpolateInTheirOwnUnit() {
        UITransform mid = StylePropertyRegistry.TRANSFORM.getInterpolator()
                .interpolate(UITransform.translate(0f, 0f), UITransform.translate(10f, 20f), 0.5f);
        assertEquals(LengthPercent.px(5f), mid.ops().get(0).lx());
        assertEquals(LengthPercent.px(10f), mid.ops().get(0).ly());
    }

    /** CSS's fallback for mismatched lists is a matrix decomposition; this engine snaps instead, and
     * that gap is documented. What must NOT happen is interpolating mismatched kinds pairwise. */
    @Test
    public void mismatchedListsSnap() {
        UITransform scale = UITransform.scale(2f);
        UITransform rotate = UITransform.rotate(1f);
        var interp = StylePropertyRegistry.TRANSFORM.getInterpolator();

        assertEquals("different kinds", scale, interp.interpolate(scale, rotate, 0.4f));
        assertEquals(rotate, interp.interpolate(scale, rotate, 0.6f));
        assertEquals("different lengths", scale,
                interp.interpolate(scale, scale.withRotation(1f), 0.4f));
    }

    /** A px-to-% translation has no single intermediate, so the whole transform snaps rather than
     * half of it interpolating and half not. */
    @Test
    public void incommensurableTranslationsSnapTheWholeTransform() {
        UITransform from = UITransform.of(UITransform.Op.translate(LengthPercent.px(0f), LengthPercent.ZERO));
        UITransform to = UITransform.of(UITransform.Op.translate(LengthPercent.percent(1f), LengthPercent.ZERO));
        assertEquals(from, StylePropertyRegistry.TRANSFORM.getInterpolator().interpolate(from, to, 0.4f));
    }

    // ── Serialization ───────────────────────────────────────────────────────

    /**
     * A transform set imperatively lands at INLINE origin, which is the only origin that travels — so
     * without a codec {@code InlineStyleCodec} throws. This is the test that would have caught that.
     */
    @Test
    public void transformsRoundTripToAClient() {
        UINode element = new UINode();
        element.getStyle().getGeneralGroup().transform(UITransform.of(
                UITransform.Op.translate(LengthPercent.px(10f), LengthPercent.percent(0.25f)),
                UITransform.Op.scale(2f, 3f),
                UITransform.Op.rotate(1.25f),
                UITransform.Op.skew(0.1f, 0.2f)));
        element.getStyle().getGeneralGroup()
                .transformOrigin(LengthPercent.px(4f), LengthPercent.percent(0f));

        for (DynamicOps<?> ops : new DynamicOps<?>[]{JsonOps.INSTANCE, PlainOps.INSTANCE}) {
            UINode clone = roundTrip(element, ops);
            assertEquals("the op list survives in order", element.getStyle().getGeneralGroup().transform(), clone.getStyle().getGeneralGroup().transform());
            assertEquals(LengthPercent.px(4f), clone.getStyle().getGeneralGroup().transformOriginX());
            assertEquals(LengthPercent.percent(0f), clone.getStyle().getGeneralGroup().transformOriginY());
        }
    }

    /**
     * {@code LengthPercent} had no codec at all, so this covers {@code border-radius} and
     * {@code outline-offset} as much as {@code transform-origin} — all of them threw on encode.
     */
    @Test
    public void lengthPercentValuedPropertiesRoundTrip() {
        UINode element = new UINode();
        element.getStyle().getGeneralGroup()
                .textOffsetY(LengthPercent.px(1.5f))
                .outlineWidth(LengthPercent.percent(0.1f));

        var style = roundTrip(element, JsonOps.INSTANCE).getStyle().getGeneralGroup();
        assertEquals(LengthPercent.px(1.5f), style.textOffsetY());
        assertEquals(LengthPercent.percent(0.1f), style.outlineWidth());
    }

    private static <T> UINode roundTrip(UINode source, DynamicOps<T> ops) {
        return new UINodeMirror<>(ops).decode(new UINodeMirror<>(ops).describe(source));
    }
}
