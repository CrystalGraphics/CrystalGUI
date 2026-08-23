package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.signal.Signal;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every live window on a {@link Desktop}, visible or hidden — the model the taskbar and the switcher
 * both render ({@code plan_windowing.md}).
 *
 * <p>A window <b>joins on open and leaves only on destroy</b>, which is Windows' rule and the reason
 * hiding is safe: the strip shows what exists rather than what is on screen, so a minimised window's
 * entry is how it comes back. No GL, no elements of its own — the taskbar (W4) is this list rendered,
 * never a second copy of it.</p>
 *
 * <h3>Two orders, and they are not interchangeable</h3>
 * <p><b>Open order</b> is what the taskbar shows: stable positions, because a bar whose entries jump on
 * every activation is the "never in the same place twice" menu bug wearing a strip. <b>MRU</b> is what
 * the switcher (W10) cycles, and it is not derivable from z — a hidden window has left the stacking
 * order entirely while keeping its place in the sequence, which is exactly the case Alt+Tab exists for.
 * Keeping both is why this is a class rather than a list.</p>
 *
 * <h3>Retention is bounded, and that is not optional</h3>
 * <p>A retained editor holds every open document's rope, undo stack, analysis and syntax tree. Eviction
 * is LRU over the hidden windows with a small cap — <b>with one exemption: a window whose content is
 * dirty is never evicted automatically</b>, because silently discarding unsaved work is the failure the
 * whole lifecycle exists to prevent. That question goes through the same discard guard a close asks
 * ({@link WindowFrame#setDiscardGuard}), so content answers it once and both paths agree.</p>
 *
 * <p>Eviction is real, which is why persistence stays a separate obligation: retention is best-effort,
 * and the web says the same thing with {@code wasDiscarded}.</p>
 */
public final class WindowRegistry {

    /**
     * How many hidden windows are kept before the least recently used one is discarded.
     *
     * <p>Generous rather than tuned — the point is that the number is finite from day one. An unbounded
     * retained set is a slow leak that only shows up in long sessions, which is the shape of bug that
     * gets found by a player and not by a test.</p>
     */
    public static final int DEFAULT_HIDDEN_CAP = 8;

    /**
     * The set changed: a window opened, was destroyed, was activated, or was hidden or shown.
     *
     * <p>What the taskbar refreshes on. Deliberately one signal rather than four — every consumer of
     * this model renders <em>all</em> of it, so a listener that had to reassemble which of four things
     * happened would be doing the reconciliation twice.</p>
     */
    public final Signal.Action onDidChange = new Signal.Action();

    /** Open order — the taskbar's. */
    private final List<WindowFrame> live = new ArrayList<>();
    /** Most recently activated first — the switcher's. */
    private final List<WindowFrame> mru = new ArrayList<>();

    private int hiddenCap = DEFAULT_HIDDEN_CAP;

    /**
     * <b>Every</b> live window in open order, visible or hidden — tool windows included.
     *
     * <p>The complete list, which is what lifecycle questions want: whether the desktop has anything on
     * it at all, what to evict, what to look up. What the taskbar and the switcher <em>show</em> is
     * narrower — see {@link #taskbarOrder()} and {@link #switcherOrder()}.</p>
     */
    public List<WindowFrame> windows() {
        return Collections.unmodifiableList(live);
    }

    /** Most-recently-activated first, tool windows included. @see #switcherOrder() */
    public List<WindowFrame> mruOrder() {
        return Collections.unmodifiableList(mru);
    }

    /**
     * Open order, <b>without tool windows</b> — what the taskbar draws.
     *
     * <h3>Why this is not just {@link #windows()}</h3>
     *
     * <p>A tool window is part of the window it belongs to rather than a destination of its own, so it
     * has no business being an entry you can click to. That is Win32's {@code WS_EX_TOOLWINDOW} and
     * IntelliJ's floating tool windows, which appear in neither the taskbar nor Alt+Tab; a torn-out
     * editor window, which is a place work happens, appears in both. See
     * {@link WindowFrame#isToolWindow()}.</p>
     *
     * <p><b>Filtering here rather than in {@code windows()}</b> is load-bearing and was nearly got
     * wrong: {@code Desktop} sizes its whole surface from whether any window is open, so a filtered
     * {@code windows()} would collapse the desktop to nothing whenever a tool window was the only thing
     * on it — and take the tool window with it. The complete list and the shown list are different
     * questions, and each caller asks the one it means.</p>
     */
    public List<WindowFrame> taskbarOrder() {
        return withoutToolWindows(live);
    }

    /** Most-recently-activated first, without tool windows — what the switcher offers. @see #taskbarOrder() */
    public List<WindowFrame> switcherOrder() {
        return withoutToolWindows(mru);
    }

    private static List<WindowFrame> withoutToolWindows(List<WindowFrame> from) {
        List<WindowFrame> out = new ArrayList<>(from.size());
        for (WindowFrame frame : from) {
            if (!frame.isToolWindow()) out.add(frame);
        }
        return out;
    }

    /** Just the hidden ones, in open order — what a "restore" affordance offers. */
    public List<WindowFrame> hidden() {
        List<WindowFrame> out = new ArrayList<>();
        for (WindowFrame frame : live) {
            if (frame.state() == WindowState.HIDDEN) out.add(frame);
        }
        return out;
    }

    /**
     * The window registered under {@code key}, or null.
     *
     * <p>A key is how a window is recognised across a restart — what geometry is persisted against
     * (W12) and what a "reopen the thing I had open" command asks for. Windows without one are
     * anonymous and simply never match.</p>
     */
    @Nullable
    public WindowFrame byKey(@Nullable String key) {
        if (key == null) return null;
        for (WindowFrame frame : live) {
            if (key.equals(frame.key())) return frame;
        }
        return null;
    }

    public int size() {
        return live.size();
    }

    public void setHiddenCap(int cap) {
        this.hiddenCap = Math.max(0, cap);
    }

    public int hiddenCap() {
        return hiddenCap;
    }

    // ── What the desktop tells it ───────────────────────────────────────────

    void opened(WindowFrame frame) {
        if (live.contains(frame)) return;
        live.add(frame);
        // AT THE BACK of the MRU, not the front. A window that opens without being activated -- which is
        // every server-opened window under W12's no-steal rule -- must not become the switcher's first
        // offer, or the attention flash becomes a focus steal with one keystroke of delay. Activation is
        // what moves it, and opening a window by hand activates it a moment later anyway.
        if (!mru.contains(frame)) mru.add(frame);
        changed();
    }

    void activated(WindowFrame frame) {
        if (!live.contains(frame)) return;
        mru.remove(frame);
        mru.add(0, frame);
        changed();
    }

    void destroyed(WindowFrame frame) {
        live.remove(frame);
        mru.remove(frame);
        changed();
    }

    /** Announces a change the registry did not make itself — a window hidden or shown. */
    void changed() {
        onDidChange.emit();
    }

    /**
     * Discards the least recently used hidden windows until the cap is met.
     *
     * <p>Walks the MRU list from the back, which is the only order that means anything here: open order
     * would evict whatever happened to be created first, and z-order does not exist for a hidden
     * window at all.</p>
     */
    void evictIfNeeded() {
        List<WindowFrame> hidden = hidden();
        if (hidden.size() <= hiddenCap) return;

        List<WindowFrame> leastRecentFirst = new ArrayList<>(hidden);
        // Sorted by MRU position descending -- indexOf is O(n) over a list of windows, which is the size
        // this whole class is written for.
        leastRecentFirst.sort((a, b) -> Integer.compare(mru.indexOf(b), mru.indexOf(a)));

        int over = hidden.size() - hiddenCap;
        for (WindowFrame frame : leastRecentFirst) {
            if (over <= 0) break;
            // DIRTY WORK IS NEVER DISCARDED SILENTLY. The guard is the content's answer, and it is the
            // same one a close asks -- so a window that would refuse to close also refuses to evaporate
            // while nobody is looking. It stays over the cap, which is the honest outcome: the cap is a
            // budget, not a promise.
            if (!frame.canDiscard()) continue;
            frame.destroy();
            over--;
        }
    }
}
