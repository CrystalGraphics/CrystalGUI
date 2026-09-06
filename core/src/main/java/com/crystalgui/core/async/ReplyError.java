package com.crystalgui.core.async;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Why a {@link Reply} did not produce a value — <b>a code and a detail, never a string to parse</b>.
 *
 * <h3>The failure this replaces</h3>
 *
 * <p>The workspace client shipped a conflict as {@code "CONFLICT " + etag} and re-parsed it at the other
 * end, so the one piece of machine-readable information in a failure — which etag the server actually
 * holds — arrived inside a sentence. A handler that wanted it split a string; a handler that wanted to
 * branch on the kind compared prose. Anything a caller must act on is a field.</p>
 *
 * <h3>Why this is here and not in the filesystem's protocol</h3>
 *
 * <p>{@code Reply} is {@code core.async}'s and a reply is answered by a job as often as by a wire, so its
 * error type cannot name a layer above it. Subclasses add what their own domain needs — a filesystem
 * conflict carries the etag it lost to — and every one of them still answers {@link #code()} and
 * {@link #detail()}, which is what a generic handler reads.</p>
 */
public class ReplyError {

    /** The reply was cancelled by its caller, or by the thing that owned it going away. */
    public static final String CANCELLED = "CANCELLED";

    /** The work threw. {@link #cause()} is the exception. */
    public static final String FAILED = "FAILED";

    /** No answer arrived inside the deadline. */
    public static final String TIMEOUT = "TIMEOUT";

    private final String code;
    private final String detail;
    @Nullable
    private final Throwable cause;

    public ReplyError(String code, String detail) {
        this(code, detail, null);
    }

    public ReplyError(String code, String detail, @Nullable Throwable cause) {
        this.code = Objects.requireNonNull(code, "code");
        this.detail = detail == null ? "" : detail;
        this.cause = cause;
    }

    /** A stable token a handler may branch on. Never shown to a person. */
    public String code() {
        return code;
    }

    /** What went wrong, in words. Shown to a person; never branched on. */
    public String detail() {
        return detail;
    }

    /** The exception behind a {@link #FAILED}, when there was one. */
    @Nullable
    public Throwable cause() {
        return cause;
    }

    public boolean is(String code) {
        return this.code.equals(code);
    }

    public static ReplyError cancelled() {
        return new ReplyError(CANCELLED, "cancelled");
    }

    public static ReplyError failed(Throwable thrown) {
        return new ReplyError(FAILED, String.valueOf(thrown), thrown);
    }

    @Override
    public String toString() {
        return detail.isEmpty() ? code : code + ": " + detail;
    }
}
