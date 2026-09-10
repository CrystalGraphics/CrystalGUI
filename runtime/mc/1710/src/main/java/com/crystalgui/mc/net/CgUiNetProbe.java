package com.crystalgui.mc.net;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.wire.CgNetworkChannel;
import com.crystalgui.net.wire.FrameMultiplexer;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.network.NetworkManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Does the wire actually carry frames between a client and a server? — {@code -Dcrystalgui.net.probe=true}.
 *
 * <p>Off by default, and it exists because the headless tests cannot answer that question. They cover the
 * engine thoroughly with two {@code FrameMultiplexer}s handing arrays to each other, which is exactly the
 * seam a platform sits under — so they prove everything <em>above</em> {@link CgNetworkChannel} and
 * nothing at all about Forge's channel, FML's discriminators, the Netty thread, the real 32,766-byte
 * ceiling, or player routing.</p>
 *
 * <p>M12 §26.13a already recorded the general form of this lesson, and it is the reason this class exists
 * rather than a fifth headless test: <i>"A client is an environment no test reproduces … Build the probe
 * early rather than reasoning from source."</i></p>
 *
 * <h3>Why {@code runClient} is enough</h3>
 *
 * <p>A single-player world runs an integrated server in the same process, on its own thread, and traffic
 * between the two goes through the same {@code SimpleNetworkWrapper} path a dedicated server uses. So one
 * client run exercises both halves, including the thread hop.</p>
 *
 * <h3>What it sends</h3>
 *
 * <p>Four sizes, chosen against the measured limit rather than round numbers: one frame, exactly at the
 * boundary, just over it, and far over it. The boundary cases are the ones an off-by-one in fragment
 * sizing hides behind.</p>
 */
public final class CgUiNetProbe {

    private static final boolean ENABLED = Boolean.getBoolean("crystalgui.net.probe");

    /** The real ceiling. A payload at exactly this size must still fit in one platform frame. */
    private static final int FRAME = 32_766;

    private static final int[] SIZES = {
            8,               // one frame, trivially
            FRAME - 64,      // one frame, with the header only just fitting
            FRAME + 1,       // two frames — the off-by-one an untested fragmenter gets wrong
            250_000,         // many frames, and past the initial credit window
    };

    /** Client side: one connection to the server. */
    private static FrameMultiplexer client;

    /** Server side: one connection per player, which is what a real session map will be. */
    private static final Map<Object, FrameMultiplexer> serverByPlayer = new HashMap<>();

    private static final List<Integer> echoed = new ArrayList<>();
    private static int sent;
    private static volatile int framesOut;
    private static volatile int framesIn;
    private static int ticks;
    private static boolean started;
    private static boolean closedScreen;
    private static boolean reported;

    private CgUiNetProbe() {
    }

    public static void register() {
        if (!ENABLED) return;
        FMLCommonHandler.instance().bus().register(new Handler());
        CrystalGuiCore.LOGGER.info("[net-probe] armed; waiting for a world");
    }

    private static void start() {
        CgNetworkChannel channel = CgPlatform.get(CgNetworkChannel.SERVICE);
        CrystalGuiCore.LOGGER.info("[net-probe] channel available={} maxFrameBytes={}",
                channel.isAvailable(), channel.maxFrameBytes());
        if (!channel.isAvailable()) {
            CrystalGuiCore.LOGGER.error("[net-probe] FAIL — no channel was provided to the platform slot");
            reported = true;
            return;
        }

        client = new FrameMultiplexer(channel.maxFrameBytes(), true, frame -> {
            framesOut++;
            channel.sendToServer(frame);
        });
        client.setMessageHandler(message -> {
            // Came back from the server. Verify the bytes rather than just the length: a fragmenter that
            // reassembles in the wrong order produces something of exactly the right size.
            int size = message.length;
            boolean intact = Arrays.equals(message, payload(size));
            CrystalGuiCore.LOGGER.info("[net-probe] echo received: {} bytes, intact={}", size, intact);
            if (intact) echoed.add(size);
            else CrystalGuiCore.LOGGER.error("[net-probe] FAIL — {} bytes came back corrupted", size);
        });

        // The server half. One connection per player, sink routed back to that player.
        channel.setInboundHandler((sender, frame) -> {
            framesIn++;
            if (framesIn == 1) {
                CrystalGuiCore.LOGGER.info("[net-probe] first inbound frame, sender={}",
                        sender == null ? "null (client side)" : sender.getClass().getSimpleName());
            }
            if (sender == null) {
                client.onFrameReceived(frame);          // client receiving from the server
                return;
            }
            serverByPlayer.computeIfAbsent(sender, player -> {
                FrameMultiplexer server = new FrameMultiplexer(
                        channel.maxFrameBytes(), false, f -> channel.sendToPlayer(player, f));
                server.setMessageHandler(message -> {
                    CrystalGuiCore.LOGGER.info("[net-probe] server got {} bytes; echoing", message.length);
                    server.send(message);
                });
                return server;
            }).onFrameReceived(frame);
        });

        // FMLOutboundHandler.TOSERVER drops silently when this is null or carries no FML_DISPATCHER.
        NetworkManager toServer = FMLCommonHandler.instance().getClientToServerNetworkManager();
        Object dispatcher = toServer == null ? null
                : toServer.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
        // The two gates every delivery passes through, checked rather than assumed:
        //   NetworkDispatcher.handleServerSideCustomPacket -> NetworkRegistry.hasChannel(name, SERVER)
        //   NetworkManager.scheduleOutboundPacket -> isChannelOpen(), or it queues forever
        CrystalGuiCore.LOGGER.info("[net-probe] hasChannel CLIENT={} SERVER={} | channelOpen={}",
                NetworkRegistry.INSTANCE.hasChannel("crystalgui", Side.CLIENT),
                NetworkRegistry.INSTANCE.hasChannel("crystalgui", Side.SERVER),
                toServer == null ? "n/a" : String.valueOf(toServer.isChannelOpen()));
        CrystalGuiCore.LOGGER.info("[net-probe] clientToServer manager={} FML_DISPATCHER={}",
                toServer == null ? "NULL" : "present",
                dispatcher == null ? "NULL — selectNetworks returns empty and the send is dropped"
                        : dispatcher.getClass().getSimpleName());

        // THE PIPELINES, both sides. If FML's handler is absent from either, a custom payload is never
        // routed to a channel and dies with no log -- which is exactly the symptom.
        try {
            CrystalGuiCore.LOGGER.info("[net-probe] CLIENT pipeline: {}",
                    toServer == null ? "n/a" : toServer.channel().pipeline().names());
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null && !server.getConfigurationManager().playerEntityList.isEmpty()) {
                EntityPlayerMP mp = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
                CrystalGuiCore.LOGGER.info("[net-probe] SERVER pipeline: {}",
                        mp.playerNetServerHandler.netManager.channel().pipeline().names());
            } else {
                CrystalGuiCore.LOGGER.warn("[net-probe] no server player — integrated server not ready");
            }
        } catch (Throwable t) {
            CrystalGuiCore.LOGGER.warn("[net-probe] pipeline dump failed", t);
        }

