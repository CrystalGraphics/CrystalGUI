package com.crystalgui.fs;

import lombok.Getter;

/**
 * A filesystem operation that failed, carrying the {@link CgFileError} that says why.
 *
 * <p>Ported in shape from VS Code's {@code FileSystemProviderError} ({@code common/files.ts}, MIT).</p>
 *
 * <p>Unchecked on purpose. Every method on a provider can fail for every reason in the enum, so a checked
 * exception would be declared on all of them and caught meaningfully at almost none — the handling that
 * matters happens at the operation boundary (an RPC handler, a UI action), not at each call.</p>
 */
@Getter
public class CgFileSystemException extends RuntimeException {

    private final CgFileError error;

    public CgFileSystemException(CgFileError error, String message) {
        super(message);
        this.error = error;
    }

    public CgFileSystemException(CgFileError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public static CgFileSystemException notFound(CgPath path) {
        return new CgFileSystemException(CgFileError.FILE_NOT_FOUND, "no such file: " + path);
    }

    public static CgFileSystemException exists(CgPath path) {
        return new CgFileSystemException(CgFileError.FILE_EXISTS, "already exists: " + path);
    }

    public static CgFileSystemException notADirectory(CgPath path) {
        return new CgFileSystemException(CgFileError.FILE_NOT_A_DIRECTORY, "not a directory: " + path);
    }

    public static CgFileSystemException isADirectory(CgPath path) {
        return new CgFileSystemException(CgFileError.FILE_IS_A_DIRECTORY, "is a directory: " + path);
    }

    /**
     * Refused.
     *
     * <p>The message deliberately does not say whether the path exists — see {@link CgFileError#NO_PERMISSIONS}.
     * Anything more specific here is an oracle a client can probe.</p>
     */
    public static CgFileSystemException denied(CgPath path) {
        return new CgFileSystemException(CgFileError.NO_PERMISSIONS, "not permitted: " + path);
    }
}
