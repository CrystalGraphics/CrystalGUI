package com.crystalgui.language.java.assist;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.language.java.classpath.ClassFileParameterNames;

import javax.annotation.Nullable;

import com.crystalgui.language.java.ecj.EcjOptions;
import com.crystalgui.text.lang.ProjectSources;
import com.crystalgui.text.lang.ProjectSourcesRegistry;
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
public final class AttachedSources {

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

    /**
     * Parameter names read straight off the class file — the fallback when no source is attached.
     *
     * <p>Here rather than anywhere else because this class already <em>is</em> "where to look when the
     * unit being analysed does not declare the symbol", and it is already cached per classpath. The two
     * answer the same question from opposite ends: source attachment quotes the declaration somebody
     * wrote, and this reads the names the compiler kept. Quoting wins where both exist, since it carries
     * the javadoc and the real layout as well.</p>
     */
    private final ClassFileParameterNames parameterNames;

    private AttachedSources(SourceArchives archives, String[] classpath) {
        this.archives = archives;
        this.classpath = classpath;
        this.parameterNames = ClassFileParameterNames.forClasspath(java.util.Arrays.asList(classpath));
    }

    /**
     * The declared names of a classpath method's parameters, or null.
     *
     * @param erasedParameters each parameter's erased type in binary form — the spelling the class
     *                         file uses, so an array is {@code java.lang.String[]} and a type variable
     *                         has already become its bound
     * @see ClassFileParameterNames
     */
    @Nullable
    public List<String> parameterNamesOf(@Nullable String ownerBinaryName,
                                         @Nullable String methodName,
                                         @Nullable List<String> erasedParameters) {
        return parameterNames.namesOf(ownerBinaryName, methodName, erasedParameters);
    }

    /**
     * One instance per classpath, built on first use.
     *
     * <p><b>Keyed on where the JDK's sources are as well as on the classpath</b>, because M13 §25.5 can
     * make that answer change mid-session: a fetch finishes and points
     * {@link JdkSourceExtract#SOURCES_PROPERTY} at an extract that was not there when this was built.
     * Without the property in the key the download completes, reports success, and every hover keeps
     * showing the assembled form until the game is restarted — which reads as the fetch having silently
     * failed. Expressing the dependency in the key is what makes the install take effect rather than
     * needing something to remember to invalidate a cache it does not own.</p>
     */
    public static AttachedSources forClasspath(List<String> classpath) {
        List<String> entries = classpath == null ? new ArrayList<>() : new ArrayList<>(classpath);
        List<String> key = new ArrayList<>(entries);
        // NOT a classpath entry -- a key component, and the NUL prefix is what stops it colliding with a
        // real path, since no filesystem permits one in a name.
        //
        // Spelled as an ESCAPE, and that is not cosmetic: this was a RAW NUL BYTE in the source. Git
        // classifies a file containing one as BINARY, so it gets no diff, no blame and no review of
        // anything else in it ever again -- while being invisible in every editor, so nothing says why.
        key.add("\u0000jdk-sources=" + System.getProperty(JdkSourceExtract.SOURCES_PROPERTY, ""));

        AttachedSources found = INSTANCES.get(key);
        if (found != null) return found;
        if (INSTANCES.size() >= MAX_INSTANCES) INSTANCES.clear();
        AttachedSources built = new AttachedSources(SourceArchives.over(entries),
                entries.toArray(new String[0]));
        AttachedSources raced = INSTANCES.putIfAbsent(key, built);
        return raced == null ? built : raced;
    }

    /**
     * A parsed attached unit and the text it was parsed from, which a quote slices.
     *
     * @param workspacePath where the text came from when it came from the WORKSPACE, else null. It is
     *                      what lets a declaration site name a project file instead of a library, and it
     *                      is a plain string because {@code com.crystalgui.fs} is not parent-first on the
     *                      band loader. @see com.crystalgui.text.lang.DeclarationSite#inProject
     */
    record Attached(CompilationUnit unit, String text, String workspacePath) {
    }

    /**
     * The whole source of a top-level type, or null when nothing has it.
     *
     * <p><b>Through the same cache every position was computed against</b>, which is the point of it
     * being here rather than a second call into {@link SourceArchives}. A {@code DeclarationSite} into a
     * library file carries rows and columns that are legal against exactly one string; anything serving
     * that file to a reader has to serve <em>that</em> string, or the caret lands on the wrong line in a
     * file nobody can edit to correct it. One read, two consumers.</p>
     *
     * <p>Public where {@link #unitFor} is not, because the text is the only part a viewer wants: a
     * parsed unit is this class's own business and costs a parse the reader has no use for.</p>
     */
    @Nullable
    public String textOf(String topLevelName) {
        if (topLevelName == null || topLevelName.isEmpty()) return null;
        SourceArchives.Found found = archives.find(topLevelName);
        return found == null ? null : found.text;
    }

    /**
     * Whether that source came out of the JDK's own {@code src.zip}.
     *
     * <p><b>Asked of the ARCHIVE, never of the package name</b> — the rule {@link #compilerOptions}
     * already turns on, and the reason it is spelled that way there: a {@code java.}-prefixed fixture
     * inside an ordinary {@code -sources.jar} is not a platform source, and treating it as one would
     * compile somebody's own code at Java 8 for the sake of its package.</p>
     *
     * <p>The answer decides one thing and it is not cosmetic. A file declaring {@code package java.util}
     * parsed at compliance 9 or above lands in the unnamed module against a package {@code java.base}
     * owns, and that single error poisons resolution for the <em>entire unit</em> — so a viewer opening
     * a JDK class under the band's ordinary ceiling would show it fully underlined with nothing
     * resolvable, which is worse than opening it with no services at all.</p>
     */
    public boolean isPlatformSource(String topLevelName) {
        if (topLevelName == null || topLevelName.isEmpty()) return false;
        SourceArchives.Found found = archives.find(topLevelName);
        return found != null && found.platform;
    }

