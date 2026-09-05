package com.crystalgui.desktop.app;

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
 *   <li>{@code open} — files to show once it is up; {@link #first()} is the usual read.</li>
 * </ul>
 *
 * <h3>The storage is already scoped to you</h3>
 *
 * <p>{@link #storage()} is <em>this application's own corner</em> of the desktop's config directory,
 * not the directory itself — the registry scopes it by the kind's id before your factory runs. So two
 * applications on one desktop keep separate sessions and separate preferences without either having to
 * know the other exists, and neither can read or overwrite the other's record by accident.</p>
 */
public record LaunchContext(ApplicationKind kind,
                            Desktop desktop,
                            @Nullable Workspace workspace,
                            ConfigStorage storage,
                            List<Resource> open) {

    public LaunchContext {
        open = open == null ? List.of() : List.copyOf(open);
    }

    /** A launch with nothing asked of it. */
    public static LaunchContext of(ApplicationKind kind, Desktop desktop,
                                   @Nullable Workspace workspace, ConfigStorage storage) {
        return new LaunchContext(kind, desktop, workspace, storage, List.of());
    }

    /** The one resource this launch was asked to open, or null. */
    @Nullable
    public Resource first() {
        return open.isEmpty() ? null : open.get(0);
    }
}
