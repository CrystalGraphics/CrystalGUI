package com.crystalgui.mc.example;

import com.crystalgui.app.machine.MachineModel;
import com.crystalgui.app.machine.MachineTrace;
import com.crystalgui.app.machine.ui.MachinePanel;
import com.crystalgui.mc.platform.Lifecycle1201;
import com.crystalgui.net.window.Presentation;
import com.crystalgui.net.window.ServerWindows;

/**
 * The worked example's <b>server half</b>: one shared machine, and what answers a request for a window.
 *
 * <p>{@link MachineModel} is a singleton and ticks with the world, the way a machine block would -- it
 * runs whether or not anybody is watching, and every player's panel is a <em>view</em> of the same
 * state, so flipping the switch on one client shows on every open panel.</p>
 *
 * <p>Kept apart from {@link MachineExampleClient1201} because a dedicated server loads this class and
 * must not load that one: no import here may be client-side.</p>
 */
public final class MachineExample1201 {

    /** One machine for the whole server. Every viewer's window mirrors the same object. */
    private static final MachineModel MACHINE = new MachineModel();

    private MachineExample1201() {}

    private static boolean registered;

    public static synchronized void registerCommon() {
        if (registered) return;
        registered = true;

        // Declared openable rather than answered by a hand-rolled notification. The resolver is the
        // authority: it gets the viewer and may answer null, which is an ordinary refusal rather than
        // an error. This one grants unconditionally because there is one machine and everybody may see
        // it; a real mod would read a position out of `args` and RE-DERIVE from it, because anything a
        // client sends is a claim rather than a fact.
        //
        // EDITOR_TAB is declared here, not named by the client: a machine's controls are a thing you
        // work in, so they belong beside the files. A host with no workbench opens a window instead and
        // nothing here changes.
        ServerWindows.openable(MachinePanel.TYPE, (viewer, args) -> {
            MachineTrace.log(MachineTrace.SERVER, "a client asked for a panel");
            return MACHINE;
        }, Presentation.EDITOR_TAB);

        // Rides the platform's tick rather than subscribing a loader event, so it happens once however
        // many loaders are in the build.
        Lifecycle1201.onServerTick(MACHINE::tick);
    }
}
