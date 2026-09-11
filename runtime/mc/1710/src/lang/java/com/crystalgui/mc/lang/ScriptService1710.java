package com.crystalgui.mc.lang;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.language.map.ReadableView;
import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.MappingCoordinates.Source;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptService;

import cpw.mods.fml.common.FMLCommonHandler;

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
     * MCP, matching {@code runtime/mc/1710/gradle.properties} — {@code channel = stable},
     * {@code mappingsVersion = 12}.
     *
     * <p>Stated here rather than read from the environment, and that is load-bearing: a version
     * discovered at runtime is a version that can differ between development and production, which is
     * the one thing this whole phase exists to prevent. Where the two CSVs are fetched from, and what
     * they must hash to, is {@code download/locations.json}.</p>
     */
    private static final MappingCoordinates MCP_STABLE_12 = MappingCoordinates.of("1.7.10", "stable", "12")
            // The two files that carry members. `params.csv` is not fetched: parameter names exist in
            // bytecode only as debug metadata and are never resolved against.
            .readable("methods.csv", Source.located("mcp/stable-12/methods.csv"), null)
            .readable("fields.csv", Source.located("mcp/stable-12/fields.csv"), null);

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
     * A job on a client, a daemon thread on a server — and the side has to be asked.
     *
     * <p>{@code JobScheduler} is drained by {@code UIDocument.frame} and by nothing else, so a job
     * submitted on a dedicated server is never run. This service is deliberately side-agnostic (its
     * other four members are installation-level facts), which makes this the one member that has to
     * know: a client gets a progress bar and a cancel, a server gets the default thread.</p>
     *
     * <p>Submitting on both is not a hypothetical mistake — it is what the mod's {@code init} did, and
     * {@code init} fires on both sides. An obfuscated server with no cached mapping waited for one for
     * ever, and nothing said why.</p>
     */
    @Override
    public void runInBackground(String title, BackgroundWork work) {
        if (!FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            ScriptService.super.runInBackground(title, work);
            return;
        }
        JobScheduler.shared().job(JobKey.of(ScriptService1710.class, title), JobLane.BACKGROUND,
                context -> {
                    work.run(context.progress(), context::isCancelled);
                    return null;
                }).submit();
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
