package com.crystalgui.fs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where a project id becomes a directory. <b>Server-side.</b>
 *
 * <p>The engine registers nothing here. A server that has installed no mod offering a workspace exposes
 * no workspace, which is the cheapest security property available and the reason this is a registry rather
 * than a scan of some blessed folder.</p>
 *
 * <h3>Ids must be unique across providers</h3>
 * <p>A duplicate is refused at registration rather than resolved by ordering. Two mods each offering
 * {@code scripts} is a collision the namespace prefix exists to prevent, and silently letting one win
 * means a {@link CgPath} saved in a document resolves to a different project depending on mod load
 * order.</p>
 */
public final class ProjectRegistry {

    private final List<ProjectProvider> providers = new CopyOnWriteArrayList<>();

    /**
     * Where {@link #defaultRootFor} puts things. Supplied by the host, because only it knows where a
     * server's writable data lives — a config directory on one loader, a world folder on another.
     */
    private volatile Path defaultBase;

    public ProjectRegistry register(ProjectProvider provider) {
        if (provider == null) throw new IllegalArgumentException("provider");
        providers.add(provider);
        return this;
    }

    public boolean unregister(ProjectProvider provider) {
        return providers.remove(provider);
    }

    /** Sets the base directory {@link #defaultRootFor} builds on. */
    public ProjectRegistry defaultBase(Path base) {
        this.defaultBase = base;
        return this;
    }

    /**
     * The conventional location for a project that has no opinion: {@code <base>/<namespace>/<name>}.
     *
     * <p>A convenience, never a requirement — {@link WorkspaceProject} takes any path, and the whole point
     * of D12 is that a mod may point a project at somewhere that already exists.</p>
     *
     * @throws IllegalStateException if the host has not set a base
     */
    public Path defaultRootFor(String namespace, String name) {
        Path base = defaultBase;
        if (base == null) {
            throw new IllegalStateException(
                    "no default base directory set — call defaultBase(...) or give the project an explicit root");
        }
        return base.resolve(namespace).resolve(name);
    }

    /**
     * Every project on offer, in registration order, with duplicate ids refused.
     *
     * <p>Recomputed on each call rather than cached: a provider may create a project at runtime, and a
     * registry that cached would need an invalidation hook nobody would remember to call.</p>
     */
    public List<WorkspaceProject> all() {
        Map<String, WorkspaceProject> byId = new LinkedHashMap<>();
        for (ProjectProvider provider : providers) {
            List<WorkspaceProject> offered = provider.projects();
            if (offered == null) continue;
            for (WorkspaceProject project : offered) {
                if (project == null) continue;
                WorkspaceProject clash = byId.putIfAbsent(project.id(), project);
                if (clash != null && clash != project) {
                    throw new IllegalStateException("two providers both offer the project id '"
                            + project.id() + "' — ids must be namespaced, e.g. 'mymod." + project.id() + "'");
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** Just the halves a client is allowed to see. */
    public List<ProjectInfo> infos() {
        List<ProjectInfo> out = new ArrayList<>();
        for (WorkspaceProject project : all()) out.add(project.info());
        return out;
    }

    /** The project an id names, or empty. */
    public Optional<WorkspaceProject> find(String projectId) {
        if (projectId == null) return Optional.empty();
        for (WorkspaceProject project : all()) {
            if (project.id().equals(projectId)) return Optional.of(project);
        }
        return Optional.empty();
    }

    /**
     * The project a path belongs to.
     *
     * @throws CgFileSystemException {@link CgFileError#FILE_NOT_FOUND} when no provider offers it.
     *         Deliberately the same answer a missing file gives: whether a project exists is not
     *         something an unauthorised client should be able to probe.
     */
    public WorkspaceProject require(CgPath path) {
        return find(path.project()).orElseThrow(() -> new CgFileSystemException(
                CgFileError.FILE_NOT_FOUND, "no such project: " + path.project()));
    }
}
