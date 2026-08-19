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

    /**
     * What {@code done} and {@code total} are counting.
     *
     * <p>Two, and there is no third until something needs one. It exists so a reader can render the
     * numbers — {@link ProgressState#summary} turns bytes into {@code "12.4 MB of 48.9 MB · 30s left"} and
     * refuses to turn a file count into the same sentence, which without this it could not tell apart.</p>
     */
    enum Unit {
        /** Files, classes, steps — anything the bar already says enough about. */
        ITEMS,
        /** A transfer, which earns a size-and-rate readout. */
        BYTES,
    }

    /** A sink that discards everything. What an unobserved job gets, and never null. */
    Progress NONE = new Progress() {
        @Override
        public void begin(String what, long total, Unit unit) {
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
     * <p><b>Calling it again retargets</b> rather than starting a second thing, which is what a download
     * needs: the honest first call is indeterminate — the size is not known until the server has answered,
     * and the connect is the part most likely to hang — and the total arrives with the response. Announce
     * first and retarget, never the reverse: a {@code begin} that waits for the size shows nothing at all
     * for as long as the network takes to refuse, which is precisely the stall this exists to report.</p>
     *
     * <p>Retargeting restarts the appear-after timer for a job that is not yet on screen and leaves one
     * that is where it is, so it cannot flicker.</p>
     *
     * @param what  the primary line, present tense — {@code "Downloading engine band 17"}
     * @param total the unit count, or <b>negative for indeterminate</b> when it cannot be known
     */
    default void begin(String what, long total) {
        begin(what, total, Unit.ITEMS);
    }

    /**
     * The same, saying what is being counted.
     *
     * <p>{@link Unit#BYTES} is what earns a transfer the {@code "12.4 MB of 48.9 MB · 30s left"} readout;
     * see {@link ProgressState#summary}. Counting is the default because most jobs are counting, and a
     * caller that says nothing should get the answer that adds nothing rather than a wrong one.</p>
     */
    void begin(String what, long total, Unit unit);

    /** Units completed so far — <b>absolute</b>, against the total given to {@link #begin}. */
    void advance(long done);

    /** The secondary line — which item, usually a file or artifact name. */
    void detail(String item);
}
