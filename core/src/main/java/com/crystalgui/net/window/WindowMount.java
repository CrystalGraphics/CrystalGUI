package com.crystalgui.net.window;

import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;

/**
 * <b>Where a server's window lands on this client</b> — the one thing a platform implements.
 *
 * <p>On 1.7.10 it wraps the tree in a {@code WindowFrame} and opens it on the desktop {@code CgUiScreen}
 * already owns. In the GL harness it is a scene. In a headless test it is three fields. That is the whole
 * platform surface for networked UI: everything above it — sessions, ids, the close matrix, dispatch by
 * type — is the engine's and is written once.</p>
 *
 * <h3>Installed once, per connection-independent host</h3>
 *
 * <p>{@code ClientWindows.setMount} takes it, and windows that arrive before a mount exists are queued and
 * drained when one does. That is not a nicety: a server can open a window before the player has ever
 * opened the screen the desktop lives on, and the two orderings are genuinely independent. The alternative
 * — a poll from a tick handler asking "is there a screen yet, is there a window yet" — is what this
 * replaces.</p>
 *
 * <h3>What a mount owes back</h3>
 *
 * <p>Exactly one thing: call {@link ClientWindowContext#userClosed} when the <em>user</em> closes it. Not
 * when the server does, not when the connection drops — the host already knows about those, and reporting
 * them would echo. The mc1710 mount gets it from {@code WindowFrame.onDestroyed}.</p>
 */
public interface WindowMount {

    /**
     * Puts a rebuilt tree on screen and returns the handle the host acts through.
     *
     * <p>Called on the thread that ticked the connection, which is the thread that owns the tree.</p>
     */
    MountedWindow mount(ClientWindowContext context);

    /** One window, on screen. */
    interface MountedWindow {

        /**
         * The window ended and it was not the user — take it off screen.
         *
         * <p><b>Do not report this back.</b> It came from the server, or from the connection dying;
         * telling the far side would be answering its own news, and on a dead connection it would be
         * writing into a socket that has gone.</p>
         */
        void closedByServer(String reason);

        /** Bring it forward. What re-opening an already-open window means. @see ServerWindows#open */
        void focus();

        /**
         * The server re-described this window and the tree was rebuilt — swap the new one in.
         *
         * <p>Not speculative: {@code ClientUiSessions} deliberately re-delivers an {@code ui/openWindow}
         * to an existing session, because that is how a reshape reaches a client that missed the delta,
         * and the session then decodes a <em>fresh</em> tree. A mount holding the old root would be
         * showing a tree nothing is updating any more.</p>
         */
        void contentReplaced(UIElement newRoot);

        /**
         * A {@link com.crystalgui.net.ViewCommand} about the WINDOW rather than the tree — its title,
         * its icon, a size hint, a message to show outside it.
         *
         * <p>Default: <b>do nothing</b>, and that is a real answer rather than a stub. A host with no
         * windows — one panel filling a screen — has no caption to set and no taskbar to rename, and
         * saying so by ignoring it is better than making every host implement four methods it has no
         * surface for. The keys are on {@code ViewCommand}.</p>
         */
        default void viewCommand(String command, StateMap<Object> args) {
        }
    }
}
