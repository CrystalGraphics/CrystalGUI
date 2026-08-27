package com.crystalgui.example.machine.session;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.example.machine.MachineModel;
import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.example.machine.ui.MachinePanel;
import com.crystalgui.example.machine.ui.MachinePanel;
import com.crystalgui.graph.GraphCodecs;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.UIText;

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

    /**
     * Kept for the <b>notification</b> pair, which the session does not have.
     *
     * <p>{@link ClientUiSession} exposes {@code onCall}/{@code call} and nothing else. The other
     * shape — {@code onNotify}/{@code notify} — is on the {@link ProtocolConnection}, one layer down,
     * where every subsystem sharing this wire meets. Same wire, different class, and knowing which is
     * which saves a confused half hour.</p>
     */
    private final ProtocolConnection<Object> connection;

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
        this(new ClientUiSession<Object>(connection), connection);
    }

    /**
     * Wraps a session somebody else opened — the shape the in-game host uses.
     *
     * <p>Takes the connection as well as the session, because the two carry different halves of the
     * protocol: the session answers and makes <em>calls</em>, the connection sends and receives
     * <em>notifications</em>. A version of this taking only the session could not have sent a
     * heartbeat.</p>
     */
    public MachineClient(ClientUiSession<Object> session, ProtocolConnection<Object> connection) {
        // A description addresses widgets by tag. Without the registry, decoding throws.
        ElementRegistry.bootstrapBuiltins();

        this.session = session;
        this.connection = connection;
        session.onWindowOpened(built -> {
            this.root = built;
            MachineTrace.log(MachineTrace.CLIENT, "window rebuilt from a description: "
                    + countElements(built) + " elements");
            attachLocalBehaviour(built);
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
        /*
         * ── S -> C NOTIFICATION, the receiving half ───────────────────────────────────────────
         *
         * On the CONNECTION, not the session -- see the field above. The handler takes a payload and
         * returns nothing; there is no responder because there is nobody to respond to.
         *
         * If nothing registered this method, the router would log it ONCE and drop it. Once rather
         * than per message, because the usual cause is a peer one version ahead sending something at
         * frame rate, and a log line per frame buries whatever else is wrong.
         */
        connection.onNotify("machine/announce", payload -> {
            String text = payload.getString("text", "");
            MachineTrace.log(MachineTrace.CLIENT, "<- announcement: \"" + text + "\" (after "
                    + payload.getInt("cycles", 0) + " cycles) -- nothing to answer");
            showOnPanel("NOTIFY received - the server said \"" + text + "\", nothing sent back");
        });

        session.onCall("machine/clientInfo", (args, respond) -> {
            MachineTrace.log(MachineTrace.CLIENT, "-> answering machine/clientInfo");
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putString("renderer", "example-client");
            out.putBool("cachedDescription", session.cacheSize() > 0);
            respond.ok(out);
            showOnPanel("REQUEST answered - the server asked who we are, and we told it");
        });
    }

    /**
     * ── C → S REQUEST ── Asks the server for its numbers, and hands the answer to a caller.
     *
     * <p><b>Two callbacks rather than one nullable result.</b> "The machine has run 12 cycles" and
     * "the server never answered" are different facts, and a UI that renders them the same way is
     * lying about one of them. The router expires the request on its own deadline — 10 seconds by
     * default — so the error path is genuinely reached rather than being a promise nobody keeps.</p>
     *
     * <p>Both callbacks run on the thread that ticked the connection, some frames later. Nothing here
     * blocks, and there is no version of this that could: a round trip is a round trip, and an API
     * that hid that would be lying about where the latency is.</p>
     */
    public void requestStats(Consumer<StateMap<Object>> onResult, Consumer<String> onError) {
        MachineTrace.log(MachineTrace.CLIENT, "-> asking the server machine/stats");
        session.call("machine/stats", null, onResult::accept, onError::accept);
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

    // ── Behaviour this side adds for itself ─────────────────────────────────

    /**
     * Wires the three buttons the <b>client</b> drives.
     *
     * <p>The server wired {@code Power}, {@code Purge} and {@code Ping client} through
     * {@code session.on(...)}, which stamps an event name on the element so this side reports a press
     * across the wire. These three are the other thing a client may do: {@code attachListener} on a
     * widget it was handed, with the listener staying <b>entirely local</b>. Nothing about it crosses
     * the wire, the server never learns the listener exists, and the description is unchanged.</p>
     *
     * <p>The button cannot tell which of the two happened to it, and neither can the stylesheet. That
     * is worth internalising: a described tree is an ordinary tree once it has been rebuilt.</p>
     */
    private void attachLocalBehaviour(UIElement built) {
        UIElement ask = built.querySelector("#ask-stats");
        if (ask instanceof Button) ((Button) ask).attachListener(this::requestStats);

        UIElement beat = built.querySelector("#heartbeat");
        if (beat instanceof Button) ((Button) beat).attachListener(this::sendHeartbeat);

        UIElement rename = built.querySelector("#bad-rename");
        // Deliberately blank -- the server refuses it, which is the only way to watch the error
        // callback fire. See MachineServer's machine/rename handler.
        if (rename instanceof Button) ((Button) rename).attachListener(() -> rename("   "));
    }

    /**
     * What the {@code Ask stats} button is bound to — {@link #requestStats(Consumer, Consumer)} with
     * the answer written onto the panel.
     *
     * <p>Delegates rather than making the call itself, so there is <b>one</b> statement of
     * {@code machine/stats} in this class. Two copies of a call is how the two drift on the day
     * somebody adds a field to the request.</p>
     */
    public void requestStats() {
        showOnPanel("REQUEST sent to the server - waiting for an answer...");
        requestStats(
                stats -> {
                    String text = stats.getInt("cycles", -1) + " cycles, "
                            + stats.getInt("heartbeats", 0) + " heartbeats, label '"
                            + stats.getString("label", "?") + "'";
                    MachineTrace.log(MachineTrace.CLIENT, "<- server answered: " + text);
                    showOnPanel("REQUEST answered - " + text);
                },
                error -> {
                    MachineTrace.log(MachineTrace.CLIENT, "<- server refused: " + error);
                    showOnPanel("REQUEST failed - " + error);
                });
    }

    /**
     * ── C → S NOTIFICATION ── Tells the server we are here. Nothing comes back.
     *
     * <p>Sent on the {@link ProtocolConnection}, because the session has no {@code notify}. There is
     * no callback to pass, and that is not an omission — <b>a notification that could fail visibly
     * would be a request.</b> If the server has no handler for this method it logs once and drops
     * it, and this side is never told, which is the whole bargain.</p>
     */
    public void sendHeartbeat() {
        StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
        out.putString("from", "example-client");
        MachineTrace.log(MachineTrace.CLIENT, "-> notifying machine/heartbeat (no answer expected)");
        connection.notify("machine/heartbeat", out);
        // No "waiting" line: nothing is coming. The server's own readout will replace this one a
        // tick later, which is itself worth watching -- that line is the AUTHORITATIVE one.
        showOnPanel("NOTIFY sent to the server - nothing will come back");
    }

    /**
     * ── C → S REQUEST THAT FAILS ── Asks the server to rename the machine.
     *
     * <p>Call it with a blank name and the server answers {@code EMPTY_NAME} through
     * {@code respond.fail}. That is an ordinary answer that happens to say no — same envelope as a
     * success, same thread, same latency — not an exception and not a timeout. The distinction
     * matters because a UI must tell "refused" apart from "never came back", and only one of those
     * is worth retrying.</p>
     */
    public void rename(String name) {
        StateMap<Object> args = new StateMap<>(PlainOps.INSTANCE);
        args.putString("name", name);
        MachineTrace.log(MachineTrace.CLIENT, "-> asking the server machine/rename('" + name + "')");
        showOnPanel("REQUEST sent to the server - waiting for an answer...");
        session.call("machine/rename", args,
                ok -> {
                    MachineTrace.log(MachineTrace.CLIENT, "<- rename accepted");
                    showOnPanel("REQUEST answered - renamed to \"" + name + "\"");
                },
                error -> {
                    MachineTrace.log(MachineTrace.CLIENT, "<- rename REFUSED: " + error);
                    showOnPanel("REQUEST REFUSED - the server answered \"" + error
                            + "\". A normal answer that says no: not an error, not a timeout.");
                });
    }

    /**
     * Writes a line into the panel's readout — <b>locally, and only until the server writes it next</b>.
     *
     * <p>Worth knowing rather than working around. That {@code UIText} belongs to the server: it is in
     * the description, the server pushes it through {@code ui/stateDelta}, and the next delta touching
     * it overwrites whatever this wrote. So a client-side write to a server-owned widget is a
     * <em>preview</em>, not a fact.</p>
     *
     * <p>The one exception the engine makes is deliberate and narrow: {@code ClientUiSession} suppresses
     * an incoming state update for an element that is <b>focused and consumes text</b>, so the server
     * echoing your own keystrokes back cannot reset the caret mid-word.</p>
     */
    private void showOnPanel(String text) {
        if (root == null) return;
        /*
         * BY ID, because this side has no MachinePanel. The server holds the object and reaches the
         * field; this side holds a tree it rebuilt from a description and addresses the same element
         * the only way it can -- by the id the description carried. That asymmetry is the
         * architecture in miniature: both halves looking at the same widget, sharing no object.
         *
         * AND IT IS THE CLIENT'S LINE, never the server's. Writing into an element the server also
         * writes is what produced a readout with this side's badge over the other side's sentence --
         * see the comment where the two lines are built. The server never touches this one, so it is
         * never in a state delta, so a local write here survives.
         */
        UIElement line = root.querySelector("#result-client");
        if (line instanceof UIText) ((UIText) line).setText(text);
    }

    private static int countElements(UIElement element) {
        int total = 1;
        for (UIElement child : element.getChildren()) total += countElements(child);
        return total;
    }
}
