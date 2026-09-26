package com.crystalgui.mc.legacy.lang;

import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;

import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * The language mod's variant on Forge 1.8 to 1.12.2. The script service arrives with L5; until then the
 * mod loads, claims its versions and installs nothing.
 */
public final class CrystalGuiLanguageLegacy implements VariantEntry {

    @Override
    public void start(Object context) {
        FmlEvents events = (FmlEvents) context;
        events.on("FMLPreInitializationEvent", event -> ((FMLPreInitializationEvent) event).getModLog()
                .info("CrystalGUI Language legacy variant: no script service on this version yet"));
    }
}
