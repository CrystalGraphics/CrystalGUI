package com.crystalgui.mc.shared;

import com.crystalgraphics.mc.shared.VariantMixins;

/**
 * CrystalGUI's HUD on MinecraftForge 1.21.6-1.21.7, whose Forge (56, 57) has no HUD event: 50-55
 * and 58+ add a layer through {@code AddGuiOverlayLayersEvent}, and between them there is nothing.
 *
 * <p>Pinned as {@code variant.mixinPlugin} by the Forge node that needs it; the mixin lives in the
 * forge branch's {@code mixin} package.</p>
 */
public final class CrystalGuiForgeMixins extends VariantMixins {

    public CrystalGuiForgeMixins() {
        super("crystalgui", "HudHook");
    }
}
