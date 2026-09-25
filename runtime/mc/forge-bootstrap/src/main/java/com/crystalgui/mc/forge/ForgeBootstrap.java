package com.crystalgui.mc.forge;

import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.ForgeStart;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

/**
 * CrystalGUI's one {@code @Mod} class for every Forge the jar runs on — modern Forge and legacy FML both
 * scan for this annotation, so a second one would be a second mod of one id. It selects the variant for
 * the running version and constructs it ({@link ForgeStart}).
 *
 * <p>The {@code @Mod.EventHandler} methods are legacy FML's lifecycle, which reaches only this instance;
 * each forwards to the variant. {@code dependencies} is legacy FML's ordering — modern Forge reads it
 * from {@code mods.toml}.</p>
 */
@Mod(value = ForgeBootstrap.MODID, modid = ForgeBootstrap.MODID, dependencies = "required-after:crystalgraphics")
public final class ForgeBootstrap {

    public static final String MODID = "crystalgui";

    private final FmlEvents legacy = ForgeStart.start(ForgeBootstrap.class, MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ForgeStart.fire(legacy, event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ForgeStart.fire(legacy, event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        ForgeStart.fire(legacy, event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        ForgeStart.fire(legacy, event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        ForgeStart.fire(legacy, event);
    }
}
