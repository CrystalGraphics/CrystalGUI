package com.crystalgui.example.machine.session;

import com.crystalgui.example.machine.MachineModel;
import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.example.machine.ui.MachinePanel;
import com.crystalgui.example.machine.ui.MachinePanel;
import com.crystalgui.example.machine.ui.MachineStyles;
import com.crystalgui.graph.GraphCodecs;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.UiEventKinds;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;

/**
 * <b>Step 4 — the server half.</b>
 *
 * <p>Owns a {@link MachineModel}, a {@link MachinePanel} and a {@link ServerUiSession}, and is the
 * only class in the example that knows all three exist. Everything below is one of three jobs:
 * wire the panel's events to the model, push the model back into the panel, and open.</p>
 *
 * <h3>The session holds the behaviour; the element holds only a name</h3>
 *
 * <p>{@code session.on(element, kind, handler)} does two separate things, and the split is the
 * design:</p>
 *
 * <ul>
 *   <li>the lambda is stored <b>on the session</b>, keyed by element and kind — it never goes near
 *       the widget, and it certainly never goes over a wire;</li>
 *   <li>the element learns the event's <b>name</b>, and that is all the description carries.</li>
 * </ul>
 *
 * <p>The client reads that name and attaches a real listener of its own — {@code Button.attachListener}
 * for {@code activate}, {@code Slider.attachListener} for {@code value}, and so on. So the client
 * knows <em>that</em> a press should be reported and has no idea what it means; the server holds the
 * meaning and never sees the press. A description that could carry a callback would be a description
 * that lets a server run code on a client, which is a different and much worse product.</p>
 *
 * <h3>Registering a handler after {@code open()} throws, deliberately</h3>
 *
 * <p>The set of reported events is part of the description the client has already been rebuilt from.
 * A handler added afterwards would sit on the server waiting for an event no client will ever send —
 * a control that is wired, looks wired, and silently does nothing. Failing at the call is the only
 * moment that mistake is cheap.</p>
 *
 * <h3>The three contract shapes, and where each one lives</h3>
 *
 * <p>There are only two message kinds you will ever write — <b>a request, which must be answered
 * exactly once</b>, and <b>a notification, which must not be answered at all</b>. Each has a
 * register-side and a send-side, and they are on different classes:</p>
 *
 * <table>
 *   <tr><th>Shape</th><th>Register</th><th>Send</th><th>Lives on</th></tr>
 *   <tr><td>Request → Response</td><td>{@code onCall(method, handler)}</td>
 *       <td>{@code call(method, args, onResult, onError)}</td>
 *       <td>{@link ServerUiSession} / {@code ClientUiSession}</td></tr>
 *   <tr><td>Notification</td><td>{@code onNotify(method, handler)}</td>
 *       <td>{@code notify(method, payload)}</td>
 *       <td>{@link ProtocolConnection} — <b>not the session</b></td></tr>
 *   <tr><td>Widget event</td><td>{@code session.on(element, kind, handler)}</td>
 *       <td>— the client sends it for you</td><td>{@link ServerUiSession}</td></tr>
 * </table>
 *
 * <p>The third row is not a third mechanism: it is a notification ({@code ui/event}) whose sending
 * side is written once inside {@code ClientUiSession} rather than by you. It is listed because from
 * where you sit it looks like a peer of the other two.</p>
 *
 * <p><b>Which shape to pick has one question behind it: is anybody waiting?</b> If the caller needs
 * an answer — or needs to know it <em>failed</em> — it is a request. If it is "here is a thing that
 * happened", it is a notification, and making it a request buys a round trip for a reply nobody
 * reads. Getting it wrong is not fatal either way, which is exactly why it is worth deciding
 * deliberately.</p>
 *
 * <h3>The four directions, one button each</h3>
 *
 * <p>The panel's demo strip exists so all four are reachable by pressing something:</p>
 *
 * <table>
 *   <tr><th></th><th>Server → Client</th><th>Client → Server</th></tr>
 *   <tr><td><b>Request</b></td>
 *       <td>{@code Ping client} → {@code machine/clientInfo}</td>
 *       <td>{@code Ask stats} → {@code machine/stats}</td></tr>
 *   <tr><td><b>Notification</b></td>
 *       <td>{@code Announce} → {@code machine/announce}</td>
 *       <td>{@code Heartbeat} → {@code machine/heartbeat}</td></tr>
 * </table>
 *
 * <p>Plus {@code Rename to ''}, which is a request the server <b>refuses</b> — the only way to see
 * the error callback fire, and the reason a request has two callbacks rather than one nullable
 * result.</p>
 *
 * <h3>Nothing here sends a state update</h3>
 *
 * <p>Look at {@link #pushModelIntoPanel()}: it writes widget setters and stops. The session
 * implements {@code UITreeObserver}, so every one of those setters marks its element state-dirty and
 * the session collects them; {@link #tick()} flushes the whole batch as one {@code ui/stateDelta}.
 * That is why ten mutations in a tick cost one message, and why nothing in this class has to know
 * which fields it changed.</p>
 */
