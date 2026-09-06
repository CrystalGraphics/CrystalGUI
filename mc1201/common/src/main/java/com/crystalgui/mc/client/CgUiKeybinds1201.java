package com.crystalgui.mc.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

    /**
     * Every mapping a loader must register. Content adds to it through {@link #add}; a loader only
     * iterates, so the set is declared once rather than enumerated per loader.
     */
    private static final List<KeyMapping> ALL = new CopyOnWriteArrayList<>(
            java.util.Arrays.asList(OPEN_EDITOR, OPEN_DESKTOP));

    private CgUiKeybinds1201() {}

    public static void add(KeyMapping mapping) {
        if (!ALL.contains(mapping)) ALL.add(mapping);
    }

    public static List<KeyMapping> all() {
        return ALL;
    }

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
