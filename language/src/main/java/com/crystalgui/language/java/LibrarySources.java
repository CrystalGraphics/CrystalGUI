package com.crystalgui.language.java;

import com.crystalgui.fs.Resource;
import com.crystalgui.fs.ResourceContentProvider;
import com.crystalgui.fs.ResourceRegistry;
import com.crystalgui.language.java.assist.AttachedSources;
import com.crystalgui.language.java.classpath.HostClasspath;

import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.text.lang.SymbolInfo;

import javax.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        List<String> classpath = HostClasspath.detect();
        // SOURCE-BACKED TABS KEEP THE FILE ICON. Only a reconstructed `.class` shows what the type IS.
        if (AttachedSources.forClasspath(classpath).textOf(binaryName) != null) return null;

        synchronized (SYMBOLS) {
            if (SYMBOLS.containsKey(binaryName)) return SYMBOLS.get(binaryName);
        }
        SymbolInfo described = describe(binaryName, classpath);
        synchronized (SYMBOLS) {
            SYMBOLS.put(binaryName, described);
        }
        return described;
    }

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
     * The decompiled form of a class, cached, or null.
     *
     * <p>Synchronized around the map alone and never around the decompile: two viewers opening different
     * classes at once must not serialise on each other, and the worst a race costs is one duplicated
     * decompile whose result replaces an identical one.</p>
     */
    @Nullable
    private static String decompiled(String binaryName, List<String> classpath) {
        synchronized (DECOMPILED) {
            String cached = DECOMPILED.get(binaryName);
            if (cached != null) return REFUSED.equals(cached) ? null : cached;
        }
        JavaEngine engine = JavaLanguage.engine();
        if (engine == null) return null;
        String java = engine.decompile(binaryName, classpath);
        String stored = java == null ? REFUSED : DECOMPILED_BANNER + java;
        synchronized (DECOMPILED) {
            DECOMPILED.put(binaryName, stored);
        }
        return java == null ? null : stored;
    }
}
