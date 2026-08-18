package com.crystalgui.language.java.classpath;

import com.crystalgui.language.engine.bridge.TypeBytes;
import com.crystalgui.language.map.MappingSet;
import com.crystalgui.language.map.ReadableView;
import com.crystalgui.language.platform.ScriptPlatform;
import com.crystalgui.language.platform.ScriptPlatforms;

/**
 * The host's answer to {@link TypeBytes} — the live runtime, remapped, with a reflective floor.
 *
 * <h3>Host-side on purpose, and that is the whole reason this class exists</h3>
 *
 * <p>Everything it touches is child-first in {@code EngineClassLoader}: {@link ScriptPlatforms} keeps a
 * static registry, {@link ReadableView} holds the mapping, {@link ReflectionOverlay} carries the ASM
 * synthesis. A compiler-side class naming any of them gets the band loader's <em>own</em> copy — and for
 * the registry that means reading a static nothing ever wrote to, because {@code register()} was called
 * on the host's copy. The compile then resolves against files as though no platform were installed:
 * correct-looking, entirely inert, and silent.</p>
 *
 * <p>So the composition happens here, once, and only {@code byte[]} crosses.</p>
 *
 * <h3>Resolved per instance, not per call</h3>
 *
 * <p>A platform is registered once during startup and never replaced, so re-reading the registry for
 * every type the compiler asks about would be a static lookup per name on the hot path of a keystroke.
 * Constructing one of these per compile is the granularity that matches how the rest of the environment
 * is cached — and it is what makes "a mixin can add a member between runs" true rather than aspirational.</p>
 */
public final class PlatformTypeBytes implements TypeBytes {

    private final ReadableView readable;
    private final ClassLoader loader;

    private PlatformTypeBytes(ReadableView readable, ClassLoader loader) {
        this.readable = readable;
        this.loader = loader;
    }

    /**
     * What the registered platform can supply, or {@link TypeBytes#NONE} where there is none.
     *
     * <p>{@code NONE} rather than a view over the classloader: off a Minecraft host the classloader
     * answers for <em>everything</em>, so a default view would quietly take over from the classpath and
     * change what every existing test resolves against. The absence has to stay an absence.</p>
     *
     * @param mappings readable ↔ runtime, or {@link MappingSet#IDENTITY} on a host whose runtime is
     *                 already readable — which a dev client genuinely is, so it is an answer and not a
     *                 placeholder
     */
    public static TypeBytes of(MappingSet mappings) {
        ScriptPlatform platform = ScriptPlatforms.current();
        if (platform == ScriptPlatform.NONE) return TypeBytes.NONE;
        // SAID OUT LOUD, ONCE PER ENGINE, and it earns the line. "Live" and "inert" produce identical
        // behaviour for every script that only touches classes which are also on disk -- which is most
        // of them -- so without this there is no way to tell from a log whether §15.5 A is working or
        // was silently skipped. That is not a hypothetical failure: it is the one this class exists to
        // fix, and it survived a full test suite and a working client.
        System.err.println("[crystalgui] resolving against the live runtime through "
                + platform.getClass().getName()
                + (mappings == MappingSet.IDENTITY ? " (identity mapping)" : " (mapped)"));
        return new PlatformTypeBytes(new ReadableView(mappings, platform.liveBytes()),
                PlatformTypeBytes.class.getClassLoader());
    }

    @Override
    public byte[] readable(String internalName) {
        try {
            return readable.readableBytesOf(internalName);
        } catch (Exception unavailable) {
            // An ordinary answer, not an error. A type the live loader cannot produce is one the
            // classpath may still have, and turning that into a throw would make an unremarkable miss
            // fatal to the whole compile.
            return null;
        }
    }

    @Override
    public byte[] synthesized(String internalName) {
        try {
            // initialize = false: resolving a NAME must never run a static initializer. On a Minecraft
            // host that would execute arbitrary class setup during a keystroke.
            return ReflectionOverlay.stubOf(
                    Class.forName(internalName.replace('/', '.'), false, loader));
        } catch (ClassNotFoundException | LinkageError | RuntimeException absent) {
            // Genuinely not there, or not describable. Either way the compiler gets the same "no" it
            // would have got without this tier — a stub that cannot be built is not an error of its own.
            return null;
        }
    }
}
