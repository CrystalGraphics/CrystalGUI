package com.crystalgui.fs;

import com.crystalgui.ui.dom.UINode;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.text.Change;

import javax.annotation.Nullable;

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

    /**
     * Somewhere to send a request — <b>not final, because a client outlives its connection</b>.
     *
     * @see #rebind(ProtocolConnection)
     */
    private Caller<T> caller;

    /**
     * The wire currently under this client, so rebinding to the same one is free.
     *
     * <p>Typed as {@code Object} because the two rebind overloads take different things and both need the
     * guard — re-registering this client's push handlers on a router that already has them is what
     * {@code MessageRouter} refuses outright, and a guard on only one overload is a guard that holds until
     * somebody uses the other.</p>
     */
    @Nullable
    private Object boundTo;
    private final com.crystalgui.serialization.DynamicOps<T> ops;

    /**
     * The etag each open path was last seen at.
     *
     * <p>Updated by every read and every successful save. A path absent from here has never been read,
     * and {@link #save} on one is a programming error rather than an unconditional write — the
     * unconditional path exists, but a caller has to ask for it deliberately.</p>
     */
    private final Map<CgPath, String> etags = new HashMap<>();

    /**
     * The last content read per path, with the etag it was read at — P6.1.10 <b>D13</b>.
     *
     * <p>Validated by etag rather than content-addressed, which is what D13 settled on: the etag is
     * already {@code mtime+size} and already travels, so a conditional read costs one field and no
     * hashing. On a hit the server sends {@code unchanged} and the bytes never leave the disk — which
     * matters most for exactly the files a chunked transfer would otherwise re-pull.</p>
     *
     * <p>Dropped when a change notification arrives for the path, so a stale entry cannot outlive the
     * revision it describes.</p>
     */
    private final Map<CgPath, byte[]> cachedContent = new HashMap<>();

    /** Paths this client has asked the server to watch, so a re-read does not ask twice. */
    private final java.util.Set<CgPath> watched = new java.util.HashSet<>();

    /** Somewhere to send a request. The two constructors below supply the two real ones. */
    @FunctionalInterface
    public interface Caller<T> {
        void call(String method, StateMap<T> args,
                  Consumer<StateMap<T>> onResult, Consumer<String> onError);
    }

    /** Told how far a chunked read has got. Both figures are bytes; {@code total} never changes. */
    @FunctionalInterface
    public interface Progress {
        void at(int done, int total);
    }

    /**
     * @param ops the wire format. Taken here rather than read off the session, which does not expose
     *            its own — widening {@code ClientUiSession} for one caller would be the worse trade.
     */
    public WorkspaceClient(ClientUiSession<UINode, T> session, com.crystalgui.serialization.DynamicOps<T> ops) {
        this(session::call, session::onCall, ops);
        // RECORDED, or the first rebind to this same wire would not recognise it and would re-register
        // the push handlers on a router that already has them -- which MessageRouter refuses outright.
        this.boundTo = session;
    }

    /**
     * Rides a connection, so the workspace shares one wire with the UI and everything else on it.
     *
     * <p>This is Phase 4 B1 in one line, and the class needed nothing else: it only ever used the session
     * to {@code call} and to {@code onCall}, which is exactly what a {@link ProtocolConnection} offers —
     * so the swap the earlier design reserved really was a transport swap rather than a rewrite. The ops
     * comes off the connection here, because a connection <em>does</em> expose its own.</p>
     */
    public WorkspaceClient(ProtocolConnection<T> connection) {
        this(connection::call, connection::onRequest, connection.ops());
        // @see the note on the session constructor: a client that does not know what it is bound to
        // cannot tell a rebind from a re-bind to the same thing.
        this.boundTo = connection;
    }

    /** One per connection, memoised weakly so a closed connection's entry goes with it. */
    private static final Map<ProtocolConnection<?>, WorkspaceClient<?>> BY_CONNECTION =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * The file client for this connection, creating it once.
     *
     * <p><b>Use this rather than the constructor</b> whenever the connection is shared. A
     * {@code WorkspaceClient} registers {@code fs.changed} to receive change notifications, and
     * {@link com.crystalgui.net.protocol.MessageRouter} refuses a duplicate registration outright — so a
     * second one on the same wire throws, and it throws from wherever the second consumer happens to be
     * constructed. That is the right refusal and the wrong place to discover it: two subsystems wanting
     * file access on one connection is entirely reasonable, and what is not reasonable is each building
     * its own notification channel for it.</p>
     *
     * <p>Found in game rather than in a test: an editor and a probe each built one over the same player's
     * connection, and the second killed the client during screen init.</p>
     */
    @SuppressWarnings("unchecked")
    public static <T> WorkspaceClient<T> forConnection(ProtocolConnection<T> connection) {
        synchronized (BY_CONNECTION) {
            WorkspaceClient<?> existing = BY_CONNECTION.get(connection);
            if (existing != null) return (WorkspaceClient<T>) existing;
            WorkspaceClient<T> created = new WorkspaceClient<>(connection);
            BY_CONNECTION.put(connection, created);
            return created;
        }
    }

    /**
     * Moves this client onto a new connection — <b>CrystalOS W11, reconnect-on-restore</b>.
     *
     * <h3>Why the client survives instead of being replaced</h3>
     *
     * <p>A window that is hidden is <em>detached</em>, and it can stay that way across a disconnect and
     * a rejoin — which is exactly what retention is for. Everything holding a {@code WorkspaceClient}
     * holds it in a {@code final} field ({@code Workbench}, {@code WorkspaceTreeSource},
     * {@code WorkspaceFileService}), and every consumer callback is registered on the client rather than
     * on the wire. So swapping the wire underneath keeps all of that working and needs no rebind threaded
     * through five widgets; building a second client instead would strand every one of those
     * subscriptions on an object nobody can reach any more.</p>
     *
     * <p>It is also the browser's answer to the same problem. An open connection blocks retention, so
     * bfcache closes it on the way in and reconnects on the way out — the page is not rebuilt.</p>
     *
     * <h3>What the far side has forgotten, and what it has not</h3>
     *
     * <p><b>Watches are re-issued</b>, and this is the defect that would otherwise be invisible.
     * {@link #watched} is a client-side memo meaning "I have already asked the server to watch this", so
     * after a reconnect it is a record of promises the new peer never made: {@code finishRead} sees the
     * path already in the set, never re-asks, and change notifications stop <em>permanently</em> for
     * precisely the files that were open. Nothing fails and nothing is logged — the editor simply stops
     * noticing edits made underneath it.</p>
     *
     * <p><b>Presence and capabilities are dropped and re-seeded.</b> Both are pushed state describing a
     * server this client is no longer talking to. Capabilities fall back to their optimistic default
     * (unknown means allowed) rather than to denied, for the reason {@link #mayWrite} gives at length: a
     * wrongly-greyed command is one the user cannot do and cannot explain.</p>
     *
     * <p><b>Cached content is dropped; etags are kept.</b> Serving bytes read from the old connection
     * would be answering with a file that may have changed while nobody was watching. The etags stay
     * because they are what a {@link #save} quotes, and the server <em>re-stats</em> before writing — so
     * a genuinely stale write comes back as a conflict the user can act on, where a cleared etag would
     * instead make saving a not-previously-read file into a client-side programming error.</p>
     *
     * @return whether anything moved — false when this is already the connection in use
     */
    public boolean rebind(ProtocolConnection<T> connection) {
        if (connection == null || connection == boundTo) return false;
        Object previous = boundTo;
        boundTo = connection;
        bind(connection::call, connection::onRequest);
        // The memo follows the client, or the next forConnection on this wire builds a SECOND client and
        // MessageRouter refuses its duplicate fs.changed registration -- from wherever that second
        // consumer happens to be constructed. @see #forConnection
        synchronized (BY_CONNECTION) {
            if (previous != null) BY_CONNECTION.remove(previous);
            BY_CONNECTION.put(connection, this);
        }
        reestablish();
        return true;
    }

    /** The session-shaped rebind, mirroring the session constructor. @see #rebind(ProtocolConnection) */
    public boolean rebind(ClientUiSession<UINode, T> session) {
        if (session == null || session == boundTo) return false;
        boundTo = session;
        bind(session::call, session::onCall);
        reestablish();
        return true;
    }

    /** Re-asks for everything the far side used to know about us. @see #rebind(ProtocolConnection) */
    private void reestablish() {
        cachedContent.clear();

        presence.clear();
        if (onPresence != null) onPresence.run();

        readable.clear();
        writable.clear();
        if (onCapabilities != null) onCapabilities.run();
        refreshCapabilities();

        // RE-ASKED, not merely remembered. The set says what this client WANTS watched; the new peer has
        // been told none of it.
        for (CgPath path : new java.util.ArrayList<>(watched)) {
            call(WorkspaceProtocol.WATCH, args().putString(WorkspaceProtocol.PATH, path.toString()),
                    ignored -> { }, ignored -> watched.remove(path));
        }

        if (onRebound != null) onRebound.run();
    }

    /**
     * Told when this client has moved to a new wire, so a view can re-read what it is showing.
     *
     * <p>The client restores what the <em>protocol</em> needs — watches, capabilities, presence — and
     * cannot know what any particular view is displaying. A file tree that listed a directory before the
     * disconnect is showing a listing from a server it is no longer attached to, and only the tree knows
     * that.</p>
     */
    public void onRebound(Runnable handler) {
        this.onRebound = handler;
    }

    @Nullable
    private Runnable onRebound;

    private WorkspaceClient(Caller<T> caller, WorkspaceRpc.Registrar<T> registrar,
                            com.crystalgui.serialization.DynamicOps<T> ops) {
        this.ops = ops;
        bind(caller, registrar);
    }

    /**
     * Points this client at a wire and puts its push handlers on it.
     *
     * <p>Separated from the constructor because a <b>reconnect runs it again</b>: the handlers below live
     * on the connection's router, so a new connection has none of them until this is repeated. Every
     * client-level subscription ({@link #onFileChanged} and friends) is deliberately <em>not</em> touched
     * — those belong to whoever asked, not to the wire, which is the whole reason this client's identity
     * is worth preserving across a reconnect rather than building a second one.</p>
     */
    private void bind(Caller<T> caller, WorkspaceRpc.Registrar<T> registrar) {
        this.caller = caller;
        // The server pushes these; nothing asks for them. Registered here rather than left to a caller,
        // because a client that reads a file is watching it (see read) and would otherwise be sent
        // notifications with no handler.
        registrar.register(WorkspaceProtocol.PRESENCE, (args, respond) -> {
            applyPresence(args);
            respond.ok(null);
        });
        registrar.register(WorkspaceProtocol.CAPABILITIES, (args, respond) -> {
            applyCapabilities(args);
            respond.ok(null);
        });
        registrar.register(WorkspaceProtocol.CHANGED, (args, respond) -> {
            CgPath path = CgPath.parse(args.getString(WorkspaceProtocol.PATH, ""));
            String kind = args.getString(WorkspaceProtocol.KIND, WorkspaceProtocol.KIND_MODIFIED);
            String etag = args.has(WorkspaceProtocol.ETAG)
                    ? args.getString(WorkspaceProtocol.ETAG, null) : null;
            // Before the handler runs: a handler that re-reads must not be served the stale bytes.
            cachedContent.remove(path);
            if (onChanged != null) onChanged.accept(new FileChanged(path, kind, etag));
            respond.ok(null);
        });
    }

    /**
     * Whether this actor may write in {@code path}'s project. <b>A hint for enablement, never the
     * authority.</b>
     *
     * <h3>The problem it solves</h3>
     *
     * <p>A command's {@code enabledWhen} runs on the client, so it cannot ask the server <i>may I?</i>:
     * {@code explorer.delete} looked enabled to a non-operator and the refusal arrived as a
     * {@code NO_PERMISSIONS} failure after a round trip. Asking per menu open is worse — that is a round
     * trip inside a UI gesture. So the answer is cached and the server pushes changes, which is VS Code's
     * context-key model: the far side volunteers what it knows and the near side reads it synchronously.</p>
     *
     * <h3>Unknown means yes, and the direction is the whole design</h3>
     *
     * <p>Two things make the cached answer approximate. It is <b>per project</b> while
     * {@link WorkspacePermission} is per path, so a host allowing writes under {@code src/} and refusing
     * them under {@code config/} cannot be represented. And it can be <b>stale</b> between a change and
     * its push, or simply absent before the first answer arrives.</p>
     *
     * <p>So it is optimistic: unknown is available. A wrongly-<em>greyed</em> command is a thing the user
     * cannot do and cannot explain — there is no message, no dialog, nothing to search for. A
     * wrongly-<em>live</em> one fails with a reason the server wrote. Being wrong in the second direction
     * is strictly recoverable and being wrong in the first is not, which is also why nothing here relaxes
     * a check: every operation is still authorised server-side on its real path.</p>
     */
    public boolean mayWrite(@Nullable CgPath path) {
        if (path == null) return true;
        Boolean known = writable.get(path.project());
        return known == null || known.booleanValue();
    }

    /** Whether this actor may read in {@code path}'s project. @see #mayWrite */
    public boolean mayRead(@Nullable CgPath path) {
        if (path == null) return true;
        Boolean known = readable.get(path.project());
        return known == null || known.booleanValue();
    }

    /**
     * Asks the server what this actor may do, and caches the answer.
     *
     * <p>Called once when a workspace opens. The server pushes updates afterwards, so this is a
     * <em>seed</em> rather than a poll — calling it per menu open is the round trip the cache exists to
     * avoid.</p>
     */
    public void refreshCapabilities() {
        caller.call(WorkspaceProtocol.CAPABILITIES, new StateMap<>(ops),
                result -> applyCapabilities(result), error -> {
                    // Left OPTIMISTIC on failure rather than assumed-denied. A server too old to know
                    // this method answers METHOD_NOT_FOUND, and greying out every write against an
                    // otherwise working workspace is a far worse answer than offering one that fails.
                });
    }

    /** Told when the cached answer changes, so a menu bar can re-evaluate what it draws. */
    public void onCapabilitiesChanged(Runnable handler) {
        this.onCapabilities = handler;
    }

    private void applyCapabilities(StateMap<T> in) {
        readable.clear();
        writable.clear();
        for (StateMap<T> entry : in.getList(WorkspaceProtocol.PROJECT_CAPABILITIES, e -> e)) {
            String project = entry.getString(WorkspaceProtocol.PROJECT, "");
            if (project.isEmpty()) continue;
            readable.put(project, entry.getBool(WorkspaceProtocol.MAY_READ, true));
            writable.put(project, entry.getBool(WorkspaceProtocol.MAY_WRITE, true));
        }
        if (onCapabilities != null) onCapabilities.run();
    }

    /** Absent means unknown, which means allowed. @see #mayWrite */
    private final Map<String, Boolean> readable = new HashMap<>();
    private final Map<String, Boolean> writable = new HashMap<>();

    @Nullable
    private Runnable onCapabilities;

    /**
     * Who else has {@code path} open, by display name. Empty when nobody does, or nobody has said yet.
     *
     * <p><b>Empty is not "nobody".</b> It is "nothing has been said", and the two are indistinguishable
     * from here — which is why this is only ever used to <em>add</em> information (a conflict naming who
     * else is editing, a status line) and never to decide anything. A UI that hid a warning because the
     * list was empty would hide it exactly when the server had not got round to answering.</p>
     */
    public List<String> whoElseHasOpen(@Nullable CgPath path) {
        if (path == null) return java.util.Collections.emptyList();
        List<String> others = presence.get(path);
        return others == null ? java.util.Collections.emptyList() : others;
    }

    /** Every path this client knows somebody else has open. */
    public java.util.Set<CgPath> pathsOthersHaveOpen() {
        return new java.util.LinkedHashSet<>(presence.keySet());
    }

    /** Told when the presence view changes, so a status line can redraw. */
    public void onPresenceChanged(Runnable handler) {
        this.onPresence = handler;
    }

    private void applyPresence(StateMap<T> in) {
        // REPLACED WHOLESALE, matching what the server sends. A merge would leave a path that has just
        // become unoccupied showing its last occupant for ever, because "nobody is here now" arrives as
        // an ABSENCE from the list rather than as an entry saying zero.
        presence.clear();
        for (StateMap<T> entry : in.getList(WorkspaceProtocol.PRESENCE_ENTRIES, e -> e)) {
            CgPath path = CgPath.parse(entry.getString(WorkspaceProtocol.PATH, ""));
            List<String> who = entry.getList(WorkspaceProtocol.WHO,
                    e -> e.getString(WorkspaceProtocol.NAME, ""));
            if (!who.isEmpty()) presence.put(path, who);
        }
        if (onPresence != null) onPresence.run();
    }

    private final Map<CgPath, List<String>> presence = new HashMap<>();

    @Nullable
    private Runnable onPresence;

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
                    entry.getString(WorkspaceProtocol.DISPLAY_NAME, ""),
                    // ABSENT MEANS THE CONVENTION, not "no roots" -- an older server that does not send
                    // the field describes an ordinary project, and reading it as rootless would silently
                    // switch every file back to declaration-derived packages.
                    entry.getList(WorkspaceProtocol.SOURCE_ROOTS,
                            root -> root.getString(WorkspaceProtocol.PATH, "")))));
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
        read(path, onResult, onError, null);
    }

    /**
     * Reads, reporting progress — and transparently pulling a large file in chunks.
     *
     * <p><b>A caller cannot tell which happened</b>, and that is the point: the reply says whether the
     * content came with it, and this follows up if it did not. The threshold is the server's, so a client
     * must never assume one. {@code progress} fires for every chunk on the chunked path and not at all on
     * the inline one, because there is nothing to report about something that already arrived.</p>
     *
     * <p>Refused above {@link WorkspaceService#MAX_FILE_BYTES} with {@code FILE_TOO_LARGE}, which reaches
     * a caller as an ordinary {@link Failure} — <em>"file too large to open"</em> is an answer, not a
     * timeout.</p>
     */
    public void read(CgPath path, Consumer<Document> onResult, Consumer<Failure> onError,
                     Progress progress) {
        StateMap<T> ask = args().putString(WorkspaceProtocol.PATH, path.toString());
        byte[] cached = cachedContent.get(path);
        String held = etags.get(path);
        if (cached != null && held != null) ask.putString(WorkspaceProtocol.IF_NONE_MATCH, held);

        call(WorkspaceProtocol.READ, ask,
                result -> {
                    String etag = result.getString(WorkspaceProtocol.ETAG, "");
                    if (result.getBool(WorkspaceProtocol.UNCHANGED, false)) {
                        byte[] have = cachedContent.get(path);
                        if (have != null) {
                            finishRead(path, have, etag, onResult);
                            return;
                        }
                        // The cache went away between asking and answering. Ask again without the
                        // condition rather than handing back nothing -- rare, and silent if unhandled.
                        read(path, onResult, onError, progress);
                        return;
                    }
                    if (!result.getBool(WorkspaceProtocol.CHUNKED, false)) {
                        finishRead(path, result.getBytes(WorkspaceProtocol.CONTENT), etag, onResult);
                        return;
                    }
                    int size = result.getInt(WorkspaceProtocol.SIZE, 0);
                    String transfer = result.getString(WorkspaceProtocol.TRANSFER, "");
                    if (progress != null) progress.at(0, size);
                    pullChunk(path, transfer, size, etag, new byte[size], 0, onResult, onError, progress, true);
                }, onError);
    }

    /**
     * One chunk, then itself again — a continuation rather than a loop, because every call is async.
     *
     * <p>The buffer is allocated once from the size the server reported and filled in place, so a 90 MB
     * file costs one array rather than a chain of concatenations. A slice that would run past the end is
     * <b>clamped rather than trusted</b>: the size and the chunks are two statements from the other side
     * and nothing here needs them to agree.</p>
     */
    private void pullChunk(CgPath path, String transfer, int size, String etag,
                           byte[] buffer, int offset,
                           Consumer<Document> onResult, Consumer<Failure> onError, Progress progress,
                           boolean mayRestart) {
        call(WorkspaceProtocol.READ_CHUNK, args()
                        .putString(WorkspaceProtocol.TRANSFER, transfer)
                        .putInt(WorkspaceProtocol.OFFSET, offset),
                result -> {
                    byte[] slice = result.getBytes(WorkspaceProtocol.CONTENT);
                    int room = Math.max(0, buffer.length - offset);
                    int copied = Math.min(slice.length, room);
                    System.arraycopy(slice, 0, buffer, offset, copied);
                    int next = offset + copied;
                    if (progress != null) progress.at(next, size);
                    // Either signal ends it. A server that says eof is believed; one that stops making
                    // progress would otherwise recurse forever on a zero-length slice.
                    if (result.getBool(WorkspaceProtocol.EOF, false) || next >= size || copied == 0) {
                        finishRead(path, buffer, etag, onResult);
                        return;
                    }
                    pullChunk(path, transfer, size, etag, buffer, next, onResult, onError, progress,
                            mayRestart);
                },
                failure -> {
                    // THE TRANSFER EXPIRED MID-PULL. The server drops one that has gone untouched, which
                    // is what stops an abandoned download leaking -- and a slow client on a busy link can
                    // legitimately hit it. Offset-addressed chunks make a resume "the same request with a
                    // different offset", so the honest recovery is to start again rather than hand the
                    // caller a not-found for a file that is plainly there. ONCE: a second failure is a
                    // real one, and retrying forever would turn a broken transfer into a hot loop.
                    if (mayRestart && failure.error() == CgFileError.FILE_NOT_FOUND) {
                        read(path, onResult, onError, progress);
                        return;
                    }
                    onError.accept(failure);
                });
    }

    /**
     * The bytes last read from the server for this path, or {@code null} if it was never read.
     *
     * <p><b>This is the merge base.</b> It is what both sides descend from: the editor's buffer is this
     * plus whatever has been typed, and the server's current copy is this plus whatever somebody else did.
     * A three-way merge needs exactly that and nothing else, which is why a conflict here never has to fall
     * back to a two-way comparison.</p>
     *
     * <p>Deliberately not a copy of the array. The caller is a merge, which reads it and never writes, and
     * copying every file on every conflict to guard against a caller that does not exist is a cost paid for
     * nothing. @see com.crystalgui.text.diff.ThreeWayMerge</p>
     */
    @Nullable
    public byte[] baseContent(CgPath path) {
        return cachedContent.get(path);
    }

    /** The half both paths share: remember the etag, start watching, hand the document over. */
    private void finishRead(CgPath path, byte[] content, String etag, Consumer<Document> onResult) {
        etags.put(path, etag);
        cachedContent.put(path, content);
        if (watched.add(path)) {
            call(WorkspaceProtocol.WATCH, args().putString(WorkspaceProtocol.PATH, path.toString()),
                    ignored -> { }, ignored -> watched.remove(path));
        }
        onResult.accept(new Document(path, content, etag));
    }

    /**
     * Saves a text file as a set of changes rather than as its whole content — P6.1.10 <b>D10</b>.
     *
     * <p>D10's rule is that writing branches on <b>what this client is holding</b>, not on what the file
     * is: a text document with a matching base revision can send a change set, and anything else sends
     * the whole file. That is knowable locally and correct for the awkward cases by construction — a
     * binary file cannot produce a change set, so it takes {@link #save} without anyone remembering a
     * rule.</p>
     *
     * <p>The conflict story is unchanged, deliberately: the etag is quoted, the server re-stats, and a
     * delta against a file that moved is <b>refused rather than merged</b>. Merging is a decision with a
     * UI attached, and it does not belong in a write path.</p>
     *
     * @throws IllegalStateException if the file was never read — there is no base revision for the
     *                               changes to be against, and guessing one corrupts the file silently
     */
    public void writeDelta(CgPath path, List<Change> changes,
                           Consumer<String> onSaved, Consumer<Failure> onError) {
        String etag = etags.get(path);
        if (etag == null) {
            throw new IllegalStateException("writeDelta() needs a prior read to have a base etag: " + path);
        }
        StateMap<T> args = args()
                .putString(WorkspaceProtocol.PATH, path.toString())
                .putString(WorkspaceProtocol.ETAG, etag);
        args.putList(WorkspaceProtocol.CHANGES, changes, (entry, change) -> entry
                .putInt(WorkspaceProtocol.FROM, change.from())
                .putInt(WorkspaceProtocol.TO, change.to())
                .putString(WorkspaceProtocol.INSERT, change.insert()));
        call(WorkspaceProtocol.WRITE_DELTA, args,
                result -> {
                    String written = result.getString(WorkspaceProtocol.ETAG, "");
                    etags.put(path, written);
                    // These bytes are no longer what the server holds and this client did not compute
                    // them -- dropping beats guessing, and the next read is conditional anyway.
                    cachedContent.remove(path);
                    onSaved.accept(written);
                },
                onError);
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
        delete(path, recursive, trashId -> onDone.run(), onError);
    }

    /**
     * As {@link #delete}, reporting where the copy went so an undo can put it back.
     *
     * <p>The id arrives on the delete's own response rather than needing a second call — and is
     * {@code null} when the server keeps nothing, which is a legitimate configuration rather than a
     * failure. A caller that never undoes uses the {@link Runnable} overload and ignores it.</p>
     */
    public void delete(CgPath path, boolean recursive, Consumer<String> onDeleted,
                       Consumer<Failure> onError) {
        StateMap<T> args = args()
                .putString(WorkspaceProtocol.PATH, path.toString())
                .putBool(WorkspaceProtocol.RECURSIVE, recursive);
        String etag = etags.get(path);
        if (etag != null) args.putString(WorkspaceProtocol.ETAG, etag);
        call(WorkspaceProtocol.DELETE, args, result -> {
            // FORGET, not merely remove: the path is gone, so its etag describes nothing and its watch
            // would report a deletion this client performed as an external change.
            forget(path);
            onDeleted.accept(result.has(WorkspaceProtocol.TRASH_ID)
                    ? result.getString(WorkspaceProtocol.TRASH_ID, null) : null);
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

    /** Puts a deleted file back where it came from, reporting where that was. */
    public void restore(String trashId, Consumer<CgPath> onRestored, Consumer<Failure> onError) {
        call(WorkspaceProtocol.RESTORE, args().putString(WorkspaceProtocol.TRASH_ID, trashId),
                result -> onRestored.accept(
                        CgPath.parse(result.getString(WorkspaceProtocol.PATH, ""))), onError);
    }

    /** Destroys a trashed entry for good. */
    public void purge(String trashId, Runnable onDone, Consumer<Failure> onError) {
        call(WorkspaceProtocol.PURGE, args().putString(WorkspaceProtocol.TRASH_ID, trashId),
                result -> onDone.run(), onError);
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
        caller.call(method, args, onResult::accept,
                error -> onError.accept(Failure.parse(error)));
    }
}
