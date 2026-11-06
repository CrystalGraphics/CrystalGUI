package com.crystalgui.mc.modern.client;

import com.crystalgraphics.platform.service.CgHostPaint;
import com.crystalgraphics.mc.modern.platform.CgMinecraftClient;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.desktop.host.ScreenOverlay;

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
            return mc != null && CgMinecraftClient.screen(mc) instanceof CgUiScreen;
        }

        @Override
        public boolean anyScreenUp() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && CgMinecraftClient.screen(mc) != null;
        }

        @Override
        public void enter() {
            CgHostPaint.enter();
        }

        @Override
        public void leave() {
            CgHostPaint.leave();
        }
    };

    /** What the desktop should be showing. @see HostSession#presentation */
    public static DesktopPresentation presentation() {
        return HostSession.isInstalled()
                ? HostSession.session().presentation(HOST) : DesktopPresentation.NONE;
    }

    /** The pinned windows on CrystalGraphics' HUD and screen stages, which its hosts fire. Once, on a client. */
    public static void attachStages() {
        HostSession.attachStages(HOST);
    }
}
