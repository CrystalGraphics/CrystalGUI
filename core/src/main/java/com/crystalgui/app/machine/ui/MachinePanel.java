package com.crystalgui.app.machine.ui;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.app.machine.MachineModel;
import com.crystalgui.app.machine.MachineTrace;
import com.crystalgui.net.window.CloseReason;
import com.crystalgui.net.window.ClientScope;
import com.crystalgui.net.window.Networked;
import com.crystalgui.net.window.ServerScope;
import com.crystalgui.net.window.UiType;
import com.crystalgui.serialization.StateMap;

import javax.annotation.Nullable;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.display.ProgressBar;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;

/**
 * <b>Step 2 — the widget tree.</b>
 *
 * <p>Six controls in a column. This class is built on the <em>server</em>, in a process with no
 * OpenGL and no fonts, and that is fine: constructing a {@link Switch} allocates an object and
 * touches no GPU. Only <em>painting</em> needs a graphics backend, and a server never paints.</p>
 *
 * <h3>No sizes, no colours, no timings in here</h3>
 *
 * <p>Look for a pixel value below. There is one — a {@code width(90)} on the label column — and it
 * is marked as the exception it is, because {@link UIText} pushes its own measured width at
 * {@code IMPORTANT} origin and a stylesheet cannot beat that. Everything else is named and styled
 * from {@link MachineStyles}.</p>
 *
 * <p>This is not a house style. It is the difference between a panel a resource pack can re-theme
 * and a panel that looks the way one programmer left it: a value written in Java arrives at
 * {@code INLINE} origin, which outranks every stylesheet rule at any specificity, so the theme
 * author's rule is not overridden — it never applies at all, and nothing reports that.</p>
 *
 * <h3>Why the widgets are public fields</h3>
 *
 * <p>{@link #serve} needs handles on individual widgets to attach behaviour and to write
 * state into. Hunting them back out with {@code querySelector} would work and would be worse: a
 * typo in a selector is a lookup that finds nothing at runtime, where a field is a compile error.
 * The tree is built once in the constructor and never reshaped, so the handles cannot go stale.</p>
 *
 * <h3>The ids are not decoration</h3>
 *
 * <p>{@code setId} is what {@code #power} in a stylesheet matches. It does <b>not</b> travel as an
 * addressing scheme — that is {@code ui.dom.ElementTreeSource}, which allocates a separate, stable
 * number per element and keeps it through a reparent and a sibling insert. The id is for the cascade;
 * the network id is for the protocol; they are unrelated and it is worth not confusing them.</p>
 *
 * <h3>And one of the fields is a whole other UI</h3>
 *
 * <p>{@link #engine} is an {@link EnginePanel} — a {@link Networked} panel of its own, nested here as
 * an ordinary field, because a panel <em>is</em> an element and elements have always nested. Read that
 * class for the composition rules; the three lines it costs on this side are in {@link #layout} and
 * {@link #serve}, and the reason the button that opens it sends nothing is in {@link #toggleEngine}.</p>
 */
public final class MachinePanel extends UIElement implements Networked<MachineModel> {

    /** This panel's kind. Declared here because a node answers the name its class declares,
     * and {@link com.crystalgui.net.window.UiType} READS this rather than deriving one. */
    public static final Name NAME = Name.of("machinepanel");

    public MachinePanel() {
        super(NAME);
    }

    /**
     * <b>What ties the two halves together.</b> Declared here because the panel is the artefact both
     * sides genuinely share — and because every reference in this initialiser points at this class,
     * which is what keeps it loadable on a dedicated server.
     *
     * <p>The server opens with it, and the client registers against it. Being a value rather than a
     * string, a mismatched pair is a compile error instead of a window that opens perfectly and
     * silently has no behaviour. Declaring it also registers this class's tag, so the description's
     * {@code <machinepanel>} decodes into this class on the far side. @see UiType</p>
     */
    public static final UiType<MachinePanel, MachineModel> TYPE =
            UiType.of("crystalgui:machine", MachinePanel::new);


    /** On/off. Reports {@code toggle}. */
    public Switch power;

    /** 0..1 throughput. Reports {@code value}. */
    public Slider throughput;

    /** The machine's name. Reports {@code text}. */
    public TextField label;

    /** Cycle progress. Server-driven only — the client never writes to it. */
    public ProgressBar progress;

