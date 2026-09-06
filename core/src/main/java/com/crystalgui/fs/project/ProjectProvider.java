package com.crystalgui.fs.project;

import java.util.List;

/**
 * A mod's answer to "what projects do you have?".
 *
 * <p>Registered with {@link ProjectRegistry}. Enumeration rather than a fixed directory scan, so a mod
 * can create a project at runtime, name it whatever it likes, and put it wherever it already keeps that
 * data.</p>
 *
 * <h3>This is not the authorisation seam</h3>
 * <p>A provider says what <em>exists</em>. Whether a given player may see or touch it is a separate
 * question with a separate callback, checked on every operation — deliberately not folded in here, because
 * a provider that filtered by player would be consulted once at listing time and then trusted, which is
 * exactly the mistake that turns an access check into an access <em>hint</em>.</p>
 */
@FunctionalInterface
public interface ProjectProvider {

    /**
     * Every project this provider currently offers.
     *
     * <p>Called whenever the set might have moved, so it should be cheap. Returning a fresh list is fine;
     * scanning a disk here is not.</p>
     *
     * <p>Since {@link #revision()} exists, "whenever the set might have moved" is decided by that rather
     * than by every caller: a provider whose projects never change is asked exactly once per process.</p>
     */
    List<WorkspaceProject> projects();

    /**
     * A number that <b>changes whenever {@link #projects()} would answer differently</b>.
     *
     * <p>{@link ProjectRegistry#all()} used to rebuild from every provider on every call, and it is on
     * the path of every read, every write and every authorisation — so one file read cost three full
     * rebuilds, and the watcher's poll cost two per file per peer per half second. The registry caches
     * now, and this is how it knows when to stop.</p>
     *
     * <p><b>Zero is the right answer for almost every provider</b>, and it is the default: a mod that
     * registers its projects at load and never changes them has a fixed set, so a constant is truthful.
     * A provider that genuinely creates projects at runtime — one per world, one per player — bumps a
     * counter when it does. IntelliJ's {@code ModificationTracker} is the same contract under the same
     * name for the same reason.</p>
     *
     * <p>Must be cheap: it is read where {@code projects()} used to be called. A field, not a scan.</p>
     */
    default long revision() {
        return 0L;
    }
}
