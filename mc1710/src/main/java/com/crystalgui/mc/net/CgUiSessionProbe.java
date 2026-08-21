package com.crystalgui.mc.net;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.wire.CgNetworkChannel;
import com.crystalgui.net.wire.FrameMultiplexer;
import com.crystalgui.net.wire.WireTransport;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.UIText;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Map;

/**
 * Does the <em>protocol</em> work over a real Minecraft connection? — {@code -Dcrystalgui.net.probe=true}.
 *
 * <p>{@link CgUiNetProbe} answers a different question and stops one layer short. It echoes byte arrays,
 * which proves the transport: Forge's channel, FML's discriminators, the Netty thread, the 32,766-byte
 * ceiling, fragmentation and player routing. It proves <b>nothing</b> about the four-kind envelope, the
 * router's correlation, or the description handshake — those had run only against two
 * {@code FrameMultiplexer}s handing arrays to each other in a headless test, which is the same shape as
 * the transport tests that were green while the real channel silently dropped everything.</p>
 *
 * <p>So this runs the actual thing: a {@link ServerUiSession} and a {@link ClientUiSession} over
 * {@link WireTransport}s on the real wire, and checks the four exchanges that between them touch every
 * message kind the engine has.</p>
 *
 * <table>
 *   <tr><th>#</th><th>Exchange</th><th>Kinds exercised</th></tr>
 *   <tr><td>1</td><td>{@code open()} → client rebuilds the tree</td>
 *       <td>notification {@code ui/openWindow}, then a <b>request</b> {@code ui/description} and its
 *           <b>response</b> — the one round trip whose correlation used to be implicit</td></tr>
 *   <tr><td>2</td><td>server mutates a widget → client sees it</td>
 *       <td>notification {@code ui/stateDelta}</td></tr>
 *   <tr><td>3</td><td>client reports an event → server's lambda runs</td>
 *       <td>notification {@code ui/event}, the whole point of the milestone</td></tr>
 *   <tr><td>4</td><td>server calls the client and gets an answer</td>
 *       <td>request/response through {@code onCall}, in the direction that is easiest to get wrong</td></tr>
 * </table>
 *
 * <p>It runs after {@link CgUiNetProbe} has finished, on its own multiplexer pair, so a failure is
 * attributable: transport green and this red means the protocol, and both red means the wire.</p>
 */
public final class CgUiSessionProbe {

    private static final boolean ENABLED = Boolean.getBoolean("crystalgui.net.probe");

    /** Distinct from the transport probe's, so neither ever sees the other's frames. */
    private static final int WINDOW_ID = 4242;

    private static ServerUiSession<Object> server;
    private static ClientUiSession<Object> client;
    private static WireTransport clientTransport;
    private static final Map<Object, WireTransport> serverByPlayer = new HashMap<>();

    private static Button serverButton;
    private static Slider serverSlider;
    private static UIText serverLabel;

    private static boolean treeArrived;
    private static boolean deltaSeen;
    private static boolean eventReceived;
    private static boolean callAnswered;

    private static int ticks;
    private static boolean started;
    private static boolean reported;
    private static boolean deltaSent;
    private static boolean eventSent;
    private static boolean callSent;

    private CgUiSessionProbe() {
    }

    public static void register() {
        if (!ENABLED) return;
        FMLCommonHandler.instance().bus().register(new Handler());
        CrystalGuiCore.LOGGER.info("[session-probe] armed; waits for the transport probe to finish");
    }

