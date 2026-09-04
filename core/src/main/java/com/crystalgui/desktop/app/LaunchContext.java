package com.crystalgui.desktop.app;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;

/**
 * <b>What a launch is handed</b> — the {@code Exec} line's arguments, and the intent's extras.
 *
 * <p>Everything an application needs that is not in its own manifest: the compositor to open onto, the
 * file client, somewhere private to write, and whatever was asked for at the moment of launching (a
 * file to open).</p>
 *
 * <h3>The storage is already scoped</h3>
 *
 * <p>{@link #storage()} is the application's own corner of the desktop's config directory, not the
 * directory itself — {@link ApplicationRegistry} scopes it by the kind's id before the factory runs
 * (D20). Two applications on one desktop therefore keep separate sessions and separate preferences
 * without either of them having to know the other exists, which is exactly the collision two status
 * bars were.</p>
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
