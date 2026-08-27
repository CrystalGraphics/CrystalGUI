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
 * <p>Both methods are defaults, so a behaviour whose whole job is a couple of {@code onCall}
 * handlers implements neither.</p>
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
     * <b>The panel, freshly bound</b> — at mount, and again after every re-describe.
     *
     * <p><b>Attach local widget listeners here and nowhere else.</b> This is the only place a panel is
     * handed over, which is deliberate: a behaviour that wired in its constructor had to remember to
     * wire again on a re-describe, and forgetting was silent — every button dead, the window otherwise
     * perfect. One entry point makes that unforgettable rather than documented.</p>
     *
     * <h3>Why a constructor is the wrong place, structurally</h3>
     *
     * <p>A behaviour has <b>two lifetimes in it</b> and they are easy to mistake for one. Things
     * registered on the <em>session</em> — {@code onCall}, {@code onNotify} — are keyed by method and
     * survive a re-describe untouched, so they belong in the constructor and run once. Things attached
     * to <em>elements</em> die with the tree that carried them, so they belong here and run every time.
     * Putting both in the constructor works right up until the server re-describes the window.</p>
     *
     * <p>The behaviour object itself is <b>kept</b> across a re-describe rather than rebuilt, so
     * anything it was remembering survives. Only the tree underneath it was replaced.</p>
     *
     * <p>The context is not passed again because it does not change: the same
     * {@link ClientWindowContext} is live for the whole of a window, and only what it points at moved.
     * Hold it from the constructor.</p>
     */
    default void onPanelBound(P panel) {
    }

    /** The window ended, however it ended. A report: it has already gone. */
    default void onClosed(String reason) {
    }
}
