package com.crystalgui.language.java.assist;

import com.crystalgui.language.java.assist.SourceArchives;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/**
 * M11 — finding the source behind a classpath symbol.
 *
 * <h3>Why this can be tested at all, and why that mattered enough to split a class</h3>
 *
 * <p>The engine jars are supplied to the adapter at runtime and are deliberately absent from the test
 * compile classpath, so anything importing {@code org.eclipse.jdt.core.dom} is unreachable from here.
 * Source <em>discovery</em> imports none of it, and it is where the quiet mistakes live: a rule that
 * looks in the wrong directory or normalises an entry name wrongly does not fail, it simply finds
 * nothing, and the popup keeps rendering the older and poorer form with no way to tell why.</p>
 *
 * <p><b>This class is also the guard on that split, and it needs no assertion to be one.</b> Giving
 * {@link SourceArchives} a field, parameter or supertype of a JDT type makes it unloadable here —
 * descriptors resolve at class load — so the whole test fails with {@code NoClassDefFoundError} rather
 * than the boundary eroding one import at a time. A separate bytecode scan would say the same thing
 * later and less clearly.</p>
 */
public class SourceArchivesTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /**
     * <b>A modular {@code src.zip} keys its entries by module, and a {@code -sources.jar} does not.</b>
     *
     * <p>{@code java.base/java/util/List.java} against {@code com/example/Thing.java}. Both are looked up
     * by package path, so one of the two has to be normalised — and the test is a {@code .} in the first
     * segment, which no package segment can contain because a package segment is a Java identifier.</p>
     *
     * <p>The last case is the one a laxer rule gets wrong: a package with a dot <em>later</em> in the
     * path is not a module prefix, and stripping it would lose the top-level package of every library
     * whose sources are laid out that way.</p>
     */
    @Test
    public void aModulePrefixIsStrippedAndAPackagePathIsNot() {
        assertEquals("java/util/List.java",
                SourceArchives.Archive.packagePathOf("java.base/java/util/List.java"));
        assertEquals("com/example/Thing.java",
                SourceArchives.Archive.packagePathOf("com/example/Thing.java"));
        assertEquals("a leading slash is not a module prefix", "com/example/Thing.java",
                SourceArchives.Archive.packagePathOf("/com/example/Thing.java"));
        assertEquals("only the FIRST segment can be a module", "com/example/a.b/Thing.java",
                SourceArchives.Archive.packagePathOf("com/example/a.b/Thing.java"));
    }

    /**
     * <b>Gradle keeps a jar and its sources in different directories</b>, and looking only beside the jar
     * finds nothing in the one layout every Gradle build produces.
     *
     * <p>{@code …/1.0/<sha1>/foo-1.0.jar} has its sources at {@code …/1.0/<othersha1>/foo-1.0-sources.jar}
     * — a sibling of the <em>parent</em>. Both shapes are asserted together because a rule covering only
     * the Maven one passes every test written against a hand-built classpath and then attaches nothing at
     * all in the environment this project is actually developed in.</p>
     */
    @Test
    public void sourcesAreFoundBesideAJarAndOneDirectoryOver() throws Exception {
        File maven = folder.newFolder("maven");
        File beside = new File(maven, "foo-1.0-sources.jar");
        writeSourceJar(beside, "com/example/Foo.java", "package com.example; class Foo {}");

        List<File> mavenCandidates =
                SourceArchives.sourcesBeside(new File(maven, "foo-1.0.jar").getAbsolutePath());
        assertTrue("the Maven layout: sources beside the jar", mavenCandidates.contains(beside));

        File version = folder.newFolder("gradle", "1.0");
        // REAL SHA-1 SHAPES, because the sibling search is gated on the directory looking like a cache
        // entry — forty hex characters. A shorter stand-in passed against the ungated version and would
        // now silently exercise nothing.
        File jarHash = new File(version, "5bc60781196d9884101dbe40d5b91bf895f24767");
        File sourcesHash = new File(version, "bf68df0b036e24bde6056fc03c5e70f06fb668f7");
        assertTrue(jarHash.mkdirs() && sourcesHash.mkdirs());
        File over = new File(sourcesHash, "foo-1.0-sources.jar");
        writeSourceJar(over, "com/example/Foo.java", "package com.example; class Foo {}");

        List<File> gradleCandidates =
                SourceArchives.sourcesBeside(new File(jarHash, "foo-1.0.jar").getAbsolutePath());
        assertTrue("the Gradle cache: sources in a SIBLING of the jar's directory",
                gradleCandidates.contains(over));
    }

    /**
     * <b>A {@code mods/} folder is not a Gradle cache, and must not be searched like one.</b>
     *
     * <p>This is the production shape and the reason the sibling search is gated. Three hundred mod jars
     * share one parent, so an ungated rule lists their grandparent three hundred identical times and then
     * proposes {@code config/foo-sources.jar} and {@code saves/foo-sources.jar} as candidates. Neither is
     * wrong so much as meaningless — and it is paid on the first hover of every launch.</p>
     */
    @Test
    public void aModsFolderIsNotSearchedLikeACache() throws Exception {
        File instance = folder.newFolder("instance");
        File mods = new File(instance, "mods");
        File config = new File(instance, "config");
        assertTrue(mods.mkdirs() && config.mkdirs());

        List<File> candidates =
                SourceArchives.sourcesBeside(new File(mods, "somemod-1.2.3.jar").getAbsolutePath());

        for (File candidate : candidates) {
            assertEquals("only the jar's own directory is a candidate outside a cache layout",
                    mods, candidate.getParentFile());
        }
        assertEquals("beside-the-jar, under both suffixes, and nothing else", 2, candidates.size());
    }

    /** A sources jar is not itself an entry to find sources for — or it would seek {@code -sources-sources}. */
    @Test
    public void aSourcesJarIsNotAskedForItsOwnSources() {
        assertTrue(SourceArchives.sourcesBeside("/somewhere/foo-1.0-sources.jar").isEmpty());
        assertTrue(SourceArchives.sourcesBeside("/somewhere/foo-1.0-src.jar").isEmpty());
        assertTrue("a directory entry has no sources to look beside",
                SourceArchives.sourcesBeside("/somewhere/build/classes/java/main").isEmpty());
    }

    /**
     * <b>An archive is not read until something asks for a file</b>, and an unreadable one answers
     * "nothing" rather than throwing.
     *
     * <p>The laziness is the reason the first keystroke is not a full pass over every zip on a modded
     * classpath; the tolerance is because a half-written {@code -sources.jar} from an interrupted
     * download is a state a real machine is in.</p>
     */
    @Test
    public void anArchiveIsIndexedOnDemandAndSurvivesRubbish() throws Exception {
        File jar = folder.newFile("lib-sources.jar");
        writeSourceJar(jar, "com/example/Thing.java", "package com.example; class Thing {}");

        SourceArchives.Archive archive = new SourceArchives.Archive(jar, false);
        assertTrue("nothing should have been read yet", archive.toString().contains("not indexed"));
        assertEquals("package com.example; class Thing {}", archive.read("com/example/Thing.java"));
        assertEquals(1, archive.size());
        assertNull(archive.read("com/example/Absent.java"));

        File rubbish = folder.newFile("broken-sources.jar");
        try (OutputStream out = new FileOutputStream(rubbish)) {
            out.write("not a zip".getBytes(StandardCharsets.UTF_8));
        }
        SourceArchives.Archive broken = new SourceArchives.Archive(rubbish, false);
        assertNull("an unreadable archive has nothing, and does not throw",
                broken.read("com/example/Thing.java"));
        assertEquals(0, broken.size());
    }

    /**
     * <b>The JDK's sources come first</b>, so a jar shipping a {@code java/} source tree cannot shadow
     * them — and they are marked as the platform, which is what decides the compliance they are read at.
     */
    @Test
    public void theJdkIsSearchedFirstAndIsMarkedAsThePlatform() {
        List<SourceArchives.Archive> archives = SourceArchives.discover(List.of());
        // src.zip is present in a full JDK and absent from a JRE; either is a legitimate host.
        for (SourceArchives.Archive archive : archives) {
            assertTrue("everything discovered from an empty classpath is the JDK's", archive.platform);
        }
        assertFalse("a JDK candidate path is always offered, present or not",
                SourceArchives.jdkSources().isEmpty());
    }

    private static void writeSourceJar(File jar, String entry, String content) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
