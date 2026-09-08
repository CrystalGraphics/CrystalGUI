package com.crystalgui.language.map;

import com.crystalgui.language.platform.MappingCoordinates;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Coordinates that are a JOIN, loaded from a cache that is already complete.
 *
 * <p>No network: the files are written where {@code MappingCache} looks, which is the state a second
 * launch is in and the one every 1.20.x client will spend its life in.</p>
 */
public class JoinedCoordinatesTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String CLIENT_TXT = String.join("\n",
            "# (C) Mojang AB",
            "net.minecraft.world.level.Level -> dhg:",
            "    net.minecraft.util.RandomSource random -> c",
            "    12:34:net.minecraft.world.level.block.state.BlockState getBlockState("
                    + "net.minecraft.core.BlockPos) -> a");

    private static final String JOINED_TSRG = String.join("\n",
            "tsrg2 obf srg",
            "dhg net/minecraft/world/level/Level",
            "\ta (Lfx;)Ldkr; m_8055_",
            "\tc Ldsx; f_46441_");

    private static MappingCoordinates forgeShaped() {
        return MappingCoordinates.of("1.20.1", "forge-srg", "47.2.0")
                .readable("client.txt", "https://example.invalid/client.txt", null)
                .runtime("joined.tsrg", "https://example.invalid/mcp_config.zip", null,
                        "config/joined.tsrg");
    }

    /** Writes both halves where {@code MappingCache} will look for them. */
    private Path primedCache() throws IOException {
        Path root = folder.newFolder("cache").toPath();
        Path directory = root.resolve("mappings").resolve("1.20.1").resolve("forge-srg-47.2.0");
        Files.createDirectories(directory);
        Files.write(directory.resolve("client.txt"), CLIENT_TXT.getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("joined.tsrg"), JOINED_TSRG.getBytes(StandardCharsets.UTF_8));
        return root;
    }

    @Test
    public void aRuntimeSideFileIsWhatMakesItAJoin() {
        assertTrue(forgeShaped().isJoined());
        assertEquals(MappingCoordinates.Side.RUNTIME, forgeShaped().sideOf("joined.tsrg"));
        assertEquals(MappingCoordinates.Side.READABLE, forgeShaped().sideOf("client.txt"));
        assertEquals("config/joined.tsrg", forgeShaped().archiveEntryOf("joined.tsrg"));
    }

    /** 1.7.10's coordinates are untouched by any of this. */
    @Test
    public void overlaidCoordinatesAreStillNotAJoin() {
        MappingCoordinates mcp = MappingCoordinates
                .of("1.7.10", "stable", "12", "https://example.invalid/conf/")
                .withDigest("methods.csv", "abc")
                .withDigest("fields.csv", "def");

        assertFalse(mcp.isJoined());
        assertEquals("https://example.invalid/conf/methods.csv", mcp.urlOf("methods.csv"));
    }

    /** A file that states its own URL keeps it, rather than being hung off a shared base. */
    @Test
    public void eachHalfKeepsItsOwnPublisher() {
        assertEquals("https://example.invalid/client.txt", forgeShaped().urlOf("client.txt"));
        assertEquals("https://example.invalid/mcp_config.zip", forgeShaped().urlOf("joined.tsrg"));
    }

    /** The whole point: two cached halves become the mapping a Forge runtime needs. */
    @Test
    public void aCompleteCacheJoinsIntoSrgToOfficial() throws IOException {
        Path root = primedCache();
        MappingCoordinates coordinates = forgeShaped();

        assertTrue("both halves are on disk", MappingCache.isComplete(coordinates, root));
        MappingCache.Result result = MappingCache.load(coordinates, root);

        assertEquals(result.detail(), MappingCache.State.CACHED, result.state());
        MappingSet mappings = result.mappings();
        String level = "net/minecraft/world/level/Level";
        assertEquals("getBlockState", mappings.readableMethod(level, "m_8055_"));
        assertEquals("random", mappings.readableField(level, "f_46441_"));
        assertEquals("classes are already official on Forge", level, mappings.readableClass(level));
        assertEquals("and without an owner, which is what the Remap command asks",
                "getBlockState", mappings.readableMethodAnywhere("m_8055_"));
    }

    /**
     * Half a join is not a mapping.
     *
     * <p>Overlaying the halves instead would put {@code obf} names in front of a script author — the
     * readable half alone maps {@code dhg.a → getBlockState}, and no runtime has a class called
     * {@code dhg}. Identity says "runtime names, as they are", which is a supported state.</p>
     */
    @Test
    public void oneHalfAloneIsNotUsed() throws IOException {
        Path root = folder.newFolder("half").toPath();
        Path directory = root.resolve("mappings").resolve("1.20.1").resolve("forge-srg-47.2.0");
        Files.createDirectories(directory);
        Files.write(directory.resolve("client.txt"), CLIENT_TXT.getBytes(StandardCharsets.UTF_8));

        MappingCoordinates readableOnly = MappingCoordinates.of("1.20.1", "forge-srg", "47.2.0")
                .readable("client.txt", "https://example.invalid/client.txt", null)
                .runtime("joined.tsrg", "https://example.invalid/absent.zip", null, "config/joined.tsrg");

        assertFalse("the runtime half is missing", MappingCache.isComplete(readableOnly, root));
    }
}
