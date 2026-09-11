package com.crystalgui.core.cache;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>Every runtime download is named in {@code download/locations.json}, and nowhere else.</b>
 *
 * <p>A URL written into a shipped class is one no released jar can repair when its host moves. So code
 * anywhere — {@code core/}, {@code language/} and every loader — names an id, and the shipped file must
 * answer every id it names.</p>
 */
public class DownloadUrlsLiveInOneFileTest {

    /** An address, not a bare scheme: {@code startsWith("https://")} checks a link and fetches nothing. */
    private static final Pattern URL_LITERAL = Pattern.compile("\"https?://[^\"\\s]+");

    /** {@code located("mcp/stable-12/methods.csv")}, or a prefix: {@code located("forge/mcp-config/" + v)}. */
    private static final Pattern LOCATED = Pattern.compile("located\\(\"([^\"]+)\"\\s*(\\+)?");

    @Test
    public void noSourceThatDownloadsNamesAUrl() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : sources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int at = 0; at < lines.size(); at++) {
                String line = lines.get(at).trim();
                // A comment may show an address; only code can download from one.
                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) continue;
                if (URL_LITERAL.matcher(line).find()) offences.add(file.getFileName() + ":" + (at + 1));
            }
        }
        assertTrue("name an id in download/locations.json instead of a URL:\n  " + String.join("\n  ", offences),
                offences.isEmpty());
    }

    @Test
    public void theShippedFileListsEveryIdAHostAsksFor() throws IOException {
        DownloadLocations shipped = DownloadLocations.get();
        List<String> missing = new ArrayList<>();
        for (Path file : sources()) {
            Matcher located = LOCATED.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            while (located.find()) {
                String id = located.group(1);
                // A prefix is answered by a template, or by pinned ids under it.
                boolean listed = located.group(2) == null ? shipped.lists(id)
                        : shipped.lists(id + "0") || !shipped.idsUnder(id).isEmpty();
                if (!listed) missing.add(id + " (" + file.getFileName() + ")");
            }
        }
        // Built rather than written, so the pattern above cannot see these.
        for (String id : new String[] {"jdk-sources/8", "jdk-sources/17", "jdk-sources/21"}) {
            if (!shipped.lists(id)) missing.add(id);
        }
        for (String band : new String[] {"8", "11", "17"}) {
            if (shipped.idsUnder("engine/" + band + "/").isEmpty()) missing.add("engine/" + band + "/*");
        }
        assertTrue("download/locations.json does not list:\n  " + String.join("\n  ", missing), missing.isEmpty());
    }

    /**
     * What the shipped file expands to, byte for byte the addresses {@code verifyDownloadLocations} fetched
     * — the build reads the file with its own code, and this is what keeps the two readings the same.
     */
    @Test
    public void theShippedFileExpandsToTheAddressesTheBuildVerifies() {
        DownloadLocations shipped = DownloadLocations.get();
        assertEquals(Arrays.asList(
                        "https://repo1.maven.org/maven2/org/benf/cfr/0.152/cfr-0.152.jar",
                        "https://github.com/CrystalGraphics/CrystalGUI/releases/download/download-mirror/cfr-0.152.jar"),
                shipped.find("engine/8/cfr-0.152.jar").urls());
        assertEquals(Collections.singletonList(
                        "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/1.20.1/mcp_config-1.20.1.zip"),
                shipped.find("forge/mcp-config/1.20.1").urls());
    }

    /** Main sources of {@code core/} and {@code language/}, and every loader's {@code src/main} and {@code src/lang}. */
    private static List<Path> sources() throws IOException {
        String root = System.getProperty("cgui.test.repoRoot");
        Assume.assumeNotNull(root);
        List<Path> files = new ArrayList<>();
        collect(Paths.get(root, "core", "src", "main", "java"), files);
        collect(Paths.get(root, "language", "src", "main", "java"), files);
        Files.walkFileTree(Paths.get(root, "runtime"), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                String name = directory.getFileName().toString();
                // Build output holds a second copy of every source file.
                if (name.equals("build") || name.equals("run") || name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (directory.endsWith(Paths.get("src", "main", "java"))
                        || directory.endsWith(Paths.get("src", "lang", "java"))) {
                    collect(directory, files);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static void collect(Path directory, List<Path> into) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(file -> file.toString().endsWith(".java")).forEach(into::add);
        }
    }
}
