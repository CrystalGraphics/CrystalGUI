package com.crystalgui.core.window;

/**
 * <b>What the compositor should be showing right now, and whether it is live</b> — M16 §26.2.
 *
 * <h3>Why this is a value and not three call sites</h3>
 *
 * <p>Before this existed, the paint path was chosen by <em>which hook fired</em>: the screen painted the
 * desktop from {@code drawScreen}, and a render-overlay hook painted the HUD when no screen was up. Two
 * paths, each deciding for itself whether it was its turn — and the frame the screen closed, <b>both
 * believed it was the other's</b>. {@code CgUiScreen} closes itself from inside its own {@code
 * drawScreen}, which is after that frame's overlay hook has already run and stood down, so the pinned
 * window was painted by nobody. A visible flicker, and one that no amount of care inside either path
 * could remove, because the paths were selected by a condition that changed between them.</p>
 *
 * <p>So the decision moves off the callers and becomes something the desktop can be <em>asked</em>. Every
 * hook now says the same two sentences — ask, then paint if the answer is mine — and there is no window
 * in which two callers hold different beliefs, because there is only one belief.</p>
 *
 * <h3>The rule that generates these four</h3>
 *
 * <p><b>A pinned window is interactive exactly when a cursor exists.</b> W14 wrote the display-only rule
 * as "in game the cursor is grabbed and the keyboard is the game's", which is true, and attached it to
 * the wrong thing — to <em>whose screen is up</em> rather than to the cursor. Minecraft ungrabs the
 * cursor whenever <b>any</b> {@code GuiScreen} is open, ours or the chat box, so the honest axis is
 * whether a pointer exists at all.</p>
 *
 * <p>Note what is <em>not</em> an axis: whether the screen belongs to us. Our own screen differs from
 * chat's only in what it SHOWS — the whole compositor rather than the pinned subset.</p>
 */
public enum DesktopPresentation {

    /** Our own screen is up: the whole compositor, full input, the top layer. */
    DESKTOP,

    /**
     * Another GUI is up — chat, an inventory, somebody else's screen.
     *
     * <p>Pinned windows only, and <b>they take input</b>: a cursor exists, so the display-only rule does
     * not apply. The top layer is painted, because a menu or a tooltip opened from a pinned window is
     * reachable here and has to land somewhere.</p>
     */
    OVERLAY,

    /**
     * No screen at all: pinned windows painted over the running game, and <b>no input</b>.
     *
     * <p>The cursor is grabbed, so its reported position is wherever the player last had a menu open.
     * Running the hover pipeline against it would enter and leave elements under a pointer that is not
     * there. The top layer is skipped for the same reason — nothing on it can be summoned.</p>
     */
    HUD,

    /** Nothing to draw: no screen and nothing pinned. */
    NONE;

    /** Whether this presentation dispatches input. @see #OVERLAY */
    public boolean isInteractive() {
        return this == DESKTOP || this == OVERLAY;
    }

    /** Whether the top layer paints — menus, tooltips, drag ghosts. @see #HUD */
    public boolean paintsTopLayer() {
        return isInteractive();
    }

    /** Whether the whole compositor paints, rather than the pinned windows alone. */
    public boolean paintsWholeDesktop() {
        return this == DESKTOP;
    }

    public boolean paintsAnything() {
        return this != NONE;
    }
}
