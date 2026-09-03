package com.crystalgui.fs.project;

import com.crystalgui.fs.CgPath;

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
public record ProjectInfo(String id, String displayName, java.util.List<String> sourceRoots) {

    public ProjectInfo {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("project id");
        if (displayName == null) displayName = id;
        // EMPTY MEANS THE CONVENTION, not "no roots", and the two cannot be told apart anyway: the wire
        // encodes an absent list and an empty one identically, so an older server describing an ordinary
        // project would otherwise read as rootless and silently switch every file back to
        // declaration-derived packages. Nothing is lost by the conflation -- a project with no source is
        // one whose declared roots simply contain none, which costs nothing to declare.
        sourceRoots = sourceRoots == null || sourceRoots.isEmpty()
                ? SourceRoots.CONVENTION : java.util.List.copyOf(sourceRoots);
    }

    /**
     * A project laid out the ordinary way.
     *
     * <p>The roots default to {@link SourceRoots#CONVENTION} rather than to nothing, because a project
     * that declares none is far more likely to be one nobody has thought about than one that genuinely
     * has no source. Every caller written before roots existed keeps working and gets the layout it
     * almost certainly has.</p>
     */
    public ProjectInfo(String id, String displayName) {
        this(id, displayName, SourceRoots.CONVENTION);
    }

    /** The project root as a path, which is what a browser opens. */
    public CgPath root() {
        return CgPath.ofProject(id);
    }
}
