package com.crystalgui.core.async;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

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
     * The scheduler's clock, so a report can stamp itself without the worker owning one.
     *
     * <p>The scheduler's rather than {@code System.currentTimeMillis()}, because every timing policy
     * around progress — the delay before showing, the minimum visible time — is tested against a fake
     * clock, and a stamp taken from the real one would make those tests wall-clock dependent.</p>
     */
    private final LongSupplier clockMillis;

    /**
     * The latest whole reading, or null before {@link Progress#begin} — <b>one reference, swapped</b>.
     *
     * @see ProgressState
     */
    private volatile ProgressState progressState;

    JobContext(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

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

    /**
     * Where this job reports how far along it is. Never null.
     *
     * <p>Reporting is unconditional — {@link Progress#NONE} is not returned here, because this context
     * belongs to a running job and the scheduler is always willing to hold its state. What decides
     * whether anything is <em>drawn</em> is {@link Progress#begin}, which a job may simply never call.</p>
     */
    public Progress progress() {
        return reporter;
    }

    /** The latest whole reading, or null if {@link Progress#begin} was never called. */
    ProgressState progressState() {
        return progressState;
    }

    /**
     * Swaps in a new state built from the old one.
     *
     * <p>Not synchronized. Two threads reporting on one job would race, and that is a caller bug rather
     * than a case to serialise for — a job is one unit of work on one thread. The volatile write is what
     * a reader needs; the read-modify-write is safe because only the worker writes.</p>
     */
    private final Progress reporter = new Progress() {
        @Override
        public void begin(String what, long total, Progress.Unit unit) {
            progressState = new ProgressState(what == null ? "" : what, "", 0L, total,
                    clockMillis.getAsLong(), unit == null ? Progress.Unit.ITEMS : unit);
        }

        @Override
        public void advance(long done) {
            ProgressState current = progressState;
            // BEFORE begin() is meaningless rather than an error -- a library that reports into a job
            // whose owner never announced it should not throw from a progress call.
            if (current == null) return;
            progressState = new ProgressState(current.what(), current.detail(), done,
                    current.total(), current.begunAtMillis(), current.unit());
        }

        @Override
        public void detail(String item) {
            ProgressState current = progressState;
            if (current == null) return;
            progressState = new ProgressState(current.what(), item == null ? "" : item,
                    current.done(), current.total(), current.begunAtMillis(), current.unit());
        }
    };

    /** What {@link #throwIfCancelled()} throws. Carries no stack trace — it is expected, not exceptional. */
    public static final class JobCancelledException extends RuntimeException {
        JobCancelledException() {
            super("job cancelled", null, false, false);
        }
    }
}
