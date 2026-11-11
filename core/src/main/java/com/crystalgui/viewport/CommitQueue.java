package com.crystalgui.viewport;

import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.render.UiPresenter;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The render thread's side of a sequenced document: it draws the newest committed frame, or the last one again, and asks
 * for the next with at most one in flight. Chromium's commit between the main thread and the compositor.
 *
 * <pre>{@code
 * // the render thread, once a host frame, in a renderer's turn at a GUI stage
 * UiCommit shown = queue.present(stage, presenter, commit -> motion.move(commit));
 * if (queue.accepting()) queue.requestFrame(() -> recordOnTheSequence(), () -> recordAgain());
 * }</pre>
 *
 * <ul>
 *   <li>A frame committed is presented exactly once; a host frame with nothing new re-presents the last
 *       ({@link UiPresenter#presentAgain}), so a busy document shows its previous picture rather than nothing.</li>
 *   <li>{@link #requestFrame} refuses while a frame is in flight or one waits to be presented.</li>
 *   <li>A recording longer than a host frame chains the next at once, on the sequence, rather than waiting a host
 *       frame to be asked: a document at 9 ms a frame then shows 110 frames a second where it showed 60. At most two
 *       commits ever wait, oldest presented first.</li>
 * </ul>
 */
final class CommitQueue {

    /** How long a frame may stay in flight before the sequence is reported as not responding. */
    private static final long HANG_NANOS = 2_000_000_000L;

    private final UiSequence sequence;
    /** Commits waiting to be presented, oldest first; at most two. Guarded by itself. */
    private final ArrayDeque<UiCommit> ready = new ArrayDeque<>(2);
    private volatile boolean inFlight;
    /** The host's frame interval, as presents measure it: what a recording must exceed to chain. */
    private volatile long hostFrameNanos = 16_666_667L;
    private long lastPresentNanos;
    @Nullable
    private volatile UiCommit active;
    private volatile long requestedNanos;
    private boolean hangReported;

    CommitQueue(UiSequence sequence) {
        this.sequence = sequence;
    }

    /**
     * Shows the newest commit at {@code stage} through {@code presenter}, or the last one again when nothing new
     * arrived, and answers the commit shown; null until the first. {@code move} writes a commit's property values first:
     * given the commit about to be drawn, it answers whether it changed them. A fresh commit is presented with them; an
     * older one is executed again ({@link UiPresenter#redraw}) when they changed, and composited again as it was
     * otherwise. With no stage nothing is drawn. Render thread.
     */
    @Nullable
    UiCommit present(@Nullable CgStageFrame stage, UiPresenter presenter, Predicate<UiCommit> move) {
        long now = System.nanoTime();
        long gap = now - lastPresentNanos;
        if (lastPresentNanos != 0L && gap > 0L && gap < 100_000_000L) hostFrameNanos = (hostFrameNanos * 7L + gap) / 8L;
        lastPresentNanos = now;
        UiCommit fresh;
        synchronized (ready) {
            fresh = ready.pollFirst();
        }
        UiCommit shown = active;
        if (fresh != null) {
            active = shown = fresh;
            if (fresh.frame() != null) {
                move.test(fresh);
                if (stage != null) presenter.present(stage, fresh.frame());
            }
        } else if (shown != null && shown.frame() != null) {
            // What moves is a window's node or a drag's: what the layers under it drew holds still, so not again.
            boolean moved = move.test(shown);
            if (stage != null && !(moved && presenter.redraw(stage, true))) presenter.presentAgain(stage);
        }
        return shown;
    }

    /** The index of the commit last presented; 0 until the first. Render thread. */
    long shownIndex() {
        UiCommit shown = active;
        return shown == null ? 0L : shown.index();
    }

    /** The newest commit, presented or waiting to be; null until the first. Any thread. */
    @Nullable
    UiCommit newest() {
        synchronized (ready) {
            UiCommit newest = ready.peekLast();
            if (newest != null) return newest;
        }
        return active;
    }

    /** Whether {@link #requestFrame} would take a frame now. Render thread. */
    boolean accepting() {
        if (inFlight) {
            reportIfHung();
            return false;
        }
        synchronized (ready) {
            return ready.isEmpty();
        }
    }

    /**
     * Posts {@code work} to the sequence, and {@code next} after it for as long as each recording takes longer than a
     * host frame; a later {@link #present} shows what they commit. Render thread.
     *
     * @return false, and nothing posted, while the previous frame is in flight or not yet presented: a frame dropped
     *         unpresented never gives its buffers back
     */
    boolean requestFrame(Supplier<UiCommit> work, Supplier<UiCommit> next) {
        if (!accepting()) return false;
        inFlight = true;
        requestedNanos = System.nanoTime();
        hangReported = false;
        sequence.execute(() -> record(work, next));
        return true;
    }

    /** One recording on the sequence, and the next posted behind it when this one missed a host frame. */
    private void record(Supplier<UiCommit> work, Supplier<UiCommit> next) {
        boolean chained = false;
        try {
            long started = System.nanoTime();
            UiCommit commit = work.get();
            long took = System.nanoTime() - started;
            int waiting;
            synchronized (ready) {
                if (commit != null) ready.addLast(commit);
                waiting = ready.size();
            }
            // POSTED, not run here: input queued behind this frame runs before the next one records.
            chained = commit != null && took > hostFrameNanos && waiting < 2;
            if (chained) {
                requestedNanos = System.nanoTime();
                sequence.execute(() -> record(next, next));
            }
        } finally {
            if (!chained) inFlight = false;
        }
    }

    /**
     * Once per frame in flight past {@link #HANG_NANOS}: what the sequence is doing, from its own stack. The document is
     * not responding; the last frame keeps showing meanwhile.
     */
    private void reportIfHung() {
        if (hangReported || System.nanoTime() - requestedNanos < HANG_NANOS) return;
        hangReported = true;
        Thread thread = sequence.runningThread();
        StringBuilder stack = new StringBuilder();
        if (thread != null) {
            for (StackTraceElement frame : thread.getStackTrace()) stack.append("\n\tat ").append(frame);
        }
        CrystalGuiCore.LOGGER.warn("[cgui] sequence '{}' has not committed a frame in {} ms; it is {}{}", sequence.name(),
                (System.nanoTime() - requestedNanos) / 1_000_000,
                thread == null ? "idle, so the frame was lost" : "on " + thread.getName(), stack);
    }
}
