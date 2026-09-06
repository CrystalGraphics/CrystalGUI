package com.crystalgui.desktop.app;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;

/**
 * The arguments a launch receives — what {@link ApplicationKind#launch}'s factory is handed.
 *
 * <p>Everything an application needs that is not already in its own manifest: which compositor to open
 * onto, the file client to work against, somewhere private to write, and whatever was asked for at the
 * moment of launching. You do not build one; {@link ApplicationRegistry} does, and passes it in.</p>
 *
 * <ul>
 *   <li>{@code kind} — the manifest being launched, so a factory can read its own id and title.</li>
 *   <li>{@code desktop} — the shell to open windows onto.</li>
 *   <li>{@code workspace} — the file client, or {@code null} when there is no connection. A manifest
 *       that cannot work without one declares {@link ApplicationKind#standalone()}'s opposite and is
 *       refused before the factory runs.</li>
 *   <li>{@code storage} — see below.</li>
 *   <li>{@code workspaces} — the parent of every workspace's own store. Scope it by a workspace
 *       identity to get where that workspace's session, backups and history live; see below.</li>
 *   <li>{@code cache} — a directory for derived output, or {@code null} for none. Scoped to you the
 *       same way {@code storage} is, and <b>disposable</b>: everything under it must be rebuildable,
 *       because the whole tree is deleted without warning.</li>
 *   <li>{@code open} — files to show once it is up; {@link #first()} is the usual read.</li>
 * </ul>
 *
 * <h3>The storage is already scoped to you</h3>
 *
 * <p>{@link #storage()} is <em>this application's own corner</em> of the desktop's config directory,
 * not the directory itself — the registry scopes it by the kind's id before your factory runs. So two
 * applications on one desktop keep separate sessions and separate preferences without either having to
 * know the other exists, and neither can read or overwrite the other's record by accident.</p>
 *
 * <p>What it is <em>not</em> scoped to is a workspace. Preferences are the application's and are the
 * same on every server; a session, a backup and local history belong to one workspace.</p>
 *
 * <h3>…and {@code workspaces} is where those go</h3>
 *
 * <p>Which workspace this is cannot be known at launch — it arrives with the server's greeting — so the
 * registry hands over the <em>parent</em> and the application scopes it when the answer exists:</p>
 *
 * <pre>{@code
 * // once the workspace has greeted and can be named
 * ConfigStorage mine = context.workspaces().scoped(workspaceIdentity);
 * workspace.setStorage(mine);          // backups and local history
 * session.useStorage(mine);            // the arrangement
 * }</pre>
 *
 * <p>Two applications over one workspace share that directory and write differently-named records in
 * it, which is right: it is one workspace's state, not one product's.</p>
 */
public record LaunchContext(ApplicationKind kind,
                            Desktop desktop,
                            @Nullable Workspace workspace,
                            ConfigStorage storage,
                            ConfigStorage workspaces,
                            @Nullable Path cache,
                            List<Resource> open) {

    public LaunchContext {
        open = open == null ? List.of() : List.copyOf(open);
    }

    /** A launch with nothing asked of it. */
    public static LaunchContext of(ApplicationKind kind, Desktop desktop,
                                   @Nullable Workspace workspace, ConfigStorage storage,
                                   ConfigStorage workspaces, @Nullable Path cache) {
        return new LaunchContext(kind, desktop, workspace, storage, workspaces, cache, List.of());
    }

    /** The one resource this launch was asked to open, or null. */
    @Nullable
    public Resource first() {
        return open.isEmpty() ? null : open.get(0);
    }
}
