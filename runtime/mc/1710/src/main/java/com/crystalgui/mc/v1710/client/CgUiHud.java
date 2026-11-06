package com.crystalgui.mc.v1710.client;

import com.crystalgraphics.platform.service.CgHostPaint;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.host.HostSession;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import net.minecraft.client.Minecraft;

/**
 * Paints pinned windows wherever the desktop is not — M16, the 1.7.10 half.
 *
 * <h3>Painting</h3>
 *
 * <p>The pinned windows paint on CrystalGraphics' HUD and screen stages ({@link HostSession#attachStages}), with
 * this class's bracket; {@link HostSession} decides which arm each paints, so neither stage decides for itself.</p>
 *
 * <h3>GL state</h3>
 *
 * <p>The discipline {@code CgUiScreen} documents applies verbatim: {@code CgGlStateManager} keeps a
 * CPU-side shadow and elides redundant calls, and Minecraft has just drawn its HUD (or a whole screen)
 * straight through {@code GL11}/{@code OpenGlHelper} without telling it. Getting this wrong produces a
 * MISSING GL call rather than an error, which shows up as the hotbar rendering wrong on the next frame
 * and points nowhere near here.</p>
 */
@SideOnly(Side.CLIENT)
public final class CgUiHud {

    private static boolean registered;

    private CgUiHud() {
    }

    /**
     * What only this Minecraft can answer about a paint.
     *
     * <p>The bracket is an invalidate on both sides: cheap — it drops the shadow, it does not read the
     * driver — and there is no earlier point that stays true, since Minecraft's own GUI pass runs
     * between frames.</p>
     *
     * @see HostSession.PaintHost
     */
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

    /** Idempotent, like every other handler registration in this package. */
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
