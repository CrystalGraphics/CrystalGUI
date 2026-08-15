package com.crystalgui.language.java;

import com.crystalgui.text.lang.CodeAction;

import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Holds the harness fixtures to what they claim.
 *
 * <p>The fixture files under {@code src/test/resources/fixtures/} exist so a person can open one, work
 * down it and see every correction in a family. That only works while the comments in them are true, and
 * a comment is exactly the kind of promise that stops being kept with nothing failing when it does — the
 * same reason a hint strip is derived from the key table its handler reads rather than written out.</p>
 *
 * <p>So the annotations are the assertion. Each {@code // FIX: "..."} line names an action that must be
 * offered on the line below it; this walks every fixture and checks all of them. A correction that is
 * renamed, broken or removed fails here, and so does a fixture that has drifted out of date.</p>
 *
 * <p>It is also the first entry in the corpus layer: these are real files analysed end to end through a
 * real engine, so a correction that throws on one of them fails here rather than in front of whoever
 * opened it.</p>
 */
public class FixtureFilesTest extends FixFixture {

    /** {@code // FIX: "Remove unused import"} — the quotes are required so a title may contain a colon. */
    private static final Pattern ANNOTATION = Pattern.compile("//\\s*FIX:\\s*\"(.*)\"\\s*$");

    @Test
    public void everyAnnotatedFixtureLineOffersWhatItClaims() throws Exception {
        List<Path> fixtures = annotatedFixtures();
        assertFalse("no annotated fixture files found — they are the harness's whole point",
                fixtures.isEmpty());

        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Path fixture : fixtures) {
            String className = fixture.getFileName().toString().replace(".java", "");
            String source = new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8);
            String[] lines = source.split("\n", -1);

            for (int i = 0; i < lines.length; i++) {
                Matcher annotation = ANNOTATION.matcher(lines[i].trim());
                if (!annotation.matches()) continue;
                String expected = annotation.group(1);
                checked++;

                int target = nextCodeLine(lines, i + 1);
                if (target < 0) {
                    failures.add(className + ":" + (i + 1) + " — nothing follows the annotation");
                    continue;
                }
                int from = offsetOfLine(lines, target);
                List<String> offered = titlesAt(className, source, from, from + lines[target].length());
                if (!offered.contains(expected)) {
                    failures.add(className + ":" + (target + 1) + " <" + lines[target].trim() + ">\n"
                            + "      wanted: " + expected + "\n"
                            + "      got:    " + (offered.isEmpty() ? "(nothing)" : offered));
                }
            }
        }

        assertTrue("checked " + checked + " annotations across " + fixtures.size()
                + " fixture(s):\n  " + String.join("\n  ", failures), failures.isEmpty());
        assertTrue("a fixture with no annotations proves nothing", checked > 0);
    }

    private static List<String> titlesAt(String className, String source, int from, int to) {
        List<String> titles = new ArrayList<>();
        for (CodeAction action : actionsOver(className, source, from, to)) titles.add(action.title());
        return titles;
    }

    /** The next line that is neither blank nor another annotation — several may stack on one target. */
    private static int nextCodeLine(String[] lines, int start) {
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (ANNOTATION.matcher(line).matches()) continue;
            return i;
        }
        return -1;
    }

    private static int offsetOfLine(String[] lines, int line) {
        int offset = 0;
        for (int i = 0; i < line; i++) offset += lines[i].length() + 1;   // split("\n") dropped each one
        return offset;
    }

    /**
     * Every fixture carrying at least one annotation.
     *
     * <p>Discovered rather than named one by one, so adding a family file to the harness is one action
     * and not two — a fixture nobody remembered to register here would be exactly the untrue comment this
     * test exists to prevent.</p>
     *
     * <p><b>Selected on the annotation, not on the extension.</b> {@code fixtures/} is shared with the
     * documents that exist for looking at syntax colouring, and {@code Main.java} alone is five hundred
     * lines written to contain every construct the grammar has a capture for. Analysing those would be
     * half a second per run to prove nothing, since a file with no {@code // FIX:} line makes no claim.
     * A string scan decides it before any engine work happens.</p>
     */
    private static List<Path> annotatedFixtures() throws IOException {
        URL directory = FixtureFilesTest.class.getResource("/fixtures");
        if (directory == null) return List.of();
        Path root;
        try {
            root = Paths.get(directory.toURI());
        } catch (Exception notAPlainDirectory) {
            return List.of();
        }
        try (Stream<Path> found = Files.list(root)) {
            List<Path> files = new ArrayList<>();
            for (Path path : (Iterable<Path>) found.sorted()::iterator) {
                if (!path.getFileName().toString().endsWith(".java")) continue;
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                if (text.contains("// FIX:")) files.add(path);
            }
            return files;
        }
    }
}
