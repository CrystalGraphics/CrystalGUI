package com.crystalgui.mc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;

/**
 * <b>A screen that can draw Minecraft's own item tooltip.</b>
 *
 * <p>Exists because {@code GuiScreen.renderToolTip} is {@code protected}: only a subclass may call it,
 * and only on itself. Reproducing it instead would mean copying Minecraft's decompiled code into this
 * repository, and what it produces is not reproducible anyway — rarity colouring, enchantment lines,
 * lore, and every line another mod contributes through its tooltip event.</p>
 *
 * <h3>An interface rather than a named screen</h3>
 *
 * <p>The first version asked {@code currentScreen instanceof CgUiScreen} and worked for exactly as long
 * as there was one screen. The moment the slot probe became a screen of its own the check answered
 * false and <b>every tooltip silently stopped</b> — no error, no log line, just nothing appearing on
 * hover, which reads as the tooltip machinery being broken rather than as the wrong screen being
 * named.</p>
 *
 * <p>So the question is a capability rather than an identity, and any future host answers it by
 * implementing this and forwarding to its own inherited {@code renderToolTip}.</p>
 */
public interface NativeTooltipHost {

    /**
     * Draws {@code stack}'s tooltip at coordinates in <b>Minecraft's GUI-scaled space</b>.
     *
     * <p>Implementations forward to {@code GuiScreen.renderToolTip} and do nothing else — the caller
     * owns the GL bracket, because the caller is the one that knows it is mid-frame inside a
     * foreign-GL scope.</p>
     */
    void drawNativeItemTooltip(ItemStack stack, int scaledX, int scaledY);

    /**
     * Routes to whatever screen is up, if it can host one.
     *
     * @return whether it drew — so a caller can tell "no host is up" from "drawn". A void return makes
     *         those the same situation, which is what made the broken version hard to see.
     */
    static boolean draw(ItemStack stack, int scaledX, int scaledY) {
        if (stack == null) return false;
        GuiScreen current = Minecraft.getMinecraft().currentScreen;
        if (!(current instanceof NativeTooltipHost)) return false;
        ((NativeTooltipHost) current).drawNativeItemTooltip(stack, scaledX, scaledY);
        return true;
    }
}
