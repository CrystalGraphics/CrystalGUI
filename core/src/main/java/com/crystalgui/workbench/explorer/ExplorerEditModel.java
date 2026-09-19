package com.crystalgui.workbench.explorer;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.document.DraggedResources;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.FileOperations;
import com.crystalgui.widget.collection.tree.TreeEditModel;
import com.crystalgui.widget.overlay.InputDialog;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.WorkbenchSettings;

/**
 * What dragging, pasting, deleting and renaming do to <b>files</b> — the Project panel's half of
 * {@code TreeEditing}.
 *
 * <p>Unordered: the listing sorts, so a drop lands in a folder and a file means its folder. Every verb goes
 * through the workspace's file service as one undo step, and a failure is named per file.</p>
 */
final class ExplorerEditModel implements TreeEditModel<CgPath> {

    private final WorkbenchContext workbench;

    private final ProjectFileTree tree;

    ExplorerEditModel(WorkbenchContext workbench, ProjectFileTree tree) {
        this.workbench = workbench;
        this.tree = tree;
    }

    @Override
    public boolean isOrdered() {
        return false;
    }

    @Nullable
    @Override
    public CgPath parentOf(CgPath path) {
        return path.isProjectRoot() ? null : path.parent();
    }

    @Override
    public int indexOf(CgPath path) {
        return -1;
    }

    @Override
    public boolean isContainer(CgPath path) {
        return tree.isDirectory(path);
    }

    /** Anything but a project root, a row still being named, or a path the server will refuse. */
    @Override
    public boolean canEdit(CgPath path) {
        return !path.isProjectRoot() && !WorkspaceTreeSource.isPlaceholder(path) && mayWrite(path);
    }

    @Override
    public boolean canDrop(List<CgPath> paths, CgPath folder) {
        return TreeEditModel.super.canDrop(paths, folder) && mayWrite(folder);
    }

    /** The files among {@code paths}, which an editor area opens where they are dropped. Folders are not opened. */
    @Nullable
    @Override
    public Object transfer(List<CgPath> paths) {
        List<Resource> files = new ArrayList<>();
        for (CgPath path : paths) {
            if (!tree.isDirectory(path)) files.add(Resource.of(path));
        }
        return files.isEmpty() ? null : new DraggedResources(files);
    }

    @Override
    public String nameOf(CgPath path) {
        return path.name();
    }

    @Override
    public String noun() {
        return "file";
    }

    @Override
    public void move(List<CgPath> paths, Target<CgPath> to) {
        transfer(paths, to.parent(), false);
    }

    @Override
    public void copy(List<CgPath> paths, Target<CgPath> to) {
        transfer(paths, to.parent(), true);
    }

    /**
     * Moves or copies into {@code folder}, one batch, each file on its own.
     *
     * <p>A copy never overwrites: a taken name gets VS Code's incremental one, whether it is pasted back
     * where it came from or onto a namesake elsewhere. A move onto a namesake would clobber a file with no
     * undo underneath, so it is refused and named. A move to where it already is does nothing.</p>
     */
    private void transfer(List<CgPath> paths, CgPath folder, boolean copying) {
        List<String> taken = namesIn(folder);
        workbench.workspace().files().batch(copying ? "copy files" : "move files", batch -> {
            for (CgPath source : paths) {
                CgPath into = folder.resolve(source.name());
                if (copying) {
                    if (taken.contains(into.name())) {
                        into = folder.resolve(FileOperations.incrementalName(source.name(), taken));
                    }
                    taken.add(into.name());
                    batch.copy(Resource.of(source), Resource.of(into));
                } else if (into.equals(source)) {
                    continue;
                } else if (taken.contains(into.name())) {
                    Notifications.show(Notification.error("Cannot move " + source.name())
                            .withDetail(folder.name() + " already has a " + into.name()));
                } else {
                    taken.add(into.name());
                    batch.rename(Resource.of(source), Resource.of(into), false);
                }
            }
        }).then(this::reportFailures);
    }

    /**
     * Deletes, behind {@code explorer.confirmDelete}.
     *
     * <p>A tab on a deleted file closes with it: a save from it would recreate what was just removed. Unsaved
     * work keeps its tab; see {@code EditorService.closeDeleted}.</p>
     */
    @Override
    public void delete(List<CgPath> paths) {
        Runnable delete = () -> workbench.workspace().files().batch("delete files", batch -> {
            for (CgPath path : paths) batch.delete(Resource.of(path));
        }).then(result -> {
            List<Resource> failed = new ArrayList<>();
            for (FileOperations.Failure failure : result.failures()) failed.add(failure.resource());
            for (CgPath path : paths) {
                if (!failed.contains(Resource.of(path))) workbench.editors().closeDeleted(Resource.of(path));
            }
            reportFailures(result);
        });
        if (!Boolean.TRUE.equals(workbench.resolve(WorkbenchSettings.CONFIRM_DELETE))) {
            delete.run();
            return;
        }
        InputDialog.confirm(tree, "Delete", confirmation(paths), "Delete", delete);
    }

    private String confirmation(List<CgPath> paths) {
        if (paths.size() > 1) return "Delete " + paths.size() + " items?";
        CgPath path = paths.get(0);
        return tree.isDirectory(path) ? "Delete '" + path.name() + "' and everything in it?"
                : "Delete '" + path.name() + "'?";
    }

    @Override
    public void rename(CgPath path, String name) {
        workbench.workspace().files().rename(Resource.of(path), Resource.of(path.parent().resolve(name)), false);
    }

    /** The stem, so the first keystroke does not take the extension with it. */
    @Override
    public int renameSelectionEnd(CgPath path, String name) {
        return name.lastIndexOf('.');
    }

    @Override
    public boolean acceptsName(CgPath path, String name) {
        return ProjectFileTree.isWellFormedName(name);
    }

    @Nullable
    @Override
    public String freeNameFor(CgPath path, String name) {
        return tree.freeNameFor(path, name);
    }

    /** Into a selected folder or beside a selected file — and with nothing selected, the first project. */
    @Nullable
    @Override
    public Target<CgPath> pasteTarget(List<CgPath> selection) {
        if (!selection.isEmpty()) return TreeEditModel.super.pasteTarget(selection);
        List<CgPath> roots = tree.source().roots();
        return roots.isEmpty() ? null : new Target<>(roots.get(0), -1);
    }

    /** Full {@code project:dir/file} paths, one per line, so a paste into a script or a message is useful. */
    @Override
    public String clipboardText(List<CgPath> paths) {
        StringBuilder text = new StringBuilder();
        for (CgPath path : paths) {
            if (text.length() > 0) text.append('\n');
            text.append(path);
        }
        return text.toString();
    }

    /** Unknown is yes: a stale answer that greys a command is worse than a refusal the server explains. */
    private boolean mayWrite(CgPath path) {
        return workbench.workspace().capabilities().mayWrite(Resource.of(path));
    }

    private List<String> namesIn(CgPath folder) {
        List<String> names = new ArrayList<>();
        for (CgPath child : workbench.projects().children(folder)) names.add(child.name());
        return names;
    }

    private void reportFailures(FileOperations.BatchResult result) {
        for (FileOperations.Failure failure : result.failures()) {
            Notifications.show(Notification.error("Could not " + result.label())
                    .withDetail(failure.resource().name() + " -- " + failure.error().detail()));
        }
    }
}
