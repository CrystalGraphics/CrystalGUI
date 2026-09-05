package com.crystalgui.workbench.extension;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.FileOperations;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.WorkbenchSettings;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.explorer.ExplorerCommands;
import com.crystalgui.workbench.explorer.ProjectFileTree;
import com.crystalgui.workbench.toolwindow.ToolWindowKind;

/**
 * The <b>Project</b> tree - the file explorer, and everything that is about looking at the workspace.
 *
 * <p>Enable it by naming {@link #ID} in an application's manifest. It builds the tree from the
 * workbench's own listing model, gives it a context menu and an undo scope, registers its tool window,
 * and remembers which folders were open between runs.</p>
 *
 * <h3>The view is here; the model is the workbench's</h3>
 *
 * <p>The listing itself ({@code WorkbenchContext.projectListing()}), the file decorations and the root
 * watches stay with the engine, because they are true whether or not anybody is looking at a tree - so a
 * product with no explorer still has projects, decorations and change notifications. What lives here is
 * everything that only means something with a tree on screen: revealing the active file, drag-and-drop
 * between folders, and the expanded-folder record.</p>
 *
 * <h3>It listens rather than being pushed</h3>
 *
 * <p>The listing announces when it changes and the tree redraws itself; nothing outside this class names
 * the widget. The same goes for preferences - {@code explorer.autoReveal} is resolved at the moment a
 * reveal happens rather than written onto the engine, so it cannot go stale and the engine holds no
 * field for a panel that may not exist.</p>
 */
public final class ProjectExtension implements WorkbenchExtension {

    public static final String ID = "crystalgui:explorer";

    /** The panel type id — a session record and a stripe button both name it. */
    public static final String TYPE = "project";

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public ProjectExtension() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(WorkbenchContext workbench) {
        ProjectFileTree tree = new ProjectFileTree(workbench.workspace(),
                workbench.projectListing(), workbench.decorations());
        // At construction, not on the first frame with a window: the registry is global, so there is
        // nothing left to wait for.
        tree.setContextMenu(CommandRegistry.global(), ExplorerCommands::menu);
        // The explorer IS the workspace's undo scope. UndoScope.nearest walks outward from focus, so
        // Ctrl+Z in the tree reaches file operations and Ctrl+Z in an editor still reaches its own text.
        tree.setUndoStack(workbench.workspace().files().undoStack());

        Live live = new Live(workbench, tree);
        Disposable panel = workbench.registerToolWindow(
                ToolWindowKind.of(TYPE, "Project")
                        .icon("crystalgui:folder")
                        .anchor(DockDropZone.SPLIT_LEFT)
                        .view(ctx -> tree)
                        .openByDefault());
        Disposable slice = workbench.registerSessionSlice(live.slice);

        live.bind();
        return () -> {
            live.close();
            slice.dispose();
            panel.dispose();
        };
    }

    /** What the feature holds while it is on. */
    private static final class Live {

        private final WorkbenchContext workbench;
        private final ProjectFileTree tree;
        private final ConnectionGroup lifetime = new ConnectionGroup();
        private final Expansion slice = new Expansion();

        /** What was last revealed, so a reveal happens on a CHANGE and not every frame. */
        @Nullable
        private CgPath revealed;

        Live(WorkbenchContext workbench, ProjectFileTree tree) {
            this.workbench = workbench;
            this.tree = tree;
        }

        void bind() {
            lifetime.add(tree.onFileChosen.connect(workbench::openFile));
            lifetime.add(tree.onFilesDropped.connect(this::dropFiles));
            // THE LISTING ANNOUNCES AND THE VIEW REDRAWS. The watcher used to call refresh() on this
            // widget directly, which is a model reaching for a view and is what kept the explorer inside
            // the engine. @see WorkspaceTreeSource#onDidInvalidate
            lifetime.add(workbench.projectListing().onDidInvalidate().connect(() -> tree.treeView().refresh()));
            // A RECONNECT INVALIDATES EVERYTHING AT ONCE, and for a different reason than a change does:
            // every listing describes a server this client is no longer attached to, and no fs.changed
            // can arrive to say so because nothing was watching.
            lifetime.add(workbench.workspace().onDidReconnect.connect(tree::markListingsStale));
            lifetime.add(workbench.onDidOpenDocument().connect(path -> revealActiveFile()));
            slice.attach(workbench, tree);
        }

