package com.crystalgui.fs.provider;

/**
 * What a directory entry is.
 *
 * <p>Ported from VS Code's {@code FileType} ({@code src/vs/platform/files/common/files.ts}, MIT).</p>
 *
 * <p><b>{@link #SYMLINK} is a flag, not an alternative.</b> VS Code's comment on it is the part worth
 * keeping: even when an entry is a symbolic link you still ask whether it is a file or a directory, and
 * the answer describes the <em>target</em>. So a link to a directory reports both, and a caller that only
 * cares about "can I list this" never has to know links exist.</p>
 */
public enum CgFileType {

    /** Neither a file, a directory, nor a link — a socket, a device, or something unreadable. */
    UNKNOWN,

    FILE,

    DIRECTORY;

    /** True when this entry can be listed. */
    public boolean isDirectory() {
        return this == DIRECTORY;
    }

    /** True when this entry has contents to read. */
    public boolean isFile() {
        return this == FILE;
    }
}
