package com.crystalgui.language.java;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.ResourceContentProvider;
import com.crystalgui.fs.ResourceRegistry;
import com.crystalgui.language.java.assist.AttachedSources;
import com.crystalgui.language.java.classpath.HostClasspath;

import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.map.MappingSet;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.SymbolInfo;

import javax.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the text behind {@code library://} — what the viewer shows for a class the workspace lacks.
 *
 * <h3>Host-side, and that is not a detail</h3>
 *
 * <p>{@code ResourceRegistry} and {@code ResourceContentProvider} live in {@code com.crystalgui.fs},
 * which {@code EngineClassLoader} does <b>not</b> delegate to its parent — so a class the engine band
 * loads may not name them, and {@code BandLoadedCodeAvoidsWorkspaceTypesTest} fails the commit that
 * makes one. This class names no engine type at all: {@code AttachedSources} is reached through
 * {@code JavaLanguage}'s ordinary host-side surface, and what crosses the bridge is a {@link String}.</p>
 *
 * <h3>The same read the positions were computed against</h3>
 *
 * <p>A {@code DeclarationSite} into a library file carries rows and columns that are legal against
 * exactly one string. {@link AttachedSources#textOf} is that string, cached per classpath, and both the
 * engine computing the site and this serving the document go through it — so the caret lands on the
 * identifier rather than near it. Reading the archive a second way here would be a second answer to a
 * question that has one.</p>
 *
 * <h3>Source first, bytecode second</h3>
 *
 * <p>A class with no attached source is decompiled instead, and the two are not interchangeable: a
 * decompiler reconstructs, so its output carries <b>no comments at all</b> and local names only where a
 * {@code LocalVariableTable} survived. That is why source is preferred wherever it exists rather than
 * simply always decompiling, and why the reader is told which they are looking at.</p>
 *
 * <p><b>Decompiled output is cached</b>, keyed by name. A decompile is hundreds of milliseconds and the
 * same class is opened repeatedly — by a second Ctrl+B into it, and by every layout restore. A failure
 * is cached too, as a sentinel: CFR meeting bytecode it cannot read will not read it on the next click
 * either, and retrying per click turns one slow answer into a stutter.</p>
 */
public final class LibrarySources implements ResourceContentProvider {

    private static final byte[] NOTHING = new byte[0];

    /**
     * A banner the viewer shows above reconstructed code.
     *
     * <p>IntelliJ says the same thing in the same place and for the same reason: what follows is not what
     * anybody wrote. Without it a reader has no way to tell a class whose author omitted every comment
     * from one whose comments a decompiler could not recover — and would reasonably conclude the first,
     * which is a false statement about somebody else's code.</p>
     */
    static final String DECOMPILED_BANNER =
            "// Decompiled from bytecode — comments and local names are not the author's.\n";

    /** What a failed decompile is remembered as, so it is not retried on every click. */
    private static final String REFUSED = "\u0000refused";

    /**
     * Decompiled output by binary name, bounded.
     *
     * <p>Unbounded would hold every class a session ever looked at, and a decompiled JDK class is tens of
     * kilobytes of string. Sixteen is what an LRU has to hold for going back and forth between a class
     * and its supertype to stay free, which is what reading actually looks like.</p>
     */
    private static final Map<String, String> DECOMPILED = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 16;
        }
    };

    private LibrarySources() {
    }

    /**
     * Registers the provider for {@link Resource#SCHEME_LIBRARY}.
     *
     * <p>Idempotent, because {@code JavaLanguage.register} is — a host or a test opening the stack twice
     * must not end with two providers, and the registry keeps the last one registered anyway.</p>
     */
    public static void register() {
        ResourceRegistry.register(Resource.SCHEME_LIBRARY, new LibrarySources());
    }

    /**
     * {@code ArrayList.java} for a type with attached source, {@code FlexDirection.class} for one
     * without.
     *
     * <p>The extension is the honest one rather than a decoration: with source, the tab really is
     * showing a {@code .java} file somebody wrote; without it, what is on screen was reconstructed from
     * a {@code .class} and IntelliJ names it that way for the same reason. It also picks the icon, since
     * a file-icon theme keys on the name — so the two say the same thing without being told to.</p>
     *
     * <p>Asked of the archive rather than of the content, so it costs one cached lookup and does not
     * depend on having already read the file.</p>
     */
    @Override
    public String displayName(Resource resource) {
        if (resource == null) return null;
        String binaryName = resource.path();
        int dot = binaryName.lastIndexOf('.');
        String simple = dot < 0 ? binaryName : binaryName.substring(dot + 1);
        if (simple.isEmpty()) return null;
        boolean hasSource =
                AttachedSources.forClasspath(HostClasspath.detect()).textOf(binaryName) != null;
        return simple + (hasSource ? ".java" : ".class");
    }

    /**
     * The icon for what this type IS — an interface glyph for an interface, an enum's for an enum.
     *
     * <h3>The extension cannot answer this</h3>
     *
     * <p>{@code FlexDirection.class} is an enum and {@code Runnable.class} is an interface, and the file
     * name is the same shape both times. Deriving the icon from it draws a class glyph on every one, and
     * that is the exact mistake this codebase has already paid for once — a hand-built symbol reported
     * {@code java.util.List} as a class, so the documentation popup drew a class glyph beside an
     * interface while a {@code .java} file in the same session drew the right one.</p>
     *
     * <p>So the kind is <b>asked of the engine</b>, which is the only thing that knows. A source-backed
     * tab keeps its file icon instead: a {@code .java} tab is a Java FILE, and takes the same glyph one
     * in the project would.</p>
     *
     * <h3>Cached, because a tab strip asks repeatedly</h3>
     *
     * <p>{@code tabIconFor} is a presentation provider — the dock re-reads it every time a strip is
     * rebuilt, which is every split, drag and reorder. Answering with a probe compile each time would
     * put one behind every rearrangement, so the answer is kept per type name. It cannot go stale in a
     * way that matters: what a class IS does not change within a session.</p>
     */
    @Override
    public SymbolInfo symbolOf(Resource resource) {
        if (resource == null) return null;
        String binaryName = resource.path();
        // THE CACHE IS CONSULTED BEFORE THE CLASSPATH IS DETECTED, and it used to be the other way round
        // -- so every answered call still paid for a full classpath probe. This is a PRESENTATION
        // provider: the dock re-reads it on every strip rebuild, so that cost landed on the frame.
        //
        // A SOURCE-BACKED TAB GETS ONE TOO, and it used to be refused here on the ground that
        // `ArrayList.java` is a FILE and should take the icon one in the project would. That reasoning
        // inverted the moment a project `.java` started showing what it declares: the two are now the
        // same statement, so refusing here would make the JDK's own sources the one place in the
        // application that still says "some Java file".
        synchronized (SYMBOLS) {
            if (SYMBOLS.containsKey(binaryName)) return SYMBOLS.get(binaryName);
        }
        scheduleDescribe(resource, binaryName);
        // NOT YET, rather than an answer bought with a frame. @see ResourceRegistry#onSymbolResolved
        return null;
    }

    /**
     * Works out what a class is, <b>off the thread that draws frames</b>, once per name.
     *
     * <h3>Measured at 761ms, on a method that reads like a getter</h3>
     *
     * <p>{@code describe} compiles a probe unit against the whole classpath, and this is reached from a
     * tab presentation and a tree row bind — both on the frame thread. So the first time any library
     * class appeared in the dock the application stopped for the better part of a second, and nothing at
     * either call site suggested it might: the signature is {@code SymbolInfo symbolOf(Resource)}.</p>
     *
     * <p><b>The glyph arrives late instead</b>, which is the trade every IDE makes for the same reason —
     * a decompiled tab shows a generic icon for a moment and then the right one. The alternative is not
     * "the correct icon immediately", it is a frozen frame.</p>
     *
     * <p>{@code PENDING} is what keeps a run of presentation reads from queueing one job each: the dock
     * re-reads every tab on every strip rebuild, so an unguarded miss would submit dozens for one class.
     * The key is the same for all of them, so the scheduler would collapse them anyway — the set makes
     * that a property of this code rather than of a scheduler detail.</p>
     */
    private static void scheduleDescribe(Resource resource, String binaryName) {
        if (!PENDING.add(binaryName)) return;
        JobScheduler.shared()
                .job(JobKey.of(LibrarySources.class, "describe-" + binaryName), JobLane.LATENCY,
                        context -> describe(binaryName, HostClasspath.detect()))
                .onDone(described -> {
                    // ON THE UI THREAD, during drain -- which is what makes storing and announcing safe
                    // to do together, and what lets the announcement reach a widget directly.
                    synchronized (SYMBOLS) {
                        SYMBOLS.put(binaryName, described);
                    }
                    PENDING.remove(binaryName);
                    ResourceRegistry.symbolResolved(resource);
                })
                .submit();
    }

    /** Names a describe is already running for. @see #scheduleDescribe */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    /**
     * What the engine says a type is, or null.
     *
     * <p>Through {@code Analysis.describe}, which is the same door {@code Resolver.describe} and the
     * documentation link both use — so the tab's glyph and the popup's owner band cannot disagree about
     * a type, which is precisely how the class-beside-an-interface bug read.</p>
     */
    @Nullable
    private static SymbolInfo describe(String binaryName, List<String> classpath) {
        JavaEngine engine = JavaLanguage.engine();
        if (engine == null) return null;
        try (Analysis analysis = engine.analyzer().analyze(
                "Probe", "public class Probe { }\n", classpath, engine.releaseLevel(), 0L)) {
            return analysis.describe(binaryName);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * What each type IS, by name — nulls included, so an unanswerable one is asked once.
     *
     * <p>Bounded only by how many library tabs a session opens, which is how many a person can read.
     * It cannot go stale in a way that matters: what a class IS does not change within a session.</p>
     */
    private static final Map<String, SymbolInfo> SYMBOLS = new HashMap<>();

    @Override
    public byte[] read(Resource resource) {
        if (resource == null) return NOTHING;
        // THE CLASSPATH IS DETECTED AT READ TIME rather than captured at registration. A resource is a
        // NAME, and what answers it can change within a session: a source archive downloaded by
        // `JdkSourceCommands` mid-session is exactly the case, and a provider holding the classpath it
        // was built with would keep serving nothing until a restart.
        List<String> classpath = HostClasspath.detect();
        String text = AttachedSources.forClasspath(classpath).textOf(resource.path());
        if (text == null) text = decompiled(resource.path(), classpath);
        // EMPTY, NEVER AN EXCEPTION -- the interface says so, and its reason applies here: a pane can
        // render a banner over empty and cannot render a throw.
        return text == null ? NOTHING : text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Where a member is declared in this class's reconstructed text. @see ResourceContentProvider#locate
     *
     * <h3>Resolved, never searched — and a USE answers as well as the declaration</h3>
     *
     * <p>The obvious version scans the text for the name and takes the first hit, which lands on whatever
     * calls the member before it is declared and cannot tell two overloads apart. This asks the compiler
     * instead: every occurrence is offered to {@code resolveAt}, and the first that resolves to a member
     * of this name reports <b>where its binding is declared in this very document</b>. So hitting a call
     * is not a near miss, it is an equally good answer — the binding is the same either way, and
     * {@code declarationOf} answers with the declaration's own position. Overloads resolve to the
     * overload that was called, which is the one a reader following it wants.</p>
     *
     * <p>The scan only supplies candidates; correctness comes entirely from the resolve. Whole-word
     * matching keeps it from offering the middle of a longer identifier, which would cost a resolve
     * apiece for answers that can never match.</p>
     *
     * <p><b>Off the UI thread by contract</b>, because this parses. The text is whatever
     * {@link #read} would return — attached source or the cached decompile, banner included — so the
     * offsets are the ones the viewer is showing.</p>
     */
    @Override
    @Nullable
    public TextPoint locate(Resource resource, String member) {
        if (resource == null || member == null || member.isEmpty()) return null;
        List<String> classpath = HostClasspath.detect();
        String text = AttachedSources.forClasspath(classpath).textOf(resource.path());
        if (text == null) text = decompiled(resource.path(), classpath);
        if (text == null) return null;
        JavaEngine engine = JavaLanguage.engine();
        if (engine == null) return null;

        String binaryName = resource.path();
        int lastDot = binaryName.lastIndexOf('.');
        String simple = lastDot < 0 ? binaryName : binaryName.substring(lastDot + 1);
        try (SourceAnalyzer.Analysis analysis =
                     engine.analyzer().analyze(simple, text, classpath, engine.releaseLevel(), 0L)) {
            for (int at = text.indexOf(member); at >= 0; at = text.indexOf(member, at + 1)) {
                if (!isWholeWord(text, at, member.length())) continue;
                SymbolInfo symbol = analysis.resolveAt(at);
                if (symbol == null || !member.equals(symbol.name())) continue;
                DeclarationSite site = symbol.declaration();
                // SAME DOCUMENT is the whole test: a member of THIS class reports a site with no
                // resource, and anything resolving into another class -- a call to something else that
                // happens to share the name -- names that one and is skipped.
                if (site != null && site.isSameDocument()) return site.start();
            }
        } catch (RuntimeException unparseable) {
            // Reconstructed output that will not analyse is not worth failing a navigation over: the
            // caller falls back to the top of the file, which is where it landed before this existed.
            return null;
        }
        return null;
    }

    private static boolean isWholeWord(String text, int at, int length) {
        int end = at + length;
        boolean before = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
        boolean after = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
        return before && after;
    }

    /**
     * The decompiled form of a class, cached, or null.
     *
     * <p>Synchronized around the map alone and never around the decompile: two viewers opening different
     * classes at once must not serialise on each other, and the worst a race costs is one duplicated
     * decompile whose result replaces an identical one.</p>
     */
    @Nullable
    private static String decompiled(String binaryName, List<String> classpath) {
        synchronized (DECOMPILED) {
            forgetIfMappingChanged();
            String cached = DECOMPILED.get(binaryName);
            if (cached != null) return REFUSED.equals(cached) ? null : cached;
        }
        JavaEngine engine = JavaLanguage.engine();
        if (engine == null) return null;

        // THE MAPPING THIS RENDERING IS AGAINST, captured rather than re-read afterwards. A decompile
        // takes a second or so and the mapping can land inside it, and text rendered through the old one
        // must not be stored under the new one.
        MappingSet used = PlatformMappings.current();
        String java = engine.decompile(binaryName, classpath);
        String stored = java == null ? REFUSED : DECOMPILED_BANNER + java;
        synchronized (DECOMPILED) {
            forgetIfMappingChanged();
            // STORED ONLY IF IT IS STILL WHAT WE RENDERED THROUGH. Discarding costs one decompile the next
            // time this class is opened; storing costs runtime names for the life of the process. The
            // caller still gets this rendering -- one tab showing SRG names is recoverable by reopening it,
            // and the alternative is showing nothing at all for a race that needs a download to land inside
            // a single decompile.
            if (used == decompiledFor) DECOMPILED.put(binaryName, stored);
        }
        return java == null ? null : stored;
    }

    /** The mapping the cached text above was rendered through. @see #forgetIfMappingChanged */
    private static MappingSet decompiledFor = MappingSet.IDENTITY;

    /**
     * Drops the decompiled text when the mapping underneath it has changed.
     *
     * <h3>A cache of remapped text keyed only by class name</h3>
     *
     * <p>Decompiling goes through {@code JavaEngine.decompile}, which reads {@code types.readable(...)} —
     * so the text is rendered through whatever {@link PlatformMappings#current} answered at the time, and
     * on a 1.7.10 client that is the difference between {@code getMinecraft()} and {@code func_71410_x()}.
     * The key had no mapping component, so the first rendering won for the life of the process.</p>
     *
     * <p><b>Only a FIRST launch can reach it, which is what makes it worth a guard rather than a
     * comment.</b> A cached mapping is applied on the claiming thread during mod init, long before any
     * viewer opens, so the identity is never current by the time anything is decompiled. A mapping that
     * must be DOWNLOADED lands whenever the network says — and anything opened before then is cached with
     * runtime names and stays wrong until the game restarts, at which point the cache is warm and it comes
     * out right. {@code PlatformTypeBytes.view} carries the same warning about its own captured reference,
     * in the same words: the symptom is "mappings work on the second launch and never on the first", which
     * reads as a caching bug rather than as a late arrival.</p>
     *
     * <p>One reference comparison per lookup, like {@code view()}'s, and at most one clear per process —
     * the mapping goes from identity to real once and never moves again.</p>
     */
    private static void forgetIfMappingChanged() {
        MappingSet now = PlatformMappings.current();
        if (now == decompiledFor) return;
        DECOMPILED.clear();
        decompiledFor = now;
    }
}
