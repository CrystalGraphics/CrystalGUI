package com.crystalgui.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLContext;
import com.crystalgraphics.platform.gl.tracked.CgTrackedStateProvider;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Every shader CrystalGUI ships, compiled by shaderc and linked on CrystalGraphics' tracked backend, in every pass
 * and keyword variant — as a Vulkan device will run them (plan/device-seam.md §6). CrystalGraphics' own shaders are
 * its {@code EngineOnTrackedBackendTest}'s.
 */
public class ShippedShadersOnTrackedBackendTest {

    private static ShadercGlslCompiler compiler;

    @BeforeClass
    public static void install() {
        compiler = new ShadercGlslCompiler();
        CgTrackedGLBackend gl = new CgTrackedGLBackend(new CgRecordingDevice(64, 64), compiler, true);
        CgGL.init(gl);
        CgCapabilities.init(new CgTrackedGLContext());
        CgCapabilities.clearCache();
        CgGlState.reset();
        CgGlState.setProvider(new CgTrackedStateProvider(gl));
        // CgGraphicsLifecycle.initContext's own steps, less the platform it would ask for a context.
        CgBindingPoints.init(CgCapabilities.detect());
        CgFallbackTextures.init();
    }

    @AfterClass
    public static void uninstall() {
        CgGlState.reset();
        compiler.close();
    }

    @Test
    public void everyPassAndKeywordVariantLinks() throws Exception {
        List<String> materials = shippedMaterials();
        List<String> failures = new ArrayList<>();
        int programs = 0;
        for (String path : materials) {
            CgMaterialShader shader = CgMaterialShaderRegistry.get().getOrCreate(path);
            if (shader.getLastParsed() == null || shader.hasCompileFailed()) shader.recompile();
            if (shader.hasCompileFailed()) {
                failures.add(shader.lastCompileError());
                continue;
            }
            CgParsedShader parsed = shader.getLastParsed();
            CgParsedPass forward = parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
            for (CgParsedPass pass : parsed.passes()) {
                List<Set<String>> variants = pass == forward ? subsets(parsed.featureNames()) : List.of(Set.of());
                for (Set<String> keywords : variants) {
                    programs++;
                    if (shader.getOrCompile(pass.name(), keywords) == null)
                        failures.add(path + " pass " + pass.name() + " " + keywords + ": did not link (see log)");
                }
            }
            CgMaterial material = CgMaterial.newInstance(path);
            if (CgMaterialShader.SHADOWS_SUPPORTED && material.hasShadowCasterPass()
                    && !shader.hasCompiledPass(CgRenderPassVariant.SHADOW.lightModeName()))
                failures.add(path + ": the generated shadow pass did not link (see log)");
            if (parsed.renderQueue() < 3000 && forward != null && !material.hasDepthPass()
                    && !shader.hasCompiledPass(CgRenderPassVariant.DEPTH.lightModeName()))
                failures.add(path + ": the generated depth pass did not link (see log)");
            material.delete();
        }
        assertTrue(String.join(System.lineSeparator(), failures), failures.isEmpty());
        assertTrue("no program was linked: the test proves nothing", programs > materials.size());
    }

    /** Every {@code .shader} directly under {@code assets/crystalgui/shaders/}, read from core's resources. */
    private static List<String> shippedMaterials() throws Exception {
        URI dir = ShippedShadersOnTrackedBackendTest.class.getResource("/assets/crystalgui/shaders/").toURI();
        try (Stream<Path> files = Files.list(Path.of(dir))) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".shader"))
                    .sorted().map(n -> "crystalgui:shaders/" + n).toList();
        }
    }

    private static List<Set<String>> subsets(List<String> names) {
        List<Set<String>> out = new ArrayList<>();
        for (int mask = 0; mask < 1 << names.size(); mask++) {
            Set<String> s = new LinkedHashSet<>();
            for (int i = 0; i < names.size(); i++) if ((mask & 1 << i) != 0) s.add(names.get(i));
            out.add(s);
        }
        return out;
    }
}
