package com.crystalgui.mc.neoforge.lang;

import com.crystalgraphics.mc.shared.FmlVersion;
import com.crystalgraphics.mc.shared.VariantBootstrap;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;

/**
 * The one {@code @Mod} class the language jar carries for NeoForge, whatever version it runs on: what makes it a mod
 * NeoForge lists, so CrystalGraphics reads its lifecycle listener. It runs nothing itself
 * ({@code docs/CGUI_LANGUAGE_STACK.md} § 1a).
 *
 * @see com.crystalgui.mc.neoforge.NeoForgeBootstrap for the host's twin
 */
@Mod(LanguageNeoForgeBootstrap.MODID)
public final class LanguageNeoForgeBootstrap {

    public static final String MODID = "crystalgui_language";

    public LanguageNeoForgeBootstrap(IEventBus modBus) {
        VariantBootstrap.startCommon(LanguageNeoForgeBootstrap.class, MODID, "neoforge",
                FmlVersion.of(FMLLoader.class), modBus);
    }
}
