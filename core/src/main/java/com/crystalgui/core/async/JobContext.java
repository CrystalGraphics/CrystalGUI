package com.crystalgui.core.async;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handed to a job while it runs, so it can find out that nobody wants its answer any more.
 *
 * <h3>Cancellation is cooperative, because there is no honest alternative</h3>
 * <p>{@code Thread.stop} is removed, and interrupting a thread mid-compile leaves the compiler's own
 * state undefined — ECJ and a jar scan are exactly the kind of long, stateful work that must be allowed
 * to unwind itself. So a job <b>polls</b>. Work that never polls is not wrong, merely uninterruptible;
 * its result is still discarded on delivery (see {@link JobScheduler}), so correctness never depends on
 * a job checking, only responsiveness does.</p>
 *
 * <h3>Where to poll</h3>
 * <p>At loop heads, and between phases. The two shapes that matter here are "for every class file on
 * the classpath" and "for every compilation unit" — one check per iteration costs nothing measurable
 * against the body of either.</p>
 */
public final class JobContext {

    /**
     * Written by the UI thread (on supersede or dispose), read by the worker.
     *
     * <p>Atomic rather than {@code volatile boolean} for no reason beyond wanting the flag and its
     * one-way transition in a single named type; the visibility guarantee is what is actually being
     * bought, and either would give it.</p>
     */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Whether the answer is still wanted. Poll it; do not cache it.
     *
     * <p>Once true it never goes back to false — a superseded job is superseded for good, and the
     * replacement gets its own context.</p>
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Throws {@link JobCancelledException} if the job has been superseded — the terse form of
     * {@link #isCancelled()} for work that has nothing to clean up.
     *
     * <p>The scheduler catches it and drops the result silently, so it is a control-flow signal rather
     * than an error: nothing is logged and nothing is reported as having failed.</p>
     */
    public void throwIfCancelled() {
        if (isCancelled()) throw new JobCancelledException();
    }

    /** Called by the scheduler only. */
    void cancel() {
        cancelled.set(true);
    }

    /** What {@link #throwIfCancelled()} throws. Carries no stack trace — it is expected, not exceptional. */
    public static final class JobCancelledException extends RuntimeException {
        JobCancelledException() {
            super("job cancelled", null, false, false);
        }
    }
}
