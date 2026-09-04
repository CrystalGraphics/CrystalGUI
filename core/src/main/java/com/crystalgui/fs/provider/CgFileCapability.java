package com.crystalgui.fs.provider;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a given filesystem can actually do.
 *
 * <p>Ported from VS Code's {@code FileSystemProviderCapabilities}
 * ({@code src/vs/platform/files/common/files.ts}, MIT), as an {@code EnumSet} rather than a bitmask
 * because Java has one and the bit arithmetic bought nothing but terseness.</p>
 *
 * <h3>Why capabilities rather than one flat interface</h3>
 * <p>The same interface has to serve an in-memory map used by tests, a real directory on a server, a
 * remote filesystem at the end of a Minecraft connection and — later — a read-only resource pack. A flat
 * interface gives two bad options: every implementation throws {@code UnsupportedOperationException} for
 * the parts it lacks, or the interface shrinks to what the weakest one can do.</p>
 *
 * <p>Capabilities let a caller <em>ask</em>. It is also how "keep the shape, defer the machinery" is
 * achieved honestly: an implementation that can only read whole files advertises {@link #FILE_READ_WRITE}
 * and implements two methods, and adding {@link #FILE_OPEN_READ_WRITE_CLOSE} later is additive rather than
 * a signature change.</p>
 */
public enum CgFileCapability {

    /** Whole-file read and write. The minimum that is useful. */
    FILE_READ_WRITE,

    /**
     * Descriptor-based access — open, read at an offset, write at an offset, close.
     *
     * <p>What chunked transfer of a large file is built on. POSIX's shape, and VS Code's, rather than a
     * bespoke transfer id: each call carries its own position, so the protocol holds no per-transfer
     * state beyond the descriptor itself.</p>
     */
    FILE_OPEN_READ_WRITE_CLOSE,

    /** Reading as a stream, for a consumer that does not want the whole file in memory. */
    FILE_READ_STREAM,

    /** Copying a file or a folder in one operation, rather than read-then-write. */
    FILE_FOLDER_COPY,

    /**
     * Paths are case-SENSITIVE on this filesystem.
     *
     * <p>Absent means {@code Foo.java} and {@code foo.java} are one file — Windows and, by default,
     * macOS. <b>A per-filesystem answer, not a global rule</b>: a global choice is wrong on whichever
     * platform it was not made for, and it cannot be walked back once paths have been saved.</p>
     */
    PATH_CASE_SENSITIVE,

    /** Everything here is read-only, whatever the permissions say. A shipped resource pack, for example. */
    READONLY,

    /** Writes land atomically — the reader never sees a half-written file. */
    FILE_ATOMIC_WRITE;

    /** No capabilities at all. */
    public static final Set<CgFileCapability> NONE =
            Collections.unmodifiableSet(EnumSet.noneOf(CgFileCapability.class));

    /** Convenience for the common in-memory or simple-disk case. */
    public static Set<CgFileCapability> of(CgFileCapability... capabilities) {
        EnumSet<CgFileCapability> set = EnumSet.noneOf(CgFileCapability.class);
        Collections.addAll(set, capabilities);
        return Collections.unmodifiableSet(set);
    }
}
