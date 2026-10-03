package com.crystalgui.mc.legacy.example;

import com.crystalgui.app.machine.MachineExample;
import com.crystalgui.mc.legacy.client.CgUiScreen;
import com.crystalgraphics.net.CgNetwork;

import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

/**
 * The Forge 1.8–1.12.2 client half of {@link MachineExample}: a key.
 *
 * <p>How this era spells a key binding and which bus its press arrives on. The request itself, and what
 * a refusal means, are the example's.</p>
 */
public final class MachineExampleClientLegacy {

    private static KeyBinding openPanel;

    private MachineExampleClientLegacy() {
    }

    public static void registerClient() {
        openPanel = new KeyBinding("key.crystalgui.machine", Keyboard.KEY_F8,
                "key.categories.crystalgui");
        ClientRegistry.registerKeyBinding(openPanel);
        FMLCommonHandler.instance().bus().register(new ClientHandler());
    }

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class ClientHandler {

        /** F8 — ask the server for the panel. Asking twice is free: the window names a key. */
        @SubscribeEvent
        public void onKeyInput(InputEvent.KeyInputEvent event) {
            if (Minecraft.getMinecraft().currentScreen != null) return;
            if (openPanel == null || !openPanel.isPressed()) return;

            MachineExample.requestPanel(CgNetwork.client());
            // The desktop is opened either way: it is where the window WILL land, and a screen that
            // appears only on success would flicker for anyone who is refused.
            CgUiScreen.openDesktop();
        }
    }
}
