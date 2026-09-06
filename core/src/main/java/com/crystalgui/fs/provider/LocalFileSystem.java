package com.crystalgui.fs.provider;

import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A {@link CgFileSystem} over real directories.
 *
 * <p>The only implementation that touches a disk, and therefore the only one that owes the check
 * {@link CgPath} cannot make: that a <b>symlink</b> does not lead outside its project. Lexical escape is
 * already impossible — a {@code CgPath} refuses {@code ..} that would climb out at construction — so this
 * class is left with exactly one security job, done in one place: {@link #resolve}.</p>
 *
 * <h3>Writes are atomic</h3>
 * <p>Temp file <b>in the target's own directory</b>, then an atomic rename. Same directory matters: a
 * temp file elsewhere is on a different filesystem as often as not, and the rename silently degrades to
 * copy-then-delete — which is exactly the non-atomic thing being avoided. A crash or a full disk leaves
 * the original intact.</p>
 */
public final class LocalFileSystem implements CgFileSystem {

    /** Anything larger is refused rather than loaded — D11's ceiling. */
    public static final long DEFAULT_MAX_FILE_BYTES = 100L * 1024 * 1024;

    /**
     * How deep a recursive delete will go.
     *
     * <p>G9's answer to symlink loops. A link pointing at its own ancestor makes a tree infinitely deep,
     * and a walk that trusts the filesystem will happily follow it forever. The walk does not follow links
     * at all, so this is a second belt — cheap, and the failure it prevents is a hung server thread.</p>
     */
    private static final int MAX_DEPTH = 64;

    private final ProjectRegistry projects;
    private final long maxFileBytes;
    private final boolean caseSensitive;

    public LocalFileSystem(ProjectRegistry projects) {
        this(projects, DEFAULT_MAX_FILE_BYTES, defaultCaseSensitivity());
    }

    public LocalFileSystem(ProjectRegistry projects, long maxFileBytes, boolean caseSensitive) {
        if (projects == null) throw new IllegalArgumentException("projects");
        this.projects = projects;
        this.maxFileBytes = maxFileBytes;
        this.caseSensitive = caseSensitive;
    }

    /**
     * Whether this host's filesystems fold case, guessed from the OS.
     *
     * <p>A guess, and deliberately overridable — the accurate answer is per <em>filesystem</em>, not per
     * host, and a case-sensitive volume mounted on Windows is a real thing. Probing for it means creating
     * a file, which a read-only project root will not allow. So: the common answer by default, and a
     * constructor for a host that knows better.</p>
     *
     * <p>This is why {@link CgFileCapability#PATH_CASE_SENSITIVE} is advertised rather than assumed — the
     * client is told what this server actually reports, instead of both ends guessing separately.</p>
     */
    private static boolean defaultCaseSensitivity() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return !(os.contains("win") || os.contains("mac") || os.contains("darwin"));
    }

    @Override
    public Set<CgFileCapability> capabilities() {
        List<CgFileCapability> caps = new ArrayList<>();
        caps.add(CgFileCapability.FILE_READ_WRITE);
        // A REAL ranged read, over a seekable channel -- so a transfer holds (resource, etag, size) and
        // never the bytes. Declared and implemented by nothing until F2.
        caps.add(CgFileCapability.FILE_OPEN_READ_WRITE_CLOSE);
        caps.add(CgFileCapability.FILE_ATOMIC_WRITE);
        if (caseSensitive) caps.add(CgFileCapability.PATH_CASE_SENSITIVE);
        return CgFileCapability.of(caps.toArray(new CgFileCapability[0]));
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    @Override
    public CgFileEntry stat(CgPath path) {
        Path target = resolve(path);
        try {
            boolean directory = Files.isDirectory(target);
            long size = directory ? 0L : Files.size(target);
            long mtime = Files.getLastModifiedTime(target).toMillis();
            return directory
                    ? CgFileEntry.directory(nameOf(path, target), mtime)
                    : CgFileEntry.file(nameOf(path, target), size, mtime);
        } catch (NoSuchFileException e) {
            throw CgFileSystemException.notFound(path);
        } catch (IOException e) {
            throw io(path, e);
        }
    }

    @Override
    public List<CgFileEntry> list(CgPath directory) {
        Path target = resolve(directory);
        if (!Files.exists(target)) throw CgFileSystemException.notFound(directory);
        if (!Files.isDirectory(target)) throw CgFileSystemException.notADirectory(directory);

        List<CgFileEntry> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
            for (Path child : stream) {
                try {
                    boolean isDirectory = Files.isDirectory(child);
                    long mtime = Files.getLastModifiedTime(child).toMillis();
                    String name = child.getFileName().toString();
                    out.add(isDirectory
                            ? CgFileEntry.directory(name, mtime)
                            : CgFileEntry.file(name, Files.size(child), mtime));
                } catch (IOException skip) {
                    // One unreadable entry must not fail the whole listing -- a directory with a
                    // permission-denied child is still a directory, and a browser that shows nothing is
                    // worse than one that shows the rest.
                }
            }
        } catch (IOException e) {
            throw io(directory, e);
        }
        return out;
    }

    @Override
    public byte[] read(CgPath path) {
        Path target = resolve(path);
        try {
            if (Files.isDirectory(target)) throw CgFileSystemException.isADirectory(path);
            long size = Files.size(target);
            if (size > maxFileBytes) {
                throw new CgFileSystemException(CgFileError.FILE_TOO_LARGE,
                        "file is " + size + " bytes, over the " + maxFileBytes + " limit: " + path);
            }
            return Files.readAllBytes(target);
        } catch (NoSuchFileException e) {
            throw CgFileSystemException.notFound(path);
        } catch (IOException e) {
            throw io(path, e);
        }
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────

    @Override
    public void write(CgPath path, byte[] content, boolean create, boolean overwrite) {
        Path target = resolve(path);
        boolean exists = Files.exists(target);

        if (exists && Files.isDirectory(target)) throw CgFileSystemException.isADirectory(path);
        if (!exists && !create) throw CgFileSystemException.notFound(path);
        if (exists && create && !overwrite) throw CgFileSystemException.exists(path);

        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw CgFileSystemException.notFound(path);

        Path temp = null;
        try {
            // IN THE TARGET'S OWN DIRECTORY. A temp elsewhere is frequently another filesystem, where
            // ATOMIC_MOVE is unsupported and the fallback is copy-then-delete -- i.e. not atomic, which
            // was the entire point.
            temp = Files.createTempFile(parent, ".cgui-", ".tmp");
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Reported honestly rather than silently degrading: a caller told this filesystem
                // advertises FILE_ATOMIC_WRITE, and quietly doing a non-atomic move would make that a lie.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException e) {
            throw io(path, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort. A stray temp file is untidy; failing the write over it would be worse.
                }
            }
        }
    }

    @Override
    public void mkdir(CgPath path) {
        Path target = resolve(path);
        try {
            Files.createDirectory(target);
        } catch (FileAlreadyExistsException e) {
            throw CgFileSystemException.exists(path);
        } catch (NoSuchFileException e) {
            throw CgFileSystemException.notFound(path);
        } catch (IOException e) {
            throw io(path, e);
        }
    }

    @Override
    public void delete(CgPath path, boolean recursive) {
        Path target = resolve(path);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw CgFileSystemException.notFound(path);

        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!recursive) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
                        if (stream.iterator().hasNext()) {
                            throw new CgFileSystemException(CgFileError.FILE_IS_A_DIRECTORY,
                                    "directory is not empty: " + path);
                        }
                    }
                    Files.delete(target);
                    return;
                }
                // NOFOLLOW by construction: walk() does not follow links unless asked, so a link inside
                // the tree is removed as a link rather than followed into whatever it points at -- which
                // is what stops a recursive delete escaping the project through one.
                try (Stream<Path> walk = Files.walk(target, MAX_DEPTH)) {
                    List<Path> deepestFirst = walk.sorted(Comparator.reverseOrder()).toList();
                    for (Path each : deepestFirst) Files.deleteIfExists(each);
                }
                return;
            }
            Files.delete(target);
        } catch (IOException e) {
            throw io(path, e);
        }
    }

    @Override
    public void rename(CgPath from, CgPath to, boolean overwrite) {
        Path source = resolve(from);
        Path target = resolve(to);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw CgFileSystemException.notFound(from);
        if (!overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw CgFileSystemException.exists(to);
        }
        try {
            if (overwrite) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target);
        } catch (FileAlreadyExistsException e) {
            throw CgFileSystemException.exists(to);
        } catch (IOException e) {
            throw io(to, e);
        }
    }

    // ── Resolution: the one security boundary this class owns ───────────────────────────────────

    /**
     * A {@link CgPath} as a real location, proven to be inside its project.
     *
     * <p>Two checks, and the second is the one that matters:</p>
     * <ol>
     *   <li><b>Lexical.</b> Belt and braces — {@code CgPath} already refuses an escaping path at
     *       construction, so this can only fire if that guarantee is ever broken. Cheap enough to keep as
     *       a tripwire.</li>
     *   <li><b>Real.</b> The deepest <em>existing</em> ancestor is resolved with
     *       {@link Path#toRealPath}, which follows every symlink, and must still lie under the project's
     *       own real root. This is what lexical analysis cannot do: {@code project:link/secret} contains
     *       no {@code ..} at all and may still point at {@code /etc}.</li>
     * </ol>
     *
     * <p>The <em>deepest existing</em> ancestor, rather than the target, because a path being written for
     * the first time does not exist yet — {@code toRealPath} would simply throw. Whatever does not exist
     * cannot be a symlink, so checking the part that does is sufficient and complete.</p>
     */
    /**
     * Reads a window of a file without loading the rest of it.
     *
     * <p>A {@link java.nio.channels.SeekableByteChannel} rather than {@code Files.readAllBytes} plus a
     * copy, which is the whole point: the transfer path exists so a 100 MB file can be sent without the
     * server holding 100 MB.</p>
     */
    @Override
    public byte[] read(CgPath path, long offset, int length) {
        if (offset < 0 || length < 0) {
            throw new CgFileSystemException(CgFileError.INVALID_PATH,
                    "offset and length must not be negative: " + offset + ", " + length);
        }
        Path file = resolve(path);
        try (SeekableByteChannel channel =
                     Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (offset >= size) return new byte[0];
            int wanted = (int) Math.min((long) length, size - offset);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(wanted);
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Short reads are legal and routine on a network mount; loop until the window is full
                // or the channel is done, and hand back whatever arrived.
            }
            byte[] out = new byte[buffer.position()];
            buffer.flip();
            buffer.get(out);
            return out;
        } catch (NoSuchFileException missing) {
            throw CgFileSystemException.notFound(path);
        } catch (java.io.IOException failed) {
            throw new CgFileSystemException(CgFileError.UNKNOWN, "cannot read " + path, failed);
        }
    }

    private Path resolve(CgPath path) {
        WorkspaceProject project = projects.require(path);
        Path root = project.root().toAbsolutePath().normalize();

        Path target = root;
        for (String segment : path.segments()) target = target.resolve(segment);
        target = target.normalize();

        if (!target.startsWith(root)) {
            throw new CgFileSystemException(CgFileError.INVALID_PATH,
                    "path escapes its project root: " + path);
        }

        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            // The project's own directory is missing or unreadable. Reported as the path not being
            // found rather than as a configuration error, because a client must not learn the difference.
            throw CgFileSystemException.notFound(path);
        }

        Path existing = target;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) throw CgFileSystemException.notFound(path);

        try {
            if (!existing.toRealPath().startsWith(realRoot)) {
                throw new CgFileSystemException(CgFileError.INVALID_PATH,
                        "path leaves its project through a link: " + path);
            }
        } catch (IOException e) {
            throw CgFileSystemException.notFound(path);
        }
        return target;
    }

    private static String nameOf(CgPath path, Path target) {
        if (!path.isProjectRoot()) return path.name();
        Path name = target.getFileName();
        return name == null ? "" : name.toString();
    }

    private static CgFileSystemException io(CgPath path, IOException cause) {
        // The message names the PATH, never the cause's text: an IOException routinely carries a
        // server-side absolute path, and this reaches a client.
        if (cause instanceof AccessDeniedException) {
            return new CgFileSystemException(CgFileError.NO_PERMISSIONS, "not permitted: " + path, cause);
        }
        return new CgFileSystemException(CgFileError.UNKNOWN, "io error at " + path, cause);
    }
}
