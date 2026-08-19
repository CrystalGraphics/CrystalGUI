package com.crystalgui.language.run;

import com.crystalgui.language.run.exec.ScriptCache;
import com.crystalgui.language.run.exec.ScriptCacheKey;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The compiled-script cache and its key — §15.5 D.3.
 *
 * <p>The key's three components each stand for a real invalidation, and the tests are one per
 * component because getting any of them wrong produces a cache that serves bytecode which cannot run.</p>
 */
public class ScriptCacheTest {

    private static Map<String, byte[]> classes(String name, int marker) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(name, new byte[]{(byte) marker, 2, 3});
        return classes;
    }

    // ── The key ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void identicalSourceMappingsAndBandIsTheSameKey() {
        assertEquals(ScriptCacheKey.of("int x = 1;", "identity", 17),
                ScriptCacheKey.of("int x = 1;", "identity", 17));
    }

    @Test
    public void changedSourceIsADifferentKey() {
        assertNotEquals(ScriptCacheKey.of("int x = 1;", "identity", 17),
                ScriptCacheKey.of("int x = 2;", "identity", 17));
    }

    @Test
    public void changedMappingsIsADifferentKey() {
        // Two environments of one Minecraft version disagree here -- a dev launch is identity,
        // production is not -- and a cache that ignored it would hand production a dev-compiled script
        // whose every MC call links to nothing.
        assertNotEquals(ScriptCacheKey.of("int x = 1;", "identity", 17),
                ScriptCacheKey.of("int x = 1;", "mcp-1.7.10", 17));
    }

    @Test
    public void changedBandIsADifferentKey() {
        // A script compiled at 17 does not load on 8, and the failure is an
        // UnsupportedClassVersionError naming a number rather than a script.
        assertNotEquals(ScriptCacheKey.of("int x = 1;", "identity", 17),
                ScriptCacheKey.of("int x = 1;", "identity", 8));
    }

    @Test
    public void theFileNameCarriesEveryComponent() {
        // So two entries differing in any component cannot collide on disk -- and a human looking at
        // the directory can tell which is which, which matters the first time somebody has to work out
        // why a cache is not hitting.
        String identity = ScriptCacheKey.of("int x = 1;", "identity", 17).fileName();
        String mapped = ScriptCacheKey.of("int x = 1;", "mcp-1.7.10", 17).fileName();
        String otherBand = ScriptCacheKey.of("int x = 1;", "identity", 8).fileName();

        assertNotEquals(identity, mapped);
        assertNotEquals(identity, otherBand);
        assertTrue(identity, identity.endsWith("-17"));
        assertTrue("a mapping id with a dot in it must be made filesystem-safe",
                mapped.contains("mcp_1_7_10"));
    }

    // ── In memory ───────────────────────────────────────────────────────────────────────────────

    @Test
    public void anInMemoryCacheStoresAndReturns() {
        ScriptCache cache = ScriptCache.inMemory();
        ScriptCacheKey key = ScriptCacheKey.of("int x = 1;", "identity", 17);
        assertNull(cache.get(key));

        cache.put(key, classes("Script", 9));
        assertArrayEquals(new byte[]{9, 2, 3}, cache.get(key).get("Script"));
    }

    @Test
    public void anInMemoryCacheHandsOutACopy() {
        // The caller goes on to remap and instrument these bytes. Nothing stops a future implementation
        // of either mutating in place, and a cache that handed out its own storage would then serve an
        // instrumented class as if it were the compile output, forever.
        ScriptCache cache = ScriptCache.inMemory();
        ScriptCacheKey key = ScriptCacheKey.of("int x = 1;", "identity", 17);
        cache.put(key, classes("Script", 9));

        Map<String, byte[]> first = cache.get(key);
        first.put("Extra", new byte[]{0});
        first.remove("Script");

        assertTrue("mutating the returned map changed the cache", cache.get(key).containsKey("Script"));
    }

    @Test
    public void theNoneCacheNeverHits() {
        ScriptCacheKey key = ScriptCacheKey.of("int x = 1;", "identity", 17);
        ScriptCache.NONE.put(key, classes("Script", 1));
        assertNull(ScriptCache.NONE.get(key));
    }

    // ── On disk ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aDirectoryCacheSurvivesBeingReopened() throws IOException {
        // The one §15.5 D.3 is actually about: fifty scripts in a world must not mean fifty compiles
        // every launch, which means surviving the process that wrote them.
        Path root = Files.createTempDirectory("cgui-cache");
        try {
            ScriptCacheKey key = ScriptCacheKey.of("int x = 1;", "identity", 17);
            Map<String, byte[]> stored = new LinkedHashMap<>();
            stored.put("Script", new byte[]{1, 2, 3});
            stored.put("Script$Inner", new byte[]{4, 5, 6});
            ScriptCache.directory(root).put(key, stored);

            // A DIFFERENT cache object over the same directory — which is what a relaunch is.
            Map<String, byte[]> read = ScriptCache.directory(root).get(key);
            assertEquals(2, read.size());
            assertArrayEquals(new byte[]{1, 2, 3}, read.get("Script"));
            assertArrayEquals("a nested class name did not survive the round trip",
                    new byte[]{4, 5, 6}, read.get("Script$Inner"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void aDirectoryCacheMissesForAnUnknownKey() throws IOException {
        Path root = Files.createTempDirectory("cgui-cache");
        try {
            assertNull(ScriptCache.directory(root)
                    .get(ScriptCacheKey.of("never compiled", "identity", 17)));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void anUnwritableDirectoryCacheMissesRatherThanThrows() throws IOException {
        // A cache that cannot be written is a cache that misses. Failing the run over it would trade a
        // fast path for a broken one.
        //
        // A directory UNDER A REGULAR FILE, which is unwritable on every OS. The first attempt used a
        // path with a leading space, which is not unwritable -- it is INVALID, and Path.of threw before
        // the cache was reached at all. Unwritable and unrepresentable are different failures, and only
        // one of them is the one under test.
        Path file = Files.createTempFile("cgui-cache-not-a-directory", "");
        try {
            ScriptCache cache = ScriptCache.directory(file.resolve("under-a-file"));
            ScriptCacheKey key = ScriptCacheKey.of("int x = 1;", "identity", 17);
            cache.put(key, classes("Script", 1));
            assertNull(cache.get(key));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void deleteTree(Path root) {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temp directory outliving one test is not worth failing it over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