    /** Abandons the cycle. Reports {@code activate}. */
    public Button purge = new Button("Purge");

    /** A line of server-written text. Also server-driven only. */
    public UIText status = new UIText("");

    // ── The nested UI. See EnginePanel, and #toggleEngine for the half that does not travel. ──

    /** The two captions {@link #showEngine} alternates between. Plain words: no glyph can be missing. */
    private static final String SHOW_ENGINE = "Show engine";
    private static final String HIDE_ENGINE = "Hide engine";

    /**
     * Opens and closes the engine section.
     *
     * <p>Wired on the <b>client</b>, in {@link #bound()}, and the server is never told: whether a
     * section is expanded is view state, exactly like a scroll position. @see #toggleEngine</p>
     */
    public Button showEngine = new Button(SHOW_ENGINE);

    /**
     * <b>A whole UI, as a field.</b>
     *
     * <p>That is the entire cost of composing here, because a {@link Networked} panel <em>is</em> an
     * element: it goes in the tree with {@code addChild}, it is described and rebuilt like anything
     * else, and the field name becomes its element id — which is also what namespaces its wire
     * methods, so this one's {@code "tune"} is {@code "engine/tune"} on both sides.</p>
     *
     * <p>Left null rather than initialised, because {@link UiType#build} deliberately does <b>not</b>
     * auto-create a nested panel: building one needs its slice of the model, and only this class knows
     * which slice that is. It is built in {@link #layout} and wired in {@link #serve}, one line
     * each.</p>
     */
    public EnginePanel engine;

    /**
     * The three collections — a streamed inventory, a followed log, and the workspace read through the
     * fs protocol. A nested {@link Networked} panel like {@link #engine}, attached over the whole model.
     */
    public StreamsPanel streams;

    /** The last thing the SERVER did. Nothing else may write it. */
    public UIText serverLine = new UIText("nothing yet");

    /** The last thing the CLIENT did. Nothing else may write it. */
    public UIText clientLine = new UIText("nothing yet");

    // ── The four protocol directions, one button each. See the table on #layout. ──

    /** SERVER-side handler → a REQUEST to the client, answered. */
    public Button pingClient = new Button("Ping client");

    /** SERVER-side handler → a NOTIFICATION to the client, unanswered. */
    public Button announce = new Button("Announce");

    /** CLIENT-side listener → a REQUEST to the server, answered. */
    public Button askStats = new Button("Ask stats");

    /** CLIENT-side listener → a NOTIFICATION to the server, unanswered. */
    public Button heartbeat = new Button("Heartbeat");

    /** CLIENT-side listener → a REQUEST the server REFUSES, so the error path is visible. */
    public Button badRename = new Button("Rename to ''");

    /**
     * State the SERVER half keeps. Ordinary fields — the framework only ever touches widget ones.
     *
     * <p>Two more used to sit here and are gone with {@code mirror()}: a {@code dirty} flag set from a
     * model subscription, and the {@code Runnable} that undid the subscription on close. Both existed
     * to answer "has anything changed", which is now the projection's question rather than the panel's.</p>
     */
    private int heartbeats;



    /** The CLIENT half's scope, stored by {@link #client}. Null on the server — which is the tell. */
    @Nullable
    private ClientScope io;

    @Override
    public String title(MachineModel model) {
        return "Machine control";
    }

    @Override
    public String key(MachineModel model) {
        return "crystalgui:machine";
    }

