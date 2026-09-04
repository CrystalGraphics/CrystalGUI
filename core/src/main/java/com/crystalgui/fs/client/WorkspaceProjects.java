package com.crystalgui.fs.client;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;

/**
 * <b>What the workspace holds, listing by listing</b> — the projects, their roots, and what is under them.
 *
 * <p>The model half of the explorer, named so that everything else can stop reaching through the
 * explorer to get at it. It is read today by the workbench (the crawl, the roots, the captions), by the
 * session (the expansion retry), by the settings, by Go to File and by the editor — five consumers
 * outside the tree that owns it, which is what makes it a service rather than a widget's field.</p>
 *
 * <h3>Everything arrives</h3>
 *
 * <p>Nothing here answers on construction. Roots come from the project listing, which cannot be asked
 * for until a session has opened; a directory's children come from a listing of their own. So a
 * consumer renders its empty state, subscribes, and is told — {@link #onDidChangeProjects()} when the
 * set of roots moves and {@link #onDidLoadListing()} per directory. Polling for them is what this
 * interface exists to stop: it is one round trip per answer, and a caller that asks before the wire is
 * ready gets nothing back and no error either.</p>
 *
 * <h3>Signals as accessors, which is the one thing an interface costs here</h3>
 *
 * <p>The engine's idiom is a {@code public final Signal} field, and an interface cannot carry one. So
 * these are methods, and they are the only place in this stack where a signal is reached through a
 * call. It is worth the trade for the same reason the interface exists at all: a consumer written
 * against it does not name the widget that happens to implement it.</p>
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
