package com.crystalgui.fs;

import java.util.ArrayList;
import java.util.List;

/**
 * The workspace, as a server offers it: projects, authorisation, and the etag rules.
 *
 * <p>The layer VS Code calls {@code FileService} — it sits above a {@link CgFileSystem} and adds the two
 * things a provider has no business knowing: <b>who is asking</b>, and <b>whether the file moved since
 * the caller last looked</b>.</p>
 *
 * <h3>What each layer owes</h3>
 * <table>
 *   <tr><td>{@link CgPath}</td><td>cannot lexically escape its project</td></tr>
 *   <tr><td>{@link CgFileSystem}</td><td>reads and writes bytes; on a real disk, no symlink escapes</td></tr>
 *   <tr><td><b>this</b></td><td>resolves the project, authorises, and enforces etags</td></tr>
 *   <tr><td>the RPC layer</td><td>carries it to a client</td></tr>
 * </table>
 *
 * <p>Keeping them apart is what makes the whole server side testable with no disk and no network: this
 * class over an {@link InMemoryFileSystem} is a complete, exercisable workspace.</p>
 */
public final class WorkspaceService {

    private final ProjectRegistry projects;
    private final CgFileSystem files;
    private final WorkspacePermission permission;

    /**
     * Where deletions go. {@link WorkspaceTrash#NONE} means they do not come back.
     *
     * <p>Server-side <b>policy</b>, deliberately invisible to the protocol: a client asks for a deletion
     * either way, and does not get to choose whether it is recoverable. That is what let {@code fs.delete}
     * gain a trash without changing shape.</p>
     */
    private final WorkspaceTrash trash;

    public WorkspaceService(ProjectRegistry projects, CgFileSystem files, WorkspacePermission permission) {
        this(projects, files, permission, new WorkspaceTrash.InMemory());
    }

    public WorkspaceService(ProjectRegistry projects, CgFileSystem files, WorkspacePermission permission,
                            WorkspaceTrash trash) {
        if (projects == null || files == null) throw new IllegalArgumentException();
        this.projects = projects;
        this.files = files;
        this.trash = trash == null ? WorkspaceTrash.NONE : trash;
        // A host that registers projects and forgets the callback gets a workspace nobody can open,
        // rather than one everybody can.
        this.permission = permission == null ? WorkspacePermission.DENY_ALL : permission;
    }

    /**
     * The projects this actor may see.
     *
     * <p>Filtered by a READ check on each project's own root, so "may not read the project" and "the
     * project is not there" look identical from outside — which is the same reason
     * {@link ProjectRegistry#require} answers {@code FILE_NOT_FOUND}.</p>
     */
    public List<ProjectInfo> projects(WorkspaceActor actor) {
        List<ProjectInfo> visible = new ArrayList<>();
        for (WorkspaceProject project : projects.all()) {
            CgPath root = CgPath.ofProject(project.id());
            if (permission.allows(actor, project, root, WorkspaceOperation.READ)) {
                visible.add(project.info());
            }
        }
        return visible;
    }

    /**
     * Attaches an OS-level event source — Phase 6.2.
     *
     * <p><b>One per project, not one per peer.</b> Every watch costs an OS handle and Linux caps them at
     * 8,192 per user by default, so N players sharing a workspace must not mean N watchers on the same
     * directory. It lives here for the same reason presence does: this is the one object every
     * {@code WorkspaceRpc} already shares.</p>
     */
    public void attachEvents(CgFileEventSource source) {
        this.events = source == null ? CgFileEventSource.NONE : source;
    }

    /**
     * Everything the filesystem has done since the last call. <b>Call once per tick, from one place.</b>
     *
     * <p>Draining is destructive, so a second caller would silently steal the first one's events — which
     * is why this is on the shared service and the per-peer watchers are handed the batch rather than
     * each asking for their own.</p>
     */
    public List<CgFileEvent> drainFileEvents() {
        return events.drain();
    }

    private CgFileEventSource events = CgFileEventSource.NONE;

    /**
     * Who has what open, across every peer.
     *
     * <p>Lives here because this is the one object every {@link WorkspaceRpc} shares — each has its own
     * actor and its own watcher, so a per-connection home could only ever answer about itself, which is
     * the opposite of what presence means. @see WorkspacePresence</p>
     */
    public WorkspacePresence presence() {
        return presence;
    }

