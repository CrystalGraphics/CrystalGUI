package com.crystalgui.net.host;

/**
 * The <b>local</b> half of a window: whatever this client does about a window that the server did not
 * ask for.
 *
 * <p>Registered per window type with {@code ClientUiHost.register}, and constructed once per window of
 * that type — Minecraft's {@code MenuScreens} registry, doing rather less because it has rather less to
 * do. A factory does its wiring in its constructor, from the {@link ClientWindowContext} it is handed:
 * listeners on widgets it was given, {@code onCall} methods the server may invoke, a
 * {@code ClientUiSession.call} it wants to make.</p>
 *
 * <p><b>Optional, and its absence is not a degraded state.</b> A window whose type nothing registered
 * still mounts, renders, and reports every event its description asked for — because a description is
 * self-sufficient. It simply has no local extras. Minecraft cannot do that: an unregistered
 * {@code MenuType} there is a broken screen.</p>
 *
 * <p>Both methods are defaults, so a behaviour that only wires listeners in its constructor implements
 * nothing at all.</p>
 */
public interface ClientWindowBehaviour {

    /**
     * The server re-described the window: {@code context.root()} is a <b>new tree</b>, and every
     * listener attached to the old one went with it.
     *
     * <p>Re-wire here. The behaviour itself is kept rather than rebuilt, so anything it was remembering
     * survives — which is the reason this exists instead of the host simply discarding it and calling
     * the factory again.</p>
     */
    default void onContentReplaced(ClientWindowContext context) {
    }

    /** The window ended, however it ended. A report: it has already gone. */
    default void onClosed(String reason) {
    }
}
