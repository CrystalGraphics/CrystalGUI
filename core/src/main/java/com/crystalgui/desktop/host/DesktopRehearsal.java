package com.crystalgui.desktop.host;

import com.crystalgraphics.render.graph.CgRehearsal;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.style.theme.UiThemeManager;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.viewport.CgUiViewport;

import javax.annotation.Nullable;

/**
 * The desktop rehearsed behind the loading screen, as CrystalGraphics rehearses a move: {@link CgRenderStage#SCREEN}
 * fired into the rehearsal's hidden target with a throwaway desktop on it, through the path a real open takes (the
 * stage, the presenter, the composite), until the JIT has settled. The first open then finds its pipelines, blur
 * targets, rasters and compiled code in place, where its first paint was a stall of hundreds of ms inside the open
 * animation.
 *
 * <p>Its own document, theme and clock: the live desktop is never attached, so its open animation is untouched.</p>
 *
 * <pre>{@code
 * DesktopRehearsal rehearsal = new DesktopRehearsal();
 * while (!rehearsal.step()) { }          // a pass or a few a call, from a load task; render thread
 * // HostSession's SCREEN renderer, while a pass runs:
 * if (CgRehearsal.active()) rehearsal.paint(stage);
 * }</pre>
 *
 * <ul>
 *   <li>Its document is bound to the render thread at its first pass, so every step runs there.</li>
 *   <li>Every SCREEN renderer is rehearsed with it, a mod's included: what it rehearses is the stage.</li>
 * </ul>
 */
final class DesktopRehearsal {

    /**
     * Its length, in passes. A cold desktop spends its first-use cost in its first three frames (199, 32, 19 ms), then
     * about 7 ms each: a few passes past the JIT's settling, never the thousands a move's rehearsal runs.
     */
    private static final int MIN_PASSES = 5, MAX_PASSES = 120, QUIET_WINDOW = 4;
    private static final float DELTA = 1f / 60f;

    private final CgRehearsal rehearsal = CgRehearsal.of(pass -> CgRenderStage.SCREEN.rehearse())
            .minPasses(MIN_PASSES).maxPasses(MAX_PASSES).window(QUIET_WINDOW);
    /** Its own, inline: closed when it settles, so the throwaway desktop's targets and rasters go with it. */
    @Nullable
    private CgUiViewport viewport;
    @Nullable
    private DesktopContent content;
    private final long begun = System.nanoTime();
    private int passes;

    /** Runs passes within this frame's warm budget; true once it has settled or failed. Render thread. */
    boolean step() {
        if (!rehearsal.step()) return false;
        if (viewport != null) viewport.close();
        viewport = null;
        content = null;
        RuntimeException failure = rehearsal.failure();
        if (failure != null) {
            CrystalGuiCore.LOGGER.error("[cgui] the desktop rehearsal failed; the first open pays for it", failure);
        } else {
            CrystalGuiCore.LOGGER.info("[cgui] desktop rehearsal: {} passes in {} ms", passes,
                    (System.nanoTime() - begun) / 1_000_000L);
        }
        return true;
    }

    /** One frame of the throwaway desktop in a rehearsal's firing of {@code SCREEN}. Render thread. */
    void paint(CgStageFrame stage) {
        if (viewport == null) {
            Desktop desktop = build();
            viewport = CgUiViewport.open(desktop.document(), CgUiViewport.Threading.INLINE, "rehearsal");
            content = new DesktopContent(desktop, DesktopPresentation.DESKTOP);
        }
        viewport.frame(stage, DELTA, content);
        passes++;
    }

    private static Desktop build() {
        UIDocument document = new UIDocument();
        document.boxes().setUiScale(DesktopHost.UI_SCALE);
        UiThemeManager.getInstance().installInto(document.styles());
        Desktop desktop = Desktop.of(document);
        desktop.addWindow(new WindowFrame("Warm-up"));
        return desktop;
    }
}
