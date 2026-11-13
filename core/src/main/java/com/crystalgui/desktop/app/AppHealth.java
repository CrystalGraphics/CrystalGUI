package com.crystalgui.desktop.app;

import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * Whether an application's document answers, and the ladder that ends one that does not. The shell's thread steps it
 * once a frame; the document's thread reports its commits.
 *
 * <pre>{@code
 * health.offered();                         // the shell handed the document input
 * health.committed();                       // the document finished a frame (its thread)
 * health.step(System.nanoTime());           // the shell, each frame: true when the state moved
 * if (health.state() == AppHealth.State.NOT_RESPONDING) ghost(windows);
 * health.close(now);                        // the user chose Close: abort, then interrupt, then abandon
 * }</pre>
 *
 * <ul>
 *   <li><b>Not responding</b> is input waiting for {@link #HANG_NANOS} with no commit, as DWM's is; a commit
 *       ends it. {@link #patient} defers the next verdict by as long again.</li>
 *   <li><b>The ladder</b>: {@code abort} at once (the engine's checkpoints throw), {@code interrupt} after
 *       {@link #INTERRUPT_NANOS}, {@code abandon} after {@link #ABANDON_NANOS}, which drops the document's thread
 *       from the pool's care. What never calls the engine and never blocks is contained, not stopped.</li>
 * </ul>
 */
final class AppHealth {

    enum State {
        RESPONDING, NOT_RESPONDING, CLOSING, ABANDONED
    }

    static final long HANG_NANOS = 5_000_000_000L;
    static final long INTERRUPT_NANOS = 500_000_000L;
    static final long ABANDON_NANOS = 2_000_000_000L;

    private final AppDocument.Backend backend;
    private final Supplier<String> name;
    private volatile long waitingSince;
    private volatile long lastCommit = System.nanoTime();
    private long patientUntil;
    private volatile State state = State.RESPONDING;
    private long closeStarted;
    private boolean interrupted;
    @Nullable
    private volatile FaultReport report;

    AppHealth(AppDocument.Backend backend, Supplier<String> name) {
        this.backend = backend;
        this.name = name;
    }

    State state() {
        return state;
    }

    /** The report taken when the state became {@link State#NOT_RESPONDING}, or null. */
    @Nullable
    FaultReport report() {
        return report;
    }

    /** Input was handed to the document: from now it owes a commit. Any thread. */
    void offered() {
        if (waitingSince == 0L) waitingSince = System.nanoTime();
    }

    /** The document finished a frame. Its thread. */
    void committed() {
        lastCommit = System.nanoTime();
        waitingSince = 0L;
        if (state == State.NOT_RESPONDING) {
            state = State.RESPONDING;
            report = null;
        }
    }

    /** The user chose Wait: the next verdict comes after another {@link #HANG_NANOS}. The shell's thread. */
    void patient(long now) {
        patientUntil = now + HANG_NANOS;
        if (state == State.NOT_RESPONDING) state = State.RESPONDING;
    }

    /** Starts the ladder: rung 1, the abort. The shell's thread. */
    void close(long now) {
        if (state == State.CLOSING || state == State.ABANDONED) return;
        state = State.CLOSING;
        closeStarted = now;
        interrupted = false;
        backend.abort();
    }

    /** Whether the document's thread is over: unwound at a checkpoint, or abandoned. */
    boolean ended() {
        return state == State.ABANDONED || (state == State.CLOSING && backend.stopped());
    }

    /** Moves the state with the clock; true when it moved. The shell's thread, once a frame. */
    boolean step(long now) {
        switch (state) {
            case RESPONDING -> {
                long since = waitingSince;
                if (since == 0L || now < patientUntil || now - Math.max(since, lastCommit) < HANG_NANOS) return false;
                report = FaultReport.hung(name.get(), backend.runningThread());
                state = State.NOT_RESPONDING;
                return true;
            }
            case CLOSING -> {
                if (backend.stopped()) return false;
                long waited = now - closeStarted;
                if (!interrupted && waited >= INTERRUPT_NANOS) {
                    interrupted = true;
                    backend.interrupt();
                }
                if (waited >= ABANDON_NANOS) {
                    backend.abandon();
                    state = State.ABANDONED;
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }
}