public final class MachineServer {

    /** Any number both peers agree on; in a real mod, one per open GUI. See {@code UiMethods}. */
    public static final int WINDOW_ID = 7001;

    private final MachineModel model = new MachineModel();
    private final MachinePanel panel = new MachinePanel();

    private ServerUiSession<Object> session;

    /**
     * Kept, because <b>the session does not expose notifications</b>.
     *
     * <p>{@link ServerUiSession} gives you {@code onCall}/{@code call} — request and response — and
     * nothing else, because a UI session's own vocabulary happens to be all it needs. The other pair,
     * {@code onNotify}/{@code notify}, lives one layer down on the {@link ProtocolConnection}, which
     * is where <em>every</em> subsystem on this wire meets. So a panel that wants to send something
     * unanswered holds the connection too. It is the same wire either way.</p>
     */
    private ProtocolConnection<Object> connection;

    /** Counts what the client has told us without being asked. Proof a notification arrived. */
    private int heartbeats;

    /** Set when the model moves; cleared by {@link #tick()} after the panel has been updated. */
    private boolean dirty = true;

    public MachineModel model() {
        return model;
    }

    public MachinePanel panel() {
        return panel;
    }

    public ServerUiSession<Object> session() {
        return session;
    }

    /**
     * Puts this panel on a connection.
     *
     * <p>The connection comes from {@code Protocols.open(...)}, and on a real server there is one per
     * player — {@code CgUiConnections} opens them on join. Several subsystems ride the same one (the
     * UI, the workspace file protocol, a script runtime), which is why the session takes a connection
     * rather than a transport: a transport is a pipe, a connection is a pipe with a router on it.</p>
     */
    public void open(ProtocolConnection<Object> connection) {
        if (session != null) throw new IllegalStateException("already open");

        this.connection = connection;
        session = new ServerUiSession<>(WINDOW_ID, panel.root, connection)
                .addSheet(MachineStyles.SHEET)
                // Without this the client applies our sheet alone and every widget loses the
                // functional geometry the engine's own sheet gives it -- a stack of unstyled boxes.
                .setUseUserAgentSheet(true);

        model.onChanged(() -> dirty = true);

        // ── Behaviour. All of it before open(), for the reason in the class javadoc. ──

        /*
         * EVERY ONE OF THESE LAMBDAS RUNS ON THE SERVER THREAD, because that is the thread that
         * drained the connection. That is the whole reason they may touch the model at all. Watch the
         * console when you flip the switch in game: the CLIENT line is on the client thread and the
         * SERVER line that follows it is on the server thread, from one gesture.
         */
        session.on(panel.power, UiEventKinds.TOGGLE, ctx -> {
            boolean on = ctx.payload().getBool("checked", false);
            MachineTrace.log(MachineTrace.SERVER, "event: power -> " + on);
            model.setRunning(on);
        });

        session.on(panel.throughput, UiEventKinds.VALUE, ctx -> {
            float value = ctx.payload().getFloat("value", 0f);
            MachineTrace.log(MachineTrace.SERVER, String.format("event: throughput -> %.2f", value));
            model.setThroughput(value);
        });

        session.on(panel.label, UiEventKinds.TEXT, ctx -> {
            String text = ctx.payload().getString("text", "");
            MachineTrace.log(MachineTrace.SERVER, "event: label -> '" + text + "'");
            model.setLabel(text);
        });

        session.onActivate(panel.purge, ctx -> {
            MachineTrace.log(MachineTrace.SERVER, "event: purge pressed");
            model.purge();
        });

        /*
         * ── S -> C REQUEST ────────────────────────────────────────────────────────────────────
         *
         * A button whose handler asks the CLIENT a question. Two lambdas, never one nullable result:
         * "the client renders with example-client" and "the client never answered" are different
         * facts, and a UI that shows them the same way is lying about one of them. The router expires
         * the request on its own deadline, so the error path is genuinely reached rather than being a
         * promise nobody keeps.
         *
         * The answer lands on the SERVER thread, in this lambda, some ticks later -- so writing it
         * into a widget is an ordinary state change and travels back as an ordinary state delta.
         * There is no special "reply arrived" path anywhere.
         */
        session.onActivate(panel.pingClient, ctx -> {
            MachineTrace.log(MachineTrace.SERVER, "-> asking the client machine/clientInfo");
            // WRITTEN BEFORE THE CALL, and this is the point of the whole readout: a request has a
            // gap between asking and knowing, and a notification does not. Pressing Ping client
            // shows this line and then replaces it; pressing Announce never shows one at all.
            say("REQUEST sent to the client - waiting for an answer...");
            session.call("machine/clientInfo", null,
                    info -> {
                        String renderer = info.getString("renderer", "?");
                        MachineTrace.log(MachineTrace.SERVER, "<- client answered: " + renderer);
                        say("REQUEST answered - the client says it is '"
                                + renderer + "'");
                    },
                    error -> {
                        MachineTrace.log(MachineTrace.SERVER, "<- client refused: " + error);
                        say("REQUEST failed - " + error);
                    });
        });

        /*
         * ── S -> C NOTIFICATION ───────────────────────────────────────────────────────────────
         *
         * The same wire, no answer, and note WHERE IT IS SENT FROM: the connection, not the session.
         * There is no callback to pass because there is nothing to call back with -- a notification
         * that could fail visibly would be a request.
         *
         * The method name is ours; nothing validates it. An unknown notification is logged once by
         * the receiving router and dropped, which is the correct treatment: nobody is waiting.
         */
        session.onActivate(panel.announce, ctx -> {
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putString("text", model.label() + " says hello");
            out.putInt("cycles", model.completedCycles());
            MachineTrace.log(MachineTrace.SERVER, "-> notifying machine/announce (no answer wanted)");
            connection.notify("machine/announce", out);
            // No "waiting" line, because there is nothing to wait for. That absence is the difference
            // between the two shapes, and it is the one thing this readout exists to make visible.
            say("NOTIFY sent to the client - nothing will come back");
        });

        /*
         * ── C -> S NOTIFICATION, the receiving half ───────────────────────────────────────────
         *
         * Registered on the CONNECTION for the same reason as above. The handler takes a payload and
         * returns nothing: there is no responder, because there is nobody to respond to.
         */
        connection.onNotify("machine/heartbeat", payload -> {
            heartbeats++;
            MachineTrace.log(MachineTrace.SERVER,
                    "<- heartbeat #" + heartbeats + " from " + payload.getString("from", "?"));
            say("NOTIFY received - heartbeat #" + heartbeats
                    + " from the client, nothing sent back");
        });

        /*
         * A method the CLIENT can call on us, answered exactly once. This is an ordinary request in
         * the same four-kind envelope as everything else -- there is no separate RPC system, and
         * "adding a message" is adding a string rather than a packet class.
         *
         * Namespaced with a slash after LSP's textDocument/hover. Nothing validates the name: an
         * unknown method is answered with METHOD_NOT_FOUND rather than dropped, so a peer learns
         * instead of waiting out a timeout.
         */
        session.onCall("machine/stats", (args, respond) -> {
            MachineTrace.log(MachineTrace.SERVER, "<- answering machine/stats");
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putInt("cycles", model.completedCycles());
            out.putString("label", model.label());
            out.putInt("heartbeats", heartbeats);
            //respond.ok(out);
            respond.fail("No permission");
            say("REQUEST answered - the client asked for our numbers and got them");
        });

        /*
         * ── A REQUEST THAT CAN FAIL ───────────────────────────────────────────────────────────
         *
         * The half of request/response that a happy-path example never shows, and the reason the
         * caller passes two lambdas.
         *
         * FAIL WITH A CODE, NEVER A MESSAGE. A client branches on a value; it cannot branch on
         * prose, and matching on message text is a coupling nobody remembers making. It is also a
         * leak: an exception's message can name a directory on the server's disk. WorkspaceRpc does
         * exactly this -- CgFileError names, plus CONFLICT with the real etag -- and reports anything
         * unexpected as UNKNOWN rather than passing the stack trace outward.
         *
         * respond.fail is not an exception. It is a normal answer that happens to say no, it arrives
         * on the same envelope as a success, and the caller's error lambda runs on the client thread
         * exactly as the result lambda would have.
         */
        session.onCall("machine/rename", (args, respond) -> {
            String name = args.getString("name", "");
            if (name.trim().isEmpty()) {
                MachineTrace.log(MachineTrace.SERVER, "<- refusing machine/rename: EMPTY_NAME");
                respond.fail("EMPTY_NAME");
                say("REQUEST refused - the client asked for a blank name, so we answered EMPTY_NAME");
                return;
            }
            MachineTrace.log(MachineTrace.SERVER, "<- machine/rename -> '" + name + "'");
            model.setLabel(name);
            respond.ok(null);   // an answer with no body is still an answer, and still exactly once
            say("REQUEST answered - renamed to '" + name + "'");
        });

        // Seed the widgets from the model before the description is taken, so the very first tree
        // the client builds is already correct -- rather than correct one state delta later.
        pushModelIntoPanel();
        session.open();
        MachineTrace.log(MachineTrace.SERVER, "opened window " + WINDOW_ID
                + ", " + countElements(panel.root) + " elements, hash=" + session.descHash());
    }

