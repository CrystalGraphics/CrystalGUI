package com.crystalgui.fs.provider;

/**
 * What a listing or a stat says about one entry.
 *
 * <p>Ported from VS Code's {@code IStat} / {@code IBaseFileStat}
 * ({@code src/vs/platform/files/common/files.ts}, MIT).</p>
 *
 * @param name  the entry's own name, with no path. Empty only for a project root.
 * @param type  see {@link CgFileType}
 * @param size  bytes, or {@code 0} for a directory
 * @param mtime last modification, in milliseconds since the epoch
 */
public record CgFileEntry(String name, CgFileType type, long size, long mtime) {

    public CgFileEntry {
        if (name == null) throw new IllegalArgumentException("name");
        if (type == null) throw new IllegalArgumentException("type");
    }

    public static CgFileEntry file(String name, long size, long mtime) {
        return new CgFileEntry(name, CgFileType.FILE, size, mtime);
    }

    public static CgFileEntry directory(String name, long mtime) {
        return new CgFileEntry(name, CgFileType.DIRECTORY, 0L, mtime);
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
        return Long.toString(mtime, 29) + Long.toString(size, 31);
    }

    /** The etag two raw numbers would produce, for a caller holding a stat rather than an entry. */
    public static String etag(long mtime, long size) {
        return Long.toString(mtime, 29) + Long.toString(size, 31);
    }
}
