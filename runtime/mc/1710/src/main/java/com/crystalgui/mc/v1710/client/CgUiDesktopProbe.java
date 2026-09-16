package com.crystalgui.mc.v1710.client;

import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.probe.DesktopProbe;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The MC 1.7.10 half of {@link DesktopProbe} — <b>which this era did not have</b>.
 *
 * <p>The scripted desktop run was 1.20.x's alone: minimise, restore mid-animation, the jump list, pin,
 * click through an overlay, click with the mouse grabbed, photographing each step. None of it is a fact
 * about a Minecraft version, and 1.7.10 is the era where the window animations and the overlay hit test
 * are hardest — LWJGL2, a bottom-left origin and a mixin standing in for an event that does not exist.
 * It is the one that most needed a scripted run and the one that never had one.</p>
 *
 * <pre>{@code
 * ./gradlew :runtime:mc:1710:runClient -Dcrystalgui.clientProbe=true
 * }</pre>
 */
public final class CgUiDesktopProbe {

    private CgUiDesktopProbe() {
    }

    public static void register() {
        if (!DesktopProbe.ENABLED) return;
        FMLCommonHandler.instance().bus().register(new Handler());
    }

    private static final DesktopProbe.Host HOST = new DesktopProbe.Host() {

        @Override
        public boolean inWorld() {
            Minecraft mc = Minecraft.getMinecraft();
            return mc.theWorld != null && mc.thePlayer != null;
        }

        @Override
        public void openDesktop() {
            CgUiScreen.openEditor();
        }

        /**
         * Read off the debug line, because 1.7.10 exposes the counter nowhere else.
         *
         * <p>{@code Minecraft.debugFPS} is private and there is no accessor; what IS public is the
         * {@code debug} String it is formatted into, as {@code "N fps, M chunk updates"}. Parsing a
         * display string is ugly and is still better than an access transformer for one diagnostic
         * line -- and it degrades to -1 rather than throwing if that format ever changes.</p>
         */
        @Override
        public int fps() {
            String debug = Minecraft.getMinecraft().debug;
            int space = debug.indexOf(' ');
            if (space <= 0) return -1;
            try {
                return Integer.parseInt(debug.substring(0, space));
            } catch (NumberFormatException notTheFormatWeExpected) {
                return -1;
            }
        }

        @Override
        public void openForeignScreen() {
            Minecraft.getMinecraft().displayGuiScreen(new GuiChat());
        }

        @Override
        public void closeScreen() {
            Minecraft.getMinecraft().displayGuiScreen(null);
        }

        /**
         * {@code setIngameFocus} rather than {@code Mouse.setGrabbed}: it is what the game itself calls,
         * so it also closes the screen and restores the in-game input state the grab is supposed to mean.
         */
        @Override
        public void grabMouse() {
            Minecraft.getMinecraft().setIngameFocus();
        }

        @Override
        public boolean mouseGrabbed() {
            return Mouse.isGrabbed();
        }

        /** LWJGL2's cursor origin is BOTTOM-left, and the probe speaks top-left. */
        @Override
        public void movePointerTo(int surfaceX, int surfaceY) {
            Mouse.setCursorPosition(surfaceX, Minecraft.getMinecraft().displayHeight - surfaceY);
        }

        /**
         * The bound framebuffer, read back — which is what Minecraft is about to present.
         *
         * <p>The same capture the unattended run takes, for the same reason: it is the pixels a person
         * would photograph. @see CgUiAutoTest</p>
         */
        @Override
        public void shoot(String fileName) {
            Minecraft mc = Minecraft.getMinecraft();
            CgUiAutoTest.capture(mc.displayWidth, mc.displayHeight, fileName);
        }

        @Override
        public void quit() {
            Minecraft.getMinecraft().shutdown();
        }

        @Override
        public boolean offerMouse(int button, boolean pressed) {
            return CgUiHud.offerMouse(button, pressed, 0f);
        }

        @Override
        public void offerKey(int platformKey, char typed, boolean pressed) {
            CgUiHud.offerKey(platformKey, typed, pressed);
        }

        /** LWJGL2's code, which is not GLFW's. */
        @Override
        public int keyZ() {
            return Keyboard.KEY_Z;
        }

        @Override
        public DesktopPresentation presentation() {
            return CgUiHud.presentation();
        }
    };

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class Handler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            DesktopProbe.tick(HOST);
        }
    }
}
