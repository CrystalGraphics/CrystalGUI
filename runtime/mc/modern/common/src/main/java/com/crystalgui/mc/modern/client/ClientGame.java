package com.crystalgui.mc.modern.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The client state Minecraft has moved between versions, spelled once so a call site does not carry a
 * directive. 26.2 moved the screen, the loading overlay and the HUD's hidden flag onto
 * {@code Minecraft.gui}, and the main target onto the game renderer.
 *
 * <pre>{@code
 * if (ClientGame.screen(mc) == null) ClientGame.setScreen(mc, new CgUiScreen());
 * }</pre>
 */
public final class ClientGame {

    private ClientGame() {}

    /** The screen in front, or null when the game has none up. */
    public static Screen screen(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.screen();
        *///?} else {
        return mc.screen;
        //?}
    }

    /** Opens {@code screen}, or closes the current one when it is null. */
    public static void setScreen(Minecraft mc, Screen screen) {
        //? if >=26.2 {
        /*mc.gui.setScreen(screen);
        *///?} else {
        mc.setScreen(screen);
        //?}
    }

    /** Whether a loading overlay (resource reload, first load) covers the game. 1.13 has none. */
    public static boolean overlayUp(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.overlay() != null;
        *///?} elif >=1.14 {
        return mc.getOverlay() != null;
        //?} else {
        /*return false;
        *///?}
    }

    /** Minecraft's own main target, which our composite lands in. */
    public static RenderTarget mainTarget(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gameRenderer.mainRenderTarget();
        *///?} else {
        return mc.getMainRenderTarget();
        //?}
    }

    /** Whether the player has hidden the HUD (F1). */
    public static boolean hudHidden(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.hud.isHidden();
        *///?} else {
        return mc.options.hideGui;
        //?}
    }

    public static void setHudHidden(Minecraft mc, boolean hidden) {
        //? if >=26.2 {
        /*if (mc.gui.hud.isHidden() != hidden) mc.gui.hud.toggle();
        *///?} else {
        mc.options.hideGui = hidden;
        //?}
    }
}
