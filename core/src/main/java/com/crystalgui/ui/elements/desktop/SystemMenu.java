package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.ui.AnchoredPlacement;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.chrome.MenuBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a window's system menu is PUT — the two placements that are not a pointer (W13a).
 *
 * <h3>The rows are shared; only the anchor differs</h3>
 *
 * <p>All three routes build {@link MenuId#WINDOW_SYSTEM} through {@link MenuBuilder}, which is the one
 * place commands become rows. A title-bar right-click is the straightforward one and uses
 * {@code ContextMenu.attach}, which anchors at the press. The other two cannot:</p>
 *
 * <ul>
 *   <li>{@link #showFor} is the <b>keyboard</b> route. {@code Alt+-} has no press to anchor to and no
 *       element under a pointer, so the menu hangs under the window's own caption — Win32's placement.</li>
 *   <li>{@link #showJumpList} is the <b>taskbar</b> route. Anchoring at the pointer puts a menu at the
 *       very bottom edge of the screen, left-aligned from wherever the press landed and drifting further
 *       from its entry the wider it gets. Windows centres a jump list over its button, and so does
 *       this.</li>
 * </ul>
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

    /** The gap between a jump list and the entry it belongs to — the previews use the same. */
    private static final float GAP = 4f;

    /**
     * On a system menu opened from a taskbar entry — Windows' jump list.
     *
     * <p>What it buys is the <b>preview panel's surface</b>: same fill, same border, same radius. From
     * the strip's point of view a jump list and a hover preview are the same object — a panel belonging
     * to one entry, floating above it — and they read as one idea only if they are drawn as one.</p>
     */
    public static final String JUMP_LIST_CLASS = "__jump-list__";

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
        // happens to be focused. That matters most for the route that has no pointer: Alt+- with
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

    /**
     * Opens {@code frame}'s menu <b>centred above {@code anchor}</b> — a taskbar entry's jump list.
     *
     * <h3>Centred, because the anchor is a label for the thing beneath it</h3>
     *
     * <p>{@code AnchoredPlacement.resolve} left-aligns on the cross axis, which is right for what it was
     * written for — a dropdown hangs from its button's left edge — and wrong here for the same reason
     * the taskbar's hover previews centre themselves: Windows' jump list sits over the middle of its
     * button, and an entry's menu that started at its left edge would drift further from the entry the
     * wider the menu got. Centred in the consumer, never in {@code AnchoredPlacement}, which every
     * dropdown in the engine depends on.</p>
     *
     * <h3>Above, and measured on the second frame</h3>
     *
     * <p>The strip is at the bottom of the desktop, so the menu has to go up — and the centring needs the
     * menu's own width, which a popover does not have until it has been laid out. So it is anchored to
     * the entry (which flips it above the strip for free), then centred on the next frame, which is the
     * same two-step the hover previews use for the identical placement.</p>
     */
    public static void showJumpList(WindowFrame frame, UIElement anchor) {
        UIWindow window = frame.getAttachedWindow();
        if (window == null) return;

        discardFor(frame);
        Menu menu = MenuBuilder.build(MenuId.WINDOW_SYSTEM, CommandRegistry.global(), anchor);
        // THE SAME SURFACE THE HOVER PREVIEW WEARS. A jump list and a preview are the same object from
        // the strip's point of view -- a panel that belongs to one entry and floats above it -- so they
        // read as one thing only if they are drawn as one. A bare menu over the taskbar looked like a
        // context menu that happened to be nearby.
        menu.addClass(JUMP_LIST_CLASS);
        List<Menu> live = MenuBuilder.present(menu, anchor, window);
        LIVE.put(frame, live);
        menu.onClosed.connect(() -> discardFor(frame));

        // ANCHORED TO THE ENTRY, not to the pointer -- showFor rather than showAt.
        //
        // That is what puts the flipping and clamping in AnchoredPlacement's hands, which is where they
        // belong: the strip is at the bottom of the desktop, so there is no room below and the placement
        // flips the panel above it without being told to. Anchoring at the POINTER instead left the menu
        // wherever the press landed and, with a point anchor, resolved the flip against a zero-sized box
        // -- which put it a whole menu-width to the left of where it belonged.
        menu.showFor(anchor, null);

        // ...AND CENTRED ON THE NEXT FRAME, once it has a width to be centred by. `open()` runs before
        // the promoted node has ever been laid out, so at that moment the box is 0x0 and any centring
        // computed from it is centring on nothing.
        //
        // moveTo, never a style write: AnchoredPlacement is the SINGLE writer of an anchored popup's
        // left/top, and reposition() runs on every layout change -- so writing the position directly
        // would be overwritten on the very next tick, every tick. moveTo is legal precisely because it
        // hands ownership over rather than competing for it, which is the same route a drag on a popup
        // body takes.
        window.registerTicker(delta -> {
            if (menu.getParent() == null) return false;
            var box = menu.getRuntimeCache();
            if (box.getWidth() <= 0f || box.getHeight() <= 0f) return true;

            // THE ANCHOR IN ROOT SPACE, never its own getRuntimeCache().
            //
            // A promoted element diverges from its DOM parent in four places, and getX()/getY() is one
            // of them -- so a menu's box and an ordinary element's box are not in the same coordinates
            // and comparing them is meaningless. anchorRectInRoot is the conversion AnchoredPlacement
            // itself works in, and the one the hover previews use for this identical placement.
            AnchoredPlacement.Rect on = AnchoredPlacement.anchorRectInRoot(anchor, window);
            var root = window.ui.rootElement.getRuntimeCache();

            float centred = on.x() + (on.width() - box.getWidth()) / 2f;
            // RE-CLAMPED, or an entry near either end of a centred strip pushes its menu off screen --
            // the exact case the hover previews already pay for.
            float widest = Math.max(0f, root.getWidth() - box.getWidth());
            // COMPUTED rather than read back: the y placement resolved to is in the promoted space this
            // is trying to avoid reading, and "above the anchor" is one subtraction.
            menu.moveTo(Math.max(0f, Math.min(centred, widest)), on.y() - box.getHeight() - GAP);
            return false;
        });
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
