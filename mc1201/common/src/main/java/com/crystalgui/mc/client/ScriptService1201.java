package com.crystalgui.mc.client;

import java.io.IOException;
import java.nio.file.Path;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.map.ReadableView;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

/**
 * The 1.20.x half of {@link ScriptService} — <b>where to put things, and nothing else</b>.
 *
 * <pre>{@code
 * ScriptService1201.install();   // from Lifecycle1201.bootstrapClient()
 * }</pre>
 *
 * <p>Registered for {@link #cacheRoot()} alone, and that is the whole reason it exists.
 * {@code EngineHost.defaultSource()} tries a configured directory, then the band bundled in the jar,
 * then a download — and <b>the second and third both begin by asking this service where they may
 * write</b>. With none registered the answer is {@code ScriptService.NONE}'s
 * {@code build/crystalgui-cache}, resolved against the process working directory, which on a client is
 * a {@code build/} folder inside the player's game directory. It works, and it is the wrong place.</p>
 *
 * <p><b>What it answers, and how each answer is arrived at.</b> {@code liveBytes()} reads the loader
 * that will run the class — see {@link MinecraftBytes1201} for why that is namespace-correct here and
 * was not on 1.7.10. The mapping and the probe are chosen by <b>asking the runtime which namespace it
 * is in</b>, never by naming a loader: the three loaders disagree, and a flag someone sets is a flag
 * that will be wrong in exactly the environment nobody tests. {@code plan/platform-mc1201.md} §3.7.</p>
 *
 * <table>
 *   <caption>What each runtime turns out to be</caption>
 *   <tr><th>Runtime</th><th>Classes</th><th>Members</th><th>Mapping</th></tr>
 *   <tr><td>any dev run</td><td>official</td><td>official</td><td>none — the probe says so</td></tr>
 *   <tr><td>NeoForge</td><td>official</td><td>official</td><td>none — the probe says so</td></tr>
 *   <tr><td>Forge</td><td>official</td><td>SRG</td><td>SRG → official</td></tr>
 *   <tr><td>Fabric</td><td>intermediary</td><td>intermediary</td><td>intermediary → official</td></tr>
 * </table>
 */
public final class ScriptService1201 implements ScriptService {

    private final Path gameDirectory;

    private ScriptService1201(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    /** Registers one, if a client is up. Idempotent. */
    public static void install() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return;
        CgPlatform.provide(ScriptServices.SERVICE, new ScriptService1201(mc.gameDirectory.toPath()));
    }

    /**
     * {@code crystalgui/cache/}, beside everything else this engine writes.
     *
     * <p>Under the engine's own root rather than a directory of its own: {@code StorageLayout} states
     * that tree once and nothing outside it may spell those segments.</p>
     */
    @Override
    public Path cacheRoot() {
        return StorageLayout.cacheIn(gameDirectory).toAbsolutePath().normalize();
    }

    @Override
    public ReadableView.ByteSource liveBytes() {
        return MinecraftBytes1201.SOURCE;
    }

    /**
     * Into a job, so a first-launch download reports into the status bar and can be cancelled.
     *
     * <p>A drained scheduler can be claimed here because {@link #install()} is called from the CLIENT
     * bootstrap alone — {@code JobScheduler} is drained by {@code UIDocument.frame}, and a dedicated
     * server has no window. A server registering this service would have to answer differently.</p>
     */
    @Override
    public void runInBackground(String title, BackgroundWork work) {
        JobScheduler.shared().job(JobKey.of(ScriptService1201.class, title), JobLane.BACKGROUND,
                context -> {
                    work.run(context.progress(), context::isCancelled);
                    return null;
                }).submit();
    }

    /**
     * A class only a Fabric runtime has, and the question that separates the three loaders.
     *
     * <p>Fabric renames classes as well as members, so {@code Level} is not there at all under that
     * name; Forge and NeoForge keep official class names and differ only in their members. Reading for
     * this one class is therefore the whole of the detection, and it is a read rather than a loader
     * check because the namespace is what actually matters — a future loader that ships intermediary
     * gets the right answer without being named here.</p>
     */
    private static final String FABRIC_LEVEL = "net/minecraft/class_1937";

    /** The official spelling of the same class, which every other runtime has. */
    private static final String OFFICIAL_LEVEL = "net/minecraft/world/level/Level";

    /** Whether this runtime speaks intermediary — asked once, since it cannot change. */
    private static Boolean intermediary;

    private static synchronized boolean isIntermediary() {
        if (intermediary == null) {
            byte[] bytes;
            try {
                bytes = MinecraftBytes1201.SOURCE.bytesOf(FABRIC_LEVEL);
            } catch (IOException | LinkageError unreadable) {
                bytes = null;
            }
            intermediary = bytes != null;
        }
        return intermediary;
    }

    /** The Minecraft version this runtime IS, which is the only one its mappings may be fetched for. */
    private static String minecraftVersion() {
        return SharedConstants.getCurrentVersion().getName();
    }

    /**
     * The join this runtime needs, in the namespace it turned out to be in.
     *
     * <p>Every 1.20.x mapping is a join: no published artifact maps out of a namespace a runtime speaks.
     * Mojang's {@code client.txt} is obf→official and is the readable half for both; the runtime half is
     * MCPConfig's TSRG2 or Fabric's Tiny, each inside an archive. @see MappingSet#then</p>
     *
     * <p>Cheap to call — the addresses that need looking up are resolved when they are fetched, and a
     * runtime that already speaks official names never gets that far because the probe stops first.</p>
     */
    @Override
    public MappingCoordinates mappings() {
        String version = minecraftVersion();
        if (isIntermediary()) {
            return MappingCoordinates.of(version, "intermediary", version)
                    .readable("client.txt", MojangMappings1201.clientMappings(version), null)
                    .runtime("mappings.tiny",
                            "https://maven.fabricmc.net/net/fabricmc/intermediary/"
                                    + version + "/intermediary-" + version + "-v2.jar",
                            null, "mappings/mappings.tiny");
        }
        return MappingCoordinates.of(version, "srg", version)
                .readable("client.txt", MojangMappings1201.clientMappings(version), null)
                .runtime("joined.tsrg",
                        "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/"
                                + version + "/mcp_config-" + version + ".zip",
                        null, "config/joined.tsrg")
                // Official CLASS names with SRG MEMBERS -- what Forge has run since 1.17. MCPConfig's
                // own class vocabulary is `net/minecraft/src/C_NNNN_` and no runtime speaks it.
                .runtimeKeepsReadableClassNames();
    }

    /**
     * Whether this runtime already speaks readable names, asked of the runtime.
     *
     * <p>{@code getBlockState} is what the class declares under official names and is {@code m_8055_} on
     * Forge and {@code method_8320} on Fabric — so one class read separates a dev run and NeoForge, which
     * need no mapping at all, from the two that do.</p>
     */
    @Override
    public NamespaceProbe namespaceProbe() {
        return NamespaceProbe.declaring(
                isIntermediary() ? FABRIC_LEVEL : OFFICIAL_LEVEL, "getBlockState");
    }

    /**
     * The runtime's own spelling of a class — identity everywhere except Fabric, which renames classes.
     *
     * <p>Read from the resolved mapping rather than decided here, so the two cannot disagree: whatever
     * the join produced is what the type index looks a class up by.</p>
     */
    @Override
    public String runtimeClassName(String onDiskInternalName) {
        return PlatformMappings.current().runtimeClass(onDiskInternalName);
    }

    @Override
    public String toString() {
        return "ScriptService1201[cache=" + cacheRoot() + "]";
    }
}
