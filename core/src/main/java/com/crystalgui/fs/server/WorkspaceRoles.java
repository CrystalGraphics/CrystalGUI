package com.crystalgui.fs.server;

/**
 * The two facts about a player that only the host game knows. @see OperatorsMayWrite
 *
 * <pre>{@code
 * WorkspaceRoles roles = new WorkspaceRoles() {
 *     public boolean isOwner(String id) {
 *         GameProfile owner = server.getSingleplayerProfile();
 *         return owner != null && id.equalsIgnoreCase(owner.getName());
 *     }
 *     public boolean isOperator(String id) {
 *         ServerPlayer player = server.getPlayerList().getPlayerByName(id);
 *         return player != null && server.getPlayerList().isOp(player.getGameProfile());
 *     }
 * };
 * }</pre>
 *
 * <p>Cheap — both are asked on every operation, not once at open time.</p>
 */
public interface WorkspaceRoles {

    /**
     * Whether this actor <b>owns the world this process is serving</b> — the single-player host.
     *
     * <p>Answer from ownership alone. Whatever the game's "may use commands" check is, it almost
     * certainly folds in a cheats flag, and cheats gate <em>commands</em>: they have nothing to say
     * about whether somebody may edit files in their own save directory. Folding it in was a real
     * defect — a fresh world has cheats off, so its host could list the workspace and not write to it,
     * and the refusal was a correct-looking {@code NO_PERMISSIONS} with no way to tell it from a real
     * one.</p>
     *
     * <p>False on a dedicated server, which has no owner present.</p>
     */
    boolean isOwner(String actorId);

    /**
     * Whether this actor is <b>connected now and an operator</b>.
     *
     * <p>Both halves matter. An actor who has left is not an operator for this purpose — refusing
     * strands nobody, since a player who has gone has nothing in flight that a write would complete.</p>
     */
    boolean isOperator(String actorId);
}
