package com.crystalgui.mc.modern.example;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.app.machine.MachineExample;
import com.crystalgui.mc.modern.client.CgUiInput;
import com.crystalgui.mc.modern.client.CgUiKeybinds;
import com.crystalgui.mc.modern.client.CgUiScreen;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;

import net.minecraft.client.KeyMapping;
import com.crystalgui.mc.modern.client.ClientGame;
import net.minecraft.client.Minecraft;

/**
 * The modern client half of {@link MachineExample}: a key.
 *
 * <p>How this era spells a key binding and where its press is polled. The request itself, and what a
 * refusal means, are the example's.</p>
 */
public final class MachineExampleClientModern {

    public static final KeyMapping OPEN_MACHINE =
            new KeyMapping("key.crystalgui.machine", CgUiInput.hostKey(CgKeyCodes.KEY_F8), CgUiKeybinds.CATEGORY);

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
        if (mc == null || ClientGame.screen(mc) != null) return;
        if (!OPEN_MACHINE.consumeClick()) return;

        MachineExample.requestPanel(CgNetwork.client());
        // The desktop is opened either way: it is where the window WILL land, and a screen that
        // appears only on success would flicker for anyone who is refused.
        CgUiScreen.openDesktop();
    }
}
