package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.chrome.MenuBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * The keyboard route to a window's system menu — {@code Alt+Space} (W13a).
 *
 * <h3>Why this is not {@code ContextMenu.attach}</h3>
 *
 * <p>{@code ContextMenu.attach} is the <em>pointer</em> route: it hangs a listener on an element, waits
 * for a right-click, and anchors the menu at the press. Both right-click routes use it and should. But
 * {@code Alt+Space} has no press to anchor to and no element under a pointer — it arrives from the
 * keymap with only the focused element to go on — so it needs the same menu built and shown at the
 * window's own corner, which is where Win32 puts it.</p>
 *
 * <p>The <b>rows</b> are shared regardless: all three routes build {@link MenuId#WINDOW_SYSTEM} through
 * {@link MenuBuilder}, which is the one place commands become rows. What differs is only the anchor.</p>
 *
 * <h3>One live menu, and it is per window</h3>
 *
 * <p>Held on the frame rather than statically, for the reason {@code ContextMenu.attach} records about
 * its own attachment site: leaving the previous menu in the tree crashes Taffy outright, because
 * promotion reparents a popover's node to the root and the next insertion is computed against a child
 * list that has quietly emptied. Per window rather than per process because two windows may each have
 * one open — a right-click on one taskbar entry while another window's menu is up.</p>
 */
public final class SystemMenu {

    private SystemMenu() {
    }

    /**
     * Opens {@code frame}'s system menu under its title bar.
     *
     * <p>Positioned in <b>root space</b>, which is what {@code Menu.showAt} takes: a frame's own
     * {@code getX()}/{@code getY()} are already the desktop's local coordinates and the desktop fills the
     * root, so no conversion is needed — but the distinction is worth stating, because the pointer route
     * <em>does</em> convert (a {@code MouseEvent} reports physical pixels, and at {@code uiScale} 2 that
     * places the menu at twice its distance from the corner).</p>
     */
    public static void showFor(WindowFrame frame) {
        UIWindow window = frame.getAttachedWindow();
        if (window == null) return;

        discardFor(frame);

        // BUILT AGAINST THE FRAME, so every enabledWhen resolves to this window rather than to whatever
        // happens to be focused. That matters most for the route that has no pointer: Alt+Space with
        // focus inside window A must not offer window B's rows.
        Menu menu = MenuBuilder.build(MenuId.WINDOW_SYSTEM, CommandRegistry.global(), frame);
        List<Menu> live = MenuBuilder.present(menu, frame, window);
        LIVE.put(frame, live);
        menu.onClosed.connect(() -> discardFor(frame));

        // UNDER the title bar, at its left edge -- Win32's placement, and the one that does not cover the
        // caption you just asked about. Read off the BAR's own measured box rather than the frame's
        // position: it is already in the same space showAt wants, and it carries the caption height
        // without anybody having to know what a caption is.
        var box = frame.titleBar().getRuntimeCache();
        menu.showAt(box.getX(), box.getY() + box.getHeight(), null);
    }

    /** Closes and drops {@code frame}'s menu chain, if it has one. */
    public static void discardFor(WindowFrame frame) {
        List<Menu> live = LIVE.remove(frame);
        if (live != null) MenuBuilder.discard(new ArrayList<>(live));
    }

    /**
     * Frame → its live chain.
     *
     * <p><b>Weak-keyed</b>, so a destroyed window that never closed its menu cannot keep itself, its
     * whole content subtree and its documents reachable from a static map for the life of the process.
     * The same reason {@code StyleEngine} keeps its applied-slot record weakly.</p>
     */
    private static final java.util.Map<WindowFrame, List<Menu>> LIVE = new java.util.WeakHashMap<>();
}
