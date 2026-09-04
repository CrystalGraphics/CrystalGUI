package com.crystalgui.mc.net;

import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.protocol.Connections;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.wire.CgNetworkChannel;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * FML's lifecycle, turned into {@link Connections} calls — <b>and nothing else</b>.
 *
 * <p>This class held the peer table, the multiplexers, the routing, the close semantics and the
 * tick isolation; all of that is {@code net.protocol.Connections} now, because none of it is about
 * Minecraft. What is left is the four events, the channel, and the one translation only this platform
 * can do: an entity into an identity.</p>
 *
 * <table>
 *   <tr><th></th><th>Opens</th><th>Closes</th><th>Ticks on</th></tr>
 *   <tr><td>Server</td><td>{@code PlayerLoggedInEvent}</td>
 *       <td>{@code PlayerLoggedOutEvent}, {@code FMLServerStoppingEvent}</td>
 *       <td>{@code ServerTickEvent}</td></tr>
 *   <tr><td>Client</td><td>{@code ClientConnectedToServerEvent}</td>
 *       <td>{@code ClientDisconnectionFromServerEvent}</td>
 *       <td>{@code ClientTickEvent}</td></tr>
 * </table>
 *
 * <p>A kick and a disconnect are the same event as a quit — FML does not distinguish them at this level,
 * and neither should this: what matters is that the peer is gone and every caller waiting on a reply is
 * told, rather than waiting out a ten-second timeout for something that is never coming.</p>
 *
 * <h3>Two tables, because this process can be both ends</h3>
 *
 * <p>Single-player runs an integrated server, so the client's connection and a player's connection exist
 * in one JVM and are ticked by different events. One table would tick the client's peer on the server's
 * tick as well.</p>
 *
 * <h3>Ticked before anything else in the frame</h3>
 *
 * <p>On {@code Phase.START}, so a message that arrived since the last tick is applied <em>before</em> the
 * world runs on it rather than a tick later. The UI's own style-before-layout ordering is the same rule
 * one layer up: state that arrived this frame must reach its consumer before the consumer runs.</p>
 */
public final class CgUiConnections {

    /** The one key the client's table needs: there is a single peer and it does not need naming. */
    private static final Object CLIENT = "client";

    @Nullable
    private static Connections server;

    @Nullable
    private static volatile Connections client;

    private static boolean registered;

    private CgUiConnections() {
    }

    /**
     * Wires the lifecycle. Called from {@code CommonProxy.init()} — <b>both sides need it, and the
     * server needs it more.</b>
     *
     * <p>Skipped while {@code crystalgui.net.probe} is set: {@link CgNetworkChannel} takes <em>one</em>
     * inbound handler, so the raw transport probe and this cannot both own the channel. The probe is
     * opt-in and diagnostic; production is this.</p>
     */
    public static synchronized void register() {
        if (registered) return;
        if (Boolean.getBoolean("crystalgui.net.probe")) {
            CrystalGuiCore.LOGGER.warn("[cgui-net] connection lifecycle NOT installed — the raw transport "
                    + "probe owns the channel while -Dcrystalgui.net.probe is set");
            return;
        }
        CgNetworkChannel channel = CgPlatform.get(CgNetworkChannel.SERVICE);
        if (!channel.isAvailable()) {
            CrystalGuiCore.LOGGER.warn("[cgui-net] no network channel; connections will not be opened");
            return;
        }
        // The SERVER is not the initiator: odd/even stream ids, as HTTP/2 splits them.
        server = new Connections("server", channel.maxFrameBytes(), false)
                .onPeerClosed(CgUiWorkspaceHost::forget);
        client = new Connections("client", channel.maxFrameBytes(), true);
        channel.setInboundHandler(CgUiConnections::route);
        FMLCommonHandler.instance().bus().register(new Handler());
        registered = true;
        CrystalGuiCore.LOGGER.info("[cgui-net] connection lifecycle installed; contributors: {}",
                Protocols.contributors());
    }

    /**
     * Whether {@link #register()} actually installed the lifecycle.
     *
     * <p>Exists because both of its failure paths are a {@code warn} and a {@code return} rather than a
     * throw — an unavailable channel, or the raw transport probe owning it — so a server with no
     * networking at all boots perfectly happily and looks healthy. Read by
     * {@link CgUiServerSmoke}, which is the thing that turns that into an exit code.</p>
     */
    public static synchronized boolean isRegistered() {
        return registered;
    }

    /** The connection to this player, or {@code null} if they have none — they left, or never had one. */
    @Nullable
    public static ProtocolConnection<Object> forPlayer(EntityPlayer player) {
        UUID id = idOf(player);
        return id == null || server == null ? null : server.get(id);
    }

