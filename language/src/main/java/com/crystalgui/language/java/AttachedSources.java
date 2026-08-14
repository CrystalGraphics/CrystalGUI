package com.crystalgui.language.java;

import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The <b>source</b> behind a classpath symbol, parsed and kept — so a declaration can be quoted rather
 * than reassembled however far away it was written.
 *
 * <h3>Why a signature wants this</h3>
 *
 * <p>{@link JavaSignatures} renders a declaration two ways, and only one of them is any good: a symbol
 * declared in the file being edited is <b>quoted</b> from the text its author wrote, and everything else
 * was <b>assembled</b> from the binding in a layout of our own invention. The assembled path existed for
 * exactly one reason — a classpath symbol had no source to quote — and every layout rule in it is there
 * to reinvent a wrapping the quoted path gets for free.</p>
 *
 * <p>So this is not a feature beside that one. With the source in hand {@code java.util.List.add} is
 * quoted out of {@code src.zip} exactly as a method three lines up is quoted out of the buffer, with the
 * JDK authors' own wrapping and their parameter names: {@code boolean add(E e)} rather than
 * {@code add(E)}. IntelliJ shows {@code println(String x)} for precisely this reason.</p>
 *
 * <p>Finding the archives and decoding a file is {@link SourceArchives}, which names no JDT type and can
 * therefore be tested directly. This class is the half that cannot: the parse.</p>
 *
 * <h3>What is cached, and why each level has to be</h3>
 *
 * <p>Analysis re-runs on a debounce while typing, so anything built per-analysis is built per-keystroke.
 * The archive index and the decoded text are held by {@code SourceArchives}; the parsed <em>unit</em> is
 * held here, and it is the expensive one — resolving {@code ArrayList} pulls in its whole supertype
 * graph. Measured cold at 27–271 ms for a first hover of a given type and 0 ms after.</p>
 *
 * <p>Failures are cached too. A classpath with no sources attached is the ordinary case rather than an
 * error, and re-deriving "still nothing" on every hover costs the same as succeeding.</p>
 */
final class AttachedSources {

    /**
     * Keyed by the classpath, because that is what the answer depends on.
     *
     * <p>Static rather than per-analysis for the reason above — an analysis is per-keystroke — and
     * per-classpath rather than global because two hosts genuinely have different sources attached. One
     * entry in practice: a session's classpath does not change.</p>
     */
    private static final Map<List<String>, AttachedSources> INSTANCES = new ConcurrentHashMap<>();

    /** Enough sessions to cover a host that reconfigures, and few enough never to be a leak. */
    private static final int MAX_INSTANCES = 4;

    private final SourceArchives archives;
    private final String[] classpath;
    /** Parsed units by top-level type name — the expensive level. */
    private final Map<String, Attached> units = new LinkedHashMap<>();

    /**
     * How many attached units to keep parsed.
     *
     * <p>A reader hovers a handful of types in a sitting and returns to them; past that, holding a
     * resolved {@code CompilationUnit} costs real memory because it retains its whole binding graph.</p>
     */
    private static final int MAX_UNITS = 24;

    private AttachedSources(SourceArchives archives, String[] classpath) {
        this.archives = archives;
        this.classpath = classpath;
    }

    /** One instance per classpath, built on first use. */
    static AttachedSources forClasspath(List<String> classpath) {
        List<String> entries = classpath == null ? new ArrayList<>() : new ArrayList<>(classpath);
        AttachedSources found = INSTANCES.get(entries);
        if (found != null) return found;
        if (INSTANCES.size() >= MAX_INSTANCES) INSTANCES.clear();
        AttachedSources built = new AttachedSources(SourceArchives.over(entries),
                entries.toArray(new String[0]));
        AttachedSources raced = INSTANCES.putIfAbsent(entries, built);
        return raced == null ? built : raced;
    }

    /** A parsed attached unit and the text it was parsed from, which a quote slices. */
    static final class Attached {
        final CompilationUnit unit;
        final String text;

        Attached(CompilationUnit unit, String text) {
            this.unit = unit;
            this.text = text;
        }
    }

