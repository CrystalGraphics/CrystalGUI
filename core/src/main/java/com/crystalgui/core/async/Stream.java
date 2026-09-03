package com.crystalgui.core.async;

import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link Reply} that arrives in pieces — <b>a partial answer is a shape, not a callback convention</b>.
 *
 * <p>A chunked read reports each chunk, a large directory listing arrives in pages, a search reports
 * matches as it finds them. Every one of those is still one question with one settlement, so a stream is
 * a reply that also announces its parts: {@link #onPartial} for each piece, {@link Reply#then} for the
 * whole, once.</p>
 *
 * <p>Pieces arrive <b>in order</b>, on the frame thread, and a piece delivered is a piece the caller may
 * keep: a listing page is appended to a tree, a read chunk is written into a buffer. The final value is
 * the whole sequence, so a caller that does not care about progressive delivery ignores
 * {@code onPartial} and reads {@link Reply#then} exactly as it would any other reply.</p>
 */
public interface Stream<T> extends Reply<List<T>> {

    /**
     * Each piece, in order, as it arrives.
     *
     * <p>Registered late, this replays the pieces already delivered before continuing — for the same
     * reason a settled reply still answers: whether a caller sees the first page must not depend on
     * whether the transport happened to be in memory.</p>
     */
    Stream<T> onPartial(Consumer<T> each);
}