    private static void start() {
        CgNetworkChannel channel = CgPlatform.get(CgNetworkChannel.SERVICE);
        if (!channel.isAvailable()) {
            CrystalGuiCore.LOGGER.error("[session-probe] FAIL — no channel in the platform slot");
            reported = true;
            return;
        }
        // Unknown tags THROW on decode, so a client that never bootstrapped rebuilds nothing and says
        // exactly why -- which is the behaviour we want, and worth not tripping over here.
        ElementRegistry.bootstrapBuiltins();

        FrameMultiplexer clientFrames =
                new FrameMultiplexer(channel.maxFrameBytes(), true, channel::sendToServer);
        clientTransport = new WireTransport(clientFrames);

        // ROUTING ONLY. The server session is NOT created here, and that is the fix for a deadlock
        // the transport probe could not have: there, the client speaks first, so a lazy
        // computeIfAbsent(sender) always fires. A SESSION is the other way round -- the server opens the
        // window and the client answers -- so a server half created on first inbound frame waits for a
        // client that is waiting for it. Every gate reported healthy and nothing moved.
        channel.setInboundHandler((sender, frame) -> {
            if (sender == null) {
                clientFrames.onFrameReceived(frame);
                return;
            }
            WireTransport transport = serverByPlayer.get(sender);
            if (transport != null) transport.frames().onFrameReceived(frame);
        });

        client = new ClientUiSession<>(clientTransport, PlainOps.INSTANCE);
        client.onWindowOpened(root -> {
            treeArrived = root != null;
            CrystalGuiCore.LOGGER.info("[session-probe] 1/4 tree rebuilt on the client: {} children",
                    root == null ? -1 : root.getChildren().size());
        });
        // Exchange 4, the client end: the server asks, this answers.
        client.onCall("probe/ping", (args, respond) -> {
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putString("pong", args.getString("from", "?"));
            respond.ok(out);
        });

        CrystalGuiCore.LOGGER.info("[session-probe] started; waiting for a player to reach the server");
    }

    /**
     * Opens the server half as soon as the integrated server has a player, which is what makes the
     * server speak first.
     *
     * @return true once it is open, so the caller can stop asking
     */
    private static boolean tryOpenServer(CgNetworkChannel channel) {
        MinecraftServer mc = MinecraftServer.getServer();
        if (mc == null || mc.getConfigurationManager() == null
                || mc.getConfigurationManager().playerEntityList.isEmpty()) {
            return false;
        }
        EntityPlayerMP player = (EntityPlayerMP) mc.getConfigurationManager().playerEntityList.get(0);
        FrameMultiplexer serverFrames = new FrameMultiplexer(
                channel.maxFrameBytes(), false, frame -> channel.sendToPlayer(player, frame));
        WireTransport transport = new WireTransport(serverFrames);
        serverByPlayer.put(player, transport);
        openServerSession(transport);
        return true;
    }

    /** Built on the SERVER side, with no window, no Taffy tree and no fonts — that absence is the point. */
    private static void openServerSession(WireTransport transport) {
        UIElement root = new UIElement();
        serverLabel = new UIText("hello from the server");
        serverButton = new Button("Press me");
        serverSlider = new Slider();
        serverSlider.setRange(0f, 1f);
        root.addChild(serverLabel);
        root.addChild(serverButton);
        root.addChild(serverSlider);

        server = new ServerUiSession<>(WINDOW_ID, root, transport, PlainOps.INSTANCE);
        // Exchange 3: the lambda stays here and only its RESULT travels.
        server.onActivate(serverButton, ctx -> {
            eventReceived = true;
            CrystalGuiCore.LOGGER.info("[session-probe] 3/4 the server's own lambda ran for a client press");
        });
        server.open();
        CrystalGuiCore.LOGGER.info("[session-probe] server session opened, descHash={}", server.descHash());
    }

