package com.crystalgui.mc.modern.net;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * A connected player, identified by profile and routed through the packet listener.
 *
 * <p><b>Never keyed on the entity.</b> A respawn or a dimension change builds a new {@link ServerPlayer}
 * and re-points the existing listener at it, so an entity-keyed map is orphaned the first time either
 * happens: inbound frames stop resolving for good while outbound keeps working, which reads as an input
 * bug rather than an identity one. The {@code GameProfile} UUID survives both, and the listener is what
 * gets re-pointed -- so a send RESOLVES its target rather than holding one.</p>
 */
public final class Peer {

    private final UUID id;
    private final String name;
    private final ServerGamePacketListenerImpl listener;

    private Peer(UUID id, String name, ServerGamePacketListenerImpl listener) {
        this.id = id;
        this.name = name;
        this.listener = listener;
    }

    @Nullable
    public static Peer of(@Nullable ServerPlayer player) {
        if (player == null || player.connection == null) return null;
        if (player.getGameProfile() == null || player.getGameProfile().getId() == null) return null;
        return new Peer(player.getGameProfile().getId(),
                player.getGameProfile().getName(), player.connection);
    }

    /** Stable across death, respawn and a dimension change. */
    public UUID id() {
        return id;
    }

    /** The name at join time: for logs and for a {@code WorkspaceActor}, never a map key. */
    public String name() {
        return name;
    }

    /** Resolved, not captured -- the listener outlives the entity and is re-pointed at the new one. */
    @Nullable
    public ServerPlayer player() {
        return listener.player;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Peer && id.equals(((Peer) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Peer[" + name + " " + id + "]";
    }
}
