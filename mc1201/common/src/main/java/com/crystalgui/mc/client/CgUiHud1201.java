package com.crystalgui.mc.client;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.host.ScreenOverlay;
import com.crystalgui.ui.dom.UIDocument;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Pinned windows over somebody else's screen, and over no screen at all.
 *
 * <p>Every arbitration decision -- which UI gets a click, who owns the keyboard, when ownership is
 * released -- is {@link ScreenOverlay}'s, in {@code core/}. A loader hands over primitives and honours
 * the boolean. This class is only the 1.20.x half: turning "is a foreign screen up" into a
 * {@link DesktopPresentation}, and painting.</p>
 *
 * <p><b>No mixin.</b> 1.7.10's Forge has no screen input event at all, which is why mc1710 needs one;
 * every version from 1.8 has a cancellable one.</p>
 */
public final class CgUiHud1201 {

    private CgUiHud1201() {}

    private static boolean foreignScreenWasUp;

    /**
     * What the desktop should be showing, and the one place a Minecraft condition becomes a
     * presentation -- so the paint hooks and the input hooks cannot disagree.
     *
     * <p>The transition is noticed here rather than in a hook of its own: a screen that renders no world
     * fires no world-render event, so a dedicated handler would miss the close and ownership would
     * survive into the next screen.</p>
     */
    public static DesktopPresentation presentation() {
        Desktop desktop = CgUiScreen1201.desktop();
        if (desktop == null) return DesktopPresentation.NONE;

        Minecraft mc = Minecraft.getInstance();
        Screen current = mc == null ? null : mc.screen;

        boolean foreignUp = current != null && !(current instanceof CgUiScreen1201);
        if (foreignUp != foreignScreenWasUp) {
            foreignScreenWasUp = foreignUp;
            desktop.screenOverlay().onForeignScreenChanged(foreignUp);
        }

        return desktop.presentation(current instanceof CgUiScreen1201, current != null);
    }

    /** Paints whatever {@link #presentation()} says, bracketed by the GL discipline. */
    public static void paint() {
        Desktop desktop = CgUiScreen1201.desktop();
        if (desktop == null) return;

        DesktopPresentation presentation = presentation();
        if (presentation == DesktopPresentation.NONE || presentation == DesktopPresentation.DESKTOP) {
            // DESKTOP is our own screen's job; painting it from here would draw it twice.
            return;
        }
        CgUiHostGl1201.enter();
        try {
            desktop.paint(presentation, CgUiScreen1201.frameDelta(), surfaceWidth(), surfaceHeight());
        } catch (RuntimeException | LinkageError failed) {
            // This runs inside Minecraft's own render loop every frame, and unlike a screen there is
            // nothing the player can close to escape it. Drop the mode instead; the windows survive.
            CrystalGuiCore.LOGGER.error("[cgui] overlay paint failed; leaving HUD mode", failed);
            desktop.exitHudMode();
        } finally {
            CgUiHostGl1201.leave();
        }
    }

    /** @return whether the desktop consumed it and the foreign screen must not see it */
    public static boolean offerMouse(int button, boolean pressed, float wheel) {
        ScreenOverlay overlay = overlay();
        if (overlay == null) return false;
        return overlay.offerMouse(pointerX(), pointerY(), button, pressed, wheel);
    }

    /** @return whether the desktop consumed it */
    public static boolean offerKey(int glfwKey, char typed, boolean pressed) {
        ScreenOverlay overlay = overlay();
        if (overlay == null) return false;
        int local = com.crystalgraphics.platform.CgPlatform.input().translateKeyboardCodes(glfwKey);
        return overlay.offerKey(local, typed, pressed);
    }

    private static ScreenOverlay overlay() {
        Desktop desktop = CgUiScreen1201.desktop();
        UIDocument window = CgUiScreen1201.window();
        if (desktop == null || window == null) return null;
        return desktop.screenOverlay();
    }

    /** Raw surface pixels, top-down -- what ScreenOverlay documents it wants. */
    private static int pointerX() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.mouseHandler == null ? 0 : (int) mc.mouseHandler.xpos();
    }

    private static int pointerY() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.mouseHandler == null ? 0 : (int) mc.mouseHandler.ypos();
    }

    private static int surfaceWidth() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getWidth();
    }

    private static int surfaceHeight() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getHeight();
    }
}
