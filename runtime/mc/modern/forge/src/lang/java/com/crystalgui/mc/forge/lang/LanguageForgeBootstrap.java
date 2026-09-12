package com.crystalgui.mc.forge.lang;

import com.crystalgraphics.mc.shared.FmlVersion;
import com.crystalgraphics.mc.shared.VariantBootstrap;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * The one {@code @Mod} class the language jar carries for Forge, whatever version it runs on.
 *
 * <p>Its own mod id, its own {@code variants.json}: the language stack is a second mod from the same
 * source tree, so it selects a variant exactly as the host does and shares nothing but the selector
 * itself. @see com.crystalgui.mc.forge.ForgeBootstrap</p>
 */
@Mod(LanguageForgeBootstrap.MODID)
public final class LanguageForgeBootstrap {

    public static final String MODID = "crystalgui_language";

    public LanguageForgeBootstrap() {
        VariantBootstrap.startCommon(LanguageForgeBootstrap.class, MODID, "forge",
                FmlVersion.of(FMLLoader.class), FMLJavaModLoadingContext.get());
    }
}
