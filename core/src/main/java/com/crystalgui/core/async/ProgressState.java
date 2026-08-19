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
 * @param unit        what {@link #done} and {@link #total} are counting, which is the only thing that
 *                    lets a reader render them — see {@link #summary}
 */
public record ProgressState(String what, String detail, long done, long total, long begunAtMillis,
                            Progress.Unit unit) {

    /** Counting things, which is the ordinary case and needs no readout. */
    public ProgressState(String what, String detail, long done, long total, long begunAtMillis) {
        this(what, detail, done, total, begunAtMillis, Progress.Unit.ITEMS);
    }

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

    /**
     * <b>{@code "12.4 MB of 48.9 MB · 30s left"}</b> — the readout beside the bar, or null.
     *
     * <h3>Why the chrome cannot work this out for itself</h3>
     *
     * <p>A bar without numbers is half a report: it says how far along but not how big, how fast, or how
     * long is left, which are the three things somebody watching a download actually wants. The chrome has
     * {@link #done} and {@link #total} and still cannot render them, because <b>it does not know what they
     * count</b> — a job stepping through 1,178 files would come out as "12 of 1178 bytes". That is what
     * {@link #unit} is for, and why it is on the state rather than guessed downstream.</p>
     *
     * <h3>Here rather than in the widget, and once rather than per producer</h3>
     *
     * <p>Every determinate byte job gets the same readout with no work: the engine-band fetch, the mapping
     * fetch and the JDK extract are three consumers already, and a formatter per producer is three
     * roundings, three ways of spelling a megabyte and three ETA bugs. It is also the half that can be
     * tested without a frame.</p>
     *
     * <p>Null for {@link Progress.Unit#ITEMS}, because a count of files is what the bar and the detail line
     * already say and repeating it as "412 of 1178 items" adds nothing.</p>
     *
     * @param nowMillis the same clock {@link #begunAtMillis} was taken on
     */
    public String summary(long nowMillis) {
        if (unit != Progress.Unit.BYTES) return null;
        if (done <= 0) return null;
        String transferred = bytes(done);
        if (isIndeterminate()) return transferred;

        StringBuilder out = new StringBuilder(transferred).append(" of ").append(bytes(total));
        String left = remaining(nowMillis);
        if (left != null) out.append(" · ").append(left);
        return out.toString();
    }

    /**
     * How long is left at the average rate so far, or null while that cannot be said honestly.
     *
     * <p><b>Average, not instantaneous.</b> An instantaneous rate over one report interval is mostly noise
     * on a connection that stalls and bursts, and it makes the number jump about in a way that reads as
     * broken. The average settles, which is what somebody deciding whether to wait actually wants.</p>
     *
     * <p>Suppressed for the first second and once the numbers say it is done — an estimate from two
     * chunks is a guess with a decimal point on it, and "0s left" on a finished transfer is noise.</p>
     */
    private String remaining(long nowMillis) {
        long elapsed = nowMillis - begunAtMillis;
        if (elapsed < 1000L || done >= total) return null;
        double perMilli = (double) done / (double) elapsed;
        if (perMilli <= 0d) return null;
        long seconds = (long) ((total - done) / perMilli / 1000d);
        if (seconds < 1) return null;
        if (seconds < 60) return seconds + "s left";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s left";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m left";
    }

    /**
     * {@code 12.4 MB}. Binary multiples, because that is what every file manager on every platform this
     * runs on reports, and a download that says 48.9 MB against a 51,200,000-byte file on disk reads as
     * two different files.
     */
    public static String bytes(long count) {
        if (count < 1024L) return count + " B";
        double value = count;
        String[] units = { "KB", "MB", "GB", "TB" };
        int at = -1;
        while (value >= 1024d && at < units.length - 1) {
            value /= 1024d;
            at++;
        }
        // ONE DECIMAL BELOW A HUNDRED, none above: "12.4 MB of 48.9 MB", "512 MB", "1.0 GB".
        //
        // The first draft cut the decimal at ten, on the reasoning that three significant digits is
        // precision nobody reads. True of a static number and wrong for this one -- a 49 MB download then
        // reads "12 MB of 49 MB" and advances in whole megabytes, so the readout barely moves and the
        // thing it exists to show is the thing it hides. Every browser shows one decimal here.
        return (value < 100d ? String.format("%.1f", value) : String.valueOf(Math.round(value)))
                + " " + units[at];
    }
}
