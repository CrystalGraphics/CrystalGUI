package com.crystalgui.mc.net;

import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.protocol.Connections;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.wire.CgNetworkChannel;

import net.minecraft.server.level.ServerPlayer;

/**
 * The peer table for MC 1.20.x. Everything here is vanilla; each loader only forwards its join, leave,
 * connect, disconnect and tick events.
 *
 * <p>The table itself is {@link Connections} in {@code core/} -- open/close/route/tick and the rule that
 * one peer's failure must not stop the others. This is the identity and the outbound route.</p>
 */
public final class Connections1201 {

    /** The client's single key. A client has exactly one server, so it needs no identity. */
    private static final Object CLIENT = "client";

    private Connections1201() {}

    private static boolean registered;
    private static Connections server;
    private static Connections client;

    public static synchronized void register() {
        if (registered) return;

        CgNetworkChannel channel = CgPlatform.get(CgNetworkChannel.SERVICE);
        if (!channel.isAvailable()) {
            CrystalGuiCore.LOGGER.warn("[cgui-net] no network channel; connections will not be opened");
            return;
        }

        server = new Connections("server", channel.maxFrameBytes(), false)
                .onPeerClosed(WorkspaceHost1201::forget);
        client = new Connections("client", channel.maxFrameBytes(), true);
        channel.setInboundHandler(Connections1201::route);

        registered = true;
        CrystalGuiCore.LOGGER.info("[cgui-net] connection lifecycle installed; contributors: {}",
                Protocols.contributors());
    }

    public static synchronized boolean isRegistered() {
        return registered;
    }

    /** The connection to this player, or null when they have none. */
    @Nullable
    public static ProtocolConnection<Object> forPlayer(@Nullable ServerPlayer player) {
        UUID id = idOf(player);
        return id == null || server == null ? null : server.get(id);
    }

    /** The connection to the server, or null when not in a world. Re-asked every frame by the host. */
    @Nullable
    public static ProtocolConnection<Object> client() {
        return client == null ? null : client.get(CLIENT);
    }

    public static int openConnections() {
        return server == null ? 0 : server.size();
    }

    @Nullable
    private static UUID idOf(@Nullable ServerPlayer player) {
        if (player == null || player.getGameProfile() == null) return null;
        return player.getGameProfile().getId();
    }

    /** Inbound. {@code sender} is the ServerPlayer on a server and null on a client. */
    private static void route(@Nullable Object sender, byte[] frame) {
        if (sender == null) {
            if (client != null) client.route(CLIENT, frame);
            return;
        }
        UUID id = sender instanceof ServerPlayer ? idOf((ServerPlayer) sender) : null;
        if (id != null && server != null) server.route(id, frame);
    }

    // ── What a loader forwards ──────────────────────────────────────────────────────────────────

    public static void onPlayerJoin(ServerPlayer player) {
        CgNetworkChannel channel = CgPlatform.get(CgNetworkChannel.SERVICE);
        if (!channel.isAvailable() || server == null) return;

        Peer1201 identity = Peer1201.of(player);
        if (identity == null) return;

        // Resolved at send time, never captured: the entity is replaced on every respawn.
        server.open(identity.id(), identity, frame -> {
            ServerPlayer live = identity.player();
            if (live != null) channel.sendToPlayer(live, frame);
        });
        CrystalGuiCore.LOGGER.info("[cgui-net] connection opened for {} ({} open)",
                identity.name(), openConnections());
    }

    /**
     * By UUID. The logout event carries whichever entity the player is wearing now, which after any
     * death is not the one that joined -- an entity-keyed removal removes nothing and every per-peer
     * map grows for the life of the server.
     */
    public static void onPlayerLeave(ServerPlayer player) {
        UUID id = idOf(player);
        if (id == null || server == null) return;
        if (server.close(id, "player left")) {
            CrystalGuiCore.LOGGER.info("[cgui-net] connection closed for {} ({} open)",
                    id, openConnections());
        }
    }

    public static void onClientConnected() {
        CgNetworkChannel channel = CgPlatform.get(CgNetworkChannel.SERVICE);
        if (!channel.isAvailable() || client == null) return;
        client.open(CLIENT, null, channel::sendToServer);
        CrystalGuiCore.LOGGER.info("[cgui-net] client connection opened");
    }

    public static void onClientDisconnected() {
        if (client != null && client.close(CLIENT, "disconnected")) {
            CrystalGuiCore.LOGGER.info("[cgui-net] client connection closed");
        }
    }

    public static void onServerTick() {
        if (server != null) server.tick();
    }

    public static void onClientTick() {
        if (client != null) client.tick();
    }

    public static void closeAll(String reason) {
        if (server != null) server.closeAll(reason);
        if (client != null) client.closeAll(reason);
    }
}
