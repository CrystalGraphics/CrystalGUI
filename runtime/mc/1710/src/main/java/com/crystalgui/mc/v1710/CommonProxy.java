package com.crystalgui.mc.v1710;

import com.crystalgui.mc.v1710.ClientProxy;
import com.crystalgui.mc.v1710.example.MachineExample1710;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgui.mc.v1710.net.CgUiWorkspaceHost;
import com.crystalgui.net.window.WindowProtocol;

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
 * <h3>Except the protocols, which are exactly the server's half</h3>
 *
 * <p>A dedicated server holds the workspace and answers its protocol with no screen anywhere, so the
 * contributors register <em>here</em>. The channel and the connections are CrystalGraphics'.</p>
 */
public class CommonProxy {

    /** FML preInit. Nothing common to do. */
    public void preInit() {
    }

    /**
     * FML init: CrystalGUI's protocols, contributed to CrystalGraphics' connections. Pure wiring — no GL, no
     * screen, no world — so it sits in common code without undoing the headless property above.
     */
    public void init() {
        // CONTRIBUTORS BEFORE CONNECTIONS. Nothing depends on it here -- no peer can exist at init, so
        // both orders bind the same set -- but a contributor is only bound to connections opened AFTER
        // it registers, so this is the order that stays correct if anything ever opens one earlier. It
        // also makes the lifecycle's own "contributors: [...]" line true rather than an empty list.
        CgUiWorkspaceHost.register();
        // THE WINDOW LIFECYCLE, on both sides. Puts a ServerWindows on every server connection and a
        // ClientWindows on every client one, so a mod opens a UI with one call and never writes a tick
        // handler, a player map or a logout hook for it. @see com.crystalgui.net.window.WindowProtocol
        WindowProtocol.register();
        CgNetwork.onPeerClosed(CgUiWorkspaceHost::forget);
        // The worked example's SERVER half.
        // Common code on purpose: it imports no screen, which is the property that lets it run on a
        // dedicated server. @see MachineExample1710
        MachineExample1710.registerCommon();
    }
}
