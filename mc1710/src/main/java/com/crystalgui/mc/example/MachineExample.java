package com.crystalgui.mc.example;

import java.util.LinkedHashMap;
import java.util.Map;

import com.crystalgui.example.machine.session.MachineServer;
import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.mc.net.CgUiConnections;
import com.crystalgui.net.protocol.ProtocolConnection;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

/**
 * The worked example's <b>server half</b>, in game. One {@link MachineServer} per player.
 *
 * <p>{@code com.crystalgui.example} is the stack with no Minecraft in it; this is the loader code that
 * gives it a world. Nothing about the panel changes between the two — the same {@link MachineServer}
 * that runs against a loopback transport in {@code MachineDemo} runs here against a real socket, and
 * this class never mentions a widget.</p>
 *
 * <h3>Why this is a separate class from {@link MachineExampleClient}</h3>
 *
 * <p>Because a dedicated server loads this one and must not load that one, and the split has to be a
 * <b>class boundary</b> rather than an {@code if}. This class was briefly one class holding both
 * halves, with a {@code static KeyBinding} field for the client's key — and a field descriptor
 * resolves at class load, unlike a method-body reference, so a server would have failed on the
 * <em>field</em> while every line of the guarded code was unreachable. Exactly the shape
 * {@code CommonProxy}/{@code ClientProxy} exists to enforce, and the reason {@code :mc1710:serverSmoke}
 * asserts that no client-only class was <em>loaded</em> rather than that nothing threw.</p>
 *
 * <p>So the test for this file is mechanical: <b>no import here may be client-side.</b> Not
 * {@code Minecraft}, not {@code KeyBinding}, not {@code GuiScreen}, and not
 * {@link MachineExampleClient}.</p>
 *
 * <h3>Why the panel opens itself rather than waiting to be asked</h3>
 *
 * <p>Opening on login makes F8 a pure client action — it brings forward a window that already exists,
 * and needs no message of its own. A real mod would open on a block being right-clicked, which is one
 * {@code onCall} away and would obscure the part worth showing.</p>
 *
 * <p>It also demonstrates the property that makes this architecture worth the trouble: the machine has
 * been running, on the server, whether or not anybody had the window open — because the model is not
 * the UI.</p>
 */
public final class MachineExample {

    /** One per player. Keyed by name, which is stable for a session and readable in a log. */
    private static final Map<String, MachineServer> SERVERS = new LinkedHashMap<>();

    private MachineExample() {
    }

    /**
     * Called from {@code CommonProxy.init()}, after {@link CgUiConnections#register()}.
     *
     * <p>Order matters only in the direction that is easy to get wrong: this opens a session on the
     * connection that class holds, so registered earlier it would simply find none.</p>
     */
    public static void registerCommon() {
        FMLCommonHandler.instance().bus().register(new ServerHandler());
    }

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class ServerHandler {

        /**
         * Opens a session for any player who does not have one, then ticks every session.
         *
         * <p>Polling on the tick rather than reacting to the login event, because the connection is
         * not necessarily open when a player logs in — {@link CgUiConnections} opens it from its own
         * handler, and the order between two handlers on one event is not something to depend on.
         * Asking every tick and doing nothing when the answer is null costs a map lookup and cannot
         * be wrong.</p>
         *
         * <p><b>Everything below runs on the server thread</b>, which is why it may touch the server's
         * tree at all. Watch the console: every {@code SERVER} line is on {@code Server thread}, and a
         * line in the wrong column is a bug you can see before it costs you anything.</p>
         */
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.getConfigurationManager() == null) return;

            for (Object each : server.getConfigurationManager().playerEntityList) {
                EntityPlayerMP player = (EntityPlayerMP) each;
                String name = player.getCommandSenderName();
                if (SERVERS.containsKey(name)) continue;

                ProtocolConnection<Object> connection = CgUiConnections.forPlayer(player);
                if (connection == null) continue;

                MachineServer machine = new MachineServer();
                machine.open(connection);
                SERVERS.put(name, machine);
                MachineTrace.log(MachineTrace.SERVER, "session opened for " + name);
            }

            // REQUIRED EVERY TICK. The session is the observer holding this tick's dirty set, so
            // nothing else can flush it -- stop calling this and the panel stays live, answers calls,
            // and never sends another state update. See MachineServer.tick().
            for (MachineServer machine : SERVERS.values()) machine.tick();
        }

        @SubscribeEvent
        public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            MachineServer machine = SERVERS.remove(event.player.getCommandSenderName());
            if (machine == null) return;
            // The connection is going anyway, so this is bookkeeping rather than a message that has to
            // arrive. Saying why is still worth it: on the client a window vanishing has half a dozen
            // possible causes and looks identical for all of them.
            machine.close("the player left");
        }
    }
}
