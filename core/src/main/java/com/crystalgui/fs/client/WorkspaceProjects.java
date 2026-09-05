package com.crystalgui.fs.client;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;

/**
 * <b>What the workspace holds</b> - the projects, their roots, and what is under each directory.
 *
 * <p>The listing model, separate from any view of it, so a panel is not the only way to reach it. Read
 * today by the workbench's crawl, the session's expansion restore, the settings, Go to File and the
 * editor - five consumers outside the file tree, which is why it is a service rather than a widget's
 * field. Get one from {@code WorkbenchContext.projectListing()}.</p>
 *
 * <pre>{@code
 * projects.onDidChangeProjects().connect(() -> redrawRoots());
 * projects.onDidLoadListing().connect(dir -> redrawChildrenOf(dir));
 * projects.loadProjects(() -> {}, () -> {});   // asks the server; safe to call again
 * for (CgPath root : projects.roots()) { ... }
 * }</pre>
 *
 * <h3>Everything arrives; nothing is ready at construction</h3>
 *
 * <p>Roots come from a project listing that cannot be asked for until a session has opened, and a
 * directory's children come from a listing of their own. So render your empty state, subscribe, and be
 * told. Asking and then reading immediately is the one thing that does not work - a call made too early
 * is discarded with no error at all, which is why {@link #loadProjects} takes an {@code onRefused} and
 * is safe to retry.</p>
 *
 * <h3>Signals are methods here</h3>
 *
 * <p>The engine's idiom is a {@code public final Signal} field and an interface cannot carry one, so
 * these are the one place in this stack where a signal is reached through a call. The trade buys the
 * interface itself: a consumer written against this names no widget.</p>
 */
public interface WorkspaceProjects {

    /**
     * Asks for the project list, which is what gives everything else its roots.
     *
     * @param onLoaded  the listing landed
     * @param onRefused the server would not answer — a caller that latched may now retry
     */
    void loadProjects(Runnable onLoaded, Runnable onRefused);

    /** The project roots, empty until a listing lands. */
    List<CgPath> roots();

    /** What has been listed under {@code parent}, empty when nothing has. */
    List<CgPath> children(CgPath parent);

    /** Whether {@code directory}'s listing has arrived. */
    boolean isListed(CgPath directory);

    /** Asks for {@code directory}'s listing if it has not been asked for. */
    void ensureListed(CgPath directory);

    /** Whether anything is known to be under {@code item} — for a disclosure arrow. */
    boolean hasChildren(CgPath item);

    /** Forgets {@code directory}'s listing, so the next reader re-asks. */
    void invalidate(CgPath directory);

    /**
     * Redraw the rows; the listing has not changed.
     *
     * <p>What a row shows can move without the directory moving — a file's declared type name arrives
     * after the row was built. Use this rather than {@link #invalidate}, which goes back to the server.</p>
     */
    void announceRowsChanged();

    /** Forgets every listing — what a reconnect does, because none of them describes this server. */
    void invalidateAll();

    boolean isDirectory(CgPath path);

    /** Every file the crawl has seen so far. */
    List<CgPath> knownFiles();

    /** Bumped whenever anything derived from a listing changes; a cheap staleness check. */
    int indexRevision();

    /**
     * Crawls a little further, within {@code budget}.
     *
     * @return whether there is more to do
     */
    boolean indexStep(int budget);

    /** A project's declared source roots, from the listing. */
    List<String> sourceRootsOf(String projectId);

    /** What to call {@code projectRoot} on screen. */
    String displayNameOf(CgPath projectRoot);

    /** The last failure, for a status line. Null when nothing has gone wrong. */
    @Nullable
    String failure();

    /** A directory's listing arrived — per listing, which is the only moment an answer can have changed. */
    Signal.Value<CgPath> onDidLoadListing();

    /** The roots changed: a listing landed, or a re-listing replaced the ones before it. */
    Signal.Action onDidChangeProjects();
}
