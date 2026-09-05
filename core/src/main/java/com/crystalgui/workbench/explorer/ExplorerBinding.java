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
 * <b>What a change on the server means to this workbench</b> - the watch on each project root, and what
 * happens when a file moves under you.
 *
 * <p>The workbench's own collaborator. It keeps a recursive watch per project root and turns each
 * notification into the right local consequence: the listing is invalidated, a clean open document is
 * reloaded, a dirty one is marked stale rather than overwritten, and a renamed file's tab follows it.</p>
 *
 * <h3>It has nothing to do with the panel</h3>
 *
 * <p>Despite the name, all of this is true whether or not anybody is looking at a file tree - which is
 * why it stayed with the engine when the tree became {@code ProjectExtension}. What went with the panel
 * is what only means something with a view on screen: reveal-on-tab-change, the auto-reveal preference,
 * and drag-and-drop between folders.</p>
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
        List<CgPath> roots = workbench.projects().roots();
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
                    workbench.projects().invalidate(moved.parent());
                    if (!change.from().isEmpty()) {
                        workbench.projects().invalidate(CgPath.parse(change.from()).parent());
                    }
                    externalChange(change);
                }
                // NO `treeView().refresh()` HERE any more: invalidating ANNOUNCES, and whoever is
                // showing the listing subscribes. A watcher reaching for a widget is what kept the
                // explorer inside the engine. @see WorkspaceTreeSource#onDidInvalidate
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

}
