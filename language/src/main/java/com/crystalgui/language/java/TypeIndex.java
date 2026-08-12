package com.crystalgui.language.java;

import com.crystalgui.text.lang.SymbolKind;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Every type name on the classpath, so an unimported one can be offered and imported on accept — §15.4.
 *
 * <h3>Names only, from the classpath's own directory structure</h3>
 *
 * <p>A class file's <em>path</em> already spells its binary name, so building this needs no parsing and no
 * classloading at all — reading {@code java/util/ArrayList.class} out of a jar's central directory is
 * enough. That matters more than it sounds: <b>loading</b> the classes to enumerate them would run every
 * static initialiser on the classpath, which for a Minecraft host means initialising the game.</p>
 *
 * <h3>Built once, lazily, and shared by every document</h3>
 *
 * <p>The classpath does not change while the process runs, so this is per-engine rather than per-document.
 * The first query pays for the scan; there is no background warm-up, because a warm-up that runs at startup
 * pays the cost on every launch including the ones where nobody opens a Java file.</p>
 *
 * <h3>What it deliberately does not do</h3>
 *
 * <p>No members, no signatures, no hierarchy — those all need the class file's <em>contents</em> and belong
 * to the compiler, which already has them. This answers exactly one question: "what types exist whose simple
 * name starts like this". Anything more is a second, worse copy of ECJ's own index.</p>
 */
final class TypeIndex {

    /** One type: enough to draw a row and to write the import. */
    record Entry(String simpleName, String packageName, SymbolKind kind) {
        String qualifiedName() {
            return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        }
    }

    /**
     * A scan wider than this is a classpath problem, not an index problem.
     *
     * <p>The cap exists so a pathological classpath degrades into a smaller index rather than into a stalled
     * editor — and it is logged when hit, because an index that silently stops halfway looks like a missing
     * type rather than a truncated scan.</p>
     */
    private static final int MAX_TYPES = 60_000;

    /** Kept to a handful so the popup is not a wall of near-identical names. */
    private static final int MAX_RESULTS = 40;

    private final List<String> classpath;
    private List<Entry> entries;

    TypeIndex(List<String> classpath) {
        this.classpath = classpath == null ? List.of() : List.copyOf(classpath);
    }

    /** Types whose simple name matches {@code prefix}, best first. */
    List<Entry> matching(String prefix) {
        if (prefix == null || prefix.isEmpty()) return List.of();
        ensureBuilt();
        String needle = prefix.toLowerCase(Locale.ROOT);
        List<Entry> hits = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.simpleName().toLowerCase(Locale.ROOT).startsWith(needle)) {
                hits.add(entry);
                if (hits.size() >= MAX_RESULTS * 4) break;
            }
        }
        // Shortest first, then alphabetical: a prefix hit on a short name is the likelier target, and the
        // total order is what stops the list permuting between keystrokes.
        hits.sort(Comparator.comparingInt((Entry e) -> e.simpleName().length())
                .thenComparing(Entry::simpleName));
        return hits.size() > MAX_RESULTS ? hits.subList(0, MAX_RESULTS) : hits;
    }

    private synchronized void ensureBuilt() {
        if (entries != null) return;
        List<Entry> built = new ArrayList<>();
        for (String element : classpath) {
            if (built.size() >= MAX_TYPES) break;
            try {
                File file = new File(element);
                if (!file.exists()) continue;
                if (file.isDirectory()) scanDirectory(file.toPath(), built);
                else scanArchive(file, built);
            } catch (IOException | RuntimeException failed) {
                // ONE BAD ENTRY MUST NOT EMPTY THE INDEX. A classpath routinely names things that are not
                // there any more, and an index that gave up on the first would silently offer nothing.
                System.err.println("[crystalgui] type index skipped " + element + ": " + failed);
            }
        }
        if (built.size() >= MAX_TYPES) {
            System.err.println("[crystalgui] type index truncated at " + MAX_TYPES
                    + " types; unimported-type completion will not offer everything on the classpath");
        }
        built.sort(Comparator.comparing(Entry::simpleName));
        entries = Collections.unmodifiableList(built);
    }

    private static void scanArchive(File file, List<Entry> into) throws IOException {
        try (ZipFile archive = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> zipEntries = archive.entries();
            while (zipEntries.hasMoreElements() && into.size() < MAX_TYPES) {
                add(zipEntries.nextElement().getName(), into);
            }
        }
    }

    private static void scanDirectory(Path root, List<Entry> into) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                if (into.size() >= MAX_TYPES) return;
                add(root.relativize(path).toString().replace(File.separatorChar, '/'), into);
            });
        }
    }

    /** Turns {@code java/util/ArrayList.class} into an entry, or ignores it. */
    private static void add(String path, List<Entry> into) {
        if (!path.endsWith(".class")) return;
        // NESTED TYPES ARE SKIPPED. `Map$Entry` cannot be imported under that name and inserting it
        // produces a compile error naming a type the list just offered -- which reads as the completion
        // being wrong rather than the name being unusable.
        if (path.indexOf('$') >= 0) return;
        // Neither of these is a type anybody writes.
        if (path.endsWith("package-info.class") || path.endsWith("module-info.class")) return;

        String binary = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        int lastDot = binary.lastIndexOf('.');
        String simple = lastDot < 0 ? binary : binary.substring(lastDot + 1);
        String packageName = lastDot < 0 ? "" : binary.substring(0, lastDot);
        if (simple.isEmpty() || !Character.isJavaIdentifierStart(simple.charAt(0))) return;

        // KIND IS UNKNOWABLE FROM THE PATH -- telling a class from an interface needs the file's access
        // flags. CLASS is the honest majority answer and the icon is the only thing that reads it; the
        // alternative is opening every entry on the classpath to colour a letter.
        into.add(new Entry(simple, packageName, SymbolKind.CLASS));
    }
}
