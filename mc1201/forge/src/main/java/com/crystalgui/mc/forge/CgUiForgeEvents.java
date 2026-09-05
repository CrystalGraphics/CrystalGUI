package com.crystalgui.mc.forge;

import com.crystalgui.mc.client.CgUiHud1201;
import com.crystalgui.mc.client.CgUiKeybinds1201;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static com.crystalgui.mc.platform.CrystalGUI1201.MODID;

/** Registration only: the keys live in {@code CgUiKeybinds1201}, the screen in {@code CgUiScreen1201}. */
public final class CgUiForgeEvents {

    private CgUiForgeEvents() {}

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(CgUiKeybinds1201.OPEN_EDITOR);
            event.register(CgUiKeybinds1201.OPEN_DESKTOP);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {
        private ForgeBus() {}

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) CgUiKeybinds1201.tick();
        }

        // ── Pinned windows over the HUD and over a foreign screen ────────────────────────────────

        @SubscribeEvent
        public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
            CgUiHud1201.paint();
        }

        @SubscribeEvent
        public static void onScreenRender(ScreenEvent.Render.Post event) {
            CgUiHud1201.paint();
        }

        @SubscribeEvent
        public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
            if (CgUiHud1201.offerMouse(event.getButton(), true, 0f)) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
            if (CgUiHud1201.offerMouse(event.getButton(), false, 0f)) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
            if (CgUiHud1201.offerMouse(-1, false, (float) event.getScrollDelta())) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
            if (CgUiHud1201.offerKey(event.getKeyCode(), (char) 0, true)) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
            if (CgUiHud1201.offerKey(0, event.getCodePoint(), true)) event.setCanceled(true);
        }
    }
}
