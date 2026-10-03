package com.crystalgui.mc.legacy.probe;

import javax.annotation.Nullable;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgui.mc.legacy.client.CgUiScreen;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgui.probe.ConnectionProbe;

import com.crystalgui.mc.legacy.Game;
import com.crystalgui.mc.legacy.client.ClientGame;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The Forge 1.8–1.12.2 half of {@link ConnectionProbe}: six answers and two verbs.
 *
 * <p>Every check, the checklist and the report are {@code core}'s. This says how this era spells a world,
 * a screen and the first joined player.</p>
 *
 * <p>In {@code .client} rather than {@code .net} deliberately: it names {@link CgUiScreen}, and this
 * package is the one {@code serverSmoke} enumerates as never-loaded-on-a-server, so putting it here is
 * what asserts it cannot leak onto one.</p>
 */
public final class CgUiConnectionProbe {

    private CgUiConnectionProbe() {
    }

    public static void register() {
        if (!ConnectionProbe.enabled()) return;
        FMLCommonHandler.instance().bus().register(new Handler());
        // THE WATCHDOG, which is what makes this a build task rather than a window somebody has to
        // close. @see ConnectionProbe#arm()
        ConnectionProbe.arm();
    }

    private static final ConnectionProbe.Host HOST = new ConnectionProbe.Host() {

        /**
         * An integrated server shares a JVM, a heap and a filesystem with its client, so the checks
         * that are about LOCATION mean nothing there — and the check that a client's own screen cannot
         * stall the server means nothing anywhere else.
         */
        @Override
        public ConnectionProbe.Topology topology() {
            return Minecraft.getMinecraft().isSingleplayer()
                    ? ConnectionProbe.Topology.INTEGRATED
                    : ConnectionProbe.Topology.DEDICATED;
        }

        /**
         * Loads a save, creating a superflat one if there is none — so this is a build task rather than
         * something needing somebody at the keyboard. Answers false while the main menu is not up.
         *
         * <p>Nothing to do when {@code -PcgJoin} sent the client straight at a server: it is already on
         * its way into a world, and {@link #inWorld} is what waits for it.</p>
         */
        @Override
        public boolean enterWorld() {
            // Non-null means -PcgJoin pointed the client at a server, so there is no save to load.
            if (Minecraft.getMinecraft().getCurrentServerData() != null) return true;
            return CgUiAutoTest.enterWorld();
        }

        @Override
        public boolean inWorld() {
            return ClientGame.inWorld();
        }

        @Override
        public boolean screenIsUp() {
            return Minecraft.getMinecraft().currentScreen != null;
        }

        @Override
        public void closeScreen() {
            Minecraft.getMinecraft().displayGuiScreen(null);
        }

        @Override
        public void openDesktop() {
            CgUiScreen.openEditor();
        }

        @Override
        @Nullable
        public CgProtocolConnection<Object> connectionToFirstPlayer() {
            MinecraftServer server = Game.server();
            if (server == null) return null;
            List<EntityPlayerMP> players = Game.players(server);
            return players.isEmpty() ? null : CgNetwork.forPlayer(players.get(0).getGameProfile().getId());
        }

        @Override
        @Nullable
        public CgProtocolConnection<Object> clientConnection() {
            return CgNetwork.client();
        }

        @Override
        public void quit() {
            Minecraft.getMinecraft().shutdown();
        }
    };

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class Handler {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            ConnectionProbe.serverTick(HOST);
        }

        /** @see #onClientTick the pause note below */
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            // AN UNATTENDED WINDOW IS NEVER FOCUSED, and pausing for that stops the integrated server
            // -- so the connection being driven over it stops being pumped and the run stalls.
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.gameSettings != null) mc.gameSettings.pauseOnLostFocus = false;
            ConnectionProbe.clientTick(HOST);
        }
    }
}
