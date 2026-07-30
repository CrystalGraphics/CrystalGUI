package com.crystalgui.render;

import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.gl.material.parse.CgMaterialShaderCompiler;
import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.gl.material.parse.CgShaderParser;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.util.io.CgIO;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Every shipped {@code crystalgui:shaders/*.shader} must generate a vertex stage free of
 * fragment-only GLSL builtins.
 *
 * <p><b>This is the test that catches the AMD crash.</b> {@code gui_rounded_rect.shader} includes
 * {@code crystalgraphics:shaders/lib/sdf.glsl} at <i>material</i> scope, and the compiler hoists
 * every preamble {@code #}-line into <i>both</i> stages — so {@code sdf_coverage}'s {@code fwidth}
 * landed in the vertex shader. NVIDIA accepted it silently, AMD rejected it, and the gallery could
 * not start at all on that machine. A GL test on this machine structurally cannot catch that; this
 * one can, because it never asks a driver for an opinion.</p>
 *
 * <p>Two details make it non-vacuous: it scans the <b>preprocessed</b> source (the bad code arrives
 * via an {@code #include}), and it evaluates the stage conditionals itself, because
 * {@link CgShaderPreprocessor} leaves {@code #ifdef} to the driver — so after the fix the raw
 * preprocessed vertex text still <i>contains</i> {@code fwidth}, inside a block the driver discards.
 * Non-stage conditionals keep both branches, so an inactive {@code #pragma cg_feature} cannot hide
 * a banned builtin.</p>
 *
 * <p>CrystalGraphics carries a near-identical test for {@code crystalgraphics:shaders/*}. The
 * duplication is deliberate: separate Gradle builds, each gating its own regressions.</p>
 */
public class ShippedShaderStagePurityTest {

    /** Builtins that exist only in the fragment stage. Keep in sync with CrystalGraphics' copy. */
    private static final String[] FRAGMENT_ONLY = {
            "fwidth", "fwidthFine", "fwidthCoarse",
            "dFdx", "dFdy", "dFdxFine", "dFdyFine", "dFdxCoarse", "dFdyCoarse",
            "discard",
            "gl_FragCoord", "gl_FrontFacing", "gl_PointCoord", "gl_FragDepth",
            "interpolateAtCentroid", "interpolateAtSample", "interpolateAtOffset",
            "gl_SampleID", "gl_SamplePosition", "gl_SampleMask", "gl_SampleMaskIn",
    };

    private static final Pattern BANNED =
            Pattern.compile("\\b(" + String.join("|", FRAGMENT_ONLY) + ")\\b");

    private static final String NAMESPACE = "crystalgui";

    private static final List<CgAttachedBuffer> NO_BUFFERS = Collections.emptyList();

    @After
    public void clearCapabilitiesCache() throws Exception {
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, null);
    }

    // ── The gate ──────────────────────────────────────────────────────────────

    /** TBO path — {@code #version 330 core}. */
    @Test
    public void shippedShaders_vertexStage_hasNoFragmentOnlyBuiltins_tboPath() throws Exception {
        assertAllShippedShadersPure(CgCapabilities.ShaderBufferPath.TBO);
    }

    /**
     * SSBO path — {@code #version 430 core}. This is what the AMD box runs, and the two paths emit
     * different sources, so covering one leaves half the generated GLSL unexamined.
     */
    @Test
    public void shippedShaders_vertexStage_hasNoFragmentOnlyBuiltins_ssboPath() throws Exception {
        assertAllShippedShadersPure(CgCapabilities.ShaderBufferPath.SSBO_GL43);
    }

    private void assertAllShippedShadersPure(CgCapabilities.ShaderBufferPath path) throws Exception {
        installCapabilities(path);

        List<String> shaders = shippedShaderPaths(NAMESPACE);
        assertFalse("Found no shipped .shader files under assets/" + NAMESPACE + "/shaders/ — "
                + "this test would pass vacuously", shaders.isEmpty());

        for (String resourcePath : shaders) {
            String source = CgIO.loadSource(resourcePath);
            assertNotNull("Could not load " + resourcePath, source);

            CgParsedShader parsed = CgShaderParser.parse(source, resourcePath);
            for (CgParsedPass pass : parsed.passes()) {
                CgMaterialShaderCompiler.CompiledSource cs = CgMaterialShaderCompiler.compile(
                        parsed, pass, NO_BUFFERS, null,
                        CgMaterialShaderCompiler.CompileConfig.DEFAULT);

                String offender = firstFragmentOnlyBuiltinInVertexStage(cs.vertexSource(), resourcePath);
                assertNull(resourcePath + " pass '" + pass.name() + "' (" + path + "): the generated"
                        + " VERTEX stage uses the fragment-only builtin '" + offender + "'."
                        + " Guard it with #ifndef CG_VERTEX_STAGE in the lib that defines it.",
                        offender);
            }
        }
    }

    /**
     * The other half of the guard: {@code sdf_coverage} must still <b>reach the fragment stage</b>.
     *
     * <p>Without this, flipping the guard's polarity — or widening it to strip the function from
     * both stages — passes every purity assertion above while silently breaking every antialiased
     * rounded rect in the engine. The vertex check alone cannot tell "correctly guarded" from
     * "deleted".</p>
     */
    @Test
    public void roundedRect_stillHasSdfCoverage_inTheFragmentStage() throws Exception {
        installCapabilities(CgCapabilities.ShaderBufferPath.SSBO_GL43);
        String path = "crystalgui:shaders/gui_rounded_rect.shader";
        CgParsedShader parsed = CgShaderParser.parse(CgIO.loadSource(path), path);
        CgMaterialShaderCompiler.CompiledSource cs = CgMaterialShaderCompiler.compile(
                parsed, parsed.passes().get(0), NO_BUFFERS, null,
                CgMaterialShaderCompiler.CompileConfig.DEFAULT);

        String frag = stripInactiveStageBlocks(
                stripComments(new CgShaderPreprocessor().process(cs.fragmentSource(), path)), false);
        assertTrue("sdf_coverage must survive into the fragment stage — if this fails the guard in "
                + "sdf.glsl has the wrong polarity and rounded rects lost their antialiasing",
                frag.contains("float sdf_coverage(float dist)"));

        String vert = stripInactiveStageBlocks(
                stripComments(new CgShaderPreprocessor().process(cs.vertexSource(), path)), true);
        assertFalse("sdf_coverage must NOT reach the vertex stage",
                vert.contains("float sdf_coverage(float dist)"));
    }

    // ── Meta-tests: prove the detector detects ────────────────────────────────
    // These drive firstFragmentOnlyBuiltinInVertexStage directly rather than through the compiler.
    // Going through the compiler would test the wrong thing: partitionGlobalDecls splits a pass's
    // '#' lines away from its code lines, so a hand-written "#ifndef ... #endif" around a function
    // in a .shader is torn apart before it ever reaches this scan. Real guards live inside included
    // .glsl files, which the preprocessor expands intact — the gate above covers that end to end.

    /** A bare builtin in the vertex source must be reported. */
    @Test
    public void detector_flags_fwidthInVertexSource() {
        assertEquals("fwidth", firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\nfloat bad(float d) { return fwidth(d); }\n", "test"));
    }

    /** One guarded out of the vertex stage must not be — this is what the fix relies on. */
    @Test
    public void detector_ignores_builtinGuardedOutOfVertexStage() {
        assertNull(firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\n#define CG_VERTEX_STAGE 1\n"
                        + "#ifndef CG_VERTEX_STAGE\nfloat ok(float d) { return fwidth(d); }\n#endif\n",
                "test"));
    }

    /** ...but one guarded <i>into</i> it still must be. */
    @Test
    public void detector_flags_builtinGuardedIntoVertexStage() {
        assertEquals("dFdx", firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\n#define CG_VERTEX_STAGE 1\n"
                        + "#ifdef CG_VERTEX_STAGE\nfloat bad(float d) { return dFdx(d); }\n#endif\n",
                "test"));
    }

    /** A builtin behind an inactive keyword must still be reported — variants get compiled too. */
    @Test
    public void detector_flags_builtinBehindAnUnknownMacro() {
        assertEquals("gl_FragCoord", firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\n#ifdef SOME_FEATURE\nvec4 bad() { return gl_FragCoord; }\n#endif\n",
                "test"));
    }

    /** Prose must not false-positive — {@code example.shader} discusses {@code discard} in comments. */
    @Test
    public void detector_ignores_builtinNamesInComments() {
        assertNull(firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\n// use discard here\n/* or fwidth, or gl_FragCoord */\n", "test"));
    }

    // ── Machinery (mirrored in CrystalGraphics' ShippedShaderStagePurityTest) ──

    static String firstFragmentOnlyBuiltinInVertexStage(String generatedVertexSource, String path) {
        String expanded = new CgShaderPreprocessor().process(generatedVertexSource, path);
        String scannable = stripInactiveStageBlocks(stripComments(expanded), true);
        java.util.regex.Matcher m = BANNED.matcher(scannable);
        return m.find() ? m.group(1) : null;
    }

    /** Removes {@code //} and block comments so prose ("...or discard...") cannot false-positive. */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i++; // land on '/', loop's i++ steps past it
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Drops text the driver's preprocessor would discard for the vertex stage. Only
     * {@code CG_VERTEX_STAGE} / {@code CG_FRAGMENT_STAGE} are resolved; every other conditional
     * keeps <b>both</b> branches, so an inactive keyword cannot hide a banned builtin.
     */
    static String stripInactiveStageBlocks(String src, boolean vertexStage) {
        StringBuilder out = new StringBuilder(src.length());
        // frame[0] = is this a resolved stage conditional, frame[1] = is this branch emitting
        Deque<boolean[]> stack = new ArrayDeque<>();

        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#ifdef ") || t.startsWith("#ifndef ")) {
                boolean negated = t.startsWith("#ifndef ");
                String name = t.substring(negated ? 8 : 7).trim();
                Boolean defined = stageMacroValue(name, vertexStage);
                if (defined == null) {
                    stack.push(new boolean[]{false, true});
                } else {
                    stack.push(new boolean[]{true, negated != defined});
                }
                continue;
            }
            if (t.startsWith("#if")) {                 // #if / #if defined(...) — unresolved
                stack.push(new boolean[]{false, true});
                continue;
            }
            if (t.startsWith("#elif")) {               // give up on this frame; keep everything
                if (!stack.isEmpty()) { stack.peek()[0] = false; stack.peek()[1] = true; }
                continue;
            }
            if (t.equals("#else")) {
                if (!stack.isEmpty()) {
                    boolean[] f = stack.peek();
                    f[1] = f[0] ? !f[1] : true;
                }
                continue;
            }
            if (t.startsWith("#endif")) {
                if (!stack.isEmpty()) stack.pop();
                continue;
            }
            boolean emitting = true;
            for (boolean[] f : stack) {
                if (!f[1]) { emitting = false; break; }
            }
            if (emitting) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static Boolean stageMacroValue(String name, boolean vertexStage) {
        if ("CG_VERTEX_STAGE".equals(name)) return vertexStage;
        if ("CG_FRAGMENT_STAGE".equals(name)) return !vertexStage;
        return null;
    }

    static List<String> shippedShaderPaths(String namespace) throws Exception {
        List<String> out = new ArrayList<>();
        URL dir = ShippedShaderStagePurityTest.class.getResource("/assets/" + namespace + "/shaders/");
        if (dir == null) return out;

        if ("file".equals(dir.getProtocol())) {
            File[] files = new File(dir.toURI()).listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".shader")) {
                        out.add(namespace + ":shaders/" + f.getName());
                    }
                }
            }
        } else if ("jar".equals(dir.getProtocol())) {
            String spec = dir.getPath();
            String jarPath = spec.substring(5, spec.indexOf("!"));
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(new File(new URL("file:" + jarPath).toURI()))) {
                String prefix = "assets/" + namespace + "/shaders/";
                for (java.util.Enumeration<java.util.jar.JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    String n = e.nextElement().getName();
                    if (n.startsWith(prefix) && n.endsWith(".shader")
                            && n.indexOf('/', prefix.length()) < 0) {
                        out.add(namespace + ":shaders/" + n.substring(prefix.length()));
                    }
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Reflection stub of the capability cache — no GL context involved. */
    static void installCapabilities(CgCapabilities.ShaderBufferPath path) throws Exception {
        Constructor<CgCapabilities> ctor = CgCapabilities.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CgCapabilities stub = ctor.newInstance();
        Field pathField = CgCapabilities.class.getDeclaredField("shaderBufferPath");
        pathField.setAccessible(true);
        pathField.set(stub, path);
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, stub);
    }
}
