package com.crystalgui.fs;

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
