package com.crystalgui.desktop.host;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.viewport.CgUiViewport;
import com.crystalgui.viewport.Readout;
import com.crystalgui.viewport.Readouts;

/**
 * What the desktop paints in one presentation, as its viewport frames it, and what the host reads back about each
 * frame. One per presentation, made once, so a frame allocates none.
 *
 * <pre>{@code
 * DesktopContent content = new DesktopContent(desktop, DesktopPresentation.HUD);
 * viewport.frame(stage, content);
 * boolean pinned = Boolean.TRUE.equals(viewport.readout(DesktopContent.PINNED));
 * }</pre>
 */
final class DesktopContent implements CgUiViewport.Content {

    /** Whether the desktop is in its document's tree. */
    static final Readout<Boolean> ATTACHED = Readout.named("desktop-attached");

    /** Whether a window is pinned over the game. */
    static final Readout<Boolean> PINNED = Readout.named("desktop-pinned");

    private final Desktop desktop;
    private final DesktopPresentation presentation;

    DesktopContent(Desktop desktop, DesktopPresentation presentation) {
        this.desktop = desktop;
        this.presentation = presentation;
    }

    @Override
    public boolean frame(float deltaSeconds, int width, int height) {
        return desktop.prepare(presentation, deltaSeconds, width, height);
    }

    @Override
    public void paint(CgUiPaintContext ctx) {
        desktop.paintInto(presentation, ctx);
    }

    @Override
    public void readouts(Readouts out) {
        out.put(ATTACHED, desktop.parent() != null);
        out.put(PINNED, desktop.hasPinnedWindows());
    }

    /** A fault in a frame must not take the game down: the HUD mode is dropped, the windows kept on the desktop. */
    @Override
    public void failed(Throwable cause) {
        CrystalGuiCore.LOGGER.error("[cgui] recording the desktop failed; leaving HUD mode");
        desktop.exitHudMode();
    }
}
