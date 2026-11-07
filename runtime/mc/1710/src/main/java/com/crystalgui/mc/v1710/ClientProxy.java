package com.crystalgui.mc.v1710;

import com.crystalgui.mc.v1710.probe.CgUiAutoTest;
import com.crystalgui.mc.v1710.client.CgUiHud;
import com.crystalgui.mc.v1710.probe.CgUiConnectionProbe;
import com.crystalgui.mc.v1710.probe.CgUiDesktopProbe;
import com.crystalgui.mc.v1710.client.CgUiScreen;

/**
 * The client half: the session, the HUD and the probes. CrystalGraphics finds CrystalGUI's lifecycle, which declares
 * its keys, and carries its input.
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit() {
        super.preInit();

        // FIRST: the key binding, the probes and the example all reach the session, and a key pressed
        // before it exists would throw rather than open anything.
        CgUiScreen.install();

        // W14: pinned windows paint over the running game. Costs one no-op branch per frame when
        // nothing is pinned, which is every frame until somebody pins something.
        CgUiHud.register();
        CgUiAutoTest.register();
        // EVERYTHING THAT NEEDS A REAL CONNECTION, in one probe. Off unless
        // -Dcrystalgui.probe.connection=true. It was six classes -- session, net, wire, remote
        // workspace, editor-open and two-client -- each hard-wiring one (topology, check-set) pair,
        // and three of which existed mainly to refuse to run in the wrong topology. @see ConnectionProbe
        CgUiConnectionProbe.register();
        // The SCRIPTED DESKTOP RUN, which this era did not have. Off unless
        // -Dcrystalgui.clientProbe=true. @see DesktopProbe
        CgUiDesktopProbe.register();
    }
}
