package com.crystalgui.desktop.window;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.desktop.taskbar.Taskbar;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.box.Box;
import org.joml.Vector2f;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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

    /**
     * On a jump list for the one frame before it is placed — its <b>starting</b> values, not its resting
     * ones.
     *
     * <p>The rise is a transition, so the resting value lives in the sheet and this class carries what it
     * eases <em>from</em>. The other way round does not work: a one-frame write from Java is itself
     * transitionable, so the engine eases toward it and the cleanup retargets it back — nothing animates,
     * and nothing reports that nothing did.</p>
     */
    public static final String RISING_CLASS = "__rising__";

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
        UIDocument window = frame.document();
        if (window == null) return;

        // BUILT AGAINST THE FRAME, so every enabledWhen resolves to this window rather than to whatever
        // happens to be focused. That matters most for the route that has no pointer: Alt+- with focus
        // inside window A must not offer window B's rows.
        Menu menu = open(frame, frame, window);

        // UNDER the title bar, at its left edge -- Win32's placement, and the one that does not cover the
        // caption you just asked about. Read off the BAR's own measured box rather than the frame's
        // position: it is already in the same space showAt wants, and it carries the caption height
        // without anybody having to know what a caption is.
        // IN THE DOCUMENT'S SPACE, which is what showAt wants and what `Box.x()` no longer is: a
        // caption's box is an offset inside its frame, so the frame's own place on the desktop has to
        // come back through the conversion. @see plan_m6.md 6.4
        Box bar = frame.titleBar().box();
        Box root = window.box();
        if (bar == null || root == null) return;
        Vector2f origin = Box.originIn(bar, root);
        menu.showAt(origin.x, origin.y + bar.height(), null);
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
    public static void showJumpList(WindowFrame frame, UINode anchor) {
        // THE ANCHOR'S WINDOW, NEVER THE FRAME'S -- and this route is the one place the difference is
        // fatal. HIDE IS DETACH: a minimised window is out of the tree entirely, so `frame` answers null
        // here for exactly the windows a jump list exists to reach, and the method returned having done
        // nothing. Reported as "right click only works when the window is not minimized", which is the
        // symptom stated precisely: every visible window was fine, so the route looked correct.
        //
        // The taskbar entry is the reliable end of the pair -- the strip is up whenever any of this can
        // be clicked -- which is the same reasoning ContextMenu.attach records for presenting from the
        // attachment site rather than the target. The sibling route above may keep asking the frame: it
        // is built against the frame and positioned off the title bar's box, so a detached window has no
        // system menu by that route anyway.
        UIDocument window = anchor.document();
        if (window == null) return;

        // BUILT AGAINST THE ENTRY, whose DataProvider answers for the window it stands for -- which is
        // how the same rows come out about a background window rather than the active one.
        Menu menu = open(frame, anchor, window);

        // SUPPRESSED AFTER THE OPEN, never before it. `open` discards whatever chain was already up, and
        // discarding is what LIFTS a suppression -- so suppressing first and opening second silences the
        // previews for exactly as long as it takes the next statement to undo it. Ordering, not logic:
        // both halves were correct and the pair was not.
        //
        // A PREVIEW AND A MENU ARE ALTERNATIVES. Windows shows one or the other and the menu cancels the
        // preview -- they occupy the same space above the same entry, so both at once puts the panel over
        // the menu that replaced it. Suppressed rather than dismissed once: the pointer never leaves the
        // entry, so the hover is still live and the delay would simply elapse again under the open menu.
        Taskbar taskbar = Taskbar.of(anchor);
        if (taskbar != null) {
            taskbar.setPreviewsSuppressed(true);
            SUPPRESSED.put(frame, taskbar);
        }
        // THE SAME SURFACE THE HOVER PREVIEW WEARS. A jump list and a preview are the same object from
        // the strip's point of view -- a panel that belongs to one entry and floats above it -- so they
        // read as one thing only if they are drawn as one. A bare menu over the taskbar looked like a
        // context menu that happened to be nearby.
        menu.addClass(JUMP_LIST_CLASS);
        // ...AND IT RISES, like the preview it replaces. The resting values are in the sheet and the
        // STARTING ones are the class -- never the other way round. A one-frame write from Java is
        // itself transitionable, so the engine eases toward it and the cleanup retargets it back:
        // nothing animates, and no test sees it.
        menu.addClass(RISING_CLASS);

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
        // AFTER LAYOUT, because every line of this reads a measured box. An ordinary per-frame hook
        // runs BEFORE layout, so on the opening frame the menu's box is the one it had before it was
        // promoted -- zero -- and the centring resolves against nothing, then corrects on the next
        // frame as a visible jump. Popover, Tooltip and InputDialog all place themselves here for the
        // same reason. Owned by the MENU, so closing it drops the hook.
        window.animation().afterLayout(menu, delta -> {
            if (menu.parent() == null) return false;
            Box box = menu.box();
            if (box == null || box.width() <= 0f || box.height() <= 0f) return true;

            // THE ANCHOR IN ROOT SPACE, never its own getRuntimeCache().
            //
            // A promoted element diverges from its DOM parent in four places, and getX()/getY() is one
            // of them -- so a menu's box and an ordinary element's box are not in the same coordinates
            // and comparing them is meaningless. anchorRectInRoot is the conversion AnchoredPlacement
            // itself works in, and the one the hover previews use for this identical placement.
            AnchoredPlacement.Rect on = AnchoredPlacement.anchorRectInRoot(anchor, window);
            Box root = window.box();
            if (on == null || root == null) return true;

            float centred = on.x() + (on.width() - box.width()) / 2f;
            // RE-CLAMPED, or an entry near either end of a centred strip pushes its menu off screen --
            // the exact case the hover previews already pay for.
            float widest = Math.max(0f, root.width() - box.width());
            // COMPUTED rather than read back: the y placement resolved to is in the promoted space this
            // is trying to avoid reading, and "above the anchor" is one subtraction.
            menu.moveTo(Math.max(0f, Math.min(centred, widest)), on.y() - box.height() - GAP);
            // DROPPED ONCE IT IS PLACED, which starts the rise. Doing it before the placement would
            // animate from wherever the unmeasured first frame put it, which is a rise from the wrong
            // place -- the panel would slide sideways as well as up.
            menu.removeClass(RISING_CLASS);
            return false;
        });
    }

    /**
     * Builds, presents and registers one system menu — everything both routes do identically.
     *
     * <p>{@code source} is what the rows are resolved against and {@code frame} is what the chain is
     * remembered under, and they are not always the same element: the keyboard route builds against the
     * frame itself, while a taskbar entry answers for a window it is not inside. Splitting them here is
     * what lets both routes share the rest.</p>
     *
     * <p>The previous chain is discarded first, which is a correctness requirement rather than tidiness:
     * promotion reparents a popover's Taffy node to the root, so a leftover sibling is still a DOM child
     * of its host but no longer one of its Taffy children — and the next insertion is computed against a
     * child list that has quietly emptied. {@code ContextMenu.attach} records the same hazard, with the
     * crash it produces.</p>
     */
    private static Menu open(WindowFrame frame, UINode source, UIDocument window) {
        discardFor(frame);
        Menu menu = MenuBuilder.build(MenuId.WINDOW_SYSTEM, CommandRegistry.global(), source);
        LIVE.put(frame, MenuBuilder.present(menu, source, window));
        menu.onClosed.connect(() -> discardFor(frame));
        return menu;
    }

    /** Closes and drops {@code frame}'s menu chain, if it has one. */
    public static void discardFor(WindowFrame frame) {
        // PREVIEWS COME BACK, and the strip is REMEMBERED rather than found from the menu: a promoted
        // popover's parent is the overlay host, not the element it was opened from, so walking out of the
        // menu never reaches the taskbar. That divergence is the whole reason promotion is documented.
        Taskbar suppressed = SUPPRESSED.remove(frame);
        if (suppressed != null) suppressed.setPreviewsSuppressed(false);

        List<Menu> live = LIVE.remove(frame);
        if (live != null) MenuBuilder.discard(new ArrayList<>(live));
    }

    /** Frame → the strip whose previews it silenced. @see #discardFor */
    private static final Map<WindowFrame, Taskbar> SUPPRESSED = new WeakHashMap<>();

    /**
     * Frame → its live chain.
     *
     * <p><b>Weak-keyed</b>, so a destroyed window that never closed its menu cannot keep itself, its
     * whole content subtree and its documents reachable from a static map for the life of the process.
     * The same reason {@code StyleEngine} keeps its applied-slot record weakly.</p>
     */
    private static final Map<WindowFrame, List<Menu>> LIVE = new WeakHashMap<>();
}
