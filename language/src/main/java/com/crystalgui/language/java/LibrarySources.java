package com.crystalgui.language.java;

import com.crystalgui.fs.Resource;
import com.crystalgui.fs.ResourceContentProvider;
import com.crystalgui.fs.ResourceRegistry;
import com.crystalgui.language.java.assist.AttachedSources;
import com.crystalgui.language.java.classpath.HostClasspath;

import java.nio.charset.StandardCharsets;

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
 * <h3>What it does not do yet</h3>
 *
 * <p>A class with no attached source anywhere answers empty, which the viewer shows as a blank tab. That
 * is the decompiler's half and it is deliberately absent rather than half-written — an assembled stub
 * would be a third content shape to explain, and the honest answer until CFR lands is nothing.</p>
 */
public final class LibrarySources implements ResourceContentProvider {

    private static final byte[] NOTHING = new byte[0];

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
        String text = AttachedSources.forClasspath(HostClasspath.detect()).textOf(resource.path());
        // EMPTY, NEVER AN EXCEPTION -- the interface says so, and its reason applies here: a pane can
        // render a banner over empty and cannot render a throw.
        return text == null ? NOTHING : text.getBytes(StandardCharsets.UTF_8);
    }
}
