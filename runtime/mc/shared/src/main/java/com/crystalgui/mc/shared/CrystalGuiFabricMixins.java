package com.crystalgui.mc.shared;

import com.crystalgraphics.mc.shared.VariantMixins;

/**
 * CrystalGUI's HUD on Fabric 1.14, whose Fabric API has no {@code HudRenderCallback}.
 *
 * <p>Pinned as {@code variant.mixinPlugin} by the Fabric node that needs it; the mixin lives in the
 * fabric branch's {@code mixin} package.</p>
 */
public final class CrystalGuiFabricMixins extends VariantMixins {

    public CrystalGuiFabricMixins() {
        super("crystalgui", "HudHook");
    }
}
