package com.crystalgui.example.machine.session;

import com.crystalgui.example.machine.MachineModel;
import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.example.machine.ui.MachinePanel;
import com.crystalgui.example.machine.ui.MachineStyles;
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
         * A method the CLIENT can call on us, answered exactly once. This is an ordinary request in
         * the same four-kind envelope as everything else -- there is no separate RPC system, and
         * "adding a message" is adding a string rather than a packet class.
         *
         * Namespaced with a slash after LSP's textDocument/hover. Nothing validates the name: an
         * unknown method is answered with METHOD_NOT_FOUND rather than dropped, so a peer learns
         * instead of waiting out a timeout.
         */
        session.onCall("machine/stats", (args, respond) -> {
            MachineTrace.log(MachineTrace.SERVER, "answering machine/stats");
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putInt("cycles", model.completedCycles());
            out.putString("label", model.label());
            respond.ok(out);
        });

        // Seed the widgets from the model before the description is taken, so the very first tree
        // the client builds is already correct -- rather than correct one state delta later.
        pushModelIntoPanel();
        session.open();
        MachineTrace.log(MachineTrace.SERVER, "opened window " + WINDOW_ID
                + ", " + countElements(panel.root) + " elements, hash=" + session.descHash());
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
