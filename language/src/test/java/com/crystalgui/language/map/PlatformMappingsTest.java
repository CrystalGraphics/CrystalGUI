package com.crystalgui.language.map;

import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptPlatform;
import com.crystalgui.language.platform.ScriptPlatforms;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>Dev probes readable; a fixture probes obfuscated.</b>
 *
 * <p>This is §26.7's whole claim: which namespace a runtime speaks is <em>observed</em>, and the two
 * environments are told apart without anybody configuring anything. Both directions are asserted,
 * because a probe that always answered "readable" would pass in dev — where the answer is right — and
 * ship a client that never translates a name.</p>
 *
 * <p>The probe reads through {@link ScriptPlatform#liveBytes()}, so a fake platform serving synthesized
 * class files exercises exactly the path a real one takes.</p>
 */
public class PlatformMappingsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final String WORLD = "net/minecraft/world/World";

    @After
    public void forget() {
        ScriptPlatforms.reset();
        PlatformMappings.resetForTesting();
    }

    /** A class file declaring one method of the given name — the only thing the probe reads. */
    private static byte[] classDeclaring(String member) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, WORLD, null,
                "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC, member, "()V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A platform whose runtime declares {@code member} and whose mappings live in {@code upstream}. */
    private void register(String member, Path cacheRoot, MappingCoordinates coordinates) {
        ScriptPlatforms.register(new ScriptPlatform() {
            @Override
            public com.crystalgui.language.map.ReadableView.ByteSource liveBytes() {
                return name -> WORLD.equals(name) ? classDeclaring(member) : null;
            }

            @Override
            public Path cacheRoot() {
                return cacheRoot;
            }

            @Override
            public MappingCoordinates mappings() {
                return coordinates;
            }

            @Override
            public NamespaceProbe namespaceProbe() {
                return NamespaceProbe.declaring(WORLD, "getBlock");
            }
        });
    }

    private MappingCoordinates upstream() throws IOException {
        Path directory = folder.newFolder("upstream").toPath();
        Files.write(directory.resolve("methods.csv"),
                "searge,name,side,desc\nfunc_147439_a,getBlock,0,\n".getBytes(StandardCharsets.UTF_8));
        return MappingCoordinates.of("1.7.10", "stable", "12", directory.toUri().toString())
                .withFile("methods.csv");
    }

    /**
     * A runtime that already declares the readable member needs no mapping, and must not fetch one.
     *
     * <p>The second half is the one worth asserting: translating through a mapping on a runtime that
     * never needed it would rename correct names into SRG ones, which is worse than doing nothing.</p>
     */
    @Test
    public void aReadableRuntimeStaysTheIdentity() throws IOException {
        Path cache = folder.newFolder("cache").toPath();
        register("getBlock", cache, upstream());

        assertTrue("a dev runtime must not be translated", PlatformMappings.current().isIdentity());
        assertFalse("a readable runtime fetched mappings it does not need",
                Files.exists(cache.resolve("mappings")));
    }

    /** An obfuscated runtime acquires the mapping and translates through it. */
    @Test
    public void anObfuscatedRuntimeResolvesAMapping() throws Exception {
        Path cache = folder.newFolder("cache").toPath();
        register("func_147439_a", cache, upstream());

        MappingSet mappings = awaitNonIdentity();
        assertEquals("getBlock", mappings.readableMethod(WORLD, "func_147439_a"));
    }

    /**
     * <b>An unreadable probe is treated as readable, not as obfuscated.</b>
     *
     * <p>"I could not tell" and "it is obfuscated" call for different behaviour, and guessing obfuscated
     * is the costly guess: it downloads a mapping and translates every name through it on a runtime that
     * may not need one. Doing nothing is recoverable; renaming everything is not.</p>
     */
    @Test
    public void anUnreadableProbeDoesNotFetch() throws IOException {
        Path cache = folder.newFolder("cache").toPath();
        ScriptPlatforms.register(new ScriptPlatform() {
            @Override
            public com.crystalgui.language.map.ReadableView.ByteSource liveBytes() {
                return name -> null;
            }

            @Override
            public Path cacheRoot() {
                return cache;
            }

            @Override
            public MappingCoordinates mappings() {
                return MappingCoordinates.of("1.7.10", "stable", "12", "file:///nowhere/")
                        .withFile("methods.csv");
            }

            @Override
            public NamespaceProbe namespaceProbe() {
                return NamespaceProbe.declaring(WORLD, "getBlock");
            }
        });

        assertTrue(PlatformMappings.current().isIdentity());
        assertFalse(Files.exists(cache.resolve("mappings")));
    }

    /**
     * The answer may arrive on a background thread, so a test has to wait for it rather than read once.
     *
     * <p>Bounded, and it fails by assertion rather than by hanging: a resolution that never completes is
     * the bug, and a test that blocks forever hides it behind a timeout somewhere else.</p>
     */
    private MappingSet awaitNonIdentity() throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            MappingSet mappings = PlatformMappings.current();
            if (!mappings.isIdentity()) return mappings;
            Thread.sleep(25);
        }
        throw new AssertionError("the mapping never resolved");
    }
}
