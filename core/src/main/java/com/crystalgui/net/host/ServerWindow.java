package com.crystalgui.net.host;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.net.ServerUiSession;
import com.crystalgui.ui.UIElement;

/**
 * One networked window: <b>the server-side unit a mod authors</b>.
 *
 * <p>What Minecraft's {@code Container} and LDLib2's {@code IContainerUIHolder} are, against a
 * described tree instead of slots. Everything about the window's <em>lifecycle</em> belongs to
 * {@link ServerUiHost} — allocating the id, constructing the session, opening it, ticking it, and
 * every one of the ways it can end. What belongs here is the tree, the behaviour, and the three
 * questions only this window can answer.</p>
 *
 * <h3>The shape it replaces</h3>
 *
 * <p>Before this, a mod opened a window by walking the player list from its own tick handler, checking
 * a map of its own, constructing a {@code ServerUiSession} against a hard-coded window id, and
 * remembering to tick it and to close it on a logout event it also had to subscribe to. Twenty mods
 * meant twenty poll loops asking a question the connection layer had already answered once. None of
 * that is here because none of it is a window's business.</p>
 *
 * <pre>{@code
 * public final class MachineWindow extends ServerWindow {
 *     private final MachineModel model;              // world state; this window is a VIEW of it
 *     private final MachinePanel panel = new MachinePanel();
 *
 *     public MachineWindow(MachineModel model) { this.model = model; }
 *
 *     @Override public String type()  { return "mymod:machine"; }
 *     @Override public String title() { return model.label(); }
 *     @Override public String key()   { return "mymod:machine"; }   // one per viewer
 *     @Override public UIElement root() { return panel.root; }
 *
 *     @Override protected void bind(SessionScope io) {
 *         io.onActivate(panel.purge, ctx -> model.purge());
 *         io.onCall("rename", (args, respond) -> { … });
 *     }
 *
 *     @Override protected void tick() { panel.status.setText(model.summary()); }
 *     @Override protected boolean stillValid(Object viewer) { return model.exists(); }
 * }
 * }</pre>
 *
 * <p>A window that is one screenful of handlers does not need a class at all — {@link UiWindows} builds
 * one from lambdas. This is for anything with a model reference, fragments, or state that outlives a
 * tick.</p>
 *
 * <h3>The model does not live here</h3>
 *
 * <p>A machine is world state and ticks with the world; this is a view of it whose lifetime is bounded
 * by a viewer. Fusing the two — advancing the model from {@link #tick()} — makes the machine stop
 * existing when somebody closes a window, which is the opposite of what a server-authoritative UI is
 * for. Hold a reference to the model and mirror it; do not <em>be</em> it.</p>
 *
 * <h3>Threading</h3>
 *
 * <p>Every method here runs on the thread that ticked the connection — the server thread in game,
 * whatever drives the loopback in a test. The host adds no thread and no callback from the network
 * thread.</p>
 */
public abstract class ServerWindow {

    /** Set by the host at open, cleared when the window ends. */
    @Nullable
    ServerUiHost host;

    @Nullable
    ServerUiSession<Object> session;

    int windowId = -1;

    boolean live;

    /** Attached in order, ticked after this window. @see SessionScope#attach */
    final List<ServerFragment> fragments = new ArrayList<>();

    // ── What a window says about itself ─────────────────────────────────────

    /**
     * What kind of window this is — {@code "mymod:machine"}, namespaced like everything else on the
     * wire.
     *
     * <p>Travels on {@code ui/openWindow} and is what a client dispatches its local behaviour on
     * ({@code ClientUiHost.register}). A client that has no factory for the type still shows the
     * window, correctly and interactively: a description is self-sufficient, so an unknown type simply
     * has no local extras. That is the one respect in which this beats Minecraft's own model, where an
     * unknown {@code MenuType} is a broken screen.</p>
     */
    public abstract String type();

    /** The tree. Built once, by whatever this window's constructor did; the host asks for it at open. */
    public abstract UIElement root();

    /** What to call it on screen. The side that opens a window is the side that knows what it is. */
    public String title() {
        return type();
    }

    /**
     * Uniqueness and persistence key for this viewer, or {@code null} for "always a new window".
     *
     * <p>Two things at once. {@link ServerUiHost#open} refuses to open a second window under a key that
     * is already open and brings the existing one forward instead — Minecraft's close-the-previous rule
     * narrowed to the same <em>subject</em> rather than applied to every window. And the client's frame
     * takes it, so the compositor restores its geometry where the user left it.</p>
     */
    @Nullable
    public String key() {
        return null;
    }

    // ── What a window does ──────────────────────────────────────────────────

    /**
     * Registers everything this window can do. Called once, by the host, <b>before</b> the session
     * opens.
     *
     * <p>That ordering is not advice a caller has to follow: the host does it, so the "handlers must be
     * registered before open" rule cannot be broken from here.</p>
     */
    protected abstract void bind(SessionScope io);

    /**
     * One world tick while this window is open.
     *
     * <p>Mirror the model into widgets here and stop — every setter is idempotent and marks its element
     * dirty, and the host flushes the whole batch afterwards as one message. Nothing here has to know
     * which fields moved.</p>
     */
    protected void tick() {
    }

    /**
     * Minecraft's {@code canInteractWith}, LDLib2's {@code isStillValid}: may this window go on
     * existing?
     *
     * <p>Checked by the host every tick, before {@link #tick()}. Answering false closes the window with
     * {@link CloseReason#NOT_VALID} — a block broken, a player walked away, an inventory gone. Default
     * true: a window that lives until something closes it.</p>
     *
     * @param viewer the connection's peer — the platform's player handle, or {@code null} in loopback
     */
    protected boolean stillValid(@Nullable Object viewer) {
        return true;
    }

    /**
     * Every way this window can end funnels here, exactly once.
     *
     * <p>A report rather than a veto: by the time this runs the window has stopped serving. A window
     * that wants to <em>refuse</em> a close has to do it on the client, where the discard guard lives —
     * the same split every windowing system makes between "the user asked" and "it happened".</p>
     */
    protected void onClosed(CloseReason reason) {
    }

    /** Why a window ended. @see ServerUiHost */
    public enum CloseReason {
        /** The server asked — {@code host.close(window, …)}. */
        SERVER,
        /** The user closed the frame. {@code ui/close}, the direction that used to be missing. */
        CLIENT,
        /** {@link #stillValid} answered false. */
        NOT_VALID,
        /** The peer went away: a logout, a kick, a server stop. Nothing was sent; nobody was there. */
        CONNECTION_LOST
    }

    // ── What the host gives back ────────────────────────────────────────────

    /** This window's session, or {@code null} before it is opened and after it ends. */
    @Nullable
    public final ServerUiSession<Object> session() {
        return session;
    }

    /** Allocated by the host, unique on its connection. Never a constant a mod picks. */
    public final int windowId() {
        return windowId;
    }

    /** The platform's handle for whoever is watching, or {@code null} in loopback. */
    @Nullable
    public final Object viewer() {
        return host == null ? null : host.peer();
    }

    /** Whether this window is currently being served. */
    public final boolean isOpen() {
        return live;
    }

    /** Asks the host to end this window. Safe on one that has already ended. */
    public final void close(String reason) {
        if (host != null) host.close(this, reason);
    }
}
