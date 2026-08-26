package com.crystalgui.example.machine.session;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.example.machine.MachineModel;
import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.example.machine.ui.MachinePanel;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;

/**
 * <b>Step 5 — the client half.</b>
 *
 * <p>Strikingly little. It registers what it can answer, says what to do when a window arrives, and
 * hands over a tree it did not build.</p>
 *
 * <h3>What is not here</h3>
 *
 * <p>No widgets. No layout. No knowledge that a machine has a throughput. The client has never heard
 * of {@link MachineModel} and could not import it in a real deployment, because on a Minecraft
 * server that class lives in code the client half never loads. Everything on screen came out of a
 * description, and every listener behind it was attached by
 * {@code ClientUiSession.wireReportedEvents} from the event names the description carried.</p>
 *
 * <p>Which is the property worth taking away: <b>adding a control to this panel is a change to
 * {@link MachinePanel} and {@link MachineServer} only.</b> Nothing on this side is recompiled, and
 * an old client draws a new panel correctly.</p>
 *
 * <h3>Why {@code bootstrapBuiltins()}</h3>
 *
 * <p>A description names a widget by tag — {@code "switch"}, {@code "slider"} — and
 * {@link ElementRegistry} is what turns that back into a class. An unregistered tag <b>throws</b> on
 * decode rather than degrading to a plain element, because a styleless div where a slider should be
 * is a UI that is subtly wrong on one side only, which is far harder to trace than a refusal. The
 * call is idempotent and every lookup triggers it anyway; it is here to be visible.</p>
 *
 * <h3>Why this class does not own a {@code UIWindow}</h3>
 *
 * <p>Because the host does. On 1.7.10 that is {@code CgUiScreen}, which is a {@code GuiScreen} and
 * owns the viewport, the input pump and the frame; in the GL harness it is the scene. A session that
 * built its own window would be a second claim on the screen, and the host already has one.</p>
 *
 * <p>So the seam is {@link #onReady}: the session hands over a root, and the host decides what to
 * mount it in. {@link #sheets()} and {@link #useUserAgentSheet()} are what it needs to style it —
 * which is deliberately the host's job too, since parsing a stylesheet is one of the things a
 * server-safe class may not do.</p>
 */
public final class MachineClient {

    private final ClientUiSession<Object> session;

    private Consumer<UIElement> onReady = root -> { };
    private Consumer<String> onClosed = reason -> { };

    /** The last thing the server told us, so the host has something to draw. */
    @Nullable
    private UIElement root;

    /**
     * The single-window shape: this session owns {@code ui/openWindow} on the connection.
     *
     * <p>Right for a demo and for a mod with one panel. For more than one window use
     * {@link #MachineClient(ClientUiSession)} with a session from
     * {@link com.crystalgui.net.ClientUiSessions}, which owns that notification for the whole
     * connection and hands out a session per window — because the open message is the one thing in
     * the vocabulary that <em>cannot</em> be routed by window id, being what announces the id.</p>
     *
     * <p>The two are mutually exclusive on one connection: both bind the same notification, so the
     * second one registered silently replaces the first.</p>
     */
    public MachineClient(ProtocolConnection<Object> connection) {
        this(new ClientUiSession<Object>(connection));
    }

    /** Wraps a session somebody else opened — the shape the in-game host uses. */
    public MachineClient(ClientUiSession<Object> session) {
        // A description addresses widgets by tag. Without the registry, decoding throws.
        ElementRegistry.bootstrapBuiltins();

        this.session = session;
        session.onWindowOpened(built -> {
            this.root = built;
            MachineTrace.log(MachineTrace.CLIENT, "window rebuilt from a description: "
                    + countElements(built) + " elements");
            onReady.accept(built);
        });
        session.onWindowClosed(reason -> {
            this.root = null;
            MachineTrace.log(MachineTrace.CLIENT, "window closed by the server: " + reason);
            onClosed.accept(reason);
        });

        /*
         * Symmetric with the server's onCall: either peer may call the other, and both use the same
         * request/response envelope. This one is answered from nothing but the client's own state,
         * which is the honest use of the direction -- the server asking the client for a fact only
         * the client has.
         *
         * Answer exactly once. The responder enforces it, and a handler that answers twice is a
         * programming error rather than a protocol one.
         */
        session.onCall("machine/clientInfo", (args, respond) -> {
            MachineTrace.log(MachineTrace.CLIENT, "answering machine/clientInfo");
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putString("renderer", "example-client");
            out.putBool("cachedDescription", session.cacheSize() > 0);
            respond.ok(out);
        });
    }

    /** Called when a window arrives and its tree has been rebuilt. The host mounts it. */
    public MachineClient onReady(Consumer<UIElement> handler) {
        this.onReady = handler == null ? root -> { } : handler;
        return this;
    }

    /** Called when the server puts the window away, with the reason it gave. */
    public MachineClient onClosed(Consumer<String> handler) {
        this.onClosed = handler == null ? reason -> { } : handler;
        return this;
    }

    /** The rebuilt tree, or null before the first window arrives and after it closes. */
    @Nullable
    public UIElement root() {
        return root;
    }

    public ClientUiSession<Object> session() {
        return session;
    }

    /**
     * The themes the server named, in the order it named them.
     *
     * <p>Order is load-bearing and is not sorted: the style engine's sheet list is a flat ordered
     * one, and re-adding a sheet appends it — that is, at the <em>highest</em> priority. A host that
     * applies these in a different order gets a different-looking panel with every rule correct.</p>
     */
    public java.util.List<com.crystalgui.net.SheetRef> sheets() {
        return session.sheets();
    }

    /** Whether the engine's own sheet goes underneath. See {@code MachineStyles}. */
    public boolean useUserAgentSheet() {
        return session.useUserAgentSheet();
    }

    /**
     * Asks the server a question.
     *
     * <p>Two callbacks rather than one nullable result, because "the machine has run 12 cycles" and
     * "the server never answered" are different facts and a UI that renders them the same way is
     * lying about one of them. The router expires the request on its own deadline, so the error path
     * is reached rather than being a promise nobody keeps.</p>
     */
    public void requestStats(Consumer<StateMap<Object>> onResult, Consumer<String> onError) {
        session.call("machine/stats", null, onResult::accept, onError::accept);
    }

    /**
     * One client tick.
     *
     * <p>Genuinely a no-op while the session rides a connection: the connection has already drained
     * the mailbox for every subsystem on it. Kept so this class stays correct if it is ever handed a
     * bare transport instead.</p>
     *
     * <p>And note it is <b>not</b> symmetric with {@code MachineServer.tick()}, which must be called.
     * The server session is the observer holding a tick's worth of dirty elements, so its tick still
     * flushes even when it owns no connection; the client session has nothing of its own to do.</p>
     */
    public void tick() {
        session.tick();
    }

    private static int countElements(UIElement element) {
        int total = 1;
        for (UIElement child : element.getChildren()) total += countElements(child);
        return total;
    }
}
