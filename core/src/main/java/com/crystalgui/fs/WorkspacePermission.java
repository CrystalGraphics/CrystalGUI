package com.crystalgui.fs;

import com.crystalgui.fs.project.WorkspaceProject;

/**
 * The host mod's answer to "may this player do this, here?".
 *
 * <p>The trust boundary. CrystalGUI has no opinion about who may touch a server's files — an op check, a
 * permission node, a whitelist and "anyone in creative" are all legitimate and all the host's business.</p>
 *
 * <h3>Consulted on every operation, never once at open time</h3>
 * <p>An authorisation granted when a workspace is opened is not an authorisation for the request after
 * it: permissions change, and a client is free to keep asking. {@link WorkspaceService} calls this on
 * each call for that reason, so an implementation must be cheap — a map lookup, not a database query.</p>
 */
@FunctionalInterface
public interface WorkspacePermission {

    /**
     * @param actor     who is asking
     * @param project   the project the path belongs to, already resolved
     * @param path      the exact path, already confined to that project
     * @param operation what is being attempted
     */
    boolean allows(WorkspaceActor actor, WorkspaceProject project, CgPath path,
                   WorkspaceOperation operation);

    /** Refuses everything. The default a host gets if it registers projects and forgets the callback. */
    WorkspacePermission DENY_ALL = (actor, project, path, operation) -> false;

    /** Allows everything — for a harness scene or a single-player world where there is no one to guard against. */
    WorkspacePermission ALLOW_ALL = (actor, project, path, operation) -> true;

    /** Read yes, write no. */
    WorkspacePermission READ_ONLY =
            (actor, project, path, operation) -> operation == WorkspaceOperation.READ;
}
