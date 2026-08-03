package com.crystalgui.fs;

/**
 * Why a filesystem operation failed.
 *
 * <p>Ported from VS Code's {@code FileSystemProviderErrorCode}
 * ({@code src/vs/platform/files/common/files.ts}, MIT), with one addition of our own.</p>
 *
 * <h3>Why an enum rather than a message</h3>
 * <p>Every operation returns one of these, and a UI has to say <em>this file is read-only</em> differently
 * from <em>the disk is full</em>. A {@code String} is fine for a log and useless for that, and the moment
 * one caller matches on message text the messages are frozen.</p>
 *
 * <h3>There is deliberately no {@code CONFLICT} here</h3>
 * <p>A stale write is not a provider failure — the provider wrote, or refused, some bytes. Detecting that
 * the file moved underneath is the job of the layer that knows about {@linkplain CgFileEntry#etag() etags},
 * and VS Code draws the same line: its provider codes carry no conflict member, and
 * {@code FILE_MODIFIED_SINCE} lives one level up in {@code FileOperationResult}. Putting it here would push
 * etag awareness into every implementation, including the in-memory one used by tests.</p>
 */
public enum CgFileError {

    /** The target already exists and the operation required that it not. */
    FILE_EXISTS,

    FILE_NOT_FOUND,

    /** A path component that had to be a directory was not one. */
    FILE_NOT_A_DIRECTORY,

    /** The target is a directory and the operation only makes sense on a file. */
    FILE_IS_A_DIRECTORY,

    /** Rejected by a quota rather than by the disk being full. */
    FILE_EXCEEDS_STORAGE_QUOTA,

    /** Larger than the reader is willing to load — see the workspace size ceiling. */
    FILE_TOO_LARGE,

    /** Locked against writing, whether by the OS or by another writer. */
    FILE_WRITE_LOCKED,

    /**
     * Refused by permissions.
     *
     * <p>Covers both the operating system's answer and the host mod's authorisation callback, and that
     * is intentional: a client must not be able to tell "this exists but you may not read it" from "this
     * does not exist" by comparing error codes.</p>
     */
    NO_PERMISSIONS,

    /**
     * The path itself is malformed, or escapes its project.
     *
     * <p><b>Ours, not VS Code's.</b> Their provider enum has no such member because their {@code URI}
     * carries whatever text it is given and the provider validates later; {@code FILE_INVALID_PATH} then
     * lives above, in {@code FileOperationResult}. {@link CgPath} validates at <em>construction</em>
     * instead — see its note on why — so the failure surfaces here, and needs a code here to be
     * distinguishable from a genuine {@link #UNKNOWN}.</p>
     */
    INVALID_PATH,

    /**
     * The provider itself could not be reached.
     *
     * <p>The one code that matters more here than in VS Code: a local filesystem is never unavailable,
     * and a filesystem on the other end of a Minecraft connection routinely is.</p>
     */
    UNAVAILABLE,

    /** Everything else. Never thrown deliberately when a better code exists. */
    UNKNOWN
}
