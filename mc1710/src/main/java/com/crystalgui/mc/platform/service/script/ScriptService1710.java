package com.crystalgui.mc.platform.service.script;

import com.crystalgui.language.map.ReadableView;
import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptService;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.nio.file.Path;

/**
 * What Minecraft 1.7.10 contributes to the language stack: a byte route, a path, and two data objects.
 *
 * <p><b>There is deliberately no logic here.</b> Fetching, verifying, caching, parsing, remapping,
 * namespace detection and compilation all live in {@code language/}, once — this class exists so a
 * second loader is an implementation of {@link ScriptService} rather than a second copy of that work.
 * If anything in here grows past "state a fact about this platform", it belongs behind the interface
 * instead.</p>
 */
public final class ScriptService1710 implements ScriptService {

    /**
     * MCP, matching {@code mc1710/gradle.properties} — {@code channel = stable},
     * {@code mappingsVersion = 12}, {@code remoteMappings = …/FML/1.7.10/conf/}.
     *
     * <p>Stated here rather than read from the environment, and that is load-bearing: a version
     * discovered at runtime is a version that can differ between development and production, which is
     * the one thing this whole phase exists to prevent. The mod and the build name the same artifact.</p>
     */
    private static final MappingCoordinates MCP_STABLE_12 = MappingCoordinates
            .of("1.7.10", "stable", "12",
                    "https://raw.githubusercontent.com/MinecraftForge/FML/1.7.10/conf/")
            // The two files that carry members. `params.csv` is deliberately not among them: its names
            // are parameter names, which exist in bytecode only as debug metadata and are never resolved
            // against, so downloading it would cost 40 KB to change nothing a script can observe.
            //
            // NO DIGESTS PINNED YET, which is the one thing here that is provisional rather than decided.
            // Upstream publishes no .md5 beside these two, so pinning means recording a hash of what was
            // fetched -- a real check against a mirror serving something else, and one that has to be
            // taken from a trusted fetch rather than invented. Until then a corrupted download is caught
            // by the parse rather than by the digest, which is weaker and is stated so it is not mistaken
            // for a decision.
            .withFile("methods.csv")
            .withFile("fields.csv");

    /**
     * {@code World#getBlock} is {@code func_147439_a} in production.
     *
     * <p>A development client runs Minecraft recompiled at MCP names, so the readable spelling is
     * genuinely what the class declares there. Asking the class is what makes the choice observable
     * instead of configured.</p>
     */
    private static final NamespaceProbe PROBE =
            NamespaceProbe.declaring("net/minecraft/world/World", "getBlock");

    @Override
    public ReadableView.ByteSource liveBytes() {
        return LaunchWrapperBytes.SOURCE;
    }

    /**
     * Notch → SRG, so the type index can offer a class the classpath stores under another name.
     *
     * <p>The whole of the 1.7.10 answer, because LaunchWrapper already owns the translation — the same
     * {@code IClassNameTransformer} {@link LaunchWrapperBytes} uses to ask for bytes. Identity in a
     * development client.</p>
     */
    @Override
    public String runtimeClassName(String onDiskInternalName) {
        return LaunchWrapperBytes.runtimeName(onDiskInternalName);
    }

    /**
     * {@code .minecraft/config/crystalgui} — beside the config, never inside the workspace.
     *
     * <p>The rule the session record, the trash and the engine bands all already follow: derived and
     * private state must not become part of a project a resource pack could ship.</p>
     */
    @Override
    public Path cacheRoot() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/crystalgui").toPath();
    }

    @Override
    public MappingCoordinates mappings() {
        return MCP_STABLE_12;
    }

    @Override
    public NamespaceProbe namespaceProbe() {
        return PROBE;
    }
}
