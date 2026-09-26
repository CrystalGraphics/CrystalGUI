package com.crystalgui.mc.legacy;

import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * CrystalGUI's variant on Forge 1.8 to 1.12.2, both sides. The host arrives with L4; until then it
 * announces which node the bootstrapper chose.
 */
public final class CrystalGUILegacy implements VariantEntry {

    @Override
    public void start(Object context) {
        FmlEvents events = (FmlEvents) context;
        events.on("FMLPreInitializationEvent", event -> ((FMLPreInitializationEvent) event).getModLog()
                .info("CrystalGUI legacy variant on Minecraft " + Loader.instance().getMCVersionString()));
    }
}
