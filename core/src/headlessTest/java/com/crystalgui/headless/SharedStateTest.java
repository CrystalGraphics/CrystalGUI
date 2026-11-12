package com.crystalgui.headless;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * <b>Nothing mutable is shared between documents</b>: no static {@code Signal} or {@code Property}.
 *
 * <p>Each document runs on its own sequence. A {@code Signal} or {@code Property} is local to one document and
 * checks its sequence, so a static one is either a bug waiting for the second document or a crash when it arrives.
 * What crosses documents is a {@code Broadcast} (an event) or a value in a published or owned type; what one document
 * keeps per feature is a {@code UIDocument.Local}.</p>
 *
 * <p>Read from the sources, as {@link LayeringTest}'s import rules are: a field's declared type is what the rule is
 * about, and the source says it without loading a class the headless classpath cannot.</p>
 */
public class SharedStateTest {

    /** A static field typed {@code Signal}, {@code Signal.Action}/{@code Value}/{@code Pair}, {@code SignalBase} or {@code Property}. */
    private static final Pattern STATIC_LOCAL_STATE = Pattern.compile(
            "\\bstatic\\b[^=;(]*\\b(Signal(\\.\\w+)?|SignalBase|Property)\\s*(<[^=;]*>)?\\s+\\w+\\s*[=;]");

    @Test
    public void noStaticSignalOrProperty() throws IOException {
        Path repo = repoRoot();
        List<String> offences = new ArrayList<>();
        for (String module : List.of("core", "language")) {
            Path src = repo.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(src)) continue;
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path file : walk.toList()) {
                    if (!file.toString().endsWith(".java")) continue;
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i).trim();
                        if (line.startsWith("*") || line.startsWith("//")) continue;
                        if (STATIC_LOCAL_STATE.matcher(line).find()) {
                            offences.add(repo.relativize(file) + ":" + (i + 1) + ": " + line);
                        }
                    }
                }
            }
        }
        assertTrue("a static Signal or Property is shared by every document -- a Broadcast, a UIDocument.Local or "
                + "a field of the owner instead:\n" + String.join("\n", offences), offences.isEmpty());
    }

    /** A static field initialised with a plain (not concurrent) collection or builder. */
    private static final Pattern STATIC_PLAIN_COLLECTION = Pattern.compile(
            "\\bstatic\\b.*?=\\s*(?:\\(.*?\\)\\s*)?new\\s+(?:java\\.util\\.)?(HashMap|LinkedHashMap|TreeMap|WeakHashMap|"
                    + "IdentityHashMap|EnumMap|HashSet|LinkedHashSet|TreeSet|ArrayList|LinkedList|ArrayDeque|StringBuilder|"
                    + "BitSet|PriorityQueue|Stack|Vector)\\b");

    /**
     * <b>A static collection says which thread rule keeps it safe</b>, in a {@code // Threads:} line on or above
     * the field, unless it is itself concurrent or wrapped ({@code Collections.synchronized*}, {@code Concurrent*}).
     *
     * <p>Several documents run style, layout and paint at once, so every static a frame can reach is shared. The rule
     * is one of three: <i>guarded by</i> a monitor, <i>filled once</i> by the static initialiser and read-only after,
     * or a concurrent type. A field that can say none of them is state of a document or a service instead.</p>
     */
    @Test
    public void everyStaticCollectionNamesItsThreadRule() throws IOException {
        Path repo = repoRoot();
        List<String> offences = new ArrayList<>();
        for (String module : List.of("core", "language")) {
            Path src = repo.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(src)) continue;
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path file : walk.toList()) {
                    if (!file.toString().endsWith(".java")) continue;
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i).trim();
                        if (line.startsWith("*") || line.startsWith("//")) continue;
                        if (!STATIC_PLAIN_COLLECTION.matcher(line).find()) continue;
                        if (line.contains("synchronized") || line.contains("Concurrent") || line.contains("CopyOnWrite")) continue;
                        boolean named = line.contains("Threads:") || i > 0 && lines.get(i - 1).contains("Threads:");
                        if (!named) offences.add(repo.relativize(file) + ":" + (i + 1) + ": " + line);
                    }
                }
            }
        }
        assertTrue("a static collection is shared by every document: add a `// Threads: guarded by ...` or `// Threads: "
                + "filled once by the static initialiser` line above it, or make it concurrent:\n"
                + String.join("\n", offences), offences.isEmpty());
    }

    private static Path repoRoot() {
        for (Path p = ClassReferences.mainClassesRoot(SharedStateTest.class); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("settings.gradle.kts"))) return p;
        }
        throw new IllegalStateException("cannot find the repository root");
    }
}