    /** A player's stable identity, or {@code null} for anything without a profile (a fake player). */
    @Nullable
    private static UUID idOf(@Nullable EntityPlayer player) {
        if (player == null || player.getGameProfile() == null) return null;
        return player.getGameProfile().getId();
    }

    /** This client's connection to the server, or {@code null} when not in a world. */
    @Nullable
    public static ProtocolConnection<Object> client() {
        Connections table = client;
        return table == null ? null : table.get(CLIENT);
    }

    /** How many peers currently hold one. Diagnostics, and what a leak would show up in. */
    public static int openConnections() {
        return (server == null ? 0 : server.size()) + (client == null ? 0 : client.size());
    }

    /**
     * <b>Netty thread.</b> Routes a frame to its peer's table.
     *
     * <p>By UUID on the server: the channel hands over whichever entity the player is currently wearing,
     * and that is a different object after every respawn — so the translation happens here, at the one
     * seam where an entity is turned into an identity. @see Mc1710Peer</p>
     */
    private static void route(@Nullable Object sender, byte[] frame) {
        if (sender == null) {
            Connections table = client;
            if (table != null) table.route(CLIENT, frame);
            return;
        }
        UUID id = sender instanceof EntityPlayer ? idOf((EntityPlayer) sender) : null;
        if (id == null || server == null) return;
        server.route(id, frame);
    }

    // ── The events ──────────────────────────────────────────────────────────

    public static final class Handler {

        @SubscribeEvent
        public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.player instanceof EntityPlayerMP)) return;
            CgNetworkChannel channel = CgPlatform.get(CgNetworkChannel.SERVICE);
            if (!channel.isAvailable() || server == null) return;
            Mc1710Peer identity = Mc1710Peer.of((EntityPlayerMP) event.player);
            if (identity == null) return;   // no profile or no handler: nothing to talk to
            // RESOLVED AT SEND TIME, never captured. The entity a player is wearing is replaced on every
            // respawn, and capturing one here means sending to a body nobody is in -- which happens to
            // work today only because the stale entity keeps its handler reference, i.e. by accident.
            server.open(identity.id(), identity, frame -> {
                EntityPlayerMP live = identity.player();
                // Null between a disconnect and this peer being dropped; FML's own argument validation
                // throws on a null target rather than ignoring it.
                if (live != null) channel.sendToPlayer(live, frame);
            });
            CrystalGuiCore.LOGGER.info("[cgui-net] connection opened for {} ({} open)",
                    identity.name(), openConnections());
        }

        @SubscribeEvent
        public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            // BY UUID, and this is the half that leaked. The logout event carries whichever entity the
            // player is wearing NOW, which after any death is not the one that joined -- so an
            // entity-keyed removal silently removed nothing and every per-peer map grew for the life of
            // the server. @see Mc1710Peer
            UUID id = idOf(event.player);
            if (id == null || server == null) return;
            if (!server.close(id, "player left")) return;
            CrystalGuiCore.LOGGER.info("[cgui-net] connection closed for {} ({} open)",
                    id, openConnections());
        }

        @SubscribeEvent
        public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
            CgNetworkChannel channel = CgPlatform.get(CgNetworkChannel.SERVICE);
            if (!channel.isAvailable() || client == null) return;
            client.open(CLIENT, null, channel::sendToServer);
            CrystalGuiCore.LOGGER.info("[cgui-net] client connection opened");
        }

        @SubscribeEvent
        public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            Connections table = client;
            if (table != null && table.close(CLIENT, "disconnected")) {
                CrystalGuiCore.LOGGER.info("[cgui-net] client connection closed");
            }
        }

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            if (server != null) server.tick();
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            Connections table = client;
            if (table != null) table.tick();
        }
    }

    /**
     * Server is stopping — close every connection.
     *
     * <p>Called from the mod's {@code FMLServerStoppingEvent} rather than subscribed here, because that
     * is a mod-lifecycle event and arrives on a different bus. Without it, a stop leaves every pending
     * call unanswered and every {@code onError} unrun, which on a reload-in-place looks like the next
     * session inheriting ghosts.</p>
     */
    public static void closeAll(String reason) {
        int had = openConnections();
        CgUiWorkspaceHost.reset();
        if (server != null) server.closeAll(reason);
        Connections table = client;
        if (table != null) table.closeAll(reason);
        if (had > 0) CrystalGuiCore.LOGGER.info("[cgui-net] closed {} connection(s): {}", had, reason);
    }
}
