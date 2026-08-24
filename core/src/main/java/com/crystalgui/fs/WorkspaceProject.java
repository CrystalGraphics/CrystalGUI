package com.crystalgui.fs;

import java.nio.file.Path;
import java.util.List;

/**
 * A project as the <b>server</b> knows it: an id, a label, and where it actually lives.
 *
 * <p>Server-side only. {@link ProjectInfo} is the half that crosses the wire, and the {@link #root()}
 * deliberately does not — see that type.</p>
 *
 * <h3>The id is {@code namespace.name}, and the separator is a dot</h3>
 * <p>Namespaced so two mods cannot collide, and a <b>dot</b> because {@link CgPath} spends the colon on
 * separating the project from the path within it — {@code myproject:src/Main.java}. The plan for this
 * feature wrote ids as {@code namespace:name}, which would have made a full path carry two colons and
 * an ambiguous split; the dot keeps parsing trivial and reads the same way a Java package does.</p>
 *
 * <h3>The mod chooses the directory, not the engine</h3>
 * <p>If the layout were fixed as {@code <root>/<namespace>/<name>}, the engine would have decided where
 * every project lives — which forecloses exposing a world's {@code datapacks/} folder, a path an admin
 * configured, or anything that already exists on disk. {@link ProjectRegistry#defaultRootFor} exists so
 * the common case is still one line.</p>
 *
 * @param info     the wire-safe half
 * @param root     where the files are. Absolute, and every {@link CgPath} in this project resolves
 *                 beneath it — the resolver's one remaining job, since {@code CgPath} has already
 *                 guaranteed no lexical escape.
 * @param excludes glob patterns never listed or walked. The hook exists from the start because a client
 *                 that has cached a listing containing {@code node_modules} keeps it.
 */
public record WorkspaceProject(ProjectInfo info, Path root, List<String> excludes) {

    public WorkspaceProject {
        if (info == null) throw new IllegalArgumentException("info");
        if (root == null) throw new IllegalArgumentException("root");
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
    }

    public WorkspaceProject(String id, String displayName, Path root) {
        this(new ProjectInfo(id, displayName), root, List.of());
    }

    public String id() {
        return info.id();
    }

    /**
     * Where this project's source starts. @see SourceRoots
     *
     * <p>Delegated to {@link ProjectInfo} rather than held here, and the split is deliberate:
     * {@link #excludes} is a <b>server-side</b> rule about what is never listed, while source roots are
     * what a <b>client-side</b> index derives qualified names with. A workspace can be remote, so
     * anything the client must know has to travel, and {@code ProjectInfo} is what travels.</p>
     */
    public java.util.List<String> sourceRoots() {
        return info.sourceRoots();
    }

    /**
     * The owning mod's namespace — everything before the first dot.
     *
     * <p>An id with no dot is entirely its own namespace, which keeps a single-project mod from having to
     * invent one.</p>
     */
    public String namespace() {
        int dot = info.id().indexOf('.');
        return dot < 0 ? info.id() : info.id().substring(0, dot);
    }
}
