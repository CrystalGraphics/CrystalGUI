package com.crystalgui.language.platform;

/**
 * The one registered {@link ScriptPlatform}, and the only way to reach it.
 *
 * <p>Mirrors {@code CgPlatform}: a loader registers one bundle during its own init, and everything else
 * reads it here. There is deliberately no setter per concern — a platform is registered whole or not at
 * all, because half-registration is the failure mode that shape rules out.</p>
 *
 * <h3>Absent is the default and answers {@link ScriptPlatform#NONE}</h3>
 *
 * <p>Never null. A caller that has to null-check a platform is a caller that will forget, and the
 * behaviour it would forget into — read the classloader, no mappings — is exactly what {@code NONE}
 * already does. Off a Minecraft host this class is invisible.</p>
 */
public final class ScriptPlatforms {

    private static volatile ScriptPlatform current = ScriptPlatform.NONE;

    private ScriptPlatforms() {
    }

    /**
     * Registers the running platform. Called once, from a loader's init.
     *
     * <p>Re-registering replaces, which is what a test that installs a fake needs; it is not an
     * invitation to swap platforms at runtime, and nothing caches the answer across a swap.</p>
     */
    public static void register(ScriptPlatform platform) {
        current = platform == null ? ScriptPlatform.NONE : platform;
    }

    /** The running platform, never null. */
    public static ScriptPlatform current() {
        return current;
    }

    /** Restores the no-platform default. For tests that registered a fake. */
    public static void reset() {
        current = ScriptPlatform.NONE;
    }
}
