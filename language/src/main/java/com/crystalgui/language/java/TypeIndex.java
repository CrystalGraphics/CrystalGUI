package com.crystalgui.language.java;

import com.crystalgui.text.SimilarNames;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
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
public final class TypeIndex {

    // ── VISIBILITY NOTE ────────────────────────────────────────────────────────────────────────
    //
    // This was package-private, and the widening is deliberate rather than incidental: "which types are
    // on the classpath" stopped being a Java-only question when a .js file gained `Java.type("a.b.C")`
    // completion, and the answer is the same scan of the same classpath. A second index for JavaScript
    // would be fifty thousand entries and one filesystem walk, duplicated, to answer identically.
    //
    // Only the query surface is public -- `matching`, `kindOf`, `Entry`, `Match`, `Kind`. The scanning,
    // the caches and the class-file reading stay package-private, so a caller cannot reach past the
    // question into how it is answered.

    /**
     * One type: enough to draw a row, write the import, and later find its bytes.
     *
     * <p>{@code container} is where the class file lives — {@code jar:}, {@code dir:} or {@code jrt:} and a
     * root. The <b>same String instance</b> is shared by every entry from one archive, so this costs a
     * pointer per entry rather than a copy, which matters at fifty thousand of them.</p>
     */
    public record Entry(String simpleName, String packageName, String container) {
        public String qualifiedName() {
            return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        }

