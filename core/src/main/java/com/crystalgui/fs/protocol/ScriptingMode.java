package com.crystalgui.fs.protocol;

/**
 * <b>Whether a project's files may be run, and by whom</b> - a capability a server grants, never a
 * feature a client has.
 *
 * <p>It travels beside {@code mayRead} and {@code mayWrite} in a project's capabilities, because it is
 * the same kind of statement made at the same moment: what this actor may do with this project, said
 * once by whoever owns the files. The Run shell reads it and disables the command with its reason when
 * the answer is {@link #SERVER_ONLY}.</p>
 *
 * <h3>Why it exists</h3>
 *
 * <p>Run compiles the buffer in front and executes it <b>in the client's JVM</b>, whatever project the
 * file came from. On a dedicated server, ungoverned, that is a live scripting environment inside every
 * player's client reachable from any project they can edit. This is the switch that closes it.</p>
 *
 * <h3>What it buys, stated so it is not oversold</h3>
 *
 * <p>No client-side check stops a modified client, and nothing can. What this buys is exact: a
 * <b>stock</b> client offers no live-scripting surface while connected to a server that has not granted
 * one - the same guarantee any anti-cheat that is not a rootkit offers.</p>
 */
public enum ScriptingMode {

    /**
     * Compile what is on screen and run it here — today's behaviour, and the right one when the machine
     * is the player's own.
     *
     * <p>Always in single-player, because the player owns the JVM they would be "attacking"; on a
     * dedicated server only for an actor its configuration grants it to.</p>
     */
    LIVE,

    /**
     * No local Run. Scripts run here only when the server sends one it has validated.
     *
     * <p>A dedicated server's default for its own projects. The Run panel stays — it is the transcript
     * of what the server ran — and the command is disabled with its reason, because a row that silently
     * does nothing teaches people the feature is broken rather than unavailable.</p>
     */
    AUTHORIZED,

    /**
     * Nothing runs and nothing arrives.
     *
     * <p>The client's own rule for its LOCAL projects while it is connected to a remote server: no
     * server speaks for those files, so nobody can authorise them.</p>
     */
    NONE;

    /** Whether the Run command may compile and execute here. */
    public boolean allowsLocalRun() {
        return this == LIVE;
    }

    /**
     * Reads a mode off the wire, answering {@link #LIVE} for anything unrecognised.
     *
     * <p>Which is the compatible answer rather than the safe-looking one, and deliberately: an absent
     * or unknown value means an older server, and treating that as a refusal would take the Run command
     * away from every single-player world that has not updated. The refusal that matters is the
     * server's own — it is what does not send scripts — and a client cannot enforce a posture its
     * server has never heard of.</p>
     */
    public static ScriptingMode parse(String text) {
        for (ScriptingMode mode : values()) {
            if (mode.name().equalsIgnoreCase(text)) return mode;
        }
        return LIVE;
    }
}
