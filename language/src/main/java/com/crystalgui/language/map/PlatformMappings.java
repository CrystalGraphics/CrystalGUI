package com.crystalgui.language.map;

import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.core.async.Progress;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.language.platform.ScriptServices;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Which namespace this runtime speaks, decided by asking it — and the mapping that follows.
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
     * Acquires the mapping <b>on the calling thread</b>, reporting into {@code progress}.
     *
     * <p>Public so a client can drive it from a job of its own and get a progress bar for free:</p>
     *
     * <pre>{@code
     * scheduler.job(key, JobLane.BACKGROUND, ctx -> {
     *     PlatformMappings.begin(ctx.progress());
     *     return null;
     * }).submit();
     * }</pre>
     *
     * <h3>Why this is not simply a {@code JobScheduler} job inside here</h3>
     *
     * <p>That was the plan and it is wrong, for a reason worth writing down: <b>{@code UIWindow.paintFrame}
     * is the only thing that drains the scheduler.</b> A dedicated server runs scripts, needs readable
     * names to compile them, and has no window — so a mapping fetch submitted as a job there would sit in
     * the queue for ever and nothing would say why. Threading is therefore the caller's decision: a client
     * calls this from a job, and anything headless gets the lazy daemon-thread path below.</p>
     *
     * <p>Idempotent. The second caller returns immediately rather than fetching again.</p>
     */
    public static void begin(Progress progress) {
        if (!claim()) return;
        acquireClaimed(progress);
    }

    /**
     * <b>Takes ownership of the acquisition, without doing it.</b> True if this caller now owns it.
     *
     * <p>Exists to close a race that was a coin flip. A client wants the fetch inside a job so it reports
     * into the status bar — but a job does not run until {@code JobScheduler.drain()}, which is the first
     * CrystalGUI paint, and anything touching {@link #current()} before that would start the lazy daemon
     * path instead. Both paths acquire correctly; only one of them draws a bar, and which one won was
     * decided by whatever happened to ask first.</p>
     *
     * <p>Claiming at registration — long before any paint — makes it deterministic: the lazy path finds
     * the work already owned and returns.</p>
     *
     * <p><b>A claim is a promise to do it.</b> Claiming and then never calling
     * {@link #acquireClaimed} leaves the mapping permanently unacquired, with {@code current()} answering
     * identity for ever and nothing to say why. Only claim where the follow-through is certain.</p>
     */
    public static boolean claim() {
        synchronized (PlatformMappings.class) {
            if (started) return false;
            started = true;
            return true;
        }
    }

    /** Does the work a {@link #claim()} promised, reporting into {@code progress}. */
    public static void acquireClaimed(Progress progress) {
        acquireClaimed(progress, () -> false);
    }

    /**
     * The same, stoppable.
     *
     * <p>This one runs inside a job — {@code ClientProxy} submits it — so unlike the engine band it has a
     * flag to hand over, and a first launch's mapping fetch is genuinely cancellable rather than merely
     * marked so.</p>
     */
    public static void acquireClaimed(Progress progress, java.util.function.BooleanSupplier cancelled) {
        ScriptService needsFetch = decide();
        if (needsFetch != null) {
            fetch(needsFetch, progress == null ? Progress.NONE : progress, cancelled);
        }
    }

    /** The lazy path: a daemon thread, so a first {@code current()} never blocks its caller. */
    /**
     * The lazy path: a daemon thread, so a first {@code current()} never blocks its caller.
     *
     * <p>Daemon, because mapping data must never be the reason a game cannot exit. One-shot, because there
     * is exactly one artifact to acquire per process.</p>
     */
    private static void startLazily() {
        ScriptService needsFetch;
        if (!claim()) return;
        // THE DECISION INLINE, THE FETCH ON A THREAD. A mapping already cached is applied before this
        // returns, so the caller's very next current() sees it -- which is the difference between the
        // editor opening with readable names and opening with runtime ones and correcting itself.
        needsFetch = decide();
        if (needsFetch == null) return;

        // Daemon, because mapping data must never be the reason a game cannot exit. One-shot, because
        // there is exactly one artifact to acquire per process.
        // UNCANCELLABLE, and that is the lazy path's nature rather than an omission: nobody asked for
        // it, nothing is watching it, and there is no job to press an × on.
        Thread worker = new Thread(() -> fetch(needsFetch, Progress.NONE, () -> false),
                "crystalgui-mappings");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The DECISION, always synchronous. Returns the platform when a network fetch is still needed.
     *
     * <p>Split from the fetch because a mapping already in the cache must be applied <b>before the first
     * analysis</b>, on whatever thread asked — otherwise the editor shows runtime names and then silently
     * changes its mind a moment later, which reads as the names being unstable rather than as a load
     * having completed. Only the network half is worth moving off the caller.</p>
     */
    private static ScriptService decide() {

        ScriptService platform = CgPlatform.get(ScriptServices.SERVICE);
        if (platform == ScriptService.NONE) return null;

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
                              java.util.function.BooleanSupplier cancelled) {
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
