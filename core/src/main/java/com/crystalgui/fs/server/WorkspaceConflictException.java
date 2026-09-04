package com.crystalgui.fs.server;

import com.crystalgui.fs.provider.CgFileEntry;
import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgPath;
import lombok.Getter;

/**
 * A write whose base has moved — the file changed since the client last read it.
 *
 * <p><b>Deliberately not a {@link CgFileError}.</b> Nothing failed at the filesystem: the provider could
 * have written the bytes perfectly well. What refused is the layer that knows about
 * {@linkplain CgFileEntry#etag() etags}, and VS Code draws the line in the same place — its provider error
 * codes carry no conflict member, and {@code FILE_MODIFIED_SINCE} lives one level up in
 * {@code FileOperationResult}.</p>
 *
 * <p>Carries the etag the file actually has now, so a client can decide without a second round trip —
 * and so the reload it offers is a reload of something it already knows the identity of.</p>
 */
@Getter
public class WorkspaceConflictException extends RuntimeException {

    private final CgPath path;
    private final String expectedEtag;
    private final String actualEtag;

    public WorkspaceConflictException(CgPath path, String expectedEtag, String actualEtag) {
        super("file changed since it was read: " + path
                + " (expected " + expectedEtag + ", found " + actualEtag + ")");
        this.path = path;
        this.expectedEtag = expectedEtag;
        this.actualEtag = actualEtag;
    }
}
