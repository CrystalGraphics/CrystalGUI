package com.crystalgui.fs.server;

import java.util.List;
import java.util.Objects;

import com.crystalgui.fs.CgFileEntry;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.project.ProjectInfo;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceService;

/**
 * A workspace <b>as one actor sees it</b> — the service with the caller already decided.
 *
 * <pre>{@code
 * ServerWorkspace fs = io.workspace();
 * for (CgFileEntry entry : fs.list(CgPath.ofProject("mymod.proj"))) …
 * byte[] bytes = fs.read(path).content();
 * }</pre>
 *
 * <p>Every {@link WorkspaceService} method takes a {@link WorkspaceActor} as its first argument, which
 * is right for a service serving many peers and wrong for a caller that is one of them: a panel handed
 * the raw service can pass {@link WorkspaceActor#LOCAL} and read files the player it is showing has no
 * permission to see. Binding the actor once removes the parameter and with it the mistake.</p>
 *
 * <p>Read-only plus the ordinary writes, and deliberately not the whole service: trash, presence and
 * the watch hub are the protocol's business, not a panel's.</p>
 */
public final class ServerWorkspace {

    private final WorkspaceService service;
    private final WorkspaceActor actor;

    public ServerWorkspace(WorkspaceService service, WorkspaceActor actor) {
        this.service = Objects.requireNonNull(service, "service");
        this.actor = Objects.requireNonNull(actor, "actor");
    }

    /** Who this view acts as. Every call below is authorised against it. */
    public WorkspaceActor actor() {
        return actor;
    }

    /** The service underneath, for the few things that genuinely need to name an actor. */
    public WorkspaceService service() {
        return service;
    }

    /** The projects this actor may see. */
    public List<ProjectInfo> projects() {
        return service.projects(actor);
    }

    /** What is in a directory. */
    public List<CgFileEntry> list(CgPath directory) {
        return service.manifest(actor, directory);
    }

    /** A file's metadata, without paying for its bytes. */
    public CgFileEntry stat(CgPath path) {
        return service.stat(actor, path);
    }

    /** A file, with the etag a later write must quote back. */
    public WorkspaceService.FileContent read(CgPath path) {
        return service.read(actor, path);
    }

    /**
     * Writes a file, refusing if it moved since {@code expectedEtag} was taken.
     *
     * @return the new etag
     */
    public String write(CgPath path, byte[] content, String expectedEtag) {
        return service.write(actor, path, content, expectedEtag);
    }

    /** Creates a file that is not there, refusing to clobber one that is. */
    public String create(CgPath path, byte[] content) {
        return service.create(actor, path, content);
    }

    public void mkdir(CgPath path) {
        service.mkdir(actor, path);
    }

    public void delete(CgPath path, boolean recursive) {
        service.delete(actor, path, recursive);
    }

    public void rename(CgPath from, CgPath to, boolean overwrite) {
        service.rename(actor, from, to, overwrite);
    }
}
