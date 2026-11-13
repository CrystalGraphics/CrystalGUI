package com.crystalgui.workbench.toolwindow;

import javax.annotation.Nullable;

import com.crystalgui.desktop.app.AppWindow;
import com.crystalgui.desktop.app.FloatSpace;
import com.crystalgui.desktop.app.WindowSpec;
import com.crystalgui.desktop.window.WindowBounds;
import com.crystalgui.workbench.view.ViewContainer;

/**
 * A tool window out of its region: a window of the desktop, owned by the workbench's own, that can be dragged anywhere on
 * the desktop and not only inside the window it came from. The view stays in the workbench's document and is shown through
 * the window.
 *
 * <pre>{@code
 * ToolWindowFloat library = new ToolWindowFloat(floats, "library", "Library", container, ToolWindowType.FLOATING,
 *         remembered, () -> manager.dockPanel("library"), () -> manager.hidePanel("library"));
 * library.bounds();      // where it is, for the next restore
 * library.destroy();
 * }</pre>
 *
 * <ul>
 *   <li><b>One header:</b> the container's own header (title, the view's controls) is adopted into the window's caption,
 *       so the caption has a single bar. The window's own label is left empty for that reason.</li>
 *   <li><b>Dock and Hide</b> are the caption's controls, IntelliJ's pair: Dock returns the tool window to the region it
 *       belongs to, and the minimise button and the X both mean Hide.</li>
 *   <li><b>No maximise, no taskbar entry:</b> a tool window sits beside the work and belongs to its owner, which
 *       represents it on the taskbar.</li>
 *   <li>It is built fresh each time it is shown and destroyed when it is hidden; the container is the stateful part and
 *       outlives it.</li>
 * </ul>
 */
public final class ToolWindowFloat {

    /** On any tool window's frame, whichever mode it is in. */
    public static final String TOOL_WINDOW_CLASS = "__tool-window__";

    /** On a frame owned by the workbench's window. See {@link ToolWindowType#FLOATING}. */
    public static final String FLOATING_CLASS = "__floating__";

    /** On a top-level tool window frame. See {@link ToolWindowType#WINDOWED}. */
    public static final String WINDOWED_CLASS = "__windowed__";

    /** The "put it back in its region" affordance. */
    public static final String DOCK_CLASS = "__dock__";

    /** What it says on hover, beside the window controls it sits with. */
    public static final String DOCK_TOOLTIP = "Dock";

    /** Where a float first appears when nothing has been remembered, in logical pixels. */
    public static final float DEFAULT_WIDTH = 320f;
    public static final float DEFAULT_HEIGHT = 240f;

    private final String typeId;
    private final ViewContainer container;
    private final AppWindow window;
    private ToolWindowType mode;

    /**
     * Opens the window.
     *
     * @param remembered where it was last, or null for the default size where the desktop puts it; a zero size is refused
     * @param onDock     Dock was pressed, on the workbench's thread
     * @param onHidden   the window was hidden by its X or minimise, on the workbench's thread
     */
    public ToolWindowFloat(FloatSpace floats, String typeId, String title, ViewContainer container, ToolWindowType mode,
                           @Nullable ToolWindowState.Bounds remembered, Runnable onDock, Runnable onHidden) {
        this.typeId = typeId;
        this.container = container;
        this.mode = mode;
        boolean usable = remembered != null && remembered.width() > 0f && remembered.height() > 0f;
        // KEYED, so a session restore finds the window that belongs to this tool window rather than opening a second one.
        // Not a claim on the desktop's record: a tool window's geometry is per project and lives in ToolWindowState.
        WindowSpec spec = WindowSpec.titled("").key("toolwindow:" + typeId).toolWindow().hideOnClose().unmaximizable()
                .styled(TOOL_WINDOW_CLASS).styled(classOf(mode))
                .size(usable ? remembered.width() : DEFAULT_WIDTH, usable ? remembered.height() : DEFAULT_HEIGHT);
        if (usable) spec.at(remembered.left(), remembered.top());
        this.window = floats.openOwned(spec);
        // THE NAME IS KEPT ON THE WINDOW'S OWN LABEL EMPTY, the adopted header already says it; the label is also the
        // one pointer-transparent element in the caption, which is what lets a press reach the title bar's move gesture.
        window.setContent(container);
        window.addCaptionAction(DOCK_CLASS, DOCK_TOOLTIP, onDock);
        window.onHidden(onHidden);
    }

    private static String classOf(ToolWindowType mode) {
        return mode == ToolWindowType.FLOATING ? FLOATING_CLASS : WINDOWED_CLASS;
    }

    /** Which tool window this is showing. */
    public String typeId() {
        return typeId;
    }

    /** The container it hosts: the same instance the region shows when this is docked back. */
    public ViewContainer container() {
        return container;
    }

    /** The window that shows it. */
    public AppWindow window() {
        return window;
    }

    public ToolWindowType mode() {
        return mode;
    }

    /**
     * Switches between the two windowed modes, which differ in a style class. {@link ToolWindowType#DOCKED} is refused: a
     * docked tool window has no window at all, which is the manager's call.
     */
    public void setMode(ToolWindowType nowMode) {
        if (nowMode == null || nowMode == ToolWindowType.DOCKED || nowMode == mode) return;
        window.styleClass(classOf(mode), false);
        mode = nowMode;
        window.styleClass(classOf(mode), true);
    }

    /** Where it is and how big, for {@link ToolWindowState#floatingBounds()}: never a zero box. */
    public ToolWindowState.Bounds bounds() {
        WindowBounds bounds = window.bounds(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        return new ToolWindowState.Bounds(bounds.left(), bounds.top(), bounds.width(), bounds.height());
    }

    /** Ends the window. */
    public void destroy() {
        window.destroy();
    }
}
