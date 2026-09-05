package com.crystalgui.mc.example;

import com.crystalgui.app.machine.MachineTrace;
import com.crystalgui.app.machine.ui.MachinePanel;
import com.crystalgui.mc.client.CgUiKeybinds1201;
import com.crystalgui.mc.client.CgUiScreen1201;
import com.crystalgui.mc.net.Connections1201;
import com.crystalgui.mc.platform.Lifecycle1201;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.window.ClientWindows;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import org.lwjgl.glfw.GLFW;

/**
 * The worked example's <b>client half</b>: a key binding. <b>Press F8.</b>
 *
 * <p>There is nothing to register for the panel itself -- the window arrives naming
 * {@link MachinePanel}, and the engine initialises the class and runs its client half. Where the window
 * goes is the mount's answer, from the presentation the server asked for.</p>
 */
public final class MachineExampleClient1201 {

    public static final KeyMapping OPEN_MACHINE =
            new KeyMapping("key.crystalgui.machine", GLFW.GLFW_KEY_F8, "key.categories.crystalgui");

    private MachineExampleClient1201() {}

    private static boolean registered;

    public static synchronized void registerClient() {
        if (registered) return;
        registered = true;
        CgUiKeybinds1201.add(OPEN_MACHINE);
        Lifecycle1201.onClientTick(MachineExampleClient1201::poll);
    }

    private static void poll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen != null) return;
        if (!OPEN_MACHINE.consumeClick()) return;

        ProtocolConnection<Object> connection = Connections1201.client();
        if (connection == null) {
            MachineTrace.log(MachineTrace.CLIENT, "F8 -- not connected to a server yet");
            return;
        }

        MachineTrace.log(MachineTrace.CLIENT, "F8 -- asking the server for a panel");
        // A request, so a refusal is something we hear rather than something we wait for. The window
        // still arrives through the ordinary mount path -- `granted` says only whether one is coming.
        ClientWindows.requestOpen(MachinePanel.TYPE, null, granted ->
                MachineTrace.log(MachineTrace.CLIENT,
                        granted ? "the server is opening one" : "the server said no"));

        // Opened either way: it is where the window WILL land, and a desktop that appeared only on
        // success would flicker for anyone who is refused.
        CgUiScreen1201.openDesktop();
    }
}
