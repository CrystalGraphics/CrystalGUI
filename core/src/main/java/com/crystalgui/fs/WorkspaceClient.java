package com.crystalgui.fs;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.serialization.StateMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The client's side of the workspace: typed calls, and the etag bookkeeping a save depends on.
 *
 * <p>Turns {@code session.call("fs.read", aStateMap, …)} into {@link #read}, and — more importantly —
 * <b>remembers the etag every file was read at</b>, so a later {@link #save} quotes the right one without
 * the UI having to carry it around. Forgetting that is how an editor ends up either never detecting a
 * conflict or reporting one on every save.</p>
 *
 * <h3>Asynchronous, because it genuinely is</h3>
 * <p>Every method takes a callback. {@link CgFileSystem} is synchronous because an implementation of it
 * touches a disk and returns; this is a network round trip, and an API that hid that would be lying about
 * where the latency is.</p>
 *
 * @param <T> the wire format
 */
public final class WorkspaceClient<T> {

    private final ClientUiSession<T> session;
    private final com.crystalgui.serialization.DynamicOps<T> ops;

    /**
     * The etag each open path was last seen at.
     *
     * <p>Updated by every read and every successful save. A path absent from here has never been read,
     * and {@link #save} on one is a programming error rather than an unconditional write — the
     * unconditional path exists, but a caller has to ask for it deliberately.</p>
     */
    private final Map<CgPath, String> etags = new HashMap<>();

    /** Paths this client has asked the server to watch, so a re-read does not ask twice. */
    private final java.util.Set<CgPath> watched = new java.util.HashSet<>();

    /**
     * @param ops the wire format. Taken here rather than read off the session, which does not expose
     *            its own — widening {@code ClientUiSession} for one caller would be the worse trade.
     */
    public WorkspaceClient(ClientUiSession<T> session, com.crystalgui.serialization.DynamicOps<T> ops) {
        this.session = session;
        this.ops = ops;
        // The server pushes these; nothing asks for them. Registered here rather than left to a caller,
        // because a client that reads a file is watching it (see read) and would otherwise be sent
        // notifications with no handler.
        session.onCall(WorkspaceProtocol.CHANGED, (args, respond) -> {
            CgPath path = CgPath.parse(args.getString(WorkspaceProtocol.PATH, ""));
            String kind = args.getString(WorkspaceProtocol.KIND, WorkspaceProtocol.KIND_MODIFIED);
            String etag = args.has(WorkspaceProtocol.ETAG)
                    ? args.getString(WorkspaceProtocol.ETAG, null) : null;
            if (onChanged != null) onChanged.accept(new FileChanged(path, kind, etag));
            respond.ok(null);
        });
    }

    /** What the server reports when a watched file moves. */
    public record FileChanged(CgPath path, String kind, String etag) {

        public boolean isDeleted() {
            return WorkspaceProtocol.KIND_DELETED.equals(kind);
        }
    }

    private Consumer<FileChanged> onChanged;

    /**
     * Called when a watched file moves under us.
     *
     * <p>A UI reacts by reloading a clean document silently and prompting on a dirty one — the etag is
     * carried so it can tell whether what it holds is already current.</p>
     */
    public void onFileChanged(Consumer<FileChanged> handler) {
        this.onChanged = handler;
    }

    /** What a failed call reports: a {@link CgFileError} name, or a conflict carrying the live etag. */
    public record Failure(String code, String actualEtag) {

        public boolean isConflict() {
            return WorkspaceProtocol.ERROR_CONFLICT.equals(code);
        }

        public CgFileError error() {
            try {
                return CgFileError.valueOf(code);
            } catch (IllegalArgumentException e) {
                return CgFileError.UNKNOWN;
            }
        }

        /** Parses what {@code WorkspaceRpc.guard} sent — {@code "CONFLICT <etag>"} or a bare code. */
        static Failure parse(String raw) {
            if (raw == null) return new Failure(CgFileError.UNKNOWN.name(), null);
            int space = raw.indexOf(' ');
            if (space > 0 && raw.startsWith(WorkspaceProtocol.ERROR_CONFLICT)) {
                return new Failure(WorkspaceProtocol.ERROR_CONFLICT, raw.substring(space + 1));
            }
            return new Failure(raw, null);
        }
    }

    /** A file as the client holds it. */
    public record Document(CgPath path, byte[] content, String etag) {

        /** The bytes as text. Callers that want a {@code TextEditor} want this. */
        public String text() {
            return new String(content, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // ── Calls ───────────────────────────────────────────────────────────────────────────────────

    public void projects(Consumer<List<ProjectInfo>> onResult, Consumer<Failure> onError) {
        call(WorkspaceProtocol.PROJECTS, args(), result -> {
            List<ProjectInfo> out = new ArrayList<>();
            result.getList(WorkspaceProtocol.PROJECT_LIST, entry -> out.add(new ProjectInfo(
                    entry.getString(WorkspaceProtocol.ID, ""),
                    entry.getString(WorkspaceProtocol.DISPLAY_NAME, ""))));
            onResult.accept(out);
        }, onError);
    }

    /** One directory's entries. Lazy and per directory, which is what a tree expands with. */
    public void list(CgPath directory, Consumer<List<CgFileEntry>> onResult, Consumer<Failure> onError) {
        call(WorkspaceProtocol.MANIFEST, args().putString(WorkspaceProtocol.PATH, directory.toString()),
                result -> {
                    List<CgFileEntry> out = new ArrayList<>();
                    result.getList(WorkspaceProtocol.ENTRIES, entry -> out.add(new CgFileEntry(
                            entry.getString(WorkspaceProtocol.NAME, ""),
                            entry.getBool(WorkspaceProtocol.DIRECTORY, false)
                                    ? CgFileType.DIRECTORY : CgFileType.FILE,
                            (long) entry.getDouble(WorkspaceProtocol.SIZE, 0),
                            (long) entry.getDouble(WorkspaceProtocol.MTIME, 0))));
                    onResult.accept(out);
                }, onError);
    }

    /**
     * Reads a file, remembers its etag, and starts watching it.
     *
     * <p>Watching is paired with reading rather than left to the caller: you watch what you have open, and
     * a UI that had to remember to ask separately would forget on exactly the paths it cared about. The
     * pair is {@link #forget}, which unwatches.</p>
     */
    public void read(CgPath path, Consumer<Document> onResult, Consumer<Failure> onError) {
        call(WorkspaceProtocol.READ, args().putString(WorkspaceProtocol.PATH, path.toString()),
                result -> {
                    String etag = result.getString(WorkspaceProtocol.ETAG, "");
                    etags.put(path, etag);
                    if (watched.add(path)) {
                        call(WorkspaceProtocol.WATCH,
                                args().putString(WorkspaceProtocol.PATH, path.toString()),
                                ignored -> { }, ignored -> watched.remove(path));
                    }
                    onResult.accept(new Document(path, result.getBytes(WorkspaceProtocol.CONTENT), etag));
                }, onError);
    }

    /**
     * Saves, quoting the etag this client last read.
     *
     * <p>A conflict arrives as a {@link Failure} whose {@link Failure#isConflict()} is true and whose
     * {@link Failure#actualEtag()} is the live one — everything a "reload or keep?" prompt needs, with no
     * second round trip.</p>
     *
     * @throws IllegalStateException if the file was never read. Saving something you have not read is not
     *                               a save; call {@link #overwrite} if that is genuinely meant.
     */
    public void save(CgPath path, byte[] content, Consumer<String> onSaved, Consumer<Failure> onError) {
        String etag = etags.get(path);
        if (etag == null) {
            throw new IllegalStateException("save() needs a prior read to have an etag: " + path
                    + " — use overwrite() to write unconditionally");
        }
        write(path, content, etag, onSaved, onError);
    }

    /** Writes with no etag check. For a caller that means it. */
    public void overwrite(CgPath path, byte[] content, Consumer<String> onSaved, Consumer<Failure> onError) {
        write(path, content, null, onSaved, onError);
    }

    private void write(CgPath path, byte[] content, String etag,
                       Consumer<String> onSaved, Consumer<Failure> onError) {
        StateMap<T> args = args()
                .putString(WorkspaceProtocol.PATH, path.toString())
                .putBytes(WorkspaceProtocol.CONTENT, content);
        // ABSENT, not empty. An empty string is a real etag that never matches, so the two must not
        // collapse -- the server distinguishes them by presence.
        if (etag != null) args.putString(WorkspaceProtocol.ETAG, etag);

        call(WorkspaceProtocol.WRITE, args, result -> {
            String fresh = result.getString(WorkspaceProtocol.ETAG, "");
            etags.put(path, fresh);
            onSaved.accept(fresh);
        }, onError);
    }

    public void create(CgPath path, byte[] content, Consumer<String> onCreated, Consumer<Failure> onError) {
        call(WorkspaceProtocol.CREATE, args()
                .putString(WorkspaceProtocol.PATH, path.toString())
                .putBytes(WorkspaceProtocol.CONTENT, content), result -> {
            String etag = result.getString(WorkspaceProtocol.ETAG, "");
            etags.put(path, etag);
            onCreated.accept(etag);
        }, onError);
    }

    public void mkdir(CgPath path, Runnable onDone, Consumer<Failure> onError) {
        call(WorkspaceProtocol.MKDIR, args().putString(WorkspaceProtocol.PATH, path.toString()),
                result -> onDone.run(), onError);
    }

    /**
     * Removes a file or directory, quoting the etag this client last saw.
     *
     * <p>The etag is quoted <b>automatically</b> from {@link #etagOf}, exactly as {@link #save} does, so a
     * caller cannot forget to guard a destructive call. A path this client has never read carries no etag
     * and is deleted unconditionally, which is right: there is nothing to be stale about.</p>
     *
     * <p>{@code recursive} is the caller's decision and not inferable here — the client does not know
     * whether a path is a directory, and guessing wrong either refuses a legitimate delete or silently
     * takes a subtree with it.</p>
     */
    public void delete(CgPath path, boolean recursive, Runnable onDone, Consumer<Failure> onError) {
        StateMap<T> args = args()
                .putString(WorkspaceProtocol.PATH, path.toString())
                .putBool(WorkspaceProtocol.RECURSIVE, recursive);
        String etag = etags.get(path);
        if (etag != null) args.putString(WorkspaceProtocol.ETAG, etag);
        call(WorkspaceProtocol.DELETE, args, result -> {
            // FORGET, not merely remove: the path is gone, so its etag describes nothing and its watch
            // would report a deletion this client performed as an external change.
            forget(path);
            onDone.run();
        }, onError);
    }

    /**
     * Moves a file or directory, quoting the etag this client last saw for the <em>source</em>.
     *
     * <p><b>The etag moves with the file.</b> Nothing else read the bytes, so what this client knew about
     * {@code from} is exactly what is now true of {@code to} — dropping it would make the next save quote
     * nothing and write unconditionally, silently giving up the conflict guard for every renamed file.</p>
     *
     * <p>The <em>watch</em> deliberately does not move. Watching is per open document and the caller is
     * the one that knows whether anything still has this open; re-watching a path nothing is looking at
     * costs a stat per tick forever.</p>
     */
    public void rename(CgPath from, CgPath to, boolean overwrite,
                       Runnable onDone, Consumer<Failure> onError) {
        StateMap<T> args = args()
                .putString(WorkspaceProtocol.FROM, from.toString())
                .putString(WorkspaceProtocol.TO, to.toString())
                .putBool(WorkspaceProtocol.OVERWRITE, overwrite);
        String etag = etags.get(from);
        if (etag != null) args.putString(WorkspaceProtocol.ETAG, etag);
        call(WorkspaceProtocol.RENAME, args, result -> {
            String carried = etags.get(from);
            forget(from);
            if (carried != null) etags.put(to, carried);
            onDone.run();
        }, onError);
    }

    // ── Etag bookkeeping ────────────────────────────────────────────────────────────────────────

    /** The etag this client last saw for a path, or {@code null} if it has never read it. */
    public String etagOf(CgPath path) {
        return etags.get(path);
    }

    /**
     * Forgets a path — what closing a document does.
     *
     * <p>Not merely tidiness: a stale entry means a later {@code save} quotes an etag from a previous
     * session of the same file and is refused for a reason the user cannot act on.</p>
     */
    public void forget(CgPath path) {
        etags.remove(path);
        if (watched.remove(path)) {
            call(WorkspaceProtocol.UNWATCH, args().putString(WorkspaceProtocol.PATH, path.toString()),
                    ignored -> { }, ignored -> { });
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────

    private StateMap<T> args() {
        return new StateMap<>(ops);
    }

    private void call(String method, StateMap<T> args,
                      Consumer<StateMap<T>> onResult, Consumer<Failure> onError) {
        session.call(method, args, onResult::accept,
                error -> onError.accept(Failure.parse(error)));
    }
}
