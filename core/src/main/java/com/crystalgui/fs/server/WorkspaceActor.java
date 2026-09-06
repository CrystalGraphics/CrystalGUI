package com.crystalgui.fs.server;

/**
 * Whoever is asking — a player, on the host's terms.
 *
 * <p>{@code core/} cannot name a Minecraft player, and should not: the same workspace has to serve a
 * harness scene with no players in it at all. So the host wraps whatever it has and the engine carries
 * this, using it for nothing but handing back to {@link WorkspacePermission}.</p>
 */
public interface WorkspaceActor {

    /** Stable across a session, and the key any permission decision should be made on. */
    String id();

    /** For logs and for the UI. Never used to decide anything. */
    default String displayName() {
        return id();
    }

    /**
     * The actor a purely local workspace uses — a harness scene, or a test.
     *
     * <p>Deliberately not a "trusted" or "admin" actor: it is an ordinary identity that a permission
     * callback is free to refuse. Nothing in the engine treats it specially.</p>
     */
    WorkspaceActor LOCAL = () -> "local";
}