    private final WorkspacePresence presence = new WorkspacePresence();

    /**
     * What this actor may do in each project it can see.
     *
     * <p>Asked against the project's own <b>root</b>, so it is a per-project answer to a per-path
     * question. That is a deliberate coarsening and the reason
     * {@link WorkspaceProtocol#CAPABILITIES} is documented as a hint: a host may allow writes under
     * {@code src/} and refuse them under {@code config/}, and no per-project broadcast can say so.
     * Nothing here relaxes anything — {@link #authorise} still runs on the real path for every
     * operation, which is where the trust actually lives.</p>
     *
     * <p>Projects the actor cannot read are omitted entirely, matching {@link #projects}: "may not read"
     * and "is not there" look identical from outside.</p>
     */
    public List<ProjectCapability> capabilities(WorkspaceActor actor) {
        List<ProjectCapability> answers = new ArrayList<>();
        for (WorkspaceProject project : projects.all()) {
            CgPath root = CgPath.ofProject(project.id());
            if (!permission.allows(actor, project, root, WorkspaceOperation.READ)) continue;
            answers.add(new ProjectCapability(project.id(), true,
                    permission.allows(actor, project, root, WorkspaceOperation.WRITE)));
        }
        return answers;
    }

    /** One project's answer. @see #capabilities */
    public record ProjectCapability(String project, boolean mayRead, boolean mayWrite) {
    }

    /**
     * One directory's entries — the listing a client caches, {@code etag} and all.
     *
     * <p>Per directory and lazy, matching a tree that expands lazily anyway. A whole-project manifest is
     * a large single response and pays for directories nobody opens.</p>
     */
    public List<CgFileEntry> manifest(WorkspaceActor actor, CgPath directory) {
        authorise(actor, directory, WorkspaceOperation.READ);
        List<CgFileEntry> entries = files.list(directory);
        List<String> excludes = projects.require(directory).excludes();
        if (excludes.isEmpty()) return entries;

        List<CgFileEntry> kept = new ArrayList<>(entries.size());
        for (CgFileEntry entry : entries) {
            if (!isExcluded(entry.name(), excludes)) kept.add(entry);
        }
        return kept;
    }

    /**
     * The hard ceiling on a single file — P6.1.10 D11's *"chunked with progress; hard cap 100 MB,
     * refused as file too large to open"*.
     *
     * <p>A cap has to exist somewhere and this is the honest place for it: a client asking for a 4 GB
     * file is not a request to serve slowly, it is one to refuse. Note it is <b>not</b> the same number
     * as the transport's {@code MAX_REASSEMBLY_BYTES} and must not be confused with it — that bounds one
     * <em>message</em>, which is precisely why anything approaching this limit has to be chunked at the
     * protocol level rather than handed over whole.</p>
     */
    public static final long MAX_FILE_BYTES = 100L * 1024 * 1024;

    /**
     * A file's metadata, authorised the same way a read is.
     *
     * <p>Exists so a caller can ask "how big, and may I" without paying for the bytes — which is what
     * lets the cap be enforced before an allocation rather than after one.</p>
     */
    public CgFileEntry stat(WorkspaceActor actor, CgPath path) {
        authorise(actor, path, WorkspaceOperation.READ);
        return files.stat(path);
    }

    /** A file, with the etag a later write must quote back. */
    public FileContent read(WorkspaceActor actor, CgPath path) {
        authorise(actor, path, WorkspaceOperation.READ);
        // STAT BEFORE READ, and the etag comes from the stat. Taking it afterwards would describe the
        // file as it is once the bytes are in hand, which is a different moment.
        CgFileEntry entry = files.stat(path);
        if (entry.isDirectory()) throw CgFileSystemException.isADirectory(path);
        // Before files.read, so the refusal costs a stat rather than the allocation it is refusing.
        if (entry.size() > MAX_FILE_BYTES) {
            throw CgFileSystemException.tooLarge(path, entry.size(), MAX_FILE_BYTES);
        }
        return new FileContent(path, files.read(path), entry.etag());
    }

