package com.crystalgui.language.java.assist;

import com.crystalgui.language.java.assist.SourceArchives;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
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
                SourceArchives.ZipArchive.packagePathOf("java.base/java/util/List.java"));
        assertEquals("com/example/Thing.java",
                SourceArchives.ZipArchive.packagePathOf("com/example/Thing.java"));
        assertEquals("a leading slash is not a module prefix", "com/example/Thing.java",
                SourceArchives.ZipArchive.packagePathOf("/com/example/Thing.java"));
        assertEquals("only the FIRST segment can be a module", "com/example/a.b/Thing.java",
                SourceArchives.ZipArchive.packagePathOf("com/example/a.b/Thing.java"));
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

        SourceArchives.ZipArchive archive = new SourceArchives.ZipArchive(jar, false);
        assertTrue("nothing should have been read yet", archive.toString().contains("not indexed"));
        assertEquals("package com.example; class Thing {}", archive.read("com/example/Thing.java"));
        assertEquals(1, archive.size());
        assertNull(archive.read("com/example/Absent.java"));

        File rubbish = folder.newFile("broken-sources.jar");
        try (OutputStream out = new FileOutputStream(rubbish)) {
            out.write("not a zip".getBytes(StandardCharsets.UTF_8));
        }
        SourceArchives.ZipArchive broken = new SourceArchives.ZipArchive(rubbish, false);
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
        // NO BUNDLED PRODUCER, so this asks only about the disk half. The bundled one is asserted to be
        // LAST by its own test, which is the property that matters and cannot be seen from here.
        List<SourceArchives.Archive> archives = SourceArchives.discover(List.of(), null);
        // src.zip is present in a full JDK and absent from a JRE; either is a legitimate host.
        for (SourceArchives.Archive archive : archives) {
            assertTrue("everything discovered from an empty classpath is the JDK's", archive.platform());
        }
        assertFalse("a JDK candidate path is always offered, present or not",
                SourceArchives.jdkSources().isEmpty());
    }

    // ── M13 §25.5 — a fetched or hand-pointed JDK source archive ────────────────────────────────

    /**
     * <b>The named archive is searched first and read as the platform.</b>
     *
     * <p>Two claims and both matter. <b>First</b>, because somebody who set the property, or a fetch that
     * just finished, is a more deliberate answer than whatever the running JVM happens to have. <b>As the
     * platform</b>, because that flag is what makes {@code AttachedSources} parse it at pre-module
     * compliance — read at 9+ a file declaring {@code package java.util} collides with {@code java.base}
     * and every type reference in it becomes unresolvable, which does not fail so much as quietly render
     * every name in the popup the wrong colour.</p>
     */
    @Test
    public void aNamedJdkArchiveIsSearchedFirstAndTreatedAsThePlatform() throws Exception {
        File archive = folder.newFile("jdk-17-sources.zip");
        writeSourceJar(archive, "java/util/List.java", "package java.util; interface List {}");

        String saved = System.getProperty(JdkSourceExtract.SOURCES_PROPERTY);
        try {
            System.setProperty(JdkSourceExtract.SOURCES_PROPERTY, archive.getAbsolutePath());
            List<SourceArchives.Archive> archives = SourceArchives.discover(List.of(), null);
            assertFalse(archives.isEmpty());
            SourceArchives.Archive first = archives.get(0);
            assertTrue("the fetched extract must outrank the running JVM's own src.zip",
                    first.platform());
            assertEquals("package java.util; interface List {}", first.read("java/util/List.java"));
        } finally {
            if (saved == null) System.clearProperty(JdkSourceExtract.SOURCES_PROPERTY);
            else System.setProperty(JdkSourceExtract.SOURCES_PROPERTY, saved);
        }
    }

    /**
     * <b>A JDK elsewhere on the machine is a candidate</b>, which is the step that matters in production.
     *
     * <p>A modded player launches on a jlink'd JRE — Mojang's launcher ships one and it carries no
     * {@code src.zip} — while very often having installed a full JDK because a pack's guide said to.
     * Reading theirs costs nothing and raises no licence question at all, so it has to be tried before
     * anything is fetched. Asserted through {@code JAVA_HOME} rather than the install roots because an
     * environment variable is the one input a test can state; the roots are the same rule with more
     * spellings.</p>
     */
    @Test
    public void aJdkNamedByTheEnvironmentIsOfferedAsWellAsTheRunningOne() {
        List<File> candidates = SourceArchives.jdkSources();
        String javaHome = System.getenv("JAVA_HOME");
        assertFalse("the running JVM is always offered", candidates.isEmpty());
        if (javaHome != null && !javaHome.isEmpty()) {
            File expected = new File(new File(javaHome), "lib/src.zip");
            assertTrue("JAVA_HOME names a JDK the launcher may not be using",
                    candidates.contains(expected));
        }
    }

    // ── M13 §25.4 — the sources we ship in our own jar ──────────────────────────────────────────

    /**
     * <b>A bundled source is read through the loader, with no index and no file.</b>
     *
     * <p>Through a real {@link URLClassLoader} over a real jar rather than a stub, because the claim
     * being made is about the JVM's own central-directory lookup — a fake loader would prove that a map
     * returns what was put in it.</p>
     */
    @Test
    public void aBundledSourceIsReadThroughTheLoader() throws Exception {
        File jar = folder.newFile("mod.jar");
        writeSourceJar(jar, BundledSources.PREFIX + "com/example/Thing.java",
                "package com.example; class Thing {}");

        try (URLClassLoader loader = new URLClassLoader(new URL[] { jar.toURI().toURL() }, null)) {
            SourceArchives.Archive archive =
                    new SourceArchives.ResourceArchive(loader, BundledSources.PREFIX);
            assertEquals("package com.example; class Thing {}",
                    archive.read("com/example/Thing.java"));
            assertNull(archive.read("com/example/Absent.java"));
            assertFalse("ours are read at the band's ceiling, not at pre-module compliance",
                    archive.platform());
        }
    }

    /**
     * <b>A disk archive beats the bundled copy</b>, which is what keeps a dev workspace honest.
     *
     * <p>The shipped sources are a snapshot of whatever version was built. A working tree or a published
     * {@code -sources.jar} for the same types is the more specific artifact and has to win, or a
     * developer reads last release's declaration for the method they are editing.</p>
     */
    @Test
    public void theBundledProducerIsSearchedLast() throws Exception {
        File onDisk = folder.newFile("lib-sources.jar");
        writeSourceJar(onDisk, "com/example/Thing.java", "package com.example; class Thing {}");
        File classes = folder.newFile("lib.jar");
        writeSourceJar(classes, BundledSources.PREFIX + "com/example/Thing.java",
                "package com.example; class Thing {}");

        try (URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
            List<SourceArchives.Archive> archives =
                    SourceArchives.discover(List.of(classes.getAbsolutePath()), loader);
            assertFalse(archives.isEmpty());
            SourceArchives.Archive last = archives.get(archives.size() - 1);
            assertTrue("the bundled producer must be the fallback, never the first answer",
                    last instanceof SourceArchives.ResourceArchive);
        }
    }

    /**
     * A package path becomes a resource path, so it may not climb out of the prefix.
     *
     * <p>Every name reaching it today comes from a binding rather than from user text — which is a fact
     * about today, and the same guard {@code EngineBundle} and {@code EngineManifest} already apply to
     * names read out of a shipped file.</p>
     */
    @Test
    public void aBundledPathCannotClimbOutOfItsPrefix() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
            SourceArchives.Archive archive =
                    new SourceArchives.ResourceArchive(loader, BundledSources.PREFIX);
            assertNull(archive.read("../../../etc/passwd"));
            assertNull(archive.read(""));
            assertNull(archive.read(null));
        }
    }

    /**
     * <b>The sources really are in the jar</b> — asserted against the running classloader, not a fixture.
     *
     * <p>Everything above proves the reader. This proves the <em>packaging</em>, which is the half that
     * fails silently: {@code tasks.jar} in {@code core/build.gradle.kts} is one line nothing else refers
     * to, and if it is dropped or renamed the popup simply goes back to the assembled form with nothing
     * to look at. A hover cannot tell "no source shipped" from "no source found".</p>
     *
     * <p>{@code UIElement} because it is the type a script author reaches for first and the one whose
     * javadoc is worth the megabytes.</p>
     */
    @Test
    public void ourOwnSourcesAreShippedAndReachableFromTheClasspath() {
        SourceArchives.Archive bundled = new SourceArchives.ResourceArchive(
                SourceArchives.class.getClassLoader(), BundledSources.PREFIX);
        String text = bundled.read("com/crystalgui/ui/dom/UIElement.java");
        assertNotNull("core's sources are not in the jar — see tasks.jar in core/build.gradle.kts", text);
        assertTrue("that is not UIElement", text.contains("public class UIElement"));
    }

    /**
     * <b>CrystalGraphics ships its own, under its own namespace</b> — and the reader needed no change.
     *
     * <p>That is the property worth pinning. A script author reaches for {@code CgPlatform} and
     * {@code CgMaterial} as readily as for {@code UIElement}, and the whole mechanism for making a jar's
     * declarations quotable is: put the sources at {@code assets/<namespace>/sources/} and add the
     * namespace to {@code BUNDLED_PREFIXES}. No code in this class knows anything about CrystalGraphics.</p>
     *
     * <p>Its own namespace rather than ours because it is a standalone library — mods depend on it with no
     * CrystalGUI in the pack, and shipping them an {@code assets/crystalgui/} directory would be claiming
     * a namespace it does not own.</p>
     */
    @Test
    public void crystalGraphicsShipsItsSourcesTooAndUnderItsOwnNamespace() {
        // THE RUNNING CLASSPATH, because that is what discovery scans. An empty list finds nothing, which
        // is correct and would make this assert against a producer that was never built.
        List<SourceArchives.Archive> archives = SourceArchives.discover(
                List.of(System.getProperty("java.class.path", "").split(File.pathSeparator)),
                SourceArchives.class.getClassLoader());
        String text = null;
        for (SourceArchives.Archive archive : archives) {
            if (!(archive instanceof SourceArchives.ResourceArchive)) continue;
            String found = archive.read("com/crystalgraphics/platform/CgPlatform.java");
            if (found != null) text = found;
        }
        assertNotNull("CrystalGraphics' sources are not in its jar — see tasks.jar in "
                + "CrystalGraphics/platform/build.gradle.kts", text);
        assertTrue("that is not CgPlatform", text.contains("class CgPlatform"));
    }

    private static void writeSourceJar(File jar, String entry, String content) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
