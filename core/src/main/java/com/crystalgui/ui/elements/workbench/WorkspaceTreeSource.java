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

    /**
     * How a directory's entries are ordered — VS Code's {@code explorer.sortOrder}, minus the two that
     * need data we do not carry.
     *
     * <p>{@code modified} and {@code foldersNestsFiles} are deliberately absent rather than stubbed:
     * {@code CgFileEntry} does carry an mtime, but sorting by it across a lazily-listed tree means a
     * folder re-orders itself the moment it is expanded, and file nesting is off by default even in VS
     * Code. Adding a constant that sorts wrongly is worse than not offering it.</p>
     */
    public enum SortOrder {
        /** Folders first, then by name. VS Code's default and every file manager's. */
        DEFAULT,
        /** Files and folders interleaved, by name alone. */
        MIXED,
        /** Files first — the inverse, for a tree read as a list of documents. */
        FILES_FIRST,
        /** By extension, then name, with folders still first. Groups a directory of assets by kind. */
        TYPE
    }

    private SortOrder sortOrder = SortOrder.DEFAULT;

    /**
     * Changes the order and re-sorts everything already listed.
     *
     * <p>Re-sorting rather than dropping the cache: the listings are still valid, and discarding them
     * would collapse every expanded folder and re-fetch the lot to answer a question about ordering.</p>
     */
    public WorkspaceTreeSource setSortOrder(SortOrder order) {
        this.sortOrder = order == null ? SortOrder.DEFAULT : order;
        for (List<CgPath> listing : children.values()) listing.sort(this::compare);
        dirty = true;
        return this;
    }

    public SortOrder sortOrder() {
        return sortOrder;
    }

    /** The comparator for {@link #sortOrder}. */
    private int compare(CgPath x, CgPath y) {
        boolean dx = directories.contains(x);
        boolean dy = directories.contains(y);
        if (dx != dy) {
            switch (sortOrder) {
                case MIXED -> { }
                case FILES_FIRST -> {
                    return dx ? 1 : -1;
                }
                default -> {
                    return dx ? -1 : 1;
                }
            }
        }
        if (sortOrder == SortOrder.TYPE && !dx) {
            int byType = x.extension().compareToIgnoreCase(y.extension());
            if (byType != 0) return byType;
        }
        // compareToIgnoreCase, so "Zebra" and "apple" sort as a human reads them rather than by code
        // point -- which is what VS Code's sortOrderLexicographicOptions 'default' means.
        return x.name().compareToIgnoreCase(y.name());
    }

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

    /**
     * Forgets a directory's contents so the next read re-fetches them.
     *
     * <p>What a file operation invalidates. <b>Both {@code children} and {@code requested} have to go</b>
     * — the second is the in-flight guard, and leaving it behind means {@code request} declines to ask
     * again and the folder stays permanently empty. That is a one-line omission with no symptom until
     * somebody creates a file and it never appears.</p>
     *
     * <p>Directory-scoped rather than a full clear: a rename touches one folder, or two, and dropping the
     * whole tree would collapse every expanded node the user had opened.</p>
     */
    public void invalidate(CgPath directory) {
        if (directory == null) return;
        children.remove(directory);
        requested.remove(directory);
        dirty = true;
    }

    /** Whether this directory's listing has been fetched — what a reveal needs, to know when to wait. */
    public boolean isListed(CgPath directory) {
        return children.containsKey(directory);
    }

    /** Asks for a directory's listing if it has not been requested. */
    public void ensureListed(CgPath directory) {
        if (directory != null && !children.containsKey(directory)) request(directory);
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
            // Sorted AFTER every child has been recorded as a directory or not -- the comparator asks
            // `directories`, so sorting inside the loop above would order against a set still being built.
            paths.sort(this::compare);
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
