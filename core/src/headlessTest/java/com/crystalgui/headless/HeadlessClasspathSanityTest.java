package com.crystalgui.headless;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Guards the guard.
 *
 * <p>Every other test in this source set proves something by <em>not</em> throwing
 * {@code NoClassDefFoundError}. That proof is worth exactly nothing if CrystalGraphics quietly
 * reappears on the classpath — the tests would all still pass, while asserting nothing at all. This
 * one fails loudly in that case.</p>
 */
public class HeadlessClasspathSanityTest {

    @Test
    public void crystalGraphicsIsNotOnTheClasspath() {
        assertAbsent("com.crystalgraphics.gl.texture.CgTexture2D");
        assertAbsent("com.crystalgraphics.api.text.CgTextLayout");
        assertAbsent("com.crystalgraphics.api.font.CgFont");
        assertAbsent("com.crystalgraphics.util.io.CgIO");
        assertAbsent("com.crystalgraphics.api.vertex.CgVertexTransformUtil");
    }

    /** JOML and Taffy are required even headlessly — UIElement/ElementStyle have fields of these
     * types, and field descriptors resolve at class load. Asserted so a future classpath trim
     * doesn't quietly remove them and turn every headless test into a confusing failure. */
    @Test
    public void jomlAndTaffyArePresentBecauseTheyAreFieldTypes() {
        assertPresent("org.joml.Matrix4f");
        assertPresent("dev.vfyjxf.taffy.tree.TaffyTree");
    }

    private static void assertAbsent(String className) {
        try {
            Class.forName(className);
            fail("CrystalGraphics is on the headless classpath (" + className + ") — "
                    + "every headless assertion in this source set is now vacuous. "
                    + "Remove it from headlessTest's dependencies.");
        } catch (ClassNotFoundException expected) {
            // exactly right
        }
    }

    private static void assertPresent(String className) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException e) {
            fail(className + " must be on the headless classpath — it appears in a field type that "
                    + "resolves at class load, so core/ cannot load without it.");
        }
    }
}
