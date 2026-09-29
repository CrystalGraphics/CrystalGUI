package com.crystalgui.app.frameprofiler;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.extension.WorkbenchExtension;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.toolwindow.ToolWindowKind;

/**
 * The Frame Profiler docked in a workbench — the same panel the window hosts, as a tool window.
 *
 * <pre>{@code
 * WorkbenchApplication.of(ctx)
 *         .with(FrameProfilerExtension.ID)      // a bottom-panel tab, beside Problems
 *         ...
 * }</pre>
 *
 * <p>Available, not enabled: an application that wants it names {@link #ID}. The window is still the
 * door everywhere else, and one is enough — two panels on one ring would each record their own work
 * into it and disagree about the selection.</p>
 *
 * <p>The panel is written against the model and the ring, not against a window, which is the whole of
 * what makes this a dozen lines. Its settings are the window's: the gear lives on the window's caption.</p>
 */
public final class FrameProfilerExtension implements WorkbenchExtension {

    public static final String ID = "crystalgui:frameprofiler.panel";

    /** The panel type id — a session record and a stripe button both name it. */
    public static final String TYPE = "frameprofiler";

    /** Reveals the panel. */
    public static final String SHOW = "workbench.showFrameProfiler";

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public FrameProfilerExtension() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(WorkbenchContext workbench) {
        // ONE PANEL, whichever call asks: the factory's contract is the same instance every time.
        FrameProfilerPanel[] panel = new FrameProfilerPanel[1];
        return workbench.registerToolWindow(ToolWindowKind.of(TYPE, "Frame Profiler")
                .icon("crystalgui:activity")
                // THE BOTTOM PANEL: it is wide and short there, which is the shape the strip and the chart
                // were drawn for.
                .region(DockRegion.PANEL)
                // BUILT ON FIRST SHOW, not at activation: a profiler nobody opened must not refresh four
                // times a second inside every workbench that merely offers it.
                .view(context -> {
                    if (panel[0] == null) panel[0] = new FrameProfilerPanel();
                    return panel[0];
                })
                .toggle(SHOW));
    }
}
