package com.crystalgui.example.machine.session;

import javax.annotation.Nullable;

import com.crystalgui.example.machine.MachineModel;
import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.example.machine.ui.MachinePanel;
import com.crystalgui.example.machine.ui.MachineStyles;
import com.crystalgui.net.UiEventKinds;
import com.crystalgui.net.window.ServerWindows;
import com.crystalgui.net.window.ServerWindow;
import com.crystalgui.net.window.WindowScope;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;

/**
 * <b>Step 4 — the server half.</b> One {@link ServerWindow}: a tree, some behaviour, three questions.
 *
 * <p>Compare what is <em>not</em> here. No session construction, no window id, no
 * {@code session.open()}, no tick loop, no player map, no close handling, no logout hook. All of that
 * belongs to {@link ServerWindows}, which is the whole point: opening a UI is
 * {@code ServerWindows.of(connection).open(new MachineWindow(model))}, and everything after it is the
 * engine's.</p>
 *
 * <h3>This window is a VIEW of the machine; it is not the machine</h3>
 *
 * <p>{@link MachineModel} is passed in and ticks somewhere else — with the world, in
 * {@code MachineExample}. So closing the panel does not stop the machine, and opening it does not start
 * one. {@link #tick()} here mirrors the model into widgets and stops, which is all a view ever does.</p>
 *
 * <p>The earlier version of this class fused the two — it advanced the model from its own tick — and
 * the fusion was invisible until you asked what happens when the last viewer leaves. The answer was
 * that the machine stopped existing, which is the opposite of what a server-authoritative UI is
 * for.</p>
 *
 * <h3>The session holds the behaviour; the element holds only a name</h3>
 *
 * <p>{@link WindowScope#on} does two separate things, and the split is the design:</p>
 *
 * <ul>
 *   <li>the lambda is stored <b>on the session</b>, keyed by element and kind — it never goes near the
 *       widget, and it certainly never goes over a wire;</li>
 *   <li>the element learns the event's <b>name</b>, and that is all the description carries.</li>
 * </ul>
 *
 * <p>The client reads that name and attaches a real listener of its own. So the client knows
 * <em>that</em> a press should be reported and has no idea what it means; the server holds the meaning
 * and never sees the press. A description that could carry a callback would be a description that lets
 * a server run code on a client, which is a different and much worse product.</p>
 *
 * <h3>The three contract shapes, and where each one lives</h3>
 *
 * <p>There are only two message kinds you will ever write — <b>a request, which must be answered
 * exactly once</b>, and <b>a notification, which must not be answered at all</b>. Each has a
 * register-side and a send-side, and since the host exists <em>both pairs are on the scope</em>:</p>
 *
 * <table>
 *   <tr><th>Shape</th><th>Register</th><th>Send</th></tr>
 *   <tr><td>Request → Response</td><td>{@code io.onCall(method, handler)}</td>
 *       <td>{@code io.call(method, args, onResult, onError)}</td></tr>
 *   <tr><td>Notification</td><td>{@code io.onNotify(method, handler)}</td>
 *       <td>{@code io.notify(method, payload)}</td></tr>
 *   <tr><td>Widget event</td><td>{@code io.on(element, kind, handler)}</td>
 *       <td>— the client sends it for you</td></tr>
 * </table>
 *
 * <p><b>The notification pair used to be somewhere else</b>, and it mattered. It lived on the
 * {@code ProtocolConnection}, which keys handlers by method name alone — so a second Machine window on
 * one connection registered {@code machine/heartbeat} twice and <em>threw at open</em>. Through the
 * scope both pairs are window-scoped, and two windows may each name the same method. A notification
 * that genuinely belongs to the connection rather than to a window — a workspace, a script runtime —
 * still registers on {@code ProtocolConnection} directly, which is what it wants.</p>
 *
 * <p><b>Which shape to pick has one question behind it: is anybody waiting?</b> If the caller needs an
 * answer — or needs to know it <em>failed</em> — it is a request. If it is "here is a thing that
 * happened", it is a notification, and making it a request buys a round trip for a reply nobody reads.
 * Getting it wrong is not fatal either way, which is exactly why it is worth deciding deliberately.</p>
 *
 * <h3>The four directions, one button each</h3>
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
 * <p>Plus {@code Rename to ''}, which is a request the server <b>refuses</b> — the only way to see the
 * error callback fire, and the reason a request has two callbacks rather than one nullable result.</p>
 *
 * <p>The {@code machine/} prefix is now <em>readability</em> rather than collision avoidance: a
 * window's methods are already scoped to that window, so two of these panels do not interfere. It is
 * kept because a log line reading {@code machine/stats} says where to look and one reading
 * {@code stats} does not.</p>
 *
 * <h3>Nothing here sends a state update</h3>
 *
 * <p>Look at {@link #mirror()}: it writes widget setters and stops. The session implements
 * {@code UITreeObserver}, so every one of those setters marks its element state-dirty and the session
 * collects them; the host flushes the whole batch as one {@code ui/stateDelta} after every tick. That
 * is why ten mutations in a tick cost one message, and why nothing in this class has to know which
 * fields it changed.</p>
 */
