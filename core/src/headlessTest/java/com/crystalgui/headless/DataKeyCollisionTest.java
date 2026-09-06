package com.crystalgui.headless;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * <b>No {@code DataKey} name is declared on both sides of the strangler line.</b>
 *
 * <p>{@code DataKey.create} interns by NAME and throws when the same name is asked for with a
 * different TYPE — and during the port every ported widget is a second class with the same simple
 * name as the one it was copied from. So {@code ProblemsPanel} and {@code ProblemsPanel} both ask for
 * {@code "problemsPanel"}, and the first test that loads both throws
 * {@code ExceptionInInitializerError} out of a static field, in a test that is about neither of
 * them.</p>
 *
 * <h3>Why a test and not a rule somebody remembers</h3>
 *
 * <p>It surfaced three times in two batches — {@code graphView}, {@code blackboard},
 * {@code problemsPanel} — each time as a crash in an unrelated fixture, and each time it was found by
 * running the suite rather than by reading the diff. The batches still to come (6.5, 6.6, 6.7) copy
 * the editor, the desktop and the workbench, which is where most of the remaining keys are.</p>
 *
 * <h3>And why the NEW copy is the one that renames</h3>
 *
 * <p>{@code ContextKeys.find} resolves a key by name out of a {@code when} expression, so a key name
 * is part of a shipped command declaration. Renaming the old one silently breaks every declaration
 * that mentions it, and nothing fails until somebody presses the key. The convention is a
 * {@code .new} suffix, dropped at 6.9 when the old copy goes.</p>
 */
public class DataKeyCollisionTest {

    /** The packages the port copies INTO. Everything else in {@code com/crystalgui} is old engine. */
    private static final List<String> NEW_ENGINE = List.of(
            "widget", "chrome", "app", "desktop", "workbench");

    private static final Pattern DECLARATION = Pattern.compile(
            "DataKey\\s*\\.\\s*create\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    @Test
    public void noKeyNameIsDeclaredByBothEngines() throws IOException {
        Path source = repoRoot().resolve("core/src/main/java/com/crystalgui");
        assertTrue("cannot find the sources at " + source, Files.isDirectory(source));

        Map<String, Set<String>> sides = new LinkedHashMap<>();
        Map<String, List<String>> where = new LinkedHashMap<>();
        try (var walk = Files.walk(source)) {
            for (Path p : walk.toList()) {
                if (!p.toString().endsWith(".java")) continue;
                String rel = source.relativize(p).toString().replace('\\', '/');
                String top = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "";
                String side = NEW_ENGINE.contains(top) ? "new" : "old";
                // COMMENTS FIRST: this file's own javadoc names `DataKey.create` twice, and so does
                // every class that explains why its key carries a suffix.
                String text = BLOCK_COMMENT.matcher(
                        new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).replaceAll(" ");
                Matcher m = DECLARATION.matcher(text);
                while (m.find()) {
                    sides.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(side);
                    where.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                            .add(side + "  " + rel);
                }
            }
        }

        List<String> clashes = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : sides.entrySet()) {
            if (e.getValue().size() < 2) continue;
            clashes.add("\"" + e.getKey() + "\"\n      " + String.join("\n      ", where.get(e.getKey())));
        }
        assertTrue("a DataKey name is declared by BOTH engines. DataKey.create interns by name and "
                + "throws when the type differs, so the first test that loads both classes dies in a "
                + "static initialiser. Give the NEW copy a `.new` suffix -- never the old one, whose "
                + "name a `when` expression may resolve:\n" + String.join("\n", clashes),
                clashes.isEmpty());
    }

    /**
     * The counter-assertion: the scan is reading real declarations.
     *
     * <p>A regex that matched nothing would satisfy the test above forever, and this is exactly the
     * kind of check that gets written once and then silently stops looking — a rename of
     * {@code DataKey.create}, a move of the source root, a walk that finds no files.</p>
     */
    @Test
    public void theScanFindsTheKeysThatDoExist() throws IOException {
        Path source = repoRoot().resolve("core/src/main/java/com/crystalgui");
        int found = 0;
        try (var walk = Files.walk(source)) {
            for (Path p : walk.toList()) {
                if (!p.toString().endsWith(".java")) continue;
                String text = BLOCK_COMMENT.matcher(
                        new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).replaceAll(" ");
                Matcher m = DECLARATION.matcher(text);
                while (m.find()) found++;
            }
        }
        // THIRTEEN TODAY, down from nineteen: the old engine took six with it, three of them keys
        // only it could answer (`DataKey<UIElement>`, `DataKey<UIWindow>`, and one naming its own
        // menu bar). The floor is deliberately just under the real count rather than a round number
        // -- the point is to fail when the scan stops finding ANYTHING (a renamed factory, a moved
        // source root, a walk that reads no files), not to police how many keys the engine has.
        assertTrue("the scan found " + found + " DataKey declarations -- there are ~13, so this is "
                + "looking in the wrong place or matching the wrong thing", found >= 10);
    }

    private static Path repoRoot() {
        Path here = ClassReferences.mainClassesRoot(DataKeyCollisionTest.class);
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("settings.gradle.kts"))) return p;
        }
        throw new IllegalStateException("cannot find the repository root from " + here);
    }
}
