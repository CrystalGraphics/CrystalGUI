package com.crystalgui.fs.client;

import com.crystalgui.core.async.PendingReply;
import com.crystalgui.core.async.PendingStream;
import com.crystalgui.core.async.Reply;
import com.crystalgui.core.async.ReplyError;
import com.crystalgui.core.async.Stream;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.protocol.FsError;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.serialization.Codec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Reading and writing files — every answer a {@link Reply}, every partial answer a {@link Stream}.
 *
 * <pre>{@code
 * files.read(resource).then(answer -> …).onError(error -> …);
 * files.write(resource, bytes, etag).then(newEtag -> …);
 * files.batch("move files", batch -> batch.rename(from, to, false)).then(result -> …);
 * }</pre>
 *
 * <p>Reached through {@link Workspace#files()}. Every mutation carries an operation id, so a call
 * retried after a timeout is answered rather than performed twice, and announces itself through
 * {@link #onWillRun}, {@link #onDidRun} and {@link #onDidFail} — which is where an undo stack, an audit
 * view and a file tree all listen rather than each call site reporting for itself.</p>
 *
 * <h3>Operations are serialised per resource</h3>
 *
 * <p>VS Code's {@code ResourceQueue}. A save and a reload of one file must never interleave, and
 * nothing about the wire orders them: the multiplexer round-robins streams, so two calls about one file
 * arrive in whichever order their windows opened. The queue is per resource rather than global, so a
 * save of one file does not wait behind a listing of another.</p>
 */
public final class FileOperations {

    private final FsCall<?> calls;
    private final AtomicLong operationIds = new AtomicLong();

    /** Per resource, the tail of the chain. @see #after */
    private final Map<Resource, Reply<?>> queues = new LinkedHashMap<>();

    /** A mutation is about to run. What an undo stack and an audit view both listen to. */
    public final Signal.Value<Resource> onWillRun = new Signal.Value<>();

    /** It succeeded. */
    public final Signal.Value<Resource> onDidRun = new Signal.Value<>();

    /**
     * It did not, and why.
     *
     * <p>A {@code ReplyError} rather than an {@code FsError}, because not every failure is the
     * filesystem's: a cancelled operation settles with {@code CANCELLED}, and a caller branching on
     * {@code code()} handles both without knowing which layer raised it. {@code FsError} is what a
     * filesystem failure actually carries, and it is a {@code ReplyError}.</p>
     */
    public final Signal.Pair<Resource, ReplyError> onDidFail =
            new Signal.Pair<>();

    /**
     * The workspace's own undo history — creates, moves and deletes.
     *
     * <p>Not a document's: an {@code UndoStack} belongs to whatever it undoes, and a file operation
     * changes the workspace rather than any one file's contents. The explorer is the scope this is
     * reached through, so Ctrl+Z there takes back a rename while Ctrl+Z in an editor still reaches the
     * editor's own.</p>
     */
    private final UndoStack undoStack = new UndoStack();

    FileOperations(FsCall<?> calls) {
        this.calls = calls;
    }

    public UndoStack undoStack() {
        return undoStack;
    }

    /**
     * Records an operation so Ctrl+Z in the explorer can take it back.
     *
     * <p>Both halves only <b>issue</b> a call; neither waits for one. The operation is already
     * asynchronous and an {@link Edit} that blocked would block the frame — so a view updates when the
     * answer arrives, through the same {@link #onDidRun} every other change takes. Undo is not a second
     * way for a tree to learn about a change.</p>
     */
    private void record(String label, Runnable redo, Runnable undo) {
        if (isReplay()) return;
        undoStack.push(new FileEdit(label, redo, undo));
    }

    /**
     * Whether this call is the stack replaying a step rather than somebody making one.
     *
     * <p>Undo issues the inverse operation and redo issues the original, both through these same
     * methods — so without this each replay would push another entry and the stack would grow on every
     * Ctrl+Z.</p>
     *
     * <p><b>Asked when the call is issued, never when it settles.</b> A delete records its step from the
     * answer, because the trash id it needs arrives with it — and by then the stack has finished
     * applying and would say no. So the answer is taken up front and carried.</p>
     */
    private boolean isReplay() {
        return undoStack.isApplying();
    }

    /** @see #record */
    private static final class FileEdit implements Edit {

        private final String label;
        private final Runnable redo;
        private final Runnable undo;

        // A class rather than a record: a record's component accessors would be named apply() and
        // undo(), which collide with Edit's own methods.
        FileEdit(String label, Runnable redo, Runnable undo) {
            this.label = label;
            this.redo = redo;
            this.undo = undo;
        }

        @Override
        public void apply() {
            redo.run();
        }

        @Override
        public void undo() {
            undo.run();
        }

        @Override
        public String label() {
            return label;
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A file's bytes.
     *
     * <p>Coalesced: a second read of one resource while the first is in flight is the same reply, not a
     * second round trip. A file above the inline limit answers a transfer, which {@link #readStream}
     * pulls through.</p>
     */
    public Reply<FsMessages.ReadResponse> read(Resource resource) {
        return read(resource, null);
    }

    /**
     * The same, conditional on an etag the caller already holds.
     *
     * <p>The server answers "unchanged" and sends no bytes when it matches, which is what makes
     * reopening a tab cost one small message. HTTP's {@code If-None-Match}, which the read path already
     * spoke and which nothing above it could reach.</p>
     */
    public Reply<FsMessages.ReadResponse> read(Resource resource, @Nullable String ifNoneMatch) {
        return calls.coalesced("read:" + resource + ":" + (ifNoneMatch == null ? "" : ifNoneMatch),
                FsMethods.READ, FsMessages.readRequest(),
                new FsMessages.ReadRequest(resource.toString(),
                        ifNoneMatch == null ? "" : ifNoneMatch),
                FsMessages.readResponse());
    }

    /**
     * A file's bytes, <b>pipelined</b> — the chunks arrive as they land and the whole is the result.
     *
     * <p>A file above the inline limit is answered as a transfer, and this pulls it through: each chunk
     * reaches {@link Stream#onPartial} as it lands, and the whole sequence is the settled value. Use it
     * when there is something to do with a partial answer — a progress bar, a partial parse — and
     * {@link #read} otherwise.</p>
     */
    public Stream<byte[]> readStream(Resource resource) {
        PendingStream<byte[]> stream = new PendingStream<>(null);
        read(resource)
                .onError(stream::fail)
                .then(response -> {
                    if (response.transfer().isEmpty()) {
                        stream.emit(response.content());
                        stream.finish();
                        return;
                    }
                    pull(stream, response.transfer(), 0L);
                });
        return stream;
    }

    /**
     * One window, then the next.
     *
     * <p>Serial <em>within</em> a transfer for now, which is honest: the server hands out one transfer
     * id and answers windows of it, and a client that asked for several at once would have to reassemble
     * out of order. What the stream buys immediately is that the caller sees each chunk — a progress
     * bar, a partial parse — rather than nothing until the last one.</p>
     */
    private void pull(PendingStream<byte[]> stream, String transfer, long offset) {
        calls.send(FsMethods.READ_CHUNK, FsMessages.chunkRequest(),
                        new FsMessages.ChunkRequest(transfer, offset, 0), FsMessages.chunkResponse())
                .onError(stream::fail)
                .then(chunk -> {
                    if (chunk.content().length > 0) stream.emit(chunk.content());
                    if (chunk.eof()) stream.finish();
                    else pull(stream, transfer, offset + chunk.content().length);
                });
    }

    /** A file's metadata, with the etag a write must quote back. */
    public Reply<FsMessages.StatResponse> stat(Resource resource) {
        return calls.coalesced("stat:" + resource, FsMethods.STAT, FsMessages.pathRequest(),
                new FsMessages.PathRequest(resource.toString()), FsMessages.statResponse());
    }

    /** One directory's entries, the project's ignore rules already applied. */
    public Reply<FsMessages.ListResponse> list(Resource directory) {
        return calls.coalesced("list:" + directory, FsMethods.LIST, FsMessages.listRequest(),
                new FsMessages.ListRequest(directory.toString()), FsMessages.listResponse());
    }

    /** Every project this actor may see. */
    public Reply<FsMessages.ProjectsResponse> projects() {
        return calls.coalesced("projects", FsMethods.PROJECTS, FsMessages.pathRequest(),
                new FsMessages.PathRequest(""), FsMessages.projectsResponse());
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Replaces a file, refusing if it moved since {@code etag}.
     *
     * <p>An unconditional overwrite passes null, which is the deliberate "mine wins" a person chooses
     * in a conflict dialog — never a default.</p>
     */
    public Reply<String> write(Resource resource, byte[] content, @Nullable String etag) {
        return mutate(resource, FsMethods.WRITE, FsMessages.writeRequest(),
                op -> new FsMessages.WriteRequest(resource.toString(), content,
                        etag == null ? "" : etag, false, true, op));
    }

    /** Creates a file that must not already exist. */
    public Reply<String> create(Resource resource, byte[] content) {
        Reply<String> created = mutate(resource, FsMethods.CREATE, FsMessages.writeRequest(),
                op -> new FsMessages.WriteRequest(resource.toString(), content, "", true, false, op));
        record("create " + resource.name(),
                () -> create(resource, content),
                () -> delete(resource));
        return created;
    }

    public Reply<String> mkdir(Resource resource) {
        Reply<String> made = mutate(resource, FsMethods.MKDIR, FsMessages.pathRequest(),
                op -> new FsMessages.PathRequest(resource.toString(), op));
        record("create " + resource.name(),
                () -> mkdir(resource),
                () -> delete(resource));
        return made;
    }

    /**
     * Moves a file to the trash, answering the id that can restore it.
     *
     * <p><b>The trash id is what makes a delete undoable</b>, and it arrives with the answer rather than
     * before it — so the undo entry is recorded when the delete succeeds, not when it is issued. A
     * delete the server refuses leaves nothing on the stack, which is right: Ctrl+Z must not offer to
     * take back something that never happened.</p>
     */
    public Reply<String> delete(Resource resource) {
        boolean replay = isReplay();
        return mutate(resource, FsMethods.DELETE, FsMessages.pathRequest(),
                        op -> new FsMessages.PathRequest(resource.toString(), op))
                .then(trashId -> {
                    // NO TRASH, NO UNDO. A host without one deletes for good, and offering to take it
                    // back would be a promise nothing can keep.
                    if (replay || trashId == null || trashId.isEmpty()) return;
                    undoStack.push(new FileEdit("delete " + resource.name(),
                            () -> delete(resource),
                            () -> restore(trashId)));
                });
    }

    /** Puts back what a delete moved to the trash. */
    public Reply<String> restore(String trashId) {
        return calls.send(FsMethods.RESTORE, FsMessages.pathRequest(),
                        new FsMessages.PathRequest(trashId), FsMessages.etagResponse())
                .map(FsMessages.EtagResponse::etag);
    }

    public Reply<String> rename(Resource from, Resource to, boolean overwrite) {
        Reply<String> moved = mutate(from, FsMethods.RENAME, FsMessages.moveRequest(),
                op -> new FsMessages.MoveRequest(from.toString(), to.toString(), overwrite, op));
        // NEVER OVERWRITING ON THE WAY BACK, whatever the original did: undoing a move that replaced
        // something must not replace whatever is at the source now.
        record("move " + from.name(),
                () -> rename(from, to, overwrite),
                () -> rename(to, from, false));
        return moved;
    }

    /**
     * Runs a mutation: queued behind anything else about this resource, announced, and carrying an
     * operation id so a retry after a timeout is answered rather than performed again.
     */
    private <A> Reply<String> mutate(Resource resource, String method,
                                     Codec<A> codec,
                                     Function<String, A> args) {
        String op = "op-" + operationIds.incrementAndGet();
        return after(resource, () -> {
            onWillRun.emit(resource);
            return calls.send(method, codec, args.apply(op), FsMessages.etagResponse())
                    .map(FsMessages.EtagResponse::etag)
                    .then(etag -> onDidRun.emit(resource))
                    .onError(error -> onDidFail.emit(resource, error));
        });
    }

    /**
     * Chains work behind whatever else is outstanding for this resource.
     *
     * <p>The queue is the reply itself: each operation's completion is what starts the next. A resource
     * with nothing outstanding starts immediately, so the ordinary case pays one map lookup.</p>
     */
    private <R> Reply<R> after(Resource resource, Supplier<Reply<R>> work) {
        Reply<?> previous = queues.get(resource);
        if (previous == null || previous.isDone()) {
            Reply<R> started = work.get();
            queues.put(resource, started);
            started.always(() -> queues.remove(resource, started));
            return started;
        }
        PendingReply<R> chained =
                new PendingReply<>(null);
        queues.put(resource, chained);
        previous.always(() -> work.get()
                .then(chained::resolve)
                .onError(chained::fail));
        chained.always(() -> queues.remove(resource, chained));
        return chained;
    }

    /**
     * Copies a file — <b>a read and a create</b>, because the server has no copy verb.
     *
     * <p>The bytes are what a copy is <em>of</em>, and across a wire they have to travel. A server-side
     * copy would be a better answer for a large file and is a protocol change rather than a client
     * one.</p>
     */
    public Reply<String> copy(Resource from, Resource to) {
        PendingReply<String> done = new PendingReply<>(null);
        read(from)
                .onError(done::fail)
                .then(content -> create(to, content.content())
                        .onError(done::fail)
                        .then(done::resolve));
        return done;
    }

    /**
     * {@code notes.txt} → {@code notes copy.txt} → {@code notes copy 2.txt}.
     *
     * <p>VS Code's {@code findValidPasteFileTarget}. Here rather than in a widget because it is a rule
     * about naming a file in this workspace, and both the explorer's paste and its drop ask for it.</p>
     *
     * @param taken names already present in the destination folder
     */
    public static String incrementalName(String name, List<String> taken) {
        if (!taken.contains(name)) return name;
        int dot = name.lastIndexOf('.');
        // A leading dot is the whole name of a dotfile, not an extension -- ".gitignore copy", never
        // "gitignore copy.". Same rule LanguageRegistry applies when it decides a language.
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        String candidate = stem + " copy" + suffix;
        for (int n = 2; taken.contains(candidate); n++) {
            candidate = stem + " copy " + n + suffix;
        }
        return candidate;
    }

    /** Whether anything is outstanding for this resource. For a test, and for the health readout. */
    public boolean isBusy(Resource resource) {
        Reply<?> outstanding = queues.get(resource);
        return outstanding != null && !outstanding.isDone();
    }

    // ── Batches ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Several operations as one undo step, with failures reported per item.
     *
     * <p>The batch settles when its members do, and its result names the ones that failed: the eleven
     * files that copied stay copied and the one that did not is reported. Failing wholesale would hand
     * the caller an error and no list, so it could not say which.</p>
     */
    public Reply<BatchResult> batch(String label, Consumer<Batch> body) {
        Batch batch = new Batch(label);
        body.accept(batch);

        PendingReply<BatchResult> settled =
                new PendingReply<>(() -> {
                    for (Reply<?> member : batch.members) member.cancel();
                });
        // SETTLED, not SUCCEEDED. `Reply.all` fails on the first failure, which is right for "I need all
        // of these" and wrong for a batch: the point of reporting per item is that the eleven files that
        // copied stay copied and the one that did not is NAMED. A batch that failed wholesale would hand
        // the caller an error and no list, so it could not say which one.
        int[] remaining = {batch.members.size()};
        if (remaining[0] == 0) {
            settled.resolve(batch.result());
            return settled;
        }
        for (Reply<?> member : batch.members) {
            member.always(() -> {
                if (--remaining[0] == 0) settled.resolve(batch.result());
            });
        }
        return settled;
    }

    /** What a batch collected. */
    public record BatchResult(String label, int succeeded, List<Failure> failures) {

        public boolean isCompletelySuccessful() {
            return failures.isEmpty();
        }
    }

    public record Failure(Resource resource, ReplyError error) {
    }

    /** The operations of one batch, gathered. */
    public final class Batch {
        private final String label;
        private final List<Reply<?>> members = new ArrayList<>();
        private final List<Failure> failures = new ArrayList<>();
        private int succeeded;

        private Batch(String label) {
            this.label = label;
        }

        public Batch write(Resource resource, byte[] content, @Nullable String etag) {
            return track(resource, FileOperations.this.write(resource, content, etag));
        }

        public Batch create(Resource resource, byte[] content) {
            return track(resource, FileOperations.this.create(resource, content));
        }

        public Batch delete(Resource resource) {
            return track(resource, FileOperations.this.delete(resource));
        }

        public Batch rename(Resource from, Resource to, boolean overwrite) {
            return track(from, FileOperations.this.rename(from, to, overwrite));
        }

        public Batch copy(Resource from, Resource to) {
            return track(from, FileOperations.this.copy(from, to));
        }

        public Batch mkdir(Resource resource) {
            return track(resource, FileOperations.this.mkdir(resource));
        }

        private Batch track(Resource resource, Reply<?> member) {
            members.add(member);
            member.then(ok -> succeeded++)
                    .onError(error -> failures.add(new Failure(resource, error)));
            return this;
        }

        private BatchResult result() {
            return new BatchResult(label, succeeded, List.copyOf(failures));
        }
    }
}
