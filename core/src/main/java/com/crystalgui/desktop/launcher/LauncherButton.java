package com.crystalgui.desktop.launcher;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.taskbar.Taskbar;
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

    /** Set while the pointer is over the button's own box, not merely over its menu. */
    public static final String POINTED_CLASS = "__pointed__";

    private final Launcher launcher = new Launcher();

    public LauncherButton() {
        super(NAME, "");
        addClass("__start__");
        setPreIcon(new UIElement().addClass(MARK_CLASS));
        append(launcher);
        attachListener(this::toggle);
        // THE MARK'S TINT FOLLOWS THE POINTER ON THE BUTTON ITSELF: :hover also matches over the open menu, a descendant.
        onMouseMove.attachListener((el, event) -> {
            var window = document();
            boolean over = false;
            if (window != null) over = containsSurfacePoint(window.input().pointer().x(), window.input().pointer().y());
            if (over != hasClass(POINTED_CLASS)) {
                if (over) addClass(POINTED_CLASS);
                else removeClass(POINTED_CLASS);
            }
        }, false, false);
        onMouseLeave.attachListener((el, event) -> removeClass(POINTED_CLASS), false, false);
        launcher.onClosed.connect(() -> removeClass(Taskbar.MENU_OPEN_CLASS));
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
        // START TAKES THE KEYBOARD, as Windows': the window that had it is no longer the active one while the menu is up.
        Desktop desktop = Desktop.ifPresent(document());
        if (desktop != null) desktop.windowManager().deactivate();
        launcher.showFor(this, this);
        // LIT WHILE ITS MENU IS UP, as an entry is while its jump list is: :hover also matches over the menu, being
        // this button's descendant, and goes the moment the pointer leaves both.
        addClass(Taskbar.MENU_OPEN_CLASS);
    }
}
