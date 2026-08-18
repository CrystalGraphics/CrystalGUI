package com.crystalgui.language.platform;

import com.crystalgui.language.map.ReadableView;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Everything a loader knows and {@code language/} cannot work out for itself.
 *
 * <h3>What a platform contributes, and what it must not</h3>
 *
 * <p>Two kinds of thing: <b>what to provide</b> — a route to live bytes, which mapping artifact this
 * environment needs, how to tell which namespace it is in — and <b>where to put it</b>. Nothing else.
 * Downloading, verifying, caching, parsing, remapping, namespace detection and compilation all belong to
 * this module, once.</p>
 *
 * <p>That split is the point rather than tidiness. A second loader should be an implementation of this
 * interface, not a second copy of the phase that built it — so {@link #liveBytes()} is the only member
 * that is genuinely code, and on Minecraft 1.7.10 it is about a dozen lines. Anything in a loader that
 * starts to look like logic is a design error and belongs behind this interface instead.</p>
 *
 * <h3>Registration mirrors {@code CgPlatform}</h3>
 *
 * <p>One bundle, registered by the loader, read through a static accessor — not a field per concern.
 * CrystalGraphics learned that one already: two registries is how a loader wires up half of something
 * and ends up with a working backend and a dead keyboard, which is why CrystalGUI has no platform
 * registry of its own and reads everything through {@code CgPlatform}.</p>
 *
 * <h3>{@link #NONE} is a real deployment, not a test double</h3>
 *
 * <p>The GL debug harness, every unit test, and a dedicated server all run with no platform registered.
 * There {@code liveBytes()} falls back to reading the classloader, there are no mappings, and the stack
 * behaves exactly as it did before this interface existed. Keeping that path real is what lets
 * {@code language/} run off a Minecraft host at all, which is the property the module exists for.</p>
 */
public interface ScriptPlatform {

    /**
     * No Minecraft host: read bytes off the classloader, no mappings, nothing to detect.
     *
     * <p>{@code cacheRoot()} is the working directory rather than a temporary one on purpose — a
     * download that vanishes between runs is a download that happens every run.</p>
     */
    ScriptPlatform NONE = new ScriptPlatform() {

        @Override
        public ReadableView.ByteSource liveBytes() {
            return ReadableView.ByteSource.ofClassLoader(ScriptPlatform.class.getClassLoader());
        }

        @Override
        public Path cacheRoot() {
            return Paths.get("build", "crystalgui-cache").toAbsolutePath().normalize();
        }

        @Override
        public MappingCoordinates mappings() {
            return MappingCoordinates.NONE;
        }

        @Override
        public NamespaceProbe namespaceProbe() {
            return NamespaceProbe.NONE;
        }

        @Override
        public String toString() {
            return "ScriptPlatform.NONE";
        }
    };

    /**
     * Where the <b>post-transform</b> bytes of a class come from.
     *
     * <p>The one member that is real per-platform code. It must answer with what will actually execute,
     * transformers and mixins included — a class file read off disk is precisely the thing that lies on
     * a Minecraft host, and {@link ReadableView.ByteSource#ofClassLoader} says so in its own javadoc.</p>
     */
    ReadableView.ByteSource liveBytes();

    /**
     * Root for anything this module downloads or extracts. Must survive a restart.
     *
     * <p>The platform answers "where", and the layout <em>beneath</em> it is this module's — so every
     * platform ends up with the same tree and a mistake in it is fixed once rather than per loader.</p>
     */
    Path cacheRoot();

    /** Which mapping artifact this environment needs, or {@link MappingCoordinates#NONE}. */
    MappingCoordinates mappings();

    /** How to tell a readable runtime from an obfuscated one, or {@link NamespaceProbe#NONE}. */
    NamespaceProbe namespaceProbe();
}
