package com.crystalgui.ui.elements.workbench;

import com.crystalgui.fs.CgFileEntry;
import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.ProjectInfo;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
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
     * Re-fetches a directory's contents, <b>keeping the ones it already has until the new list arrives</b>.
     *
     * <p>What a file operation invalidates. The in-flight guard has to be dropped, or {@code request}
     * declines to ask again and the folder stays frozen on its old contents forever — a one-line omission
     * with no symptom until somebody creates a file and it never appears.</p>
     *
     * <p><b>The listing itself is deliberately NOT dropped.</b> It used to be, and that is a visible bug
     * rather than a tidy one: a listing arrives over the network some frames later, and in between
     * {@code children()} answered "empty" for a folder that is still expanded. So every create and every
     * delete collapsed the whole folder and repopulated it two frames later — six rows vanished and came
     * back, which reads as the entire tree being rebuilt under you. Traced in the harness as
     * {@code model=15 -> 9 -> 14} across three consecutive frames.</p>
     *
     * <p>Serving the stale list for those two frames is not a compromise, it is what every file tree does:
     * the deleted row lingers for a frame instead of its five siblings disappearing with it. The refresh
     * is then a swap rather than a clear-and-refill, and nothing on screen moves except what changed.</p>
     *
     * <p>Directory-scoped rather than a full clear: a rename touches one folder, or two, and dropping the
     * whole tree would collapse every expanded node the user had opened.</p>
     */
    public void invalidate(CgPath directory) {
        if (directory == null) return;
        requested.remove(directory);
        // ASKED EAGERLY, because children() only re-requests when it has nothing — and it now has
        // something. Without this the stale list would be served indefinitely and the create would never
        // show up at all, which is the failure the old comment above was written about.
        request(directory);
        dirty = true;
    }

    /**
     * Re-fetches <b>every</b> directory this tree has listed — what "Reload from Disk" means.
     *
     * <p>Distinct from {@link #invalidate} on purpose, and the distinction is the whole bug it was
     * written for. A file <em>operation</em> knows which folder it touched, so it invalidates that one and
     * nothing else. A user pressing F5 knows the opposite: they are asking because something changed that
     * the tree has no way to know about, and they cannot tell it where.</p>
     *
     * <p>Reloading only the selected row's folder therefore did nothing at all whenever the change was
     * anywhere else — which is most of the time. It read as "F5 is broken", and then as "F5 needs two
     * presses", because a second press after clicking elsewhere would sometimes happen to land on the right
     * folder. Traced in the harness: every press fetched correctly and returned an identical listing.</p>
     *
     * <p>Bounded by what is already on screen rather than by the project: only directories that have been
     * listed are re-listed, so a collapsed tree costs one call and nothing walks the disk. Expanded state
     * and selection are untouched, because the listings are replaced rather than dropped.</p>
     */
    public void invalidateAll() {
        // COPIED before iterating: request() completes synchronously against an in-memory transport, and
        // its handler writes straight back into `children`.
        for (CgPath directory : new ArrayList<>(children.keySet())) {
            requested.remove(directory);
            request(directory);
        }
        dirty = true;
    }

    // ── Indexing the whole workspace (E17) ──────────────────────────────────────────────────────

    /**
     * How many directories one {@link #indexStep} may ask for.
     *
     * <p>Small on purpose. The crawl runs from a per-frame tick, and a listing is a round trip, so the
     * cost of being greedy is paid by the frame the user is looking at rather than by the index. A few
     * per frame reaches a few hundred directories within a second or two, which is faster than anyone
     * types the first letter of a file name.</p>
     */
    public static final int DEFAULT_INDEX_BUDGET = 4;

    /**
     * A ceiling on how much of the workspace is walked, so a pathological tree cannot crawl forever.
     *
     * <p>Chosen to be far above any project this is meant for and far below "the whole disk". Reaching it
     * is not an error — the index simply stops growing, and everything already found stays searchable.</p>
     */
    public static final int MAX_INDEXED_DIRECTORIES = 4_000;

    private int indexed;

    /**
     * Directories discovered but not yet asked for.
     *
     * <p>A queue fed by arriving listings, rather than a frontier recomputed from the whole cache on every
     * call. Recomputing was both O(everything known) per frame and <b>wrong</b>: "nothing to ask for right
     * now" is indistinguishable from "nothing left", so the crawl latched itself off the first time every
     * known directory happened to be in flight — and never resumed when a folder appeared later, whether
     * from a deeper listing or from someone creating one.</p>
     */
    private final java.util.ArrayDeque<CgPath> indexFrontier = new java.util.ArrayDeque<>();

    /** Enqueued at least once, so a directory is not walked twice. */
    private final Set<CgPath> indexSeen = new HashSet<>();

    /**
     * Listings asked for and not yet answered.
     *
     * <p>Tracked explicitly rather than inferred from "is it in {@code children} yet", which is the shape
     * this had and which is wrong for the case that matters: invalidation deliberately KEEPS the stale
     * listing while the replacement is fetched, so a re-requested directory is in {@code children} and in
     * flight at the same time. Inferring reported the crawl finished the instant everything outstanding
     * happened to be a refresh.</p>
     */
    private final Set<CgPath> inFlight = new HashSet<>();

    /**
     * Requests listings for directories not yet fetched — one step of a breadth-first crawl.
     *
     * <p><b>It warms the same cache the tree reads.</b> There is no second index to keep in step: a
     * directory the crawl listed is a directory the tree expands instantly, and a file the user creates
     * shows up in both because both are the one listing. That is the whole reason this lives here rather
     * than beside the thing that searches it.</p>
     *
     * <p>Listings arrive asynchronously, so a step only <em>asks</em>; what it asked for becomes visible on
     * some later frame and is crawled further on the frame after that. Call it until it returns false.</p>
     *
     * @return whether there is more to ask for
     */
    public boolean indexStep(int budget) {
        // The roots are the only thing not discovered by a listing, so they seed the queue -- and they
        // arrive asynchronously, which is why this is here rather than in loadProjects.
        for (CgPath root : roots) enqueueForIndex(root);

        int asked = 0;
        while (asked < budget && !indexFrontier.isEmpty() && indexed < MAX_INDEXED_DIRECTORIES) {
            CgPath next = indexFrontier.poll();
            if (children.containsKey(next)) continue;   // already listed, by the tree or by an earlier step
            request(next);
            asked++;
            indexed++;
        }
        // NOTHING QUEUED IS NOT THE SAME AS NOTHING LEFT: everything asked for may still be in flight, and
        // its children cannot be queued until the listing lands. Reporting "done" here walked exactly two
        // levels and stopped, which looks like a crawl that works on small projects.
        return !indexFrontier.isEmpty() || !inFlight.isEmpty();
    }

    /** Queues a directory for the crawl, once. */
    private void enqueueForIndex(CgPath directory) {
        if (children.containsKey(directory) || !indexSeen.add(directory)) return;
        indexFrontier.add(directory);
    }

    /**
     * Every file the index has reached — directories excluded.
     *
     * <p>What Go to File searches. Incomplete while the crawl is still running, which is honest: a picker
     * that showed nothing until a whole workspace had been walked would be unusable on the first press,
     * and every editor with this feature shows a growing list.</p>
     */
    public List<CgPath> knownFiles() {
        List<CgPath> files = new ArrayList<>();
        for (List<CgPath> listing : children.values()) {
            for (CgPath child : listing) {
                if (!directories.contains(child)) files.add(child);
            }
        }
        return files;
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
        if (known == null) {
            request(parent);
            return List.of();
        }
        return filter.isEmpty() ? known : filtered(known);
    }

    /**
     * Type-to-filter — IntelliJ's speed search, VS Code's list keyboard navigation in {@code filter} mode.
     *
     * <p>Matching is {@link SearchMatcher}'s, already ported from VS Code's {@code filters.ts}, so the
     * explorer ranks the same way the command palette does rather than inventing a second idea of what
     * "matches" means.</p>
     *
     * <p><b>It filters what has been listed, and says so.</b> A tree loaded a directory at a time cannot
     * answer "does anything under here match" without fetching the whole project — so a folder is kept if
     * its own name matches, or if something already listed beneath it does. Typing into a collapsed tree
     * therefore narrows what you can see rather than searching the workspace; searching the workspace is
     * Find in Files, which is a different feature with a server behind it.</p>
     */
    public WorkspaceTreeSource setFilter(String query) {
        String next = query == null ? "" : query.trim();
        if (next.equals(filter)) return this;
        filter = next;
        parsedFilter = next.isEmpty() ? null : SearchQuery.of(next);
        dirty = true;
        return this;
    }

    public String filter() {
        return filter;
    }

    private String filter = "";

    @Nullable
    private SearchQuery parsedFilter;

    private List<CgPath> filtered(List<CgPath> candidates) {
        List<CgPath> kept = new ArrayList<>();
        for (CgPath child : candidates) {
            if (matches(child)) kept.add(child);
        }
        return kept;
    }

    /** A path survives the filter if its own name matches, or anything listed beneath it does. */
    private boolean matches(CgPath path) {
        if (parsedFilter == null) return true;
        if (SearchMatcher.match(parsedFilter, path.name(), 0) != null) return true;
        List<CgPath> listed = children.get(path);
        if (listed == null) return false;
        for (CgPath child : listed) {
            if (matches(child)) return true;
        }
        return false;
    }

    @Override
    public boolean hasChildren(CgPath item) {
        return directories.contains(item);
    }

    private void request(CgPath directory) {
        if (!requested.add(directory)) return;
        inFlight.add(directory);
        client.list(directory, entries -> {
            inFlight.remove(directory);
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
            // Whatever this listing revealed becomes the next thing to walk. Feeding the queue HERE is what
            // makes the crawl resume for a folder that appears later -- a deeper listing, or one somebody
            // just created -- rather than depending on a step happening to look in the right place.
            for (CgPath child : paths) {
                if (directories.contains(child)) enqueueForIndex(child);
            }
            dirty = true;
        }, failed -> {
            inFlight.remove(directory);
            // Retryable rather than latched -- the listing may have failed because the directory was
            // being written to.
            requested.remove(directory);
            if (failed.error() != CgFileError.FILE_NOT_FOUND) children.put(directory, List.of());
        });
    }
}
