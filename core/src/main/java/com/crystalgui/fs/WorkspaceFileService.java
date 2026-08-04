package com.crystalgui.fs;

import com.crystalgui.core.signal.Signal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

/**
 * Every file operation the UI performs, with the open documents accounted for.
 *
 * <h3>Why this exists between the explorer and {@link WorkspaceClient}</h3>
 *
 * <p><b>Port of VS Code's {@code IWorkingCopyFileService}</b>, and it exists for the sentence that file
 * names: <i>"any operation that would leave a stale dirty working copy behind will make sure to revert the
 * working copy first."</i> {@link WorkspaceClient} moves bytes; it knows nothing about editors holding
 * unsaved changes to those bytes.</p>
 *
 * <p>The hazard was live here before this class. {@code Workbench} keeps a {@code Map<CgPath, TextEditor>}
 * and <em>nothing</em> updated it when a path changed: rename a file with its editor open and the map
 * keyed on a path that no longer existed — the tab kept its old title, Ctrl+S wrote to the old name, and
 * opening the new name produced a second editor for the same file. Silently, every time.</p>
 *
 * <h3>The rules, and where each diverges</h3>
 *
 * <table>
 *   <tr><th>Operation</th><th>Open documents</th></tr>
 *   <tr><td>{@code move}</td><td><b>Retargeted</b>, keeping content and undo history. The destination's
 *       documents, if it is being overwritten, are closed — their bytes are gone</td></tr>
 *   <tr><td>{@code copy}</td><td>The destination's are closed, for the same reason. The source is
 *       untouched — nothing happened to it</td></tr>
 *   <tr><td>{@code delete}</td><td>Every document at or <b>under</b> the path is closed</td></tr>
 *   <tr><td>{@code create}</td><td>Nothing. There was nothing there</td></tr>
 * </table>
 *
 * <p>The one deliberate divergence is {@code move}: VS Code soft-reverts the source. We retarget instead,
 * because the filesystem plan already settled that for externally-observed renames and a rename the user
 * asked for has even less claim to discard their work. See {@link WorkingCopies#retarget}.</p>
 *
 * <h3>Events, and why the explorer must listen to them rather than to itself</h3>
 *
 * <p>{@link #onWillRun} / {@link #onDidRun} / {@link #onDidFail} mirror VS Code's triple. A view that
 * updates itself at the call site has <em>two</em> paths into its model — its own optimism, and the
 * change notification another client's edit arrives on — and those two will disagree. Rendering only what
 * comes back means one path, and it is the same path for everybody's changes. Q11 in the chrome plan
 * settles this; these signals are what make it possible.</p>
 */
public class WorkspaceFileService {

    /** What happened, or is about to. */
    public enum Kind {
        CREATE, CREATE_FOLDER, MOVE, COPY, DELETE
    }

    /**
     * One operation, as reported.
     *
     * @param source where it came from — {@code null} for everything but {@link Kind#MOVE} and
     *               {@link Kind#COPY}
     */
    public record Operation(Kind kind, CgPath target, @Nullable CgPath source) {

        public static Operation of(Kind kind, CgPath target) {
            return new Operation(kind, target, null);
        }
    }

    /** Fires before the bytes move, after the open documents have been dealt with. */
    public final Signal.Value<Operation> onWillRun = new Signal.Value<>();

    /** Fires once the server has confirmed it. <b>This is what a view should render from.</b> */
    public final Signal.Value<Operation> onDidRun = new Signal.Value<>();

    /** Fires when the server refused. Carries the operation so a caller can name what failed. */
    public final Signal.Pair<Operation, WorkspaceClient.Failure> onDidFail = new Signal.Pair<>();

    private final WorkspaceClient<?> client;
    private final WorkingCopies copies;

    public WorkspaceFileService(WorkspaceClient<?> client, WorkingCopies copies) {
        if (client == null) throw new IllegalArgumentException("a file service needs a workspace client");
        this.client = client;
        this.copies = copies == null ? WorkingCopies.NONE : copies;
    }

    // ── Operations ──────────────────────────────────────────────────────────────────────────────

    /** Creates a file that is not there. Refuses rather than clobbering — see {@code fs.create}. */
    public void create(CgPath path, String content, Runnable onDone, Consumer<WorkspaceClient.Failure> onError) {
        Operation op = Operation.of(Kind.CREATE, path);
        onWillRun.emit(op);
        client.create(path, content.getBytes(StandardCharsets.UTF_8),
                etag -> succeed(op, onDone), failure -> fail(op, failure, onError));
    }

