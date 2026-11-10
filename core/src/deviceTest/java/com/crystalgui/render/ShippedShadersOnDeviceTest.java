package com.crystalgui.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.vulkan.shader.ExplicitBindingCheck;
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
 * Every shader CrystalGUI ships, compiled by shaderc into a pipeline on CrystalGraphics' null device, in every pass and
 * keyword variant: as a Vulkan device will run them. CrystalGraphics' own shaders are its {@code DevicePipelinesTest}'s.
 */
public class ShippedShadersOnDeviceTest {

    private static ShadercGlslCompiler compiler, bare;

    @BeforeClass
    public static void install() {
        compiler = new ShadercGlslCompiler();
        bare = new ShadercGlslCompiler(null, false, false);   // Vulkan's own rules, nothing auto-bound
        new CgNullRHI(64, 64).install(new ExplicitBindingCheck(compiler, bare));
        CgCapabilities.clearCache();
        // CgEngineLifecycle.ensureGraphicsContext's own steps, less the platform it would ask for a device.
        CgBindingPoints.init(CgCapabilities.detect());
        CgFallbackTextures.init();
    }

    @AfterClass
    public static void uninstall() {
        compiler.close();
        bare.close();
    }

    @Test
    public void everyPassAndKeywordVariantBuilds() throws Exception {
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
                        failures.add(path + " pass " + pass.name() + " " + keywords + ": did not build (see log)");
                }
            }
            CgMaterial material = CgMaterial.newInstance(path);
            if (parsed.renderQueue() < 3000 && forward != null && !material.hasDepthPass()
                    && !shader.hasCompiledPass(CgRenderPassVariant.DEPTH.lightModeName()))
                failures.add(path + ": the generated depth pass did not build (see log)");
            material.delete();
        }
        assertTrue(String.join(System.lineSeparator(), failures), failures.isEmpty());
        assertTrue("no program was built: the test proves nothing", !materials.isEmpty() && programs >= materials.size());
    }

    /** Every {@code .shader} directly under {@code assets/crystalgui/shaders/}, read from core's resources. */
    private static List<String> shippedMaterials() throws Exception {
        URI dir = ShippedShadersOnDeviceTest.class.getResource("/assets/crystalgui/shaders/").toURI();
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