    /**
     * Structure only. <b>Build side.</b>
     *
     * <p>Every widget already exists and already carries its field name as its id by the time this
     * runs — the framework did that — so what is left is genuinely the arrangement, which is the one
     * thing no amount of reflection could infer. {@code this} is the root: a panel is an element.</p>
     */
    @Override
    public void build(MachineModel model) {
        addClass(MachineStyles.PANEL_CLASS);

        UIText title = new UIText("Machine control");
        title.addClass(MachineStyles.TITLE_CLASS);
        append(title);

        append(MachineRows.row("Power", power));

        throughput.setRange(0f, 1f);
        append(MachineRows.row("Throughput", throughput));

        label.setPlaceholder("name this machine");
        append(MachineRows.row("Label", label));

        append(MachineRows.row("Cycle", progress));

        status.addClass(MachineStyles.STATUS_CLASS);
        append(status);

        append(purge);

        /*
         * THE NESTED PANEL, and the two lines are the whole of it.
         *
         * EnginePanel is a Networked element, so it goes into the tree exactly as the rows above did.
         * What is NOT here is as informative as what is: no registration, no id string, no wire
         * contract, and nothing on the client at all. The field name becomes the element id, the
         * element id becomes the scope prefix, and MachinePanel.TYPE's own declaration already
         * registered <enginepanel> so the far side can decode one.
         *
         * build() rather than `new`: constructing a panel is create-fill-name-then-layout, and the
         * slice -- model.engine() -- is the input only this class can supply. That is exactly why
         * UiType.build fills every null widget field for you and deliberately leaves a nested PANEL
         * alone: it has no way to know which part of the model belongs to it.
         */
        append(showEngine);
        engine = EnginePanel.TYPE.build(model.engine());
        append(engine);

        /*
         * THE PROTOCOL DEMO STRIP.
         *
         * Each entry SAYS WHAT IT WILL DO, because the first version did not and was unreadable: five
         * buttons captioned "Ping client" and "Announce" under rows labelled "S -> C" tell a reader
         * the mechanism's initials and nothing about the outcome. A demo whose controls need the
         * source open beside them is not demonstrating anything.
         *
         * So every entry carries four facts, in the order somebody reads them:
         *
         *   KIND       is an answer coming back?  (REQUEST yes, NOTIFY no)
         *   DIRECTION  who starts it
         *   METHOD     the exact string on the wire, so the panel, the code and the log line up
         *   OUTCOME    one sentence of what to expect
         *
         * The other thing worth noticing is WHO LISTENS, and it is deliberately mixed. Ping client
         * and Announce are wired on the SERVER through session.on(...), so pressing them sends a
         * ui/event and the server's lambda runs. The last three are wired on the CLIENT, in
         * #wire, by calling attachListener on the very same fields -- purely local,
         * never crossing the wire. The button cannot tell which happened to it, and neither can the
         * stylesheet.
         */
        UIText demoTitle = new UIText("Protocol demo");
        demoTitle.addClass(MachineStyles.TITLE_CLASS);
        append(demoTitle);

        UIText demoHint = new UIText(
                "Press one. The result appears at the bottom, and every step is in the game log.");
        demoHint.addClass(MachineStyles.HINT_CLASS);
        append(demoHint);

        append(demoEntry(pingClient, KIND_REQUEST, "server asks client",
                "machine/clientInfo",
                "The server asks who is drawing this. The client answers; the reply shows below."));

        append(demoEntry(announce, KIND_NOTIFY, "server tells client",
                "machine/announce",
                "The server sends a message. Nothing comes back, and nothing is waiting for one."));

        append(demoEntry(askStats, KIND_REQUEST, "client asks server",
                "machine/stats",
                "The client asks for the cycle and heartbeat counts. The server answers."));

        append(demoEntry(heartbeat, KIND_NOTIFY, "client tells server",
                "machine/heartbeat",
                "The client reports in. The server counts it and replies with nothing."));

        append(demoEntry(badRename, KIND_REFUSED, "client asks server",
                "machine/rename",
                "Asks for a blank name. The server REFUSES with the code EMPTY_NAME -- which is a "
                        + "normal answer, not an error and not a timeout."));

        UIText wireLabel = new UIText("Result");
        wireLabel.addClass(MachineStyles.LABEL_CLASS);
        append(wireLabel);

        /*
         * TWO LINES, ONE PER SIDE, AND EACH HAS EXACTLY ONE AUTHOR.
         *
         * This was one line with a badge naming whoever wrote it last, and it produced a genuinely
         * wrong readout: "[CLIENT] NOTIFY sent to the client", which is the CLIENT badge above the
         * SERVER's sentence. Worth understanding, because the mechanism is a property of the engine
         * rather than a slip.
         *
         *   1. The client presses Announce. The server's handler sends the notification and then
         *      writes the readout: badge = "SERVER", text = "NOTIFY sent to the client ...".
         *   2. Property.set RETURNS EARLY ON AN EQUAL VALUE. The badge already said "SERVER" from an
         *      earlier message, so that write marked NOTHING dirty and never entered the delta. The
         *      text had changed, so it did.
         *   3. Meanwhile the client's own machine/announce handler had already written badge =
         *      "CLIENT" locally -- it runs first, because the notification was sent before the delta.
         *   4. The delta lands carrying only the text. The badge stays "CLIENT".
         *
         * The rule underneath it: A STATE DELTA CARRIES WHAT CHANGED ON THE SERVER, NEVER WHAT
         * DIFFERS BETWEEN THE TWO SIDES. A client that writes locally into a server-owned widget has
         * desynchronised it, and the server cannot put it right, because from where the server is
         * standing nothing happened. The idempotent-setter property that makes pushModelIntoPanel
         * free is the same property that makes this unrecoverable.
         *
         * So the fix is not a smarter badge. It is one author per element -- after which each badge
         * is a fixed label nothing ever rewrites, and the whole class of bug is unreachable. The
         * bonus is that BOTH HALVES OF EVERY EXCHANGE ARE NOW ON SCREEN AT ONCE: press Heartbeat and
         * the client line says it sent one while the server line says it received one.
         */
        append(MachineRows.authored(MachineStyles.WHO_SERVER_CLASS, "SERVER", serverLine));

        append(MachineRows.authored(MachineStyles.WHO_CLIENT_CLASS, "CLIENT", clientLine));

        // THE COLLECTIONS, which are the one thing a description cannot carry: two hundred slots and a
        // growing log are streams, and the workspace column is not on this wire at all. @see StreamsPanel
        //
        // BUILT HERE, like the engine panel and for the same reason: a nested panel needs its slice of
        // the model and only this layout knows which slice that is. Over the WHOLE model here, because
        // an inventory and a log are the machine's rather than some sub-part's.
        streams = StreamsPanel.TYPE.build(model);
        append(streams);
    }

