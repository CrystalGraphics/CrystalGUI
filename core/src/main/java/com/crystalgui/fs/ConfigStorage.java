package com.crystalgui.fs;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Where the client keeps its <em>own</em> files — preferences, and one session record per project.
 *
 * <h3>Not a {@link CgFileSystem}, because a {@link CgPath} is confined to a project</h3>
 *
 * <p>That confinement is the most valuable property {@code CgPath} has, and configuration is precisely the
 * thing that must live outside every project. Reaching a config directory through a project path would
 * need either a fake project or a hole in the confinement, and the second would cost more than this
 * interface does.</p>
 *
 * <h3>Client-local, always</h3>
 *
 * <p>The workspace may be a dedicated server shared by several people. Preferences are not the server's to
 * hold: stored there they would be somebody else's when they connect, and would not follow their owner to
 * the next world they join. The same goes for a session record — which tabs <em>I</em> had open.</p>
 *
 * <h3>And not a platform service</h3>
 *
 * <p>{@code AGENTS.md} is explicit that a second registry beside CrystalGraphics' is how a loader ends up
 * wiring one and not the other. This is a dependency handed to whoever needs it, exactly as
 * {@link WorkspaceClient} already is, so there is nothing to forget to register.</p>
 */
public interface ConfigStorage {

    /** The contents of a named blob, or null when there is none. Absence is never an exception. */
    @Nullable
    String read(String name);

    /**
     * Replaces a named blob.
     *
     * @throws IllegalStateException when {@link #isWritable()} is false
     */
    void write(String name, String contents);

    /** Every name currently stored — what pruning session records for long-gone projects needs. */
    List<String> list();

    void delete(String name);

    /** False for a store that cannot be written: a config directory that could not be created. */
    default boolean isWritable() {
        return true;
    }
}
