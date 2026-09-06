package com.crystalgui.core.storage;

import java.nio.file.Path;

/**
 * <b>Where everything CrystalGUI writes goes</b> — the {@code crystalgui/} tree, stated once.
 *
 * <p>A host answers one question, <em>which directory is this installation</em>, and asks this for the
 * rest. Nothing else may spell these names.</p>
 *
 * <pre>{@code
 * // a client, from the game directory
 * Path root   = StorageLayout.rootIn(gameDir);          // <gameDir>/crystalgui
 * Path config = StorageLayout.configIn(gameDir);        // …/workspace-config   durable
 * Path cache  = StorageLayout.cacheIn(gameDir);         // …/cache              disposable
 *
 * // a server, from the world directory in single-player or the server directory on a dedicated one
 * Path projects = StorageLayout.projectsIn(worldDir);   // …/crystalgui/projects
 * }</pre>
 *
 * <h3>The same tree at every root</h3>
 *
 * <p>An installation, a world and a dedicated server each get the identical shape; what differs is only
 * which children happen to be non-empty. A dedicated server grows a {@code projects/} and nothing else,
 * not because anything branches on it but because nobody asks it for the other two.</p>
 *
 * <h3>Two rules a caller has to know</h3>
 *
 * <ul>
 *   <li><b>{@link #cacheIn} is disposable.</b> Deleting that whole tree at any moment must lose
 *       nothing. Anything that would be missed belongs under {@link #configIn}.</li>
 *   <li><b>{@link #projectsIn} is the user's own files.</b> Never write private state into it — a
 *       session record there becomes part of a project somebody ships.</li>
 * </ul>
 *
 * <p>This class names no host type and holds no state, which is what lets a dedicated server use it:
 * the base is a parameter precisely so that reaching it never means reaching a client.</p>
 */
public final class StorageLayout {

    /** The one directory CrystalGUI writes into, relative to an installation. */
    public static final String ROOT = "crystalgui";

    /** Durable: preferences, the desktop arrangement, sessions, backups, local history. */
    public static final String CONFIG = "workspace-config";

    /** Derived: compiled scripts, indexes, thumbnails. Deletable at any moment. */
    public static final String CACHE = "cache";

    /** The workspaces themselves — the user's own files. */
    public static final String PROJECTS = "projects";

    /** One application's own corner, under {@link #CONFIG} or {@link #CACHE}. */
    public static final String APPS = "apps";

    /**
     * Unsaved work, under one workspace's directory.
     *
     * <p>Its own store, never shared with {@link #HISTORY}: each owns everything in the store it is
     * given, so sharing one means discarding backups also deletes the history beside them.</p>
     */
    public static final String BACKUPS = "backups";

    /** What each save held, under one workspace's directory. Its own store — see {@link #BACKUPS}. */
    public static final String HISTORY = "history";

    private StorageLayout() {
    }

    /** {@code <installation>/crystalgui}. */
    public static Path rootIn(Path installation) {
        return installation.resolve(ROOT);
    }

    /** {@code <installation>/crystalgui/workspace-config}. */
    public static Path configIn(Path installation) {
        return rootIn(installation).resolve(CONFIG);
    }

    /** {@code <installation>/crystalgui/cache}. */
    public static Path cacheIn(Path installation) {
        return rootIn(installation).resolve(CACHE);
    }

    /** {@code <installation>/crystalgui/projects}. */
    public static Path projectsIn(Path installation) {
        return rootIn(installation).resolve(PROJECTS);
    }
}
