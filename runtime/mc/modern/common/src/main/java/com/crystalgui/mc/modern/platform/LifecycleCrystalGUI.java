package com.crystalgui.mc.modern.platform;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.mc.modern.client.CgUiHud;
import com.crystalgui.mc.modern.client.CgUiKeybinds;
import com.crystalgui.mc.modern.client.CgUiScreen;
import com.crystalgui.mc.modern.probe.CgUiAutoTest;
import com.crystalgui.mc.modern.probe.ClientProbe;
import com.crystalgui.mc.modern.probe.ConnectionProbeModern;
import com.crystalgui.probe.ConnectionProbe;
import com.crystalgui.probe.ServerSmoke;

import java.util.Arrays;
import java.util.Collections;

/**
 * <b>The one class a 1.20.x loader talks to</b>: the platform's own moments, which a loader forwards. The game's
 * ticks and its server's start and stop are the engine's ({@code CgEngineLifecycle}), and CrystalGUI's start
 * from them is its own listener's.
 */
public final class LifecycleCrystalGUI {

    private LifecycleCrystalGUI() {}

    /**
     * Mod init, both sides: where this platform's client-only classes are, for the server smoke. The protocols, the
     * workspace and the example start from the engine's own lifecycle.
     */
    public static void bootstrap() {
        // .probe too: every class in there is client-only.
        CgPlatform.provide(ServerSmoke.FOOTPRINT, new ServerSmoke.Footprint(LifecycleCrystalGUI.class,
                Arrays.asList("com.crystalgui.mc.modern.client", "com.crystalgui.mc.modern.probe"),
                Collections.emptyList()));
    }

    /**
     * Client init, once. Everything client-side that has to be registered is registered from here, so a
     * loader never enumerates it -- which is why {@link CgUiKeybinds#all()} must be read AFTER this runs.
     */
    public static void bootstrapClient() {
        // FIRST: the keybinds reach the session, and a key pressed before it exists would throw rather
        // than open anything.
        CgUiScreen.install();
        CgUiHud.attachStages();
        CgEngineLifecycle.onClientTick(CgUiAutoTest::tick);
        CgEngineLifecycle.onClientTick(ClientProbe::tick);
        CgEngineLifecycle.onClientTick(CgUiKeybinds::tick);

        // THE CONNECTION PROBE is registered from HERE rather than from a common path: it is a client
        // class, and registering in client init keeps it off a server entirely; serverSmoke enumerates
        // `.probe` as well as `.client`, which would catch it if that ever stopped being true.
        // @see ServerSmoke.Host#clientPackages()
        if (ConnectionProbe.enabled()) {
            CgEngineLifecycle.onServerTick(ConnectionProbeModern::serverTick);
            CgEngineLifecycle.onClientTick(ConnectionProbeModern::clientTick);
            // THE WATCHDOG, which is what makes this a build task rather than a window somebody has to
            // close by hand. @see ConnectionProbe#arm()
            ConnectionProbe.arm();
        }
    }
}
