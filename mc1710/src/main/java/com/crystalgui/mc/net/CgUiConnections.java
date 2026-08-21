package com.crystalgui.mc.net;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.wire.CgNetworkChannel;
import com.crystalgui.net.wire.FrameMultiplexer;
import com.crystalgui.net.wire.WireTransport;
import com.crystalgui.serialization.PlainOps;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 4 <b>A4</b> — one {@link ProtocolConnection} per peer, opened when they arrive and closed when
 * they leave.
 *
 * <p>Before this, both halves of a session were constructed together and died together, which is only
 * possible because they shared a process. A real connection has a beginning and an end that neither half
 * chooses: a player joins, a player is kicked, a server stops. This is where that is modelled, and it is
 * the last structural thing between the engine and a workspace hosted on a dedicated server.</p>
 *
 * <h3>Where each end lives</h3>
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
 * <h3>Three threads, and the map is the only thing they share</h3>
 *
 * <p>Frames arrive on Netty's thread, server connections open, close and tick on the server thread, and
 * the client's does all three on the client thread. So the map is a {@link ConcurrentHashMap} and
 * nothing else is shared: {@link ProtocolConnection#tick()} is the only thing that dispatches, and it is
 * always called from the thread that owns whatever the handlers touch.</p>
 *
 * <h3>Ticked before anything else in the frame</h3>
 *
 * <p>On {@code Phase.START}, so a message that arrived since the last tick is applied <em>before</em> the
 * world runs on it rather than a tick later. The UI's own {@code calculateStyle}-before-layout ordering
 * is the same rule one layer up: state that arrived this frame must reach its consumer before the
 * consumer runs.</p>
 */
public final class CgUiConnections {

    /** Everything a peer needs, kept together so closing one closes all of it. */
    private static final class Peer {
        final FrameMultiplexer frames;
        final ProtocolConnection<Object> connection;

        Peer(FrameMultiplexer frames, ProtocolConnection<Object> connection) {
            this.frames = frames;
            this.connection = connection;
        }
    }

    private static final Map<Object, Peer> SERVER = new ConcurrentHashMap<>();

    @Nullable
    private static volatile Peer client;

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
        channel.setInboundHandler(CgUiConnections::route);
        FMLCommonHandler.instance().bus().register(new Handler());
        registered = true;
        CrystalGuiCore.LOGGER.info("[cgui-net] connection lifecycle installed; contributors: {}",
                Protocols.contributors());
    }

    /** The connection to this player, or {@code null} if they have none — they left, or never had one. */
    @Nullable
    public static ProtocolConnection<Object> forPlayer(EntityPlayer player) {
        Peer peer = SERVER.get(player);
        return peer == null ? null : peer.connection;
    }

    /** This client's connection to the server, or {@code null} when not in a world. */
    @Nullable
    public static ProtocolConnection<Object> client() {
        Peer peer = client;
        return peer == null ? null : peer.connection;
    }

    /** How many players currently hold one. Diagnostics, and what a leak would show up in. */
    public static int openConnections() {
        return SERVER.size() + (client == null ? 0 : 1);
    }

    // ── Opening and closing ─────────────────────────────────────────────────

    private static Peer open(CgNetworkChannel channel, boolean initiator, @Nullable Object player) {
        FrameMultiplexer[] slot = new FrameMultiplexer[1];
        slot[0] = new FrameMultiplexer(channel.maxFrameBytes(), initiator,
                player == null ? channel::sendToServer : frame -> channel.sendToPlayer(player, frame));
        WireTransport transport = new WireTransport(slot[0]);
        // The pump goes in here rather than being left to a caller: tick() is then the one call, and a
        // subsystem that forgot to pump would receive nothing, silently.
        ProtocolConnection<Object> connection =
                Protocols.open(transport, PlainOps.INSTANCE, transport::pump, player);
        return new Peer(slot[0], connection);
    }

    private static void closePeer(@Nullable Peer peer, String reason) {
        if (peer == null) return;
        // Fails everything outstanding rather than letting each caller wait out its own timeout. A peer
        // that is gone is knowable now; ten seconds of silence per pending call is not information.
        peer.connection.close(reason);
    }

    /**
     * <b>Netty thread.</b> Routes a frame to its peer's multiplexer, which only enqueues.
     *
     * <p>A frame for a peer that has already gone is dropped rather than opening one: a connection is
     * created by a lifecycle event, never by traffic. Creating one here would resurrect a player who has
     * left, and would make a disconnect racy against whatever was still in flight.</p>
     */
    private static void route(@Nullable Object sender, byte[] frame) {
        if (sender == null) {
            Peer peer = client;
            if (peer != null) peer.frames.onFrameReceived(frame);
            return;
        }
        Peer peer = SERVER.get(sender);
        if (peer != null) peer.frames.onFrameReceived(frame);
    }

    // ── The events ──────────────────────────────────────────────────────────

    public static final class Handler {

        @SubscribeEvent
        public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.player instanceof EntityPlayerMP)) return;
            CgNetworkChannel channel = CgPlatform.get(CgNetworkChannel.SERVICE);
            if (!channel.isAvailable()) return;
            // The SERVER is not the initiator: odd/even stream ids, as HTTP/2 splits them, so the two
            // ends can allocate concurrently without agreeing on anything.
            SERVER.put(event.player, open(channel, false, event.player));
            CrystalGuiCore.LOGGER.info("[cgui-net] connection opened for {} ({} open)",
                    event.player.getCommandSenderName(), openConnections());
        }

        @SubscribeEvent
        public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            Peer peer = SERVER.remove(event.player);
            if (peer == null) return;
            // Anything that bound per-peer state must be told, or its maps grow for the life of the
            // server -- a leak that only shows on a box that has been up for a week.
            CgUiWorkspaceHost.forget(event.player);
            closePeer(peer, "player left");
            CrystalGuiCore.LOGGER.info("[cgui-net] connection closed for {} ({} open)",
                    event.player.getCommandSenderName(), openConnections());
        }

        @SubscribeEvent
        public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
            CgNetworkChannel channel = CgPlatform.get(CgNetworkChannel.SERVICE);
            if (!channel.isAvailable()) return;
            client = open(channel, true, null);
            CrystalGuiCore.LOGGER.info("[cgui-net] client connection opened");
        }

        @SubscribeEvent
        public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            Peer peer = client;
            client = null;
            closePeer(peer, "disconnected");
            if (peer != null) CrystalGuiCore.LOGGER.info("[cgui-net] client connection closed");
        }

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            for (Peer peer : SERVER.values()) {
                tickSafely(peer, "server");
            }
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            tickSafely(client, "client");
        }

        /**
         * One peer's exception must not stop every other peer being ticked.
         *
         * <p>On a server this is the difference between one player's broken handler and every player's
         * session freezing — and the frozen ones would show no error of their own, which is the shape
         * that gets diagnosed as a network fault.</p>
         */
        private void tickSafely(@Nullable Peer peer, String which) {
            if (peer == null) return;
            try {
                peer.connection.tick();
            } catch (RuntimeException failed) {
                CrystalGuiCore.LOGGER.error("[cgui-net] {} connection tick failed: {}",
                        which, failed.getMessage(), failed);
            }
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
        for (Peer peer : SERVER.values()) closePeer(peer, reason);
        SERVER.clear();
        closePeer(client, reason);
        client = null;
        if (had > 0) CrystalGuiCore.LOGGER.info("[cgui-net] closed {} connection(s): {}", had, reason);
    }
}
