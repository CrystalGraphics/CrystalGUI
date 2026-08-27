package com.crystalgui.example.machine.session;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.example.machine.MachineModel;
import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.example.machine.ui.MachinePanel;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.ClientWindowBehaviour;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.UIText;

/**
 * <b>Step 5 — the client half.</b> Strikingly little, and less than it used to be.
 *
 * <p>Registered once, at mod init:</p>
 *
 * <pre>{@code
 * ClientWindows.register(MachinePanel.TYPE, MachineClient::new);
 * }</pre>
 *
 * <p><b>Type-checked, not string-matched.</b> {@code MachinePanel.TYPE} is a {@code
 * WindowType<MachinePanel>}, so this constructor <em>must</em> take a {@code MachinePanel} — a
 * mismatched pair is a compile error. It used to be two copies of a string that nothing compared,
 * where a typo produced a window that opened, rendered and reported every event with no behaviour at
 * all.</p>
 *
 * <p>That is the whole of the client's wiring. What used to sit around it — tracking whether the
 * connection had been replaced, tearing down on disconnect, installing a session listener at exactly
 * the right moment, and polling every tick for "is there a screen yet and is there a window yet" — is
 * {@link ClientWindows}'s, once, for every mod.</p>
 *
 * <h3>What is not here</h3>
 *
 * <p>No widgets. No layout. No knowledge that a machine has a throughput. This class has never heard of
 * {@link MachineModel} and could not import it in a real deployment, because on a Minecraft server that
 * class lives in code the client half never loads. Everything on screen came out of a description, and
 * every listener behind it was attached by {@code ClientUiSession.wireReportedEvents} from the event
 * names the description carried.</p>
 *
 * <p>Which is the property worth taking away: <b>adding a control to this panel is a change to
 * {@link MachinePanel} and {@link MachineWindow} only.</b> Nothing on this side is recompiled, and an
 * old client draws a new panel correctly.</p>
 *
 * <h3>...and it is optional</h3>
 *
 * <p>Delete the registration and the panel still opens, still renders, and still reports every event
 * the server asked for. Only the three buttons <em>this side</em> drives would stop working. A
 * behaviour adds local extras; it is not what makes a window work. Minecraft cannot do that — an
 * unregistered {@code MenuType} there is a broken screen — and the difference is that a description is
 * self-sufficient where a {@code MenuType} is only a key to code.</p>
 *
 * <h3>Two ways to put behaviour on a widget, and the button cannot tell</h3>
 *
 * <p>The server wired {@code Power}, {@code Purge}, {@code Ping client} and {@code Announce} through
 * {@code io.on(...)}, which stamps an event name on the element so this side reports a press across the
 * wire. The three below are the other thing a client may do: {@code attachListener} on a widget it was
 * handed, with the listener staying <b>entirely local</b>. Nothing about it crosses the wire, the server
 * never learns the listener exists, and the description is unchanged.</p>
 */
public final class MachineClient implements ClientWindowBehaviour {

    private ClientWindowContext window;

    /**
     * The panel, <b>bound to the tree this client rebuilt</b> — not the server's object.
     *
     * <p>Same class, same field names, different instance over a different tree. Writing to it is a
     * local preview the next state delta overwrites; the server is reached through the session.</p>
     */
    private MachinePanel panel;

