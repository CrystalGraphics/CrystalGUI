package com.crystalgui.fs.server;

import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.provider.CgFileEntry;
import com.crystalgui.fs.provider.CgFileSystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Where deleted files go, so a delete is recoverable.
 *
 * <h3>Outside the project, and the client never sees a path into it</h3>
 *
 * <p>{@code .trash/} under the project root fails four ways, any one of them fatal: every other actor on a
 * shared workspace sees it; it ships, because a project directory here is a resource pack or a datapack;
 * it changes the project's content for anything hashing or manifesting it; and the project may be
 * {@code READONLY}. VS Code delegates to the OS trash, IntelliJ keeps Local History in its own system
 * directory, Unity uses the OS trash — not one of them puts it in the project.</p>
 *
 * <p>So this is server-side storage reached only through opaque ids. The client asks for a deletion and
 * gets one; whether the bytes survive is a <b>policy the service applies</b>, and deliberately absent from
 * the protocol. That is also why {@code fs.delete} did not have to change to gain a trash.</p>
 *
 * <h3>An entry is a snapshot, not a file</h3>
 *
 * <p>One shape covers a file and a directory: {@link Entry} carries a list of {@link Item}s, each a path
 * relative to what was deleted. A single file is one item with an empty relative path. Restoring replays
 * them. Without that, deleting a folder would need a second mechanism — and the second mechanism is the
 * one that gets it wrong, because it is exercised a tenth as often.</p>
 */
public interface WorkspaceTrash {

    /** One captured file: its path relative to what was deleted, and its bytes. */
    record Item(String relativePath, byte[] content) {
    }

    /** One deletion. {@code actor} matters because a shared workspace will be asked who deleted this. */
    record Entry(String id, CgPath originalPath, String actor, long deletedAt, boolean directory,
                 List<Item> items) {

        public long size() {
            long total = 0;
            for (Item item : items) total += item.content().length;
            return total;
        }
    }

    /**
     * Discards everything — delete really deletes.
     *
     * <p>Explicit rather than a null, so a host that wants no recovery has said so. A workspace with no
     * trash is a legitimate configuration; one that silently acquired no trash because a field defaulted
     * to null is a data-loss bug waiting for its first user.</p>
     */
    WorkspaceTrash NONE = new WorkspaceTrash() {
        @Override
        public String capture(CgFileSystem files, CgPath path, String actor) {
            return null;
        }

        @Override
        public CgPath restore(CgFileSystem files, String id) {
            throw new CgFileSystemException(CgFileError.FILE_NOT_FOUND, "no trash: " + id);
        }

        @Override
        public boolean purge(String id) {
            return false;
        }

        @Override
        public List<Entry> list(String project) {
            return List.of();
        }
    };

    /**
     * Copies whatever is at {@code path} into the trash, before it is deleted.
     *
     * @return the entry id, or {@code null} when this trash keeps nothing
     */
    @Nullable
    String capture(CgFileSystem files, CgPath path, String actor);

    /**
     * Writes an entry back where it came from and forgets it.
     *
     * @return the path restored to
     * @throws CgFileSystemException if the id is unknown, or something is already in the way
     */
    CgPath restore(CgFileSystem files, String id);

    /** Destroys an entry for good. False if it was not there. */
    boolean purge(String id);

    /** Everything recoverable for one project, newest first. */
    List<Entry> list(String project);

    /**
     * The default: an in-memory store, bounded by count.
     *
     * <p>In memory because {@code core/} has no disk and should not grow one — a host that wants trash to
     * survive a restart implements this interface against whatever storage it already has. Bounded because
     * an unbounded history is a leak nobody notices until it is large, which is the reason IntelliJ's Local
     * History has a retention period at all.</p>
     */
    final class InMemory implements WorkspaceTrash {

        /** How many deletions are kept. Oldest goes first, per project. */
        public static final int DEFAULT_LIMIT = 64;

        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private final int limit;
        private long nextId;

        public InMemory() {
            this(DEFAULT_LIMIT);
        }

        public InMemory(int limit) {
            this.limit = Math.max(1, limit);
        }

        @Override
        public String capture(CgFileSystem files, CgPath path, String actor) {
            CgFileEntry stat = files.stat(path);
            List<Item> items = new ArrayList<>();
            captureInto(files, path, path, items);

            // Monotonic rather than random: an id has to be stable and unique, and nothing here needs it
            // to be unguessable -- it never leaves the server's own bookkeeping.
            String id = "trash-" + (nextId++);
            entries.put(id, new Entry(id, path, actor, nextId, stat.isDirectory(), items));
            evictOldest(path.project());
            return id;
        }

        private static void captureInto(CgFileSystem files, CgPath root, CgPath at, List<Item> out) {
            CgFileEntry stat = files.stat(at);
            if (stat.isDirectory()) {
                for (CgFileEntry child : files.list(at)) {
                    captureInto(files, root, at.resolve(child.name()), out);
                }
                return;
            }
            // "" for the deleted file itself, "sub/dir/name" for anything beneath a deleted folder.
            String relative = at.equals(root) ? "" : at.path().substring(root.path().length() + 1);
            out.add(new Item(relative, files.read(at)));
        }

        @Override
        public CgPath restore(CgFileSystem files, String id) {
            Entry entry = entries.get(id);
            if (entry == null) {
                throw new CgFileSystemException(CgFileError.FILE_NOT_FOUND, "no such trash entry: " + id);
            }
            if (files.exists(entry.originalPath())) {
                // Refusing rather than overwriting: the whole promise of a trash is that using it cannot
                // lose anything, and a restore that clobbers whatever took the name is a second deletion.
                throw new CgFileSystemException(CgFileError.FILE_EXISTS,
                        "something is already at " + entry.originalPath());
            }
            for (Item item : entry.items()) {
                CgPath target = item.relativePath().isEmpty()
                        ? entry.originalPath() : entry.originalPath().resolve(item.relativePath());
                CgPath parent = target.parent();
                if (parent != null && !parent.isProjectRoot() && !files.exists(parent)) {
                    files.mkdir(parent);
                }
                files.write(target, item.content(), true, false);
            }
            // An empty directory captures no items, so nothing above created it.
            if (entry.directory() && !files.exists(entry.originalPath())) {
                files.mkdir(entry.originalPath());
            }
            entries.remove(id);
            return entry.originalPath();
        }

        @Override
        public boolean purge(String id) {
            return entries.remove(id) != null;
        }

        @Override
        public List<Entry> list(String project) {
            List<Entry> found = new ArrayList<>();
            for (Entry entry : entries.values()) {
                if (entry.originalPath().project().equals(project)) found.add(entry);
            }
            java.util.Collections.reverse(found);   // newest first: what you just deleted is what you want
            return found;
        }

        private void evictOldest(String project) {
            List<String> ids = new ArrayList<>();
            for (Entry entry : entries.values()) {
                if (entry.originalPath().project().equals(project)) ids.add(entry.id());
            }
            for (int i = 0; ids.size() - i > limit; i++) entries.remove(ids.get(i));
        }
    }
}
