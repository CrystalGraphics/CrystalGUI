package com.crystalgui.ui.elements.workbench;

import com.crystalgui.fs.CgFileEntry;
import com.crystalgui.fs.CgFileError;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.SourceRoots;
import com.crystalgui.fs.ProjectInfo;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.core.search.SearchMatch;
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

    /**
     * A directory's listing arrived — including an empty one from a refusal.
     *
     * <h3>What it replaced</h3>
     *
     * <p>{@code WorkbenchSession.tick()}, called every frame from {@code CrystalEditor}'s ticker, purely
     * to re-attempt an expansion that could not happen yet: a folder cannot be expanded before the
     * listing revealing it lands, and listings arrive over several frames. So the restore asked "has it
     * arrived?" sixty times a second and gave up after a fixed number of tries.</p>
     *
     * <p>Now the arrival says so. The retry runs once per listing instead of once per frame, which is
     * both fewer attempts and strictly better ones — every attempt happens at a moment when the answer
     * may actually have changed.</p>
     *
     * <p><b>A refused listing is announced too.</b> It is still an answer about that directory, and the
     * case that most needs the restore to move on is the one where the folder is simply gone.</p>
     */
    public final Signal.Value<CgPath> onDidLoadListing = new Signal.Value<>();

    /**
     * Bumped whenever anything the PROJECT INDEX derives from changes — a directory listing, or a
     * project's declared source roots.
     *
     * <p>An {@code int} rather than a signal because the one consumer is a per-frame pull, and it needs
     * "has anything changed since I last looked" rather than "what changed". Reading it is a field load;
     * the alternative it replaced was rebuilding a list of every file in the workspace, every frame,
     * to find out that nothing had.</p>
     *
     * <p>It covers both inputs on purpose. The two arrive on separate round trips, so a counter bumped
     * only by listings goes stale for a project whose roots land after its files — and the index would
     * then derive every one of that project's names against the fallback convention, permanently.</p>
     */
    public int indexRevision() {
        return indexRevision;
    }

    private int indexRevision;

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

    /** @deprecated a caller that cannot hear a refusal cannot retry. @see #loadProjects(Runnable, Runnable) */
    @Deprecated
    public void loadProjects(Runnable onLoaded) {
        loadProjects(onLoaded, () -> { });
    }

    /**
     * Asks for the project list, which is what gives the tree its roots.
     *
     * <p>Called by the host rather than on construction, because a client's window id is not valid until
     * its session has opened — and the server discards a packet addressed to another window, so a call made
     * too early is thrown away with no error at all.</p>
     *
     * <p><b>{@code onRefused} exists because that last sentence is a description of a bug, not of a
     * design.</b> A caller latches so it asks once; if the one ask lands before the workspace is ready,
     * the tree is empty for the life of the screen and nothing anywhere says why. Reporting the refusal
     * is what lets the latch be released and the ask retried. @see ProjectFileTree#loadProjects</p>
     */
    public void loadProjects(Runnable onLoaded, Runnable onRefused) {
        // SEEDED HERE, and for the same reason the comment above gives. What this actor may do is a
        // question about the projects, so the first moment it can be asked is the first moment they can
        // be -- and asking it here rather than at construction means it inherits that timing for free
        // instead of needing its own rule. The server pushes every change afterwards, so this is a seed
        // and never a poll: calling it per menu open would be the round trip the cache exists to avoid.
        // @see WorkspaceClient#mayWrite
        client.refreshCapabilities();
        // SAID OUT LOUD, both ways round. The comment above notes that a call made too early is "thrown
        // away with no error at all" -- so the empty tree it produces is indistinguishable from a tree
        // that was never asked, from a server with no projects, and from an answer still in flight. Four
        // states, one appearance, and a report of it can only ever be "it was empty". Two lines make the
        // log say which.
        CrystalGuiCore.LOGGER.info("[cgui-fs] asking for the project list");
        client.projects(infos -> {
            CrystalGuiCore.LOGGER.info("[cgui-fs] project list: {} project(s)", infos.size());
            roots.clear();
            for (ProjectInfo info : infos) {
                CgPath root = info.root();
                roots.add(root);
                directories.add(root);
                projectNames.put(info.id(), info.displayName());
                // RETAINED, because the index derives a qualified name from a path and cannot do it
                // without knowing where the name starts. The listing is the only place these arrive.
                projectSourceRoots.put(info.id(), info.sourceRoots());
            }
            indexRevision++;
            dirty = true;
            onLoaded.run();
        }, error -> {
            failure = "projects failed: " + error.code();
            dirty = true;
            CrystalGuiCore.LOGGER.warn("[cgui-fs] project listing refused: {} — the tree will retry",
                    error.code());
            onRefused.run();
        });
    }

    /** Project id to its declared source roots, filled by the project listing. @see #sourceRootsOf */
    private final java.util.Map<String, java.util.List<String>> projectSourceRoots =
            new java.util.HashMap<>();

    /**
     * Where {@code projectId}'s source starts, or the convention when it has not been listed yet.
     *
     * <p>The fallback matters more than it looks: the crawl and the project listing are separate round
     * trips, so files can be known before their project's roots are. Answering "no roots" in that window
     * would index those files as declaring nothing, and nothing re-derives them afterwards — the index
     * would be permanently short of whatever arrived early.</p>
     */
    /**
     * What {@code path} is in its own project's layout — module, source root, package or folder.
     *
     * <h3>Asked here rather than assembled by the caller, and that is about what comes next</h3>
     *
     * <p>A renderer could call {@link #sourceRootsOf} and {@link SourceRoots#roleOf} itself; it is two
     * lines. But the ROLE is a fact about a project, and this is the only class that holds projects.
     * When a project can depend on another as a library, the answer stops being derivable from the path
     * and the roots alone — the same directory is a module in the project you opened and a library in the
     * one that depends on it, and nothing in a {@link CgPath} says which. Then this method learns about
     * project KIND and no caller changes.</p>
     *
     * <p>Which is also the argument against a second {@code Map<String, Kind>} beside
     * {@link #projectSourceRoots} when that day comes: two parallel maps keyed by project id drift, and a
     * record per project does not. @see ProjectFileTree#NODEROLE_PREFIX</p>
     */
    public SourceRoots.Role roleOf(CgPath path) {
        if (path == null) return SourceRoots.Role.FOLDER;
        return SourceRoots.roleOf(path.path(), sourceRootsOf(path.project()));
    }

    public java.util.List<String> sourceRootsOf(String projectId) {
        java.util.List<String> declared = projectSourceRoots.get(projectId);
        return declared == null ? com.crystalgui.fs.SourceRoots.CONVENTION : declared;
    }

    /**
     * The one project's display name, or null when there is not exactly one.
     *
     * <p>For naming something that has no project of its own — a library class opened read-only, which
     * belongs to a jar rather than to the workspace. A window's caption still wants to say where you
     * are, and with a single project open that is unambiguous. With several it is not, so it says
     * nothing rather than guessing which one you meant.</p>
     */
    @Nullable
    public String soleProjectName() {
        return projectNames.size() == 1 ? projectNames.values().iterator().next() : null;
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
        // HIGHLIGHT KEEPS EVERY ROW. The query still applies -- isMatch and descendantMatches are what
        // the renderer reads -- it simply does not decide what exists.
        List<CgPath> visible = filter.isEmpty() || findMode == FindMode.HIGHLIGHT
                ? known : filtered(known);
        if (compactFolders) visible = compacted(parent, visible);
        // AFTER compaction, and that ordering is the whole of why a chain does not swallow the row being
        // created: a directory with one child plus a placeholder still has one *real* child, so
        // compressing first keeps the chain honest, and prepending after keeps the placeholder visible.
        if (pendingNew != null && pendingNew.parent() != null && pendingNew.parent().equals(parent)) {
            List<CgPath> withPending = new ArrayList<>(visible.size() + 1);
            withPending.add(pendingNew);
            withPending.addAll(visible);
            return withPending;
        }
        return visible;
    }

    // -- The row being created ----------------------------------------------------------------------

    /**
     * The placeholder for an entry that does not exist yet -- VS Code's {@code NewExplorerItem}.
     *
     * <p>A row before a file: the tree shows where the thing will land, inside the folder that was
     * chosen, while its name is still being typed. The alternative is a dialog that hides the folder it
     * is asking about.</p>
     */
    @Nullable
    private CgPath pendingNew;

    /** @see #beginPendingNew for why a control character. */
    private static final String PENDING_NAME = "\u0001new";

    /**
     * Inserts a placeholder as the first child of {@code parent} and returns it.
     *
     * <p><b>First, not sorted into place.</b> An entry with no name has no position -- sorting it would
     * put it at the top anyway and then make it jump on the first keystroke, which is movement under the
     * cursor for no information. VS Code puts it at the top and leaves it there.</p>
     *
     * <p>The name is {@code U+0001}, which no filesystem permits in a real one -- so the placeholder can
     * never collide with anything the workspace holds, and a path that somehow reached the server would be
     * refused there rather than creating something. {@code U+0000} is the obvious choice and
     * {@link CgPath} rightly refuses it outright, which is how this was found.</p>
     */
    public CgPath beginPendingNew(CgPath parent, boolean directory) {
        endPendingNew();
        CgPath placeholder = parent.resolve(PENDING_NAME);
        pendingNew = placeholder;
        if (directory) directories.add(placeholder);
        dirty = true;
        return placeholder;
    }

    /** Removes the placeholder, whether the edit committed or was abandoned. */
    public void endPendingNew() {
        if (pendingNew == null) return;
        directories.remove(pendingNew);
        pendingNew = null;
        dirty = true;
    }

    /** Whether {@code path} is the row being created rather than a real entry. */
    public boolean isPendingNew(CgPath path) {
        return pendingNew != null && pendingNew.equals(path);
    }

    /** The parent the placeholder sits under, or null when there is none. */
    @Nullable
    public CgPath pendingNewParent() {
        return pendingNew == null ? null : pendingNew.parent();
    }

    /**
     * What has been listed under {@code directory} -- unfiltered, uncompacted, no request issued.
     *
     * <p>For asking a question <em>about</em> the tree rather than rendering it: name validation needs the
     * real siblings, and {@link #children} would answer with whatever the filter and the compaction left,
     * so a name would be judged free because the row holding it is currently hidden.</p>
     */
    public List<CgPath> listedChildren(CgPath directory) {
        List<CgPath> known = children.get(directory);
        return known == null ? List.of() : known;
    }

    // ── Compact folders ─────────────────────────────────────────────────────────────────────────

    /**
     * Whether a single-child directory chain renders as one row — VS Code's
     * {@code explorer.compactFolders}, IntelliJ's <i>Compact Empty Middle Packages</i>.
     *
     * <p><b>Off for now</b>, and that is a gap rather than a decision. It is on in VS Code and the reason
     * is arithmetic — {@code src/main/java/com/crystalgui} is five rows and one useful one — but there is
     * no setting to turn it back on with, so shipping it on means a reader who wants the packages spelled
     * out cannot have them. It goes back to {@code true} the day it is settable.</p>
     */
    private boolean compactFolders = false;

    /** The displayed label for a compacted row — {@code "main/java/com/crystalgui"}. */
    private final Map<CgPath, String> compactLabels = new HashMap<>();

    /** Every path swallowed by a chain → the row that now stands for it. @see #visibleRowFor */
    private final Map<CgPath, CgPath> chainOwner = new HashMap<>();

    public WorkspaceTreeSource setCompactFolders(boolean value) {
        if (this.compactFolders == value) return this;
        this.compactFolders = value;
        compactLabels.clear();
        chainOwner.clear();
        dirty = true;
        return this;
    }

    public boolean isCompactFolders() {
        return compactFolders;
    }

    /**
     * What to draw for {@code path} — a project's name, its own name, or the whole chain it stands for.
     *
     * <p>Asked by the renderer rather than computed there, because the chain is a fact the source
     * discovered while listing and the view has no way to rediscover it: by the time a row exists, the
     * intermediate directories are not in the tree at all.</p>
     */
    public String rowLabel(CgPath path) {
        // The placeholder has no name yet, and showing its sentinel would flash a control character on
        // the frame between the row appearing and the editor taking over.
        if (isPendingNew(path)) return "";
        if (path.isProjectRoot()) return displayNameOf(path);
        String label = compactLabels.get(path);
        return label != null ? label : path.name();
    }

    /**
     * The row that shows {@code path}, which may be a chain end further down.
     *
     * <p>Needed by anything that maps a <em>path</em> back to the tree — reveal, auto-reveal, a problem
     * row jumping to a file. Without it, revealing {@code src/main/java/Foo} tries to expand
     * {@code src/main}, which is no longer a row, and the reveal silently does nothing.</p>
     */
    public CgPath visibleRowFor(CgPath path) {
        CgPath owner = chainOwner.get(path);
        return owner != null ? owner : path;
    }

    /**
     * Replaces each directory child with the end of its single-child chain.
     *
     * <h3>The rule is VS Code's, and the root carve-out is the whole of it</h3>
     *
     * <p>{@code ExplorerCompressionDelegate.isIncompressible} refuses to merge a node into its parent when
     * the parent is a <b>root</b> — so a top-level folder always keeps its own row and absorbs downward
     * from there. That is why the answer is {@code src/main/java} and never {@code project/src/main/java}:
     * the roots are the one thing a user picked, and hiding one inside a path is hiding the project.</p>
     *
     * <p>A chain stops at the first directory whose listing has not arrived. That is not a compromise —
     * compressing through an unlisted directory would mean asserting it has exactly one child before
     * anything has said so, and the row would then have to un-compress itself when the listing landed.
     * Stopping means the row simply grows once, on the refresh the listing already triggers.</p>
     */
    private List<CgPath> compacted(CgPath parent, List<CgPath> visible) {
        List<CgPath> out = new ArrayList<>(visible.size());
        for (CgPath child : visible) {
            if (!directories.contains(child)) {
                out.add(child);
                continue;
            }
            CgPath end = child;
            StringBuilder label = new StringBuilder(child.name());
            List<CgPath> swallowed = new ArrayList<>(2);
            while (true) {
                List<CgPath> inner = children.get(end);
                // A filter must not compress: the one child that survived filtering is not the one child
                // the directory has, so the row would claim a structure the project does not have.
                if (inner == null || inner.size() != 1 || !filter.isEmpty()) break;
                CgPath only = inner.get(0);
                if (!directories.contains(only)) break;
                // AND NEVER THROUGH A ROLE BOUNDARY. A source root is not a package, so swallowing it into
                // the chain below hides where source starts -- `src/main/java/com/example` rendered as one
                // row called `java/com/example`, wearing the PACKAGE icon of its deepest segment, and the
                // source root simply gone from the tree. IntelliJ compacts middle PACKAGES and stops at
                // the root for exactly this reason. @see SourceRoots#roleOf
                if (roleOf(end) != SourceRoots.Role.PACKAGE && roleOf(end) != SourceRoots.Role.FOLDER) break;
                swallowed.add(end);
                end = only;
                label.append('/').append(only.name());
            }
            if (swallowed.isEmpty()) {
                // No longer a chain -- a sibling appeared, or the filter changed. Left behind, the stale
                // label would render a path that is no longer true.
                compactLabels.remove(child);
                chainOwner.remove(child);
            } else {
                compactLabels.put(end, label.toString());
                for (CgPath inner : swallowed) chainOwner.put(inner, end);
            }
            out.add(end);
        }
        return out;
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
        return setFilter(query == null ? null : SearchQuery.of(query));
    }

    /**
     * As above, with the query's <b>options</b> — Match Case, Words, Regex.
     *
     * <p>The overload that matters: given only the text, this built its own {@code SearchQuery} and every
     * option the user had set was silently dropped on the way in.</p>
     */
    public WorkspaceTreeSource setFilter(@Nullable SearchQuery query) {
        SearchQuery next = query == null || query.isEmpty() ? null : query;
        String text = next == null ? "" : next.text();
        if (text.equals(filter) && sameOptions(next, parsedFilter)) return this;
        filter = text;
        parsedFilter = next;
        dirty = true;
        return this;
    }

    private static boolean sameOptions(@Nullable SearchQuery a, @Nullable SearchQuery b) {
        if (a == null || b == null) return a == b;
        return a.options().equals(b.options());
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

    /**
     * What typing does — VS Code's {@code ExplorerFindProvider} has both, and so does this.
     *
     * <p>They answer different questions. <b>Filter</b> asks "show me only these", which is what you want
     * when hunting through a large tree; <b>highlight</b> asks "where are these", which is what you want
     * when the shape of the tree is part of the answer — and it is IntelliJ's speed search.</p>
     */
    public enum FindMode {
        /** Non-matching rows are removed. */
        FILTER,
        /** Every row stays; matches are marked and folders count what is under them. */
        HIGHLIGHT
    }

    private FindMode findMode = FindMode.HIGHLIGHT;

    /**
     * Sets the mode.
     *
     * <p><b>Highlight is the default</b>, and the reason is a defect this file already described: a filter
     * with nothing saying it is on is a tree that has mysteriously lost half its files. Highlight cannot
     * do that — the tree is still the tree — so it is the safe one to have on when the user has not
     * chosen.</p>
     */
    public WorkspaceTreeSource setFindMode(FindMode mode) {
        FindMode next = mode == null ? FindMode.HIGHLIGHT : mode;
        if (next == findMode) return this;
        findMode = next;
        dirty = true;
        return this;
    }

    public FindMode findMode() {
        return findMode;
    }

    /** Whether {@code path}'s own name matches what is being searched for. */
    public boolean isMatch(CgPath path) {
        return parsedFilter != null && SearchMatcher.match(parsedFilter, path.name(), 0) != null;
    }

    /**
     * <b>Where</b> in {@code path}'s name the query matched — empty when it did not.
     *
     * <p>{@link SearchMatch} has carried these ranges all along; nothing had asked for them. Both
     * references highlight the matched <em>characters</em> rather than the row: IntelliJ bands them in
     * amber, VS Code recolours them. A whole-row mark says "something here matched" and leaves the eye
     * to find what.</p>
     */
    public List<SearchMatch.Range> matchRanges(CgPath path) {
        if (parsedFilter == null) return List.of();
        SearchMatch match = SearchMatcher.match(parsedFilter, path.name(), 0);
        return match == null ? List.of() : match.ranges();
    }

    /**
     * How many <b>listed</b> descendants of {@code directory} match, itself excluded.
     *
     * <p>Listed, not all: a lazily-loaded tree cannot answer for a folder it has never opened without
     * fetching the project, and claiming zero there would be a wrong answer rather than a partial one.
     * VS Code's {@code ExplorerFindHighlightTree} counts the same way and for the same reason.</p>
     */
    public int descendantMatches(CgPath directory) {
        List<CgPath> listed = children.get(directory);
        if (listed == null || parsedFilter == null) return 0;
        int count = 0;
        for (CgPath child : listed) {
            if (isMatch(child)) count++;
            count += descendantMatches(child);
        }
        return count;
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
            indexRevision++;
            // Whatever this listing revealed becomes the next thing to walk. Feeding the queue HERE is what
            // makes the crawl resume for a folder that appears later -- a deeper listing, or one somebody
            // just created -- rather than depending on a step happening to look in the right place.
            for (CgPath child : paths) {
                if (directories.contains(child)) enqueueForIndex(child);
            }
            dirty = true;
            onDidLoadListing.emit(directory);
        }, failed -> {
            inFlight.remove(directory);
            // Retryable rather than latched -- the listing may have failed because the directory was
            // being written to.
            requested.remove(directory);
            if (failed.error() != CgFileError.FILE_NOT_FOUND) {
                children.put(directory, List.of());
                indexRevision++;
                // Announced too. A refused listing is still an ANSWER about this directory, and a
                // restore waiting on it has to learn that it arrived and was empty -- otherwise the one
                // case that never resolves is the one where the folder is gone, which is exactly the
                // case the retry budget exists for.
                onDidLoadListing.emit(directory);
            }
        });
    }
}
