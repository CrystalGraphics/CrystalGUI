package com.crystalgui.mc.modern.probe;

import javax.annotation.Nullable;

import com.crystalgui.mc.modern.net.Connections;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.mc.modern.client.CgUiScreen;
import com.crystalgui.probe.ConnectionProbe;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The MC 1.20.x half of {@link ConnectionProbe}: six answers and two verbs.
 *
 * <p>1.20.x had none of these checks — the six probes they came from were 1.7.10's alone, so the
 * session handshake, tree and state deltas, fan-out and the workspace had never been exercised over a
 * connection on this era at all. That is the whole reason the probe went to {@code core}.</p>
 *
 * <p>In {@code .client} rather than {@code .net} deliberately: it names {@link CgUiScreen}, and this
 * package is the one {@code serverSmoke} enumerates as never-loaded-on-a-server.</p>
 */
public final class ConnectionProbeModern {

    private ConnectionProbeModern() {
    }

    private static final ConnectionProbe.Host HOST = new ConnectionProbe.Host() {

        @Override
        public ConnectionProbe.Topology topology() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.hasSingleplayerServer()
                    ? ConnectionProbe.Topology.INTEGRATED
                    : ConnectionProbe.Topology.DEDICATED;
        }

        /** Loads a save, or says there is none. @see CgUiAutoTest#enterWorld */
        @Override
        public boolean enterWorld() {
            return CgUiAutoTest.enterWorld();
        }

        @Override
        public boolean inWorld() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.level != null && mc.player != null;
        }

        @Override
        public boolean screenIsUp() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.screen != null;
        }

        @Override
        public void closeScreen() {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(null);
        }

        @Override
        public void openDesktop() {
            CgUiScreen.openEditor();
        }

        @Override
        @Nullable
        public ProtocolConnection<Object> connectionToFirstPlayer() {
            Minecraft mc = Minecraft.getInstance();
            MinecraftServer server = mc == null ? null : mc.getSingleplayerServer();
            if (server == null || server.getPlayerList() == null) return null;
            if (server.getPlayerList().getPlayers().isEmpty()) return null;
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            return Connections.forPlayer(player);
        }

        @Override
        @Nullable
        public ProtocolConnection<Object> clientConnection() {
            return Connections.client();
        }

        @Override
        public void quit() {
            Minecraft mc = Minecraft.getInstance();
            // stop() rather than System.exit: it runs Minecraft's own shutdown, which is what
            // CgGraphicsLifecycle.destroyContext hangs off.
            if (mc != null) mc.stop();
        }
    };

    /**
     * <b>An unattended window is never focused, and pausing for that stalls the run.</b>
     *
     * <p>A paused client stops the integrated server, so the connection the probe is being driven over
     * stops being pumped -- and the pause menu re-opens every tick, which the probe then spends its
     * tick closing. Set every tick rather than once at start-up: {@code --quickPlaySingleplayer} loads
     * the world before anything of ours is consulted, so there is no single earlier moment that is
     * reliably after the options exist and before the world.</p>
     */
    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null) mc.options.pauseOnLostFocus = false;
        ConnectionProbe.clientTick(HOST);
    }

    /**
     * Once per server tick — <b>and only on an integrated one</b>.
     *
     * <p>{@code LifecycleCrystalGUI.serverTick} runs on a dedicated server too, where this class must
     * never load. The host reaches the integrated server through the client, so on a dedicated server
     * there is nothing for it to answer with and the probe's own server half is simply never driven:
     * a two-process run drives the session from the client, which is where the probe lives.</p>
     */
    public static void serverTick() {
        ConnectionProbe.serverTick(HOST);
    }
}
