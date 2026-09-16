package com.crystalgui.mc.modern.client;

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
public final class CgUiHud {

    private CgUiHud() {}

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
        Desktop desktop = CgUiScreen.desktop();
        if (desktop == null) return DesktopPresentation.NONE;

        Minecraft mc = Minecraft.getInstance();
        Screen current = mc == null ? null : mc.screen;

        boolean foreignUp = current != null && !(current instanceof CgUiScreen);
        if (foreignUp != foreignScreenWasUp) {
            foreignScreenWasUp = foreignUp;
            // Nullable: screenOverlay() answers null while the compositor has no document, which is its
            // ordinary state until a window opens. Thrown from the HUD event it takes out the rest of
            // Minecraft's overlay chain with it.
            ScreenOverlay overlay = desktop.screenOverlay();
            if (overlay != null) overlay.onForeignScreenChanged(foreignUp);
        }

        return desktop.presentation(current instanceof CgUiScreen, current != null);
    }

    /** Paints whatever {@link #presentation()} says, bracketed by the GL discipline. */
    /** The HUD arm: no screen is up. @see #paint(DesktopPresentation) */
    public static void paintHud() {
        paint(DesktopPresentation.HUD);
    }

    /** The arm for somebody else's screen. @see #paint(DesktopPresentation) */
    public static void paintOverScreen() {
        paint(DesktopPresentation.OVERLAY);
    }

    /**
     * Paints {@code arm}, and only when the compositor is actually in it.
     *
     * <p>One arm per hook. A frame with a screen open fires the HUD hook and the screen hook both, and
     * painting from each draws the whole compositor twice -- style, layout and all.</p>
     */
    private static void paint(DesktopPresentation arm) {
        Desktop desktop = CgUiScreen.desktop();
        if (desktop == null || !CgUiHostGl.contextIsLive()) return;

        // Inside the guard: deciding what to present reads the compositor and can throw for the same
        // reasons painting it can, and the catch below is what keeps that out of Minecraft's chain.
        DesktopPresentation presentation;
        try {
            presentation = presentation();
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[cgui] could not decide a presentation; leaving HUD mode", failed);
            desktop.exitHudMode();
            return;
        }
        // DESKTOP is our own screen's job, NONE paints nothing, and the other arm's hook owns the rest.
        if (presentation != arm) return;
        // 1.20 posts no screen event for a move, so the pointer is offered once per frame from here --
        // the per-frame drain mc1710 gets from pumping the event queue itself. Without it hover never
        // updates and a drag runs on wherever the pointer was when a button last changed.
        offerMove();
        CgUiHostGl.enter();
        try {
            desktop.paint(presentation, CgUiScreen.frameDelta(), surfaceWidth(), surfaceHeight());
        } catch (RuntimeException | LinkageError failed) {
            // This runs inside Minecraft's own render loop every frame, and unlike a screen there is
            // nothing the player can close to escape it. Drop the mode instead; the windows survive.
            CrystalGuiCore.LOGGER.error("[cgui] overlay paint failed; leaving HUD mode", failed);
            desktop.exitHudMode();
        } finally {
            CgUiHostGl.leave();
        }
    }

    /** @return whether the desktop consumed it and the foreign screen must not see it */
    public static boolean offerMouse(int button, boolean pressed, float platformWheel) {
        if (!pointerIsAvailable()) return false;
        ScreenOverlay overlay = overlay();
        if (overlay == null) return false;
        // Signed here, not by the loaders: this path consumes a scroll over any window and cancels the
        // screen event, so it -- not CgUiScreen.mouseScrolled -- is what a scroll in our own screen
        // reaches.
        return overlay.offerMouse(pointerX(), pointerY(), button, pressed,
                CgUiInput.wheel(platformWheel));
    }

    /** No button, and the value the engine reads as "this is a move". */
    private static final int NO_BUTTON = -1;

    /**
     * Whether the compositor may take pointer input at all -- only under a screen.
     *
     * <p>With no screen there is no cursor: the pointer is the camera, its position runs with the look
     * direction and a click is an attack, so hit-testing it puts the compositor wherever the player
     * happens to be looking and breaking a block presses a pinned window.</p>
     *
     * <p>A pinned window is a DISPLAY while the player is playing, and becomes interactive when
     * something releases the mouse -- which is a screen, and therefore the OVERLAY arm. mc1710 states
     * the same policy from the other side, by draining input only for a foreign screen. The grab is
     * checked as well as the screen so a mod that captures the mouse under one is still refused.</p>
     */
    private static boolean pointerIsAvailable() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.screen != null
                && mc.mouseHandler != null && !mc.mouseHandler.isMouseGrabbed();
    }

    /** The pointer's position, delivered and never consumed. @see ScreenOverlay#offerMouse */
    public static void offerMove() {
        if (!pointerIsAvailable()) return;
        ScreenOverlay overlay = overlay();
        if (overlay == null) return;
        overlay.offerMouse(pointerX(), pointerY(), NO_BUTTON, false, 0f);
    }

    /** @return whether the desktop consumed it */
    public static boolean offerKey(int glfwKey, char typed, boolean pressed) {
        ScreenOverlay overlay = overlay();
        if (overlay == null) return false;
        int local = com.crystalgraphics.platform.CgPlatform.input().translateKeyboardCodes(glfwKey);
        return overlay.offerKey(local, typed, pressed);
    }

    private static ScreenOverlay overlay() {
        Desktop desktop = CgUiScreen.desktop();
        UIDocument window = CgUiScreen.window();
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
