package com.crystalgui.language.java;

import com.crystalgui.text.lang.SymbolKind;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
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

    /**
     * What {@link #matching} found, and whether there was more of it.
     *
     * <p>The second half is not bookkeeping. A completion list built from a truncated index must be
     * reported {@link com.crystalgui.text.lang.CompletionList#incomplete}, or the session filters the forty
     * names it was given locally and never asks again as the query narrows — so typing {@code CgTex} shows
     * whatever forty things started with {@code C}, and the type actually being typed is not among them.</p>
     */
    record Match(List<Entry> entries, boolean truncated) {
    }

    /**
     * Types whose simple name matches {@code prefix}, best first.
     *
     * <h3>Subsequence, not just prefix — because the consumer matches that way</h3>
     *
     * <p>This filtered with {@code startsWith} while {@link com.crystalgui.core.search.SearchMatcher},
     * which ranks the list afterwards, matches scattered characters. An index that pre-filters more
     * strictly than the thing consuming it is worse than no index: typing {@code CgRenderer} found nothing,
     * because <em>nothing</em> starts with that — {@code CgBatchRenderer}, {@code CgQuadRenderer} and
     * {@code CgTextRenderer} were never handed over to be ranked. The one row that did appear had survived
     * from an earlier, shorter query's batch, which made it look like an index with a single entry in it.
     * </p>
     *
     * <p>The two filters have to agree, and this is the side that moves: the matcher's rule is the one the
     * user can see working on every other row in the popup.</p>
     *
     * <h3>A cheap test first, because this runs over every type on the machine</h3>
     *
     * <p>{@code SearchMatcher}'s subsequence tier is a small dynamic program — right for ranking forty
     * candidates and far too much for fifty thousand on every keystroke. {@link #isSubsequence} is a single
     * linear scan with no allocation, and it is <b>exactly the same predicate</b>: anything it rejects the
     * DP would also reject, so nothing is lost by asking the cheap question first.</p>
     */
    Match matching(String prefix) {
        if (prefix == null || prefix.isEmpty()) return new Match(List.of(), false);
        ensureBuilt();
        String needle = prefix.toLowerCase(Locale.ROOT);
        List<Entry> prefixed = new ArrayList<>();
        List<Entry> scattered = new ArrayList<>();
        for (Entry entry : entries) {
            String candidate = entry.simpleName().toLowerCase(Locale.ROOT);
            if (candidate.startsWith(needle)) prefixed.add(entry);
            else if (isSubsequence(needle, candidate)) scattered.add(entry);
            // Bounded on the WHOLE haystack, not per bucket: a one-character query matches most of the
            // JDK, and collecting all of it to then keep forty is work nobody reads.
            if (prefixed.size() + scattered.size() >= MAX_RESULTS * 8) break;
        }

        // A real prefix hit beats any scattered one, whatever their lengths -- the same tier ordering
        // SearchMatcher applies, kept here so this list arrives already in the order the ranking will
        // agree with rather than being reshuffled a step later.
        prefixed.sort(BY_BREVITY);
        scattered.sort(BY_BREVITY);
        List<Entry> hits = new ArrayList<>(prefixed);
        hits.addAll(scattered);

        boolean truncated = hits.size() > MAX_RESULTS;
        return new Match(truncated ? new ArrayList<>(hits.subList(0, MAX_RESULTS)) : hits, truncated);
    }

    /** Shortest first, then alphabetical — a total order, so the list cannot permute between keystrokes. */
    private static final Comparator<Entry> BY_BREVITY =
            Comparator.comparingInt((Entry e) -> e.simpleName().length()).thenComparing(Entry::simpleName);

    /** Whether every character of {@code needle} appears in {@code candidate}, in order. Both lower-case. */
    private static boolean isSubsequence(String needle, String candidate) {
        if (needle.length() > candidate.length()) return false;
        int at = 0;
        for (int i = 0; i < candidate.length() && at < needle.length(); i++) {
            if (candidate.charAt(i) == needle.charAt(at)) at++;
        }
        return at == needle.length();
    }

    private synchronized void ensureBuilt() {
        if (entries != null) return;
        List<Entry> built = new ArrayList<>();
        // THE PLATFORM FIRST, and first because of the cap: if anything is going to be dropped it must not
        // be java.lang.
        scanPlatform(built);
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

    /**
     * The JDK's own types, which are not on the classpath at all.
     *
     * <h3>The bug this exists for</h3>
     *
     * <p>Typing {@code System} offered {@code SystemClock}, {@code SystemUtils} and six more from log4j —
     * and not {@code java.lang.System}. The index scanned {@link HostClasspath#detect()}, which is
     * classpath entries, and since Java 9 the platform classes are in the <b>jrt image</b> rather than in
     * any jar on it. So the index had never contained {@code List}, {@code Map} or {@code String} either;
     * the omission was invisible because the only thing it feeds is unimported-type completion, and every
     * type anyone actually reached for was already imported.</p>
     *
     * <p>ECJ resolves those types perfectly well, which is what made this hard to see: the analyser is
     * given {@code includeRunningVMBootclasspath = true}, a different mechanism entirely.</p>
     *
     * <h3>Java 8 has no jrt, and says so by throwing</h3>
     *
     * <p>{@code FileSystems.getFileSystem("jrt:/")} raises {@code ProviderNotFoundException} on 8, which is
     * the honest signal to fall back to {@code sun.boot.class.path}. Written as a catch rather than a
     * version check because the version check would be a second way of asking the same question, and the
     * two can disagree on a stripped or modular runtime.</p>
     */
    private static void scanPlatform(List<Entry> into) {
        try {
            FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path modules = jrt.getPath("/modules");
            try (Stream<Path> walk = Files.walk(modules)) {
                walk.filter(Files::isRegularFile).forEach(path -> {
                    if (into.size() >= MAX_TYPES) return;
                    // /modules/java.base/java/lang/System.class -> java/lang/System.class
                    if (path.getNameCount() < 3) return;
                    add(path.subpath(2, path.getNameCount()).toString().replace('\\', '/'), into);
                });
            }
            return;
        } catch (Exception noJrt) {
            // Java 8, or a runtime with no jrt provider. Fall through.
        }
        String boot = System.getProperty("sun.boot.class.path");
        if (boot == null) return;
        for (String element : boot.split(File.pathSeparator)) {
            if (into.size() >= MAX_TYPES) break;
            try {
                File file = new File(element);
                if (file.isFile()) scanArchive(file, into);
            } catch (IOException | RuntimeException unreadable) {
                System.err.println("[crystalgui] type index skipped boot entry " + element
                        + ": " + unreadable);
            }
        }
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
        // NOT FOR USERS. `sun.` and anything with an `internal` package segment is implementation detail
        // that the compiler will refuse or warn about; offering it is offering a mistake. IntelliJ hides
        // the same set. Filtered here rather than per-source so the classpath gets it too.
        if (binary.startsWith("sun.") || binary.contains(".internal.")) return;
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
