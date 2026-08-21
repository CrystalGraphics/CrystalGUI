package com.crystalgui.language.java;

import com.crystalgui.fs.Resource;
import com.crystalgui.fs.ResourceContentProvider;
import com.crystalgui.fs.ResourceRegistry;
import com.crystalgui.language.java.assist.AttachedSources;
import com.crystalgui.language.java.classpath.HostClasspath;

import com.crystalgui.language.engine.JavaEngine;

import javax.annotation.Nullable;

import java.nio.charset.StandardCharsets;
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