    /**
     * The attached unit declaring {@code topLevelName}, or null when there is no source for it.
     *
     * <p>Synchronized because the cache is a plain map and analysis runs off the UI thread: two hovers
     * in flight would otherwise interleave inside {@link LinkedHashMap}. The lock is held across the
     * parse, which serialises two <em>different</em> types being resolved at once — acceptable, since
     * the alternative is parsing the same unit twice and keeping whichever finished last.</p>
     */
    synchronized Attached unitFor(String topLevelName) {
        if (topLevelName == null || topLevelName.isEmpty()) return null;
        if (units.containsKey(topLevelName)) return units.get(topLevelName);

        SourceArchives.Found found = archives.find(topLevelName);
        Attached attached = found == null ? null : parse(topLevelName, found);
        if (units.size() >= MAX_UNITS) units.clear();
        units.put(topLevelName, attached);
        return attached;
    }

    private Attached parse(String topLevelName, SourceArchives.Found found) {
        try {
            CompilationUnit unit = parseAttached(topLevelName, found);
            return unit == null ? null : new Attached(unit, found.text);
        } catch (RuntimeException | LinkageError refused) {
            // A source archive this band cannot read -- a JDK 21 src.zip under band 8's JDT is the
            // obvious case, and a configuration a user can genuinely be in. Nothing is broken by it:
            // the caller falls back to the assembled form, which is what it had before any source was
            // attached at all.
            return null;
        }
    }

    /**
     * The attached source, resolved against <b>the same classpath the primary analysis used</b>.
     *
     * <p>That is what makes {@code findDeclaringNode(bindingKey)} work across the two parses: a JDT
     * binding key is derived from the signature, so the key the editor's unit reports for
     * {@code List.add} is character-for-character the key this unit reports for its declaration — but
     * only if both resolved the same {@code List}.</p>
     */
    private CompilationUnit parseAttached(String topLevelName, SourceArchives.Found found) {
        ASTParser parser = ASTParser.newParser(EcjOptions.jlsLevel());
        parser.setSource(found.text.toCharArray());
        parser.setUnitName(topLevelName.replace('.', '/') + ".java");
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        // RECOVERY, for the same reason the primary parse uses it and a sharper one here: a platform
        // source is deliberately read at an older language than it was written in (see below), so a body
        // full of `var` is expected rather than exceptional. Recovery keeps the HEADER -- the only part
        // a signature quotes -- parsed and resolved anyway.
        parser.setStatementsRecovery(true);
        parser.setBindingsRecovery(true);
        parser.setCompilerOptions(compilerOptions(found.platform));
        parser.setEnvironment(classpath, new String[0], new String[0], true);
        Object parsed = parser.createAST(null);
        return parsed instanceof CompilationUnit ? (CompilationUnit) parsed : null;
    }

    /**
     * <b>The JDK's own sources are read at Java 8, and everything else at the band's ceiling.</b>
     *
     * <h3>Why the platform is the exception, and why 8 is not a guess</h3>
     *
     * <p>A file out of {@code src.zip} declares {@code package java.util} — a package that
     * {@code java.base} already owns. Parsed at any compliance from 9 up it lands in the <em>unnamed</em>
     * module and the compiler refuses the clash outright: <i>"The package java.util conflicts with a
     * package accessible from another module: java.base"</i>. That single error is not local to the
     * package line — it poisons resolution for the whole unit, so every type reference in the file
     * becomes unresolvable. {@code java.util.List} still quotes, and quotes with
     * {@code SequencedCollection} in the plain type colour rather than the interface one, three lines
     * under an editor drawing that same word correctly.</p>
     *
     * <p>Compliance 8 is the last level with <b>no module system at all</b>, so the clash cannot arise —
     * which makes it a derived constant rather than a tuned one. It is also strictly better here rather
     * than a trade: at 9+ <em>no</em> platform type resolves its references, and at 8 all of them do
     * except those whose declaration uses syntax newer than Java 8. Those fall back to the assembled
     * form, which is what they would have had anyway.</p>
     *
     * <p><b>The rule keys on which archive the bytes came from, not on the package name.</b> A
     * {@code java.}-prefixed fixture inside an ordinary {@code -sources.jar} is not a platform source and
     * would be mis-parsed by a name test; provenance is a fact {@link SourceArchives} already has.</p>
     *
     * <p>An ordinary library keeps the ceiling, because it has no such clash and may genuinely be
     * written in a newer language than the script using it.</p>
     */
    private static Map<String, String> compilerOptions(boolean platform) {
        return EcjOptions.forLevel(platform ? LAST_PRE_MODULE_LEVEL : EcjOptions.jlsLevel());
    }

    /** Modules arrived in 9, so 8 is the newest language with nothing for a package to conflict with. */
    private static final int LAST_PRE_MODULE_LEVEL = 8;
}