    // ── The three badges an entry can carry ─────────────────────────────────

    /** An answer is coming back, and the caller can be told it failed. */
    private static final String[] KIND_REQUEST = { "REQUEST", MachineStyles.KIND_REQUEST_CLASS };

    /** Nothing comes back. A notification that could fail visibly would be a request. */
    private static final String[] KIND_NOTIFY = { "NOTIFY", MachineStyles.KIND_NOTIFY_CLASS };

    /** A request whose answer is "no" — still an ordinary answer, on the ordinary path. */
    private static final String[] KIND_REFUSED = { "REFUSED", MachineStyles.KIND_REFUSED_CLASS };

    /**
     * One self-explaining demo entry.
     *
     * <p>Two lines. The first is the button beside its badge, direction and wire method; the second
     * is a sentence saying what pressing it will do. Everything a reader needs is on screen, which is
     * the whole difference between this and the version it replaced.</p>
     */
    private static UIElement demoEntry(Button button, String[] kind, String direction,
                                       String method, String outcome) {
        UIElement entry = new UIElement();
        entry.addClass(MachineStyles.DEMO_CLASS);

        UIElement head = new UIElement();
        head.addClass(MachineStyles.ROW_CLASS);
        head.append(button);

        /*
         * neverSelfSizeWidth() ON BOTH, and without it the columns do not line up.
         *
         * A UIText measures itself after layout and pushes the result back as its width at IMPORTANT
         * origin -- which outranks any stylesheet rule at any specificity, so `width: 62px` in the
         * sheet would not be overridden, it would never apply. These two are sized by their BOX and
         * their text is incidental, which is exactly what this call is for; the sentence below them
         * is the opposite and is left to size itself.
         */
        UIText badge = new UIText(kind[0]);
        badge.addClass(MachineStyles.KIND_CLASS);
        badge.addClass(kind[1]);
        head.append(badge);

        UIText where = new UIText(direction);
        where.addClass(MachineStyles.DIRECTION_CLASS);
        head.append(where);

        /*
         * forceSelfSizeWidth(), and it is not optional: without it this measured ZERO and the method
         * name -- the one thing tying the panel to the code and the log -- rendered as nothing at all.
         *
         * It is the last item in a flex ROW with no width rule of its own, so the auto-detect read a
         * box of zero and latched "sized by its box". The sentence below it self-sizes correctly with
         * no help because it is in a COLUMN, which is what makes this a one-element problem rather
         * than an obvious one. Same trap AGENTS.md records for the Blackboard's type column.
         */
        UIText wireName = new UIText(method);
        wireName.addClass(MachineStyles.METHOD_CLASS);
        head.append(wireName);

        entry.append(head);

        UIText what = new UIText(outcome);
        what.addClass(MachineStyles.OUTCOME_CLASS);
        entry.append(what);

        return entry;
    }

