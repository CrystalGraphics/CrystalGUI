package com.crystalgui.headless;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * What the headless classpath must hold: a dedicated server's. CrystalGraphics core ships on a server and holds shared
 * utilities the engine names in its field and signature types (easing); JOML and Taffy are field types too. What a
 * server lacks is a GL context, and no test here has one.
 */
public class HeadlessClasspathSanityTest {

    @Test
    public void crystalGraphicsCoreIsPresentForItsSharedUtilities() {
        assertPresent("com.crystalgraphics.easing.CgEasing");
    }

    /** JOML and Taffy are required even headlessly — UIElement/ElementStyle have fields of these
     * types, and field descriptors resolve at class load. Asserted so a future classpath trim
     * doesn't quietly remove them and turn every headless test into a confusing failure. */
    @Test
    public void jomlAndTaffyArePresentBecauseTheyAreFieldTypes() {
        assertPresent("org.joml.Matrix4f");
        assertPresent("dev.vfyjxf.taffy.tree.TaffyTree");
    }

    /** It reads default.css through CgIO when it initialises, which needs core and no GL. */
    @Test
    public void styleSheetLoads() {
        assertPresent("com.crystalgui.style.sheet.StyleSheet");
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
