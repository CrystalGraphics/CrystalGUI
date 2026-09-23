package com.crystalgui.mc.neoforge;

import com.crystalgraphics.mc.shared.VariantBootstrap;
import com.crystalgraphics.mc.shared.FmlVersion;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import com.crystalgraphics.mc.shared.FmlSide;
import net.neoforged.fml.loading.FMLLoader;

import static com.crystalgui.mc.modern.platform.CrystalGUI.MODID;

/**
 * The one {@code @Mod} class the jar carries for NeoForge, whatever Minecraft version it runs on.
 *
 * <p>NeoForge's scanner reads every class in the jar, so two variants both bearing
 * {@code @Mod("crystalgui")} are two mods of one id and it refuses to load rather than choosing. The
 * variants carry none; this reads {@code variants.json} and constructs the one whose range covers
 * the running version.</p>
 *
 * <p>The event bus NeoForge hands this constructor is passed straight through as the entry's
 * {@code context}. Nothing here names a Minecraft type, and nothing in it changes when a version is
 * added.</p>
 */
@Mod(MODID)
public final class NeoForgeBootstrap {

    public NeoForgeBootstrap(IEventBus modBus) {
        String minecraft = FmlVersion.of(FMLLoader.class);
        VariantBootstrap.startCommon(NeoForgeBootstrap.class, MODID, "neoforge", minecraft, modBus);
        if (FmlSide.isClient(FMLLoader.class)) {
            VariantBootstrap.startClient(NeoForgeBootstrap.class, MODID, "neoforge", minecraft, modBus);
        }
    }
}
