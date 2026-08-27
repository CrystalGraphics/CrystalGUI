package com.crystalgui.mc.example;

import org.lwjgl.input.Keyboard;

import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.example.machine.ui.MachinePanel;
import com.crystalgui.example.machine.session.MachineClient;
import com.crystalgui.example.machine.session.MachineWindow;
import com.crystalgui.mc.client.CgUiScreen;
import com.crystalgui.mc.net.CgUiConnections;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.protocol.ProtocolConnection;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * The worked example's <b>client half</b>. <b>Press F8.</b>
 *
 * <h3>How short this is, is the point</h3>
 *
 * <p>One registration line and a key binding. The previous version was two hundred and sixty lines, and
 * every one of them was engine work a mod should never have written: tracking whether the connection
 * had been replaced, tearing down on disconnect, installing a session listener at exactly the right
 * moment (late was silent), building a {@code WindowFrame}, applying a stylesheet, and polling every
 * client tick for "is there a screen yet, is there a window yet".</p>
 *
 * <p>Worse than long, it was <b>wrong in a way nobody would notice</b>: closing the panel destroyed the
 * frame, the per-tick poll saw a destroyed frame on the next tick, and re-wrapped the still-live
 * session's tree in a <em>new</em> one. Close, in the shipped example, meant blink. The server was never
 * told anything, because there was no message with which to tell it.</p>
 *
 * <h3>What each of the two lines does</h3>
 *
 * <ul>
 *   <li>{@code ClientWindows.register(type, factory)} — what this client does <em>locally</em> about a
 *       window of that type. Minecraft's {@code MenuScreens.register}, and optional in a way that one
 *       is not: delete it and the panel still opens, still renders and still reports every event the
 *       server asked for. Only the three buttons this side drives would go quiet.</li>
 *   <li>F8 — <b>a user asking for a UI</b>, which is the direction Minecraft's model has no message
 *       for and which every "right-click to open" actually needs. It sends {@code machine/open} and the
 *       server decides.</li>
 * </ul>
 *
 * <p>Where the window <em>goes</em> is not here either: {@code CgUiWindowMount} answers that once, for
 * every mod, in terms of the desktop {@code CgUiScreen} already owns.</p>
 *
 * <h3>The two threads</h3>
 *
 * <p>Everything here runs on the client thread; everything in {@link MachineExample} runs on the
 * server's. In single player both live in one process, which is exactly the configuration where getting
 * that wrong still works — so the console prints the thread on every line and the two columns should
 * never cross:</p>
 *
 * <pre>
 *   [machine] CLIENT [Client thread] F8 -- asking the server for a panel
 *   [machine] SERVER [Server thread] the client asked for a panel
 *   [machine] CLIENT [Client thread] window mounted
 *   [machine] SERVER [Server thread] event: power -&gt; true
 * </pre>
 */
public final class MachineExampleClient {

    private static KeyBinding openPanel;

    private MachineExampleClient() {
    }

    public static void registerClient() {
        // WHAT THIS CLIENT DOES LOCALLY about a machine panel. Once, at init -- there is no connection
        // to wait for and no session to adopt, because a window type is a fact about this installation
        // rather than about any one wire. @see ClientWindows#register
        ClientWindows.register(MachinePanel.TYPE, MachineClient::new);

        openPanel = new KeyBinding("key.crystalgui.machine", Keyboard.KEY_F8,
                "key.categories.crystalgui");
        ClientRegistry.registerKeyBinding(openPanel);
        // THE FML BUS, not MinecraftForge.EVENT_BUS. fireKeyInput posts to FMLCommonHandler.bus(), and
        // registering on the wrong one compiles, runs, and never fires -- CgUiInput carries the same
        // note for the same reason.
        FMLCommonHandler.instance().bus().register(new ClientHandler());
    }

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class ClientHandler {

        /**
         * F8 — ask for the panel, and show the desktop it will land on.
         *
         * <p>Only reached with no screen up: {@code GuiScreen.allowUserInput} is false while one is
         * open, so FML gates this event out entirely. With the desktop already showing, the panel is
         * reached the way every other window is — from the taskbar.</p>
         *
         * <p>Asking twice is free. The window names a key, so the server brings the existing panel
         * forward instead of building a second one — which keeps its scroll position and whatever is
         * half-typed in it.</p>
         */
        @SubscribeEvent
        public void onKeyInput(InputEvent.KeyInputEvent event) {
            if (Minecraft.getMinecraft().currentScreen != null) return;
            if (openPanel == null || !openPanel.isPressed()) return;

            ProtocolConnection<Object> connection = CgUiConnections.client();
            if (connection == null) {
                // "F8 did nothing" has several causes and they look identical from outside. Say which.
                MachineTrace.log(MachineTrace.CLIENT, "F8 -- not connected to a server yet");
                return;
            }

            MachineTrace.log(MachineTrace.CLIENT, "F8 -- asking the server for a panel");
            // A NOTIFICATION: nobody is waiting. The window arriving IS the answer, and it arrives as a
            // window rather than as a reply -- which is why this is not a request.
            // Says to the server "Hey — the user asked for a machine panel. If they're allowed one, build it and send it to me."
            connection.notify(MachineExample.OPEN, null);

            // Ask for the DESKTOP, not the editor: F6 brings the editor forward whatever state it was
            // left in, which is not what was asked for here. The panel lands on the desktop by itself,
            // in the background, and asks for attention rather than stealing focus.
            CgUiScreen.openDesktop();
        }
    }
}
