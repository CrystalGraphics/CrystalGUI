package com.crystalgui.language.map;

import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptPlatform;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.language.platform.ScriptPlatforms;

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
 * <p>The read goes through {@link ScriptPlatform#liveBytes()} — the same source the compiler resolves
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
        if (!started) begin();
        return current;
    }

    private static synchronized void begin() {
        if (started) return;
        started = true;

        ScriptPlatform platform = CgPlatform.get(ScriptPlatforms.SERVICE);
        if (platform == ScriptPlatform.NONE) return;

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
            return;
        }

        Boolean readable = isReadable(platform, probe);
        if (readable == null) {
            System.err.println("[crystalgui] could not read " + probe.internalName()
                    + " to tell which namespace this runtime speaks; assuming it is already readable");
            return;
        }
        if (readable) {
            System.err.println("[crystalgui] the runtime already speaks readable names ("
                    + probe.internalName() + " declares " + probe.readableMember() + ")");
            return;
        }

        if (MappingCache.isComplete(coordinates, platform.cacheRoot())) {
            // ON THIS THREAD, deliberately: it is a parse and no network, and a mapping that is ready
            // before the first analysis avoids a window where the editor shows runtime names and then
            // silently changes its mind.
            apply(MappingCache.load(coordinates, platform.cacheRoot()));
            return;
        }

        // A DAEMON THREAD rather than a scheduler, and rather than the caller's thread. A network fetch
        // reached from a language's register() would sit inside initGui and stall the client for as long
        // as an unreachable host takes to time out. Daemon, because mapping data must never be the reason
        // a game cannot exit -- and one-shot, because there is exactly one artifact to acquire per
        // process. A JobScheduler would be the tidier home and lives above this layer; reaching up for it
        // would invert the dependency for a single thread.
        Thread fetch = new Thread(() -> apply(MappingCache.load(coordinates, platform.cacheRoot())),
                "crystalgui-mappings");
        fetch.setDaemon(true);
        fetch.start();
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
    private static Boolean isReadable(ScriptPlatform platform, NamespaceProbe probe) {
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
