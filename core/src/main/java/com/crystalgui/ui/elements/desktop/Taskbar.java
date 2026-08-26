package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.chrome.ContextMenu;
import com.crystalgui.ui.elements.UIText;
import com.crystalgraphics.platform.input.CgMouseCodes;
import dev.vfyjxf.taffy.style.TaffyDisplay;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The strip along the bottom of the {@link Desktop} — one entry per live window
 * ({@code plan_windowing.md}, W4).
 *
 * <p><b>It is the {@link WindowRegistry}, rendered.</b> Never a second list: a window joins on open and
 * leaves only on destroy, so the strip shows what <em>exists</em> rather than what is on screen, and a
 * minimised window's entry is how it comes back. That is Windows' rule, and it is the reason hiding is
 * safe to offer at all — <b>minimise with no discoverable way back is worse than no minimise</b>.</p>
 *
 * <h3>Where it sits, and why that is a decision</h3>
 * <p><b>Bottom, with the entries centred</b> — a strip in the same place, and roughly the same shape, as
 * Minecraft's hotbar. That is deliberate: it is where a player's eye already lives, so the compositor's
 * one permanent landmark costs no new habit. The band spans the full width for <em>layout</em> (which
 * is what keeps the work area trivial — see {@link Desktop}) while only the island in the middle paints,
 * so the corners stay free for whatever a shell later wants in them.</p>
 *
 * <p><b>It covers the real hotbar, deliberately.</b> The strip paints only while our own screen is up
 * ({@link DesktopPresentation#DESKTOP} — the HUD and overlay presentations paint pinned windows alone),
 * and with a screen up the hotbar cannot be used: the cursor is the screen's. Minecraft draws the
 * in-game HUD <em>before</em> every screen, so the hotbar is behind chat and inventories too, and a
 * full-width, near-opaque bar hides it where a translucent island the same size merely blurred it into
 * blotches behind the labels. What genuinely has to respect the hotbar is W14's pinned windows, which
 * paint over the running game. (This used to say the game was paused; it is not — see
 * {@code CgUiScreen.doesGuiPauseGame}.)</p>
 *
 * <h3>Entries are reconciled, never rebuilt</h3>
 * <p>Refresh runs on every registry change, including activation — so rebuilding the strip would destroy
 * the element that is being clicked, which is the trap that froze the table header. Entries are kept per
 * window and updated in place; only a window that has genuinely gone loses one.</p>
 */
public class Taskbar extends UIElement {

    /** The centred island the entries live in — the part that actually paints. */
    public static final String ENTRIES_CLASS = "__entries__";
    /** One per live window. */
    public static final String ENTRY_CLASS = "__entry__";
    /** On the entry whose window is active. A class, like every other state the compositor flips. */
    public static final String ACTIVE_CLASS = "__active__";
    /** On the entry of a window that is currently hidden. */
    public static final String HIDDEN_CLASS = "__hidden__";
    /** The entry's icon slot, hidden until the window declares one. */
    public static final String ICON_CLASS = "__icon__";
    /** On the entry of a window that opened without focus and has not been looked at yet. */
    public static final String ATTENTION_CLASS = "__attention__";
    /** The entry's badge slot — an unsaved count, an error count. Absent until the window declares one. */
    public static final String BADGE_CLASS = "__badge__";
    /** On an entry whose window is reporting progress; the fill below is only laid out while it is on. */
    public static final String BUSY_CLASS = "__busy__";
    /** The bar drawn behind an entry's label while its window reports progress. */
    public static final String PROGRESS_CLASS = "__progress__";
    /**
     * The pill under every entry — Windows' running indicator. Every live window has one; the active
     * window's is wider and takes the accent, which is the one place the strip says "this one" in colour.
     * An internal child of the entry, built in its constructor, so it exists before the entry is attached.
     */
    public static final String INDICATOR_CLASS = "__indicator__";
    /**
     * The hairline along the bar's top edge. An ELEMENT rather than a border, because a one-sided
     * {@code border-width-top} draws nothing here and the left-hand spelling draws all four edges — the
     * documented trap — and a single edge is how {@code statusbarview} spells its separators too.
     */
    public static final String EDGE_CLASS = "__edge__";

    private final UIElement entries;

    /** Window → its entry. Insertion-ordered so a rebuild of the child list keeps open order. */
    private final Map<WindowFrame, Button> entryOf = new LinkedHashMap<>();

    private final ConnectionGroup subscriptions = new ConnectionGroup();

    /**
     * Built by {@link Desktop}; public because a tag needs a factory.
     *
     * <p>A widget's cascade identity is its <b>tag</b>, so an unregistered {@code Taskbar} could not be
     * styled by {@code taskbar { ... }} at all — the lesson {@code Dropdown extends Button} paid for by
     * laying out at zero height until {@code default.css} named it. Which is why the desktop is found by
     * walking up rather than handed in: a factory has nothing to hand.</p>
     */
    public Taskbar() {
        // THE HAIRLINE FIRST, so it paints under the entries rather than over them. Absolute, so the row
        // below still centres its island as if the edge were not there.
        UIElement edge = new UIElement();
        edge.addClass(EDGE_CLASS);
        edge.setHitTest(false);
        addInternalChild(edge);

        entries = new UIElement();
        entries.addClass(ENTRIES_CLASS);
        addInternalChild(entries);

        // THE STRIP'S OWN CONTEXT MENU -- Windows' home for Show Desktop, and W13c's answer to a command
        // that was registered, enabled, working and findable by nobody: the palette was its only route.
        //
        // A right-click on an ENTRY never reaches here, because the entry's own handler consumes it and
        // opens the jump list instead -- which is the distinction the two menus exist for. This one is
        // about the SET of windows; that one names a window.
        ContextMenu.attach(this, CommandRegistry.global(),
                pressed -> ContextMenu.of(MenuId.TASKBAR_CONTEXT));
        // BOTH CHILDREN BEFORE ATTACH. Adding one later means inserting a Taffy node into a parent
        // whose children are mid-registration -- `Index (is 1) should be < child_count (0)` -- which is
        // exactly what building the previews lazily from createEntry did, since refresh() runs from
        // onWindowChanged. @see UIElement#taffyChildIndex
        previews = new TaskbarPreviews(this);
    }

    /** The hover previews. One panel that moves between entries. @see TaskbarPreviews */
    private final TaskbarPreviews previews;

    /**
     * Turns hover previews off while something else owns the space above an entry — a jump list.
     *
     * <p>Explicit rather than relying on the preview's own mouse-down dismissal, and that is not
     * belt-and-braces: {@code stopPropagation()} halts the remaining listeners <b>on the same element and
     * phase</b>, so the jump list's own handler — attached first — was silencing the preview's dismissal
     * outright. Depending on the order two listeners happen to be registered in is exactly the coupling
     * that produced a preview sitting on top of the menu that replaced it.</p>
     */
    void setPreviewsSuppressed(boolean suppressed) {
        previews.setSuppressed(suppressed);
    }

    /**
     * Whether previews are currently silenced.
     *
     * <p>Exposed for one assertion that has no other observable: "the strip can preview again" is the
     * absence of a panel plus the absence of a reason for it, and a test can otherwise only wait and
     * conclude nothing appeared — which is equally true of a strip that is broken for good.</p>
     */
    public boolean previewsSuppressedForTesting() {
        return previews.isSuppressed();
    }

    /** A taskbar owns its entries; they follow the registry. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /**
     * Subscribes while attached and only while attached — {@code StatusBarView}'s pattern, for the same
     * reason: the registry outlives any taskbar that has left the tree.
     */
    @Override
    protected void onWindowChanged(@Nullable UIWindow previous, @Nullable UIWindow current) {
        super.onWindowChanged(previous, current);
        subscriptions.disconnectAll();
        Desktop desktop = desktop();
        if (current == null || desktop == null) return;
        subscriptions.add(desktop.registry().onDidChange.connect(this::refresh));
        refresh();
    }

    /** The desktop this strip belongs to — always its parent, and null only while detached. */
    @Nullable
    Desktop desktop() {
        for (UIElement element = getParent(); element != null; element = element.getParent()) {
            if (element instanceof Desktop) return (Desktop) element;
        }
        return null;
    }

    /**
     * Brings the strip in line with the registry. Cheap when nothing changed, and safe to call from a
     * handler running on an entry — nothing is detached unless its window is genuinely gone.
     */
    public void refresh() {
        Desktop desktop = desktop();
        if (desktop == null) return;
        List<WindowFrame> live = desktop.registry().taskbarOrder();

        // GONE FIRST, so an entry whose window was destroyed cannot be re-used below.
        List<WindowFrame> stale = new ArrayList<>();
        for (WindowFrame frame : entryOf.keySet()) {
            if (!live.contains(frame)) stale.add(frame);
        }
        for (WindowFrame frame : stale) {
            Button entry = entryOf.remove(frame);
            if (entry != null) entries.removeChild(entry);
        }

        WindowFrame active = desktop.activeWindow();
        int index = 0;
        for (WindowFrame frame : live) {
            Button entry = entryOf.get(frame);
            if (entry == null) {
                entry = createEntry(desktop, frame);
                entryOf.put(frame, entry);
                entries.addChild(entry);
            }
            // OPEN ORDER, and stable: a bar whose entries jump on every activation is the "never in the
            // same place twice" menu bug wearing a strip. addChildAt is a no-op when it is already there.
            if (entry.getSiblingIndex() != index) entries.addChildAt(entry, index);
            index++;

            entry.setText(frame.getTitle());
            applyIcon(entry, frame.iconName());
            setClass(entry, ACTIVE_CLASS, frame == active);
            setClass(entry, HIDDEN_CLASS, frame.state() == WindowState.HIDDEN);
            setClass(entry, ATTENTION_CLASS, frame.isDemandingAttention());
            applyBadge(entry, frame.badge());
            applyProgress(entry, frame.progress());
        }
    }

    /**
     * The window's badge, in the entry's post-icon slot.
     *
     * <p>Built on first use rather than always, which is the {@code SearchField} lesson: a permanent
     * child hidden with {@code display: none} still counts for the parent's {@code gap-all}, so every
     * entry in the strip would have gained a gap for a badge almost none of them has. Not existing is
     * the only spelling of "costs nothing" that actually is.</p>
     */
    private void applyBadge(Button entry, @Nullable String badge) {
        UIElement slot = entry.getPostIcon();
        if (badge == null) {
            if (slot != null) slot.setDisplayed(false);
            return;
        }
        if (slot == null) {
            slot = new UIText("");
            slot.addClass(BADGE_CLASS);
            slot.setHitTest(false);
            entry.setPostIcon(slot);
        }
        slot.setDisplayed(true);
        if (slot instanceof UIText text) text.setText(badge);
    }

    /**
     * The window's progress, as a fill behind the entry's label.
     *
     * <p>A width in percent, which is the one thing a progress bar can be: the entry is sized by its own
     * content and writing a pixel width onto the fill would pin it to whatever the entry measured when
     * the job started — the documented "animate the content, never the container" trap, from the side
     * where the container is the thing that must stay free.</p>
     */
    private void applyProgress(Button entry, float progress) {
        boolean busy = progress >= 0f;
        setClass(entry, BUSY_CLASS, busy);
        UIElement fill = entry.getUnderlay();
        if (!busy) {
            if (fill != null) fill.setDisplayed(false);
            return;
        }
        if (fill == null) {
            fill = new UIElement();
            fill.addClass(PROGRESS_CLASS);
            // A BUTTON REFUSES PUBLIC CHILDREN, like every composite -- so this goes in the widget's own
            // slot rather than being parented onto it. setUnderlay is what makes an absolutely
            // positioned fill measure against the entry's box instead of against the strip's.
            entry.setUnderlay(fill);
        }
        fill.setDisplayed(true);
        float percent = Math.max(0f, Math.min(1f, progress)) * 100f;
        StyleGroup.importantPipeline(fill.getStyle().getLayoutGroup(), l -> l.widthPercent(percent));
    }

    /**
     * One entry.
     *
     * <p><b>Click semantics are Windows'</b>, and the third case is the one people miss: a click on the
     * entry of the window you are already in <em>minimises</em> it. Without that the strip is a
     * one-way trip — every entry restores, nothing puts anything away — and the gesture that makes a
     * taskbar feel like a taskbar is the toggle.</p>
     */
    private Button createEntry(Desktop desktop, WindowFrame frame) {
        Button entry = new Entry(frame);
        entry.addClass(ENTRY_CLASS);
        // W13a's third route, and the one that is NOT ContextMenu.attach.
        //
        // The rows are MenuId.WINDOW_SYSTEM's, exactly as the title bar's and the keyboard route's are.
        // What differs is the placement: attach anchors at the POINTER, which for a strip along the
        // bottom of the screen puts a menu at the very edge, left-aligned from wherever the press landed
        // and drifting further from its entry the wider it gets. Windows' jump list is CENTRED over its
        // button, which is the same rule the hover previews above these entries already follow --
        // an anchor that is a label for the thing beneath it wants centring, not left alignment.
        entry.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.RIGHT_BUTTON) return;
            event.stopPropagation();
            SystemMenu.showJumpList(frame, element);
        }, false, false);
        previews.watch(entry, frame);
        entry.attachListener(() -> {
            if (frame.state() == WindowState.HIDDEN || desktop.activeWindow() != frame) {
                // POINTER activation, so focus lands without a ring: the user pointed at this. Restoring
                // a hidden window is part of what activate() means -- see Desktop.activate.
                desktop.activate(frame, false);
            } else {
                // THE GESTURE, not the state change -- WindowFrame.minimize plays the flight into this
                // very entry and hides at the end of it. Calling hide() here put the window away with no
                // animation at all, while the caption's own button animated: one gesture, two call
                // sites, and only one of them looked like a minimise.
                frame.minimize();
            }
        });
        // MIDDLE-CLICK CLOSES, as it does on every taskbar and on every browser tab. Through
        // requestClose, never destroy: the window's own policy decides what closing means, so a
        // HIDE_ON_CLOSE window is put away and a dirty one still gets to refuse.
        //
        // On mouse-DOWN rather than the press pair, because a Button's activation is button-agnostic --
        // it fires for any button, so routing this through onPressed would close the window on an
        // ordinary left click as well.
        entry.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.MIDDLE_BUTTON) return;
            event.stopPropagation();
            frame.requestClose();
        }, false, false);
        return entry;
    }

    /**
     * The window's icon, as a {@link WindowIcon} tile in the entry's pre-icon slot.
     *
     * <p><b>Every entry has one</b>, icon or not — a window without an icon gets its initial on the
     * neutral tile rather than an entry that starts at its label. The slot is built on the entry's first
     * refresh and kept, which is the same pattern the badge follows, and it is safe here for the reason
     * the badge's is: {@code setPreIcon} on an attached entry is a structural change the widget makes
     * about itself, not a child added from inside a parent's own attach.</p>
     */
    private void applyIcon(Button entry, @Nullable String iconName) {
        UIElement slot = entry.getPreIcon();
        WindowIcon tile;
        if (slot instanceof WindowIcon existing) {
            tile = existing;
        } else {
            tile = new WindowIcon();
            tile.addClass(ICON_CLASS);
            entry.setPreIcon(tile);
        }
        tile.show(iconName, entry.getText());
    }

    private static void setClass(UIElement element, String cls, boolean on) {
        if (on) element.addClass(cls);
        else element.removeClass(cls);
    }

    /** Hides the whole strip — what fullscreen (W13) does, and the reason the work area is a layout box
     * rather than a subtraction somebody has to remember. */
    public void setBarVisible(boolean visible) {
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(),
                l -> l.display(visible ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    /** The window a hover preview is currently showing, or null. @see TaskbarPreviews */
    @Nullable
    WindowFrame previewedWindow() {
        return previews.showingFrame();
    }

    /**
     * The strip {@code element} is in, or null — its nearest taskbar ancestor.
     *
     * <p>The same walk {@link WindowFrame#of} does, and here for the same reason: it is a question about
     * a widget, so it belongs beside the widget rather than in whichever consumer needed it first. Both
     * take the DOM chain, which for anything that can be <b>promoted</b> is the only chain that answers
     * truthfully — promotion moves a Taffy node and never a DOM parent.</p>
     *
     * <p><b>It does not answer for a menu the strip opened</b>, which is the case worth stating because
     * it looks like it should. A promoted popover's DOM parent is the overlay host and not the element
     * it was invoked from, so walking out of a jump list reaches the root without ever meeting the
     * taskbar — {@code SystemMenu} therefore remembers the strip it suppressed rather than re-deriving
     * it on the way out.</p>
     */
    @Nullable
    public static Taskbar of(@Nullable UIElement element) {
        for (UIElement el = element; el != null; el = el.getParent()) {
            if (el instanceof Taskbar) return (Taskbar) el;
        }
        return null;
    }

    /** The entry for {@code frame}, or null. Exposed for tests and for a future context menu. */
    @Nullable
    public Button entryFor(WindowFrame frame) {
        return entryOf.get(frame);
    }

    /** The island the entries sit in. */
    public UIElement entries() {
        return entries;
    }

    /**
     * One entry, which knows which window it stands for.
     *
     * <h3>An entry is NOT inside the window it represents, and that is the whole reason this exists</h3>
     *
     * <p>{@code DataContext}'s walk goes outward from the element a command was invoked on. For a caption
     * button that reaches the frame in two steps; from a taskbar entry it reaches the taskbar, then the
     * desktop — which answers with the <b>active</b> window. So right-clicking a background entry and
     * choosing Close would close whatever window was in front instead, which is the worst failure
     * available to a menu whose whole job is to name its subject.</p>
     *
     * <p>Answering {@link WindowFrame#WINDOW_FRAME} itself puts the entry ahead of the desktop in the
     * walk — the documented rule that an element which answers must still win — so the menu is about the
     * window whose button was pressed, and the desktop's answer stays the last resort it was written to
     * be.</p>
     */
    private static final class Entry extends Button implements DataProvider {

        private final WindowFrame frame;

        Entry(WindowFrame frame) {
            super(frame.getTitle());
            this.frame = frame;
            // THE RUNNING INDICATOR, built here and never later: a Button refuses public children, and
            // an internal one added from inside the strip's refresh would be inserted into a parent whose
            // children are mid-registration. Absolute in the sheet, so the row of icon and label is laid
            // out as if it were not there; centred by `left: 50%` plus a translate, which is layout-free
            // and needs no auto-margin support from Taffy.
            UIElement indicator = new UIElement();
            indicator.addClass(INDICATOR_CLASS);
            indicator.setHitTest(false);
            addInternalChild(indicator);
        }

        @Override
        @Nullable
        public Object getData(DataKey<?> key) {
            return key == WindowFrame.WINDOW_FRAME ? frame : null;
        }

        /**
         * {@code button} — its superclass's tag, and the one case where that is right.
         *
         * <p>An unregistered subclass falls back to its own lowercased class name, so this would report
         * {@code entry} and match none of the {@code button} rules that make it look like a control —
         * the {@code ToolWindowFrame} lesson, which cost a whole unstyled widget. An entry wants a
         * button's look entirely, plus {@link #ENTRY_CLASS} for what differs.</p>
         */
        @Override
        public String tagName() {
            return "button";
        }
    }
}
