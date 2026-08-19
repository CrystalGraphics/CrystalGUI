package com.crystalgui.language.run;

/**
 * What a script is doing — the replacement for an exit code, which does not exist here.
 *
 * <h3>Why not an exit code</h3>
 *
 * <p>IntelliJ can name a run by how it ended because a run is a process and a process ends. An
 * event-driven script does not: it is loaded, it registers handlers, and it sits there being ready.
 * "Finished with exit code 0" is not merely unavailable for that, it would be actively false.</p>
 */
public enum RunState {

    /** Built, never run. The state a script is in the moment it compiles clean. */
    COMPILED,

    /**
     * Executing right now, with a beginning and an end — <b>a one-shot only</b>.
     *
     * <p>Deliberately not used for event-driven work. A per-tick handler genuinely <em>is</em> executing
     * twenty times a second, so surfacing that here would strobe every indicator reading this state
     * twenty times a second and communicate nothing. What a reader wants to know about such a script is
     * that it is {@link #LIVE}, which is steady.</p>
     */
    RUNNING,

    /**
     * Loaded, handlers registered, waiting to fire.
     *
     * <p><b>Named {@code LIVE} and not {@code IDLE} on purpose.</b> "Idle" reads as nothing happening,
     * when what it means is that this will run again without anybody asking — which is the single most
     * important fact about a script that has registered handlers, and the one an exit code cannot
     * express.</p>
     */
    LIVE,

    /**
     * Ran to completion and left nothing behind.
     *
     * <p>The one-shot's ending, and it needs a name of its own: it is not {@link #LIVE} because nothing
     * will fire again, not {@link #STOPPED} because nobody asked it to end, not {@link #FAILED} because
     * nothing went wrong, and emphatically not back to {@link #COMPILED}, which would lose the fact that
     * it ran at all. IntelliJ says "finished with exit code 0" here; without a process there is no code,
     * but there is still the finishing.</p>
     */
    FINISHED,

    /** Interrupted through the kill flag (§19.3). Its transcript survives. */
    STOPPED,

    /** Threw, and registered nothing. Distinct from {@link #STOPPED}: nobody asked for this one. */
    FAILED;

    /** Whether this state means the script can still do something without being asked again. */
    public boolean isActive() {
        return this == RUNNING || this == LIVE;
    }
}
