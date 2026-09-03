package com.crystalgui.fs.client;

import com.crystalgui.core.async.PendingReply;
import com.crystalgui.core.async.Reply;
import com.crystalgui.fs.protocol.FsError;
import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <b>One typed call across the wire, as a {@link Reply}</b> — and the coalescing that goes with it.
 *
 * <h3>Three things the callback pairs could not do</h3>
 *
 * <p>{@code plan_fs_rewrite.md} N18, N17. {@code WorkspaceClient} took an
 * {@code (onResult, onError)} pair per method, so: two reads of one path in flight were two round
 * trips, because nothing knew they were the same question; a caller could not cancel, because
 * {@code MessageRouter.cancel} existed and nothing exposed it; and a failure arrived as a string that
 * had to be split on a space to recover the etag a conflict lost to.</p>
 *
 * <p>Every call goes through here now. A key coalesces — a second caller asking the same question while
 * the first is in flight gets the same reply — and a failure is parsed once, into a {@link FsError}
 * with its code and its etag as fields.</p>
 */
final class FsCall<T> {

    /** How a message reaches the far side. The connection's {@code call}, in production. */
    public interface Caller<T> {
        void call(String method, @Nullable StateMap<T> args,
                  Consumer<StateMap<T>> onResult, Consumer<String> onError);
    }

    private Caller<T> caller;
    private final DynamicOps<T> ops;
    private final Health health;

    /**
     * Calls in flight, by key.
     *
     * <p>The key is the question, not the method — {@code read:proj:a.txt}. Two tabs opening one file
     * on the same frame, the explorer and the index both listing one directory, a save and the reload
     * it triggers: all of them ask the same thing twice, and one of the two is waste.</p>
     */
    private final Map<String, PendingReply<?>> inFlight = new LinkedHashMap<>();

    FsCall(Caller<T> caller, DynamicOps<T> ops, Health health) {
        this.caller = caller;
        this.ops = ops;
        this.health = health;
    }

    /**
     * Points this at a new wire.
     *
     * <p>Anything in flight over the old one is failed rather than left pending: its answer is never
     * coming, and a reply that never settles is a batch that never completes and a spinner that never
     * comes down.</p>
     */
    void rebind(Caller<T> next) {
        this.caller = next;
        for (PendingReply<?> pending : new java.util.ArrayList<>(inFlight.values())) {
            pending.fail(new FsError(FsError.FAILED, "the connection was replaced"));
        }
        inFlight.clear();
    }

    /** A call nothing else could be asking. Mutations, which must never coalesce. */
    <A, R> Reply<R> send(String method, Codec<A> argsCodec, A args, Codec<R> resultCodec) {
        PendingReply<R> reply = new PendingReply<>(null);
        dispatch(method, argsCodec, args, resultCodec, reply);
        return reply;
    }

    /**
     * A call that is the same question as any other with this key.
     *
     * <p>Reads only. A mutation with the same shape as another is still a second mutation — coalescing
     * two writes would be dropping one.</p>
     */
    @SuppressWarnings("unchecked")
    <A, R> Reply<R> coalesced(String key, String method, Codec<A> argsCodec, A args,
                              Codec<R> resultCodec) {
        PendingReply<R> existing = (PendingReply<R>) inFlight.get(key);
        if (existing != null && !existing.isDone()) return existing;

        PendingReply<R> reply = new PendingReply<>(null);
        inFlight.put(key, reply);
        reply.always(() -> inFlight.remove(key, reply));
        dispatch(method, argsCodec, args, resultCodec, reply);
        return reply;
    }

    private <A, R> void dispatch(String method, Codec<A> argsCodec, A args, Codec<R> resultCodec,
                                 PendingReply<R> reply) {
        StateMap<T> encoded = args == null ? null
                : new StateMap<>(ops, argsCodec.encode(ops, args));
        // TIMED HERE, at the one door every call goes through -- which is the whole reason there is one.
        long stamp = health.asked();
        caller.call(method, encoded,
                result -> {
                    health.answered(stamp);
                    try {
                        reply.resolve(result == null ? null : resultCodec.decode(ops, result.encode()));
                    } catch (RuntimeException undecodable) {
                        // A payload this build cannot read is a protocol failure, not a file one --
                        // reported as itself rather than as a missing file, which is what a caller
                        // would otherwise show the person.
                        reply.fail(new FsError(FsError.FAILED,
                                "could not decode the answer to " + method + ": " + undecodable));
                    }
                },
                error -> {
                    FsError failure = parse(error);
                    health.failed(failure);
                    reply.fail(failure);
                });
    }

    /**
     * Turns the wire's {@code CODE detail} into a typed failure.
     *
     * <p>The one place a failure string is read. {@code WorkspaceClient} parsed
     * {@code "CONFLICT " + etag} at its call sites, so the etag — the only actionable thing in the only
     * failure that needs action — was recovered by splitting a sentence, in each of the places that
     * cared.</p>
     */
    static FsError parse(@Nullable String error) {
        if (error == null || error.isEmpty()) return new FsError(FsError.FAILED, "no reason given");
        int space = error.indexOf(' ');
        String code = space < 0 ? error : error.substring(0, space);
        String detail = space < 0 ? "" : error.substring(space + 1);
        if (FsError.CONFLICT.equals(code)) {
            // The detail IS the etag on a conflict -- which is exactly the coupling the structured
            // error removes, and which stays here for as long as the far side may be an older build.
            return FsError.conflict("the file changed since you last read it", detail);
        }
        return new FsError(code, detail);
    }
}
