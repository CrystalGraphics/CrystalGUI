package com.crystalgui.workbench.dock;

import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.app.AppWindow;
import com.crystalgui.desktop.app.WindowSpec;
import com.crystalgui.desktop.window.WindowBounds;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.service.Focus;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;

/**
 * A window of the desktop holding a dock area of its own: where an editor tab lands when it is torn out.
 *
 * <pre>{@code
 * DockWindow torn = new DockWindow(workbench.dock(), layout, "notes.md");
 * }</pre>
 *
 * <ul>
 *   <li><b>A peer, not an accessory:</b> not owned by the window it came from, so clicking one buries the other as two
 *       document windows do. A tool window is the owned kind.</li>
 *   <li><b>A window of the desktop</b>, so it can leave the window it came from; the dock inside it stays in the
 *       workbench's document and is shown through the window.</li>
 *   <li><b>Its editors are still the workbench's:</b> its dock belongs to the {@linkplain DockArea#home() home} it was torn
 *       from, which answers what is active and where the next file opens across every window.</li>
 *   <li><b>It closes when it empties</b>, but never while a drag runs: an empty dock is a legitimate transient state
 *       between a tab leaving and landing, and closing then would take the drop target with it.</li>
 * </ul>
 */
public final class DockWindow {

    /** On the frame, so a sheet can tell a document window from a tool window's. */
    public static final String DOCK_WINDOW_CLASS = "__dock-window__";

    /** Where a torn-out editor appears when nothing says otherwise, in logical pixels. */
    public static final float DEFAULT_WIDTH = 480f;
    public static final float DEFAULT_HEIGHT = 360f;

    private final DockArea area;
    private final AppWindow window;
    private final String title;
    private boolean closePending;

    /** Opens it at the default size where the desktop puts it. @param from the dock it was torn out of; it joins that dock's home */
    public DockWindow(DockArea from, DockLayout layout, String title) {
        this(from, layout, title, DEFAULT_WIDTH, DEFAULT_HEIGHT, Float.NaN, Float.NaN);
    }

    /** Opens it at {@code width} by {@code height}, at {@code (left, top)} unless that is NaN. */
    public DockWindow(DockArea from, DockLayout layout, String title, float width, float height, float left, float top) {
        this.title = title;
        this.area = new DockArea(from, layout);
        DockArea home = area.home();
        StyleGroup.defaultPipeline(area.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(0).flexGrow(1f));
        WindowSpec spec = WindowSpec.titled(title).styled(DOCK_WINDOW_CLASS).size(width, height).content(area);
        // STILL THE PRODUCT IT CAME FROM. @see DockArea#tornWindowIcon
        if (home.tornWindowIcon() != null) spec.icon(home.tornWindowIcon());
        if (!Float.isNaN(left) && !Float.isNaN(top)) spec.at(left, top);
        this.window = home.floats().open(spec);
        home.adopt(this);
        window.onClosed(() -> home.release(this));
        // A LAYOUT CHANGE IS THE ONLY MOMENT THIS CAN BECOME TRUE, so it is the only moment worth asking: dragging the
        // last tab back into the main editor leaves a window with nothing in it and no way to get anything back into it.
        area.onDidChangeLayout.connect(this::closeIfEmptyOnceIdle);
        // ...AND THE MOMENT THERE IS SOMETHING TO FOCUS. A dock builds its groups a frame after the activation that
        // focused this window. @see #handOnFocus
        area.onDidChangeLayout.connect(this::handOnFocus);
    }

    /** The dock inside it: the same widget the main workbench uses, with its own layout. */
    public DockArea area() {
        return area;
    }

    /** The window that shows it. */
    public AppWindow window() {
        return window;
    }

    /** The name it was torn out under; it does not track what is in it afterwards. */
    public String title() {
        return title;
    }

    /** Where it is and how big, for a session record. */
    public WindowBounds bounds() {
        return window.bounds(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * Passes focus on to the editor once the dock has built one.
     *
     * <p>The window is focused a frame before it has any content, so at activation there is no tab to focus and focus
     * sits on the dock itself, where a keystroke reaches nothing: events travel root to target and back, and the editor
     * is a descendant. Handed on the first time the layout says there is something to give it to. <b>It hands focus on,
     * never takes it:</b> focus anywhere but the dock itself is left alone. Pointer focus, so it does not ring.</p>
     */
    private void handOnFocus() {
        UIDocument document = area.document();
        if (document == null) return;
        Focus focus = document.focus();
        if (focus.focused() != area) return;
        UIElement wanted = editor();
        if (wanted != null && wanted != area) focus.requestPointerFocus(wanted);
    }

    /** The editor in the active tab, never the dock that holds it, or null. */
    private UIElement editor() {
        DockGroup group = area.activeGroup();
        Tab tab = group == null ? null : group.tabView().getSelectedTab();
        UIDocument document = area.document();
        return tab == null || document == null ? null : document.focus().firstFocusableIn(tab.content());
    }

    /** Whether this window still holds a panel. */
    public boolean isEmpty() {
        for (DockLeaf leaf : area.layout().leaves()) {
            if (!leaf.isEmpty()) return false;
        }
        return true;
    }

    /**
     * Destroys the window if its last panel has gone.
     *
     * @return whether it closed
     */
    public boolean closeIfEmpty() {
        if (!isEmpty()) return false;
        window.destroy();
        return true;
    }

    /** Closes when empty, once no drag is running: the decision is made now and the act waits for the pointer's release. */
    private void closeIfEmptyOnceIdle() {
        if (!isEmpty() || closePending) return;
        UIDocument document = area.document();
        if (document == null) return;
        closePending = true;
        document.animation().every(area, delta -> {
            if (window.state() == WindowState.DESTROYED) return false;
            if (document.input().mode(Drag.class) != null) return true;
            closePending = false;
            closeIfEmpty();
            return false;
        });
    }
}
