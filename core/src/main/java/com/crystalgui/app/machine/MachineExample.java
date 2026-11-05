package com.crystalgui.app.machine;

import javax.annotation.Nullable;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.app.machine.ui.MachinePanel;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.Presentation;
import com.crystalgui.net.window.ServerWindows;

/**
 * <b>The worked example, wired</b> — one machine on a server, a panel a client can ask for.
 *
 * <p>A host calls {@link #registerServer} once; the client asks from the launcher, where {@link #KIND} lists it:</p>
 *
 * <pre>{@code
 * // server init, after the window protocol is registered
 * MachineExample.registerServer();
 *
 * // client: the desktop lists MachineExample.KIND; or by hand
 * MachineExample.requestPanel(myConnections.client());
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

    /**
     * The launcher's entry: asks the server for the panel, which lands as a tab of the Crystal Editor, and
     * brings the editor forward. Needs a connection, so a standalone desktop lists it unavailable.
     */
    public static final ApplicationKind KIND = ApplicationKind.of("crystalgui:machine-example", "Machine Example")
            .keywords("machine", "example", "server", "panel")
            .singleInstance()
            .launch(context -> {
                requestPanel(CgNetwork.client());
                Application editor = context.desktop().applications()
                        .activateOrLaunch(CrystalEditor.KIND, context.workspace());
                return new Forward(editor);
            });

    private MachineExample() {
    }

    /**
     * Declares the panel a client may ask for, and ticks the machine from {@link CgEngineLifecycle#onServerTick}:
     * it advances with the world, so it needs no session, no player list and no flush.
     *
     * <p>Idempotent — four loaders share this and only one of them is ever the caller.</p>
     */
    public static synchronized void registerServer() {
        if (!registered) {
            registered = true;
            ServerWindows.openable(MachinePanel.TYPE, (viewer, args) -> {
                MachineTrace.log(MachineTrace.SERVER, "a client asked for a panel");
                return MACHINE;
            }, Presentation.EDITOR_TAB);
            CgEngineLifecycle.onServerTick(MACHINE::tick);
        }
    }

    /**
     * Asks the server for a panel. Safe to call repeatedly: the window names a key, so a second ask
     * brings the same window forward rather than opening another.
     *
     * @param connection this client's connection, or null when it is not on a server
     */
    public static void requestPanel(@Nullable CgProtocolConnection<Object> connection) {
        if (connection == null) {
            MachineTrace.log(MachineTrace.CLIENT, "asked for a panel -- not connected to a server yet");
            return;
        }
        MachineTrace.log(MachineTrace.CLIENT, "asking the server for a panel");
        ClientWindows.requestOpen(MachinePanel.TYPE, null, granted ->
                MachineTrace.log(MachineTrace.CLIENT,
                        granted ? "the server is opening one" : "the server said no"));
    }

    /** The editor the panel lands in, standing for the request: closing the request closes nothing. */
    private record Forward(Application editor) implements Application {

        @Override
        public ApplicationKind kind() {
            return KIND;
        }

        @Override
        public WindowFrame mainWindow() {
            return editor.mainWindow();
        }

        @Override
        public boolean open(Resource resource) {
            return false;
        }

        @Override
        public void activate() {
            editor.activate();
        }

        @Override
        public void dispose() {
        }
    }
}