        for (int size : SIZES) client.send(payload(size));
        sent = SIZES.length;
        CrystalGuiCore.LOGGER.info("[net-probe] queued {} messages: {}", sent, Arrays.toString(SIZES));
    }

    /** Deterministic content, so the receiver can verify rather than trust the length. */
    private static byte[] payload(int length) {
        byte[] value = new byte[length];
        for (int i = 0; i < length; i++) value[i] = (byte) (i * 31 + (length & 0x7F));
        return value;
    }

    public static final class Handler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (!ENABLED || event.phase != TickEvent.Phase.END || reported) return;

            Minecraft mc = Minecraft.getMinecraft();
            // A world, and a connection to it. In single-player that means the integrated server has
            // accepted us, which is when a channel can carry anything.
            if (mc.theWorld == null || mc.thePlayer == null) return;

            // NO SCREEN, OR THE SERVER IS NOT LISTENING.
            //
            // CgUiScreen.doesGuiPauseGame() is true, and in single player Minecraft.runTick sets
            // isGamePaused from exactly that -- which stops the INTEGRATED SERVER ticking. A paused
            // server never runs NetworkManager.processReceivedPackets, so inbound frames sit in
            // receivedPacketsQueue forever: no error, no exception, nothing received.
            //
            // This cost eleven runs. Every gate reported healthy -- channel registered on both sides,
            // connection open, dispatcher live, both pipelines correct, sends accepted -- because the
            // transport was fine and the probe's own harness was pausing the peer it was talking to.
            if (mc.currentScreen != null) {
                if (!closedScreen) {
                    closedScreen = true;
                    CrystalGuiCore.LOGGER.info("[net-probe] closing {} — it pauses the integrated server",
                            mc.currentScreen.getClass().getSimpleName());
                }
                mc.displayGuiScreen(null);
                return;
            }

            if (!started) {
                // A few ticks of grace: the handshake completes after the world exists, and a frame sent
                // before it lands is dropped by FML with nothing said.
                if (++ticks < 40) return;
                started = true;
                start();
                return;
            }

            // Both halves pump on this thread. In production the server half pumps on the server tick;
            // here the integrated server shares the process, and the probe is checking transport rather
            // than threading policy.
            if (client != null) client.pump();
            for (FrameMultiplexer server : serverByPlayer.values()) server.pump();

            // Every second, so a run that is cut short still says how far it got.
            if (ticks % 20 == 0) {
                CrystalGuiCore.LOGGER.info("[net-probe] tick {} — framesOut={} framesIn={} echoed={} "
                                + "pendingOut={} credit={}",
                        ticks, framesOut, framesIn, echoed.size(),
                        client.pendingOutboundMessages(), client.sendCredit());
            }
            if (++ticks > 40 + 200) finish();
            else if (echoed.size() == sent) finish();
        }

        private void finish() {
            reported = true;
            boolean pass = echoed.size() == sent;
            if (pass) {
                CrystalGuiCore.LOGGER.info("[net-probe] PASS — all {} messages made the round trip: {}",
                        sent, echoed);
            } else {
                List<Integer> missing = new ArrayList<>();
                for (int size : SIZES) if (!echoed.contains(size)) missing.add(size);
                CrystalGuiCore.LOGGER.error("[net-probe] FAIL — {} of {} returned; missing {}. "
                                + "framesOut={} framesIn={}",
                        echoed.size(), sent, missing, framesOut, framesIn);
            }
        }
    }
}