    // ── The SERVER half ─────────────────────────────────────────────────────

    /**
     * Everything this panel does on the server. Once, before the client is told anything.
     *
     * <p>May freely name {@link MachineModel} — this is a method body, and a method body resolves
     * lazily. A <em>field</em> of that type would not, which is the one rule that lets both halves
     * live in one class. @see Networked</p>
     *
     * <p>Every lambda below runs on the <b>server thread</b>, because that is the thread that drained
     * the connection. That is the whole reason they may touch the model at all. Watch the console when
     * you flip the switch in game: the CLIENT line is on the client thread and the SERVER line that
     * follows it is on the server thread, from one gesture.</p>
     */
    @Override
    public void serve(MachineModel model, ServerScope io) {
        /*
         * NAMED, AND OFFERED. The ref is a content hash the client may already hold; the CSS beside it
         * is what a client that does not can fetch with ui/sheet.
         */
        io.sheet(MachineStyles.SHEET, MachineStyles.CSS);

        io.on(power, Switch.TOGGLE, (ctx, on) -> {
            MachineTrace.log(MachineTrace.SERVER, "event: power -> " + on);
            model.setRunning(on);
        });

        io.on(throughput, Slider.VALUE_CHANGED, (ctx, value) -> {
            MachineTrace.log(MachineTrace.SERVER, String.format("event: throughput -> %.2f", value));
            model.setThroughput(value);
        });

        io.on(label, TextField.TEXT_CHANGED, (ctx, typed) -> {
            MachineTrace.log(MachineTrace.SERVER, "event: label -> '" + typed + "'");
            model.setLabel(typed);
        });

        io.on(purge, Button.ACTIVATE, ctx -> {
            MachineTrace.log(MachineTrace.SERVER, "event: purge pressed");
            model.purge();
        });

        /*
         * ── S -> C REQUEST ────────────────────────────────────────────────────────────────────
         *
         * Two lambdas, never one nullable result: "the client renders with example-client" and "the
         * client never answered" are different facts, and a UI that shows them the same way is lying
         * about one of them. The answer lands on the SERVER thread some ticks later, so writing it
         * into a widget is an ordinary state change and travels back as an ordinary state delta.
         */
        io.on(pingClient, Button.ACTIVATE, ctx -> {
            MachineTrace.log(MachineTrace.SERVER, "-> asking the client machine/clientInfo");
            // WRITTEN BEFORE THE CALL, and this is the point of the readout: a request has a gap
            // between asking and knowing, and a notification does not.
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
         */
        io.on(announce, Button.ACTIVATE, ctx -> {
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
         * On the SCOPE, so it is window-scoped. On the connection it would be keyed by method name
         * alone, and a second Machine window on one wire would throw at open.
         */
        io.onNotify("machine/heartbeat", payload -> {
            heartbeats++;
            MachineTrace.log(MachineTrace.SERVER,
                    "<- heartbeat #" + heartbeats + " from " + payload.getString("from", "?"));
            say("NOTIFY received - heartbeat #" + heartbeats
                    + " from the client, nothing sent back");
        });

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
         * FAIL WITH A CODE, NEVER A MESSAGE. A client branches on a value; it cannot branch on prose,
         * and matching on message text is a coupling nobody remembers making. respond.fail is not an
         * exception: it is a normal answer that happens to say no, on the same envelope as a success.
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

        /*
         * ── THE NESTED PANEL'S SERVER HALF ────────────────────────────────────────────────────
         *
         * PROPS DOWN, EVENTS UP, and neither of them is a message.
         *
         * attach() hands the child its SLICE -- model.engine(), not model -- and a scope prefixed by
         * the child's element id, so the "tune" it registers is "engine/tune" on the wire. The child
         * is then ticked with that slice after this panel's own tick and told closed() when the window
         * ends, both by the host. Nothing else here has to remember it exists.
         *
         * The callback is the other direction and it is an ORDINARY JAVA CALL. Both server halves are
         * objects in one process on one thread; routing this through the session would be a round trip
         * to the room we are standing in, and would invent a wire contract no client ever sees.
         */
        engine.onRestarted(() -> say("the engine panel restarted the engine - a plain Java callback, "
                + "not a message: both halves are in this process"));
        io.attach(engine, model.engine());

        // THE SAME MOVE for the collections, over the WHOLE model rather than a slice: an inventory and
        // a log are the machine's, not some sub-part's, so there is nothing narrower to hand over.
        io.attach(streams, model);

        /*
         * THE MODEL, STATED ONCE.
         *
         * This used to be a mirror(model) method writing all five widgets, called from tick() behind a
         * dirty flag the panel kept itself -- three moving parts, of which the flag was the one the
         * engine's own rule says you should not need, and the method was the one you could forget to
         * add a field to. A forgotten field is invisible: the widget keeps its opening value, which is
         * right, and simply never moves again.
         *
         * The two explicit ones come FIRST, because autoProject leaves alone anything already named.
         */

        // No accessor is called `power` -- the model says isRunning() -- and a convention that reached
        // for isPower() would be inventing one. Named, so the report does not list it as a gap.
        io.project(power, Switch.CHECKED, model::isRunning);

        // Composed of two fields and belongs to no single accessor, which is the ordinary case for a
        // readout. A lambda is the whole mechanism; nesting would work the same way.
        io.project(status, () -> (model.isRunning() ? "running" : "stopped")
                + " - " + model.completedCycles() + " cycles", UIText::setText);

        // throughput, label and progress: field name meets accessor name, and each widget's contract
        // declares which slot it means. Everything it cannot wire -- the buttons, the nested engine
        // panel -- is logged with the reason, because a silent skip is the failure being removed here.
        io.autoProject(model);
    }

    /**
     * Writes the SERVER's result line. <b>Nothing else in the process may write it.</b>
     *
     * <p>That exclusivity is the whole design, not tidiness — see the comment where the two lines are
     * built. Two authors on one element produced a readout with the client's badge above the server's
     * sentence, and no amount of care at the call sites could have prevented it.</p>
     */
    private void say(String message) {
        serverLine.setText(message);
    }

    // ── The CLIENT half ─────────────────────────────────────────────────────


    /**
     * Opens and closes the engine section — <b>and this is the whole of it, on the client, in
     * three lines that send nothing</b>.
     *
     * <p>Whether a section is expanded is <b>view state</b>, the same category as a scroll position or
     * a selection, and this codebase draws that line everywhere else already: document state goes
     * through an edit, view state is mutated directly. Sending it would make the server the authority
     * on something it cannot possibly have an opinion about — and would mean two players sharing one
     * machine could fold each other's panels.</p>
     *
     * <p>Done with a <b>class</b> rather than by writing {@code display} from Java, for the reason the
     * whole example keeps repeating: a value written in Java lands at {@code INLINE} origin and no
     * stylesheet rule could ever move it again. {@code machine.css} owns the hiding; this owns the
     * state. And it is a class rather than a pseudo-class because the engine re-evaluates a
     * pseudo-class on its own terms and a class on yours.</p>
     *
     * <p>The one honest cost: a re-describe rebuilds the tree, so the section comes back closed. That
     * is what "view state" means, and it is the same thing that happens to a scroll position.</p>
     */
    private void toggleEngine() {
        boolean opening = !engine.hasClass(MachineStyles.ENGINE_OPEN_CLASS);
        if (opening) engine.addClass(MachineStyles.ENGINE_OPEN_CLASS);
        else engine.removeClass(MachineStyles.ENGINE_OPEN_CLASS);
        showEngine.setText(opening ? HIDE_ENGINE : SHOW_ENGINE);
    }

    /**
     * What this client answers on the wire. <b>Once</b>, at mount.
     *
     * <p>Separate from {@link #bound()} because a session registration is keyed by method and
     * survives a re-describe untouched — running it twice would be refused by the router, not merely
     * duplicated.</p>
     */
    @Override
    public void client(ClientScope io) {
        // ── the tree, every bind ────────────────────────────────────────────
        askStats.attachListener(this::requestStats);
        heartbeat.attachListener(this::sendHeartbeat);
        // Deliberately blank -- the server refuses it, which is the only way to watch the error
        // callback fire. See the machine/rename handler above.
        badRename.attachListener(() -> rename("   "));
        showEngine.attachListener(this::toggleEngine);
    

        // ── wire methods ───────────────────────────────────────────────────

        this.io = io;

        /*
         * ── S -> C NOTIFICATION, the receiving half ───────────────────────────────────────────
         *
         * If nothing registered this method, the router would log it ONCE and drop it. Once rather
         * than per message, because the usual cause is a peer one version ahead sending something at
         * frame rate, and a log line per frame buries whatever else is wrong.
         */
        io.onNotify("machine/announce", payload -> {
            String text = payload.getString("text", "");
            MachineTrace.log(MachineTrace.CLIENT, "<- announcement: \"" + text + "\" (after "
                    + payload.getInt("cycles", 0) + " cycles) -- nothing to answer");
            show("NOTIFY received - the server said \"" + text + "\", nothing sent back");
        });

        /*
         * Symmetric with the server's onCall: either peer may call the other, and both use the same
         * request/response envelope. This one is answered from nothing but the client's own state,
         * which is the honest use of the direction.
         */
        io.onCall("machine/clientInfo", (args, respond) -> {
            MachineTrace.log(MachineTrace.CLIENT, "-> answering machine/clientInfo");
            StateMap<Object> out = io.newMap();
            out.putString("renderer", "example-client");
            out.putBool("cachedDescription", io.session().cacheSize() > 0);
            respond.ok(out);
            show("REQUEST answered - the server asked who we are, and we told it");
        });
    }

    /**
     * ── C → S REQUEST ── Asks the server for its numbers.
     *
     * <p>Both callbacks run on the thread that ticked the connection, some frames later. Nothing here
     * blocks, and there is no version of this that could: a round trip is a round trip, and an API
     * that hid that would be lying about where the latency is.</p>
     */
    private void requestStats() {
        MachineTrace.log(MachineTrace.CLIENT, "-> asking the server machine/stats");
        show("REQUEST sent to the server - waiting for an answer...");
        io.call("machine/stats", null,
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
     * visibly would be a request.</b></p>
     */
    private void sendHeartbeat() {
        StateMap<Object> out = io.newMap();
        out.putString("from", "example-client");
        MachineTrace.log(MachineTrace.CLIENT, "-> notifying machine/heartbeat (no answer expected)");
        io.notify("machine/heartbeat", out);
        // No "waiting" line: nothing is coming. The server's own readout will replace this one a tick
        // later, which is itself worth watching -- that line is the AUTHORITATIVE one.
        show("NOTIFY sent to the server - nothing will come back");
    }

    /**
     * ── C → S REQUEST THAT FAILS ── Asks the server to rename the machine.
     *
     * <p>Call it with a blank name and the server answers {@code EMPTY_NAME}. That is an ordinary
     * answer that happens to say no — same envelope as a success, same thread, same latency — not an
     * exception and not a timeout. The distinction matters because a UI must tell "refused" apart from
     * "never came back", and only one of those is worth retrying.</p>
     */
    private void rename(String name) {
        StateMap<Object> args = io.newMap();
        args.putString("name", name);
        MachineTrace.log(MachineTrace.CLIENT, "-> asking the server machine/rename('" + name + "')");
        show("REQUEST sent to the server - waiting for an answer...");
        io.call("machine/rename", args,
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

    /**
     * Writes the CLIENT's result line — <b>and only ever that one</b>.
     *
     * <p>The <em>other</em> line belongs to the server: it is in the description, the server pushes it
     * through {@code ui/stateDelta}, and the next delta touching it overwrites whatever this wrote. So
     * a client-side write to a server-owned widget is a <em>preview</em>, not a fact — which is why the
     * two sides have a line each rather than sharing one.</p>
     *
     * <p>Note that this instance's {@code clientLine} and the server's are <b>different objects over
     * different trees</b>, which is the architecture in miniature: one class, one set of field names,
     * and no object shared between the halves.</p>
     */
    private void show(String text) {
        clientLine.setText(text);
    }

    @Override
    public void closed(CloseReason reason) {
        /*
         * ONE METHOD, BOTH SIDES. The server's own close and the client hearing about one arrive
         * here, on two different instances -- and `io` is the tell, because only the client half was
         * ever handed a scope. Worth knowing before writing anything side-specific in a panel: the
         * hook is not split per side, because most panels genuinely want the same teardown twice.
         */
        MachineTrace.log(io == null ? MachineTrace.SERVER : MachineTrace.CLIENT,
                "window closed: " + reason);
    }
}
