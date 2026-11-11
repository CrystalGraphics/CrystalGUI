package com.crystalgui.mc.legacy;

import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.probe.ServerSmoke;

import java.util.Arrays;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.ICrashCallable;

/**
 * CrystalGUI on Forge 1.8–1.12.2, both sides: the crash-report line and where its client-only classes are. Everything a screen needs is {@link CrystalGUILegacyClient}'s, which only a client constructs — so a
 * dedicated server never loads a class naming {@code GuiScreen}.
 *
 * <p>No {@code CgPlatformService} registration: CrystalGraphics owns every one and registered it in its own
 * preInit, which FML runs first ({@code required-after:crystalgraphics}).</p>
 */
public final class CrystalGUILegacy implements VariantEntry {

    private static final String NAME = "CrystalGUI";

    @Override
    public void start(Object context) {
        FmlEvents events = (FmlEvents) context;
        events.on("FMLPreInitializationEvent", event ->
                FMLCommonHandler.instance().registerCrashCallable(new ICrashCallable() {
                    @Override public String getLabel() { return CrashVariant.label(NAME); }
                    @Override public String call() { return CrashVariant.report(CrystalGUILegacy.class); }
                }));
        // The jar relocates this root, so it is read off the class. .probe too: every class in there is client-only.
        String root = CrystalGUILegacy.class.getPackage().getName();
        events.on("FMLInitializationEvent", event -> CgPlatform.provide(ServerSmoke.FOOTPRINT,
                new ServerSmoke.Footprint(CrystalGUILegacy.class,
                        Arrays.asList(root + ".probe"),
                        Arrays.asList(root + ".CrystalGUILegacyClient"))));
    }
}
