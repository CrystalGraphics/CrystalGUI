package com.crystalgui.mc.example;

import com.crystalgui.app.machine.MachineModel;
import com.crystalgui.app.machine.MachineTrace;
import com.crystalgui.app.machine.ui.MachinePanel;
import com.crystalgui.net.window.ServerWindows;

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

    /** ONE machine for the whole server. Every viewer's window mirrors the same object. */
    private static final MachineModel MACHINE = new MachineModel();

    private MachineExample() {
    }

    /** Called from {@code CommonProxy.init()}, after {@code WindowProtocol.register()}. */
    public static void registerCommon() {
        /*
         * DECLARED OPENABLE, rather than a hand-rolled notification.
         *
         * This used to be `wire.onNotify("machine/open", ...)`, with a comment saying the window
         * arriving IS the answer. True while it always succeeded -- and indistinguishable from a lost
         * packet when it did not, which is the whole problem: the player presses F8 and nothing
         * happens, forever, with nothing to look at.
         *
         * The resolver is the authority. It gets the viewer and may answer null, which is an ordinary
         * refusal rather than an error. This one grants unconditionally because there is exactly one
         * machine on the server and everybody may see it; a real mod reads a position out of `args`
         * and RE-DERIVES from it -- checking the block is loaded and the player is near it -- because
         * anything a client sends is a claim rather than a fact.
         */
        ServerWindows.openable(MachinePanel.TYPE, (viewer, args) -> {
            MachineTrace.log(MachineTrace.SERVER, "a client asked for a panel");
            return MACHINE;
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