    public void createFolder(CgPath path, Runnable onDone, Consumer<WorkspaceClient.Failure> onError) {
        Operation op = Operation.of(Kind.CREATE_FOLDER, path);
        onWillRun.emit(op);
        client.mkdir(path, () -> succeed(op, onDone), failure -> fail(op, failure, onError));
    }

    /**
     * Moves a file or directory, retargeting whatever is open at the source.
     *
     * <p><b>The retarget happens on success, never before.</b> Doing it first would leave every open
     * document pointing at a path that does not exist whenever the server refuses — and the server refuses
     * routinely, for a name collision or a stale etag, which are both ordinary user mistakes rather than
     * error paths.</p>
     */
    public void move(CgPath from, CgPath to, boolean overwrite,
                     Runnable onDone, Consumer<WorkspaceClient.Failure> onError) {
        Operation op = new Operation(Kind.MOVE, to, from);
        // The DESTINATION's documents go now: if this succeeds their bytes are replaced, and if it fails
        // the overwrite never happened so they were describing the same file either way. Closing early is
        // safe here in a way retargeting the source is not.
        if (overwrite) closeUnder(to);
        onWillRun.emit(op);
        client.rename(from, to, overwrite, () -> {
            retargetUnder(from, to);
            succeed(op, onDone);
        }, failure -> fail(op, failure, onError));
    }

    /**
     * Deletes a file or directory, closing every document at or under it.
     *
     * <p>Closed <b>after</b> the server confirms, for the same reason move retargets late: a refused
     * delete that had already closed the editor would take the user's unsaved work with it and leave the
     * file sitting on disk.</p>
     */
    public void delete(CgPath path, boolean recursive,
                       Runnable onDone, Consumer<WorkspaceClient.Failure> onError) {
        Operation op = Operation.of(Kind.DELETE, path);
        onWillRun.emit(op);
        client.delete(path, recursive, () -> {
            closeUnder(path);
            succeed(op, onDone);
        }, failure -> fail(op, failure, onError));
    }

    /**
     * Copies one <b>file</b>, read-then-create.
     *
     * <p>Two round trips and no server support needed, which is the whole reason it is here rather than
     * waiting on an {@code fs.copy}. A <b>directory</b> copy is deliberately not attempted client-side: it
     * is one round trip per file, it is not atomic, and half of it landing is worse than none of it. That
     * wants a server-side method — see G1 in the chrome plan.</p>
     */
    public void copyFile(CgPath from, CgPath to, Runnable onDone,
                         Consumer<WorkspaceClient.Failure> onError) {
        Operation op = new Operation(Kind.COPY, to, from);
        onWillRun.emit(op);
        client.read(from, document -> client.create(to, document.content(), etag -> {
            closeUnder(to);
            succeed(op, onDone);
        }, failure -> fail(op, failure, onError)), failure -> fail(op, failure, onError));
    }

    // ── Naming ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A name that does not collide, given the names already in the folder.
     *
     * <p>VS Code's {@code explorer.incrementalNaming} in its {@code 'simple'} default: append
     * {@code " copy"}, then {@code " copy 2"}, {@code " copy 3"}. Before the extension, not after — the
     * extension is what decides how a file opens, so {@code Main.java copy} is a plain-text file with a
     * confusing name.</p>
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

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────

    private void succeed(Operation op, Runnable onDone) {
        onDidRun.emit(op);
        if (onDone != null) onDone.run();
    }

    private void fail(Operation op, WorkspaceClient.Failure failure,
                      Consumer<WorkspaceClient.Failure> onError) {
        onDidFail.emit(op, failure);
        if (onError != null) onError.accept(failure);
    }

    private void closeUnder(CgPath path) {
        for (CgPath open : copies.openUnder(path)) copies.close(open);
    }

    /**
     * Retargets every open document under a moved path, rebasing each onto the new root.
     *
     * <p>Renaming a <em>directory</em> is the case this exists for: the moved path is the folder, but what
     * is open are the files inside it, and each needs its own new path rather than the folder's.</p>
     */
    private void retargetUnder(CgPath from, CgPath to) {
        for (CgPath open : copies.openUnder(from)) {
            copies.retarget(open, rebase(open, from, to));
        }
    }

    /** {@code (a/b/c.txt, a/b, x/y)} → {@code x/y/c.txt}; an exact match is just the new path. */
    static CgPath rebase(CgPath open, CgPath from, CgPath to) {
        if (open.equals(from)) return to;
        String openPath = open.path();
        String fromPath = from.path();
        // The separator check is what stops "src2/A.java" being rebased because it starts with "src".
        if (!fromPath.isEmpty() && openPath.startsWith(fromPath + "/")) {
            String tail = openPath.substring(fromPath.length());
            return CgPath.of(to.project(), to.path() + tail);
        }
        return open;
    }
}
