package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
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
 * <p>There is no conflict with the real hotbar, and it is worth saying why rather than leaving it to
 * look like an oversight: <b>the taskbar exists only on the desktop screen</b>, where the game is paused
 * and the cursor is free. In game there is no strip at all — the cursor is grabbed for look control, so
 * nothing there could be clicked. What genuinely has to respect the hotbar is W14's pinned windows,
 * which do paint over the running game.</p>
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
        entries = new UIElement();
        entries.addClass(ENTRIES_CLASS);
        addInternalChild(entries);
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
    private Desktop desktop() {
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
        List<WindowFrame> live = desktop.registry().windows();

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
        }
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
        Button entry = new Button(frame.getTitle());
        entry.addClass(ENTRY_CLASS);
        entry.attachListener(() -> {
            if (frame.state() == WindowState.HIDDEN || desktop.activeWindow() != frame) {
                // POINTER activation, so focus lands without a ring: the user pointed at this. Restoring
                // a hidden window is part of what activate() means -- see Desktop.activate.
                desktop.activate(frame, false);
            } else {
                frame.hide();
            }
        });
        return entry;
    }

    /**
     * The window's icon, drawn into the entry's pre-icon slot.
     *
     * <p>Resolved through {@link CgUiSvg#ofIcon}, never {@code of(path)} — that is what binds the
     * light/dark variant at <em>draw</em> time, and the one time a caller reached past it every
     * {@code icon()} in every stylesheet drew the light file forever.</p>
     */
    private void applyIcon(Button entry, @Nullable String iconName) {
        UIElement slot = entry.getPreIcon();
        if (iconName == null) {
            if (slot != null) slot.setDisplayed(false);
            return;
        }
        CgUiSvg glyph = CgUiSvg.ofIcon(iconName);
        if (glyph == null) return;
        if (slot == null) {
            slot = new UIElement();
            slot.addClass(ICON_CLASS);
            // Unhittable, like every other composite part: a hittable icon would swallow the press meant
            // for the entry itself.
            slot.setHitTest(false);
            entry.setPreIcon(slot);
        }
        slot.setDisplayed(true);
        UIElement iconSlot = slot;
        StyleGroup.defaultPipeline(iconSlot.getStyle().getGeneralGroup(), g -> g.overlay(glyph));
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

    /** The entry for {@code frame}, or null. Exposed for tests and for a future context menu. */
    @Nullable
    public Button entryFor(WindowFrame frame) {
        return entryOf.get(frame);
    }

    /** The island the entries sit in. */
    public UIElement entries() {
        return entries;
    }
}
