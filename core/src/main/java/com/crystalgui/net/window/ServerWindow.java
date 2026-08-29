package com.crystalgui.net.window;

import java.util.ArrayList;
import com.crystalgui.core.signal.Connection;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.crystalgui.net.ServerUiSession;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.projection.Projections;

/**
 * <b>One open window on a connection, server side</b> — the handle {@link ServerWindows#open} returns.
 *
 * <p>Not an authoring surface any more: a UI is a {@link Networked} element, and everything a window
 * subclass used to answer — the tree, the handlers, the tick, the validity check, the title, the key —
 * is answered by the panel. What is left here is genuinely the <em>window's</em>: the session it is
 * served through, the id the host allocated, the close matrix, and the panel itself.</p>
 *
 * <p>Most callers do not even keep it:</p>
 *
 * <pre>{@code
 * ServerWindows.of(connection).open(MachinePanel.TYPE, machine);
 * }</pre>
 *
 * <p>A caller that does gets the panel back typed — {@code window.panel()} — because {@code open} was
 * handed a typed {@link UiType} and the parameter costs nothing outward.</p>
 *
 * <h3>The model does not live here either</h3>
 *
 * <p>A machine is world state and ticks with the world; a window is a view of it whose lifetime is
 * bounded by a viewer. The model was captured into this window's hooks at {@code open} and is handed
 * to the panel's server methods each time they run — it is never a field anything can reach for from
 * the wrong side.</p>
 *
 * <h3>Threading</h3>
 *
 * <p>Everything runs on the thread that ticks the connection — the server thread in game, whatever
 * drives the loopback in a test.</p>
 *
 * @param <P> the panel this window serves
 */
public final class ServerWindow<P extends UIElement> {

    /** Which {@link UiType} opened this. Identity — what the key-dedup check compares. */
    final UiType<?, ?> uiType;

    private final P panel;

    /** {@code io -> panel.serve(model, io)}, captured where the model's type was known. */
    final Consumer<ServerScope> binder;

    /** {@code () -> panel.tick(model)}. */
    final Runnable ticker;

    /** {@code viewer -> panel.stillValid(model, viewer)}. */
    final Predicate<Object> validity;

    /** {@code reason -> panel.closed(reason.name())}. */
    final Consumer<CloseReason> closer;

    private final String title;

    @Nullable
    private final String key;

    /**
     * Every projection declared by this window's panel <b>and its attached children</b>, in one set.
     *
     * <p>One set per WINDOW rather than per panel, because they all run at the same moment for the same
     * reason: a projection has to write before the session flushes, or its change waits a tick. Nesting
     * does not need its own set -- a child's projections are simply declared into this one, which is
     * also what makes a child's fields visible to the same {@code gatedBy} epoch when the parent sets
     * one.</p>
     */
    private final Map<Object, Projections> projectionsByScope = new LinkedHashMap<>();

    /** Undone when the window ends. @see ServerScope#bind */
    final List<Connection> bindings = new ArrayList<>();

    /**
     * This scope's own set, created on first use.
     *
     * <p>One set PER PANEL rather than one per window, so a nested panel's {@code projectWhen} gate
     * covers its own fields and not its parent's — a child has its own model and its own notion of
     * having changed, and one shared gate would let either silence the other.</p>
     */
    Projections projectionsFor(Object scope) {
        return projectionsByScope.computeIfAbsent(scope, ignored -> Projections.create());
    }

    /** Every set, parent first then children in attach order. */
    Collection<Projections> allProjections() {
        return projectionsByScope.values();
    }

    /** Runs them all. @return how many projections wrote something */
    int runProjections() {
        int changed = 0;
        for (Projections set : projectionsByScope.values()) changed += set.run();
        return changed;
    }

    /** Drops every projection and undoes every binding. Called when the window ends. */
    void releaseProjections() {
        for (Projections set : projectionsByScope.values()) set.close();
        projectionsByScope.clear();
        for (Connection binding : bindings) binding.disconnect();
        bindings.clear();
    }

    /** Nested panels attached through {@link ServerScope#attach}, in attach order. */
    final List<Attached> attached = new ArrayList<>();

    /** Set by the host at open, cleared when the window ends. */
    @Nullable
    ServerWindows host;

    @Nullable
    ServerUiSession<Object> session;

    int windowId = -1;

    boolean live;

    ServerWindow(UiType<?, ?> uiType, P panel, Consumer<ServerScope> binder, Runnable ticker,
                 Predicate<Object> validity, Consumer<CloseReason> closer,
                 String title, @Nullable String key) {
        this.uiType = uiType;
        this.panel = panel;
        this.binder = binder;
        this.ticker = ticker;
        this.validity = validity;
        this.closer = closer;
        this.title = title;
        this.key = key;
    }

    // ── What a window says about itself ─────────────────────────────────────

    /** The panel this window serves — also its tree: the panel <b>is</b> the root element. */
    public P panel() {
        return panel;
    }

    /** What kind of window this is on the wire — {@code "mymod:machine"}. */
    public String typeId() {
        return uiType.id();
    }

    /** What the client was told to call it. Read from the panel once, at open. */
    public String title() {
        return title;
    }

    /** The uniqueness and persistence key this window opened under, or {@code null}. */
    @Nullable
    public String key() {
        return key;
    }


    // ── What the host gives back ────────────────────────────────────────────

    /** This window's session, or {@code null} before it is opened and after it ends. */
    @Nullable
    public ServerUiSession<Object> session() {
        return session;
    }

    /** Allocated by the host, unique on its connection. Never a constant a mod picks. */
    public int windowId() {
        return windowId;
    }

    /** The platform's handle for whoever is watching, or {@code null} in loopback. */
    @Nullable
    public Object viewer() {
        return host == null ? null : host.peer();
    }

    /** Whether this window is currently being served. */
    public boolean isOpen() {
        return live;
    }

    /** Asks the host to end this window. Safe on one that has already ended. */
    public void close(String reason) {
        if (host != null) host.close(this, reason);
    }

    /** A nested panel's server hooks, captured with its slice at {@link ServerScope#attach}. */
    record Attached(Runnable ticker, Consumer<CloseReason> closer) {
    }
}
