package com.crystalgui.language.platform;

import com.crystalgui.core.storage.CrystalGuiStorage;
import com.crystalgui.language.map.MojangMappings;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.map.ReadableView;
import com.crystalgui.language.platform.MappingCoordinates.Source;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgGameClasses;
import com.crystalgraphics.platform.service.CgGameDirectory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * What the language stack needs to know about the game it runs in: where a class's live bytes come from, where to
 * cache, which mapping this runtime needs, and what the runtime calls a class. Every answer is worked out from
 * CrystalGraphics' {@link CgGameClasses} and {@link CgGameDirectory}, so no loader registers anything.
 *
 * <pre>{@code
 * if (ScriptService.live()) {
 *     byte[] level = ScriptService.liveBytes().bytesOf("net/minecraft/world/level/Level");
 * }
 * Path cache = ScriptService.cacheRoot();                    // <game>/crystalgui/cache; build/crystalgui-cache with no game
 * MappingCoordinates join = ScriptService.mappings();        // Fabric: intermediary joined to Mojang's names
 * }</pre>
 *
 * <ul>
 *   <li>With no game ({@link #live()} false: the harness, a test) there are no live bytes, no mappings and no probe,
 *       and every name is its own.</li>
 *   <li>The mapping is chosen from the running version and from which namespace the runtime's classes are in, read
 *       off its bytes, never from the loader's name.</li>
 * </ul>
 */
public final class ScriptService {

    // Fabric's intermediary name for Level: present only on a runtime that renames classes as well as members
    private static final String FABRIC_LEVEL = "net/minecraft/class_1937";
    // Level's official name: NeoForge, Forge from 1.17, and every dev run
    private static final String OFFICIAL_LEVEL = "net/minecraft/world/level/Level";
    // Level as MCP named it: Forge before 1.17, legacy Forge and 1.7.10
    private static final String MCP_LEVEL = "net/minecraft/world/World";

    /** 1.7.10's MCP set, matching {@code runtime/mc/1710/gradle.properties}. Parameter names are never resolved. */
    private static final MappingCoordinates MCP_STABLE_12 = MappingCoordinates.of("1.7.10", "stable", "12")
            .readable("methods.csv", Source.located("mcp/stable-12/methods.csv"), null)
            .readable("fields.csv", Source.located("mcp/stable-12/fields.csv"), null);

    /** Each stable MCP release from 1.8 to 1.12.2, oldest first: a version takes the newest at or below it. */
    private static final String[][] LEGACY_MCP_STABLE = {
            {"1.8", "18-1.8"}, {"1.8.8", "20-1.8.8"}, {"1.8.9", "22-1.8.9"},
            {"1.9", "24-1.9"}, {"1.9.4", "26-1.9.4"}, {"1.10", "29-1.10.2"},
            {"1.11", "32-1.11"}, {"1.12", "39-1.12"},
    };

    /** A class's bytes as the game runs it, transformers and mixins included. */
    private static final ReadableView.ByteSource GAME = internalName -> {
        try {
            return CgGameClasses.bytes(internalName);
        } catch (IOException unreadable) {
            return null;
        }
    };

    private ScriptService() {
    }

    /** Whether a game answers: false in the harness and in tests. */
    public static boolean live() {
        return CgGameClasses.present();
    }

    /** Where a class's post-transform bytes come from; {@link ReadableView.ByteSource#NONE} with no game. */
    public static ReadableView.ByteSource liveBytes() {
        return live() ? GAME : ReadableView.ByteSource.NONE;
    }

    /** Where this module downloads and extracts to: the game's {@code crystalgui/cache}, else {@code build/crystalgui-cache}. */
    public static Path cacheRoot() {
        if (!live()) return Paths.get("build", "crystalgui-cache").toAbsolutePath().normalize();
        return CrystalGuiStorage.LAYOUT.cacheIn(CgGameDirectory.game()).toAbsolutePath().normalize();
    }

    /**
     * What the runtime calls a class the classpath stores under another name: LaunchWrapper's Notch-to-SRG rename,
     * then the mapping's (Fabric's intermediary). Identity in a dev run and with no game. For the type index, which
     * scans jar entries.
     */
    public static String runtimeClassName(String onDiskInternalName) {
        return PlatformMappings.current().runtimeClass(CgGameClasses.runtimeName(onDiskInternalName));
    }

    /** The class whose package names the merged jar's running node, or null with no game. */
    @Nullable
    public static Class<?> runningNodeClass() {
        return live() ? CgPlatform.get(CgGameClasses.HOST).getClass() : null;
    }

    /** The mapping this runtime needs, or {@link MappingCoordinates#NONE}. */
    public static MappingCoordinates mappings() {
        String version = CgGameClasses.minecraftVersion();
        if (version.isEmpty()) return MappingCoordinates.NONE;
        if (version.equals("1.7.10")) return MCP_STABLE_12;
        if (compareVersions(version, "1.13") < 0) {
            // SRG members under MCP class names; the names are the running version's, not the node's
            String mcp = legacyMcp(version);
            Source names = Source.located("forge/mcp-stable/" + mcp);
            return MappingCoordinates.of(version, "stable", mcp)
                    .readable("methods.csv", names, "methods.csv")
                    .readable("fields.csv", names, "fields.csv");
        }
        if (has(FABRIC_LEVEL)) {
            return MappingCoordinates.of(version, "intermediary", version)
                    .readable("client.txt", MojangMappings.clientMappings(version), null)
                    .runtime("mappings.tiny", Source.located("fabric/intermediary/" + version), "mappings/mappings.tiny");
        }
        String mcp = modernMcp(version);
        if (mcp != null) {
            // Mojang published nothing before 1.14.4, so MCP's names are the readable ones
            Source names = Source.located("forge/mcp-stable/" + mcp);
            return MappingCoordinates.of(version, "stable", mcp)
                    .readable("methods.csv", names, "methods.csv")
                    .readable("fields.csv", names, "fields.csv");
        }
        MappingCoordinates srg = MappingCoordinates.of(version, "srg", version)
                .readable("client.txt", MojangMappings.clientMappings(version), null)
                .runtime("joined.tsrg", Source.located("forge/mcp-config/" + version), "config/joined.tsrg");
        // Forge from 1.17 runs official class names with SRG members; MCPConfig's own classes
        // (net/minecraft/src/C_NNNN_) are no runtime's
        return has(OFFICIAL_LEVEL) ? srg.runtimeKeepsReadableClassNames() : srg;
    }

    /**
     * How to tell a readable runtime from an obfuscated one, or {@link NamespaceProbe#NONE}: a class and the member
     * it declares under readable names ({@code getBlockState}, which Forge runs as {@code m_8055_} and Fabric as
     * {@code method_8320}).
     */
    public static NamespaceProbe namespaceProbe() {
        String version = CgGameClasses.minecraftVersion();
        if (version.isEmpty()) return NamespaceProbe.NONE;
        if (version.equals("1.7.10")) return NamespaceProbe.declaring(MCP_LEVEL, "getBlock");
        if (compareVersions(version, "1.13") < 0) return NamespaceProbe.declaring(MCP_LEVEL, "getBlockState");
        String level = has(FABRIC_LEVEL) ? FABRIC_LEVEL : has(OFFICIAL_LEVEL) ? OFFICIAL_LEVEL : MCP_LEVEL;
        return NamespaceProbe.declaring(level, "getBlockState");
    }

    private static boolean has(String internalName) {
        try {
            return CgGameClasses.bytes(internalName) != null;
        } catch (IOException | LinkageError unreadable) {
            return false;
        }
    }

    /** MCP's stable release for a modern Forge version Mojang published no mappings for, or null. */
    @Nullable
    private static String modernMcp(String minecraft) {
        switch (minecraft) {
            case "1.13.2": return "47-1.13.2";
            case "1.14.2": return "53-1.14.2";
            case "1.14.3": return "56-1.14.3";
            default: return null;
        }
    }

    private static String legacyMcp(String minecraft) {
        String chosen = LEGACY_MCP_STABLE[0][1];
        for (String[] release : LEGACY_MCP_STABLE) {
            if (compareVersions(release[0], minecraft) <= 0) chosen = release[1];
        }
        return chosen;
    }

    /** Compares dotted Minecraft versions by their numbers; a suffix ({@code -pre1}) is ignored. */
    public static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? leadingNumber(left[i]) : 0;
            int r = i < right.length ? leadingNumber(right[i]) : 0;
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    private static int leadingNumber(String part) {
        int value = 0;
        for (int i = 0; i < part.length() && Character.isDigit(part.charAt(i)); i++) {
            value = value * 10 + (part.charAt(i) - '0');
        }
        return value;
    }
}
