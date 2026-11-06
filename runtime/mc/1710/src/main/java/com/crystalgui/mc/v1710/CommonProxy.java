package com.crystalgui.mc.v1710;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.probe.ServerSmoke;

import java.util.Arrays;

/**
 * The server-side half: nothing.
 *
 * <p>Everything CrystalGUI does on 1.7.10 is a screen, and a dedicated server has no screens. This
 * class exists so {@link ClientProxy} has something to extend and so the client-only classes are
 * unreachable from common code — {@code CgUiScreen} imports {@code GuiScreen}, which does not exist
 * server-side, and a static reference from a common class is enough to fail class loading there.</p>
 *
 * <p>{@code core/} is headless-clean by construction (its build fails on a {@code net.minecraft.*}
 * import), and that property is worth not undoing at the loader.</p>
 *
 * <h3>Except the server's answers</h3>
 *
 * <p>A dedicated server holds the workspace with no screen anywhere, so what the platform answers for it
 * registers <em>here</em>. The protocols themselves are the engine's.</p>
 */
public class CommonProxy {

    /** FML preInit. Nothing common to do. */
    public void preInit() {
    }

    /**
     * FML init: what only this platform answers, handed to the engine. The protocols, the workspace and the example
     * start from the engine's own lifecycle. No GL, no screen, no world, so it sits in common code.
     */
    public void init() {
        // .probe too: every class in there is client-only. CommonProxy exists so ClientProxy is unreachable from common code.
        CgPlatform.provide(ServerSmoke.FOOTPRINT, new ServerSmoke.Footprint(CommonProxy.class,
                Arrays.asList("com.crystalgui.mc.v1710.client", "com.crystalgui.mc.v1710.probe"),
                Arrays.asList("com.crystalgui.mc.v1710.ClientProxy")));
    }
}
}
