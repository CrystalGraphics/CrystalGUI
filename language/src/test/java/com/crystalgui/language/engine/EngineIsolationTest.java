package com.crystalgui.language.engine;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The engine loader is genuinely isolated and genuinely child-first.
 *
 * <h3>Why this uses the real jars rather than synthesised ones</h3>
 *
 * <p>The property under test is "our pinned Rhino wins over whatever else is around", and the reason it
 * matters is that <b>several mods ship Rhino</b> at versions we do not choose. Two real, differently
 * versioned copies of the same class is exactly the situation, and bands 8 and 11 already carry
 * one each — 1.7.15.1 and 1.9.1. A synthetic pair would prove the mechanism and not the case.</p>
 */
public class EngineIsolationTest {

    private static final String RHINO_CONTEXT = "org.mozilla.javascript.Context";

    private static EngineClassLoader loaderFor(EngineBand band) throws IOException {
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        return EngineClassLoader.over(band, source, EngineIsolationTest.class.getClassLoader());
    }

    @Test
    public void theApplicationClasspathCannotSeeAnEngineAtAll() {
        // The precondition every other assertion here rests on, and it is worth stating: if Rhino were
        // reachable through the ordinary loader, every test below would pass for the wrong reason.
        try {
            Class.forName(RHINO_CONTEXT, false, EngineIsolationTest.class.getClassLoader());
            fail("Rhino is on the application classpath — the engine configurations are supposed to be "
                    + "resolvable and consumed by nothing");
        } catch (ClassNotFoundException expected) {
            // Correct.
        }
    }

    @Test
    public void twoBandsLoadTwoDIFFERENTCopiesOfTheSameClass() throws IOException {
        EngineClassLoader band8 = loaderFor(EngineBand.JAVA_8);
        EngineClassLoader band11 = loaderFor(EngineBand.JAVA_11);
        try {
            Class<?> fromEight = Class.forName(RHINO_CONTEXT, false, band8);
            Class<?> fromEleven = Class.forName(RHINO_CONTEXT, false, band11);

            assertEquals(fromEight.getName(), fromEleven.getName());
            assertNotSame("same name, two loaders — these MUST be different types, or the isolation is "
                    + "decorative", fromEight, fromEleven);
            assertSame(band8, fromEight.getClassLoader());
            assertSame(band11, fromEleven.getClassLoader());
        } catch (ClassNotFoundException absent) {
            fail("an engine band has no Rhino: " + absent);
        } finally {
            band8.close();
            band11.close();
        }
    }

    @Test
    public void eachBandLoadsItsOwnPinnedRhinoVersion() throws Exception {
        // Not merely "different objects" — different CODE. Rhino reports its own version, so this is the
        // assertion that the pins in EngineBand are what actually gets loaded rather than whatever the
        // resolver felt like.
        EngineClassLoader band8 = loaderFor(EngineBand.JAVA_8);
        EngineClassLoader band11 = loaderFor(EngineBand.JAVA_11);
        try {
            String eight = implementationVersion(band8);
            String eleven = implementationVersion(band11);
            assertTrue("band 8 loaded Rhino " + eight + ", expected " + EngineBand.JAVA_8.rhinoVersion(),
                    eight.contains(EngineBand.JAVA_8.rhinoVersion()));
            assertTrue("band 11 loaded Rhino " + eleven + ", expected " + EngineBand.JAVA_11.rhinoVersion(),
                    eleven.contains(EngineBand.JAVA_11.rhinoVersion()));
        } finally {
            band8.close();
            band11.close();
        }
    }

    /**
     * Rhino's own version string, read from an instance.
     *
     * <p>Through the no-arg constructor reflectively rather than {@code Context.enter()}, because
     * entering associates the context with the calling <em>thread</em> — and this test enters two
     * bands' contexts in one method, so the second would either clash with the first or silently
     * answer about it.</p>
     */
    private static String implementationVersion(EngineClassLoader loader) throws Exception {
        Class<?> context = Class.forName(RHINO_CONTEXT, false, loader);
        Object instance = context.getDeclaredConstructor().newInstance();
        return String.valueOf(context.getMethod("getImplementationVersion").invoke(instance));
    }

