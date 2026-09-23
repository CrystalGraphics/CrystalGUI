package com.crystalgui.app.frameprofiler;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.widget.control.Button;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.display.FrameStatsOverlay;

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

    /** Opens the profiler, or closes it when it is already the window in front. */
    public static final String OPEN_COMMAND = "profiler.open";

    private static boolean registered;

    /**
     * The command, and the frame readout's door — idempotent, from the application layer's
     * {@code ApplicationKinds} service, because the desktop may not name an application.
     *
     * <p>F9 everywhere: the harness scene's own key for it, so the gesture learned profiling a scene is
     * the gesture in game, beside F7 and F8 for the readout.</p>
     */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        CommandRegistry.global().register(Command.of(OPEN_COMMAND, "Frame Profiler")
                .binding("F9")
                .run(context -> {
                    UIElement source = UIElement.sourceOf(context);
                    Desktop desktop = source == null ? null : Desktop.ifPresent(source.document());
                    if (desktop == null) return;
                    WindowFrame existing = desktop.registry().byKey(WINDOW_KEY);
                    if (existing != null && existing == desktop.activeWindow()) existing.requestClose();
                    else openOn(desktop);
                })
                .enabledWhen(context -> {
                    UIElement source = UIElement.sourceOf(context);
                    return source != null && Desktop.ifPresent(source.document()) != null;
                }));
        // THE READOUT'S SPARKLINE opens the frame it shows. The readout is the thing people already look
        // at, so it should be the door.
        FrameStatsOverlay.onOpenFrame(FrameProfiler::openAt);
    }

    /**
     * Opens the profiler paused on the frame with {@code frameIndex}, or on the nearest one still held.
     *
     * <pre>{@code
     * FrameProfiler.openAt(document, 412);
     * }</pre>
     */
    @Nullable
    public static WindowFrame openAt(@Nullable UIDocument document, long frameIndex) {
        WindowFrame window = open(document);
        if (window == null) return null;
        for (UIElement each : window.composedSubtree()) {
            if (each instanceof FrameProfilerPanel panel) {
                panel.model().setFollowing(false);
                panel.model().refresh();
                panel.model().selectFrameIndex(frameIndex);
                break;
            }
        }
        return window;
    }

    /** Testing seam: {@code CommandRegistry.resetForTesting()} drops the command, not this flag. */
    public static synchronized void resetForTesting() {
        registered = false;
    }

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
            // IT NEEDS NO SERVER: it measures whatever this process is doing, a title screen included.
            .standalone()
            // BEFORE THE FIRST FRAME, whether or not the window is ever opened: the ring's size and
            // "record from launch" are only worth anything if they apply before anything is recorded.
            .autostart(context -> ProfilerSettings.atLaunch(context.storage()))
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
        // THE SAME FILE A LAUNCH READS, for a window opened by key rather than through the registry.
        // A no-op when the desktop's autostart already loaded it.
        ConfigStorage config = desktop.config();
        if (config != null) ProfilerSettings.useStorage(config.scoped(StorageLayout.APPS).scoped(ID));
        WindowFrame existing = desktop.registry().byKey(WINDOW_KEY);
        if (existing != null) {
            // ACTIVATE, not raise: a closed profiler is HIDDEN (HIDE_ON_CLOSE), and raising a hidden window
            // reorders it and leaves it hidden -- the second F9 did nothing at all.
            desktop.activate(existing, true);
            return existing;
        }
        WindowFrame frame = new WindowFrame("Frame Profiler");
        frame.setKey(WINDOW_KEY);
        frame.setPolicy(WindowPolicy.HIDE_ON_CLOSE);
        frame.setApplication(KIND);
        frame.markApplicationMain();
        FrameProfilerPanel panel = new FrameProfilerPanel();
        frame.setContent(panel);
        // THE GEAR, left of the pin: the settings are the window's own, so they are reached from its
        // caption rather than from a menu this window does not have.
        Button gear = frame.addCaptionAction(WindowFrame.SETTINGS_ACTION_CLASS, "Settings", panel::toggleSettings);
        panel.onSettingsToggled.connect(open -> {
            if (open) gear.addClass(WindowFrame.ACTION_ON_CLASS);
            else gear.removeClass(WindowFrame.ACTION_ON_CLASS);
        });
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
            if (desktop != null) desktop.activate(window, true);
        }

        @Override
        public void dispose() {
            window.requestClose();
        }
    }
}
