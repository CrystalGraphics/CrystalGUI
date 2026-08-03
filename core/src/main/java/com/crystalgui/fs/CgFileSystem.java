package com.crystalgui.fs;

import java.util.List;
import java.util.Set;

/**
 * A filesystem, as the server sees one.
 *
 * <p>Ported from VS Code's {@code IFileSystemProvider}
 * ({@code src/vs/platform/files/common/files.ts}, MIT), reduced to the operations this engine needs and
 * made synchronous.</p>
 *
 * <h3>Synchronous, unlike theirs — and the reason is not laziness</h3>
 * <p>Every method there returns a {@code Promise}, because JavaScript has no other option and because
 * their remote provider implements the <em>same</em> interface as their disk one. Here the two are
 * genuinely different things: an implementation of this touches a map or a directory and returns, while a
 * client talking to a server across a Minecraft connection is not a filesystem at all — it is a session,
 * with latency, failure and cancellation that a filesystem interface would have to pretend away.</p>
 *
 * <p>So this stays synchronous and server-side, and the client gets an API shaped like what it actually
 * is. The asynchrony settled in D4 lives at that boundary, not in here.</p>
 *
 * <h3>Paths are already safe</h3>
 * <p>Every {@link CgPath} handed to an implementation has been through construction, which means it
 * cannot lexically escape its project. What an implementation over a real disk still owes is the check
 * lexical analysis cannot do: that a <b>symlink</b> does not lead outside the root.</p>
 */
public interface CgFileSystem {

    /**
     * What this implementation can do. Never null; may be empty.
     *
     * <p>Callers ask rather than discovering by exception — see {@link CgFileCapability}.</p>
     */
    Set<CgFileCapability> capabilities();

    default boolean has(CgFileCapability capability) {
        return capabilities().contains(capability);
    }

    /**
     * One entry's metadata.
     *
     * @throws CgFileSystemException {@link CgFileError#FILE_NOT_FOUND}
     */
    CgFileEntry stat(CgPath path);

    /**
     * The entries directly inside a directory, in no guaranteed order.
     *
     * @throws CgFileSystemException {@link CgFileError#FILE_NOT_FOUND} or
     *                               {@link CgFileError#FILE_NOT_A_DIRECTORY}
     */
    List<CgFileEntry> list(CgPath directory);

    /**
     * A whole file's bytes.
     *
     * @throws CgFileSystemException {@link CgFileError#FILE_NOT_FOUND},
     *                               {@link CgFileError#FILE_IS_A_DIRECTORY} or
     *                               {@link CgFileError#FILE_TOO_LARGE}
     */
    byte[] read(CgPath path);

    /**
     * Replaces or creates a file.
     *
     * <p>The two flags are VS Code's, and both are needed to express what an editor actually does:
     * <em>save</em> is {@code create=false, overwrite=true}; <em>new file</em> is
     * {@code create=true, overwrite=false}, which must fail rather than silently clobber something that
     * appeared since the user typed the name.</p>
     *
     * @throws CgFileSystemException {@link CgFileError#FILE_NOT_FOUND} when creating is not allowed and
     *                               the file is absent, {@link CgFileError#FILE_EXISTS} when overwriting
     *                               is not allowed and it is present, or
     *                               {@link CgFileError#NO_PERMISSIONS} on a {@linkplain
     *                               CgFileCapability#READONLY read-only} filesystem
     */
    void write(CgPath path, byte[] content, boolean create, boolean overwrite);

    /**
     * Creates a directory, and only the last component of it.
     *
     * @throws CgFileSystemException {@link CgFileError#FILE_EXISTS} or
     *                               {@link CgFileError#FILE_NOT_FOUND} when the parent is absent
     */
    void mkdir(CgPath path);

    /**
     * Removes a file, or a directory when {@code recursive}.
     *
     * @throws CgFileSystemException {@link CgFileError#FILE_NOT_FOUND}
     */
    void delete(CgPath path, boolean recursive);

    /**
     * Moves an entry.
     *
     * @throws CgFileSystemException {@link CgFileError#FILE_NOT_FOUND} or
     *                               {@link CgFileError#FILE_EXISTS}
     */
    void rename(CgPath from, CgPath to, boolean overwrite);

    /** Whether an entry exists, without the exception. */
    default boolean exists(CgPath path) {
        try {
            stat(path);
            return true;
        } catch (CgFileSystemException e) {
            return false;
        }
    }
}
