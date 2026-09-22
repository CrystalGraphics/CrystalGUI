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

    /** The mark on the button. Four panes, as every start button since 1995 has drawn. */
    public static final String GLYPH_CLASS = "__start-glyph__";

    /** One of the glyph's four panes. */
    public static final String PANE_CLASS = "__start-pane__";

    private final Launcher launcher = new Launcher();

    public LauncherButton() {
        super(NAME, "");
        addClass("__start__");
        setPreIcon(glyph());
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

    /**
     * The four-pane mark.
     *
     * <p>Four real elements in a grid rather than an SVG: two CSS rules against an asset to ship, and
     * the mark has to read at twelve logical pixels, where a stroked outline icon would not. An empty
     * grid draws nothing, so the panes are what make it a mark at all.</p>
     */
    private static UIElement glyph() {
        UIElement mark = new UIElement();
        mark.addClass(GLYPH_CLASS);
        for (int pane = 0; pane < 4; pane++) {
            UIElement quarter = new UIElement();
            quarter.addClass(PANE_CLASS);
            mark.append(quarter);
        }
        return mark;
    }
}
