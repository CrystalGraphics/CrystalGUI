package com.crystalgui.mc.v1710.client;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.host.HostSession;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import org.lwjgl.input.Mouse;

import net.minecraft.client.Minecraft;

/**
 * Paints pinned windows wherever the desktop is not — M16, the 1.7.10 half.
 *
 * <h3>Painting</h3>
 *
 * <p>The pinned windows paint on CrystalGraphics' HUD and screen stages ({@link HostSession#attachStages}), with
 * this class's bracket; {@link HostSession} decides which arm each paints, so neither stage decides for itself.</p>
 *
 * <h3>Input, and why there is a mixin</h3>
 *
 * <p><b>1.7.10's Forge has no {@code GuiScreenEvent.MouseInputEvent} and no {@code KeyboardInputEvent}</b>
 * — they arrived in 1.8. Verified against the decompiled tree: {@code GuiScreenEvent} carries
 * {@code InitGui}, {@code DrawScreen} and {@code ActionPerformed}, and nothing else. And polling LWJGL
 * from a render hook cannot substitute, because {@code GuiScreen.handleInput} drains the event queue
 * from {@code runTick} and whoever drains it first is the only one who sees it. So input takes two
 * halves: {@code MixinGuiScreen} cancels that drain, and {@code CgUiOverlayInput} does it here on the
 * render tick — which also lifts both UIs off 20 Hz sampling. The mixin is this version's exception
 * rather than the pattern; every later version has a cancellable event and needs no bytecode.</p>
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
            CgGL.fromHost();
            CgGlState.invalidateAllIfPresent();
        }

        @Override
        public void leave() {
            CgGL.toHost();
            // MINECRAFT GETS ITS FIXED-FUNCTION STATE BACK. It drew with alpha and blend on and lighting
            // off and will assume the same next frame; CrystalGUI's endFrame restores what IT saved,
            // which is not the same thing.
            CgGlState.invalidateAllIfPresent();
        }
    };

    /** Idempotent, like every other handler registration in this package. */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        // The render tick is on FML's bus, not Forge's: a handler on the wrong one is never called and never complains.
        FMLCommonHandler.instance().bus().register(new CgUiHud.Handler());
        // The pinned windows paint on CrystalGraphics' HUD and screen stages, which its hosts fire.
        HostSession.attachStages(HOST);
    }

    /** What the desktop should be showing right now. @see HostSession#presentation */
    public static DesktopPresentation presentation() {
        return HostSession.isInstalled()
                ? HostSession.session().presentation(HOST) : DesktopPresentation.NONE;
    }

    // ── Input, offered to the compositor ────────────────────────────────────────────────────────
    //
    // 1.7.10 has no synthetic-input path of its own: CgUiOverlayInput DRAINS LWJGL's queue, which is
    // right for real input and gives a probe nothing to push through. 1.20.x has had these two since
    // its overlay landed; this is the same pair, so a scripted run exercises the same chain on both.

    /** @return whether the desktop consumed it and a foreign screen must not see it */
    public static boolean offerMouse(int button, boolean pressed, float wheel) {
        // LWJGL2 measures from the bottom; the DISPLAY height, since GuiScreen.height is GUI-scaled.
        return HostSession.isInstalled() && HostSession.session().offerMouse(HOST, Mouse.isGrabbed(),
                Mouse.getX(), Minecraft.getMinecraft().displayHeight - Mouse.getY(), button, pressed, wheel);
    }

    /** @return whether the desktop consumed it */
    public static boolean offerKey(int keyCode, char typed, boolean pressed) {
        return HostSession.isInstalled() && HostSession.session().offerKey(keyCode, typed, pressed);
    }

    public static final class Handler {

        /**
         * Drains input for a foreign screen, once per FRAME.
         *
         * <p>{@code MixinGuiScreen} cancels the screen's own 20 Hz drain and this replaces it, so both
         * UIs get per-frame input rather than tick-rate input. Phase START, so it lands before anything
         * is drawn -- a screen's handler can reach {@code displayGuiScreen} through a button, and doing
         * that from inside a render is a hazard worth not introducing on somebody else's behalf.</p>
         */
        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            if (!CgUiOverlayInput.wants()) return;
            CgUiOverlayInput.drainInto(Minecraft.getMinecraft().currentScreen);
        }
    }
}
