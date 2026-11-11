package com.crystalgui.viewport;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A typed key for something the host reads about a document's frame without touching its tree: filled on the
 * document as each frame is recorded, read on the host's thread from the frame on screen.
 *
 * <pre>{@code
 * static final Readout<Boolean> PINNED = Readout.named("pinned");
 *
 * // the Content, on the document, after its paint
 * public void readouts(Readouts out) { out.put(PINNED, desktop.hasPinnedWindows()); }
 *
 * // the host, any frame
 * boolean pinned = Boolean.TRUE.equals(viewport.readout(PINNED));
 * }</pre>
 *
 * <ul>
 *   <li>Declare one as a constant: each {@code named} call is a new key, equal only to itself.</li>
 *   <li>A value is a snapshot: put an immutable one, or a copy. The host reads it on another thread.</li>
 * </ul>
 */
public final class Readout<T> {

    private static final AtomicInteger COUNT = new AtomicInteger();

    final int index;
    private final String name;

    private Readout(String name) {
        this.name = name;
        this.index = COUNT.getAndIncrement();
    }

    /** A new key; {@code name} is for messages. */
    public static <T> Readout<T> named(String name) {
        return new Readout<>(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
