package com.crystalgui.fs;

/**
 * What a client is told about a project: an id and a name to show. <b>Never a directory.</b>
 *
 * <p>The separation from {@link WorkspaceProject} is deliberate and is a security boundary, not tidiness.
 * A client needs an id to build {@link CgPath}s with and a label to draw; it has no use for
 * {@code /home/mc/servers/survival/projects/scripts}, and sending one leaks the host's disk layout to
 * everybody who can open the workspace. Keeping the {@code Path} in a type that has no reason to be
 * serialized is how that stays true without anyone having to remember it.</p>
 *
 * @param id          the project id — see {@link WorkspaceProject} for the {@code namespace.name} form
 * @param displayName what a user sees. Free text; never parsed.
 */
public record ProjectInfo(String id, String displayName) {

    public ProjectInfo {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("project id");
        if (displayName == null) displayName = id;
    }

    /** The project root as a path, which is what a browser opens. */
    public CgPath root() {
        return CgPath.ofProject(id);
    }
}
