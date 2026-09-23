package com.crystalgui.mc.modern.example;

import com.crystalgui.app.machine.MachineExample;
import com.crystalgui.mc.modern.client.CgUiKeybinds;
import com.crystalgui.mc.modern.client.CgUiScreen;
import com.crystalgui.mc.modern.net.Connections;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import org.lwjgl.glfw.GLFW;

/**
 * The MC 1.20.x client half of {@link MachineExample}: a key.
 *
 * <p>How this era spells a key binding and where its press is polled. The request itself, and what a
 * refusal means, are the example's.</p>
 */
public final class MachineExampleClientModern {

    public static final KeyMapping OPEN_MACHINE =
            new KeyMapping("key.crystalgui.machine", GLFW.GLFW_KEY_F8, CgUiKeybinds.CATEGORY);

    private MachineExampleClientModern() {}

    private static boolean registered;

    public static synchronized void registerClient() {
        if (registered) return;
        registered = true;
        CgUiKeybinds.add(OPEN_MACHINE);
        LifecycleCrystalGUI.onClientTick(MachineExampleClientModern::poll);
    }

    private static void poll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen != null) return;
        if (!OPEN_MACHINE.consumeClick()) return;

        MachineExample.requestPanel(Connections.client());
        // The desktop is opened either way: it is where the window WILL land, and a screen that
        // appears only on success would flicker for anyone who is refused.
        CgUiScreen.openDesktop();
    }
}