    @Test
    public void theJdkAlwaysComesFromTheParent() throws IOException {
        // Loading a second java.lang.String is not merely wrong, it is forbidden -- but the rule has to
        // be spelled out because the loader is child-first by default and `java.` is only exempt because
        // PARENT_FIRST says so.
        EngineClassLoader loader = loaderFor(EngineBand.JAVA_17);
        try {
            assertSame(String.class, Class.forName("java.lang.String", false, loader));
            assertSame(java.util.List.class, Class.forName("java.util.List", false, loader));
        } catch (ClassNotFoundException impossible) {
            fail(String.valueOf(impossible));
        } finally {
            loader.close();
        }
    }

    @Test
    public void theBridgePackageIsParentFirstAndNothingElseOfOursIs() {
        // The whole isolation trade in two assertions. The bridge package must be shared, or a cast
        // across the seam throws the least helpful exception the JVM produces -- `X cannot be cast to X`.
        // Everything else of ours must NOT be, or the adapter would be loaded by the parent and could
        // not see the engine it exists to talk to.
        assertTrue(EngineClassLoader.isParentFirst(
                "com.crystalgui.language.engine.bridge.SomeContract"));
        assertFalse(EngineClassLoader.isParentFirst(
                "com.crystalgui.language.java.EcjAdapter"));
        assertFalse(EngineClassLoader.isParentFirst("org.mozilla.javascript.Context"));
        assertTrue(EngineClassLoader.isParentFirst("java.lang.String"));
        assertTrue(EngineClassLoader.isParentFirst("javax.tools.JavaCompiler"));
    }

    @Test
    public void aBandWithNoJarsRefusesLoudlyRatherThanLoadingNothing() throws IOException {
        try {
            EngineClassLoader.over(EngineBand.JAVA_17, EngineSource.NONE, null);
            fail("an empty band produced a loader — it would fail later as ClassNotFoundException, "
                    + "several frames from the actual problem");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no jars"));
        }
    }

    @Test
    public void aMissingBandDirectoryIsEmptyRatherThanAnError() throws IOException {
        // A build that ships only the band it targets is legitimate, and must not fail on the two it
        // deliberately omitted.
        Path absent = Path.of("build", "no-such-engine-directory");
        EngineSource source = EngineSource.directory(absent);
        assertTrue(source.jarsFor(EngineBand.JAVA_8).isEmpty());
        assertTrue(source.jarsFor(EngineBand.JAVA_17).isEmpty());
    }

    @Test
    public void aDirectorySourceReturnsJarsInAStableOrder() throws IOException {
        // Files.list gives filesystem order, which differs between ext4 and NTFS. A duplicate class
        // across two jars then resolves differently per platform -- a bug that reproduces for one
        // person and for nobody else.
        Path root = Files.createTempDirectory("cgui-engine-order");
        Path band = Files.createDirectories(root.resolve("17"));
        for (String name : List.of("zeta.jar", "alpha.jar", "middle.jar", "notes.txt")) {
            Files.createFile(band.resolve(name));
        }
        try {
            List<java.net.URL> jars = EngineSource.directory(root).jarsFor(EngineBand.JAVA_17);
            assertEquals(3, jars.size());
            assertTrue(jars.get(0).toString().endsWith("alpha.jar"));
            assertTrue(jars.get(1).toString().endsWith("middle.jar"));
            assertTrue(jars.get(2).toString().endsWith("zeta.jar"));
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Collections.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // A temp directory that outlives the test is not worth failing it over.
                    }
                });
            }
        }
    }

    @Test
    public void aPathListDropsEntriesThatDoNotExist() {
        // A build assembles these and is occasionally stale. One missing jar should surface as the
        // engine failing to find a class it needs -- naming that class -- rather than as a startup
        // failure naming a file nobody was looking at.
        EngineSource source = EngineSource.ofPathList("no/such/a.jar" + java.io.File.pathSeparator
                + "also/missing/b.jar");
        assertSame(EngineSource.NONE, source);
    }

    @Test
    public void anEmptyOrAbsentPathListIsNone() {
        assertSame(EngineSource.NONE, EngineSource.ofPathList(null));
        assertSame(EngineSource.NONE, EngineSource.ofPathList("   "));
    }
}
