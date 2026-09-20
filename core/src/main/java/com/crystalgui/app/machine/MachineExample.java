package com.crystalgui.app.machine;

import javax.annotation.Nullable;

import com.crystalgui.app.machine.ui.MachinePanel;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.Presentation;
import com.crystalgui.net.window.ServerWindows;

/**
 * <b>The worked example, wired</b> — one machine on a server, a panel a client can ask for.
 *
 * <p>A host supplies a key and a tick, and nothing else:</p>
 *
 * <pre>{@code
 * // server init, after the window protocol is registered
 * Runnable tick = MachineExample.registerServer();
 * myLoader.onServerTick(tick);
 *
 * // client, when the host's own key was pressed and no screen is up
 * MachineExample.requestPanel(myConnections.client());
 * myHost.openDesktop();
 * }</pre>
 *
 * <p>Everything that made this an example — that there is exactly one model, that it advances with the
 * world rather than with a session, that a panel is <em>asked for</em> and the server may say no, and
 * that asking twice is free because the window names a key — is here. What a loader still owns is how
 * its game spells a key binding and a tick, which is genuinely all it ever was: the two copies of this
 * were ten lines of substance each.</p>
 *
 * @see com.crystalgui.app.machine the package note, which this is the runnable half of
 */
public final class MachineExample {

    /** ONE machine for the whole server. Every viewer's window mirrors the same object. */
    private static final MachineModel MACHINE = new MachineModel();

    private static boolean registered;

    private MachineExample() {
    }

    /**
     * Declares the panel a client may ask for, and hands back the tick the host must drive.
     *
     * <p>Idempotent — four loaders share this and only one of them is ever the caller.</p>
     *
     * @return the machine's tick. <b>The host drives it from its own server tick</b>: the machine
     *         advances with the world, so it needs no session, no player list and no flush
     */
    public static synchronized Runnable registerServer() {
        if (!registered) {
            registered = true;
            ServerWindows.openable(MachinePanel.TYPE, (viewer, args) -> {
                MachineTrace.log(MachineTrace.SERVER, "a client asked for a panel");
                return MACHINE;
            }, Presentation.EDITOR_TAB);
        }
        return MACHINE::tick;
    }

    /**
     * Asks the server for a panel. Safe to call repeatedly: the window names a key, so a second ask
     * brings the same window forward rather than opening another.
     *
     * @param connection this client's connection, or null when it is not on a server
     */
    public static void requestPanel(@Nullable ProtocolConnection<Object> connection) {
        if (connection == null) {
            MachineTrace.log(MachineTrace.CLIENT, "asked for a panel -- not connected to a server yet");
            return;
        }
        MachineTrace.log(MachineTrace.CLIENT, "asking the server for a panel");
        ClientWindows.requestOpen(MachinePanel.TYPE, null, granted ->
                MachineTrace.log(MachineTrace.CLIENT,
                        granted ? "the server is opening one" : "the server said no"));
    }
}
