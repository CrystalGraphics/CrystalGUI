package com.crystalgui.fs.server;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.project.WorkspaceProject;

/**
 * <b>Everyone reads; operators write; the owner of a single-player world always writes.</b>
 *
 * <pre>{@code
 * new com.crystalgui.fs.server.WorkspaceHost(id, "Workspace", host);
 * // ...where host.permission() answers:
 * new OperatorsMayWrite(myRoles)
 * }</pre>
 *
 * <p>A read is allowed to any connected player because a workspace is the server's shared content,
 * like a datapack. If that turns out to be wrong for somebody, the fix is a per-project permission
 * rather than tightening this one — which is why {@link #allows} takes the project it was given.</p>
 *
 * <p>The game-specific half is {@link WorkspaceRoles}, which is two questions. This class exists
 * because the policy over them was written twice — once per Minecraft era — and the copies had already
 * drifted on the one case that matters most, the single-player owner with cheats off.</p>
 */
public final class OperatorsMayWrite implements WorkspacePermission {

    private final WorkspaceRoles roles;

    public OperatorsMayWrite(WorkspaceRoles roles) {
        this.roles = roles;
    }

    @Override
    public boolean allows(WorkspaceActor actor, WorkspaceProject project, CgPath path,
                          WorkspaceOperation operation) {
        if (operation == WorkspaceOperation.READ) return true;
        String id = actor.id();
        return roles.isOwner(id) || roles.isOperator(id);
    }
}