    /**
     * Built by {@link ClientWindows} when a window of {@link MachinePanel#TYPE} opens.
     *
     * <p>Everything it needs arrives here: the panel already bound to the rebuilt tree, and the
     * context carrying the session for calls and notifications. There is no connection to find, no
     * session to adopt, no tree to search and nothing to wait for — by the time this runs the window
     * is on screen.</p>
     */
    public MachineClient(MachinePanel panel, ClientWindowContext window) {
        this.panel = panel;
        this.window = window;
        wire(panel);

        /*
         * ── S -> C NOTIFICATION, the receiving half ───────────────────────────────────────────
         *
         * On the SESSION, which is window-scoped. It used to be on the connection, keyed by method name
         * alone -- so a second Machine window on one wire threw at open. The handler takes a payload and
         * returns nothing; there is no responder because there is nobody to respond to.
         *
         * If nothing registered this method, the router would log it ONCE and drop it. Once rather than
         * per message, because the usual cause is a peer one version ahead sending something at frame
         * rate, and a log line per frame buries whatever else is wrong.
         */
        window.session().onNotify("machine/announce", payload -> {
            String text = payload.getString("text", "");
            MachineTrace.log(MachineTrace.CLIENT, "<- announcement: \"" + text + "\" (after "
                    + payload.getInt("cycles", 0) + " cycles) -- nothing to answer");
            show("NOTIFY received - the server said \"" + text + "\", nothing sent back");
        });

        /*
         * Symmetric with the server's onCall: either peer may call the other, and both use the same
         * request/response envelope. This one is answered from nothing but the client's own state, which
         * is the honest use of the direction -- the server asking the client for a fact only the client
         * has.
         *
         * Answer exactly once. The responder enforces it, and a handler that answers twice is a
         * programming error rather than a protocol one.
         */
        window.session().onCall("machine/clientInfo", (args, respond) -> {
            MachineTrace.log(MachineTrace.CLIENT, "-> answering machine/clientInfo");
            StateMap<Object> out = new StateMap<>(window.session().ops());
            out.putString("renderer", "example-client");
            out.putBool("cachedDescription", window.session().cacheSize() > 0);
            respond.ok(out);
            show("REQUEST answered - the server asked who we are, and we told it");
        });
    }

    /**
     * The server re-described the window: {@code context.root()} is a <b>new tree</b> and every listener
     * attached to the old one went with it.
     *
     * <p>Not hypothetical — a re-open is how a reshape reaches a client that missed the delta. The
     * session's own registrations survive (they are keyed by method, not by element); only the widget
     * listeners have to be put back.</p>
     */
    @Override
    public void onContentReplaced(ClientWindowContext context) {
        this.window = context;
        // RE-BOUND, because the old panel's fields point into a tree nothing updates any more. The
        // session's own registrations survive -- those are keyed by method, not by element.
        this.panel = MachinePanel.TYPE.bind(context.root());
        wire(panel);
    }

    @Override
    public void onClosed(String reason) {
        MachineTrace.log(MachineTrace.CLIENT, "window closed: " + reason);
    }

    // ── Behaviour this side adds for itself ─────────────────────────────────

    /**
     * Three lines, and each one is checked.
     *
     * <p>This used to be three {@code querySelector} calls guarded by {@code instanceof}, and the
     * guard was the problem: the day an id moved, the line <b>silently did nothing</b> and left a
     * button that looked wired and was not. Binding resolves every part once, up front, and fails
     * loudly there instead. @see MachinePanel#bindTo</p>
     */
    private void wire(MachinePanel panel) {
        panel.askStats.attachListener(this::requestStats);
        panel.heartbeat.attachListener(this::sendHeartbeat);
        // Deliberately blank -- the server refuses it, which is the only way to watch the error callback
        // fire. See MachineWindow's machine/rename handler.
        panel.badRename.attachListener(() -> rename("   "));
    }

    /**
     * ── C → S REQUEST ── Asks the server for its numbers, and hands the answer to a caller.
     *
     * <p><b>Two callbacks rather than one nullable result.</b> "The machine has run 12 cycles" and "the
     * server never answered" are different facts, and a UI that renders them the same way is lying
     * about one of them. The router expires the request on its own deadline — 10 seconds by default —
     * so the error path is genuinely reached rather than being a promise nobody keeps.</p>
     *
     * <p>Both callbacks run on the thread that ticked the connection, some frames later. Nothing here
     * blocks, and there is no version of this that could: a round trip is a round trip, and an API that
     * hid that would be lying about where the latency is.</p>
     */
    public void requestStats(Consumer<StateMap<Object>> onResult, Consumer<String> onError) {
        MachineTrace.log(MachineTrace.CLIENT, "-> asking the server machine/stats");
        window.session().call("machine/stats", null, onResult::accept, onError::accept);
    }

    /**
     * What the {@code Ask stats} button is bound to — {@link #requestStats(Consumer, Consumer)} with the
     * answer written onto the panel.
     *
     * <p>Delegates rather than making the call itself, so there is <b>one</b> statement of
     * {@code machine/stats} in this class. Two copies of a call is how the two drift on the day somebody
     * adds a field to the request.</p>
     */
    public void requestStats() {
        show("REQUEST sent to the server - waiting for an answer...");
        requestStats(
                stats -> {
                    String text = stats.getInt("cycles", -1) + " cycles, "
                            + stats.getInt("heartbeats", 0) + " heartbeats, label '"
                            + stats.getString("label", "?") + "'";
                    MachineTrace.log(MachineTrace.CLIENT, "<- server answered: " + text);
                    show("REQUEST answered - " + text);
                },
                error -> {
                    MachineTrace.log(MachineTrace.CLIENT, "<- server refused: " + error);
                    show("REQUEST failed - " + error);
                });
    }

