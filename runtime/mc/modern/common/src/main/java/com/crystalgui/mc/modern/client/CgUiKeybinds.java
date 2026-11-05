package com.crystalgui.mc.modern.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.crystalgraphics.platform.input.CgKeyCodes;

import net.minecraft.client.KeyMapping;
//? if >=1.21.9 {
/*import com.crystalgraphics.mc.modern.platform.ResourceIds;
*///?}

/**
 * The key that opens the desktop, Left Alt. A vanilla {@code KeyMapping}, so it lives here; each loader only
 * registers it and calls {@link #tick()} once a client tick. It touches no window, so a desktop left with
 * everything minimised comes back that way; the editor is launched from the desktop.
 */
public final class CgUiKeybinds {

    /** CrystalGUI's key category, for every mapping of ours. 1.21.9 made it a registered id. */
    //? if >=1.21.9 {
    /*public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(ResourceIds.of("crystalgui", "keys"));
    *///?} else {
    public static final String CATEGORY = "key.categories.crystalgui";
    //?}

    public static final KeyMapping OPEN_DESKTOP =
            new KeyMapping("key.crystalgui.desktop", CgUiInput.hostKey(CgKeyCodes.KEY_LMENU), CATEGORY);

    /**
     * Every mapping a loader must register. Content adds to it through {@link #add}; a loader only
     * iterates, so the set is declared once rather than enumerated per loader.
     */
    private static final List<KeyMapping> ALL = new CopyOnWriteArrayList<>(
            java.util.Arrays.asList(OPEN_DESKTOP));

    private CgUiKeybinds() {}

    public static void add(KeyMapping mapping) {
        if (!ALL.contains(mapping)) ALL.add(mapping);
    }

    public static List<KeyMapping> all() {
        return ALL;
    }

    public static void tick() {
        if (OPEN_DESKTOP.consumeClick()) CgUiScreen.openDesktop();
    }
}
