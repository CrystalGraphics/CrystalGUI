package com.crystalgui.mc.client;

import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.core.window.DesktopPresentation;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Paints pinned windows wherever the desktop is not — M16, the 1.7.10 half.
 *
 * <h3>Two hooks, one decision</h3>
 *
 * <p>Neither hook decides anything. Each asks {@link UIDocument#presentation} what should be on screen and
 * paints if the answer is its own — which is the whole of the flicker fix. Before that, each path tested
 * a Minecraft condition for itself, and on the frame the desktop closed <b>both concluded it was the
 * other's turn</b>: {@code CgUiScreen} closes itself from inside its own {@code drawScreen}, after that
 * frame's overlay hook had already run and stood down.</p>
 *
 * <ul>
 *   <li>{@link RenderGameOverlayEvent.Post} — no screen at all. After Minecraft's own HUD, so a pinned
 *       window sits over the hotbar and the chat rather than under them.</li>
 *   <li>{@link GuiScreenEvent.DrawScreenEvent.Post} — a screen we do not own. This is also the only one
 *       that fires for a screen with no world behind it: the main menu and the loading screen set
 *       {@code skipRenderWorld}, and the overlay event never runs there.</li>
 * </ul>
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

    /** What the last frame saw, so a screen opening or closing is noticed exactly once. */
    private static boolean foreignScreenWasUp;

    private CgUiHud() {
    }

    /** Idempotent, like every other handler registration in this package. */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        // THE FORGE BUS, not FMLCommonHandler's: these are client render events and are posted on
        // MinecraftForge.EVENT_BUS. The neighbours here register on the FML bus because tick and
        // connection events live there -- the two are not interchangeable, and a handler on the wrong
        // one is never called and never complains.
        CgUiHud.Handler handler = new CgUiHud.Handler();
        MinecraftForge.EVENT_BUS.register(handler);
        // AND THE FML BUS for the render tick: TickEvent lives there, not on the Forge bus. A handler on
        // the wrong one is never called and never complains, which is why both registrations are here
        // rather than split between two call sites.
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(handler);
        CrystalGuiCore.LOGGER.info("[cgui] overlay hooks registered; pinned windows paint over the game "
                + "and over other GUIs");
    }

    /**
     * What the desktop should be showing right now, or {@code null} if there is no desktop yet.
     *
     * <p>The one place a Minecraft condition is turned into a presentation, which is what keeps the two
     * paint hooks and the input mixin agreeing with each other.</p>
     */
    static DesktopPresentation presentation() {
        GuiScreen current = Minecraft.getMinecraft().currentScreen;

        // THE TRANSITION IS NOTICED HERE, not in a hook of its own, because this is the one thing every
        // caller runs -- both paint hooks and the input mixin. A dedicated handler on
        // RenderGameOverlayEvent was the first attempt and is wrong for the case that matters least
        // often and breaks worst: a screen that sets skipRenderWorld (the main menu, the loading screen)
        // renders no world, so that event never fires and the close is never seen. Ownership would then
        // survive into the next screen.
        Desktop desktop = CgUiScreen.desktop();
        if (desktop == null) return DesktopPresentation.NONE;

        boolean foreignUp = current != null && !(current instanceof CgUiScreen);
        if (foreignUp != foreignScreenWasUp) {
            foreignScreenWasUp = foreignUp;
            desktop.screenOverlay().onForeignScreenChanged(foreignUp);
        }

        return desktop.presentation(current instanceof CgUiScreen, current != null);
    }

    /** Paints {@code presentation}, bracketed by the GL discipline. Shared by both hooks. */
    private static void paint(DesktopPresentation presentation) {
        Desktop desktop = CgUiScreen.desktop();
        if (desktop == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            CgGlState.invalidateAllIfPresent();
            desktop.paint(presentation, CgUiScreen.frameDelta(), mc.displayWidth, mc.displayHeight);
        } catch (RuntimeException | LinkageError e) {
            // A FAULT HERE MUST NOT TAKE THE GAME DOWN. This runs inside Minecraft's own render loop on
            // every frame, and unlike a screen there is nothing the player can close to escape it.
            // Logged and the mode dropped, which puts them back in a working game with their windows
            // intact on the desktop.
            CrystalGuiCore.LOGGER.error("[cgui] overlay paint failed; leaving HUD mode", e);
            desktop.exitHudMode();
        } finally {
            // MINECRAFT GETS ITS FIXED-FUNCTION STATE BACK. It drew with alpha and blend on and lighting
            // off and will assume the same next frame; CrystalGUI's endFrame restores what IT saved,
            // which is not the same thing.
            CgGlState.invalidateAllIfPresent();
        }
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

        /** No screen: the HUD arm. */
        @SubscribeEvent
        public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
            if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
            if (presentation() != DesktopPresentation.HUD) return;
            paint(DesktopPresentation.HUD);
        }

        /**
         * A screen we do not own: the OVERLAY arm.
         *
         * <p><b>Our own screen is skipped, and that guard is not a formality.</b> {@code CgUiScreen}'s
         * {@code drawScreen} is already painting the whole compositor; painting again here would draw
         * every window twice, and the second pass would win the {@code localToWorld} reconciliation the
         * hit test walks — so clicks would land somewhere other than what is on screen. That is the
         * mirror rule {@code CgUiPaintContext.mirrored} exists for, met from the wrong side.</p>
         */
        @SubscribeEvent
        public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
            if (event.gui instanceof CgUiScreen) return;
            if (presentation() != DesktopPresentation.OVERLAY) return;
            paint(DesktopPresentation.OVERLAY);
        }

    }
}
