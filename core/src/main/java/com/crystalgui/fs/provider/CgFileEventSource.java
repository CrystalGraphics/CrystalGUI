package com.crystalgui.fs.provider;

import java.util.List;

/**
 * A source of filesystem events for one project — Phase 6.2.
 *
 * <p>Not every filesystem has one. {@code InMemoryFileSystem} has no operating system underneath, a
 * read-only resource pack cannot change, and a future remote-backed filesystem would have its own
 * mechanism entirely. So this is a capability a filesystem may offer rather than a method on
 * {@link CgFileSystem}, and a caller that has none falls back to the etag poll — which is not a
 * degraded mode, because <b>the poll is required even when a source exists</b>. @see CgFileEvent.Kind#OVERFLOW</p>
 *
 * <h3>Drained, never pushed</h3>
 *
 * <p>{@link #drain()} is non-blocking and is called from whatever thread already owns the tick. It is
 * emphatically <b>not</b> a listener: a signal emitted by a watcher thread carries that thread into every
 * consumer, and this codebase has already paid for exactly that once — a status update pushed from a
 * script thread reached {@code StyleEngine}'s dirty-match set while the UI thread was copying it, and
 * threw from inside {@code advanceFrame} with nothing about the culprit anywhere in the trace.</p>
 *
 * <p>The implementation may use a thread internally — {@code WatchService} is happiest with one — but
 * nothing it owns may escape across {@link #drain()}, which is a plain hand-off of a snapshot.</p>
 */
public interface CgFileEventSource extends AutoCloseable {

    /**
     * Everything seen since the last call, oldest first. Empty when nothing happened.
     *
     * <p>Non-blocking by contract. A caller ticking at 20 Hz must never be the thing that waits.</p>
     */
    List<CgFileEvent> drain();

    /** Stops watching and releases whatever the OS handed out. Idempotent. */
    @Override
    void close();

    /** A source for a filesystem that cannot produce events. Always empty, always safe to close. */
    CgFileEventSource NONE = new CgFileEventSource() {
        @Override
        public List<CgFileEvent> drain() {
            return java.util.Collections.emptyList();
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return "CgFileEventSource.NONE";
        }
    };
}
