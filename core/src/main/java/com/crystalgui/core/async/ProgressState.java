package com.crystalgui.core.async;

/**
 * <b>One whole reading of a job's progress</b> — immutable, so a reader never sees half of one.
 *
 * <h3>Why this is a value rather than fields on the context</h3>
 *
 * <p>The obvious shape is a few {@code volatile} fields updated in place. It does not work: a reader can
 * take {@code done} from after a write and {@code total} from before it, and draw a bar past its own end.
 * Volatile makes each field's read fresh; it does not make a <em>group</em> of them consistent.</p>
 *
 * <p>So a report builds one of these and the context swaps a single reference. A reader sees this state or
 * the previous one, never a mixture.</p>
 *
 * <p>The cost is an allocation per report, which is why {@link Progress} reporting is expected to be
 * rate-limited at the source — a transfer accumulates bytes in a local and reports on a threshold, not per
 * chunk. Those two are one decision: solve them apart and the other comes back.</p>
 *
 * @param what        the primary line — what is happening
 * @param detail      the secondary line — which item, or empty
 * @param done        units completed, against {@link #total}
 * @param total       total units, or negative for indeterminate
 * @param begunAtMillis when {@link Progress#begin} was called, on the scheduler's clock
 */
public record ProgressState(String what, String detail, long done, long total, long begunAtMillis) {

    /** No total to measure against, so a bar cannot be drawn — a stripe is. */
    public boolean isIndeterminate() {
        return total < 0;
    }

    /**
     * How far along, 0..1, or negative when indeterminate.
     *
     * <p><b>Clamped</b>, because a caller that reports more than it promised is reporting a bug and the
     * chrome is the wrong place to show it. A zero total is complete rather than a division by zero:
     * "nothing to do" is done.</p>
     */
    public float fraction() {
        if (isIndeterminate()) return -1f;
        if (total == 0) return 1f;
        if (done <= 0) return 0f;
        if (done >= total) return 1f;
        return (float) ((double) done / (double) total);
    }
}
