package com.crystalgui.core.window;

/**
 * What closing a window <em>means</em> — Swing's {@code setDefaultCloseOperation}, minus the values
 * nobody here needs yet.
 *
 * <p>Close is a request everywhere in the survey ({@code WM_CLOSE}, {@code WM_DELETE_WINDOW},
 * {@code windowShouldClose:}), and the answer belongs to whatever owns the window's lifetime rather
 * than to the compositor. Cocoa says the same thing from the other end: {@code releasedWhenClosed} "is
 * ignored for windows owned by window controllers".</p>
 *
 * <p>{@code DO_NOTHING_ON_CLOSE} is deliberately absent. A window that ignores its own close button is
 * a window the user cannot get rid of, and the case it exists for — "ask first" — is served by the
 * discard guard instead, which can refuse <em>and say why</em>.</p>
 */
public enum WindowPolicy {

    /**
     * Closing hides; the window is retained and can come back. What an application window wants — the
     * editor, a tool window, anything holding work.
     *
     * <p><b>Not the default</b>, and Swing is the reason. {@code JFrame} defaults to {@code HIDE_ON_CLOSE}
     * and it is a famous footgun: the window vanishes, the application looks closed, and everything it
     * held is still alive with nothing pointing at it. A window whose owner has not said it wants
     * retention should not silently accumulate — and until the taskbar (W4) exists, a hidden window has
     * no discoverable way back at all, which is worse than one that closed.</p>
     */
    HIDE_ON_CLOSE,

    /**
     * Closing destroys: {@code Disposer} runs and the registry drops it. The default, and right for
     * anything dialog-shaped — including a server-opened window, since closing a chest does not retain
     * it and {@code OpenWindow}'s content hash already makes re-opening one small packet.
     */
    DESTROY_ON_CLOSE
}
