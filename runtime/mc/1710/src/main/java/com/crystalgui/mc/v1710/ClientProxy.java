package com.crystalgui.mc.v1710;

import com.crystalgui.lifecycle.CgUiLifecycle;
import com.crystalgui.mc.v1710.probe.CgUiAutoTest;
import com.crystalgui.mc.v1710.client.CgUiHud;
import com.crystalgui.mc.v1710.probe.CgUiConnectionProbe;
import com.crystalgui.mc.v1710.probe.CgUiDesktopProbe;
import com.crystalgui.mc.v1710.client.CgUiInput;
import com.crystalgui.mc.v1710.client.CgUiScreen;
import com.crystalgui.mc.v1710.example.MachineExampleClient1710;

/**
 * The client half: register the key binding and the input pump.
 *
 * <p>Registration itself does no GL work, but it can <em>cause</em> some, which is the non-obvious
 * part: {@link CgUiLifecycle#register()} delivers {@code onInit} immediately when a context is already
 * live, and that hook constructs and warms the paint context. The warm binds materials, so it writes
 * GL state on the host's context from inside what looks like a pure registration call. It is scoped
 * there — see the comment in {@code CgUiLifecycle.onInit} for what leaks and what it cost.</p>
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit() {
        super.preInit();
        
        // FIRST: the key binding, the probes and the example all reach the session, and a key pressed
        // before it exists would throw rather than open anything.
        CgUiScreen.install();

        CgUiLifecycle.register();
        // THE POINTER IS THIS PLATFORM'S TO DRESS, and it is a property of the process rather than of
        CgUiInput.register();
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
        // The worked example's CLIENT half: F8, and the session it shows. Always on -- it is meant to
        // be opened and looked at, unlike the probes above, which are diagnostics behind a flag.
        MachineExampleClient1710.registerClient();
    }
}
