package com.crystalgui.desktop.host;

import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgui.core.window.DesktopPresentation;

/** {@link HostSession}'s renderers on CrystalGraphics' GUI stages: the HUD arm on {@code HUD}, the overlay arm on {@code SCREEN}. */
final class HostStages {

    private static boolean attached;

    private HostStages() {
    }

    static synchronized void attach(HostSession.PaintHost host) {
        if (attached) return;
        attached = true;
        CgRenderStage.HUD.register(HostSession.COMPOSITOR, frame -> {
            if (HostSession.isInstalled()) HostSession.session().paintAt(frame, DesktopPresentation.HUD, host);
        });
        CgRenderStage.SCREEN.register(HostSession.COMPOSITOR, frame -> {
            if (HostSession.isInstalled()) HostSession.session().paintAt(frame, DesktopPresentation.OVERLAY, host);
        });
    }
}
