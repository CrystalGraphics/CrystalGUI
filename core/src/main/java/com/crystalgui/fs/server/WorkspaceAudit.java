package com.crystalgui.fs.server;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * <b>A line per mutation, naming who did what to which file</b> — and a rate limit on the same counter.
 *
 * <h3>Why a workspace needs one and a filesystem does not</h3>
 *
 * <p>This server hands a remote peer write access to a directory on
 * somebody's machine. Every other system that does that keeps a record: it is what makes "a file I did
 * not touch has changed" answerable, and it is the difference between a permission model and a
 * permission model you can trust. The workspace had none — a write was authorised, performed, and left
 * no trace beyond the file's own mtime.</p>
 *
 * <h3>The limit rides the same counter, deliberately</h3>
 *
 * <p>A rate limit needs to count mutations per actor over a window, which is exactly what an audit
 * already holds. Two structures counting the same events would eventually disagree, and the one that
 * disagreed would be the one deciding whether to refuse somebody.</p>
 *
 * <p>The refusal has its own error code so a client can back off rather than treating it as a permission
 * failure and giving up — {@code FsError.RATE_LIMITED}. Minecraft kicks on packet flood and Chromium's
 * {@code ReportBadMessage} kills the sending renderer; neither takes the document down.</p>
 */
public final class WorkspaceAudit {

    /** How many mutations one actor may make in {@link #WINDOW_MILLIS}. */
    public static final int DEFAULT_LIMIT = 240;

    /** The window the limit is measured over. One minute — long enough that a paste of a hundred
     * files is not a flood, short enough that a runaway loop is caught inside a few seconds. */
    public static final long WINDOW_MILLIS = 60_000L;

    /** How many entries are kept for reading back. Older ones are dropped; the log line survives. */
    public static final int RETAINED = 512;

    /**
     * One thing that was done.
     *
     * @param at    when, on the clock this audit was given
     * @param actor who — the id, because a display name changes and an id does not
     * @param path  what
     */
    public record Entry(long at, String actor, WorkspaceOperation operation, String path,
                        boolean refused, String detail) {
    }

    private final LongSupplier clockMillis;
    private final int limit;
    private final Deque<Entry> entries = new ArrayDeque<>();

    /** Per actor, the timestamps inside the window. Pruned on every ask, so it cannot grow unbounded. */
    private final java.util.Map<String, Deque<Long>> recent = new java.util.LinkedHashMap<>();

    public WorkspaceAudit() {
        this(System::currentTimeMillis, DEFAULT_LIMIT);
    }

    /**
     * @param clockMillis the time source — an input, never read directly, for the reason
     *                    {@code TextBuffer} records about {@code TransitionEngine}: time that decides
     *                    behaviour has to be steppable or the behaviour is asserted indirectly for ever
     */
    public WorkspaceAudit(LongSupplier clockMillis, int limit) {
        this.clockMillis = clockMillis;
        this.limit = Math.max(1, limit);
    }

    /**
     * Whether this actor may make another mutation now.
     *
     * <p>Asked <b>before</b> the work, so a refusal costs nothing. Reads do not count: they are bounded
     * by what exists and a client legitimately reads a great deal.</p>
     */
    public boolean allow(WorkspaceActor actor) {
        long now = clockMillis.getAsLong();
        Deque<Long> mine = recent.computeIfAbsent(actor.id(), key -> new ArrayDeque<>());
        while (!mine.isEmpty() && now - mine.peekFirst() > WINDOW_MILLIS) mine.pollFirst();
        return mine.size() < limit;
    }

    /** Records a mutation that happened. */
    public void record(WorkspaceActor actor, WorkspaceOperation operation, CgPath path) {
        write(actor, operation, path, false, "");
    }

    /** Records one that was refused, and why — which is the half worth having at three in the morning. */
    public void refused(WorkspaceActor actor, WorkspaceOperation operation, @Nullable CgPath path,
                        String reason) {
        write(actor, operation, path, true, reason);
    }

    private void write(WorkspaceActor actor, WorkspaceOperation operation, @Nullable CgPath path,
                       boolean refused, String detail) {
        long now = clockMillis.getAsLong();
        Entry entry = new Entry(now, actor.id(), operation,
                path == null ? "" : path.toString(), refused, detail);
        entries.addLast(entry);
        while (entries.size() > RETAINED) entries.pollFirst();

        if (!refused) {
            Deque<Long> mine = recent.computeIfAbsent(actor.id(), key -> new ArrayDeque<>());
            mine.addLast(now);
            while (!mine.isEmpty() && now - mine.peekFirst() > WINDOW_MILLIS) mine.pollFirst();
        }

        // AT INFO, and one line. A mutation is rare next to a read and a server operator has to be able
        // to find it without turning debug on for everything.
        if (refused) {
            CrystalGuiCore.LOGGER.info("[cgui-fs] REFUSED {} {} by {}: {}",
                    operation, entry.path(), actor.id(), detail);
        } else {
            CrystalGuiCore.LOGGER.info("[cgui-fs] {} {} by {}", operation, entry.path(), actor.id());
        }
    }

    /** What has been recorded, oldest first. For a server command that shows it. */
    public List<Entry> recent() {
        return new ArrayList<>(entries);
    }

    /** How many mutations this actor has made inside the window. */
    public int rateFor(WorkspaceActor actor) {
        long now = clockMillis.getAsLong();
        Deque<Long> mine = recent.get(actor.id());
        if (mine == null) return 0;
        while (!mine.isEmpty() && now - mine.peekFirst() > WINDOW_MILLIS) mine.pollFirst();
        return mine.size();
    }
}
