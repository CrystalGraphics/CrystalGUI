package com.crystalgui.mc;

import com.crystalgui.mc.example.MachineExample;
import com.crystalgui.mc.net.CgUiConnections;
import com.crystalgui.mc.net.CgUiWorkspaceHost;
import com.crystalgui.mc.net.Mc1710NetworkChannel;

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
 * <h3>Except networking, which is exactly the server's half</h3>
 *
 * <p>The class comment above was true for as long as CrystalGUI was only ever a screen. It stops being
 * true the moment a workspace is hosted rather than local: a dedicated server holds the files and answers
 * the protocol, and it does that with no screen anywhere. So the channel registers <em>here</em> rather
 * than in {@code ClientProxy} — both sides need it, and the server needs it more.</p>
 */
public class CommonProxy {

    /** FML preInit. Nothing common to do. */
    public void preInit() {
    }

    /**
     * FML init. Registers the network channel, which both sides need.
     *
     * <p><b>init and not preInit, following CustomNPC+</b>, which builds its {@code PacketHandler} at
     * preInit but calls {@code registerChannels()} from {@code FMLInitializationEvent}. Ours registered
     * at preInit and no packet was ever delivered, in either direction, with every gate reporting
     * healthy — channel present on both sides, connection open, dispatcher live, sends accepted. This is
     * the one structural difference from a mod that demonstrably works.</p>
     *
     * <p>Registration is pure wiring — no GL, no screen, no world — which is what lets it sit in common
     * code without undoing the headless property above.</p>
     */
    public void init() {
        Mc1710NetworkChannel.register();
        // Phase 4 A4. Must follow the channel: it takes the channel's inbound handler, and a handler
        // installed onto an unavailable channel is silently discarded.
        // CONTRIBUTORS BEFORE CONNECTIONS. Nothing depends on it here -- no peer can exist at init, so
        // both orders bind the same set -- but a contributor is only bound to connections opened AFTER
        // it registers, so this is the order that stays correct if anything ever opens one earlier. It
        // also makes the lifecycle's own "contributors: [...]" line true rather than an empty list.
        CgUiWorkspaceHost.register();
        CgUiConnections.register();
        // The worked example's SERVER half. After connections, because it opens a session per player
        // on the connection that class holds -- registered earlier it would simply find none.
        // Common code on purpose: it imports no screen, which is the property that lets it run on a
        // dedicated server. @see com.crystalgui.mc.example.MachineExample
        MachineExample.registerCommon();
    }
}
