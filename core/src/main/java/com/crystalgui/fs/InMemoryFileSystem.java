package com.crystalgui.fs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A whole filesystem in a {@code Map}.
 *
 * <p>Ported from VS Code's {@code InMemoryFileSystemProvider}
 * ({@code src/vs/platform/files/common/inMemoryFilesystemProvider.ts}, MIT) — the same tree of file and
 * directory nodes, the same {@code lookup} / {@code lookupAsDirectory} / {@code lookupAsFile} /
 * {@code lookupParentDirectory} resolution chain, and the same rules about what each operation refuses.</p>
 *
 * <h3>This is not only a test double</h3>
 * <p>It is the implementation the protocol is developed against, because it makes the entire server side
 * runnable with no disk, no Minecraft and no GL — a headless test can stand up a workspace, wire a client
 * to it over {@code InMemoryTransport}, and exercise the whole stack in one JVM. VS Code ships theirs for
 * the same reason.</p>
 *
 * <p><b>Case-sensitive</b>, and it advertises {@link CgFileCapability#PATH_CASE_SENSITIVE} so callers can
 * tell. A map keyed by string is case-sensitive whether or not anyone decided it should be, so the
 * capability states it rather than leaving it to be discovered.</p>
 */
public final class InMemoryFileSystem implements CgFileSystem {

    /** A node. Either {@link #content} is set (a file) or {@link #children} is (a directory). */
    private static final class Node {
        final boolean directory;
        long mtime;
        byte[] content;
        final Map<String, Node> children;

        Node(boolean directory, long mtime) {
            this.directory = directory;
            this.mtime = mtime;
            this.content = directory ? null : new byte[0];
            this.children = directory ? new LinkedHashMap<>() : null;
        }
    }

    private final Map<String, Node> roots = new LinkedHashMap<>();

    /**
     * A monotonic stand-in for a wall clock.
     *
     * <p>Deliberately not {@code System.currentTimeMillis()}. An etag is {@code mtime + size}, and a test
     * that writes twice inside one millisecond would produce two identical etags and a conflict check that
     * silently passes when it should fail. A counter makes every write observably distinct, which is what
     * the tests above this need in order to mean anything.</p>
     */
    private long clock = 1L;

    private long tick() {
        return clock++;
    }

    @Override
    public Set<CgFileCapability> capabilities() {
        return CgFileCapability.of(
                CgFileCapability.FILE_READ_WRITE,
                CgFileCapability.PATH_CASE_SENSITIVE,
                CgFileCapability.FILE_ATOMIC_WRITE);   // a map assignment cannot be half-done
    }

    @Override
    public CgFileEntry stat(CgPath path) {
        Node node = lookup(path, false);
        return node.directory
                ? CgFileEntry.directory(nameOf(path), node.mtime)
                : CgFileEntry.file(nameOf(path), node.content.length, node.mtime);
    }

    @Override
    public List<CgFileEntry> list(CgPath directory) {
        Node node = lookupAsDirectory(directory, false);
        List<CgFileEntry> out = new ArrayList<>(node.children.size());
        for (Map.Entry<String, Node> child : node.children.entrySet()) {
            Node value = child.getValue();
            out.add(value.directory
                    ? CgFileEntry.directory(child.getKey(), value.mtime)
                    : CgFileEntry.file(child.getKey(), value.content.length, value.mtime));
        }
        return out;
    }

    @Override
    public byte[] read(CgPath path) {
        return lookupAsFile(path, false).content.clone();
    }

    @Override
    public void write(CgPath path, byte[] content, boolean create, boolean overwrite) {
        if (path.isProjectRoot()) throw CgFileSystemException.isADirectory(path);
        Node parent = lookupParentDirectory(path);
        String name = nameOf(path);
        Node existing = parent.children.get(name);

        if (existing != null && existing.directory) throw CgFileSystemException.isADirectory(path);
        if (existing == null && !create) throw CgFileSystemException.notFound(path);
        if (existing != null && create && !overwrite) throw CgFileSystemException.exists(path);

        Node file = existing;
        if (file == null) {
            file = new Node(false, tick());
            parent.children.put(name, file);
        }
        file.content = content.clone();
        file.mtime = tick();
    }

    @Override
    public void mkdir(CgPath path) {
        if (path.isProjectRoot()) {
            roots.computeIfAbsent(path.project(), key -> new Node(true, tick()));
            return;
        }
        Node parent = lookupParentDirectory(path);
        String name = nameOf(path);
        if (parent.children.containsKey(name)) throw CgFileSystemException.exists(path);
        parent.children.put(name, new Node(true, tick()));
    }

    @Override
    public void delete(CgPath path, boolean recursive) {
        if (path.isProjectRoot()) {
            if (roots.remove(path.project()) == null) throw CgFileSystemException.notFound(path);
            return;
        }
        Node parent = lookupParentDirectory(path);
        String name = nameOf(path);
        Node target = parent.children.get(name);
        if (target == null) throw CgFileSystemException.notFound(path);
        if (target.directory && !target.children.isEmpty() && !recursive) {
            throw new CgFileSystemException(CgFileError.FILE_IS_A_DIRECTORY,
                    "directory is not empty: " + path);
        }
        parent.children.remove(name);
        parent.mtime = tick();
    }

    @Override
    public void rename(CgPath from, CgPath to, boolean overwrite) {
        Node source = lookup(from, false);
        Node targetParent = lookupParentDirectory(to);
        String targetName = nameOf(to);

        if (targetParent.children.containsKey(targetName)) {
            if (!overwrite) throw CgFileSystemException.exists(to);
            targetParent.children.remove(targetName);
        }
        // Removed from its old home only once the new one is known to be free, so a refused rename
        // leaves the tree exactly as it was rather than losing the source.
        Node sourceParent = lookupParentDirectory(from);
        sourceParent.children.remove(nameOf(from));
        targetParent.children.put(targetName, source);
        // THE MTIME IS NOT TOUCHED, because a rename does not modify a file -- on every real
        // filesystem the inode moves and its timestamps go with it. This used to re-stamp it, which
        // made the fake disagree with production about the one fact a rename preserves: `WatchHub`
        // pairs an external delete-and-create into a rename by matching their etags, and an etag is
        // mtime + size. So a rename was unpairable here and pairable everywhere else, which is the
        // worst direction for a fake to be wrong in.
    }

    // ── Seeding, for tests and fixtures ─────────────────────────────────────────────────────────

    /** Creates a project root, so a filesystem can be populated before anything is registered. */
    public InMemoryFileSystem addProject(String project) {
        roots.computeIfAbsent(project, key -> new Node(true, tick()));
        return this;
    }

    /** Writes a file, creating every directory above it. The fixture shorthand. */
    public InMemoryFileSystem seed(String path, byte[] content) {
        CgPath target = CgPath.parse(path);
        addProject(target.project());
        List<String> segments = target.segments();
        Node node = roots.get(target.project());
        for (int i = 0; i < segments.size() - 1; i++) {
            Node next = node.children.get(segments.get(i));
            if (next == null) {
                next = new Node(true, tick());
                node.children.put(segments.get(i), next);
            }
            node = next;
        }
        Node file = new Node(false, tick());
        file.content = content.clone();
        node.children.put(segments.get(segments.size() - 1), file);
        return this;
    }

    public InMemoryFileSystem seed(String path, String content) {
        return seed(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ── Resolution — VS Code's chain, name for name ─────────────────────────────────────────────

    private static String nameOf(CgPath path) {
        return path.name();
    }

    private Node lookup(CgPath path, boolean silent) {
        Node node = roots.get(path.project());
        if (node == null) {
            if (silent) return null;
            throw CgFileSystemException.notFound(path);
        }
        for (String segment : path.segments()) {
            Node child = node.directory ? node.children.get(segment) : null;
            if (child == null) {
                if (silent) return null;
                throw CgFileSystemException.notFound(path);
            }
            node = child;
        }
        return node;
    }

    private Node lookupAsDirectory(CgPath path, boolean silent) {
        Node node = lookup(path, silent);
        if (node == null) return null;
        if (!node.directory) throw CgFileSystemException.notADirectory(path);
        return node;
    }

    private Node lookupAsFile(CgPath path, boolean silent) {
        Node node = lookup(path, silent);
        if (node == null) return null;
        if (node.directory) throw CgFileSystemException.isADirectory(path);
        return node;
    }

    private Node lookupParentDirectory(CgPath path) {
        CgPath parent = path.parent();
        if (parent == null) throw CgFileSystemException.isADirectory(path);
        return lookupAsDirectory(parent, false);
    }
}
