package com.crystalgui.mc.example;

import org.lwjgl.input.Keyboard;

import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.mc.client.CgUiScreen;
import com.crystalgui.mc.net.CgUiConnections;
import com.crystalgui.net.protocol.ProtocolConnection;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * The worked example's <b>client half</b>: a key binding. <b>Press F8.</b>
 *
 * <p>There is nothing to register — the window arrives naming
 * {@link com.crystalgui.example.machine.ui.MachinePanel}, and the engine initialises the class and
 * runs its client half. Where the window <em>goes</em> is {@code CgUiWindowMount}'s answer, once, for
 * every mod.</p>
 */
public final class MachineExampleClient {

    private static KeyBinding openPanel;

    private MachineExampleClient() {
    }

    public static void registerClient() {
        openPanel = new KeyBinding("key.crystalgui.machine", Keyboard.KEY_F8,
                "key.categories.crystalgui");
        ClientRegistry.registerKeyBinding(openPanel);
        // The FML bus, not MinecraftForge.EVENT_BUS — fireKeyInput posts to FMLCommonHandler.bus().
        FMLCommonHandler.instance().bus().register(new ClientHandler());
    }

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class ClientHandler {

        /** F8 — ask the server for the panel. Asking twice is free: the window names a key. */
        @SubscribeEvent
        public void onKeyInput(InputEvent.KeyInputEvent event) {
            if (Minecraft.getMinecraft().currentScreen != null) return;
            if (openPanel == null || !openPanel.isPressed()) return;

            ProtocolConnection<Object> connection = CgUiConnections.client();
            if (connection == null) {
                MachineTrace.log(MachineTrace.CLIENT, "F8 -- not connected to a server yet");
                return;
            }

            MachineTrace.log(MachineTrace.CLIENT, "F8 -- asking the server for a panel");
            // A notification: nobody waits. The window arriving IS the answer.
            connection.notify(MachineExample.OPEN, null);
            CgUiScreen.openDesktop();
        }
    }
}
