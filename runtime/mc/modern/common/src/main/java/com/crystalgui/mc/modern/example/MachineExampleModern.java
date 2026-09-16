package com.crystalgui.mc.modern.example;

import com.crystalgui.app.machine.MachineExample;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;

/**
 * The MC 1.20.x server half of {@link MachineExample}: one tick.
 *
 * <p>Content rides the platform's tick rather than subscribing a loader event of its own — otherwise a
 * mod's tick is wired three times and only one copy ever gets debugged.</p>
 */
public final class MachineExampleModern {

    private MachineExampleModern() {}

    public static void registerCommon() {
        LifecycleCrystalGUI.onServerTick(MachineExample.registerServer());
    }
}
