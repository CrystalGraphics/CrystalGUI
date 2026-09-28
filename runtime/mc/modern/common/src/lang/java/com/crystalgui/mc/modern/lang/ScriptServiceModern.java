package com.crystalgui.mc.modern.lang;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.MappingCoordinates.Source;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.map.ReadableView;
import com.crystalgui.mc.shared.modern.MinecraftBytes;
import com.crystalgui.mc.shared.modern.MojangMappings;

//? if >=1.14 {
import net.minecraft.SharedConstants;
//?}
import net.minecraft.client.Minecraft;

/**
 * The 1.20.x half of {@link ScriptService} — <b>where to put things, and nothing else</b>.
 *
 * <pre>{@code
 * LanguageLifecycle.bootstrapClient(ScriptServiceModern::forThisClient);   // from each loader's language entry
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
 * that will run the class — see {@link MinecraftBytes} for why that is namespace-correct here and
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
public final class ScriptServiceModern implements ScriptService {

    private final Path gameDirectory;

    private ScriptServiceModern(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    /** This client's service, or null before a client is up. */
    @Nullable
    public static ScriptServiceModern forThisClient() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.gameDirectory == null ? null : new ScriptServiceModern(mc.gameDirectory.toPath());
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
        return MinecraftBytes.SOURCE;
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
        JobScheduler.shared().job(JobKey.of(ScriptService.class, title), JobLane.BACKGROUND,
                context -> {
                    work.run(context.progress(), context::isCancelled);
                    return null;
                }).submit();
    }

    /**
     * A class only a Fabric runtime has, and the question that separates the three loaders.
     *
     * <p>Fabric renames classes as well as members, so {@code Level} is not there at all under that
     * name; NeoForge and Forge from 1.17 keep official class names and differ only in their members, and
     * Forge before 1.17 has MCP ones ({@link #MCP_LEVEL}). Reading for
     * this one class is therefore the whole of the detection, and it is a read rather than a loader
     * check because the namespace is what actually matters — a future loader that ships intermediary
     * gets the right answer without being named here.</p>
     */
    private static final String FABRIC_LEVEL = "net/minecraft/class_1937";

    /** The official spelling of the same class: NeoForge, Forge from 1.17, and every dev run. */
    private static final String OFFICIAL_LEVEL = "net/minecraft/world/level/Level";

    /** Whether this runtime speaks intermediary — asked once, since it cannot change. */
    private static Boolean intermediary;

    private static synchronized boolean isIntermediary() {
        if (intermediary == null) {
            byte[] bytes;
            try {
                bytes = MinecraftBytes.SOURCE.bytesOf(FABRIC_LEVEL);
            } catch (IOException | LinkageError unreadable) {
                bytes = null;
            }
            intermediary = bytes != null;
        }
        return intermediary;
    }

    /** The Minecraft version this runtime IS, which is the only one its mappings may be fetched for. */
    private static String minecraftVersion() {
        // 1.21.6 renamed WorldVersion's accessors; 1.13 has none, and its node runs 1.13.2 alone.
        //? if >=1.21.6 {
        /*return SharedConstants.getCurrentVersion().name();
        *///?} elif >=1.14 {
        return SharedConstants.getCurrentVersion().getName();
        //?} else {
        /*return "1.13.2";
        *///?}
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
                    .readable("client.txt", MojangMappings.clientMappings(version), null)
                    .runtime("mappings.tiny", Source.located("fabric/intermediary/" + version),
                            "mappings/mappings.tiny");
        }
        //? if <1.14 {
        /*// Mojang published no mappings before 1.14.4, so there is no readable half to join.
        return MappingCoordinates.NONE;
        *///?} else {
        MappingCoordinates srg = MappingCoordinates.of(version, "srg", version)
                .readable("client.txt", MojangMappings.clientMappings(version), null)
                .runtime("joined.tsrg", Source.located("forge/mcp-config/" + version), "config/joined.tsrg");
        // Official CLASS names with SRG MEMBERS -- what Forge has run since 1.17. MCPConfig's own class
        // vocabulary is then `net/minecraft/src/C_NNNN_`, which no runtime speaks. Before 1.17 its classes
        // are the MCP names Forge ran, and are kept.
        return officialClassNames() ? srg.runtimeKeepsReadableClassNames() : srg;
        //?}
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
        return NamespaceProbe.declaring(isIntermediary() ? FABRIC_LEVEL
                : officialClassNames() ? OFFICIAL_LEVEL : MCP_LEVEL, "getBlockState");
    }

    /**
     * {@code Level} as Forge named it before 1.17: MCP class names, SRG members. A probe of
     * {@link #OFFICIAL_LEVEL} found no class there and took the runtime for readable, so no mapping was
     * fetched and no Minecraft member completed.
     */
    private static final String MCP_LEVEL = "net/minecraft/world/World";

    /** Whether this runtime has official class names -- NeoForge, Forge from 1.17, and every dev run. */
    private static Boolean officialClassNames;

    private static synchronized boolean officialClassNames() {
        if (officialClassNames == null) {
            byte[] bytes;
            try {
                bytes = MinecraftBytes.SOURCE.bytesOf(OFFICIAL_LEVEL);
            } catch (IOException | LinkageError unreadable) {
                bytes = null;
            }
            officialClassNames = bytes != null;
        }
        return officialClassNames;
    }

    /**
     * The runtime's own spelling of a class — identity except on Fabric and Forge before 1.17, whose
     * class names are not Mojang's.
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
        return "ScriptServiceModern[cache=" + cacheRoot() + "]";
    }
}
