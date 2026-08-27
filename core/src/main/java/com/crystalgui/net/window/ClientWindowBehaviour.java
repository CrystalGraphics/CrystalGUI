package com.crystalgui.net.window;

/**
 * The <b>local</b> half of a window: whatever this client does about a window that the server did not
 * ask for.
 *
 * <p>Registered per window type with {@code ClientWindows.register}, and constructed once per window of
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
 *
 * <h3>Typed on its panel, and the parameter is earned</h3>
 *
 * <p>The test this codebase applies to every generic is <em>does the framework hand you the thing, or
 * do you already hold it?</em> — and here it hands it to you twice: once to the factory, and again
 * whenever the tree is rebuilt. Without the parameter a behaviour has to name its own type back at the
 * framework ({@code MachinePanel.TYPE.bind(context.root())}) in every implementation, which is the
 * binding done by hand in the one place the host already knows how to do it.</p>
 *
 * <p>It costs nothing outward: unlike {@code ServerWindow}, a behaviour is never held in a public
 * heterogeneous collection, so the wildcard stays inside {@code ClientWindows} on a single field.</p>
 *
 * <p>A window with no panel class is {@code ClientWindowBehaviour<UIElement>} — for a bare window the
 * tree <em>is</em> the panel, which is what {@link WindowType#bare} already says.</p>
 *
 * @param <P> the panel type this behaviour is registered against
 */
public interface ClientWindowBehaviour<P> {

    /**
     * The server re-described the window: this is a <b>new tree</b> and a <b>freshly bound panel</b>,
     * and every listener attached to the old one went with it.
     *
     * <p>Re-wire here. The behaviour itself is kept rather than rebuilt, so anything it was remembering
     * survives — which is the reason this exists instead of the host simply discarding it and calling
     * the factory again.</p>
     *
     * <p>The panel is bound <b>by the host</b>, so an implementation never repeats what its own
     * registration already said. Its own registrations on the session survive untouched: those are
     * keyed by method, not by element.</p>
     */
    default void onContentReplaced(P panel, ClientWindowContext context) {
    }

    /** The window ended, however it ended. A report: it has already gone. */
    default void onClosed(String reason) {
    }
}
