package com.crystalgui.desktop.host;

import com.crystalgraphics.platform.input.CgInputStage;
import com.crystalgraphics.render.stage.CgRenderStage;

/**
 * {@link HostSession}'s renderers on CrystalGraphics' GUI stages: the HUD arm on {@code HUD}; our own screen's desktop
 * or the overlay over somebody else's on {@code SCREEN}, which rehearses the desktop too. Its input on {@code SCREEN}'s
 * input stage.
 */
final class HostStages {

    private static boolean attached;

    private HostStages() {
    }

    /** Once a process, from {@link HostSession#install}. */
    static synchronized void attach() {
        if (attached) return;
        attached = true;
        CgInputStage.SCREEN.register(HostSession.COMPOSITOR, new HostInputListener());
        CgRenderStage.HUD.register(HostSession.COMPOSITOR, frame -> {
            if (HostSession.isInstalled()) HostSession.session().paintAt(frame, false);
        });
        CgRenderStage.SCREEN.register(HostSession.COMPOSITOR, frame -> {
            if (HostSession.isInstalled()) HostSession.session().paintAt(frame, true);
        });
    }
}
