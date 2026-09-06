package com.crystalgui.render;

import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.util.io.CgIO;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What {@code CgMaterialShader} compares a reload against — and why it expands includes first.
 *
 * <p>A resource reload marks every material dirty, so the shader skips the recompile when its source
 * has not changed. The comparison is made on the source with {@code #include} <b>expanded</b>, and this
 * pins that: comparing the raw {@code .shader} text instead would silently stop reloading every shader
 * whose only change was in a lib, which presents as "F3+T does nothing for sdf.glsl edits" — a bug
 * nobody would find twice, because the shader it fails on still renders fine from its stale program.</p>
 *
 * <p>It lives here rather than beside the code it describes because CrystalGraphics' own test source
 * set cannot currently load log4j, and the preprocessor logs.</p>
 */
public class ShaderReloadComparisonTest {

    /** Shipped, and includes {@code lib/sdf.glsl} — the case the skip has to stay honest about. */
    private static final String WITH_INCLUDE = "crystalgui:shaders/gui_rounded_rect.shader";

    private static String expand(String path) {
        String source = CgIO.loadSource(path);
        assertNotNull("shipped shader should load: " + path, source);
        return new CgShaderPreprocessor().process(source, path);
    }

    @Test
    public void theComparedTextCarriesTheIncludedLib() {
        String raw = CgIO.loadSource(WITH_INCLUDE);
        assertTrue("fixture must actually include something", raw.contains("#include"));

        String expanded = expand(WITH_INCLUDE);
        assertTrue("the lib's contents must be in the compared text, or editing it changes nothing",
                expanded.contains("sdf_rounded_box"));
        assertTrue(expanded.length() > raw.length());
    }

    @Test
    public void anUnchangedSourceComparesEqual() {
        // The other half: if expansion were not deterministic the skip would never fire and every
        // reload would recompile regardless.
        assertEquals(expand(WITH_INCLUDE), expand(WITH_INCLUDE));
    }
}
