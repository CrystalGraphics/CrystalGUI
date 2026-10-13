package com.crystalgui.fs.protocol;

import com.crystalgraphics.job.reply.CgReplyError;
import com.crystalgui.fs.CgFileError;

import org.jetbrains.annotations.Nullable;

/**
 * Why a filesystem call failed: the codes a {@link CgReplyError} carries for one, and the etag a conflict lost to,
 * which rides in its {@code data}.
 *
 * <pre>{@code
 * reply.fail(FsError.of(FsError.NOT_FOUND, path.toString()));
 * reply.fail(FsError.conflict("the file changed since you last read it", currentEtag));
 *
 * save.onError(error -> {
 *     if (error.is(FsError.CONFLICT)) reload(FsError.actualEtag(error));
 * });
 * }</pre>
 */
public final class FsError {

    /** The file moved since the etag the write quoted. {@link #actualEtag} is what it holds now. */
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

    /** Anything else. */
    public static final String FAILED = CgReplyError.FAILED;

    private FsError() {
    }

    public static CgReplyError of(String code, String detail) {
        return new CgReplyError(code, detail);
    }

    /** The provider's own error, mapped. One place, so a new provider error is added once. */
    public static CgReplyError of(CgFileError error, String detail) {
        return new CgReplyError(codeFor(error), detail);
    }

    /** A {@link #CONFLICT}, carrying what the file's etag is now. */
    public static CgReplyError conflict(String detail, @Nullable String actualEtag) {
        return new CgReplyError(CONFLICT, detail, null, actualEtag);
    }

    /**
     * What the file's etag is now, on a {@link #CONFLICT}; null for anything else. The client re-reads with it,
     * which is what makes a conflict resolvable rather than an unconditional overwrite.
     */
    @Nullable
    public static String actualEtag(CgReplyError error) {
        return error.is(CONFLICT) && error.data() instanceof String etag ? etag : null;
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