        /**
         * Selects the active file in the tree when the active tab changes.
         *
         * <p>On a CHANGE only. Revealing every frame would fight the user for the selection — they click
         * a folder, and a frame later the tree jumps back to whatever file is open.</p>
         */
        private void revealActiveFile() {
            // READ AT THE MOMENT IT MATTERS, not cached and not pushed in. The engine used to hold an
            // `autoReveal` field that WorkbenchSettings wrote through a setter -- one more thing it had
            // to know about a panel -- and a preference resolved on a tab change costs nothing.
            if (!Boolean.TRUE.equals(workbench.resolve(WorkbenchSettings.AUTO_REVEAL))) return;
            CgPath active = workbench.activeFilePath();
            if (active == null || active.equals(revealed)) return;
            revealed = active;
            tree.reveal(active);
        }

        /**
         * A drag-and-drop from the tree — move by default, copy with the modifier.
         *
         * <p>Each item is issued independently, for the reason paste is: several files dropped into a
         * folder are several operations that can succeed or fail separately, and stopping on the first
         * refusal leaves the user guessing which ones landed.</p>
         */
        private void dropFiles(List<CgPath> sources, ProjectFileTree.DropRequest request) {
            // ONE UNDO STEP FOR THE WHOLE DROP, and it settles when its members do.
            workbench.workspace().files().batch(request.copy() ? "copy files" : "move files", batch -> {
                for (CgPath source : sources) {
                    // A folder dropped into itself or its own descendant would move a directory under
                    // itself, which the filesystem refuses with a message about paths rather than about
                    // the gesture.
                    if (source.equals(request.destination()) || source.contains(request.destination())) {
                        Notifications.show(Notification.error("Cannot move")
                                .withDetail(source.name() + " into itself"));
                        continue;
                    }
                    CgPath target = request.destination().resolve(source.name());
                    if (target.equals(source)) continue;   // dropped back where it already is
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

        void close() {
            lifetime.disconnectAll();
            slice.close();
        }
    }

    /**
     * Which folders were open — <b>the explorer's corner of the session record</b>.
     *
     * <p>{@code WorkbenchSession} used to write this itself, off {@code fileTree().treeView()}, and
     * restore it with a 600-attempt per-frame budget. Both were the reach that kept the explorer inside
     * the engine. The retry is per LISTING here, which is the only moment the answer can have
     * changed.</p>
     */
    private static final class Expansion implements SessionSlice {

        private static final String KEY_EXPANDED = "expanded";
        private static final String KEY_PATH = "path";

        private final Set<CgPath> pending = new LinkedHashSet<>();
        private final ConnectionGroup lifetime = new ConnectionGroup();

        @Nullable
        private WorkbenchContext workbench;
        @Nullable
        private ProjectFileTree tree;

        void attach(WorkbenchContext workbench, ProjectFileTree tree) {
            this.workbench = workbench;
            this.tree = tree;
            lifetime.add(workbench.projectListing().onDidLoadListing().connect(directory -> drain()));
        }

        @Override
        public String id() {
            return ID;
        }

        @Override
        public void write(StateMap<JsonElement> into) {
            if (tree == null) return;
            into.putList(KEY_EXPANDED, tree.treeView().expandedItems(),
                    (entry, path) -> entry.putString(KEY_PATH, path.toString()));
        }

        @Override
        public void read(StateMap<JsonElement> from) {
            pending.clear();
            // THE SAME KEY THE SESSION USED TO OWN AT THE TOP LEVEL, which is why a slice with an empty
            // corner is handed the whole record: one arrangement per user, against ten lines.
            for (String raw : from.getList(KEY_EXPANDED, map -> map.getString(KEY_PATH, ""))) {
                if (raw.isEmpty()) continue;
                CgPath parsed = parseOrNull(raw);
                if (parsed != null) pending.add(parsed);
            }
            drain();
        }

        /** Expands whatever has arrived. Called per listing; a folder that never lists simply lingers. */
        private void drain() {
            if (pending.isEmpty() || tree == null || workbench == null) return;
            for (CgPath folder : new ArrayList<>(pending)) {
                if (!workbench.projects().hasChildren(folder)) continue;
                tree.treeView().setExpanded(folder, true);
                pending.remove(folder);
            }
        }

        @Nullable
        private static CgPath parseOrNull(String raw) {
            try {
                return CgPath.parse(raw);
            } catch (RuntimeException unparseable) {
                return null;
            }
        }

        void close() {
            lifetime.disconnectAll();
            pending.clear();
            workbench = null;
            tree = null;
        }
    }
}
