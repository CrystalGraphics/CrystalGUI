package com.crystalgui.core.async;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A {@link Stream} whoever produces the pieces settles — {@link PendingReply}'s counterpart.
 *
 * <p>The producer calls {@link #emit} per piece and {@link #finish} once. The pieces are accumulated,
 * so the settled value is the whole sequence and a caller who only registered {@link Reply#then} gets
 * the same answer a non-streaming call would have given.</p>
 *
 * <p>Not thread-safe, for {@link PendingReply}'s reason: pieces are published on the frame thread.</p>
 */
public final class PendingStream<T> implements Stream<T> {

    private final PendingReply<List<T>> settlement;
    private final List<T> pieces = new ArrayList<>();
    private final List<Consumer<T>> onPartial = new ArrayList<>(2);

    public PendingStream(@Nullable Runnable onCancel) {
        this.settlement = new PendingReply<>(onCancel);
    }

    // ── Producing ───────────────────────────────────────────────────────────────────────────────

    /** One piece, in order. Ignored once the stream has settled. */
    public void emit(T piece) {
        if (settlement.isDone()) return;
        pieces.add(piece);
        for (Consumer<T> listener : new ArrayList<>(onPartial)) listener.accept(piece);
    }

    /** No more pieces. Settles with everything emitted. */
    public void finish() {
        settlement.resolve(Collections.unmodifiableList(new ArrayList<>(pieces)));
        onPartial.clear();
    }

    /** Settles with a failure. Pieces already delivered stay delivered — the caller kept them. */
    public void fail(ReplyError reason) {
        settlement.fail(reason);
        onPartial.clear();
    }

    /** What has arrived so far. For a producer deciding whether to ask for more. */
    public int emitted() {
        return pieces.size();
    }

    // ── Stream ──────────────────────────────────────────────────────────────────────────────────

    @Override
    public Stream<T> onPartial(Consumer<T> each) {
        if (each == null) return this;
        // REPLAYED, then subscribed. A caller registering after the first page must still see it, or
        // whether a tree shows its first rows depends on whether the transport was in memory.
        for (T already : new ArrayList<>(pieces)) each.accept(already);
        if (!settlement.isDone()) onPartial.add(each);
        return this;
    }

    @Override
    public Reply<List<T>> then(Consumer<List<T>> onResult) {
        settlement.then(onResult);
        return this;
    }

    @Override
    public Reply<List<T>> onError(Consumer<ReplyError> onError) {
        settlement.onError(onError);
        return this;
    }

    @Override
    public Reply<List<T>> always(Runnable whenSettled) {
        settlement.always(whenSettled);
        return this;
    }

    @Override
    public <U> Reply<U> map(Function<List<T>, U> mapper) {
        return settlement.map(mapper);
    }

    @Override
    public void cancel() {
        settlement.cancel();
        onPartial.clear();
    }

    @Override
    public boolean isDone() {
        return settlement.isDone();
    }

    @Override
    @Nullable
    public List<T> result() {
        return settlement.result();
    }

    @Override
    @Nullable
    public ReplyError error() {
        return settlement.error();
    }

    @Override
    public String toString() {
        return "Stream(" + pieces.size() + " pieces, " + (isDone() ? "settled" : "open") + ")";
    }
}
