package com.crystalgui.language.cache;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Where a download comes from: the jar's copy of {@code download/locations.json}, master's, and an override.
 *
 * <p>Driven over {@code file:} URLs, as {@link DownloadsTest} is. The rule everything rests on is that
 * master's copy can MOVE an artifact and never CHANGE one — which is what lets every released jar take
 * addresses from a file nobody signs.</p>
 */
public class DownloadLocationsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /** A jar's copy: a pinned file, two templates and one engine band. */
    private static final String JAR = """
            {
              "format": 1,
              "self": ["https://example.invalid/locations.json"],
              "repositories": {
                "central": ["https://central.example/{path}"],
                "mirror": ["https://mirror.example/{file}"]
              },
              "files": {
                "a/b.jar": {"digest": "md5:0123abcd", "urls": ["https://one.example/b.jar", "https://two.example/b.jar"]},
                "maps/{minecraft}": {"urls": ["https://maps.example/{minecraft}/maps.zip"]},
                "lib/{minecraft}": {"maven": "org.example:lib:{minecraft}:v2@zip", "from": ["central"]}
              },
              "engines": {
                "from": ["central", "mirror"],
                "bands": {"8": {"org.example.tools:engine:1.0": "sha1:abcd"}}
              }
            }
            """;

    @Test
    public void anArtifactIsTheJarsPinAndItsUrlsBestFirst() {
        DownloadLocations locations = DownloadLocations.of(JAR, null, null, false);

        DownloadLocations.Location where = locations.find("a/b.jar");
        assertEquals("md5:0123abcd", where.digest());
        assertEquals(Arrays.asList("https://one.example/b.jar", "https://two.example/b.jar"), where.urls());
        assertNull("an id the jar does not list has no location", locations.find("a/c.jar"));
    }

    @Test
    public void aTemplateIsFilledFromTheIdAndNeverPinned() {
        DownloadLocations locations = DownloadLocations.of(JAR, null, null, false);

        DownloadLocations.Location maps = locations.find("maps/1.20.4");
        assertEquals(Collections.singletonList("https://maps.example/1.20.4/maps.zip"), maps.urls());
        assertNull(maps.digest());
        assertEquals(Collections.singletonList("https://central.example/org/example/lib/1.20.4/lib-1.20.4-v2.zip"),
                locations.find("lib/1.20.4").urls());
        assertNull("a placeholder is one path segment", locations.find("maps/1.20/4"));
    }

    /** An engine jar is its band's coordinates, in each repository the engines come from, in order. */
    @Test
    public void anEngineJarIsItsCoordinatesInEachRepository() {
        DownloadLocations locations = DownloadLocations.of(JAR, null, null, false);

        assertEquals(Collections.singletonList("engine/8/engine-1.0.jar"), locations.idsUnder("engine/8/"));
        DownloadLocations.Location engine = locations.find("engine/8/engine-1.0.jar");
        assertEquals("sha1:abcd", engine.digest());
        assertEquals(Arrays.asList("https://central.example/org/example/tools/engine/1.0/engine-1.0.jar",
                "https://mirror.example/engine-1.0.jar"), engine.urls());
    }

    /**
     * <b>Master's copy moves an artifact and never changes one.</b> Its URLs come first; its digests and
     * any id the jar does not list are ignored, so it cannot make a jar accept other bytes. An address it
     * adds to a repository reaches every artifact there, which is what makes a moved host one line.
     */
    @Test
    public void mastersCopyAddsUrlsAndCannotChangeWhatTheJarAccepts() throws IOException {
        Path master = write("master.json", """
                {
                  "repositories": {"central": ["https://central-moved.example/{path}"]},
                  "files": {
                    "a/b.jar": {"digest": "md5:ffffffff", "urls": ["https://three.example/b.jar"]},
                    "evil/new.jar": {"digest": "md5:00000000", "urls": ["https://evil.example/new.jar"]}
                  }
                }
                """);
        DownloadLocations locations = DownloadLocations.of(JAR, null, master, true);

        DownloadLocations.Location where = locations.find("a/b.jar");
        assertEquals("the jar's pin, whatever master says", "md5:0123abcd", where.digest());
        assertEquals(Arrays.asList("https://three.example/b.jar",
                "https://one.example/b.jar", "https://two.example/b.jar"), where.urls());
        assertNull(locations.find("evil/new.jar"));
        assertEquals(Arrays.asList("https://central-moved.example/org/example/tools/engine/1.0/engine-1.0.jar",
                        "https://central.example/org/example/tools/engine/1.0/engine-1.0.jar",
                        "https://mirror.example/engine-1.0.jar"),
                locations.find("engine/8/engine-1.0.jar").urls());
    }

    /** An override file, a pack's own mirror, is tried before anything else and under the same rule. */
    @Test
    public void anOverrideIsTriedFirst() throws IOException {
        Path master = write("master.json", """
                {"files": {"a/b.jar": {"urls": ["https://three.example/b.jar"]}}}
                """);
        DownloadLocations locations = DownloadLocations.of(JAR, """
                {"files": {"a/b.jar": {"digest": "md5:ffffffff", "urls": ["https://pack.example/b.jar"]}}}
                """, master, true);

        DownloadLocations.Location where = locations.find("a/b.jar");
        assertEquals("https://pack.example/b.jar", where.urls().get(0));
        assertEquals("md5:0123abcd", where.digest());
    }

    /**
     * <b>A link that died is repaired from master's copy</b> — the point of the file. The jar knows only a
     * dead URL; master's copy, fetched once every known URL has failed, adds one that works. Once per
     * session: a second failure does not fetch it again.
     */
    @Test
    public void aDeadLinkIsRepairedFromMastersCopy() throws IOException {
        Path artifact = write("moved.bin", "the artifact");
        String pin = "md5:" + CacheFiles.digestOf(artifact);
        Path served = folder.getRoot().toPath().resolve("served.json");
        write("served.json", """
                {"self": ["%s"], "files": {"a/moved.bin": {"urls": ["%s"]}}}
                """.formatted(served.toUri(), artifact.toUri()));
        // A fresh copy from before the move, so nothing fetches master's until a download has failed.
        Path cached = write("cached.json", """
                {"self": ["%s"]}
                """.formatted(served.toUri()));
        DownloadLocations locations = DownloadLocations.of("""
                {"self": ["%s"], "files": {"a/moved.bin": {"digest": "%s", "urls": ["%s"]}}}
                """.formatted(served.toUri(), pin, folder.getRoot().toPath().resolve("gone.bin").toUri()),
                null, cached, true);

        Path target = folder.getRoot().toPath().resolve("installed.bin");
        assertTrue(Downloads.located(locations, "a/moved.bin").into(target));
        assertEquals(pin, "md5:" + CacheFiles.digestOf(target));
        assertTrue("the fetched copy is kept for the next launch",
                read(cached).contains(artifact.toUri().toString()));
        assertFalse("at most once per session", locations.refresh());
    }

    /** A copy a day old is fetched again before the first download, and a fresh one is left alone. */
    @Test
    public void aCopyADayOldIsFetchedBeforeTheFirstDownload() throws IOException {
        Path served = folder.getRoot().toPath().resolve("served.json");
        write("served.json", """
                {"self": ["%s"], "files": {"a/b.jar": {"urls": ["https://fresh.example/b.jar"]}}}
                """.formatted(served.toUri()));
        String jar = """
                {"self": ["%s"], "files": {"a/b.jar": {"digest": "md5:0123abcd", "urls": ["https://one.example/b.jar"]}}}
                """.formatted(served.toUri());
        String before = """
                {"self": ["%s"]}
                """.formatted(served.toUri());

        DownloadLocations unchanged = DownloadLocations.of(jar, null, write("fresh.json", before), true);
        unchanged.refreshIfStale();
        assertEquals("https://one.example/b.jar", unchanged.find("a/b.jar").urls().get(0));

        Path stale = write("stale.json", before);
        Files.setLastModifiedTime(stale, FileTime.fromMillis(
                System.currentTimeMillis() - DownloadLocations.STALE_AFTER_MILLIS - 60_000));
        DownloadLocations refreshed = DownloadLocations.of(jar, null, stale, true);
        refreshed.refreshIfStale();
        assertEquals("https://fresh.example/b.jar", refreshed.find("a/b.jar").urls().get(0));
    }

    /** An answer this jar cannot read — an error page served as 200, a newer format — replaces nothing. */
    @Test
    public void anAnswerThisJarCannotReadIsIgnored() throws IOException {
        Path page = write("page.html", "<html>Rate limit exceeded</html>");
        String jar = """
                {"self": ["%s"], "files": {"a/b.jar": {"digest": "md5:0123abcd", "urls": ["https://one.example/b.jar"]}}}
                """.formatted(page.toUri());
        Path cached = write("cached.json", """
                {"self": ["%s"], "files": {"a/b.jar": {"urls": ["https://kept.example/b.jar"]}}}
                """.formatted(page.toUri()));

        DownloadLocations locations = DownloadLocations.of(jar, null, cached, true);
        assertFalse(locations.refresh());
        assertEquals("https://kept.example/b.jar", locations.find("a/b.jar").urls().get(0));

        Path newer = write("newer.json", """
                {"format": 2, "files": {"a/b.jar": {"urls": ["https://newer.example/b.jar"]}}}
                """);
        assertEquals(Collections.singletonList("https://one.example/b.jar"),
                DownloadLocations.of(jar, null, newer, true).find("a/b.jar").urls());
    }

    private Path write(String name, String text) throws IOException {
        Path file = folder.getRoot().toPath().resolve(name);
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
