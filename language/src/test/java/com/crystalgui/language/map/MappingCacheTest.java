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
 * Acquiring the mapping data — every state it can end in.
 *
 * <p><b>Served from a {@code file:} URL</b>, so nothing here touches the network. That is not only about
 * speed: a test that reached GitHub would fail on an aeroplane, fail in CI behind a proxy, and pass for
 * the wrong reason the day the upstream layout changed. What is under test is the caching, verifying and
 * repairing — the transport is {@code URL.openConnection}, which is not ours.</p>
 */
public class MappingCacheTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final String METHODS = "searge,name,side,desc\nfunc_147439_a,getBlock,0,\n";

    /** A directory standing in for the upstream, and coordinates pointing at it. */
    private MappingCoordinates upstreamWith(String contents) throws IOException {
        Path upstream = folder.newFolder("upstream").toPath();
        Files.write(upstream.resolve("methods.csv"), contents.getBytes(StandardCharsets.UTF_8));
        return MappingCoordinates.of("1.7.10", "stable", "12", upstream.toUri().toString())
                .withFile("methods.csv");
    }

    @Test
    public void nothingConfiguredIsNotAnError() {
        MappingCache.Result result =
                MappingCache.load(MappingCoordinates.NONE, folder.getRoot().toPath());
        assertEquals(MappingCache.State.NOT_CONFIGURED, result.state());
        assertTrue(result.mappings().isIdentity());
    }

    /**
     * <b>First launch fetches; second launch does not.</b>
     *
     * <p>The two states are reported separately because they are the difference between a one-off cost
     * and a per-launch one, and because a cache that silently re-fetches every time looks identical to a
     * working one until somebody is offline.</p>
     */
    @Test
    public void theFirstLaunchFetchesAndTheSecondIsCached() throws IOException {
        MappingCoordinates coordinates = upstreamWith(METHODS);
        Path cache = folder.newFolder("cache").toPath();

        assertFalse(MappingCache.isComplete(coordinates, cache));
        MappingCache.Result first = MappingCache.load(coordinates, cache);
        assertEquals(first.detail(), MappingCache.State.FETCHED, first.state());
        assertEquals("getBlock", first.mappings().readableMethod("x/Y", "func_147439_a"));

        assertTrue(MappingCache.isComplete(coordinates, cache));
        MappingCache.Result second = MappingCache.load(coordinates, cache);
        assertEquals(MappingCache.State.CACHED, second.state());
        assertEquals("getBlock", second.mappings().readableMethod("x/Y", "func_147439_a"));
    }

    /** Version-addressed: a different version is a different directory, so there is nothing to invalidate. */
    @Test
    public void anotherVersionCachesElsewhere() throws IOException {
        MappingCoordinates twelve = upstreamWith(METHODS);
        Path cache = folder.newFolder("cache").toPath();
        MappingCache.load(twelve, cache);

        assertTrue(Files.isDirectory(cache.resolve("mappings/1.7.10/stable-12")));
        assertFalse("stable-13 must not be able to see stable-12's files",
                Files.exists(cache.resolve("mappings/1.7.10/stable-13")));
    }

    /**
     * <b>A corrupted cache entry is re-fetched, not trusted and not wedged.</b>
     *
     * <p>Both halves matter. Trusting it means the wrong names forever with no symptom but a mapping
     * that does not work; wedging means the only fix is deleting a directory by hand.</p>
     */
    @Test
    public void aCorruptedFileIsReFetched() throws IOException {
        MappingCoordinates coordinates = upstreamWith(METHODS)
                .withDigest("methods.csv", md5Of(METHODS));
        Path cache = folder.newFolder("cache").toPath();
        assertEquals(MappingCache.State.FETCHED, MappingCache.load(coordinates, cache).state());

        Path cached = cache.resolve("mappings/1.7.10/stable-12/methods.csv");
        Files.write(cached, "tampered".getBytes(StandardCharsets.UTF_8));
        assertFalse(MappingCache.isComplete(coordinates, cache));

        MappingCache.Result repaired = MappingCache.load(coordinates, cache);
        assertEquals(repaired.detail(), MappingCache.State.FETCHED, repaired.state());
        assertEquals(METHODS, new String(Files.readAllBytes(cached), StandardCharsets.UTF_8));
    }

    /** A source serving the wrong bytes is refused rather than cached — that is what a pinned digest is for. */
    @Test
    public void bytesThatDoNotMatchThePinnedDigestAreRejected() throws IOException {
        MappingCoordinates coordinates = upstreamWith("searge,name,side,desc\nfunc_1,somethingElse,0,\n")
                .withDigest("methods.csv", md5Of(METHODS));
        Path cache = folder.newFolder("cache").toPath();

        MappingCache.Result result = MappingCache.load(coordinates, cache);
        assertEquals(MappingCache.State.UNAVAILABLE, result.state());
        assertTrue(result.mappings().isIdentity());
        assertFalse("bad bytes were cached",
                Files.exists(cache.resolve("mappings/1.7.10/stable-12/methods.csv")));
    }

    /**
     * <b>Unreachable is a state, not an exception.</b>
     *
     * <p>Being offline on the first launch is an ordinary thing to be. The editor still opens, scripts
     * still run, and the names are the runtime's — so this has to return rather than throw, and the
     * detail has to say which absence it is.</p>
     */
    @Test
    public void anUnreachableSourceDegradesRatherThanThrowing() throws IOException {
        MappingCoordinates coordinates = MappingCoordinates.of("1.7.10", "stable", "12",
                folder.getRoot().toPath().resolve("does-not-exist").toUri().toString())
                .withFile("methods.csv");
        Path cache = folder.newFolder("cache").toPath();

        MappingCache.Result result = MappingCache.load(coordinates, cache);
        assertEquals(MappingCache.State.UNAVAILABLE, result.state());
        assertTrue(result.mappings().isIdentity());
        assertTrue("the detail must name the file that could not be had",
                result.detail().contains("methods.csv"));
    }

    /** Everything downloaded and nothing recognised it — a missing parser, not a missing network. */
    @Test
    public void filesInNoKnownFormatAreReportedAsSuch() throws IOException {
        MappingCoordinates coordinates = upstreamWith("this is not a mapping file at all\n");
        Path cache = folder.newFolder("cache").toPath();

        MappingCache.Result result = MappingCache.load(coordinates, cache);
        assertEquals(MappingCache.State.UNAVAILABLE, result.state());
        assertTrue(result.detail(), result.detail().contains("format"));
    }

    private static String md5Of(String contents) throws IOException {
        Path file = Files.createTempFile("cgui-md5", ".tmp");
        try {
            Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
            return com.crystalgui.language.cache.CacheFiles.digestOf(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
