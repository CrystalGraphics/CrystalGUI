package com.crystalgui.ui.elements.workbench;

import com.crystalgui.fs.CgFileEntry;
import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.ProjectInfo;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.ui.elements.tree.TreeDataSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * A {@link TreeDataSource} over an asynchronous {@link WorkspaceClient} — the file tree's model.
 *
 * <h3>Answer from what has arrived; request what has not</h3>
 *
 * <p>{@code TreeDataSource} is synchronous because it is a UI contract, and {@code WorkspaceClient} is not
 * because it is a network round trip. A directory whose listing is still in flight reports <b>no</b>
 * children, which is honest, and resolves itself when the response lands and the view is refreshed. Every
 * remote file browser works this way; the alternative is blocking the render thread on a round trip.</p>
 *
 * <p>{@link #hasChildren} still returns true for such a directory. Reporting it as a leaf would render it
 * without a disclosure arrow, leaving nothing to click to trigger the request it is waiting for.</p>
 *
 * <h3>Failures are reported, not swallowed</h3>
 *
 * <p>A listing that fails clears its {@code requested} mark so it can be retried — a directory being
 * written to at the wrong moment is a normal, transient failure. A failed {@code projects} call is
 * recorded in {@link #failure()} instead: the first version of that was an empty lambda with a comment
 * claiming the status line covered it, so when the call was being dropped outright the tree was simply
 * empty with no reason given.</p>
 */
public final class WorkspaceTreeSource implements TreeDataSource<CgPath> {

    private final WorkspaceClient<?> client;
    private final List<CgPath> roots = new ArrayList<>();
    private final Map<String, String> projectNames = new HashMap<>();
    private final Map<CgPath, List<CgPath>> children = new HashMap<>();
    private final Set<CgPath> directories = new HashSet<>();
    private final Set<CgPath> requested = new HashSet<>();

    private volatile boolean dirty;

    @Nullable
    private String failure;

    public WorkspaceTreeSource(WorkspaceClient<?> client) {
        this.client = client;
    }

    /** The last failure, for a status line. Null when nothing has gone wrong. */
    @Nullable
    public String failure() {
        return failure;
    }

    /**
     * Asks for the project list, which is what gives the tree its roots.
     *
     * <p>Called by the host rather than on construction, because a client's window id is not valid until
     * its session has opened — and the server discards a packet addressed to another window, so a call made
     * too early is thrown away with no error at all.</p>
     */
    public void loadProjects(Runnable onLoaded) {
        client.projects(infos -> {
            roots.clear();
            for (ProjectInfo info : infos) {
                CgPath root = info.root();
                roots.add(root);
                directories.add(root);
                projectNames.put(info.id(), info.displayName());
            }
            dirty = true;
            onLoaded.run();
        }, error -> {
            failure = "projects failed: " + error.code();
            dirty = true;
        });
    }

    public String displayNameOf(CgPath projectRoot) {
        return projectNames.getOrDefault(projectRoot.project(), projectRoot.project());
    }

    public boolean isDirectory(CgPath path) {
        return directories.contains(path);
    }

    /** True once since the last call — a view uses it to decide whether to refresh. */
    public boolean drainRefresh() {
        if (!dirty) return false;
        dirty = false;
        return true;
    }

    @Override
    public List<CgPath> roots() {
        return roots;
    }

    @Override
    public List<CgPath> children(CgPath parent) {
        List<CgPath> known = children.get(parent);
        if (known != null) return known;
        request(parent);
        return List.of();
    }

    @Override
    public boolean hasChildren(CgPath item) {
        return directories.contains(item);
    }

    private void request(CgPath directory) {
        if (!requested.add(directory)) return;
        client.list(directory, entries -> {
            List<CgPath> paths = new ArrayList<>(entries.size());
            for (CgFileEntry entry : entries) {
                CgPath child = directory.resolve(entry.name());
                paths.add(child);
                if (entry.isDirectory()) directories.add(child);
            }
            paths.sort((x, y) -> {
                boolean dx = directories.contains(x), dy = directories.contains(y);
                if (dx != dy) return dx ? -1 : 1;      // directories first, as every file tree does
                return x.name().compareToIgnoreCase(y.name());
            });
            children.put(directory, paths);
            dirty = true;
        }, failed -> {
            // Retryable rather than latched -- the listing may have failed because the directory was
            // being written to.
            requested.remove(directory);
            if (failed.error() != CgFileError.FILE_NOT_FOUND) children.put(directory, List.of());
        });
    }
}