    /**
     * Index every archive now, so the first lookup that misses does not pay for all of them.
     *
     * <p>For a warm-up thread, and the only honest way to warm this — see {@link SourceArchives#warm}
     * for why asking about a type cannot do it.</p>
     */
    public void warm() {
        archives.warm();
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

        // THE WORKSPACE FIRST, and never from the cache below.
        //
        // A project file is the one source here that CHANGES: an archive's text is fixed for the life of
        // the classpath, which is what makes caching it by name correct, while another editor's buffer
        // moves on every keystroke. Caching this would pin a sibling as it was when some earlier hover
        // happened to ask, so an author would fix a signature in one file and keep reading the old one in
        // another -- the same trap `ScriptNameEnvironment` documents about never caching a source unit.
        //
        // It outranks the archives for the reason the name environment does: a workspace file beats a jar
        // publishing the same name, or adding a dependency would silently change which source you are
        // shown for your own code.
        // ONE VIEW for both questions, so a provider registered between them cannot answer half of it.
        ProjectSources project = ProjectSourcesRegistry.view();
        String projectPath = project.pathOf(topLevelName);
        if (projectPath != null) {
            String text = project.sourceOf(topLevelName);
            // Null is "not read yet", not "no such file" -- so fall through rather than answering with
            // nothing, and the next analysis after the read lands will find it.
            if (text != null) return parseProject(topLevelName, text, projectPath);
        }

        if (units.containsKey(topLevelName)) return units.get(topLevelName);

        SourceArchives.Found found = archives.find(topLevelName);
        Attached attached = found == null ? null : parse(topLevelName, found);
        if (units.size() >= MAX_UNITS) units.clear();
        units.put(topLevelName, attached);
        return attached;
    }

    /**
     * Parses one attached unit now, so the FIRST hover does not pay for the machinery.
     *
     * <h3>There is a one-time cost under the per-class one, and it is most of a first hover</h3>
     *
     * <p>Measured by hovering five CrystalGraphics classes in a row, whose sources are bundled in their
     * jar:</p>
     *
     * <pre>
     *   CgFrameBufferFormat  18,786 chars  125,713us   6.7us/char   &lt;- first
     *   CgRenderPipeline     19,691 chars   53,689us   2.7us/char
     *   CgShaderBindings     12,665 chars   29,475us   2.3us/char
     *   CgFrameData           6,903 chars   24,378us   3.5us/char
     *   CgBlendState          5,755 chars   17,743us   3.1us/char
     * </pre>
     *
     * <p>Two files of nearly identical size, 125.7ms against 53.7ms. The difference is not the file: it is
     * everything JDT builds on its first attached parse — the AST parser, the name environment over this
     * classpath, and its internal caches — and it is about 70ms that the first hover of a session pays and
     * no later one does.</p>
     *
     * <p>Warmed with a type every classpath has and whose source is small, so the warm builds the
     * machinery rather than re-parsing something large. A classpath with no attached sources finds
     * nothing and warms nothing, which is correct: there is no cost there to move.</p>
     *
     * <p><b>Off the frame thread, by its caller.</b> {@code JavaLanguage} already runs a MIN_PRIORITY
     * daemon for exactly this kind of speculation and states the trade there — work that is free if
     * nobody ever hovers.</p>
     */
    public void warmParser() {
        unitFor("java.lang.Object");
    }

    /**
     * A workspace file, parsed the way an attached library source is.
     *
     * <p>At the band's ceiling rather than at 8: the platform exception below exists for {@code src.zip}
     * declaring a package {@code java.base} already owns, and a project file is ordinary code that may
     * legitimately use whatever the band compiles.</p>
     */
    private Attached parseProject(String topLevelName, String text, String workspacePath) {
        try {
            CompilationUnit unit = parseSource(topLevelName, text, false);
            return unit == null ? null : new Attached(unit, text, workspacePath);
        } catch (RuntimeException | LinkageError refused) {
            return null;
        }
    }

    private Attached parse(String topLevelName, SourceArchives.Found found) {
        try {
            // THE PARSE, PER CLASS. Quoting a declaration means parsing the whole compilation unit it
            // lives in, and the first hover on a class with attached source was measured at 150-167ms.
            // Reported per class so the SHARED setup and the per-class parse can be told apart: if the
            // first is dear and later ones are cheap, a warm-up fixes the first hover; if every one is
            // dear, nothing can be warmed and the answer has to be that the popup does not wait for it.
            long timed = FrameProfile.begin();
            CompilationUnit unit = parseAttached(topLevelName, found);
            FrameProfile.step(timed, "attached.parse " + topLevelName
                    + " (" + found.text.length() + " chars)");
            return unit == null ? null : new Attached(unit, found.text, null);
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
        return parseSource(topLevelName, found.text, found.platform);
    }

    private CompilationUnit parseSource(String topLevelName, String text, boolean platform) {
        ASTParser parser = ASTParser.newParser(EcjOptions.jlsLevel());
        parser.setSource(text.toCharArray());
        parser.setUnitName(topLevelName.replace('.', '/') + ".java");
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        // RECOVERY, for the same reason the primary parse uses it and a sharper one here: a platform
        // source is deliberately read at an older language than it was written in (see below), so a body
        // full of `var` is expected rather than exceptional. Recovery keeps the HEADER -- the only part
        // a signature quotes -- parsed and resolved anyway.
        parser.setStatementsRecovery(true);
        parser.setBindingsRecovery(true);
        parser.setCompilerOptions(compilerOptions(platform));
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