public final class MachineWindow extends ServerWindow {

    /** What a client dispatches its local behaviour on. @see MachineClient */
    public static final String TYPE = "crystalgui:machine";

    /**
     * One panel per viewer, and re-opening brings the existing one forward.
     *
     * <p>Minecraft's close-the-previous-container rule, narrowed to the same <em>subject</em>: pressing
     * F8 twice must not stack two identical panels, and must not throw away the first one's scroll
     * position to build a second.</p>
     */
    public static final String KEY = "crystalgui:machine";

    /** Passed in. This window does not own it and does not advance it. @see MachineWindow */
    private final MachineModel model;

    private final MachinePanel panel = new MachinePanel();

    /** Counts what the client has told us without being asked. Proof a notification arrived. */
    private int heartbeats;

    /** Set when the model moves; cleared by {@link #tick()} once the panel has caught up. */
    private boolean dirty = true;

    public MachineWindow(MachineModel model) {
        this.model = model;
        model.onChanged(() -> dirty = true);
    }

    public MachineModel model() {
        return model;
    }

    public MachinePanel panel() {
        return panel;
    }

    // ── What this window says about itself ──────────────────────────────────

    @Override
    public String type() {
        return TYPE;
    }

    @Nullable
    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Machine control";
    }

    @Override
    public UIElement root() {
        return panel.root;
    }

    // ── What it does ────────────────────────────────────────────────────────

    /**
     * Everything this panel can do. Called once, by the host, before the client is told anything.
     *
     * <p>The ordering that used to be a rule a caller had to follow — <i>handlers before open</i> — is
     * now structural: the host calls this and then opens, so there is no way to get it wrong from
     * here.</p>
     */
    @Override
    protected void bind(WindowScope io) {
        /*
         * NAMED, AND OFFERED. The ref is a content hash the client may already hold; the CSS beside it
         * is what a client that does not can fetch with ui/sheet. Before that message existed, a host
         * had to resolve refs from a constant in its own jar -- fine for a mod installed on both sides,
         * and a wall for anything a server authors.
         *
         * The engine's own sheet stays underneath (the default): without it every widget loses the
         * functional geometry it gives them and the panel is a stack of unstyled boxes.
         */
        io.sheet(MachineStyles.SHEET, MachineStyles.CSS);

        /*
         * EVERY ONE OF THESE LAMBDAS RUNS ON THE SERVER THREAD, because that is the thread that drained
         * the connection. That is the whole reason they may touch the model at all. Watch the console
         * when you flip the switch in game: the CLIENT line is on the client thread and the SERVER line
         * that follows it is on the server thread, from one gesture.
         */
        io.on(panel.power, UiEventKinds.TOGGLE, ctx -> {
            boolean on = ctx.payload().getBool("checked", false);
            MachineTrace.log(MachineTrace.SERVER, "event: power -> " + on);
            model.setRunning(on);
        });

        io.on(panel.throughput, UiEventKinds.VALUE, ctx -> {
            float value = ctx.payload().getFloat("value", 0f);
            MachineTrace.log(MachineTrace.SERVER, String.format("event: throughput -> %.2f", value));
            model.setThroughput(value);
        });

        io.on(panel.label, UiEventKinds.TEXT, ctx -> {
            String text = ctx.payload().getString("text", "");
            MachineTrace.log(MachineTrace.SERVER, "event: label -> '" + text + "'");
            model.setLabel(text);
        });

        io.onActivate(panel.purge, ctx -> {
            MachineTrace.log(MachineTrace.SERVER, "event: purge pressed");
            model.purge();
        });

        /*
         * ── S -> C REQUEST ────────────────────────────────────────────────────────────────────
         *
         * A button whose handler asks the CLIENT a question. Two lambdas, never one nullable result:
         * "the client renders with example-client" and "the client never answered" are different facts,
         * and a UI that shows them the same way is lying about one of them. The router expires the
         * request on its own deadline, so the error path is genuinely reached rather than being a
         * promise nobody keeps.
         *
         * The answer lands on the SERVER thread, in this lambda, some ticks later -- so writing it into
         * a widget is an ordinary state change and travels back as an ordinary state delta. There is no
         * special "reply arrived" path anywhere.
         */
        io.onActivate(panel.pingClient, ctx -> {
            MachineTrace.log(MachineTrace.SERVER, "-> asking the client machine/clientInfo");
            // WRITTEN BEFORE THE CALL, and this is the point of the whole readout: a request has a gap
            // between asking and knowing, and a notification does not. Pressing Ping client shows this
            // line and then replaces it; pressing Announce never shows one at all.
            say("REQUEST sent to the client - waiting for an answer...");
            io.call("machine/clientInfo", null,
                    info -> {
                        String renderer = info.getString("renderer", "?");
                        MachineTrace.log(MachineTrace.SERVER, "<- client answered: " + renderer);
                        say("REQUEST answered - the client says it is '" + renderer + "'");
                    },
                    error -> {
                        MachineTrace.log(MachineTrace.SERVER, "<- client refused: " + error);
                        say("REQUEST failed - " + error);
                    });
        });

        /*
         * ── S -> C NOTIFICATION ───────────────────────────────────────────────────────────────
         *
         * The same wire, no answer. There is no callback to pass because there is nothing to call back
         * with -- a notification that could fail visibly would be a request.
         *
         * The method name is ours; nothing validates it. An unknown notification is logged once by the
         * receiving router and dropped, which is the correct treatment: nobody is waiting.
         */
        io.onActivate(panel.announce, ctx -> {
            StateMap<Object> out = io.newMap();
            out.putString("text", model.label() + " says hello");
            out.putInt("cycles", model.completedCycles());
            MachineTrace.log(MachineTrace.SERVER, "-> notifying machine/announce (no answer wanted)");
            io.notify("machine/announce", out);
            // No "waiting" line, because there is nothing to wait for. That absence is the difference
            // between the two shapes, and it is the one thing this readout exists to make visible.
            say("NOTIFY sent to the client - nothing will come back");
        });

        /*
         * ── C -> S NOTIFICATION, the receiving half ───────────────────────────────────────────
         *
         * The handler takes a payload and returns nothing: there is no responder, because there is
         * nobody to respond to.
         *
         * ON THE SCOPE, not on the connection. It used to be the latter, which keys by method name
         * alone -- so a second Machine window on one wire threw at open. The move is the whole of what
         * changed here, and it is why this example now survives being opened twice.
         */
        io.onNotify("machine/heartbeat", payload -> {
            heartbeats++;
            MachineTrace.log(MachineTrace.SERVER,
                    "<- heartbeat #" + heartbeats + " from " + payload.getString("from", "?"));
            say("NOTIFY received - heartbeat #" + heartbeats
                    + " from the client, nothing sent back");
        });

        /*
         * A method the CLIENT can call on us, answered exactly once. This is an ordinary request in the
         * same four-kind envelope as everything else -- there is no separate RPC system, and "adding a
         * message" is adding a string rather than a packet class.
         *
         * Namespaced with a slash after LSP's textDocument/hover. Nothing validates the name: an
         * unknown method is answered with METHOD_NOT_FOUND rather than dropped, so a peer learns
         * instead of waiting out a timeout.
         */
        io.onCall("machine/stats", (args, respond) -> {
            MachineTrace.log(MachineTrace.SERVER, "<- answering machine/stats");
            StateMap<Object> out = io.newMap();
            out.putInt("cycles", model.completedCycles());
            out.putString("label", model.label());
            out.putInt("heartbeats", heartbeats);
            respond.ok(out);
            say("REQUEST answered - the client asked for our numbers and got them");
        });

        /*
         * ── A REQUEST THAT CAN FAIL ───────────────────────────────────────────────────────────
         *
         * The half of request/response that a happy-path example never shows, and the reason the caller
         * passes two lambdas.
         *
         * FAIL WITH A CODE, NEVER A MESSAGE. A client branches on a value; it cannot branch on prose,
         * and matching on message text is a coupling nobody remembers making. It is also a leak: an
         * exception's message can name a directory on the server's disk. WorkspaceRpc does exactly this
         * -- CgFileError names, plus CONFLICT with the real etag -- and reports anything unexpected as
         * UNKNOWN rather than passing the stack trace outward.
         *
         * respond.fail is not an exception. It is a normal answer that happens to say no, it arrives on
         * the same envelope as a success, and the caller's error lambda runs on the client thread
         * exactly as the result lambda would have.
         */
        io.onCall("machine/rename", (args, respond) -> {
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

        // The very first tree the client builds is already correct, rather than correct one state delta
        // later. Safe to do here because bind() runs before the description is taken.
        mirror();
    }

    /**
     * One world tick — <b>mirror only</b>.
     *
     * <p>The model advanced somewhere else. The host flushes after this returns, so there is nothing to
     * send and nothing to remember to send.</p>
     */
    @Override
    protected void tick() {
        if (!dirty) return;
        mirror();
        dirty = false;
    }

    /**
     * Told once, however this window ended — closed by the server, closed by the user, no longer valid,
     * or the connection dying.
     *
     * <p>The reason is not decoration: "the block was broken", "you walked away" and "the server is
     * shutting down" are three different things that all look like a window vanishing, and a report of
     * it can only ever be that it disappeared.</p>
     */
    @Override
    protected void onClosed(CloseReason reason) {
        MachineTrace.log(MachineTrace.SERVER, "window closed: " + reason);
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

    /**
     * The model is the truth; the widgets are a view of it.
     *
     * <p>Every setter here is idempotent — {@code setValue} with an unchanged value writes no candidate
     * and marks nothing dirty (the cascade's {@code replaceOrPutCandidate} no-ops on an unchanged
     * value). So calling this more often than necessary costs a few comparisons, not traffic, and there
     * is no need to work out which field moved.</p>
     */
    private void mirror() {
        panel.power.setChecked(model.isRunning());
        panel.throughput.setValue(model.throughput());
        panel.progress.setFraction(model.progress());
        panel.label.setText(model.label());
        panel.status.setText((model.isRunning() ? "running" : "stopped")
                + " - " + model.completedCycles() + " cycles");
    }
}
