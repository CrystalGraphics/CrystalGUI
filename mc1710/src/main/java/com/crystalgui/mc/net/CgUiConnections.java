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
import java.util.UUID;
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
        /** Null on the client, where there is one peer and it does not need naming. */
        @Nullable
        final Mc1710Peer identity;

        Peer(FrameMultiplexer frames, ProtocolConnection<Object> connection,
             @Nullable Mc1710Peer identity) {
            this.frames = frames;
            this.connection = connection;
            this.identity = identity;
        }
    }

    /**
     * Keyed by the player's <b>UUID</b>, never by their entity.
     *
     * <p>1.7.10 constructs a new {@code EntityPlayerMP} on every respawn and every dimension change, so
     * an entity-keyed map is orphaned by the first death: inbound frames name the new body, the lookup
     * misses, and every frame from that client is dropped for the rest of the session — while outbound
     * keeps working, which makes it read as an input bug rather than an identity one. The logout event
     * carries the new body too, so even the cleanup missed and the connection leaked. @see Mc1710Peer</p>
     */
    private static final Map<UUID, Peer> SERVER = new ConcurrentHashMap<>();

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
        if (id == null) return null;
        Peer peer = SERVER.get(id);
        return peer == null ? null : peer.connection;
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
        Peer peer = client;
        return peer == null ? null : peer.connection;
    }

    /** How many players currently hold one. Diagnostics, and what a leak would show up in. */
    public static int openConnections() {
        return SERVER.size() + (client == null ? 0 : 1);
    }

    // ── Opening and closing ─────────────────────────────────────────────────

    private static Peer open(CgNetworkChannel channel, boolean initiator, @Nullable Mc1710Peer player) {
        FrameMultiplexer[] slot = new FrameMultiplexer[1];
        // RESOLVED AT SEND TIME, never captured. The entity a player is wearing is replaced on every
        // respawn, and capturing one here means sending to a body nobody is in -- which happens to work
        // today only because the stale entity keeps its handler reference, i.e. by accident. Asking the
        // peer is what FML's own PLAYER outbound target does one layer down. @see Mc1710Peer
        slot[0] = new FrameMultiplexer(channel.maxFrameBytes(), initiator,
                player == null ? channel::sendToServer : frame -> {
                    EntityPlayerMP live = player.player();
                    // Null between a disconnect and this peer being dropped; FML's own argument
                    // validation throws on a null target rather than ignoring it.
                    if (live != null) channel.sendToPlayer(live, frame);
                });
        WireTransport transport = new WireTransport(slot[0]);
        // The pump goes in here rather than being left to a caller: tick() is then the one call, and a
        // subsystem that forgot to pump would receive nothing, silently.
        ProtocolConnection<Object> connection =
                Protocols.open(transport, PlainOps.INSTANCE, transport::pump, player);
        return new Peer(slot[0], connection, player);
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
        // BY UUID. The channel hands over whichever entity the player is currently wearing, and that is
        // a different object after every respawn -- so the translation happens here, at the one seam
        // where an entity is turned into an identity. @see Mc1710Peer
        UUID id = sender instanceof EntityPlayer ? idOf((EntityPlayer) sender) : null;
        if (id == null) return;
        Peer peer = SERVER.get(id);
        if (peer != null) peer.frames.onFrameReceived(frame);
    }

    // ── The events ──────────────────────────────────────────────────────────

    public static final class Handler {

        @SubscribeEvent
        public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.player instanceof EntityPlayerMP)) return;
            CgNetworkChannel channel = CgPlatform.get(CgNetworkChannel.SERVICE);
            if (!channel.isAvailable()) return;
            Mc1710Peer identity = Mc1710Peer.of((EntityPlayerMP) event.player);
            if (identity == null) return;   // no profile or no handler: nothing to talk to
            // The SERVER is not the initiator: odd/even stream ids, as HTTP/2 splits them, so the two
            // ends can allocate concurrently without agreeing on anything.
            SERVER.put(identity.id(), open(channel, false, identity));
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
            Peer peer = id == null ? null : SERVER.remove(id);
            if (peer == null) return;
            // Anything that bound per-peer state must be told, or its maps grow for the life of the
            // server -- a leak that only shows on a box that has been up for a week.
            if (peer.identity != null) CgUiWorkspaceHost.forget(peer.identity);
            closePeer(peer, "player left");
            CrystalGuiCore.LOGGER.info("[cgui-net] connection closed for {} ({} open)",
                    peer.identity == null ? id : peer.identity.name(), openConnections());
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
