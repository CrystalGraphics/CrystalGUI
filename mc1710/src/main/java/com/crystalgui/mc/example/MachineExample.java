package com.crystalgui.mc.example;

import com.crystalgui.example.machine.MachineModel;
import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.example.machine.ui.MachinePanel;
import com.crystalgui.net.window.ServerWindows;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The worked example's <b>server half</b>: one shared machine, and the message that asks for a window.
 *
 * <p>{@link MachineModel} is a singleton and ticks with the world, the way a machine block would — it
 * runs whether or not anybody is watching, and every player's panel is a <em>view</em> of the same
 * state: flip the switch on one client and every open panel shows it.</p>
 *
 * <p>The window opens when it is <b>asked</b> for ({@code machine/open}, from the client's F8; a real
 * mod would use a block's right-click — same line, different trigger). Ids, sessions, ticking and
 * teardown on logout all belong to {@link ServerWindows}.</p>
 *
 * <p>Kept separate from {@link MachineExampleClient} because a dedicated server loads this class and
 * must not load that one — so no import here may be client-side.</p>
 */
public final class MachineExample {

    /** What the client sends to ask for a panel. On the connection, not a window: there is no window yet. */
    public static final String OPEN = "machine/open";

    /** ONE machine for the whole server. Every viewer's window mirrors the same object. */
    private static final MachineModel MACHINE = new MachineModel();

    private MachineExample() {
    }

    /** Called from {@code CommonProxy.init()}, after {@code WindowProtocol.register()}. */
    public static void registerCommon() {
        Protocols.contribute("machine", new Protocols.Contributor() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> void bind(ProtocolConnection<T> connection) {
                if (connection.peer() == null) return;   // a client end consumes; it does not serve
                ProtocolConnection<Object> wire = (ProtocolConnection<Object>) connection;
                wire.onNotify(OPEN, payload -> {
                    MachineTrace.log(MachineTrace.SERVER, "the client asked for a panel");
                    // One call. Asking twice brings the existing window forward: the panel names a key.
                    ServerWindows.of(wire).open(MachinePanel.TYPE, MACHINE);
                });
            }
        });
        FMLCommonHandler.instance().bus().register(new ServerHandler());
    }

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class ServerHandler {

        /** The machine advances with the world — no sessions, no player list, no flush. */
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) MACHINE.tick();
        }
    }
}
