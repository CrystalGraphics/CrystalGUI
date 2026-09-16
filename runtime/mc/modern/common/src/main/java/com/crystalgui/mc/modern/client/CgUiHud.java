package com.crystalgui.mc.modern.client;

import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.desktop.host.ScreenOverlay;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.input.HostPointer;

import net.minecraft.client.Minecraft;

/**
 * Pinned windows over somebody else's screen, and over no screen at all — the 1.20.x half.
 *
 * <p>Every arbitration decision — which UI gets a click, who owns the keyboard, when ownership is
 * released — is {@link ScreenOverlay}'s, in {@code core/}, and which arm to paint is
 * {@link HostSession}'s. What is left here is what only this Minecraft can answer: whether a screen is
 * up and whose it is, where the pointer is, and how to bracket a draw.</p>
 *
 * <p><b>No mixin.</b> 1.7.10's Forge has no screen input event at all, which is why mc1710 needs one;
 * every version from 1.8 has a cancellable one.</p>
 */
public final class CgUiHud {

    private CgUiHud() {}

    /** What only this Minecraft can answer about a paint. @see HostSession.PaintHost */
    private static final HostSession.PaintHost HOST = new HostSession.PaintHost() {

        @Override
        public boolean ownScreenUp() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.screen instanceof CgUiScreen;
        }

        @Override
        public boolean anyScreenUp() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.screen != null;
        }

        /**
         * 1.20 posts no screen event for a move, so the pointer is offered once per frame from here —
         * the per-frame drain mc1710 gets from pumping the event queue itself. Without it hover never
         * updates and a drag runs on wherever the pointer was when a button last changed.
         */
        @Override
        public void beforePaint() {
            offerMove();
        }

        @Override
        public void enter() {
            CgUiHostGl.enter();
        }

        @Override
        public void leave() {
            CgUiHostGl.leave();
        }
    };

    /** What the desktop should be showing. @see HostSession#presentation */
    public static DesktopPresentation presentation() {
        return HostSession.isInstalled()
                ? HostSession.session().presentation(HOST) : DesktopPresentation.NONE;
    }

    /** The HUD arm: no screen is up. */
    public static void paintHud() {
        paint(DesktopPresentation.HUD);
    }

    /** The arm for somebody else's screen. */
    public static void paintOverScreen() {
        paint(DesktopPresentation.OVERLAY);
    }

    private static void paint(DesktopPresentation arm) {
        // Whether there is anything to draw INTO is this loader's question, and it is asked before the
        // session is: the engine initialises on the first WORLD render, and these hooks also fire over a
        // title screen where there has never been one.
        if (!HostSession.isInstalled() || !CgUiHostGl.contextIsLive()) return;
        HostSession session = HostSession.session();
        // The delta read ONCE and passed in -- reading it again inside would advance the clock twice.
        session.paint(arm, session.frameDelta(), HOST);
    }

    // ── Input, offered to the compositor ────────────────────────────────────────────────────────

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
        overlay.offerMouse(pointerX(), pointerY(), HostPointer.NO_BUTTON, false, 0f);
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
}
