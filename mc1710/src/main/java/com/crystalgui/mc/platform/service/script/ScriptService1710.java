package com.crystalgui.mc.platform.service.script;

import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.language.map.ReadableView;
import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptService;

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
     * {@code .minecraft} on a client, {@code <serverdir>} on a dedicated server — the installation
     * {@code crystalgui/} sits in.
     *
     * <p>Handed in rather than looked up, which is what makes this class side-agnostic: it is the
     * parent of {@code FMLPreInitializationEvent.getModConfigurationDirectory()}, and that event is
     * the one Forge fires on both sides.</p>
     */
    private final File gameDirectory;

    public ScriptService1710(File gameDirectory) {
        if (gameDirectory == null) throw new IllegalArgumentException("gameDirectory is null");
        this.gameDirectory = gameDirectory;
    }

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
                    // PINNED TO A COMMIT, not to the `1.7.10` BRANCH it named before.
                    //
                    // A branch is a moving reference. This one has not moved since May 2015 and there is
                    // no reason to expect it to, which is exactly the kind of reasoning that is true right
                    // up until it is not -- and if it did move, every client would silently start fetching
                    // different names for the same pinned `stable/12` coordinate. A commit is immutable by
                    // construction: git addresses it by the content it reaches.
                    "https://raw.githubusercontent.com/MinecraftForge/FML/"
                            + "a099592d3d1418245e3af65eda195da244287188/conf/")
            // The two files that carry members. `params.csv` is deliberately not among them: its names
            // are parameter names, which exist in bytecode only as debug metadata and are never resolved
            // against, so downloading it would cost 40 KB to change nothing a script can observe.
            //
            // DIGESTS ARE UPSTREAM'S OWN, and the note here used to say there were none. That was true of
            // `.md5` files beside the CSVs and false of the repository: GIT ADDRESSES EVERY BLOB BY SHA-1,
            // GitHub's API reports it, and it is as pinnable as anything we could compute -- with the
            // advantage of being upstream's number rather than a hash we recorded from one fetch and hoped
            // was of the right bytes. `CacheFiles` reads the `gitblob:` tag.
            //
            // Taken from the contents API at the commit above; `git hash-object <file>` reproduces them.
            .withDigest("methods.csv", "gitblob:4861d2b6c224122c25ba39ac224f4886b6ddd938")
            .withDigest("fields.csv", "gitblob:db4063eab8c1dc742791c59d071be22772ae462c");

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
     * {@code crystalgui/cache} — <b>derived</b>, and never inside the workspace.
     *
     * <p>Two rules meet here. Private state must not become part of a project a resource pack could
     * ship, which is why it is not in the workspace; and compiled classes are rebuildable, which is why
     * it is in {@code cache/} rather than beside the session record. Deleting that whole tree at any
     * moment must lose nothing, and this is one of the things it is promising about.</p>
     *
     * <p><b>This was the one member that made the whole service client-only</b> (Phase 4 A5). It read
     * {@code Minecraft.getMinecraft().mcDataDir} — a class that does not exist in a server jar at all,
     * so a dedicated server could not have a script service — and the other four members were
     * installation-level facts that had no such problem. The fix is not a side branch: Forge's preInit
     * carries the config directory on both sides, and the installation is its parent. No
     * {@code net.minecraft.client} reference survives here, which is what makes "is this loadable
     * server-side" a question with a structural answer rather than one about which method happens to
     * get called.</p>
     */
    @Override
    public Path cacheRoot() {
        return StorageLayout.cacheIn(gameDirectory.toPath());
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