    /**
     * ── C → S NOTIFICATION ── Tells the server we are here. Nothing comes back.
     *
     * <p>There is no callback to pass, and that is not an omission — <b>a notification that could fail
     * visibly would be a request.</b> If the server has no handler for this method it logs once and
     * drops it, and this side is never told, which is the whole bargain.</p>
     */
    public void sendHeartbeat() {
        StateMap<Object> out = new StateMap<>(window.session().ops());
        out.putString("from", "example-client");
        MachineTrace.log(MachineTrace.CLIENT, "-> notifying machine/heartbeat (no answer expected)");
        window.session().notify("machine/heartbeat", out);
        // No "waiting" line: nothing is coming. The server's own readout will replace this one a tick
        // later, which is itself worth watching -- that line is the AUTHORITATIVE one.
        show("NOTIFY sent to the server - nothing will come back");
    }

    /**
     * ── C → S REQUEST THAT FAILS ── Asks the server to rename the machine.
     *
     * <p>Call it with a blank name and the server answers {@code EMPTY_NAME} through
     * {@code respond.fail}. That is an ordinary answer that happens to say no — same envelope as a
     * success, same thread, same latency — not an exception and not a timeout. The distinction matters
     * because a UI must tell "refused" apart from "never came back", and only one of those is worth
     * retrying.</p>
     */
    public void rename(String name) {
        StateMap<Object> args = new StateMap<>(window.session().ops());
        args.putString("name", name);
        MachineTrace.log(MachineTrace.CLIENT, "-> asking the server machine/rename('" + name + "')");
        show("REQUEST sent to the server - waiting for an answer...");
        window.session().call("machine/rename", args,
                ok -> {
                    MachineTrace.log(MachineTrace.CLIENT, "<- rename accepted");
                    show("REQUEST answered - renamed to \"" + name + "\"");
                },
                error -> {
                    MachineTrace.log(MachineTrace.CLIENT, "<- rename REFUSED: " + error);
                    show("REQUEST REFUSED - the server answered \"" + error
                            + "\". A normal answer that says no: not an error, not a timeout.");
                });
    }

    /** The session, for a caller that wants to reach past this class. */
    public ClientUiSession<Object> session() {
        return window.session();
    }

    /** The tree the server described, as rebuilt here. */
    @Nullable
    public UIElement root() {
        return window.root();
    }

    /** The panel bound to that tree. Same field names as the server's, different object. */
    public MachinePanel panel() {
        return panel;
    }

    /**
     * Writes a line into the panel's readout — <b>the client's line, and only ever that one</b>.
     *
     * <p>Worth knowing rather than working around. The <em>other</em> line belongs to the server: it is
     * in the description, the server pushes it through {@code ui/stateDelta}, and the next delta
     * touching it overwrites whatever this wrote. So a client-side write to a server-owned widget is a
     * <em>preview</em>, not a fact — which is why the two sides have a line each rather than sharing
     * one. Sharing produced a readout with this side's badge above the other side's sentence, and no
     * amount of care at the call sites could have prevented it.</p>
     *
     * <p>The one exception the engine makes is deliberate and narrow: {@code ClientUiSession} suppresses
     * an incoming state update for an element that is <b>focused and consumes text</b>, so the server
     * echoing your own keystrokes back cannot reset the caret mid-word.</p>
     */
    private void show(String text) {
        /*
         * A FIELD NOW, and it used to be querySelector("#result-client") plus an instanceof.
         *
         * The asymmetry it demonstrated is still exactly true and is worth keeping in mind: the server
         * holds the panel OBJECT and reaches a field, while this side holds a tree it rebuilt from a
         * description. What changed is that the binding does that translation once, at mount, instead
         * of at every call site -- so both halves now read `panel.clientLine`, sharing a class and no
         * object at all.
         */
        panel.clientLine.setText(text);
    }
}