    public static final class Handler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (!ENABLED || event.phase != TickEvent.Phase.END || reported) return;

            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.thePlayer == null) return;
            // Same trap CgUiNetProbe documents: an open screen pauses the integrated server, and a paused
            // server never drains its inbound queue.
            if (mc.currentScreen != null) {
                mc.displayGuiScreen(null);
                return;
            }
            // Let the transport probe have the channel to itself first; its handler is replaced by ours.
            if (++ticks < 320) return;

            if (!started) {
                started = true;
                start();
                return;
            }
            if (clientTransport == null) return;
            if (server == null && !tryOpenServer(CgPlatform.get(CgNetworkChannel.SERVICE))) {
                if (ticks % 40 == 0) {
                    CrystalGuiCore.LOGGER.info("[session-probe] no server player yet");
                }
                return;
            }

            clientTransport.pump();
            for (WireTransport transport : serverByPlayer.values()) transport.pump();
            if (server != null) server.tick();
            client.tick();

            step();

            if (ticks % 40 == 0) {
                CrystalGuiCore.LOGGER.info("[session-probe] tick {} — tree={} delta={} event={} call={}",
                        ticks, treeArrived, deltaSeen, eventReceived, callAnswered);
            }
            if (treeArrived && deltaSeen && eventReceived && callAnswered) finish(true);
            else if (ticks > 320 + 400) finish(false);
        }

        /** One exchange per readiness gate, so a stall names the exchange it stalled on. */
        private void step() {
            if (!treeArrived || server == null) return;

            // 2/4 -- a server-side mutation must reach the client as a state delta.
            if (!deltaSent) {
                deltaSent = true;
                serverSlider.setValue(0.75f);
                CrystalGuiCore.LOGGER.info("[session-probe] server moved the slider to 0.75");
                return;
            }
            if (!deltaSeen) {
                UIElement mirrored = client.root() == null ? null : client.root().getChildren().get(2);
                if (mirrored instanceof Slider && Math.abs(((Slider) mirrored).getValue() - 0.75f) < 1e-4f) {
                    deltaSeen = true;
                    CrystalGuiCore.LOGGER.info("[session-probe] 2/4 the delta arrived and applied");
                }
                return;
            }

            // 3/4 -- the client presses the REAL widget, which is the only way an event is raised.
            // ClientUiSession.report is private on purpose: what reports is a listener the client
            // attached because the DESCRIPTION said this element reports 'activate'. Calling it directly
            // would skip the half of the path that can actually be wrong.
            if (!eventSent) {
                UIElement mirrored = client.root().getChildren().get(1);
                if (!(mirrored instanceof Button)) return;
                eventSent = true;
                ((Button) mirrored).onPressed.emit();
                CrystalGuiCore.LOGGER.info("[session-probe] client pressed the mirrored button");
                return;
            }
            if (!eventReceived) return;

            // 4/4 -- a request in the server->client direction, answered by the client's handler.
            if (!callSent) {
                callSent = true;
                StateMap<Object> args = new StateMap<>(PlainOps.INSTANCE);
                args.putString("from", "server");
                server.call("probe/ping", args,
                        result -> {
                            callAnswered = "server".equals(result.getString("pong", ""));
                            CrystalGuiCore.LOGGER.info("[session-probe] 4/4 the client answered: pong={}",
                                    result.getString("pong", ""));
                        },
                        error -> CrystalGuiCore.LOGGER.error("[session-probe] the call failed: {}", error));
            }
        }

        /**
         * Reports, then <b>quits the game</b>.
         *
         * <p>Not tidiness. A client left running holds file handles on the vanilla jars, and the next
         * build then fails with {@code Could not evaluate onlyIf predicate for task
         * ':mc1710:mergeVanillaSidedJars'} — which names a Gradle task and says nothing about a stray
         * process, so it reads as a broken build rather than a live one. Costing two rebuilds to learn
         * that is two more than it should.</p>
         *
         * <p><b>Expect one exception after the verdict.</b> Quitting from inside a client tick leaves the
         * integrated server mid-tick, and it dies with {@code IllegalStateException: Display not
         * created}. It is written AFTER the PASS/FAIL line, on the Server thread, and means nothing —
         * but an unexplained exception in a probe log is exactly the thing that costs somebody an hour,
         * so it is named here rather than left to be rediscovered.</p>
         */
        private void finish(boolean pass) {
            reported = true;
            Minecraft.getMinecraft().shutdown();
            if (pass) {
                CrystalGuiCore.LOGGER.info("[session-probe] PASS — the whole protocol crossed a real "
                        + "Minecraft connection: description request/response, state delta, event, "
                        + "and a server->client call");
            } else {
                CrystalGuiCore.LOGGER.error("[session-probe] FAIL — tree={} delta={} event={} call={}. "
                                + "The first false is the exchange that stalled.",
                        treeArrived, deltaSeen, eventReceived, callAnswered);
            }
        }
    }
}
