package com.crystalgui.mc.legacy.client;

import com.crystalgraphics.platform.service.CgHostPaint;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.host.HostSession;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Pinned windows wherever the desktop is not. They paint on
 * CrystalGraphics' HUD and screen stages ({@link HostSession#attachStages}), with this class's bracket.
 *
 * <p>GL: Minecraft has just drawn through {@code GlStateManager} without telling CrystalGraphics' shadow,
 * so the bracket drops the shadow on both sides. A stale shadow is a missing GL call, not an error.</p>
 */
@SideOnly(Side.CLIENT)
public final class CgUiHud {

    private static boolean registered;

    private CgUiHud() {
    }

    /** What only this Minecraft can answer about a paint. @see HostSession.PaintHost */
    static final HostSession.PaintHost HOST = new HostSession.PaintHost() {

        @Override
        public boolean ownScreenUp() {
            return Minecraft.getMinecraft().currentScreen instanceof CgUiScreen;
        }

        @Override
        public boolean anyScreenUp() {
            return Minecraft.getMinecraft().currentScreen != null;
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

    /** Idempotent. */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        // The pinned windows paint on CrystalGraphics' HUD and screen stages, which its hosts fire.
        HostSession.attachStages(HOST);
    }

    /** What the desktop should be showing right now. @see HostSession#presentation */
    public static DesktopPresentation presentation() {
        return HostSession.isInstalled()
                ? HostSession.session().presentation(HOST) : DesktopPresentation.NONE;
    }
}
