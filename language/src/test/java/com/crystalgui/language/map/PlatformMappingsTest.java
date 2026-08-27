package com.crystalgui.language.map;

import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.language.platform.ScriptServices;

import org.junit.After;
import org.junit.Before;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>Dev probes readable; a fixture probes obfuscated.</b>
 *
 * <p>This is §26.7's whole claim: which namespace a runtime speaks is <em>observed</em>, and the two
 * environments are told apart without anybody configuring anything. Both directions are asserted,
 * because a probe that always answered "readable" would pass in dev — where the answer is right — and
 * ship a client that never translates a name.</p>
 *
 * <p>The probe reads through {@link ScriptService#liveBytes()}, so a fake platform serving synthesized
 * class files exercises exactly the path a real one takes.</p>
 */
public class PlatformMappingsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final String WORLD = "net/minecraft/world/World";

    /**
     * BEFORE as well as after, and the before is the one that was missing.
     *
     * <p>{@code PlatformMappings} resolves once per process by design, and anything that opens a
     * {@code ScriptHost} reads it — so a test class running earlier leaves it resolved to the identity,
     * and this one then registers a platform that is never probed. It passed alone and failed in the
     * suite, which is the signature.</p>
     */
    @Before
    @After
    public void forget() {
        CgPlatform.provide(ScriptServices.SERVICE, null);
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
        CgPlatform.provide(ScriptServices.SERVICE, new ScriptService() {
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

            @Override
            public String runtimeClassName(String onDiskInternalName) {
                return onDiskInternalName;
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

    /**
     * An obfuscated runtime acquires the mapping and translates through it.
     *
     * <p>The cache is filled FIRST, so the resolution this asserts is the synchronous one. That is not
     * avoiding the hard case, it is testing the one that is deterministic: with a complete cache
     * {@code begin()} parses on the calling thread by design, and asserting on the background fetch
     * instead means racing a daemon thread on a machine running the rest of the suite -- which is a test
     * that fails for reasons that have nothing to do with the code. The fetch path has its own coverage
     * in {@link MappingCacheTest}, where nothing is asynchronous.</p>
     */
    @Test
    public void anObfuscatedRuntimeResolvesAMapping() throws Exception {
        Path cache = folder.newFolder("cache").toPath();
        MappingCoordinates coordinates = upstream();
        assertEquals(MappingCache.State.FETCHED, MappingCache.load(coordinates, cache).state());

        register("func_147439_a", cache, coordinates);

        MappingSet mappings = PlatformMappings.current();
        assertFalse("an obfuscated runtime was left on the identity mapping", mappings.isIdentity());
        assertEquals("getBlock", mappings.readableMethod(WORLD, "func_147439_a"));
    }

    /**
     * <b>A CLAIM turns the lazy path off, and only the claimer can turn the mapping on.</b>
     *
     * <h3>The trap, and why it cost a production run</h3>
     *
     * <p>{@link PlatformMappings#claim} exists so a host with a UI can draw a progress bar instead of
     * letting whichever caller happened to ask first spawn a silent daemon thread. The cost is that it
     * marks the work as owned: {@code current()} then stops starting anything, and answers the identity
     * until the claimer follows through. The class javadoc says so — <i>"a claim is a promise to do
     * it"</i> — and this is that sentence as an assertion.</p>
     *
     * <p>{@code mc1710} claimed at mod init and put the whole of the acquisition in a
     * {@code JobScheduler} job, which only starts when something drains the scheduler, which only
     * {@code UIWindow.advanceFrame} does. So the mapping was owed to a frame. On {@code runObfClient} it
     * was never paid: {@code mcp_stable/12} sat complete in the config directory, no branch of
     * {@code decideClaimed} ever ran in any log of any run, every compiled script cached under a key
     * ending {@code -identity-8}, and a script calling {@code Minecraft.getMinecraft()} met a runtime
     * that has only {@code func_71410_x}.</p>
     *
     * <p>The second half is the fix and the reason the two are asserted together: reading an already
     * downloaded mapping is a parse, so a claimer can always discharge it <b>on the thread it claimed
     * on</b>, with no job, no frame and no daemon. Only a genuine download is worth deferring.</p>
     */
    @Test
    public void aClaimIsDischargedOnTheClaimingThread() throws Exception {
        Path cache = folder.newFolder("cache").toPath();
        MappingCoordinates coordinates = upstream();
        assertEquals(MappingCache.State.FETCHED, MappingCache.load(coordinates, cache).state());
        register("func_147439_a", cache, coordinates);

        assertTrue("nothing had claimed yet", PlatformMappings.claim());
        assertTrue("a claim must stop current() doing the work itself -- that is what claiming MEANS,"
                        + " and it is why failing to follow through is permanent",
                PlatformMappings.current().isIdentity());

        // No job, no frame, no daemon thread: the same thread that claimed discharges it.
        assertNull("a complete cache owes no network fetch", PlatformMappings.decideClaimed());

        MappingSet mappings = PlatformMappings.current();
        assertFalse("a claimed acquisition left the runtime on identity names", mappings.isIdentity());
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
        CgPlatform.provide(ScriptServices.SERVICE, new ScriptService() {
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

            @Override
            public String runtimeClassName(String onDiskInternalName) {
                return onDiskInternalName;
            }
        });

        assertTrue(PlatformMappings.current().isIdentity());
        assertFalse(Files.exists(cache.resolve("mappings")));
    }

}
