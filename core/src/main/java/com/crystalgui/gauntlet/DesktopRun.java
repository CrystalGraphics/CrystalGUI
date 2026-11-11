package com.crystalgui.gauntlet;

import com.crystalgui.screen.CgUiScreen;
import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.variable.CgVar;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.NotificationEvent;
import com.crystalgui.core.notify.Notifications;

/**
 * A test that drives CrystalGUI's desktop in a world: it enters one, opens the desktop a few seconds after it loads,
 * and from then on hears every frame counted from the open.
 *
 * <pre>{@code
 * final class MyTest extends DesktopRun {
 *     MyTest() { super(true); }                         // the desktop bare, no application brought forward
 *     @Override protected void onDesktopFrame(int frame) {
 *         if (frame == 30) check("my.window", CgUiScreen.desktop().windows().size() > 0, "one window");
 *         if (frame == 60) endTest();
 *     }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>In a world because the editor needs a server: on a title screen the desktop comes up empty.</li>
 *   <li>A notification the desktop raises is logged, so a refusal a person would read reaches the run's log.</li>
 * </ul>
 */
public abstract class DesktopRun extends CgGauntletController {

    /** Seconds in a world before the desktop opens, so the world's pipeline has run some frames. */
    private static final CgVar.Num OPEN_AFTER = CgVar.number("crystalgui.desktop.openAfter", 3f, 0f, 600f,
            CgVar.DEV | CgVar.RESTART, "Seconds a run's desktop tests wait in a world before they open the desktop.");

    private final boolean bare;
    private boolean opened;
    private int frames;

    /** @param bare whether it opens the bare desktop rather than bringing the primary application forward */
    protected DesktopRun(boolean bare) {
        this.bare = bare;
    }

    /** Each frame from the open: 1 is the frame after it. */
    protected abstract void onDesktopFrame(int frame);

    /** The desktop was just opened. */
    protected void onOpened() {
    }

    @Override
    protected void onStateChange(CgGauntletState from, CgGauntletState to) {
        if (to.equals(CgGauntletState.TITLE)) enterWorld();
    }

    @Override
    protected final void onTick(float seconds) {
        if (!opened) {
            if (!state().equals(CgGauntletState.IN_WORLD) || timeInState() < OPEN_AFTER.get()) return;
            opened = true;
            Notifications.onDidChange.connect(event -> {
                if (event.kind() != NotificationEvent.Kind.ADDED || event.notification() == null) return;
                Notification added = event.notification();
                CrystalGuiCore.LOGGER.info("{} notification: {}{}", id(), added,
                        added.getDetail().isEmpty() ? "" : " -- " + added.getDetail().replace('\n', ' '));
            });
            if (bare) CgUiScreen.open();
            else CgUiScreen.openApplication();
            markHeartbeatActive("desktop opened" + (bare ? ", bare" : ""));
            onOpened();
            return;
        }
        onDesktopFrame(++frames);
    }
}
