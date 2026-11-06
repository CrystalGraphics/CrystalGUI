package com.crystalgui.core.storage;

import com.crystalgraphics.platform.service.CgStorageLayout;

import java.nio.file.Path;

/**
 * Where CrystalGUI keeps what it writes: {@code <base>/crystalgui/}, on CrystalGraphics' {@link CgStorageLayout}, plus
 * the folders it adds under it.
 *
 * <pre>{@code
 * Path config = CrystalGuiStorage.LAYOUT.configIn(gameDir);    // …/crystalgui/workspace-config   durable
 * Path cache  = CrystalGuiStorage.LAYOUT.cacheIn(gameDir);     // …/crystalgui/cache              disposable
 * Path world  = CrystalGuiStorage.projectsIn(worldDir);        // …/crystalgui/projects           the user's files
 *
 * // under workspace-config, each placed by the desktop:
 * //   apps/<application>/      an application's preferences
 * //   projects/<workspace>/    one workspace's session and backups (BACKUPS, HISTORY inside)
 * //   extensions/<extension>/  what a user made with an extension, shared by every application
 * }</pre>
 *
 * <ul>
 *   <li>{@code projects/} is the user's own files; private state never goes there.</li>
 *   <li>A dedicated server, a world and a client share the tree; a server only ever asks for {@code projects/}.</li>
 * </ul>
 */
public final class CrystalGuiStorage {

    /** {@code crystalgui/} under any base. */
    public static final CgStorageLayout LAYOUT = CgStorageLayout.of("crystalgui");

    /** The workspaces themselves, the user's own files. */
    public static final String PROJECTS = "projects";

    /** One application's own corner, under config or cache. */
    public static final String APPS = "apps";

    /** What a user made with one extension, the same in every application and workspace, under config. */
    public static final String EXTENSIONS = "extensions";

    /** Unsaved work, under one workspace's store. Its own store, never shared with {@link #HISTORY}. */
    public static final String BACKUPS = "backups";

    /** What each save held, under one workspace's store. Its own store, see {@link #BACKUPS}. */
    public static final String HISTORY = "history";

    private CrystalGuiStorage() {
    }

    /** {@code <base>/crystalgui/projects}. */
    public static Path projectsIn(Path base) {
        return LAYOUT.rootIn(base).resolve(PROJECTS);
    }
}
