package com.crystalgui.language.map;

import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.core.async.Progress;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.language.platform.ScriptServices;

import java.util.function.BooleanSupplier;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Which namespace this runtime speaks, decided by asking it — and the mapping that follows.
 *
 * <h3>This module drives the acquisition; a platform only says how to run it</h3>
 *
 * <p>Nothing outside {@code language/} needs to call anything. The first {@link #current()} probes,
 * applies a cached mapping on the calling thread, and hands a genuine download to
 * {@link ScriptService#runInBackground} — which is the one part a host knows better, since
 * {@code JobScheduler} is drained by {@code UIDocument.frame} and by nothing else.</p>
 *
 * <h3>Probed, never configured</h3>
 *
 * <p>A 1.7.10 development client runs Minecraft recompiled at MCP names, so {@code World} really
 * declares {@code getBlock} and the mapping is the identity. The same client in production declares
 * {@code func_147439_a}. A flag someone sets is a flag that will be wrong in exactly the environment
 * nobody tests; the two differ observably, so this observes them. It costs one class read.</p>
 *
 * <p>The read goes through {@link ScriptService#liveBytes()} — the same source the compiler resolves
 * against — so the probe cannot answer differently from what will later be compiled against. A check
 * against a file could, and on the platform where the disk view lies that is the worst of both.</p>
 *
 * <h3>Resolved once, and the fetch is never on the calling thread</h3>
 *
 * <p>The platform is registered during startup and never replaced, so this is a process-wide answer
 * computed once. But a first launch may need a download, and languages register from the client thread —
 * so the split is: <b>probe synchronously</b> (a class read), <b>parse synchronously when the cache is
 * already complete</b> (one pass over 630 KB, which is a launch cost nobody notices), and go to a
 * background thread <b>only when bytes actually have to be fetched</b>.</p>
 *
 * <p>Until that thread finishes, {@link #current} answers {@link MappingSet#IDENTITY} — which is the
 * honest answer, not a placeholder: with no mapping the runtime namespace is what is shown, exactly as
 * on a platform that never had one. Anything holding the result must therefore re-read it rather than
 * capture it, which is what {@code PlatformTypeBytes} does.</p>
 */
public final class PlatformMappings {

    private static volatile MappingSet current = MappingSet.IDENTITY;
    private static volatile boolean started;

    private PlatformMappings() {
    }

    /**
     * The mapping to translate through — {@link MappingSet#IDENTITY} until anything says otherwise.
     *
     * <p>Starts resolution on the first call and returns immediately. Callers must re-read rather than
     * hold: this reference is replaced once, when a background fetch completes.</p>
     */
    public static MappingSet current() {
        if (!started) startLazily();
        return current;
    }

    /**
     * <b>Takes ownership of the acquisition, without doing it.</b> True if this caller now owns it.
     *
     * <p>One artifact per process, so whoever takes the claim owes the work. Not public: a host states
     * the what, the where and the how, and {@link #current()} decides when — this is the split between
     * that decision and the work it commits to, and the in-package test asserts it.</p>
     *
     * <p><b>A claim is a promise to do it.</b> Claiming and then not following through leaves the mapping
     * permanently unacquired, with {@code current()} answering identity for ever and nothing to say
     * why.</p>
     */
    static boolean claim() {
        synchronized (PlatformMappings.class) {
            if (started) return false;
            started = true;
            return true;
        }
    }

    /**
     * Probe, apply what is cached, and hand a download to the host — so a first {@code current()} never
     * blocks its caller.
     *
     * <p>One-shot: there is exactly one artifact to acquire per process, and {@link #claim()} is what
     * makes that true however many threads ask at once.</p>
     */
    private static void startLazily() {
        if (!claim()) return;
        // THE DECISION INLINE, THE FETCH ON A THREAD. A mapping already cached is applied before this
        // returns, so the caller's very next current() sees it -- which is the difference between the
        // editor opening with readable names and opening with runtime ones and correcting itself.
        ScriptService needsFetch = decide();
        if (needsFetch == null) return;

        // THE HOST SAYS HOW, THIS SAYS WHEN. A platform states the what, the where and the how; deciding
        // that a fetch is owed is this module's job and used to be copied into every loader. The default
        // is a daemon thread, so a dedicated server is correct with no code at all, and a host with a UI
        // overrides it to get a progress bar. @see ScriptService#runInBackground
        needsFetch.runInBackground("Downloading Minecraft mappings",
                (progress, cancelled) -> fetch(needsFetch, progress, cancelled));
    }

    /**
     * The DECISION of a {@link #claim()}, <b>on the calling thread</b>. Returns the platform when a network
     * fetch is still owed, and null when there is nothing left to do.
     *
     * <h3>Why a caller must run this itself rather than let a job do it</h3>
     *
     * <p>Split from the fetch because a mapping already in the cache must be applied <b>before the first
     * analysis</b>, on whatever thread asked — otherwise the editor shows runtime names and then silently
     * changes its mind a moment later, which reads as the names being unstable rather than as a load
     * having completed. Only the network half is worth moving off the caller.</p>
     *
     * <p>Putting the whole of {@link #acquireClaimed} in a job puts this half in there too, and that is
     * <b>not</b> merely late — it makes a cached mapping depend on the job running at all. Measured on
     * {@code runObfClient}: {@code mcp_stable/12} was complete on disk, the claim was made at mod init, the
     * job was submitted, and no branch of this method ever executed — no mapping line in any log of any run,
     * and every compiled script cached under a key ending {@code -identity-8}. The script then called
     * {@code Minecraft.getMinecraft()} against a runtime that only has {@code func_71410_x} and died with
     * {@code NoSuchMethodError}. A cache read is a parse and costs nothing; there is no reason for it to be
     * anywhere but here.</p>
     */
    static ScriptService decideClaimed() {
        return decide();
    }

    private static ScriptService decide() {

        ScriptService platform = CgPlatform.get(ScriptServices.SERVICE);
        if (platform == ScriptService.NONE) {
            // SAID, because this was the one branch out of five that returned in silence -- and silence
            // here is indistinguishable from every other way of ending up with identity names. It is also
            // the branch a THREADING or CLASSLOADER fault arrives through: a worker that cannot see a
            // service the client thread provided, or a second copy of this class defined inside the engine
            // band, both present exactly as "no platform" and neither leaves any other trace.
            System.err.println("[crystalgui] mappings: NOT_CONFIGURED — no script platform is registered"
                    + " (asked from " + Thread.currentThread().getName() + ")"
                    + "; runtime names will be shown as they are");
            return null;
        }

        NamespaceProbe probe = platform.namespaceProbe();
        MappingCoordinates coordinates = platform.mappings();
        if (probe.isNone() || coordinates.isNone()) {
            // Nothing to decide, or nothing to fetch. Either way the runtime is taken as it is -- and it
            // is SAID, because "this platform declares no mappings" and "the download failed" produce the
            // same names on screen and are entirely different things to whoever is looking at them.
            System.err.println("[crystalgui] mappings: NOT_CONFIGURED — "
                    + (probe.isNone() ? "no namespace probe" : "no mapping coordinates")
                    + " on " + platform.getClass().getName()
                    + "; runtime names will be shown as they are");
            return null;
        }

        Boolean readable = isReadable(platform, probe);
        if (readable == null) {
            System.err.println("[crystalgui] could not read " + probe.internalName()
                    + " to tell which namespace this runtime speaks; assuming it is already readable");
            return null;
        }
        if (readable) {
            System.err.println("[crystalgui] the runtime already speaks readable names ("
                    + probe.internalName() + " declares " + probe.readableMember() + ")");
            return null;
        }

        if (MappingCache.isComplete(coordinates, platform.cacheRoot())) {
            // ON THIS THREAD, deliberately: it is a parse and no network, and a mapping that is ready
            // before the first analysis avoids a window where the editor shows runtime names and then
            // silently changes its mind.
            apply(MappingCache.load(coordinates, platform.cacheRoot()));
            return null;
        }

        // Everything above was free. What is left is the network, and only the caller knows where that
        // should run -- so it is handed back rather than done here. @see #begin @see #startLazily
        return platform;
    }

    /**
     * The network half, on whatever thread the caller chose.
     *
     * <p>Indeterminate: the two CSVs are small and their host declares no length worth trusting, so a
     * sweep is honest where a bar would be invented.</p>
     */
    private static void fetch(ScriptService platform, Progress progress,
                              BooleanSupplier cancelled) {
        MappingCoordinates coordinates = platform.mappings();
        progress.begin("Downloading Minecraft mappings", -1);
        progress.detail(coordinates.cacheKey());
        apply(MappingCache.load(coordinates, platform.cacheRoot(), cancelled));
    }

    private static void apply(MappingCache.Result result) {
        // SAID ONCE, WHICHEVER IT IS. "No mappings configured" and "the download failed" produce the same
        // thing on screen -- runtime names -- and are entirely different to somebody offline on purpose.
        System.err.println("[crystalgui] mappings: " + result.state() + " — " + result.detail());
        if (!result.mappings().isIdentity()) current = result.mappings();
    }

    /**
     * Whether the probe's type declares its readable member — null when the type cannot be read.
     *
     * <p>Null rather than false, because "I could not tell" and "it is obfuscated" call for different
     * behaviour: treating an unreadable probe as obfuscated would download a mapping and translate every
     * name through it on a runtime that never needed one, which is worse than doing nothing.</p>
     */
    private static Boolean isReadable(ScriptService platform, NamespaceProbe probe) {
        byte[] bytes;
        try {
            bytes = platform.liveBytes().bytesOf(probe.internalName());
        } catch (Exception | LinkageError unavailable) {
            return null;
        }
        if (bytes == null) return null;
        return declares(bytes, probe.readableMember());
    }

    /** Whether a class file declares a member of this name, as a method or as a field. */
    private static boolean declares(byte[] classFile, String member) {
        final boolean[] found = {false};
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (member.equals(name)) found[0] = true;
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (member.equals(name)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /** Forgets the resolved answer. For tests, which register different platforms in one JVM. */
    public static synchronized void resetForTesting() {
        current = MappingSet.IDENTITY;
        started = false;
    }
}
