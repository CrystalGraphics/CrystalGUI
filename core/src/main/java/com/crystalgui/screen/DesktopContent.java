package com.crystalgui.screen;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.box.BoxPainter;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.viewport.CgUiViewport;
import com.crystalgui.viewport.Readout;
import com.crystalgui.viewport.Readouts;

/**
 * What the desktop paints in one {@link CgUiScreen.Mode}, as its viewport frames it, and what the screen reads back
 * about each frame. One per mode, made once, so a frame allocates none.
 *
 * <p>Three things vary between the modes and nothing else does: what is painted (the whole document, or the window
 * layer alone), whether the top layer goes with it, and whether input runs. Each is the mode's answer, so a new
 * situation is a new mode rather than a new path. The root transform is the box tree's in every mode, which is what
 * makes a pinned window pixel-identical on the desktop, over another screen and on the HUD.</p>
 */
final class DesktopContent implements CgUiViewport.Content {

    /** Whether the desktop is in its document's tree. */
    static final Readout<Boolean> ATTACHED = Readout.named("desktop-attached");

    /** Whether a window is pinned over the game. */
    static final Readout<Boolean> PINNED = Readout.named("desktop-pinned");

    /** Where a press lands, frozen with the frame: what the router answers from over another screen. */
    static final Readout<HitRegions> HIT_REGIONS = Readout.named("desktop-hit-regions");

    private final Desktop desktop;
    private final UIDocument document;
    private final CgUiScreen.Mode mode;

    DesktopContent(Desktop desktop, UIDocument document, CgUiScreen.Mode mode) {
        this.desktop = desktop;
        this.document = document;
        this.mode = mode;
    }

    @Override
    public boolean frame(float deltaSeconds, int width, int height) {
        // Asked before the returns below, so the desktop's live class is never a stale answer.
        desktop.setCoversSurface(mode.coversSurface());
        if (mode == CgUiScreen.Mode.NONE) return false;
        if (!mode.coversSurface() && desktop.parent() == null) return false;
        // SURFACE pixels in, LOGICAL units to lay out in: the scale lives on the box tree's root transform and nowhere
        // else, so this is the only division and painting picks it up from the matrix it already reads.
        float scale = document.boxes().uiScale();
        document.frame(deltaSeconds, width / scale, height / scale);
        return true;
    }

    @Override
    public void paint(CgUiPaintContext ctx) {
        if (mode.coversSurface()) {
            document.paint(ctx);
            return;
        }
        // THE WINDOW LAYER, not the desktop: the taskbar is chrome for a desktop that is not up. The top layer goes
        // with it only where the mode takes input.
        Box layer = desktop.windowLayer().box();
        if (layer != null) BoxPainter.paintSubtree(layer, ctx);
        if (mode.paintsTopLayer()) {
            Box top = document.hasTopLayerContent() ? document.topLayer() : null;
            if (top != null) BoxPainter.paintSubtree(top, ctx);
        }
    }

    @Override
    public void readouts(Readouts out) {
        out.put(ATTACHED, desktop.parent() != null);
        out.put(PINNED, desktop.hasPinnedWindows());
        // Only over the game is a press routed from the frame; the desktop's own screen dispatches it.
        if (!mode.coversSurface() && mode != CgUiScreen.Mode.NONE) out.put(HIT_REGIONS, HitRegions.capture(document));
    }

    /** A fault in a frame must not take the game down: the HUD mode is dropped, the windows kept on the desktop. */
    @Override
    public void failed(Throwable cause) {
        CrystalGuiCore.LOGGER.error("[cgui] recording the desktop failed; leaving HUD mode");
        desktop.exitHudMode();
    }
}
