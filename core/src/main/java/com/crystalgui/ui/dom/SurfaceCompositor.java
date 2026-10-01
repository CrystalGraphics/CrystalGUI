package com.crystalgui.ui.dom;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.render.UiGpu;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The render thread's side of a document on a sequence: it draws the newest committed frame, or the last one again, and
 * asks for the next with at most one in flight (plan engine-threaded-ui §2.4).
 *
 * <pre>{@code
 * // the render thread, once a host frame, inside the host's draw bracket
 * UiCommit shown = compositor.present(width, height);
 * compositor.requestFrame(() -> recordOnTheSequence());   // runs on the sequence; its commit shows next frame
 * }</pre>
 *
 * <ul>
 *   <li>A frame committed is presented exactly once; a host frame with nothing new re-presents the last
 *       ({@link UiGpu#presentAgain}), so a busy document shows its previous picture rather than nothing.</li>
 *   <li>{@link #requestFrame} refuses while a frame is in flight: the caller carries its delta to the next request.</li>
 * </ul>
 */
final class SurfaceCompositor<F> {

    private final UiSequence sequence;
    private final AtomicReference<UiCommit<F>> pending = new AtomicReference<>();
    private volatile boolean inFlight;
    @Nullable
    private UiCommit<F> active;

    /** How long a frame may stay in flight before the sequence is reported as not responding. */
    private static final long HANG_NANOS = 2_000_000_000L;
    private volatile long requestedNanos;
    private boolean hangReported;

    SurfaceCompositor(UiSequence sequence) {
        this.sequence = sequence;
    }

    /**
     * Draws the newest commit onto the bound host target, or the last one again when nothing new arrived, and answers
     * the commit shown. Render thread. Null until the first commit.
     */
    @Nullable
    UiCommit<F> present(int width, int height) {
        return present(width, height, commit -> false);
    }

    /**
     * {@link #present(int, int)}, letting {@code move} write a commit's property values first: given the commit about to
     * be drawn, it answers whether it changed them. A fresh commit is presented with them; an older one is executed again
     * ({@link UiGpu#redraw}) when they changed, and shown again as it was otherwise.
     */
    UiCommit<F> present(int width, int height, Predicate<UiCommit<F>> move) {
        UiCommit<F> fresh = pending.getAndSet(null);
        if (fresh != null) {
            active = fresh;
            if (fresh.frame() != null) {
                move.test(fresh);
                UiGpu.present(fresh.frame());
            }
        } else if (active != null && active.frame() != null) {
            if (move.test(active)) UiGpu.redraw(width, height);
            else UiGpu.presentAgain(width, height);
        }
        return active;
    }

    /** The commit last presented, without drawing anything; null until the first. */
    @Nullable
    UiCommit<F> active() {
        UiCommit<F> fresh = pending.get();
        return fresh != null ? fresh : active;
    }

    /**
     * Posts {@code work} to the sequence, whose answer is presented by a later {@link #present}. Render thread.
     *
     * @return false, and nothing posted, while the previous frame is in flight or not yet presented: a frame dropped
     *         unpresented never gives its buffers back
     */
    /** Whether {@link #requestFrame} would take a frame now. Render thread. */
    boolean accepting() {
        if (inFlight) {
            reportIfHung();
            return false;
        }
        return pending.get() == null;
    }

    boolean requestFrame(Supplier<UiCommit<F>> work) {
        if (inFlight) {
            reportIfHung();
            return false;
        }
        if (pending.get() != null) return false;
        inFlight = true;
        requestedNanos = System.nanoTime();
        hangReported = false;
        sequence.execute(() -> {
            try {
                UiCommit<F> commit = work.get();
                if (commit != null) pending.set(commit);
            } finally {
                inFlight = false;
            }
        });
        return true;
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
