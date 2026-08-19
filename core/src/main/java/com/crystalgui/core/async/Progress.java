package com.crystalgui.core.async;

/**
 * <b>How a job says how far along it is.</b> Write-only, obtained from {@link JobContext#progress()}.
 *
 * <h3>Write-only, deliberately</h3>
 *
 * <p>There is no {@code isCancelled()} here. {@link JobContext} already answers that, and two answers to
 * one question is how two callers come to disagree about which is authoritative. A job asks its context
 * whether to stop and tells its progress how far it has got.</p>
 *
 * <h3>Absolute, not a delta</h3>
 *
 * <p>{@link #advance(long)} takes units <em>completed</em>, not units since last time. A delta that is
 * dropped or double-counted desyncs the bar permanently and silently, and nothing downstream can detect
 * it; an absolute value cannot drift. Every caller that has a delta also has a running total — a stream
 * has a byte counter — so this costs nothing to supply.</p>
 *
 * <h3>Never null, so nobody branches on being watched</h3>
 *
 * <p>{@link JobContext#progress()} always returns one, and {@link #NONE} when there is nothing to draw. A
 * job reports unconditionally; whether anyone is looking is not its business. Same reasoning as a platform
 * service slot's absent-value.</p>
 *
 * <h3>Reporting is expected to be rate-limited by the caller</h3>
 *
 * <p>Each report allocates a {@link ProgressState} — that is what makes a reader's view consistent. A
 * report per 8 KB chunk is a couple of thousand allocations for a 16 MB download, feeding a bar that
 * redraws sixty times a second. Accumulate and report on a threshold.</p>
 */
public interface Progress {

    /** A sink that discards everything. What an unobserved job gets, and never null. */
    Progress NONE = new Progress() {
        @Override
        public void begin(String what, long total) {
        }

        @Override
        public void advance(long done) {
        }

        @Override
        public void detail(String item) {
        }

        @Override
        public String toString() {
            return "Progress.NONE";
        }
    };

    /**
     * Announces the work, and <b>is what makes it visible</b> — a job that never calls this is never
     * drawn, however long it runs.
     *
     * <p>That is not an oversight to fix later. The scheduler runs an analysis on every keystroke; if
     * everything appeared, the chrome would flicker continuously. Appearing is opt-in.</p>
     *
     * @param what  the primary line, present tense — {@code "Downloading engine band 17"}
     * @param total the unit count, or <b>negative for indeterminate</b> when it cannot be known
     */
    void begin(String what, long total);

    /** Units completed so far — <b>absolute</b>, against the total given to {@link #begin}. */
    void advance(long done);

    /** The secondary line — which item, usually a file or artifact name. */
    void detail(String item);
}
