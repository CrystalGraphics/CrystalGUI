package com.crystalgui.desktop.host;

import com.crystalgraphics.platform.input.CgKeyBindings;
import com.crystalgraphics.platform.input.CgKeyCodes;

/**
 * CrystalGUI's keys, declared once on a client through CrystalGraphics' {@link CgKeyBindings}, which every era's host
 * registers with its game: Show Desktop, Left Alt by default.
 *
 * <pre>{@code
 * DesktopKeys.declare();   // CgUiLifecycle.onPreInit: before any loader's key registration
 * }</pre>
 */
public final class DesktopKeys {

    /** Their category in the controls: {@code key.category.crystalgui.keys}. */
    public static final String CATEGORY = "crystalgui:keys";

    private static boolean declared;

    private DesktopKeys() {
    }

    /** Declares every key; a second call does nothing. */
    public static synchronized void declare() {
        if (declared) return;
        declared = true;
        // Touches no window, so a desktop left with everything minimised comes back that way
        CgKeyBindings.declare("crystalgui:desktop", CgKeyCodes.KEY_LMENU, CATEGORY, () -> {
            if (HostSession.isInstalled()) HostSession.session().openDesktop();
        });
    }
}