    /**
     * Replaces a file, refusing if it moved since {@code expectedEtag} was taken.
     *
     * <p><b>The re-stat is the guarantee, and it is here rather than in a watcher.</b> Whatever a
     * platform's file-watching story is — and on a network mount it is often nothing — a write cannot land
     * on a file that changed underneath, because this looks immediately before writing. Watching only ever
     * makes a client find out <em>sooner</em>; correctness never rests on it.</p>
     *
     * @param expectedEtag the etag the caller last saw, or {@code null} to write unconditionally
     * @return the etag the file now has
     * @throws WorkspaceConflictException if the file moved
     */
    public String write(WorkspaceActor actor, CgPath path, byte[] content, String expectedEtag) {
        authorise(actor, path, WorkspaceOperation.WRITE);
        requireUnchanged(path, expectedEtag);
        files.write(path, content, false, true);
        return files.stat(path).etag();
    }

    /**
     * Refuses if {@code path} no longer carries {@code expectedEtag}. A null expectation checks nothing.
     *
     * <p>Extracted so {@link #write}, {@link #delete} and {@link #rename} cannot drift. Three copies of a
     * four-line guard is three chances for one of them to compare the wrong way round, and the one that
     * got it wrong would be the one nobody wrote a test for.</p>
     *
     * <p>A directory has an etag too ({@code mtime + size}), so this is meaningful for a recursive delete
     * as well — though far weaker there, since a directory's mtime says nothing about its contents.</p>
     */
    private void requireUnchanged(CgPath path, String expectedEtag) {
        if (expectedEtag == null) return;
        String actual = files.stat(path).etag();   // throws FILE_NOT_FOUND if it vanished
        if (!expectedEtag.equals(actual)) {
            throw new WorkspaceConflictException(path, expectedEtag, actual);
        }
    }

    /**
     * Creates a file that is not there.
     *
     * <p>Separate from {@link #write} because the failure is different and matters: New File onto an
     * existing path must refuse, not clobber something that appeared while the user was typing a name.</p>
     */
    public String create(WorkspaceActor actor, CgPath path, byte[] content) {
        authorise(actor, path, WorkspaceOperation.WRITE);
        files.write(path, content, true, false);
        return files.stat(path).etag();
    }

    public void mkdir(WorkspaceActor actor, CgPath path) {
        authorise(actor, path, WorkspaceOperation.WRITE);
        files.mkdir(path);
    }

    public void delete(WorkspaceActor actor, CgPath path, boolean recursive) {
        delete(actor, path, recursive, null);
    }

    /**
     * Removes a file or directory, refusing if it moved since {@code expectedEtag} was taken.
     *
     * <p><b>The guard matters more here than it does on {@link #write}.</b> A stale write loses the other
     * author's edit; a stale delete loses the file. Same re-stat, same {@link WorkspaceConflictException},
     * and for the same reason: whatever the platform's watching story is, this looks immediately before
     * acting.</p>
     *
     * @param expectedEtag the etag the caller last saw, or {@code null} to delete unconditionally
     */
    public void delete(WorkspaceActor actor, CgPath path, boolean recursive, String expectedEtag) {
        deleteToTrash(actor, path, recursive, expectedEtag);
    }

    /**
     * Deletes, keeping a copy, and reports where the copy went.
     *
     * <p>The captured id is what makes undo possible: the bytes have to be taken <b>before</b> the delete,
     * and only the server is in a position to do that. A client-side "read it first, then delete" would be
     * two round trips with a window in between where another actor can change what it is about to
     * destroy.</p>
     *
     * @return the trash id, or {@code null} when nothing was kept
     */
    @javax.annotation.Nullable
    public String deleteToTrash(WorkspaceActor actor, CgPath path, boolean recursive,
                                String expectedEtag) {
        authorise(actor, path, WorkspaceOperation.WRITE);
        requireUnchanged(path, expectedEtag);
        // CAPTURE FIRST, and only then delete. The reverse order is a delete that loses the file whenever
        // the capture throws -- and the capture is the half that reads every byte, so it is the half that
        // can fail.
        String id = trash.capture(files, path, actor.id());
        files.delete(path, recursive);
        return id;
    }

