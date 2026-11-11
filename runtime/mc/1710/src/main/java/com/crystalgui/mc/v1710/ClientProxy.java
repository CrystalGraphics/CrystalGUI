package com.crystalgui.mc.v1710;

import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.mc.v1710.probe.CgUiAutoTest;
import com.crystalgui.mc.v1710.probe.CgUiConnectionProbe;
import com.crystalgui.mc.v1710.probe.CgUiDesktopProbe;

/**
 * The client half: the session and the probes. The session paints, takes input and puts its screen up through
 * CrystalGraphics; CrystalGraphics finds CrystalGUI's lifecycle, which declares its keys.
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit() {
        super.preInit();

        // FIRST: the key binding, the probes and the example all reach the session, and a key pressed
        // before it exists would throw rather than open anything.
        HostSession.install(CrystalEditor.KIND);
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
