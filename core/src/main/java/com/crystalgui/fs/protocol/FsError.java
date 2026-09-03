package com.crystalgui.fs.protocol;

import com.crystalgui.core.async.ReplyError;
import com.crystalgui.fs.CgFileError;

import org.jetbrains.annotations.Nullable;

/**
 * Why a filesystem call failed — <b>a code, a detail, and the etag a conflict lost to</b>.
 *
 * <h3>It was a sentence</h3>
 *
 * <p>{@code plan_fs_rewrite.md} N17. A conflict travelled as {@code "CONFLICT " + etag} and was
 * re-parsed at the other end by splitting on a space, so the one piece of machine-readable information
 * in the most important failure the protocol has arrived inside prose. A handler that wanted to branch
 * on the kind compared strings, and {@code Failure.code()} might or might not name a
 * {@link CgFileError} depending on which layer had raised it.</p>
 *
 * <p>A {@link ReplyError}, so a filesystem failure and a cancelled job reach a caller through the same
 * {@code onError}. What this adds is the field a conflict is useless without.</p>
 */
public final class FsError extends ReplyError {

    /** The file moved since the etag the write quoted. {@link #actualEtag()} is what it holds now. */
    public static final String CONFLICT = "CONFLICT";

    /** The actor may not do that to that path. Deliberately the same answer as a missing file. */
    public static final String NOT_PERMITTED = "NOT_PERMITTED";

    /** No such file, no such directory, no such project — one code, on purpose. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** The path is not one this workspace can represent, or escapes its project. */
    public static final String INVALID_PATH = "INVALID_PATH";

    /** Above {@code WorkspaceService.MAX_FILE_BYTES}. Not "serve it slowly" — a refusal. */
    public static final String TOO_LARGE = "TOO_LARGE";

    /** The file exists and the caller said not to overwrite. */
    public static final String ALREADY_EXISTS = "ALREADY_EXISTS";

    /** This actor is sending more than the server will take. Its own code, so a client can back off. */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    /** A transfer id that has expired or never existed. */
    public static final String NO_SUCH_TRANSFER = "NO_SUCH_TRANSFER";

    @Nullable
    private final String actualEtag;

    public FsError(String code, String detail) {
        this(code, detail, null);
    }

    public FsError(String code, String detail, @Nullable String actualEtag) {
        super(code, detail);
        this.actualEtag = actualEtag;
    }

    /**
     * What the file's etag is <b>now</b>, on a {@link #CONFLICT}. Null for every other code.
     *
     * <p>The client re-reads with it, and it is what makes a conflict resolvable rather than merely
     * reported: without it the only recovery is an unconditional overwrite, which is the thing a
     * conflict exists to prevent.</p>
     */
    @Nullable
    public String actualEtag() {
        return actualEtag;
    }

    /** The provider's own error, mapped. One place, so a new provider error is added once. */
    public static FsError of(CgFileError error, String detail) {
        return new FsError(codeFor(error), detail);
    }

    public static FsError conflict(String detail, @Nullable String actualEtag) {
        return new FsError(CONFLICT, detail, actualEtag);
    }

    private static String codeFor(CgFileError error) {
        if (error == null) return FAILED;
        switch (error) {
            case FILE_NOT_FOUND:
                return NOT_FOUND;
            case FILE_EXISTS:
                return ALREADY_EXISTS;
            case NO_PERMISSIONS:
                return NOT_PERMITTED;
            case FILE_TOO_LARGE:
                return TOO_LARGE;
            case INVALID_PATH:
            case FILE_IS_A_DIRECTORY:
            case FILE_NOT_A_DIRECTORY:
                return INVALID_PATH;
            default:
                return FAILED;
        }
    }
}