        String classFilePath() {
            return qualifiedName().replace('.', '/') + ".class";
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

    /** Qualified name to entry, so the hierarchy walk can find an ancestor outside its own container. */
    private final java.util.Map<String, Entry> byName = new java.util.HashMap<>();

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
    public record Match(List<Entry> entries, boolean truncated) {
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
    /**
     * A view of this index that hides what a policy refuses.
     *
     * <p>A <b>view</b> and not a copy, because the index is shared per classpath and holds fifty thousand
     * entries: filtering it would mean a second scan and a second fifty thousand, and the policy is a
     * property of the <em>asker</em> rather than of the classpath. The filter is applied to the answer, so
     * two callers with different policies read the same index.</p>
     *
     * <p>A {@link Predicate} rather than the policy type, so this package stays free of {@code language.run}
     * — the same reason every crossing into an engine is a JDK type.</p>
     */
    public Filtered filtered(Predicate<String> allowsClass) {
        return new Filtered(this, allowsClass);
    }

    /** @see #filtered */
    public static final class Filtered {

        private final TypeIndex index;
        private final Predicate<String> allowsClass;

        private Filtered(TypeIndex index, Predicate<String> allowsClass) {
            this.index = index;
            this.allowsClass = allowsClass;
        }

        /**
         * As {@link TypeIndex#matching}, minus what the policy refuses.
         *
         * <p>{@code truncated} is carried through unchanged: it says the index had more to give, which is
         * still true after filtering and is what makes the consumer ask again as the query narrows.</p>
         */
        public Match matching(String prefix) {
            Match all = index.matching(prefix);
            if (allowsClass == null) return all;
            List<Entry> kept = new ArrayList<>(all.entries().size());
            for (Entry entry : all.entries()) {
                if (allowsClass.test(entry.qualifiedName())) kept.add(entry);
            }
            return new Match(kept, all.truncated());
        }

        public Kind kindOf(Entry entry) {
            return index.kindOf(entry);
        }
    }

    public Match matching(String prefix) {
        if (prefix == null || prefix.isEmpty()) return new Match(List.of(), false);
        ensureBuilt();
        String needle = prefix.toLowerCase(Locale.ROOT);
        List<Entry> prefixed = new ArrayList<>();
        List<Entry> scattered = new ArrayList<>();
        for (Entry entry : entries) {
            String candidate = entry.simpleName().toLowerCase(Locale.ROOT);
            if (candidate.startsWith(needle)) {
                if (prefixed.size() < MAX_RESULTS) prefixed.add(entry);
            } else if (scattered.size() < MAX_RESULTS && isSubsequence(needle, candidate)) {
                scattered.add(entry);
            }
            // BOUNDED PER BUCKET, and the scan stops only when BOTH are full.
            //
            // A single combined bound truncated by ALPHABET rather than by quality: entries are walked in
            // name order, so a cheap query whose scattered matches fill the quota early ends the scan
            // before the letter the user actually typed. `CgText` returned four rows and none of them was
            // CgTexture -- a plain prefix hit, sitting past the cut-off because a few hundred unrelated
            // subsequence matches had already used it up. `CgRenderer` was unaffected only because its
            // longer, rarer character run matched almost nothing on the way.
            if (prefixed.size() >= MAX_RESULTS && scattered.size() >= MAX_RESULTS) break;
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

    /**
     * Qualified names of types a keystroke or two away from {@code simpleName} — "did you mean".
     *
     * <p>A separate walk from {@link #matching}, not a mode of it: that one asks "what starts like this",
     * this asks "what is this a misspelling of", and {@code Strimg} answers the second and not the first.
     * The distance function cuts off past the tolerance, and the length pre-check in {@code SimilarNames}
     * skips most of the index before it is ever computed, so this stays affordable over fifty thousand
     * entries — it runs on a hover, not a keystroke, and never on a name that resolved.</p>
     *
     * <p>Two packages offering the same simple name both come back, in a fixed order, so the correction
     * can offer each and say which is which; ranking is by simple name and the qualified names then follow
     * their simple name's rank.</p>
     */
    List<String> similar(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) return List.of();
        ensureBuilt();
        Set<String> simple = new LinkedHashSet<>();
        for (Entry entry : entries) simple.add(entry.simpleName());
        List<String> rankedSimple = SimilarNames.rank(simpleName, simple);
        if (rankedSimple.isEmpty()) return List.of();

        List<String> qualified = new ArrayList<>();
        for (String candidate : rankedSimple) {
            for (Entry entry : entries) {
                if (entry.simpleName().equals(candidate)) qualified.add(entry.qualifiedName());
            }
        }
        return qualified;
    }

    // ── What a type IS, read from its access flags ──────────────────────────────────────────────

    /**
     * The kind and abstractness of {@code entry}, read from its class file.
     *
     * <h3>Lazily, and only for what is being shown</h3>
     *
     * <p>The path spells the NAME and says nothing about what the type is — everything else is in the access
     * flags, and reading those means opening the file. Doing it during the scan would mean opening fifty
     * thousand of them, which is tens of seconds; doing it for the forty rows a query returns is a handful
     * of milliseconds, and the answer is memoised because the same names come back on every keystroke.</p>
     *
     * <p>Through ASM, which is already here for the mapping layer, rather than by hand-parsing the constant
     * pool to reach one {@code u2}. {@code SKIP_CODE} and friends are unnecessary — {@code getAccess} and
     * {@code getSuperName} read the header without visiting anything.</p>
     *
     * <p><b>Failure is silent and answers CLASS.</b> An unreadable entry is a jar that changed under us or a
     * malformed class, and the right response is the majority answer rather than no row at all: the name is
     * still correct and still worth offering.</p>
     */
    public Kind kindOf(Entry entry) {
        return kinds.computeIfAbsent(entry.qualifiedName(), name -> readKind(entry));
    }

    /** What the icon layer needs: what it is, and whether it is abstract. */
    public record Kind(SymbolKind kind, boolean isAbstract) {
    }

    private static final Kind PLAIN_CLASS = new Kind(SymbolKind.CLASS, false);

    private final java.util.concurrent.ConcurrentHashMap<String, Kind> kinds =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Kind readKind(Entry entry) {
        byte[] bytes = bytesOf(entry);
        if (bytes == null) return PLAIN_CLASS;
        try {
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
            int access = reader.getAccess();
            boolean isAbstract = (access & org.objectweb.asm.Opcodes.ACC_ABSTRACT) != 0;

            // ORDER MATTERS: an annotation is also an interface, and an enum is also a class, so the most
            // specific flag has to be tested first. Reversed, every annotation would draw as an interface.
            if ((access & org.objectweb.asm.Opcodes.ACC_ANNOTATION) != 0) {
                return new Kind(SymbolKind.ANNOTATION, false);
            }
            if ((access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0) {
                // An interface is abstract by definition; saying so would put the abstract mark on every
                // one of them, which is noise rather than information.
                return new Kind(SymbolKind.INTERFACE, false);
            }
            if ((access & org.objectweb.asm.Opcodes.ACC_ENUM) != 0) {
                return new Kind(SymbolKind.ENUM, false);
            }
            String superName = reader.getSuperName();
            if ("java/lang/Record".equals(superName)) return new Kind(SymbolKind.RECORD, false);
            if (isThrowable(superName, entry.container(), 0)) {
                return new Kind(SymbolKind.EXCEPTION, isAbstract);
            }
            return new Kind(SymbolKind.CLASS, isAbstract);
        } catch (RuntimeException malformed) {
            return PLAIN_CLASS;
        }
    }

    /**
     * Whether {@code internalName}'s hierarchy reaches {@code Throwable}.
     *
     * <p>Walked rather than tested against a list of names, because the interesting exceptions are the ones
     * a project declares itself and those are three or four hops from anything nameable. Bounded, because a
     * malformed or circular hierarchy must not hang a keystroke — and because past a handful of hops the
     * answer is essentially always no.</p>
     *
     * <p>Each ancestor is looked for in the SAME container first and then across the index, since a
     * project's exception hierarchy is normally in one place and the JDK's roots are not.</p>
     */
    private boolean isThrowable(String internalName, String container, int depth) {
        if (internalName == null || depth > MAX_HIERARCHY_HOPS) return false;
        if ("java/lang/Throwable".equals(internalName)) return true;
        if ("java/lang/Object".equals(internalName)) return false;

        String binary = internalName.replace('/', '.');
        byte[] bytes = bytesFrom(container, internalName + ".class");
        if (bytes == null) {
            Entry located = byName.get(binary);
            if (located == null) return false;
            bytes = bytesOf(located);
            container = located.container();
        }
        if (bytes == null) return false;
        try {
            return isThrowable(new org.objectweb.asm.ClassReader(bytes).getSuperName(), container, depth + 1);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** Deep enough for a project's own exception hierarchy, shallow enough to never be felt. */
    private static final int MAX_HIERARCHY_HOPS = 8;

    private byte[] bytesOf(Entry entry) {
        return bytesFrom(entry.container(), entry.classFilePath());
    }

    /** Reads one class file out of whichever kind of container it came from. */
    private static byte[] bytesFrom(String container, String classFilePath) {
        if (container == null) return null;
        try {
            if (container.startsWith("jar:")) {
                File archive = new File(container.substring(4));
                if (!archive.isFile()) return null;
                try (ZipFile zip = new ZipFile(archive)) {
                    ZipEntry found = zip.getEntry(classFilePath);
                    if (found == null) return null;
                    try (java.io.InputStream in = zip.getInputStream(found)) {
                        return in.readAllBytes();
                    }
                }
            }
            if (container.startsWith("dir:")) {
                Path file = Path.of(container.substring(4)).resolve(classFilePath);
                return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            }
            if (container.startsWith("jrt:")) {
                FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
                Path file = jrt.getPath(container.substring(4), classFilePath);
                return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return null;
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
        for (Entry entry : built) byName.putIfAbsent(entry.qualifiedName(), entry);
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
                    add(path.subpath(2, path.getNameCount()).toString().replace('\\', '/'), into,
                            "jrt:/modules/" + path.getName(1));
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
        String container = "jar:" + file.getPath();
        try (ZipFile archive = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> zipEntries = archive.entries();
            while (zipEntries.hasMoreElements() && into.size() < MAX_TYPES) {
                add(zipEntries.nextElement().getName(), into, container);
            }
        }
    }

    private static void scanDirectory(Path root, List<Entry> into) throws IOException {
        String container = "dir:" + root;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                if (into.size() >= MAX_TYPES) return;
                add(root.relativize(path).toString().replace(File.separatorChar, '/'), into, container);
            });
        }
    }

    /** Turns {@code java/util/ArrayList.class} into an entry, or ignores it. */
    private static void add(String path, List<Entry> into, String container) {
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
        into.add(new Entry(simple, packageName, container));
    }
}
