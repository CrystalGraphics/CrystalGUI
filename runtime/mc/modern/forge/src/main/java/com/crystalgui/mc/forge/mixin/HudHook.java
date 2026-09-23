package com.crystalgui.mc.forge.mixin;

//? if >=1.21.6 {
/*import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The HUD on Forge 1.21.6-1.21.7, which has no HUD event: once a frame, after vanilla's own HUD.
// Mojang's names at runtime, so no remap. @see com.crystalgui.mc.shared.CrystalGuiForgeMixins
@Mixin(value = Gui.class, remap = false)
public abstract class HudHook {

    @Inject(method = "render", at = @At("TAIL"), require = 1)
    private void crystalgui$paintHud(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        LifecycleCrystalGUI.paintHud();
    }
}
*///?}
