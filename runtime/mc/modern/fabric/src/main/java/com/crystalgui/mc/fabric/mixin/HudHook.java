package com.crystalgui.mc.fabric.mixin;

//? if <1.15 {
/*import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The HUD on Fabric 1.14, whose Fabric API has no HudRenderCallback: once a frame, after vanilla's.
// Named both ways, since the dev run is Mojang-named and production intermediary.
// @see com.crystalgui.mc.shared.CrystalGuiFabricMixins
@Mixin(value = Gui.class, remap = false)
public abstract class HudHook {

    @Inject(method = {"render", "method_1753"}, at = @At("TAIL"), require = 1)
    private void crystalgui$paintHud(float partialTick, CallbackInfo ci) {
        LifecycleCrystalGUI.paintHud();
    }
}
*///?}
