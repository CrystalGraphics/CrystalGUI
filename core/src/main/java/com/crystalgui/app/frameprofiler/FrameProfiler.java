package com.crystalgui.app.frameprofiler;

import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;

/**
 * <b>The frame profiler</b> — an application, and the manifest a desktop lists it from.
 *
 * <pre>{@code
 * desktop.applications().launch(FrameProfiler.KIND, workspace, storage);
 * // or, from anywhere with a document:
 * FrameProfiler.open(document);
 * }</pre>
 *
 * <h3>A window, not a dialog</h3>
 *
 * <p>It is resizable, long-lived, arranged into bands, and has to be usable <b>while the application it
 * measures keeps running</b> — you cannot profile something that is blocked on a modal. So a
 * {@link WindowFrame} on the desktop, with {@link WindowPolicy#HIDE_ON_CLOSE}: closing it keeps the
 * selection and the snapshot, and the taskbar entry is how it comes back.</p>
 *
 * <h3>One instance</h3>
 *
 * <p>Two windows on one ring would double the viewer's own cost — which lands in the ring they are
 * both reading — and disagree about the selection.</p>
 */
public final class FrameProfiler {

    public static final String ID = "crystalgui:frameprofiler";

    /** The window key, so a second open raises the first rather than stacking another. */
    public static final String WINDOW_KEY = "profiler:main";

    private FrameProfiler() {
    }

    public static final ApplicationKind KIND = ApplicationKind.of(ID, "Frame Profiler")
            .icon("crystalgui:activity")
            // WHAT SOMEBODY TYPES WHEN THEY DO NOT KNOW WHAT IT IS CALLED. Nobody looking for a
            // dropped frame searches for "frame profiler"; they search for "fps" or "lag".
            .keywords("fps", "performance", "profiler", "trace", "lag", "frame time", "jank")
            // IT OPENS NOTHING. A trace file will be a DocumentKind when export grows a reader; until
            // then declaring a handler for one would put the profiler in "open with" for a file it
            // cannot read, which is worse than not being listed.
            .singleInstance()
            .launch(context -> new Instance(context.desktop()));

    /**
     * Opens the profiler on {@code document}'s desktop, or raises the one already there.
     *
     * <p>The door the HUD and the command both use. A desktop is required — there is nowhere for a
     * window to go without one — and the absence answers null rather than throwing, because a surface
     * with no desktop is an ordinary configuration rather than a mistake.</p>
     */
    @Nullable
    public static WindowFrame open(@Nullable UIDocument document) {
        if (document == null) return null;
        Desktop desktop = Desktop.ifPresent(document);
        return desktop == null ? null : openOn(desktop);
    }

    /** As {@link #open}, given the desktop already. */
    public static WindowFrame openOn(Desktop desktop) {
        WindowFrame existing = desktop.registry().byKey(WINDOW_KEY);
        if (existing != null) {
            desktop.raise(existing);
            return existing;
        }
        WindowFrame frame = new WindowFrame("Frame Profiler");
        frame.setKey(WINDOW_KEY);
        frame.setPolicy(WindowPolicy.HIDE_ON_CLOSE);
        frame.setApplication(KIND);
        frame.markApplicationMain();
        frame.setContent(new FrameProfilerPanel());
        desktop.addWindow(frame);
        // AFTER addWindow, which is what places and sizes it -- a size written first is overwritten.
        // Clamped to the work area: the bands below sum to well over a short desktop's height, and a
        // window taller than the desktop pushes its own tabs off the bottom.
        float available = desktop.box() == null ? 0f : desktop.box().height();
        frame.resizeTo(720f, available > 0f ? Math.min(520f, available - 60f) : 520f);
        return frame;
    }

    /** One running profiler. */
    private static final class Instance implements Application {

        private final WindowFrame window;

        Instance(Desktop desktop) {
            window = openOn(desktop);
        }

        @Override
        public ApplicationKind kind() {
            return KIND;
        }

        @Override
        public WindowFrame mainWindow() {
            return window;
        }

        @Override
        public boolean open(Resource resource) {
            return false;       // not mine — see KIND's note on declaring no handler
        }

        @Override
        public void activate() {
            Desktop desktop = Desktop.ifPresent(window.document());
            if (desktop != null) desktop.raise(window);
        }

        @Override
        public void dispose() {
            window.requestClose();
        }
    }
}
