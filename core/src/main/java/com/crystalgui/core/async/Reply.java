package com.crystalgui.core.async;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * <b>One shape for every answer that is not ready yet</b> — a job on a worker, a call across a wire, a
 * provider that has to decompile something first.
 *
 * <p>One shape, so two answers compose: chain with {@link #then} and {@link #map}, wait on several
 * with {@link #all}, and handle failure once with {@link #onError}. Callback pairs cannot be composed
 * at all — a caller wanting the second answer after the first writes the nesting by hand, and a
 * completion forgotten in one branch is a step that never finishes with nothing to see.</p>
 *
 * <h3>Continuations run on the frame thread</h3>
 *
 * <p>Every callback here runs where the tree lives: for a job during {@code JobScheduler.drain()}, for a
 * wire call during the connection's tick. That is the whole reason a caller may touch a widget from
 * inside {@link #then}, and it is why this type is deliberately <b>not</b> thread-safe — see
 * {@link PendingReply}.</p>
 *
 * <h3>A settled reply still answers</h3>
 *
 * <p>Registering a callback on a reply that has already settled runs it immediately rather than dropping
 * it. Anything else makes the correctness of a caller depend on whether the work happened to finish
 * first, which for an in-memory transport in a test is always and for a socket is never.</p>
 */
public interface Reply<T> {

    /** The value arrived. Runs on the frame thread; runs immediately if the reply has already settled. */
    Reply<T> then(Consumer<T> onResult);

    /** It did not. Structured — {@link ReplyError#code()} is what a handler branches on. */
    Reply<T> onError(Consumer<ReplyError> onError);

    /** Settled, either way. What a batch counts, and where a spinner is taken down. */
    Reply<T> always(Runnable whenSettled);

    /** A view over this reply's value. Cancelling the view cancels the reply it came from. */
    <U> Reply<U> map(Function<T, U> mapper);

    /**
     * Stop caring about the answer, and tell whoever is producing it.
     *
     * <p>Reaches the scheduler for a job and {@code MessageRouter.cancel} for a wire call. A cancelled
     * reply settles with {@link ReplyError#CANCELLED} rather than never settling, so an {@link #always}
     * still runs and a batch waiting on it still completes.</p>
     */
    void cancel();

    /** Whether it has settled, either way. */
    boolean isDone();

    /**
     * The value, or null if it has not settled or settled with an error.
     *
     * <p>For a test over an in-memory transport, where the answer is already there by the time the call
     * returns. In application code this is the wrong question: use {@link #then}.</p>
     */
    @Nullable
    T result();

    /** The error, or null if it has not settled or settled with a value. */
    @Nullable
    ReplyError error();

    // ── Composition ─────────────────────────────────────────────────────────────────────────────

    /** Two of them, as one. Fails with the first error either produces; cancelling cancels both. */
    static <A, B> Reply<Both<A, B>> both(Reply<A> a, Reply<B> b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        PendingReply<Both<A, B>> joined = new PendingReply<>(() -> {
            a.cancel();
            b.cancel();
        });
        Runnable check = () -> {
            if (joined.isDone()) return;
            if (a.isDone() && b.isDone() && a.error() == null && b.error() == null) {
                joined.resolve(new Both<>(a.result(), b.result()));
            }
        };
        a.onError(joined::fail).then(ignored -> check.run());
        b.onError(joined::fail).then(ignored -> check.run());
        // Both may already have settled, in which case neither callback above will fire again.
        check.run();
        return joined;
    }

    /** All of them. Fails with the first error any produces; cancelling cancels every one. */
    static Reply<Void> all(Collection<? extends Reply<?>> replies) {
        List<Reply<?>> members = new ArrayList<>(replies);
        PendingReply<Void> joined = new PendingReply<>(() -> {
            for (Reply<?> member : members) member.cancel();
        });
        if (members.isEmpty()) {
            joined.resolve(null);
            return joined;
        }
        Runnable check = () -> {
            if (joined.isDone()) return;
            for (Reply<?> member : members) {
                if (!member.isDone()) return;
                if (member.error() != null) return;
            }
            joined.resolve(null);
        };
        for (Reply<?> member : members) {
            member.onError(joined::fail).then(ignored -> check.run());
        }
        check.run();
        return joined;
    }

    /** An answer that is already here. */
    static <T> Reply<T> of(@Nullable T value) {
        PendingReply<T> settled = new PendingReply<>(null);
        settled.resolve(value);
        return settled;
    }

    /** A failure that is already here. */
    static <T> Reply<T> failed(ReplyError error) {
        PendingReply<T> settled = new PendingReply<>(null);
        settled.fail(error);
        return settled;
    }

    /**
     * What {@link #both} answers.
     *
     * <p>Nested rather than a general {@code Pair} in {@code core.data}: a general pair invites being
     * used as a return type, and a method returning "two things with no names" is a method that wanted
     * a record.</p>
     */
    record Both<A, B>(@Nullable A first, @Nullable B second) {
    }
}
