package com.crystalgui.language.platform;

import org.junit.After;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The SPI's contract, which is mostly about what happens when there is <b>no</b> platform.
 *
 * <p>Off a Minecraft host — the harness, every other test, a dedicated server — nothing registers one,
 * and that path has to be as real as the registered one. A null slipping out of here would be found by
 * whichever caller forgot to check, which is the failure this pins against.</p>
 */
public class ScriptPlatformTest {

    @After
    public void restoreDefault() {
        ScriptPlatforms.reset();
    }

    @Test
    public void withNothingRegisteredTheCurrentPlatformIsNoneRatherThanNull() {
        assertSame(ScriptPlatform.NONE, ScriptPlatforms.current());
    }

    /** Every member of NONE answers, so no caller has to special-case the absent platform. */
    @Test
    public void noneAnswersEveryMember() {
        ScriptPlatform none = ScriptPlatform.NONE;
        assertNotNull("liveBytes", none.liveBytes());
        assertNotNull("cacheRoot", none.cacheRoot());
        assertTrue("mappings should be NONE", none.mappings().isNone());
        assertTrue("probe should be NONE", none.namespaceProbe().isNone());
    }

    /** NONE reads the classloader, which is what makes it work in a plain JVM. */
    @Test
    public void nonesByteSourceReadsItsOwnClasses() throws Exception {
        byte[] bytes = ScriptPlatform.NONE.liveBytes()
                .bytesOf("com/crystalgui/language/platform/ScriptPlatform");
        assertNotNull("NONE could not read a class it plainly ships", bytes);
        assertTrue("not a class file", bytes.length > 4
                && (bytes[0] & 0xFF) == 0xCA && (bytes[1] & 0xFF) == 0xFE);
    }

    @Test
    public void registeringNullFallsBackRatherThanStoringIt() {
        ScriptPlatforms.register(null);
        assertSame(ScriptPlatform.NONE, ScriptPlatforms.current());
    }

    @Test
    public void aRegisteredPlatformIsWhatCurrentAnswers() {
        ScriptPlatform fake = fakePlatform(Paths.get("somewhere"));
        ScriptPlatforms.register(fake);
        assertSame(fake, ScriptPlatforms.current());
    }

    /**
     * Coordinates are version-addressed, which is what removes cache invalidation entirely.
     *
     * <p>Two artifacts must never share a directory: an upgrade is then a miss rather than something
     * that has to be detected and cleared.</p>
     */
    @Test
    public void coordinatesCacheKeyIsTheVersion() {
        MappingCoordinates twelve = MappingCoordinates.of("1.7.10", "stable", "12", "https://x/");
        MappingCoordinates thirteen = MappingCoordinates.of("1.7.10", "stable", "13", "https://x/");
        assertEquals("stable-12", twelve.cacheKey());
        assertFalse("two versions must not share a cache directory",
                twelve.cacheKey().equals(thirteen.cacheKey()));
    }

    /** A base URL is usable whether or not the caller remembered the trailing slash. */
    @Test
    public void coordinatesJoinUrlsWithoutDoubledSlashes() {
        assertEquals("https://x/conf/methods.csv",
                MappingCoordinates.of("1.7.10", "stable", "12", "https://x/conf").urlOf("methods.csv"));
        assertEquals("https://x/conf/methods.csv",
                MappingCoordinates.of("1.7.10", "stable", "12", "https://x/conf/").urlOf("methods.csv"));
    }

    /** Digests are optional per file, so a platform can be brought up before they are known. */
    @Test
    public void anUnpinnedDigestIsNullRatherThanEmpty() {
        MappingCoordinates coordinates = MappingCoordinates.of("1.7.10", "stable", "12", "https://x/")
                .withDigest("methods.csv", "ABCDEF");
        assertEquals("abcdef", coordinates.digestOf("methods.csv"));
        assertNull(coordinates.digestOf("fields.csv"));
    }

    private static ScriptPlatform fakePlatform(final Path cacheRoot) {
        return new ScriptPlatform() {
            @Override
            public com.crystalgui.language.map.ReadableView.ByteSource liveBytes() {
                return ScriptPlatform.NONE.liveBytes();
            }

            @Override
            public Path cacheRoot() {
                return cacheRoot;
            }

            @Override
            public MappingCoordinates mappings() {
                return MappingCoordinates.NONE;
            }

            @Override
            public NamespaceProbe namespaceProbe() {
                return NamespaceProbe.NONE;
            }
        };
    }
}
