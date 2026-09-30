package com.crystalgui.mc.modern.client;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.desktop.host.ScreenOverlay;
import com.crystalgui.ui.input.HostPointer;
//? if >=26.1 {
/*import com.crystalgui.lifecycle.CgUiLifecycle;
*///?}

import net.minecraft.client.Minecraft;

/**
 * Pinned windows over somebody else's screen, and over no screen at all — the 1.20.x half.
 *
 * <p>Every arbitration decision — which UI gets a click, who owns the keyboard, when ownership is
 * released, whether the pointer may reach a window at all — is {@link HostSession}'s and
 * {@link ScreenOverlay}'s, in {@code core/}. What is left here is what only this Minecraft can answer:
 * whether a screen is up and whose it is, where the pointer is and whether it is grabbed, and how to
 * bracket a draw.</p>
 *
 * <p><b>No mixin.</b> 1.7.10's Forge has no screen input event at all, which is why 1.7.10 needs one;
 * every version from 1.8 has a cancellable one.</p>
 */
public final class CgUiHud {

    private CgUiHud() {}

    /** What only this Minecraft can answer about a paint. @see HostSession.PaintHost */
    private static final HostSession.PaintHost HOST = new HostSession.PaintHost() {

        @Override
        public boolean ownScreenUp() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && ClientGame.screen(mc) instanceof CgUiScreen;
        }

        @Override
        public boolean anyScreenUp() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && ClientGame.screen(mc) != null;
        }

        /**
         * 1.20 posts no screen event for a move, so the pointer is offered once per frame from here —
         * the per-frame drain 1.7.10 gets from pumping the event queue itself. Without it hover never
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
        // 26.1 extracts the GUI before it draws the level, so a paint from its hooks lands under the
        // world: the arm paints at the frame end instead, over everything. @see CgUiLifecycle#atFrameEnd
        //? if >=26.1 {
        /*CgUiLifecycle.atFrameEnd(arm, () -> paintNow(arm));
        *///?} else {
        paintNow(arm);
        //?}
    }

    private static void paintNow(DesktopPresentation arm) {
        // Whether there is anything to draw INTO is this loader's question, and it is asked before the
        // session is: the engine initialises on the first WORLD render, and these hooks also fire over a
        // title screen where there has never been one.
        if (!HostSession.isInstalled() || !CgUiHostGl.contextIsLive()) return;
        HostSession.session().paint(arm, HOST);
    }

    // ── Input, offered to the compositor ────────────────────────────────────────────────────────

    /** @return whether the desktop consumed it and the foreign screen must not see it */
    public static boolean offerMouse(int button, boolean pressed, float platformWheel) {
        // Signed here, not by the loaders: this path consumes a scroll over any window and cancels the
        // screen event, so it -- not CgUiScreen.mouseScrolled -- is what a scroll in our own screen
        // reaches.
        return HostSession.isInstalled() && HostSession.session().offerMouse(HOST, pointerGrabbed(),
                pointerX(), pointerY(), button, pressed, CgUiInput.wheel(platformWheel));
    }

    /** The pointer's position, delivered and never consumed. @see ScreenOverlay#offerMouse */
    public static void offerMove() {
        if (!HostSession.isInstalled()) return;
        HostSession.session().offerMouse(HOST, pointerGrabbed(), pointerX(), pointerY(),
                HostPointer.NO_BUTTON, false, 0f);
    }

    /** @return whether the desktop consumed it */
    public static boolean offerKey(int glfwKey, char typed, boolean pressed) {
        return HostSession.isInstalled() && HostSession.session().offerKey(
                CgPlatform.input().translateKeyboardCodes(glfwKey), typed, pressed);
    }

    /** Whether the game holds the pointer; no mouse handler counts as held. */
    private static boolean pointerGrabbed() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.mouseHandler == null || mc.mouseHandler.isMouseGrabbed();
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
