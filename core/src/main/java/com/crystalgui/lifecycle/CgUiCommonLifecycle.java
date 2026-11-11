package com.crystalgui.lifecycle;

import javax.annotation.Nullable;

import com.crystalgraphics.gauntlet.CgGauntlet;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgui.app.machine.MachineExample;
import com.crystalgraphics.platform.service.CgServers;
import com.crystalgraphics.probe.CgServerSmoke;
import com.crystalgui.fs.server.ServerWorkspaceHost;
import com.crystalgui.fs.server.WorkspaceHost;
import com.crystalgui.gauntlet.ConnectionTest;
import com.crystalgui.gauntlet.ServerTest;
import com.crystalgui.net.window.WindowProtocol;

/**
 * CrystalGUI's start on both sides, which no host names: the window protocol, the workspace a server serves, the
 * worked example, and its tests for a dedicated server's run.
 *
 * <p>Declared {@code common} in {@code META-INF/crystalgraphics/listeners}; {@link CgUiClientLifecycle} is the client's.</p>
 */
public final class CgUiCommonLifecycle implements CgLifecycleListener {

    /** The one listener; what the engine's discovery registers. */
    public static final CgUiCommonLifecycle INSTANCE = new CgUiCommonLifecycle();

    /** One server tick, in seconds. */
    private static final float SERVER_TICK_SECONDS = 1f / 20f;

    @Nullable
    private WorkspaceHost workspace;

    private CgUiCommonLifecycle() {
    }

    /** Its tests for an unattended run, and the class a dedicated server must never load. */
    @Override
    public void onPreInit() {
        CgGauntlet.register("crystalgui:server", ServerTest::new);
        CgGauntlet.register(ConnectionTest.ID, ConnectionTest::new);
        // The entry to every GPU resource CrystalGUI owns: loading it means something asked a server to paint.
        CgServerSmoke.clientOnly("com.crystalgui.render.CgUiPaintContext");
    }

    /** After the network installs: contributors bind onto every connection opened from here. */
    @Override
    public void onInit() {
        WindowProtocol.register();
        CgNetwork.onPeerClosed(this::forget);
        MachineExample.registerServer();
        CgEngineLifecycle.onServerTick(this::serverTick);
    }

    @Override
    public synchronized void onServerStarting(@Nullable Object server) {
        if (workspace != null || !CgServers.running()) return;
        workspace = new WorkspaceHost(WorkspaceHost.DEFAULT_PROJECT_ID, "Workspace", ServerWorkspaceHost.INSTANCE);
        workspace.contribute();
    }

    @Override
    public synchronized void onServerStopping() {
        if (workspace != null) workspace.reset();
    }

    private synchronized void forget(Object peer) {
        if (workspace != null) workspace.forget(peer);
    }

    private synchronized void serverTick() {
        if (workspace != null) workspace.tick(SERVER_TICK_SECONDS);
    }
}
