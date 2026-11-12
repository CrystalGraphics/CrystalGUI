package com.crystalgui.desktop.app;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.storage.config.CgConfigStorage;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.WorkspaceClient;

/**
 * What an application is handed when it launches: its manifest, its windows, its storage and what it was asked to
 * open. It is the argument of {@link ApplicationKind#launch}'s factory; the registry builds it, you never do.
 *
 * <pre>{@code
 * public static final ApplicationKind KIND = ApplicationKind.of("mymod:planner", "Planner")
 *         .launch(Planner::new);
 *
 * Planner(AppContext app) {
 *     main = app.windows().open(WindowSpec.titled("Planner").content(new PlannerView()));
 *     Resource file = app.first();                      // what "open with" launched it for, or null
 *     CgConfigStorage prefs = app.storage();            // this application's own corner of the config directory
 * }
 * }</pre>
 *
 * <table>
 *   <caption>What it carries</caption>
 *   <tr><td>{@code kind}</td><td>the manifest being launched: its id, name and icon</td></tr>
 *   <tr><td>{@link #windows()}</td><td>where this application opens its windows and finds them again</td></tr>
 *   <tr><td>{@code workspace}</td><td>the file client, or {@code null} with no connection; a kind that cannot work
 *       without one is refused before its factory runs</td></tr>
 *   <tr><td>{@code storage}</td><td>this application's own corner of the desktop's config directory</td></tr>
 *   <tr><td>{@code cache}</td><td>a directory for output you can rebuild, or {@code null}; it is deleted without warning</td></tr>
 *   <tr><td>{@code document}</td><td>the document it runs in, or {@code null} when it runs in the shell's; its windows' client roots live here</td></tr>
 *   <tr><td>{@code open}</td><td>the files to show once it is up; {@link #first()} is the usual read</td></tr>
 * </table>
 *
 * <p>Storage is scoped by the kind's id before the factory runs, so two applications on one desktop keep separate
 * sessions and preferences. It is not scoped to a workspace: preferences are the same on every server, while a
 * session, a backup and a local history belong to one. A workspace's identity arrives with the server's greeting,
 * after launch, so ask for its store then:</p>
 *
 * <pre>{@code
 * CgConfigStorage mine = app.desktop().workspaceStore(workspaceIdentity);
 * workspace.setStorage(mine);
 * }</pre>
 *
 * <ul>
 *   <li>Open windows with {@link #windows()}, never by building a frame: {@link #desktop()} is the engine's shell,
 *       there for what the engine itself needs (stores, the connection, notifications).</li>
 *   <li>Keep nothing in {@code cache} you cannot regenerate.</li>
 * </ul>
 */
public record AppContext(ApplicationKind kind,
                            Desktop desktop,
                            @Nullable WorkspaceClient workspace,
                            CgConfigStorage storage,
                            @Nullable Path cache,
                            List<Resource> open,
                            @Nullable AppDocument document) {

    public AppContext {
        open = open == null ? List.of() : List.copyOf(open);
    }

    /** A launch into the shell's own document. */
    public AppContext(ApplicationKind kind, Desktop desktop, @Nullable WorkspaceClient workspace,
                      CgConfigStorage storage, @Nullable Path cache, List<Resource> open) {
        this(kind, desktop, workspace, storage, cache, open, null);
    }

    /** This launch, with {@code document} as the one its application runs in. */
    public AppContext withDocument(@Nullable AppDocument document) {
        return new AppContext(kind, desktop, workspace, storage, cache, open, document);
    }

    /** A launch with nothing asked of it. */
    public static AppContext of(ApplicationKind kind, Desktop desktop,
                                   @Nullable WorkspaceClient workspace, CgConfigStorage storage,
                                   @Nullable Path cache) {
        return new AppContext(kind, desktop, workspace, storage, cache, List.of());
    }

    /** This application's windows: where it opens them, and finds them again. */
    public AppWindows windows() {
        return new AppWindows(desktop, kind, document);
    }

    /**
     * Gives the desktop the state this application wants back after a restart; call it when the state changes. Any thread.
     *
     * <pre>{@code
     * context.saveState(layout.toJson());        // the next session starts the application with this
     * }</pre>
     *
     * <p>The desktop writes it with the session, never reads it. The last string wins; a restart with none starts clean.</p>
     */
    public void saveState(String state) {
        desktop.applications().rememberState(kind.id(), state);
        desktop.saveSoon();
    }

    /**
     * Hands {@code apply} the state the last session left, if there was one, before the application's first frame.
     *
     * <pre>{@code
     * context.restoreState(json -> layout.load(json));    // in the constructor
     * }</pre>
     *
     * @return whether there was state to apply
     */
    public boolean restoreState(java.util.function.Consumer<String> apply) {
        String state = desktop.applications().stateOf(kind.id());
        if (state == null || state.isEmpty()) return false;
        apply.accept(state);
        return true;
    }

    /** The one resource this launch was asked to open, or null. */
    @Nullable
    public Resource first() {
        return open.isEmpty() ? null : open.get(0);
    }
}
