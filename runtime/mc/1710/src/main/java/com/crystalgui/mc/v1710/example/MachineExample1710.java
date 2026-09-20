package com.crystalgui.mc.v1710.example;

import com.crystalgui.app.machine.MachineExample;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The MC 1.7.10 server half of {@link MachineExample}: one tick.
 *
 * <p>Which bus, and that {@code @SubscribeEvent} is not honoured on statics. That is the whole of what
 * this era contributes to the example.</p>
 */
public final class MachineExample1710 {

    private static Runnable machineTick;

    private MachineExample1710() {
    }

    /** Called from {@code CommonProxy.init()}, after {@code WindowProtocol.register()}. */
    public static void registerCommon() {
        machineTick = MachineExample.registerServer();
        FMLCommonHandler.instance().bus().register(new ServerHandler());
    }

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class ServerHandler {

        /** The machine advances with the world — no sessions, no player list, no flush. */
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END && machineTick != null) machineTick.run();
        }
    }
}
