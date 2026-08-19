package com.crystalgui.language.engine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Bands carried inside a jar and extracted on first use (§26.2).
 *
 * <p>Over a <b>real jar and a real {@link URLClassLoader}</b> rather than a directory, because the whole
 * reason extraction exists is that a nested jar has no URL a loader can open — a directory-backed
 * fixture would pass without exercising the thing being built. The jars inside are not real engines;
 * what is under test is the listing, the extraction and the caching, none of which reads their content.</p>
 */
public class BundledEngineSourceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final String ROOT = "assets/crystalgui/engines";

    /** A jar carrying one band: two "jars" and the index that names them. */
    private Path bundleJar(String index, String... entries) throws IOException {
        Path jar = folder.newFile("bundle-" + entries.length + "-" + index.hashCode() + ".jar").toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(ROOT + "/8/" + EngineBundle.INDEX));
            zip.write(index.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(ROOT + "/8/" + entry));
                zip.write(("contents of " + entry).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    private URLClassLoader loaderOver(Path jar) throws IOException {
        // Null parent, so a stray resource of the same name on the test classpath cannot answer instead.
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
    }

    @Test
    public void theBandIsExtractedAndAnsweredAsUrls() throws Exception {
        Path jar = bundleJar("a.jar\nb.jar\n", "a.jar", "b.jar");
        Path cache = folder.newFolder("cache").toPath();
        try (URLClassLoader loader = loaderOver(jar)) {
            List<URL> urls = EngineSource.extractedFrom(loader, ROOT, cache).jarsFor(EngineBand.JAVA_8);

            assertEquals(2, urls.size());
            assertTrue(Files.isRegularFile(cache.resolve("8/a.jar")));
            assertTrue(Files.isRegularFile(cache.resolve("8/b.jar")));
            assertTrue(urls.get(0).toString().endsWith("a.jar"));
        }
    }

    /**
     * <b>Index order is classpath order, and it is not re-sorted.</b>
     *
     * <p>Two jars can declare the same package and position decides which wins. Sorting here would make
     * that decision differently from the build that chose it — silently, and only for whoever hits the
     * duplicate.</p>
     */
    @Test
    public void theIndexOrderIsPreserved() throws Exception {
        Path jar = bundleJar("z.jar\na.jar\n", "a.jar", "z.jar");
        Path cache = folder.newFolder("cache").toPath();
        try (URLClassLoader loader = loaderOver(jar)) {
            List<URL> urls = EngineSource.extractedFrom(loader, ROOT, cache).jarsFor(EngineBand.JAVA_8);
            assertTrue("the index said z first", urls.get(0).toString().endsWith("z.jar"));
            assertTrue(urls.get(1).toString().endsWith("a.jar"));
        }
    }

    /**
     * <b>A second launch copies nothing.</b>
     *
     * <p>Proven by making the cached file <em>different</em> from what the jar holds and showing it
     * survives: an implementation that re-extracts every time passes any test that only counts files.
     * This is also what makes the first-launch cost a one-off rather than 16 MB on every start.</p>
     */
    @Test
    public void anAlreadyExtractedBandIsNotRewritten() throws Exception {
        Path jar = bundleJar("a.jar\n", "a.jar");
        Path cache = folder.newFolder("cache").toPath();
        try (URLClassLoader loader = loaderOver(jar)) {
            EngineSource source = EngineSource.extractedFrom(loader, ROOT, cache);
            source.jarsFor(EngineBand.JAVA_8);

            Path extracted = cache.resolve("8/a.jar");
            Files.write(extracted, "left alone".getBytes(StandardCharsets.UTF_8));
            source.jarsFor(EngineBand.JAVA_8);

            assertEquals("left alone",
                    new String(Files.readAllBytes(extracted), StandardCharsets.UTF_8));
        }
    }

    /**
     * <b>An emptied cache entry is repaired rather than trusted.</b>
     *
     * <p>Zero length is what an interrupted copy leaves, and it is precisely what an existence check
     * would accept — after which the band is permanently broken and the only fix is deleting the
     * directory by hand.</p>
     */
    @Test
    public void aTruncatedCacheEntryIsReExtracted() throws Exception {
        Path jar = bundleJar("a.jar\n", "a.jar");
        Path cache = folder.newFolder("cache").toPath();
        try (URLClassLoader loader = loaderOver(jar)) {
            EngineSource source = EngineSource.extractedFrom(loader, ROOT, cache);
            source.jarsFor(EngineBand.JAVA_8);

            Path extracted = cache.resolve("8/a.jar");
            try (OutputStream truncate = Files.newOutputStream(extracted)) {
                truncate.flush();
            }
            assertEquals(0, Files.size(extracted));

            source.jarsFor(EngineBand.JAVA_8);
            assertEquals("contents of a.jar",
                    new String(Files.readAllBytes(extracted), StandardCharsets.UTF_8));
        }
    }

    /**
     * <b>No index means no band, and that is a supported build rather than an error.</b>
     *
     * <p>Shipping the editor without engines is legitimate — the whole stack degrades to grammar-only
     * colouring — so this has to answer empty and write nothing at all, including the directory.
     * Throwing here would turn a deliberate build choice into a startup failure.</p>
     */
    @Test
    public void aJarWithNoBandAnswersEmpty() throws Exception {
        Path jar = folder.newFile("empty.jar").toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("something/else.txt"));
            zip.closeEntry();
        }
        Path cache = folder.newFolder("cache").toPath();
        try (URLClassLoader loader = loaderOver(jar)) {
            assertTrue(EngineSource.extractedFrom(loader, ROOT, cache)
                    .jarsFor(EngineBand.JAVA_8).isEmpty());
            assertFalse("a band directory was created for a band that does not exist",
                    Files.exists(cache.resolve("8")));
        }
    }

    /**
     * <b>An index entry that could climb out of the band directory is refused.</b>
     *
     * <p>An index names files in one directory. A name with a separator in it is either a mistake or an
     * attempt to write somewhere else, and the difference does not matter — neither should extract.</p>
     */
    @Test
    public void anIndexCannotNameAPathOutsideTheBand() throws Exception {
        Path jar = bundleJar("../escaped.jar\nsub/dir.jar\ngood.jar\n", "good.jar");
        Path cache = folder.newFolder("cache").toPath();
        try (URLClassLoader loader = loaderOver(jar)) {
            List<URL> urls = EngineSource.extractedFrom(loader, ROOT, cache).jarsFor(EngineBand.JAVA_8);
            assertEquals(1, urls.size());
            assertTrue(urls.get(0).toString().endsWith("good.jar"));
            assertFalse(Files.exists(cache.resolve("escaped.jar")));
        }
    }
}
