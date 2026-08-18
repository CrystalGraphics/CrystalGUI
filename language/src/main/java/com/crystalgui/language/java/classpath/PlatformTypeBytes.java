package com.crystalgui.language.java.classpath;

import com.crystalgui.language.engine.bridge.TypeBytes;
import com.crystalgui.language.map.MappingSet;
import com.crystalgui.language.map.PlatformMappings;
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

    private final ReadableView.ByteSource source;
    private final ClassLoader loader;

    /** The view, and the mapping it was built for. @see #view */
    private volatile ReadableView readable;
    private volatile MappingSet builtFor;

    private PlatformTypeBytes(ReadableView.ByteSource source, ClassLoader loader) {
        this.source = source;
        this.loader = loader;
    }

    /**
     * The remapping view for whatever mapping is current <b>now</b>.
     *
     * <p>Re-read rather than captured, because the mapping arrives late on a first launch: the probe is
     * synchronous but a download is not, so {@link PlatformMappings#current} answers {@code IDENTITY}
     * until the fetch lands and then answers the real set. A view built once at construction would pin
     * the identity for the life of the process, and the symptom would be that mappings work on the
     * second launch and never on the first — which reads as a caching bug rather than a captured
     * reference.</p>
     *
     * <p>Rebuilt only when the set actually changes, which is at most once, so the steady-state cost is
     * one reference comparison per type the compiler asks about.</p>
     */
    private ReadableView view() {
        MappingSet now = PlatformMappings.current();
        ReadableView cached = readable;
        if (cached == null || builtFor != now) {
            cached = new ReadableView(now, source);
            readable = cached;
            builtFor = now;
        }
        return cached;
    }

    /**
     * What the registered platform can supply, or {@link TypeBytes#NONE} where there is none.
     *
     * <p>{@code NONE} rather than a view over the classloader: off a Minecraft host the classloader
     * answers for <em>everything</em>, so a default view would quietly take over from the classpath and
     * change what every existing test resolves against. The absence has to stay an absence.</p>
     *
     * <p>The mapping is not a parameter: which namespace the runtime speaks is <b>probed</b>, not chosen
     * by a caller, and {@link PlatformMappings} is where that happens. Passing one in would let two
     * engines in one process translate differently.</p>
     */
    public static TypeBytes of() {
        ScriptPlatform platform = ScriptPlatforms.current();
        if (platform == ScriptPlatform.NONE) return TypeBytes.NONE;
        // A WAY TO TURN THE LIVE ROUTE OFF WITHOUT REMOVING THE PLATFORM, for diagnosis only.
        //
        // "Live" and "file-based" differ in exactly one place and produce identical behaviour nearly
        // everywhere, which is why the note below exists at all -- and the corollary is that when they
        // DO differ, there is no way to attribute it without being able to run the same client both
        // ways. Registering no platform is not the same experiment: that also disables the mapping and
        // the namespace probe, so it changes three things at once.
        if (Boolean.getBoolean("crystalgui.language.noLiveBytes")) {
            System.err.println("[crystalgui] live runtime resolution DISABLED by "
                    + "-Dcrystalgui.language.noLiveBytes -- diagnosis only");
            return TypeBytes.NONE;
        }
        // SAID OUT LOUD, ONCE PER ENGINE, and it earns the line. "Live" and "inert" produce identical
        // behaviour for every script that only touches classes which are also on disk -- which is most
        // of them -- so without this there is no way to tell from a log whether §15.5 A is working or
        // was silently skipped. That is not a hypothetical failure: it is the one this class exists to
        // fix, and it survived a full test suite and a working client.
        System.err.println("[crystalgui] resolving against the live runtime through "
                + platform.getClass().getName());
        return new PlatformTypeBytes(platform.liveBytes(),
                PlatformTypeBytes.class.getClassLoader());
    }

    @Override
    public byte[] readable(String internalName) {
        try {
            return view().readableBytesOf(internalName);
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
