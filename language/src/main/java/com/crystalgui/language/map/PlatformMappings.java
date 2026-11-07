package com.crystalgui.language.map;

import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgui.language.platform.ScriptService;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Which namespace this runtime speaks, decided by asking it — and the mapping that follows.
 *
 * <h3>This module drives the acquisition</h3>
 *
 * <p>Nothing outside {@code language/} needs to call anything. The first {@link #current()} probes,
 * applies a cached mapping on the calling thread, and hands a genuine download to the job pool
 * ({@code CgJob.executor}), which needs no frame or tick to drain it, so a dedicated server fetches too.</p>
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
 * <p>The game never changes under a process, so this is a process-wide answer computed once. But a first launch may need a download, and languages register from the client thread —
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
     * <b>Begins acquisition now</b>, so the first analysis does not have to trigger it.
     *
     * <pre>{@code
     * PlatformMappings.start();      // at the language mod's start, ahead of any editor
     * }</pre>
     *
     * <p>Without this the first caller of {@link #current()} is the first analysis, which therefore
     * <b>cannot win</b>: it starts the download and reads the identity in the same breath, and its
     * diagnostics are computed against runtime names. Services are attached once per document and
     * nothing re-runs an analysis when a mapping lands, so those diagnostics stand until the file is
     * closed and reopened — "mappings work on the second launch and never on the first", which
     * {@code LibrarySources.forgetIfMappingChanged} describes in the same words about its own cache.</p>
     *
     * <p>Called at mod init this is many seconds of world-loading ahead of any editor, which is what
     * makes the race practically decided rather than merely likelier. It is <b>not</b> a guarantee: a
     * slow enough first fetch still lands after an analysis, and that analysis is still stale.</p>
     *
     * <p>Idempotent and cheap to call from anywhere — the claim makes it one-shot per process.</p>
     */
    public static void start() {
        if (!started) startLazily();
    }

    /** What a decision reads: the game's bytes, the cache, the mapping and the probe. */
    record Inputs(ReadableView.ByteSource bytes, Path cacheRoot, MappingCoordinates coordinates,
                  NamespaceProbe probe) {
        static Inputs ofGame() {
            if (!ScriptService.live()) return null;
            return new Inputs(ScriptService.liveBytes(), ScriptService.cacheRoot(), ScriptService.mappings(),
                    ScriptService.namespaceProbe());
        }
    }

    // A test's platform, in place of the game's
    private static volatile Inputs testInputs;

    /** Probe, apply what is cached, and start a download on the job pool, which needs no frame to drain it. */
    private static void startLazily() {
        if (!claim()) return;
        // Nothing may leave here: current() is called inside catch-to-null misses (PlatformTypeBytes), so a throw
        // would read as "no such type" with the claim already taken, and identity names for good
        Inputs needsFetch;
        try {
            needsFetch = decide();
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.warn("[cgui] mappings: the decision failed; runtime names will be"
                    + " shown as they are", failed);
            return;
        }
        if (needsFetch == null) return;
        CgJob.executor(CgJobLane.BACKGROUND).execute(() -> {
            try {
                fetch(needsFetch, () -> false);
            } catch (RuntimeException | LinkageError failed) {
                CrystalGuiCore.LOGGER.warn("[cgui] mappings: the fetch failed; runtime names will be"
                        + " shown as they are", failed);
            }
        });
    }

    /**
     * The decision of a {@link #claim()}, on the calling thread: a cached mapping is applied before this returns, so
     * the first analysis already sees it. Returns what a network fetch still needs, or null when nothing is owed.
     */
    static Inputs decideClaimed() {
        return decide();
    }

    private static Inputs decide() {
        Inputs in = testInputs != null ? testInputs : Inputs.ofGame();
        if (in == null) {
            // Said: a thread or classloader fault (a band's own copy of this class) arrives here too
            CrystalGuiCore.LOGGER.info("[cgui] mappings: NOT_CONFIGURED -- no game (asked from {}); runtime names"
                    + " will be shown as they are", Thread.currentThread().getName());
            return null;
        }
        if (in.probe().isNone() || in.coordinates().isNone()) {
            CrystalGuiCore.LOGGER.info("[cgui] mappings: NOT_CONFIGURED -- {}; runtime names will be shown as"
                    + " they are", in.probe().isNone() ? "no namespace probe" : "no mapping coordinates");
            return null;
        }

        Boolean readable = isReadable(in.bytes(), in.probe());
        if (readable == null) {
            CrystalGuiCore.LOGGER.warn("[cgui] could not read {} to tell which namespace this runtime"
                    + " speaks; assuming it is already readable", in.probe().internalName());
            return null;
        }
        if (readable) {
            CrystalGuiCore.LOGGER.info("[cgui] the runtime already speaks readable names ({} declares"
                    + " {})", in.probe().internalName(), in.probe().readableMember());
            return null;
        }

        if (MappingCache.isComplete(in.coordinates(), in.cacheRoot())) {
            // On this thread: a parse, no network, and ready before the first analysis
            apply(MappingCache.load(in.coordinates(), in.cacheRoot()));
            return null;
        }
        return in;
    }

    private static void fetch(Inputs in, BooleanSupplier cancelled) {
        apply(MappingCache.load(in.coordinates(), in.cacheRoot(), cancelled));
    }

    private static void apply(MappingCache.Result result) {
        // SAID ONCE, WHICHEVER IT IS. "No mappings configured" and "the download failed" produce the same
        // thing on screen -- runtime names -- and are entirely different to somebody offline on purpose.
        //
        // THE LOGGER, not stderr: a launcher keeps the console half in memory and writes only the log
        // file, so every line this class printed was unreadable on the one build that needed it -- an
        // installed client.
        if (result.state() == MappingCache.State.UNAVAILABLE) {
            CrystalGuiCore.LOGGER.warn("[cgui] mappings: {} -- {}", result.state(), result.detail());
        } else {
            CrystalGuiCore.LOGGER.info("[cgui] mappings: {} -- {}", result.state(), result.detail());
        }
        if (!result.mappings().isIdentity()) current = result.mappings();
    }

    /**
     * Whether the probe's type declares its readable member — null when the type cannot be read.
     *
     * <p>Null rather than false, because "I could not tell" and "it is obfuscated" call for different
     * behaviour: treating an unreadable probe as obfuscated would download a mapping and translate every
     * name through it on a runtime that never needed one, which is worse than doing nothing.</p>
     */
    private static Boolean isReadable(ReadableView.ByteSource source, NamespaceProbe probe) {
        byte[] bytes;
        try {
            bytes = source.bytesOf(probe.internalName());
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
        testInputs = null;
    }

    /** Decides from {@code inputs} instead of the game's. For tests, with {@link #resetForTesting()} after. */
    static void useForTesting(Inputs inputs) {
        testInputs = inputs;
    }
}
