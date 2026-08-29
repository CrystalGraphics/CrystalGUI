package com.crystalgui.mc;

import com.crystalgui.lifecycle.CgUiLifecycle;
import com.crystalgui.mc.client.CgUiAutoTest;
import com.crystalgui.mc.client.CgUiHud;
import com.crystalgui.mc.client.CgUiInput;
import com.crystalgui.mc.net.CgUiNetProbe;
import com.crystalgui.mc.net.CgUiRemoteWorkspaceProbe;
import com.crystalgui.mc.net.CgUiWireProbe;
import com.crystalgui.mc.net.CgUiSessionProbe;

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
        
        CgUiLifecycle.register();
        CgUiInput.register();
        // W14: pinned windows paint over the running game. Costs one no-op branch per frame when
        // nothing is pinned, which is every frame until somebody pins something.
        CgUiHud.register();
        CgUiAutoTest.register();
        // Off unless -Dcrystalgui.net.probe=true. @see CgUiNetProbe
        CgUiNetProbe.register();
        // The layer above it: the same flag, a later start, its own multiplexers. @see CgUiSessionProbe
        CgUiSessionProbe.register();
        // The DEDICATED-server version: -PcgRemoteProbe, against runServer over a socket.
        CgUiRemoteWorkspaceProbe.register();
        CgUiWireProbe.register();
    }
}
