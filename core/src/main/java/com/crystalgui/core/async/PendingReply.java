package com.crystalgui.core.async;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A {@link Reply} whoever produces the answer settles — the writable half of the pair.
 *
 * <p>Handed out as a {@code Reply}, so a caller can register and cancel and cannot resolve. The
 * producer keeps this type: {@code JobScheduler.Job.submit()} resolves one from {@code drain()}, and
 * the workspace client resolves one from the connection's tick.</p>
 *
 * <h3>Not thread-safe, deliberately</h3>
 *
 * <p>Both producers settle on the frame thread, which is where every callback here has to run anyway —
 * a {@link Reply#then} may touch the tree. Making this concurrent would let a worker settle a reply
 * whose continuation then runs on that worker, which is the ownership rule {@code UiThread} exists to
 * assert. A worker hands its result to the scheduler; the scheduler settles the reply.</p>
 */
public final class PendingReply<T> implements Reply<T> {

    @Nullable
    private final Runnable onCancel;

    private final List<Consumer<T>> onResult = new ArrayList<>(2);
    private final List<Consumer<ReplyError>> onError = new ArrayList<>(2);
    private final List<Runnable> onSettled = new ArrayList<>(2);

    private boolean done;
    @Nullable
    private T value;
    @Nullable
    private ReplyError error;

    /**
     * @param onCancel what to tell the producer when the caller stops caring — the scheduler, the
     *                 router. Null when there is nothing to tell, as for an already-settled reply.
     */
    public PendingReply(@Nullable Runnable onCancel) {
        this.onCancel = onCancel;
    }

    // ── Settling ────────────────────────────────────────────────────────────────────────────────

    /** Answers with a value. The second settle of a reply is ignored, not an error — a cancel races. */
    public void resolve(@Nullable T result) {
        if (done) return;
        done = true;
        value = result;
        for (Consumer<T> listener : new ArrayList<>(onResult)) listener.accept(result);
        settled();
    }

    /** Answers with a failure. */
    public void fail(ReplyError reason) {
        if (done) return;
        done = true;
        error = reason == null ? new ReplyError(ReplyError.FAILED, "no reason given") : reason;
        for (Consumer<ReplyError> listener : new ArrayList<>(onError)) listener.accept(error);
        settled();
    }

    private void settled() {
        for (Runnable listener : new ArrayList<>(onSettled)) listener.run();
        onResult.clear();
        onError.clear();
        onSettled.clear();
    }

    // ── Reply ───────────────────────────────────────────────────────────────────────────────────

    @Override
    public Reply<T> then(Consumer<T> listener) {
        if (listener == null) return this;
        if (done) {
            if (error == null) listener.accept(value);
            return this;
        }
        onResult.add(listener);
        return this;
    }

    @Override
    public Reply<T> onError(Consumer<ReplyError> listener) {
        if (listener == null) return this;
        if (done) {
            if (error != null) listener.accept(error);
            return this;
        }
        onError.add(listener);
        return this;
    }

    @Override
    public Reply<T> always(Runnable listener) {
        if (listener == null) return this;
        if (done) {
            listener.run();
            return this;
        }
        onSettled.add(listener);
        return this;
    }

    @Override
    public <U> Reply<U> map(Function<T, U> mapper) {
        PendingReply<U> mapped = new PendingReply<>(this::cancel);
        then(result -> mapped.resolve(mapper.apply(result)));
        onError(mapped::fail);
        return mapped;
    }

    @Override
    public void cancel() {
        if (done) return;
        if (onCancel != null) onCancel.run();
        // AFTER telling the producer, and unconditionally: a producer that answers its own cancellation
        // synchronously has already settled this by the time we get here, and the guard in fail() makes
        // that the winner. One that does not, settles here -- so `always` runs either way and a batch
        // waiting on this reply cannot be left one short forever.
        fail(ReplyError.cancelled());
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    @Nullable
    public T result() {
        return error == null ? value : null;
    }

    @Override
    @Nullable
    public ReplyError error() {
        return error;
    }

    @Override
    public String toString() {
        if (!done) return "Reply(pending)";
        return error != null ? "Reply(" + error + ")" : "Reply(" + value + ")";
    }
}
