package com.crystalgui.mc.legacy;

import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgui.mc.legacy.client.CgUiHud;
import com.crystalgui.mc.legacy.client.CgUiScreen;
import com.crystalgui.mc.legacy.probe.CgUiAutoTest;
import com.crystalgui.mc.legacy.probe.CgUiConnectionProbe;
import com.crystalgui.mc.legacy.probe.CgUiDesktopProbe;

/**
 * CrystalGUI on a Forge 1.8–1.12.2 client, after {@link CrystalGUILegacy}: the desktop's session, the paint hooks
 * and the probes, each of which is off unless its flag is set. Its keys and input are CrystalGraphics'.
 */
public final class CrystalGUILegacyClient implements VariantEntry {

    @Override
    public void start(Object context) {
        ((FmlEvents) context).on("FMLPreInitializationEvent", event -> {
            // First: the keys, the probes and the example all reach the session.
            CgUiScreen.install();
            CgUiHud.register();
            CgUiAutoTest.register();
            CgUiConnectionProbe.register();
            CgUiDesktopProbe.register();
        });
    }
}
