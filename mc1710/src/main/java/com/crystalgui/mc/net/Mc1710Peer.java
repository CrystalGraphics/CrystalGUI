package com.crystalgui.mc.net;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;

/**
 * Who is on the other end of a connection, <b>stably</b> — what {@code ProtocolConnection.peer()}
 * answers on 1.7.10.
 *
 * <h3>An {@code EntityPlayerMP} is not a player</h3>
 *
 * <p>It is a player's <em>current body</em>, and 1.7.10 builds a new one every time somebody dies or
 * leaves a dimension: {@code ServerConfigurationManager.respawnPlayer} constructs
 * {@code new EntityPlayerMP(...)}, copies the old one into it, and {@code NetHandlerPlayServer}
 * re-points {@code playerEntity} at the replacement. Nothing about the connection changed — the socket,
 * the channel and the handler are all the same — but every map keyed on the entity has just been
 * orphaned.</p>
 *
 * <p>That was a live defect, and its shape is worth keeping written down because none of it announces
 * itself:</p>
 *
 * <ul>
 *   <li><b>Inbound died permanently.</b> Frames arrive naming the <em>new</em> entity, the connection
 *       map holds the <em>old</em> one, the lookup misses, and the frame is dropped — for the rest of
 *       the session. Every click in every CrystalGUI window went nowhere.</li>
 *   <li><b>Outbound kept working</b>, because the stale entity retains its handler reference and FML
 *       resolves the target through it at send time. So state deltas still arrived and the panel still
 *       animated: it reads as an input bug in the widget rather than an identity bug in a map.</li>
 *   <li><b>Cleanup missed too.</b> The logout event carries the new entity while the maps hold the old
 *       one, so removing by it silently removes nothing — the connection, its multiplexer, the
 *       workspace binding and the presence entry all outlive the player, and the open-connection count
 *       climbs by one per died-then-quit player.</li>
 * </ul>
 *
 * <h3>What is stable, and why this holds two things rather than one</h3>
 *
 * <p>The {@link #id() UUID} is the identity: it comes off the {@code GameProfile}, survives death,
 * dimension changes and a name change, and is what a permission check and an audit line both want.
 * The {@link NetHandlerPlayServer} is the <em>route</em>: it is the object that outlives the entity and
 * is re-pointed at the replacement, so asking it for {@link #player()} always yields the live body
 * without polling the configuration manager or subscribing to a respawn event.</p>
 *
 * <p>So a send resolves its target at send time rather than capturing it, which is the same thing FML's
 * own {@code PLAYER} outbound target does one layer down.</p>
 */
public final class Mc1710Peer {

    private final UUID id;
    private final String name;
    private final NetHandlerPlayServer handler;

    Mc1710Peer(UUID id, String name, NetHandlerPlayServer handler) {
        this.id = id;
        this.name = name;
        this.handler = handler;
    }

    /**
     * Builds a peer for a player who has just joined, or {@code null} if they have no connection —
     * which a fake player or a not-yet-attached entity legitimately does not.
     */
    @Nullable
    static Mc1710Peer of(EntityPlayerMP player) {
        if (player == null || player.playerNetServerHandler == null) return null;
        if (player.getGameProfile() == null || player.getGameProfile().getId() == null) return null;
        return new Mc1710Peer(player.getGameProfile().getId(), player.getCommandSenderName(),
                player.playerNetServerHandler);
    }

    /** The stable identity — survives death, respawn and a dimension change. */
    public UUID id() {
        return id;
    }

    /** The name at join time. For logs and for a {@code WorkspaceActor}; never a map key. */
    public String name() {
        return name;
    }

    /**
     * The player's <b>current</b> entity, resolved rather than remembered.
     *
     * <p>Null only in the window between a disconnect and this peer being dropped, which is why every
     * caller checks: sending to a null target throws out of FML's own argument validation.</p>
     */
    @Nullable
    public EntityPlayerMP player() {
        return handler.playerEntity;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Mc1710Peer && id.equals(((Mc1710Peer) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** The name, because this ends up in log lines about connections opening and closing. */
    @Override
    public String toString() {
        return name;
    }
}
