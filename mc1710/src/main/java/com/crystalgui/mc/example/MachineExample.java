package com.crystalgui.mc.example;

import java.util.LinkedHashMap;
import java.util.Map;

import com.crystalgui.example.machine.MachineModel;
import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.example.machine.session.MachineWindow;
import com.crystalgui.mc.net.CgUiConnections;
import com.crystalgui.net.host.ServerUiHost;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The worked example's <b>server half</b>, in game.
 *
 * <p>{@code com.crystalgui.example} is the stack with no Minecraft in it; this is the loader code that
 * gives it a world. Nothing about the panel changes between the two — the same {@link MachineWindow}
 * that runs against a loopback transport in {@code MachineDemo} runs here against a real socket, and
 * this class never mentions a widget.</p>
 *
 * <h3>What this class used to be, and why none of it is here</h3>
 *
 * <p>It walked {@code playerEntityList} on every server tick, checked a name-keyed map of its own,
 * constructed a session for anybody missing, ticked every session it held, and subscribed a logout
 * event to close them. That is one poll loop, one map and two handlers <b>per mod</b> — twenty mods
 * with GUIs on a twenty-player server would ask twenty times a second a question
 * {@link CgUiConnections} answers exactly once, at {@code PlayerLoggedInEvent}.</p>
 *
 * <p>{@link ServerUiHost} owns all of it now. What is left below is the two things that are genuinely
 * this example's: the machines, and the message that asks for a window.</p>
 *
 * <h3>The machine is world state; the window is a view of it</h3>
 *
 * <p>{@link MachineModel} lives in a map here and ticks with the world, exactly as a TileEntity would.
 * So a machine goes on running whether or not anybody has the panel open — which is the property that
 * makes a server-authoritative UI worth the trouble, and which the earlier version quietly did not
 * have, because the model ticked inside the session and stopped existing when the viewer left.</p>
 *
 * <h3>The window is opened when it is ASKED for</h3>
 *
 * <p>{@code machine/open}, from the client's F8. A real mod would open on a block being right-clicked,
 * which is the same one line from a different trigger. Opening on login was the old shape and hid the
 * interesting half: a client <em>asking</em> for a UI is the direction Minecraft's own model has no
 * message for, and it is what every "right-click to open" actually needs.</p>
 *
 * <p>The window's {@link MachineWindow#KEY key} makes repeat asks free: the second F8 brings the
 * existing panel forward rather than stacking a second one, keeping its scroll position and whatever is
 * half-typed in it.</p>
 *
 * <h3>Why this is a separate class from {@link MachineExampleClient}</h3>
 *
 * <p>Because a dedicated server loads this one and must not load that one, and the split has to be a
 * <b>class boundary</b> rather than an {@code if}. This was briefly one class holding both halves, with
 * a {@code static KeyBinding} field for the client's key — and a field descriptor resolves at class
 * load, unlike a method-body reference, so a server would have failed on the <em>field</em> while every
 * line of the guarded code was unreachable. Exactly the shape {@code CommonProxy}/{@code ClientProxy}
 * exists to enforce, and the reason {@code :mc1710:serverSmoke} asserts that no client-only class was
 * <em>loaded</em> rather than that nothing threw.</p>
 *
 * <p>So the test for this file is mechanical: <b>no import here may be client-side.</b> Not
 * {@code Minecraft}, not {@code KeyBinding}, not {@code GuiScreen}, and not
 * {@link MachineExampleClient}.</p>
 */
public final class MachineExample {

    /** What the client sends to ask for a panel. Its own method, on the connection: not window-scoped,
     * because there is no window yet — that is the whole point of it. */
    public static final String OPEN = "machine/open";

    /**
     * One machine per peer, and they run whether or not anybody is watching.
     *
     * <p>Keyed by the connection's peer, which is stable for that player's whole session
     * ({@code Mc1710Peer}). Dropped when the connection closes — a real mod would keep a machine in the
     * world rather than in a map, which is the one place this example is shaped by being an example.</p>
     */
    private static final Map<Object, MachineModel> MACHINES = new LinkedHashMap<>();

    private MachineExample() {
    }

    /**
     * Called from {@code CommonProxy.init()}, after {@code UiHosts.register()}.
     *
     * <p>A contributor, like the workspace: it is told about every connection opened afterwards, and it
     * never sees a player list, a tick or a login event.</p>
     */
    public static void registerCommon() {
        Protocols.contribute("machine", new Protocols.Contributor() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> void bind(ProtocolConnection<T> connection) {
                // A CLIENT connection has no peer and hosts nothing: it is the consumer. Same
                // discriminator CgUiWorkspaceHost reads, and the reason a single-player process does not
                // serve itself from its own client end.
                Object peer = connection.peer();
                if (peer == null) return;

                ProtocolConnection<Object> wire = (ProtocolConnection<Object>) connection;
                MACHINES.put(peer, new MachineModel());

                wire.onNotify(OPEN, payload -> {
                    MachineModel machine = MACHINES.get(peer);
                    if (machine == null) return;
                    MachineTrace.log(MachineTrace.SERVER, "the client asked for a panel");
                    // ONE CALL. The id, the session, the description, the tick and every way this window
                    // can end are the host's. Asking twice brings the first one forward, because the
                    // window names a key. @see ServerUiHost#open
                    ServerUiHost.of(wire).open(new MachineWindow(machine));
                });

                // The machine goes when its owner does. A real mod's machine lives in the world and
                // would outlive the player entirely, which is the more useful shape and needs no hook.
                wire.onClosed(reason -> MACHINES.remove(peer));
            }
        });
        FMLCommonHandler.instance().bus().register(new ServerHandler());
    }

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class ServerHandler {

        /**
         * Advances every machine. <b>Not a session in sight.</b>
         *
         * <p>This is the one tick handler the example needs, and it is about machines rather than about
         * windows: no player list, no map of sessions, no flush. A window that is open mirrors its
         * machine from {@code MachineWindow.tick}, which {@link ServerUiHost} calls from the
         * connection's own tick, and the flush after it belongs to the session.</p>
         *
         * <p><b>Everything here runs on the server thread</b>, which is why it may touch the world at
         * all. Watch the console: every {@code SERVER} line is on {@code Server thread}, and a line in
         * the wrong column is a bug you can see before it costs you anything.</p>
         */
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            for (MachineModel machine : MACHINES.values()) machine.tick();
        }
    }
}
