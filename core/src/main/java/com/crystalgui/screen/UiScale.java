package com.crystalgui.screen;

import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgDisplayScale;
import com.crystalgui.core.async.Broadcast;
import com.crystalgui.core.settings.Preferences;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.ui.dom.UIDocument;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nullable;

/**
 * The scale a document draws at: the screen's, times the desktop's, times its application's zoom. The one place the
 * three are multiplied; a document follows it with {@link #bind}.
 *
 * <pre>{@code
 * // The desktop's own document, its frames and taskbar: the screen and the desktop scale
 * Connection follow = UiScale.bind(document, null);
 *
 * // An application's document, and the popups it opens: times appearance.zoom.<id>
 * Connection follow = UiScale.bind(document, "crystalgui:editor");
 *
 * float now = UiScale.of("crystalgui:editor");
 * UiScale.zoomIn("crystalgui:editor");      // the next ZOOM_STEPS entry; zoomOut, resetZoom
 * }</pre>
 *
 * <p>Easy to get wrong: a document whose scale is set by hand ({@code boxes().setUiScale}) no longer follows. A
 * fixed-scale screen does that on purpose and never binds.</p>
 */
public final class UiScale {

    /** The zoom steps, as a browser's: Ctrl + and Ctrl − move along them, Ctrl 0 returns to 1. */
    public static final float[] ZOOM_STEPS = {0.5f, 0.67f, 0.75f, 0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f};

    /** {@code -Dcrystalgui.ui.screenScale=<n>}: the screen's part, in place of the platform's. Gates pin it to 1. */
    private static final float SCREEN_OVERRIDE = readOverride();

    /** Posted by {@link #poll} when the screen's part moves: a window taken to another monitor. */
    private static final Broadcast<Float> SCREEN_MOVED = Broadcast.create("ui-screen-scale");
    private static float lastScreen = 1f;
    private static float lastCap = Float.MAX_VALUE;

    /** The smallest desktop a zoom may leave, in logical pixels: what fits a 1280 x 800 screen at 125 %. */
    public static final int MIN_DESKTOP_WIDTH = 1024, MIN_DESKTOP_HEIGHT = 640;

    // Weak: a closed application's document is not kept alive by having been zoomed.
    private static final Map<UIDocument, String> APPS = Collections.synchronizedMap(new WeakHashMap<>());

    private UiScale() {
    }

    /** The screen's part: the operating system's scale for the host's window, 1.5 at 150 %. */
    public static float screen() {
        if (SCREEN_OVERRIDE > 0f) return SCREEN_OVERRIDE;
        float scale = CgPlatform.get(CgDisplayScale.SERVICE).scale();
        return scale >= 0.5f && scale <= 8f ? scale : 1f;
    }

    /** Re-applies every bound document's scale if the screen's part or the surface's cap moved since the last call. Render thread, each host frame. */
    public static void poll() {
        float now = screen(), cap = cap();
        if (now == lastScreen && cap == lastCap) return;
        lastScreen = now;
        lastCap = cap;
        SCREEN_MOVED.post(now);
    }

    /** The desktop's scale: what its frames, taskbar and menus draw at. */
    public static float desktop() {
        return capped(screen() * Preferences.current().desktopScale());
    }

    /** What {@code app}'s documents draw at; the desktop's for null. */
    public static float of(@Nullable String app) {
        return app == null ? desktop() : capped(desktop() * Preferences.current().zoom(app));
    }

    /** {@code scale}, no larger than leaves the surface {@link #MIN_DESKTOP_WIDTH} by {@link #MIN_DESKTOP_HEIGHT}. */
    private static float capped(float scale) {
        return Math.min(scale, cap());
    }

    /** The largest scale the surface allows, never below the screen's own; unbounded before the surface exists. */
    private static float cap() {
        int width = CgGraphicsLifecycle.getCurrentWidth(), height = CgGraphicsLifecycle.getCurrentHeight();
        if (width <= 0 || height <= 0) return Float.MAX_VALUE;
        return Math.max(screen(), Math.min(width / (float) MIN_DESKTOP_WIDTH, height / (float) MIN_DESKTOP_HEIGHT));
    }

    /** The application {@code document} was bound for; null for the desktop's own and for an unbound one. */
    @Nullable
    public static String appOf(UIDocument document) {
        return APPS.get(document);
    }

    /** Sets {@code document}'s scale, now or at its next turn, and again whenever a part of it changes. Any thread. */
    public static Connection bind(UIDocument document, @Nullable String app) {
        if (app != null) APPS.put(document, app);
        document.execute(() -> apply(document, app));
        Connection preferences = Preferences.PUBLISHED.changes().subscribe(document::execute, () -> apply(document, app));
        Connection screen = SCREEN_MOVED.subscribe(document::execute, () -> apply(document, app));
        return () -> {
            preferences.disconnect();
            screen.disconnect();
        };
    }

    /** {@code app}'s zoom one step up. */
    public static void zoomIn(String app) {
        step(app, +1);
    }

    /** {@code app}'s zoom one step down. */
    public static void zoomOut(String app) {
        step(app, -1);
    }

    /** {@code app}'s zoom back to 1. */
    public static void resetZoom(String app) {
        Preferences.update(p -> p.withZoom(app, 1f));
    }

    /** The step {@code direction} away from {@code zoom}: the nearest listed one beyond it, clamped to the list. */
    static float stepFrom(float zoom, int direction) {
        if (direction > 0) {
            for (float each : ZOOM_STEPS) if (each > zoom + 1e-4f) return each;
            return ZOOM_STEPS[ZOOM_STEPS.length - 1];
        }
        for (int i = ZOOM_STEPS.length - 1; i >= 0; i--) if (ZOOM_STEPS[i] < zoom - 1e-4f) return ZOOM_STEPS[i];
        return ZOOM_STEPS[0];
    }

    private static void step(String app, int direction) {
        Preferences.update(p -> p.withZoom(app, stepFrom(p.zoom(app), direction)));
    }

    private static void apply(UIDocument document, @Nullable String app) {
        float scale = of(app);
        if (document.boxes().uiScale() != scale) document.boxes().setUiScale(scale);
    }

    private static float readOverride() {
        String held = System.getProperty("crystalgui.ui.screenScale");
        if (held == null) return 0f;
        try {
            return Float.parseFloat(held);
        } catch (NumberFormatException unreadable) {
            return 0f;
        }
    }
}
