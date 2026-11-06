package com.crystalgui.desktop.host;

import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.render.UiFrame;
import com.crystalgui.render.UiGpu;
import com.crystalgui.style.theme.UiThemeManager;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;

/**
 * Draws a throwaway desktop behind the loading screen, so the first real open finds its pipelines, blur targets and
 * rasters built. The desktop's first paint otherwise lands inside the open animation as a stall of hundreds of ms.
 *
 * <p>Its own document, theme and clocks: the live desktop is never attached, so its open animation is untouched.
 * Frames execute into the UI's own target and are never composited ({@link UiGpu#warm}).</p>
 *
 * <pre>{@code
 * DesktopWarmup warmup = new DesktopWarmup();
 * while (!warmup.step(width, height)) { }   // one frame a call, from a load task; render thread
 * }</pre>
 *
 * <p>Easy to get wrong: its document is bound to the thread of the first {@link #step}, so every step runs there.</p>
 */
final class DesktopWarmup {

    /** Frames drawn, and at least this long: the bar's entry slide and the first window's glass are both seen. */
    private static final int FRAMES = 40;
    private static final long MIN_NANOS = 600_000_000L;
    private static final float DELTA = 1f / 60f;

    @Nullable
    private Desktop desktop;
    private int drawn;
    private long begun;

    /** Draws one frame. Returns true once the warm-up is complete. */
    boolean step(int width, int height) {
        if (desktop == null) {
            begun = System.nanoTime();
            desktop = build();
        }
        UiFrame frame = desktop.record(DesktopPresentation.DESKTOP, DELTA, width, height);
        if (frame != null) UiGpu.warm(frame);
        drawn++;
        return drawn >= FRAMES && System.nanoTime() - begun >= MIN_NANOS;
    }

    private static Desktop build() {
        UIDocument document = new UIDocument();
        document.boxes().setUiScale(HostServices.DEFAULT_UI_SCALE);
        UiThemeManager.getInstance().installInto(document.styles());
        Desktop desktop = Desktop.of(document);
        desktop.addWindow(new WindowFrame("Warm-up"));
        return desktop;
    }
}
