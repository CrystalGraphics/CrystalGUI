package com.crystalgui.mc.client;

import net.minecraft.client.KeyMapping;

import org.lwjgl.glfw.GLFW;

/**
 * The two keys that open the desktop. Vanilla {@code KeyMapping}s, so they live here; each loader only
 * registers them and calls {@link #tick()} once a client tick.
 *
 * <p>Two keys and two different things: F6 asks for the EDITOR and brings it forward whatever state it
 * was left in, F7 asks for the DESKTOP and touches no window, so one left with everything minimised
 * comes back that way.</p>
 */
public final class CgUiKeybinds1201 {

    private static final String CATEGORY = "key.categories.crystalgui";

    public static final KeyMapping OPEN_EDITOR =
            new KeyMapping("key.crystalgui.open", GLFW.GLFW_KEY_F6, CATEGORY);

    public static final KeyMapping OPEN_DESKTOP =
            new KeyMapping("key.crystalgui.desktop", GLFW.GLFW_KEY_F7, CATEGORY);

    private CgUiKeybinds1201() {}

    /**
     * Both are asked, never else-if: {@code consumeClick()} CONSUMES the press, so a chain that stops at
     * the first hit leaves the other key's press queued and it fires on the next unrelated keystroke.
     */
    public static void tick() {
        boolean editor = OPEN_EDITOR.consumeClick();
        boolean desktop = OPEN_DESKTOP.consumeClick();
        if (editor) CgUiScreen1201.openEditor();
        else if (desktop) CgUiScreen1201.openDesktop();
    }
}
