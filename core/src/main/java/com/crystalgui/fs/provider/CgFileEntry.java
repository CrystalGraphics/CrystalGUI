package com.crystalgui.fs.provider;

/**
 * What a listing or a stat says about one entry.
 *
 * <p>Ported from VS Code's {@code IStat} / {@code IBaseFileStat}
 * ({@code src/vs/platform/files/common/files.ts}, MIT).</p>
 *
 * @param name  the entry's own name, with no path. Empty only for a project root.
 * @param type  {@link Type#FILE} or {@link Type#DIRECTORY}
 * @param size  bytes, or {@code 0} for a directory
 * @param mtime last modification, in milliseconds since the epoch
 */
public record CgFileEntry(String name, Type type, long size, long mtime) {

    /**
     * What a directory entry is. Ported from VS Code's {@code FileType}
     * ({@code src/vs/platform/files/common/files.ts}, MIT).
     *
     * <p>Nested, because an entry is the only thing that has one: it was its own file and its only
     * consumers were this record and this record's test.</p>
     *
     * <p><b>Two constants, not VS Code's four.</b> Its {@code Unknown} is a socket or a device, which
     * neither factory below can produce and which both predicates answer false to — an entry no code
     * could make and every consumer would mishandle. Its {@code SymbolicLink} is a FLAG rather than an
     * alternative, and that distinction is worth keeping for the day links are supported: even for a
     * link you still ask whether it is a file or a directory, and the answer describes the TARGET, so a
     * link to a directory reports both. This enum documented that member for a release without ever
     * declaring it, which is how a javadoc ends up describing a design nobody built.</p>
     */
    public enum Type {
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

    public CgFileEntry {
        if (name == null) throw new IllegalArgumentException("name");
        if (type == null) throw new IllegalArgumentException("type");
    }

    public static CgFileEntry file(String name, long size, long mtime) {
        return new CgFileEntry(name, Type.FILE, size, mtime);
    }

    public static CgFileEntry directory(String name, long mtime) {
        return new CgFileEntry(name, Type.DIRECTORY, 0L, mtime);
    }

    public boolean isDirectory() {
        return type.isDirectory();
    }

    public boolean isFile() {
        return type.isFile();
    }

    /**
     * A cheap identity for these bytes — {@code mtime} and {@code size}, and nothing else.
     *
     * <p>Ported verbatim in spirit from VS Code's {@code etag()}:</p>
     * <pre>{@code return stat.mtime.toString(29) + stat.size.toString(31); }</pre>
     *
     * <h3>Why not a content hash</h3>
     * <p><b>Because listing a directory would then have to read every byte in it.</b> These two numbers
     * come back from the directory scan itself, so an etag costs nothing; hashing would turn "show me this
     * folder" into "read the whole project", and it would only be noticeably wrong on somebody's real
     * workspace rather than in a test.</p>
     *
     * <p>One value serves both jobs it is asked to do — telling a cache that its copy is still good, and
     * refusing a write whose base has moved. The plan for this feature originally specified a content hash
     * for the first and a revision counter for the second; VS Code has shipped one number for both for a
     * decade.</p>
     *
     * <h3>What it costs</h3>
     * <p>A write that leaves <em>both</em> mtime and size unchanged is invisible to it. That needs a
     * same-length edit inside one filesystem timestamp tick, and it is the trade every HTTP ETag and every
     * {@code make} has always made. Callers who cannot accept it hash the content themselves.</p>
     *
     * <p>The odd bases are VS Code's, kept deliberately: 29 and 31 make the two fields' digit ranges
     * unlikely to line up, so a pair that differs is very unlikely to concatenate into the same string.</p>
     */
    public String etag() {
        return etag(mtime, size);
    }

    /**
     * The etag two raw numbers would produce, for a caller holding a stat rather than an entry.
     *
     * <p>The one definition of the format — {@link #etag()} delegates here. It was a second copy of the
     * same two calls, which is one edit away from two filesystems disagreeing about what an etag IS,
     * and the disagreement would present as every conditional read missing.</p>
     */
    public static String etag(long mtime, long size) {
        return Long.toString(mtime, 29) + Long.toString(size, 31);
    }
}
