package com.crystalgui.language.java.assist;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M13 §25.4 — which jars ship their own sources, discovered rather than declared.
 *
 * <p>The first test is the one the class exists for: a mod nobody here has heard of is found, with no
 * registration, no entry in our source and nothing but a directory in its jar. Everything else is a rule
 * about not paying for the scan more than once or in the wrong places.</p>
 */
public class BundledSourcesTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /**
     * <b>A third-party mod is discovered with no registration at all.</b>
     *
     * <p>This was a two-entry constant naming CrystalGUI and CrystalGraphics, which works for exactly the
     * two projects that can edit that file. The convention exists for everybody else, and <b>a list a
     * third party has to be added to is a list a third party cannot use</b>.</p>
     *
     * <p>Deliberately a modid this repository never mentions.</p>
     */
    @Test
    public void anyModShippingSourcesIsFoundWithoutBeingDeclared() throws Exception {
        File jar = folder.newFile("someothermod-3.1.4.jar");
        write(jar,
                "assets/someothermod/sources/com/example/other/Api.java", "package com.example.other;",
                "com/example/other/Api.class", "not really a class");

        Set<String> prefixes = BundledSources.prefixesIn(List.of(jar.getAbsolutePath()));
        assertEquals(Set.of("assets/someothermod/sources/"), prefixes);
        assertEquals("someothermod", BundledSources.namespaceOf(prefixes.iterator().next()));
    }

    /** Several jars, several namespaces, and the classpath's order is kept. */
    @Test
    public void everyNamespaceOnTheClasspathIsCollected() throws Exception {
        File first = folder.newFile("a.jar");
        write(first, "assets/alpha/sources/com/a/A.java", "package com.a;");
        File second = folder.newFile("b.jar");
        write(second, "assets/beta/sources/com/b/B.java", "package com.b;",
                "assets/gamma/sources/com/c/C.java", "package com.c;");

        List<String> found = List.copyOf(BundledSources.prefixesIn(
                List.of(first.getAbsolutePath(), second.getAbsolutePath())));
        assertEquals(List.of("assets/alpha/sources/", "assets/beta/sources/", "assets/gamma/sources/"),
                found);
    }

    /**
     * <b>A jar with assets and no sources contributes nothing</b> — which is nearly every mod ever built.
     *
     * <p>And the discriminator is a real {@code .java} file rather than the directory entry above it. That
     * costs the whole entry walk and buys independence from whether the jar's builder wrote directory
     * entries — and it is what lets a discovered prefix be reported as present without a second check.</p>
     */
    @Test
    public void aJarWithAssetsButNoSourcesIsNotANamespace() throws Exception {
        File jar = folder.newFile("ordinary.jar");
        write(jar,
                "assets/ordinary/textures/gui/thing.png", "not a png either",
                "assets/ordinary/lang/en_US.lang", "key=value");
        assertTrue(BundledSources.prefixesIn(List.of(jar.getAbsolutePath())).isEmpty());

        File named = folder.newFile("named.jar");
        // A directory entry and NOTHING UNDER IT. Present in a jar built from an empty source set, and an
        // answer of "yes" here would put an archive in the chain that can never answer anything.
        write(named, "assets/named/sources/", "");
        assertTrue(BundledSources.prefixesIn(List.of(named.getAbsolutePath())).isEmpty());
    }

    /**
     * A classpath repeats itself, and opening a jar is the slow part.
     *
     * <p>Measured at 67 ms of the scan's ~105 ms warm just to open 359 files, so a shadowed module named
     * twice by two resolutions is worth not paying for twice.</p>
     */
    @Test
    public void aJarNamedTwiceIsOpenedOnce() throws Exception {
        File jar = folder.newFile("twice.jar");
        write(jar, "assets/twice/sources/com/t/T.java", "package com.t;");
        String path = jar.getAbsolutePath();
        assertEquals(1, BundledSources.prefixesIn(List.of(path, path, path)).size());
    }

    /**
     * Everything that is not a readable jar is skipped rather than being an error.
     *
     * <p>A classpath names things that are not there, a {@code mods/} folder holds files that are not
     * jars, and a directory entry is a module's class output — where sources never are, because they are
     * injected at packaging time.</p>
     */
    @Test
    public void rubbishOnTheClasspathIsSkippedRatherThanFatal() throws Exception {
        File notAJar = folder.newFile("notes.txt");
        File notAZip = folder.newFile("broken.jar");
        try (FileOutputStream out = new FileOutputStream(notAZip)) {
            out.write("this is not a zip".getBytes(StandardCharsets.UTF_8));
        }
        File directory = folder.newFolder("classes");
        File absent = new File(folder.getRoot(), "gone.jar");

        Set<String> prefixes = BundledSources.prefixesIn(Arrays.asList(
                notAJar.getAbsolutePath(), notAZip.getAbsolutePath(),
                directory.getAbsolutePath(), absent.getAbsolutePath(), null));
        assertTrue(prefixes.isEmpty());
    }

    /**
     * <b>A jar the loader has and the classpath list does not is still discovered.</b>
     *
     * <p>Discovery reads a classpath list; the prefix it finds is read back through the classloader. Those
     * two agreeing is an assumption, not a guarantee — and when it fails it fails silently, with the popup
     * simply staying thin. On 1.7.10 the loader <em>is</em> a {@code URLClassLoader}
     * ({@code LaunchClassLoader}) and is the authoritative answer; on a modern JVM the application loader
     * is not one and the list is. The union means neither has to be trusted alone.</p>
     */
    @Test
    public void aJarReachableOnlyThroughTheLoaderIsStillFound() throws Exception {
        File jar = folder.newFile("loaderonly.jar");
        write(jar, "assets/loaderonly/sources/com/l/L.java", "package com.l;");

        assertTrue("nothing on the classpath list to find",
                BundledSources.prefixesIn(List.of()).isEmpty());
        try (URLClassLoader loader = new URLClassLoader(new URL[] { jar.toURI().toURL() }, null)) {
            assertEquals(Set.of("assets/loaderonly/sources/"),
                    BundledSources.prefixesIn(List.of(), loader));
        }
    }

    @Test
    public void aNamespaceIsTheSegmentAfterAssets() {
        assertEquals("crystalgui", BundledSources.namespaceOf("assets/crystalgui/sources/"));
        assertEquals("crystalgraphics", BundledSources.namespaceOf("assets/crystalgraphics/sources/"));
        assertFalse("ours is discovered like anybody else's, not privileged",
                BundledSources.PREFIX.isEmpty());
    }

    /** Pairs of (entry name, contents). */
    private static void write(File jar, String... entries) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar))) {
            for (int at = 0; at + 1 < entries.length; at += 2) {
                zip.putNextEntry(new ZipEntry(entries[at]));
                zip.write(entries[at + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
