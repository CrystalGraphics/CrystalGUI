package com.crystalgui.mc.legacy.client;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.host.HostSession;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.input.Mouse;

/**
 * Paints pinned windows wherever the desktop is not, and offers them a foreign screen's input.
 *
 * <p>Neither paint hook decides anything: each names the arm it owns and {@link HostSession#paint} paints
 * only if the compositor is in it. {@link RenderGameOverlayEvent.Post} is the no-screen arm, after
 * Minecraft's HUD; {@link GuiScreenEvent.DrawScreenEvent.Post} is a screen we do not own, and the only
 * one that fires over the main menu.</p>
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
            CgGL.fromHost();
            CgGlState.invalidateAllIfPresent();
        }

        @Override
        public void leave() {
            CgGL.toHost();
            CgGlState.invalidateAllIfPresent();
        }
    };

    /** Idempotent. */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        Handler handler = new Handler();
        // Render and screen events are on Forge's bus, the render tick on FML's; on 1.12 they are one bus,
        // which ignores the second registration.
        MinecraftForge.EVENT_BUS.register(handler);
        FMLCommonHandler.instance().bus().register(handler);
        CrystalGuiCore.LOGGER.info("[cgui] overlay hooks registered; pinned windows paint over the game "
                + "and over other GUIs");
    }

    /** What the desktop should be showing right now. @see HostSession#presentation */
    public static DesktopPresentation presentation() {
        return HostSession.isInstalled()
                ? HostSession.session().presentation(HOST) : DesktopPresentation.NONE;
    }

    /** Paints {@code arm}, and only if the compositor is in it. */
    private static void paint(DesktopPresentation arm) {
        if (!HostSession.isInstalled()) return;
        HostSession.session().paint(arm, HOST);
    }

    // ── Synthetic input, for the desktop probe ──────────────────────────────────────────────────

    /** @return whether the desktop consumed it and a foreign screen must not see it */
    public static boolean offerMouse(int button, boolean pressed, float wheel) {
        return HostSession.isInstalled() && HostSession.session().offerMouse(HOST, Mouse.isGrabbed(),
                Mouse.getX(), Minecraft.getMinecraft().displayHeight - Mouse.getY(), button, pressed, wheel);
    }

    /** @return whether the desktop consumed it */
    public static boolean offerKey(int keyCode, char typed, boolean pressed) {
        return HostSession.isInstalled() && HostSession.session().offerKey(keyCode, typed, pressed);
    }

    private static GuiScreen guiOf(GuiScreenEvent event) {
        //? if <1.9 {
        /*return event.gui;
        *///?} else {
        return event.getGui();
        //?}
    }

    private static RenderGameOverlayEvent.ElementType typeOf(RenderGameOverlayEvent event) {
        //? if <1.9 {
        /*return event.type;
        *///?} else {
        return event.getType();
        //?}
    }

    public static final class Handler {

        /** Drains a foreign screen's input once per frame, before anything is drawn. */
        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.START || !CgUiOverlayInput.wants()) return;
            CgUiOverlayInput.drain(Minecraft.getMinecraft().currentScreen);
        }

        @SubscribeEvent
        public void onMouseInput(GuiScreenEvent.MouseInputEvent.Pre event) {
            if (CgUiOverlayInput.wants() && CgUiOverlayInput.offerCurrentMouse(guiOf(event))) event.setCanceled(true);
        }

        @SubscribeEvent
        public void onKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Pre event) {
            if (CgUiOverlayInput.wants() && CgUiOverlayInput.offerCurrentKey(guiOf(event))) event.setCanceled(true);
        }

        /** No screen: the HUD arm. */
        @SubscribeEvent
        public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
            if (typeOf(event) != RenderGameOverlayEvent.ElementType.ALL) return;
            paint(DesktopPresentation.HUD);
        }

        /**
         * A screen we do not own: the OVERLAY arm. Our own is skipped — its {@code drawScreen} already
         * paints the compositor, and a second pass would win the hit test's {@code localToWorld}.
         */
        @SubscribeEvent
        public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
            if (guiOf(event) instanceof CgUiScreen) return;
            paint(DesktopPresentation.OVERLAY);
        }
    }
}
