package com.crystalgui.mc.legacy;

import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

/** CrystalGUI's variant on a Forge 1.8 to 1.12.2 client, after {@link CrystalGUILegacy}. */
public final class CrystalGUILegacyClient implements VariantEntry {

    @Override
    public void start(Object context) {
        FmlEvents events = (FmlEvents) context;
        events.on("FMLInitializationEvent", event -> System.out.println("[CrystalGUI] legacy client, gui scale "
                + Minecraft.getMinecraft().gameSettings.guiScale));
    }
}