    /**
     * Writes the SERVER's result line. <b>Nothing else in the process may write it.</b>
     *
     * <p>That exclusivity is the whole design, not tidiness — see the comment where the two lines are
     * built. Two authors on one element produced a readout with the client's badge above the server's
     * sentence, and no amount of care at the call sites could have prevented it.</p>
     */
    private void say(String message) {
        panel.serverLine.setText(message);
    }

    private static int countElements(com.crystalgui.ui.UIElement element) {
        int total = 1;
        for (com.crystalgui.ui.UIElement child : element.getChildren()) total += countElements(child);
        return total;
    }

    /**
     * One world tick.
     *
     * <p>Order matters and is not arbitrary: advance the model, mirror it into the widgets, then let
     * the session flush. Flushing first would send the previous tick's state and always run one frame
     * behind — which looks like network latency and is not.</p>
     */
    public void tick() {
        int before = model.completedCycles();
        model.tick();
        // Per CYCLE, not per tick: a line every tick is twenty a second and stops being readable, and
        // the thread it is on is the point rather than the number.
        if (model.completedCycles() != before) {
            MachineTrace.log(MachineTrace.SERVER, "cycle " + model.completedCycles() + " complete");
        }

        if (dirty) {
            pushModelIntoPanel();
            dirty = false;
        }

        /*
         * REQUIRED, and asymmetric with the client -- worth knowing, because the two read alike.
         *
         * ClientUiSession.tick() returns immediately when the session rides a connection: all it
         * would do is drain a mailbox somebody else already drained. ServerUiSession.tick() skips
         * that same drain and still FLUSHES, because flushing is the thing only it can do -- it is
         * the observer holding this tick's dirty set, and nothing else knows the set exists.
         *
         * So a server that stops calling this keeps a perfectly live session, answers calls, and
         * never sends another state update.
         */
        if (session != null) session.tick();
    }

    /**
     * The model is the truth; the widgets are a view of it.
     *
     * <p>Every setter here is idempotent — {@code setValue} with an unchanged value writes no
     * candidate and marks nothing dirty (the cascade's {@code replaceOrPutCandidate} no-ops on an
     * unchanged value). So calling this more often than necessary costs a few comparisons, not
     * traffic, and there is no need to work out which field moved.</p>
     */
    private void pushModelIntoPanel() {
        panel.power.setChecked(model.isRunning());
        panel.throughput.setValue(model.throughput());
        panel.progress.setFraction(model.progress());
        panel.label.setText(model.label());
        panel.status.setText((model.isRunning() ? "running" : "stopped")
                + " - " + model.completedCycles() + " cycles");
    }

    /**
     * Tells the client to put the window away, then stops.
     *
     * <p>The reason is not decoration: "the block was broken", "you walked away" and "the server is
     * shutting down" are three different things that all look like a window vanishing, and a report
     * of it can only ever be that it disappeared.</p>
     */
    public void close(String reason) {
        if (session != null) session.close(reason);
        MachineTrace.log(MachineTrace.SERVER, "closed: " + reason);
    }
}