    /** Puts a trashed entry back where it came from. Refuses if something has taken its place. */
    public CgPath restore(WorkspaceActor actor, String trashId) {
        CgPath target = trashPathOf(trashId);
        authorise(actor, target, WorkspaceOperation.WRITE);
        return trash.restore(files, trashId);
    }

    /** Destroys a trashed entry for good. */
    public boolean purge(WorkspaceActor actor, String trashId) {
        authorise(actor, trashPathOf(trashId), WorkspaceOperation.WRITE);
        return trash.purge(trashId);
    }

    /** What is recoverable in a project, newest first. */
    public java.util.List<WorkspaceTrash.Entry> trashList(WorkspaceActor actor, String project) {
        authorise(actor, CgPath.ofProject(project), WorkspaceOperation.READ);
        return trash.list(project);
    }

    /**
     * The original path behind a trash id, so a restore can be authorised against the place it will land.
     *
     * <p>Authorising the <em>destination</em> rather than the id is the point: an id says nothing about
     * permissions, and a restore is a write to wherever the file used to live.</p>
     */
    private CgPath trashPathOf(String trashId) {
        for (WorkspaceTrash.Entry entry : trash.list("")) {
            if (entry.id().equals(trashId)) return entry.originalPath();
        }
        // list("") matches no project, so scan across everything the trash holds instead.
        for (WorkspaceProject project : projects.all()) {
            for (WorkspaceTrash.Entry entry : trash.list(project.id())) {
                if (entry.id().equals(trashId)) return entry.originalPath();
            }
        }
        throw new CgFileSystemException(CgFileError.FILE_NOT_FOUND, "no such trash entry: " + trashId);
    }

    /** Both ends are authorised — a move is a write in two places. */
    public void rename(WorkspaceActor actor, CgPath from, CgPath to, boolean overwrite) {
        rename(actor, from, to, overwrite, null);
    }

    /**
     * Moves a file or directory, refusing if the <em>source</em> moved since {@code expectedEtag}.
     *
     * <p>The source, not the destination: what the caller read and is acting on is the thing at
     * {@code from}. A destination that appeared underneath is what {@code overwrite} is for, and it is a
     * different question with a different answer.</p>
     */
    public void rename(WorkspaceActor actor, CgPath from, CgPath to, boolean overwrite,
                       String expectedEtag) {
        authorise(actor, from, WorkspaceOperation.WRITE);
        authorise(actor, to, WorkspaceOperation.WRITE);
        requireUnchanged(from, expectedEtag);
        if (!from.project().equals(to.project())) {
            throw new CgFileSystemException(CgFileError.INVALID_PATH,
                    "cannot rename across projects: " + from + " -> " + to);
        }
        files.rename(from, to, overwrite);
    }

    /** The bytes of a file and the etag they were read at. */
    public record FileContent(CgPath path, byte[] content, String etag) {
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the project and asks the host, in that order.
     *
     * <p>Refusal is {@link CgFileError#NO_PERMISSIONS} with a message that does not say whether the path
     * exists. Anything more specific is an oracle a client can probe to map a server's disk.</p>
     */
    private void authorise(WorkspaceActor actor, CgPath path, WorkspaceOperation operation) {
        WorkspaceProject project = projects.require(path);
        if (!permission.allows(actor, project, path, operation)) {
            throw CgFileSystemException.denied(path);
        }
    }

    /**
     * Glob matching, restricted to what an exclusion list actually needs.
     *
     * <p>{@code *} matches within one name and {@code ?} matches one character; there is no {@code **},
     * because these are applied per directory entry rather than to a whole path. A full glob engine here
     * would be a lot of surface for {@code node_modules} and {@code .git}.</p>
     */
    private static boolean isExcluded(String name, List<String> patterns) {
        for (String pattern : patterns) {
            if (matches(name, pattern, 0, 0)) return true;
        }
        return false;
    }

    private static boolean matches(String name, String pattern, int n, int p) {
        while (p < pattern.length()) {
            char c = pattern.charAt(p);
            if (c == '*') {
                for (int skip = n; skip <= name.length(); skip++) {
                    if (matches(name, pattern, skip, p + 1)) return true;
                }
                return false;
            }
            if (n >= name.length()) return false;
            if (c != '?' && c != name.charAt(n)) return false;
            n++;
            p++;
        }
        return n == name.length();
    }
}
