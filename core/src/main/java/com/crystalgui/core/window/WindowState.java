package com.crystalgui.core.window;

/**
 * What a window <em>is</em> — the three states every windowing system in the survey turned out to have,
 * under different names ({@code plan_windowing.md}).
 *
 * <p>The distinction that matters is between the middle one and the last: <b>hide, close and destroy are
 * three different verbs</b>, and conflating any two of them is the defect this whole plan exists to
 * prevent. Win32 has {@code SW_HIDE} / {@code WM_CLOSE} / {@code DestroyWindow}; ICCCM has
 * {@code IconicState} / {@code WM_DELETE_WINDOW} / {@code WithdrawnState}; Cocoa has {@code orderOut:} /
 * {@code windowShouldClose:} / {@code close}; the web has hidden, {@code beforeunload} and
 * <em>discarded</em>. Close is a <b>request</b> in every one of them, which is why it is not a state
 * here: it is a question whose answer is {@link WindowPolicy}.</p>
 */
public enum WindowState {

    /**
     * On the desktop and working — laid out, painted, ticking, taking input.
     */
    VISIBLE,

    /**
     * Retained but <b>detached from the tree</b>, which is what makes the freeze real rather than
     * promised.
     *
     * <p>Detachment is what this engine already treats as "not participating": selectors do not match a
     * detached element ({@code invalidateStyleMatch} early-returns), there is no layout, no paint and no
     * hit-testing, and {@code onRemoved} drops every input reference — hover, press target, pointer
     * capture, a drag anchored inside. The alternative, {@code display: none} in place, keeps the
     * subtree matching selectors and keeps every ticker firing, which is precisely the
     * <i>hidden editor that keeps compiling</i> failure.</p>
     *
     * <p>The window object survives, and so does everything it holds: an editor's rope, its undo stack,
     * its analysis. That is the point, and it is also why retention is <b>bounded</b> — see
     * {@link WindowRegistry}.</p>
     */
    HIDDEN,

    /**
     * Gone. {@code Disposer} has run, the registry has dropped it, and the instance must not be shown
     * again — a destroyed window is not a hidden one that could come back, and the difference is the
     * whole reason retention is safe to rely on.
     */
    DESTROYED
}
