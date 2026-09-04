package com.crystalgui.workbench.explorer;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.document.Document;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.FileOperations;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.diff.ConflictDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracted from {@link Workbench}. See the plan's §4.5 for why this cluster is one thing.
 */
public final class ExplorerBinding {

    private final Workbench workbench;

    public ExplorerBinding(Workbench workbench) {
        this.workbench = workbench;
    }

    /**
     * Takes a recursive watch on every project root, and drops the ones whose root is gone.
     *
     * <p>Runs whenever the listing changes rather than once, because it can change: a reconnect
     * re-lists, and a project opened or closed on the server moves the set. Re-taking a root already
     * watched would cost a second subscription — {@code Workspace.watch} would hand back the same
     * object with its holder count bumped, and this would add a second listener to it — so a root
     * already in the map is left alone.</p>
     */
    public void watchProjectRoots() {
        List<CgPath> roots = workbench.fileTree.source().roots();
        List<CgPath> gone = new ArrayList<>();
        for (Map.Entry<CgPath, Workbench.RootWatch> each : workbench.rootWatches.entrySet()) {
            if (!roots.contains(each.getKey())) gone.add(each.getKey());
        }
        for (CgPath root : gone) workbench.rootWatches.remove(root).dispose();

        for (CgPath root : roots) {
            if (workbench.rootWatches.containsKey(root)) continue;
            Workspace.Watch watch = workbench.workspace.watch(Resource.of(root), true);
            Connection listener = watch.onChanged.connect(changes -> {
                for (FsMessages.FileChange change : changes) {
                    CgPath moved = CgPath.parse(change.path());
                    workbench.fileTree.source().invalidate(moved.parent());
                    if (!change.from().isEmpty()) {
                        workbench.fileTree.source().invalidate(CgPath.parse(change.from()).parent());
                    }
                    externalChange(change);
                }
                workbench.fileTree.treeView().refresh();
            });
            workbench.rootWatches.put(root, new Workbench.RootWatch(watch, listener));
        }
    }

    /**
     * Phase 5.5 — a conflict is a question, not a notification.
     *
     * <p>This was a balloon whose single action was <i>"Reopen to take theirs"</i>, and the comment beside
     * it already said the prose had named the fix and that it should be a button. It understated the
     * problem twice. A balloon <b>fades</b>, so a user who was not looking takes the default — and the
     * default was "your save silently did not happen". And that one button <b>discards unsaved work in a
     * click</b>, while the opposite resolution, keep mine, was not offered at all.</p>
     *
     * <p>Both resolutions destroy something, which is exactly the case that earns a modal.
     * @see ConflictDialog</p>
     */
    /**
     * Phase 6.3 — what to do when a file changes on the server underneath an open editor.
     *
     * <p>The notification has crossed the wire since Phase 4 and reached <b>only the file tree</b>: an
     * open editor was never told. So a clean buffer showed stale content for ever, a deleted file left a
     * perfectly normal-looking tab, and a change under a dirty buffer was discovered at save time or not
     * at all.</p>
     *
     * <table>
     *   <tr><th>State</th><th>What happens</th></tr>
     *   <tr><td>Clean, changed</td><td><b>Reloaded silently.</b> The overwhelmingly common case — a
     *       git checkout, an external save — and prompting for it is what makes a watcher feel naggy
     *       rather than helpful. VS Code does the same, and there is nothing to lose: the buffer and the
     *       file agreed a moment ago</td></tr>
     *   <tr><td>Dirty, changed</td><td>Marked and <b>left alone</b>. Reloading would destroy unsaved
     *       work without asking; the decision belongs at save time, where {@code ConflictDialog} already
     *       makes it with all three answers on the table</td></tr>
     *   <tr><td>Deleted</td><td>Marked, buffer kept. Closing the tab throws away text the user may well
     *       want to write back — which is the whole reason the buffer is worth more than the file</td></tr>
     *   <tr><td>Not open</td><td>Nothing. The tree refresh above is the entire correct response</td></tr>
     * </table>
     */
    private void externalChange(FsMessages.FileChange change) {
        CgPath path = CgPath.parse(change.path());
        // THE DOCUMENT ITSELF ALREADY ACTED. WorkspaceDocuments watches every open document and has
        // moved it to ORPHANED, CONFLICTING or reloaded it before this runs -- which is why this is a
        // BADGE and not a decision. Two places deciding what a change means is how a reload and a
        // conflict prompt came to race each other.
        Document document = workbench.documents.get(Resource.of(path));
        if (document == null) return;
        switch (document.state()) {
            case ORPHANED -> {
                workbench.externallyDeleted.add(path);
                workbench.externallyChanged.remove(path);
            }
            case CONFLICTING -> {
                workbench.externallyDeleted.remove(path);
                workbench.externallyChanged.add(path);
            }
            default -> {
                workbench.externallyDeleted.remove(path);
                workbench.externallyChanged.remove(path);
            }
        }
        workbench.documentTabs.refreshTabTitles();
    }

    public Workbench setAutoReveal(boolean enabled) {
        workbench.autoReveal = enabled;
        return workbench;
    }

    /**
     * Selects the active file in the tree when the active tab changes.
     *
     * <p>On a CHANGE only. Revealing every frame would fight the user for the selection — they click a
     * folder, and a frame later the tree jumps back to whatever file is open.</p>
     */
    public void revealActiveFile() {
        if (!workbench.autoReveal) return;
        CgPath active = workbench.activeFilePath();
        if (active == null || active.equals(workbench.revealed)) return;
        workbench.revealed = active;
        workbench.fileTree.reveal(active);
    }

    /**
     * Performs a drag-and-drop from the tree — move by default, copy with the modifier.
     *
     * <p>Each item is issued independently, for the reason paste is: several files dropped into a folder
     * are several operations that can succeed or fail separately, and stopping on the first refusal leaves
     * the user guessing which ones landed.</p>
     */
    public void dropFiles(List<CgPath> sources, ProjectFileTree.DropRequest request) {
        // ONE UNDO STEP FOR THE WHOLE DROP, and it settles when its members do -- the batch used to take
        // `track()` runnables the caller had to remember to call, and a forgotten one left the
        // transaction open for good.
        workbench.files.batch(request.copy() ? "copy files" : "move files", batch -> {
            for (CgPath source : sources) {
                // A folder dropped into itself or its own descendant would move a directory under
                // itself, which the filesystem refuses with a message about paths rather than about the
                // gesture.
                if (source.equals(request.destination()) || source.contains(request.destination())) {
                    Notifications.show(Notification.error("Cannot move")
                            .withDetail(source.name() + " into itself"));
                    continue;
                }
                CgPath target = request.destination().resolve(source.name());
                if (target.equals(source)) continue;   // dropped back where it already is
                // IN THE BATCH, like the move beside it. It was a bare read-and-create outside the
                // batch, so a copy that failed was reported by nothing while the move next to it was
                // named -- and a dropped FOLDER did nothing at all, a read of a directory being an
                // error. `fs/copy` is the server's now and takes a whole subtree.
                if (request.copy()) {
                    batch.copy(Resource.of(source), Resource.of(target));
                } else {
                    batch.rename(Resource.of(source), Resource.of(target), false);
                }
            }
        }).then(result -> {
            if (result.isCompletelySuccessful()) return;
            // NAMED, which is the whole point of reporting per item: the eleven that moved stay moved
            // and the one that did not is said out loud.
            for (FileOperations.Failure failure : result.failures()) {
                Notifications.show(Notification.error("Could not " + result.label())
                        .withDetail(failure.resource().name() + " -- " + failure.error().detail()));
            }
        });
    }

}
