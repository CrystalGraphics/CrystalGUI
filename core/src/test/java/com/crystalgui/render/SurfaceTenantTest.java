package com.crystalgui.render;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A picture composited into a surface damages its place there when it moves or stops being composited: no ghost stays. */
public class SurfaceTenantTest {

    @Test
    public void aSurfaceNoLongerCompositedDamagesWhereItWas() {
        Surface window = new Surface(null), cover = new Surface(null);
        window.beginWalk(1L, false);
        cover.placeIn(window, 10f, 20f, 50f, 60f, 1L);
        window.endWalk();

        window.beginWalk(2L, false);
        window.endWalk();
        float[] damage = new float[4];
        assertTrue("the cover left and nothing of the window was drawn again", window.damage(damage));
        assertArrayEquals(new float[]{10f, 20f, 50f, 60f}, damage, 0f);
    }

    @Test
    public void aLayerThatShrinksDamagesWhereItWas() {
        Surface window = new Surface(null);
        Surface.Placement layer = new Surface.Placement();
        window.beginWalk(1L, false);
        layer.place(window, 0f, 0f, 100f, 100f, 1L);
        window.endWalk();

        window.beginWalk(2L, false);
        layer.place(window, 40f, 40f, 60f, 60f, 2L);
        window.endWalk();
        float[] damage = new float[4];
        assertTrue(window.damage(damage));
        assertArrayEquals("the larger picture it drew the walk before was left standing",
                new float[]{0f, 0f, 100f, 100f}, damage, 0f);
    }

    @Test
    public void aSurfaceStillCompositedAndUnmovedDamagesOnlyItsPlace() {
        Surface window = new Surface(null), cover = new Surface(null);
        window.beginWalk(1L, false);
        cover.placeIn(window, 10f, 20f, 50f, 60f, 1L);
        window.endWalk();

        window.beginWalk(2L, false);
        window.endWalk();
        window.beginWalk(3L, false);
        window.endWalk();
        float[] damage = new float[4];
        assertFalse("a surface gone a walk ago damaged again", window.damage(damage));
    }
}
