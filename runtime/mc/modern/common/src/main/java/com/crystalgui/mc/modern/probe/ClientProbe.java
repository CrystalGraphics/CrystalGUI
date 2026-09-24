package com.crystalgui.mc.modern.probe;

import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;
import com.crystalgui.mc.modern.client.CgUiHud;
import com.crystalgui.mc.modern.client.CgUiScreen;
import com.crystalgui.probe.DesktopProbe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;

import org.lwjgl.glfw.GLFW;

/**
 * The MC 1.20.x half of {@link DesktopProbe}: thirteen one-liners.
 *
 * <p>The routine itself — minimise, restore mid-animation, the jump list, pin, click through an
 * overlay, click with the mouse grabbed — is {@code core}'s. It used to be 353 lines here, and 1.7.10
 * had no version of it at all.</p>
 *
 * <pre>{@code
 * ./gradlew :runtime:mc:modern:forge:1.20.1:runClient -Dcrystalgui.clientProbe=true
 * }</pre>
 *
 * <p>Screenshots land in {@code runs/client/screenshots} as {@code cgui-NN-step.png}. Add
 * {@code -Dcrystalgui.layer.probe=true} for GL readbacks alongside them.</p>
 */
public final class ClientProbe {

    /** @see DesktopProbe#ENABLED */
    public static final boolean ENABLED = DesktopProbe.ENABLED;

    private ClientProbe() {}

    /** Called once per client tick. @see DesktopProbe#tick */
    public static void tick() {
        DesktopProbe.tick(HOST);
    }

    private static final DesktopProbe.Host HOST = new DesktopProbe.Host() {

        @Override
        public boolean inWorld() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.level != null && mc.player != null;
        }

        @Override
        public void openDesktop() {
            CgUiScreen.openEditor();
        }

        @Override
        public int fps() {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return -1;
            // getFps arrived in 1.19.3; before it the count leads the debug string, "60 fps T: ...".
            //? if >=1.19.3 {
            return mc.getFps();
            //?} else {
            /*String fps = mc.fpsString;
            int end = fps == null ? -1 : fps.indexOf(' ');
            try {
                return end > 0 ? Integer.parseInt(fps.substring(0, end)) : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
            *///?}
        }

        @Override
        public void openForeignScreen() {
            Minecraft mc = Minecraft.getInstance();
            //? if >=1.21.9 {
            /*if (mc != null) mc.setScreen(new ChatScreen("", false));
            *///?} else {
            if (mc != null) mc.setScreen(new ChatScreen(""));
            //?}
        }

        @Override
        public void closeScreen() {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(null);
        }

        @Override
        public void grabMouse() {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.mouseHandler != null) mc.mouseHandler.grabMouse();
        }

        @Override
        public boolean mouseGrabbed() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed();
        }

        @Override
        public void movePointerTo(int surfaceX, int surfaceY) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getWindow() == null) return;
            GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), surfaceX, surfaceY);
        }

        /** Minecraft's own main target, which is where our composite lands. */
        @Override
        public void shoot(String fileName) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            //? if >=1.21.6 {
            /*Screenshot.grab(mc.gameDirectory, fileName, mc.getMainRenderTarget(), 1, message -> { });
            *///?} elif >=1.17.1 {
            Screenshot.grab(mc.gameDirectory, fileName, mc.getMainRenderTarget(), message -> { });
            //?} else {
            /*// Before 1.17.1 grab also takes the frame's size.
            Screenshot.grab(mc.gameDirectory, fileName, mc.getMainRenderTarget().width,
                    mc.getMainRenderTarget().height, mc.getMainRenderTarget(), message -> { });
            *///?}
        }

        @Override
        public void quit() {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.stop();
        }

        @Override
        public boolean offerMouse(int button, boolean pressed) {
            return LifecycleCrystalGUI.offerMouse(button, pressed, 0f);
        }

        @Override
        public void offerKey(int platformKey, char typed, boolean pressed) {
            LifecycleCrystalGUI.offerKey(platformKey, typed, pressed);
        }

        @Override
        public int keyZ() {
            return GLFW.GLFW_KEY_Z;
        }

        @Override
        public DesktopPresentation presentation() {
            return CgUiHud.presentation();
        }
    };
}
