package com.crystalgui.fs;

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
     */
    List<WorkspaceProject> projects();
}
