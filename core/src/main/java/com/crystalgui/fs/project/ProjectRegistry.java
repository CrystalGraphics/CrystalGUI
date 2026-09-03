package com.crystalgui.fs.project;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgFileError;
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
        generation++;
        return this;
    }

    public boolean unregister(ProjectProvider provider) {
        boolean removed = providers.remove(provider);
        if (removed) generation++;
        return removed;
    }

    /**
     * Bumped by {@link #register} and {@link #unregister}, so the registry's own membership is part of
     * what {@link #stamp()} covers and a provider need not report it.
     */
    private volatile long generation;

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
     * <h3>Cached, on the providers' own revisions</h3>
     *
     * <p>This used to rebuild from every provider on every call, with a javadoc arguing that a cache
     * "would need an invalidation hook nobody would remember to call". The objection was right about
     * hooks and wrong about the cost: this method is on the path of every authorisation, every
     * {@code LocalFileSystem.resolve} and every listing, so <b>one file read rebuilt it three times</b>
     * and the watcher's etag poll rebuilt it twice per file per peer per half second.</p>
     *
     * <p>{@link ProjectProvider#revision()} is the hook nobody has to remember, because the answer that
     * needs no hook is the default: a provider whose set never changes returns a constant and is asked
     * for its projects exactly once. Only a provider that genuinely creates projects at runtime has
     * anything to bump, and it is the one that knows it did.</p>
     */
    public List<WorkspaceProject> all() {
        return snapshot().ordered;
    }

    /** Just the halves a client is allowed to see. */
    public List<ProjectInfo> infos() {
        Snapshot current = snapshot();
        if (current.infos == null) {
            List<ProjectInfo> out = new ArrayList<>(current.ordered.size());
            for (WorkspaceProject project : current.ordered) out.add(project.info());
            current.infos = List.copyOf(out);
        }
        return current.infos;
    }

    /** The project an id names, or empty. A map lookup — it used to be a scan of a freshly built list. */
    public Optional<WorkspaceProject> find(String projectId) {
        if (projectId == null) return Optional.empty();
        return Optional.ofNullable(snapshot().byId.get(projectId));
    }

    // ── The cache ───────────────────────────────────────────────────────────────────────────────

    /** What the registry answered, and the stamp it was true for. */
    private static final class Snapshot {
        final long stamp;
        final List<WorkspaceProject> ordered;
        final Map<String, WorkspaceProject> byId;
        List<ProjectInfo> infos;

        Snapshot(long stamp, List<WorkspaceProject> ordered, Map<String, WorkspaceProject> byId) {
            this.stamp = stamp;
            this.ordered = ordered;
            this.byId = byId;
        }
    }

    private volatile Snapshot snapshot;

    /**
     * The registry's membership plus every provider's revision.
     *
     * <p>Summed rather than hashed: a sum changes whenever any one part does, which is the whole
     * requirement, and it cannot collide with itself the way a hash of two numbers can.</p>
     */
    private long stamp() {
        long total = generation;
        for (ProjectProvider provider : providers) total += provider.revision();
        return total;
    }

    private Snapshot snapshot() {
        long now = stamp();
        Snapshot current = snapshot;
        if (current != null && current.stamp == now) return current;

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
        // The stamp is re-read AFTER building, and the one from before is what is stored. A provider that
        // changed while we were walking it therefore leaves a snapshot whose stamp no longer matches, and
        // the next call rebuilds -- rather than caching a half-observed set under the new stamp.
        Snapshot built = new Snapshot(now, List.copyOf(byId.values()), Map.copyOf(byId));
        snapshot = built;
        return built;
    }

    /**
     * Drops the cache.
     *
     * <p>Not needed by a correct provider — {@link ProjectProvider#revision()} is the mechanism — and
     * here for the host that has to do something drastic, and for tests that swap a provider's contents
     * without pretending to be one.</p>
     */
    public ProjectRegistry invalidate() {
        generation++;
        snapshot = null;
        return this;
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
