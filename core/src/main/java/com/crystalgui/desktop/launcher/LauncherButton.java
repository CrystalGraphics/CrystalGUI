package com.crystalgui.desktop.launcher;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;

/**
 * The taskbar's start button — the one fixed thing on a strip that is otherwise whatever is running.
 *
 * <pre>{@code
 * LauncherButton start = new LauncherButton();
 * taskbar.append(start);      // Taskbar does this itself; nobody else builds one
 * }</pre>
 *
 * <h3>It owns the popover</h3>
 *
 * <p>One {@link Launcher} per button rather than one per desktop, because the popover anchors to this
 * element and a shared one would have to be re-anchored on every open — and a taskbar on a second
 * surface would be fighting the first for it. The panel is cheap: its list is refilled from the
 * registry on every open, so nothing is kept between them but the widget.</p>
 *
 * <p>Clicking while open <b>closes</b> it. A start button that reopened its own menu on the click that
 * light-dismissed it is the oldest bug in this shape of control.</p>
 */
public class LauncherButton extends Button {

    public static final Name NAME = Name.of("launcherbutton");

    /** The slot the sheet draws the start mark into: the logo's C and gem, {@code crystalgui:start}. */
    public static final String MARK_CLASS = "__start-mark__";

    private final Launcher launcher = new Launcher();

    public LauncherButton() {
        super(NAME, "");
        addClass("__start__");
        setPreIcon(new UIElement().addClass(MARK_CLASS));
        append(launcher);
        attachListener(this::toggle);
    }

    public Launcher launcher() {
        return launcher;
    }

    public void toggle() {
        if (launcher.isOpen()) {
            launcher.hide();
            return;
        }
        launcher.refill();
        launcher.showFor(this, this);
    }
}
