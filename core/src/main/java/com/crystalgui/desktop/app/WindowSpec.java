package com.crystalgui.desktop.app;

import javax.annotation.Nullable;

import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.ui.dom.UIElement;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * What an application asks of the window manager when it opens a window. It is a request: the manager decides
 * where the window goes, and the application never holds the frame that draws it.
 *
 * <pre>{@code
 * // The common case: a titled window with its content, sized by the application's kind unless told otherwise
 * AppWindow main = app.windows().open(WindowSpec.titled("Planner").icon("mymod:planner").content(new PlannerView()));
 *
 * // A window of a given size that opens behind and is put back where the user left it
 * app.windows().open(WindowSpec.titled("Log").size(480, 320).key("mymod:log").background());
 * }</pre>
 *
 * <ul>
 *   <li>Every setter returns the spec, so a request reads as one expression.</li>
 *   <li>An unset size or place is the manager's to choose: the kind's {@link ApplicationKind#windowSize}, then the
 *       cascade. A {@link #key} that has been seen before restores the last place and size instead.</li>
 *   <li>Set {@link #content} here rather than after opening: the window is then placed and sized with it.</li>
 *   <li>A spec can open more than one window; it holds no state of a window.</li>
 * </ul>
 */
@Getter
@Setter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WindowSpec {

    /** The caption, and the name the taskbar and the switcher show. */
    @Setter(AccessLevel.NONE)
    private String title = "";
    /** A namespaced icon id, as the taskbar and the title bar show it. */
    @Nullable
    private String icon;
    /** The name the window's place and size are remembered under. */
    @Nullable
    private String key;
    /** What the window shows; set before it opens, so it is placed and sized with it. */
    @Nullable
    private UIElement content;
    /** False keeps the title out of the caption (the taskbar still reads it), as a workbench's menu bar does. */
    private boolean titleShown = true;
    /** Opens already pinned above ordinary windows. */
    private boolean pinned;
    /** What the X does: destroy the window (the default), or hide it and let the application run on. */
    private WindowPolicy policy = WindowPolicy.DESTROY_ON_CLOSE;

    @Setter(AccessLevel.NONE)
    private float width, height;
    @Setter(AccessLevel.NONE)
    private float left, top;
    @Setter(AccessLevel.NONE)
    private boolean placed;
    @Setter(AccessLevel.NONE)
    @Nullable
    private AppWindow owner;
    @Setter(AccessLevel.NONE)
    private boolean takesFocus = true;

    public static WindowSpec titled(String title) {
        WindowSpec spec = new WindowSpec();
        spec.title = title == null ? "" : title;
        return spec;
    }

    /** The size to open at, in pixels; both must be positive or the kind's default applies. */
    public WindowSpec size(float width, float height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /** Where to open, in the work area's coordinates; without it the manager cascades the window. */
    public WindowSpec at(float left, float top) {
        this.left = left;
        this.top = top;
        this.placed = true;
        return this;
    }

    /** The window this one belongs to: it minimises and closes with it, and stays above it. */
    public WindowSpec ownedBy(@Nullable AppWindow owner) {
        this.owner = owner;
        return this;
    }

    /** Opens without taking focus, as a server's window does: it asks for attention instead. */
    public WindowSpec background() {
        this.takesFocus = false;
        return this;
    }

    /** The X hides the window instead of destroying it; a taskbar entry brings it back. */
    public WindowSpec hideOnClose() {
        this.policy = WindowPolicy.HIDE_ON_CLOSE;
        return this;
    }
}
