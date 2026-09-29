package com.crystalgui.mc.legacy.lang;

import com.crystalgraphics.mc.shared.FmlVersion;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.language.map.ReadableView;
import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.MappingCoordinates.Source;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.mc.launchwrapper.LaunchWrapperBytes;

import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.io.File;
import java.nio.file.Path;

/**
 * What Forge 1.8–1.12.2 contributes to the language stack: a byte route, a path and two data objects.
 * Fetching, parsing, remapping and compiling are {@code language/}'s.
 *
 * <p>The runtime speaks SRG members under MCP class names, as on 1.7.10, so the readable half is MCP's
 * {@code methods.csv} and {@code fields.csv} and there is no runtime file to join.</p>
 *
 * <ul>
 *   <li>The MCP names are the <b>running</b> Minecraft's, not the node's: one node serves a whole SRG
 *       plateau, and a member 1.11.2 added has no name in 1.10.2's set.</li>
 *   <li>Side-agnostic but for {@link #runInBackground}: the game directory is handed in from preInit, which
 *       Forge fires on both sides.</li>
 * </ul>
 */
public final class ScriptServiceLegacy implements ScriptService {

    /**
     * Each stable MCP release from 1.8 to 1.12.2, oldest first: a Minecraft version takes the newest one
     * at or below it. Forge's maven publishes nothing in between.
     */
    private static final String[][] MCP_STABLE = {
            {"1.8", "18-1.8"}, {"1.8.8", "20-1.8.8"}, {"1.8.9", "22-1.8.9"},
            {"1.9", "24-1.9"}, {"1.9.4", "26-1.9.4"}, {"1.10", "29-1.10.2"},
            {"1.11", "32-1.11"}, {"1.12", "39-1.12"},
    };

    /** {@code World#getBlockState}, which production runs as {@code func_180495_p}. */
    private static final NamespaceProbe PROBE = NamespaceProbe.declaring("net/minecraft/world/World", "getBlockState");

    private final File gameDirectory;

    public ScriptServiceLegacy(File gameDirectory) {
        if (gameDirectory == null) throw new IllegalArgumentException("gameDirectory is null");
        this.gameDirectory = gameDirectory;
    }

    @Override
    public ReadableView.ByteSource liveBytes() {
        return LaunchWrapperBytes.SOURCE;
    }

    /** A job on a client, a daemon thread on a server, where nothing drains {@link JobScheduler}. */
    @Override
    public void runInBackground(String title, BackgroundWork work) {
        if (!FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            ScriptService.super.runInBackground(title, work);
            return;
        }
        JobScheduler.shared().job(JobKey.of(ScriptServiceLegacy.class, title), JobLane.BACKGROUND,
                context -> {
                    work.run(context.progress(), context::isCancelled);
                    return null;
                }).submit();
    }

    /** Notch → SRG, through LaunchWrapper's own class-name transformer. */
    @Override
    public String runtimeClassName(String onDiskInternalName) {
        return LaunchWrapperBytes.runtimeName(onDiskInternalName);
    }

    /** {@code crystalgui/cache}: derived, and never inside the workspace. */
    @Override
    public Path cacheRoot() {
        return StorageLayout.cacheIn(gameDirectory.toPath());
    }

    @Override
    public MappingCoordinates mappings() {
        String minecraft = minecraftVersion();
        String mcp = mcpVersion(minecraft);
        Source names = Source.located("forge/mcp-stable/" + mcp);
        return MappingCoordinates.of(minecraft, "stable", mcp)
                // Members only: parameter names are debug metadata and never resolved against.
                .readable("methods.csv", names, "methods.csv")
                .readable("fields.csv", names, "fields.csv");
    }

    @Override
    public NamespaceProbe namespaceProbe() {
        return PROBE;
    }

    /** The running version, read at run time: {@code ForgeVersion.mcVersion} is a constant javac inlines. */
    private static String minecraftVersion() {
        return FmlVersion.of(ForgeVersion.class);
    }

    /** The MCP release for {@code minecraft}: the newest at or below it. */
    private static String mcpVersion(String minecraft) {
        String chosen = MCP_STABLE[0][1];
        for (String[] release : MCP_STABLE) {
            if (compare(release[0], minecraft) <= 0) chosen = release[1];
        }
        return chosen;
    }

    private static int compare(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? Integer.parseInt(left[i]) : 0;
            int r = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    @Override
    public String toString() {
        String minecraft = minecraftVersion();
        return "ScriptServiceLegacy[Minecraft " + minecraft + ", MCP " + mcpVersion(minecraft)
                + ", cache=" + cacheRoot() + "]";
    }
}
