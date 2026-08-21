package com.crystalgui.mc.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.protocol.ProtocolConnection;
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

/**
 * Does the <em>protocol</em> work over the real connection lifecycle? — {@code -PcgSessionProbe}.
 *
 * <p>{@link CgUiNetProbe} answers a lower question and owns the channel to do it: it echoes byte arrays,
 * proving Forge's channel, FML's discriminators, the Netty thread, the frame ceiling and player routing.
 * This one proves everything above that, and — since Phase 4 A4 — <b>proves it on the path that
 * ships</b>: it opens no multiplexer, installs no inbound handler and pumps nothing. It takes the
 * connections {@link CgUiConnections} opened on join and puts a session on each end.</p>
 *
 * <p>That is why the two probes have separate flags. A probe that builds its own transport tests the
 * engine; a probe that borrows the real one tests the wiring, and the wiring is what had never existed.</p>
 *
 * <table>
 *   <tr><th>#</th><th>Exchange</th><th>Kinds exercised</th></tr>
 *   <tr><td>1</td><td>{@code open()} → client rebuilds the tree</td>
 *       <td>notification {@code ui/openWindow}, then a <b>request</b> {@code ui/description} and its
 *           <b>response</b></td></tr>
 *   <tr><td>2</td><td>server mutates a widget → client sees it</td><td>notification {@code ui/stateDelta}</td></tr>
 *   <tr><td>3</td><td>client presses → server's lambda runs</td><td>notification {@code ui/event}</td></tr>
 *   <tr><td>4</td><td>server calls the client and gets an answer</td>
 *       <td>request/response, in the direction easiest to get wrong</td></tr>
 * </table>
 *
 * <h3>Two handlers, because there are two threads</h3>
 *
 * <p>Server-side work — opening the session, mutating the tree, issuing the call, flushing — runs on
 * {@code ServerTickEvent}. Client-side work — reading the rebuilt tree, pressing a widget — runs on
 * {@code ClientTickEvent}. The first version did both from the client tick and happened to work, because
 * a single-player integrated server shares the process; it was still touching a tree from the wrong
 * thread, and the point of this probe is now to exercise what production does rather than what
 * survives.</p>
 */
public final class CgUiSessionProbe {

    private static final boolean ENABLED = Boolean.getBoolean("crystalgui.session.probe");

    private static final int WINDOW_ID = 4242;

    /** How long to wait before calling it a failure, in client ticks. */
    private static final int DEADLINE_TICKS = 20 * 40;

    private static volatile ServerUiSession<Object> server;
    private static volatile ClientUiSession<Object> client;

    private static Slider serverSlider;

    private static volatile boolean treeArrived;
    private static volatile boolean deltaSeen;
    private static volatile boolean eventReceived;
    private static volatile boolean callAnswered;

    private static volatile boolean deltaSent;
    private static volatile boolean eventSent;
    private static volatile boolean callSent;
    private static volatile boolean reported;

    private static int clientTicks;

    private CgUiSessionProbe() {
    }

    public static void register() {
        if (!ENABLED) return;
        FMLCommonHandler.instance().bus().register(new Handler());
        CrystalGuiCore.LOGGER.info("[session-probe] armed; waiting for CgUiConnections to open a pair");
    }

    // ── The two ends ────────────────────────────────────────────────────────

    /** <b>Server thread.</b> Opens the window once the player has a connection. */
    private static void openServer(ProtocolConnection<Object> connection) {
        UIElement root = new UIElement();
        root.addChild(new UIText("hello from the server"));
        Button button = new Button("Press me");
        root.addChild(button);
        serverSlider = new Slider();
        serverSlider.setRange(0f, 1f);
        root.addChild(serverSlider);

        // Rides the connection: no transport and no router of its own, sharing the wire with every
        // contributor Protocols bound onto it.
        ServerUiSession<Object> session = new ServerUiSession<>(WINDOW_ID, root, connection);
        session.onActivate(button, ctx -> {
            eventReceived = true;
            CrystalGuiCore.LOGGER.info("[session-probe] 3/4 the server's own lambda ran for a client press");
        });
        session.open();
        server = session;
        CrystalGuiCore.LOGGER.info("[session-probe] server session opened on the real connection, hash={}",
                session.descHash());
    }

    /** <b>Client thread.</b> */
    private static void openClient(ProtocolConnection<Object> connection) {
        ElementRegistry.bootstrapBuiltins();
        ClientUiSession<Object> session = new ClientUiSession<>(connection);
        session.onWindowOpened(root -> {
            treeArrived = root != null;
            CrystalGuiCore.LOGGER.info("[session-probe] 1/4 tree rebuilt on the client: {} children",
                    root == null ? -1 : root.getChildren().size());
        });
        session.onCall("probe/ping", (args, respond) -> {
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putString("pong", args.getString("from", "?"));
            respond.ok(out);
        });
        client = session;
        CrystalGuiCore.LOGGER.info("[session-probe] client session attached to the real connection");
    }

