package com.crystalgui.fs.provider;

import com.crystalgui.fs.CgPath;
import java.util.List;
import javax.annotation.Nullable;

/**
 * One thing the filesystem did, as reported by the operating system rather than inferred from a stat.
 *
 * <p>Deliberately not a wire-level change: that carries an etag and is the answer
 * to <i>"what should this client be told"</i>, which is a question with authorisation and per-peer state
 * behind it. This is the raw notification underneath, before anyone has decided whether it matters or who
 * may hear about it.</p>
 */
public final class CgFileEvent {

    public enum Kind {
        CREATED,
        MODIFIED,
        DELETED,

        /**
         * <b>Events were lost.</b> Not a thing that happened to a file — a thing that happened to the
         * watcher.
         *
         * <p>Every OS primitive underneath drops events under load: a {@code WatchKey} raises this once
         * its queue exceeds 512 on default Linux settings, and Windows' {@code ReadDirectoryChangesW} has
         * the same shape with its own buffer. The documented recovery is to re-scan, which is why
         * the etag poll survives a real watcher rather than being replaced by
         * one.</p>
         *
         * <p><b>A consumer that ignores this reports most changes</b>, which is worse than reporting none
         * because it looks like it works.</p>
         */
        OVERFLOW
    }

    /**
     * A source of these, for one project.
     *
     * <p>Nested, because a source of events has no meaning apart from the events: it was its own
     * file and every consumer of it already named this one.</p>
     *
     * <p>Not every filesystem has one. {@code InMemoryFileSystem} has no operating system underneath, a
     * read-only resource pack cannot change, and a future remote-backed filesystem would have its own
     * mechanism entirely. So this is a capability a filesystem may offer rather than a method on
     * {@link CgFileSystem}, and a caller that has none falls back to the etag poll — which is not a
     * degraded mode, because <b>the poll is required even when a source exists</b>. @see Kind#OVERFLOW</p>
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
    public interface Source extends AutoCloseable {

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
        Source NONE = new Source() {
            @Override
            public List<CgFileEvent> drain() {
                return java.util.Collections.emptyList();
            }

            @Override
            public void close() {
            }

            @Override
            public String toString() {
                return "CgFileEvent.Source.NONE";
            }
        };
    }

    private final Kind kind;

    @Nullable
    private final CgPath path;

    private CgFileEvent(Kind kind, @Nullable CgPath path) {
        this.kind = kind;
        this.path = path;
    }

    public static CgFileEvent of(Kind kind, CgPath path) {
        return new CgFileEvent(kind, path);
    }

    /** Events were lost and whatever is watching must reconcile. @see Kind#OVERFLOW */
    public static CgFileEvent overflow() {
        return new CgFileEvent(Kind.OVERFLOW, null);
    }

    public Kind kind() {
        return kind;
    }

    /** The file, or {@code null} for an {@link Kind#OVERFLOW}, which is about no particular file. */
    @Nullable
    public CgPath path() {
        return path;
    }

    public boolean isOverflow() {
        return kind == Kind.OVERFLOW;
    }

    @Override
    public String toString() {
        return kind + (path == null ? "" : " " + path);
    }
}
