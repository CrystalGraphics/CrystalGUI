package com.crystalgui.fs.server;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>What each mutation answered, so a retry is answered the same way</b> — D17's other half.
 *
 * <h3>The failure it prevents</h3>
 *
 * <p>A write crosses the wire, the server performs it and answers, and the answer is lost — a dropped
 * packet, a reconnect, a timeout that fired a moment early. The client retries with the same etag. The
 * file now holds the etag the client's own write produced, so the conditional write is refused as a
 * <b>conflict against itself</b>: the person is shown a merge dialog for a change nobody else made,
 * and the only two buttons are "keep mine" and "take theirs" over identical content.</p>
 *
 * <p>The fix is the one every idempotent API uses — Stripe's idempotency keys, HTTP's
 * {@code Idempotency-Key} draft, and every payment system that has ever existed. The client generates
 * an operation id per mutation and repeats it on a retry; the server answers a repeat from here rather
 * than performing it again.</p>
 *
 * <h3>Bounded, and bounded by count</h3>
 *
 * <p>An id is only useful for as long as a client might retry, which is the request timeout plus a
 * reconnect — seconds. Keeping the last few hundred covers that with room to spare, and a bound by
 * count rather than by age needs no clock and cannot be defeated by a client that stops talking.</p>
 */
public final class RecentOperations {

    /** How many answers are remembered. A client cannot usefully retry something older. */
    public static final int RETAINED = 256;

    private final int retained;

    /** Insertion-ordered, so the oldest entry is the first one out. */
    private final Map<String, String> answers = new LinkedHashMap<>();

    public RecentOperations() {
        this(RETAINED);
    }

    public RecentOperations(int retained) {
        this.retained = Math.max(1, retained);
    }

    /**
     * The answer this operation already produced, or null if it is new.
     *
     * <p>An empty or absent id is always null: an operation with no id is one the client is not
     * prepared to retry, which is every read.</p>
     */
    @Nullable
    public String answerFor(@Nullable String operationId) {
        if (operationId == null || operationId.isEmpty()) return null;
        return answers.get(operationId);
    }

    public boolean isRepeat(@Nullable String operationId) {
        return answerFor(operationId) != null;
    }

    /** Remembers what this operation answered — the etag it produced. */
    public void record(@Nullable String operationId, String etag) {
        if (operationId == null || operationId.isEmpty()) return;
        answers.remove(operationId);
        answers.put(operationId, etag == null ? "" : etag);
        while (answers.size() > retained) {
            answers.remove(answers.keySet().iterator().next());
        }
    }

    public int size() {
        return answers.size();
    }
}