    public static final class Handler {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (!ENABLED || event.phase != TickEvent.Phase.END || reported) return;

            if (server == null) {
                MinecraftServer mc = MinecraftServer.getServer();
                if (mc == null || mc.getConfigurationManager() == null
                        || mc.getConfigurationManager().playerEntityList.isEmpty()) {
                    return;
                }
                EntityPlayerMP player =
                        (EntityPlayerMP) mc.getConfigurationManager().playerEntityList.get(0);
                ProtocolConnection<Object> connection = CgUiConnections.forPlayer(player);
                if (connection == null) return;
                openServer(connection);
                return;
            }

            // Riding a connection, so this only flushes what the tree changed -- CgUiConnections already
            // drained and expired on its own ServerTickEvent.
            server.tick();

            if (!treeArrived) return;

            if (!deltaSent) {
                deltaSent = true;
                serverSlider.setValue(0.75f);
                CrystalGuiCore.LOGGER.info("[session-probe] server moved the slider to 0.75");
                return;
            }
            if (!deltaSeen || !eventReceived) return;

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

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (!ENABLED || event.phase != TickEvent.Phase.END || reported) return;

            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.thePlayer == null) return;
            // An open screen pauses the integrated server, and a paused server never drains its inbound
            // queue. @see CgUiNetProbe, which cost eleven runs to learn it.
            if (mc.currentScreen != null) {
                mc.displayGuiScreen(null);
                return;
            }

            if (client == null) {
                ProtocolConnection<Object> connection = CgUiConnections.client();
                if (connection == null) {
                    if (++clientTicks > DEADLINE_TICKS) {
                        finish(false, "no client connection was ever opened");
                    }
                    return;
                }
                openClient(connection);
                return;
            }

            clientTicks++;

            if (treeArrived && deltaSent && !deltaSeen) {
                UIElement mirrored = client.root().getChildren().get(2);
                if (mirrored instanceof Slider
                        && Math.abs(((Slider) mirrored).getValue() - 0.75f) < 1e-4f) {
                    deltaSeen = true;
                    CrystalGuiCore.LOGGER.info("[session-probe] 2/4 the delta arrived and applied");
                }
            }

            if (deltaSeen && !eventSent) {
                UIElement mirrored = client.root().getChildren().get(1);
                if (mirrored instanceof Button) {
                    eventSent = true;
                    // The REAL widget, because what reports is a listener the client attached from the
                    // description. Calling report() directly would skip the half that can be wrong.
                    ((Button) mirrored).onPressed.emit();
                    CrystalGuiCore.LOGGER.info("[session-probe] client pressed the mirrored button");
                }
            }

            if (clientTicks % 40 == 0) {
                CrystalGuiCore.LOGGER.info("[session-probe] tick {} — tree={} delta={} event={} call={} "
                                + "(connections open: {})",
                        clientTicks, treeArrived, deltaSeen, eventReceived, callAnswered,
                        CgUiConnections.openConnections());
            }
            if (treeArrived && deltaSeen && eventReceived && callAnswered) {
                finish(true, "");
            } else if (clientTicks > DEADLINE_TICKS) {
                finish(false, "timed out");
            }
        }

        /**
         * Reports, then quits.
         *
         * <p>A client left running holds file handles on the vanilla jars, and the next build then fails
         * with {@code Could not evaluate onlyIf predicate for task ':mc1710:mergeVanillaSidedJars'} —
         * which names a Gradle task and says nothing about a live process.</p>
         *
         * <p><b>Expect one exception after the verdict.</b> Quitting from inside a tick leaves the
         * integrated server mid-tick and it dies with {@code IllegalStateException: Display not created}.
         * It is written after the PASS/FAIL line and means nothing.</p>
         */
        private void finish(boolean pass, String why) {
            reported = true;
            if (pass) {
                CrystalGuiCore.LOGGER.info("[session-probe] PASS — the whole protocol crossed a real "
                        + "Minecraft connection, over the connection lifecycle that ships: description "
                        + "request/response, state delta, event, and a server->client call");
            } else {
                CrystalGuiCore.LOGGER.error("[session-probe] FAIL ({}) — tree={} delta={} event={} call={}. "
                                + "The first false is the exchange that stalled.",
                        why, treeArrived, deltaSeen, eventReceived, callAnswered);
            }
            Minecraft.getMinecraft().shutdown();
        }
    }
}
