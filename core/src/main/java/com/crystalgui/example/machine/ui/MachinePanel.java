package com.crystalgui.example.machine.ui;

import com.crystalgui.example.machine.session.MachineWindow;
import com.crystalgui.net.window.WindowType;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.ProgressBar;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.Switch;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;

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
 * <h3>Why the fields are public and final</h3>
 *
 * <p>{@link MachineWindow} needs handles on individual widgets to attach behaviour and to write
 * state into. Hunting them back out with {@code querySelector} would work and would be worse: a
 * typo in a selector is a lookup that finds nothing at runtime, where a field is a compile error.
 * The tree is built once in the constructor and never reshaped, so the handles cannot go stale.</p>
 *
 * <h3>The ids are not decoration</h3>
 *
 * <p>{@code setId} is what {@code #power} in a stylesheet matches. It does <b>not</b> travel as an
 * addressing scheme — see {@link com.crystalgui.net.NetworkIds}, which derives a number for every
 * element from a document-order walk on both sides and sends nothing. The id is for the cascade;
 * the network id is for the protocol; they are unrelated and it is worth not confusing them.</p>
 */
public final class MachinePanel {

    /**
     * <b>What ties the two halves together.</b> Declared here because the panel is the artefact both
     * sides genuinely share — and because every reference in this initialiser points at this class,
     * which is what keeps it loadable on a dedicated server.
     *
     * <p>The server's window answers with it, and the client registers against it. Being a value
     * rather than a string, a mismatched pair is a compile error instead of a window that opens
     * perfectly and silently has no behaviour. @see WindowType</p>
     */
    public static final WindowType<MachinePanel> TYPE =
            WindowType.of("crystalgui:machine", MachinePanel::bindTo);

    /*
     * THE IDS, WRITTEN ONCE. The build path stamps them on; the bind path looks them up. Two copies of
     * a string that must agree is exactly the failure WindowType exists to remove one level up, and it
     * would be odd to remove it there and reintroduce it here.
     */
    private static final String ID_POWER = "power";
    private static final String ID_THROUGHPUT = "throughput";
    private static final String ID_LABEL = "label";
    private static final String ID_PROGRESS = "progress";
    private static final String ID_STATUS = "status";
    private static final String ID_PURGE = "purge";
    private static final String ID_PING_CLIENT = "ping-client";
    private static final String ID_ANNOUNCE = "announce";
    private static final String ID_ASK_STATS = "ask-stats";
    private static final String ID_HEARTBEAT = "heartbeat";
    private static final String ID_BAD_RENAME = "bad-rename";
    private static final String ID_RESULT_SERVER = "result-server";
    private static final String ID_RESULT_CLIENT = "result-client";

    /** The root the description is taken from, and the root the client rebuilds. */
    public final UIElement root;

    /** On/off. Reports {@code toggle}. */
    public final Switch power;

    /** 0..1 throughput. Reports {@code value}. */
    public final Slider throughput;

    /** The machine's name. Reports {@code text}. */
    public final TextField label;

    /** Cycle progress. Server-driven only — the client never writes to it. */
    public final ProgressBar progress;

    /** Abandons the cycle. Reports {@code activate}. */
    public final Button purge;

    /** A line of server-written text. Also server-driven only. */
    public final UIText status;

    /** The last thing the SERVER did. Nothing else may write it. */
    public final UIText serverLine;

    /** The last thing the CLIENT did. Nothing else may write it. */
    public final UIText clientLine;

    // ── The four protocol directions, one button each. See MachineWindow's table. ──

    /** SERVER-side handler → a REQUEST to the client, answered. */
    public final Button pingClient;

    /** SERVER-side handler → a NOTIFICATION to the client, unanswered. */
    public final Button announce;

    /** CLIENT-side listener → a REQUEST to the server, answered. */
    public final Button askStats;

    /** CLIENT-side listener → a NOTIFICATION to the server, unanswered. */
    public final Button heartbeat;

    /** CLIENT-side listener → a REQUEST the server REFUSES, so the error path is visible. */
    public final Button badRename;

    public MachinePanel() {
        root = new UIElement();
        root.addClass(MachineStyles.PANEL_CLASS);

        UIText title = new UIText("Machine control");
        title.addClass(MachineStyles.TITLE_CLASS);
        root.addChild(title);

        power = new Switch();
        power.setId(ID_POWER);
        root.addChild(row("Power", power));

        throughput = new Slider();
        throughput.setRange(0f, 1f);
        throughput.setId(ID_THROUGHPUT);
        root.addChild(row("Throughput", throughput));

        label = new TextField();
        label.setPlaceholder("name this machine");
        label.setId(ID_LABEL);
        root.addChild(row("Label", label));

        progress = new ProgressBar();
        progress.setId(ID_PROGRESS);
        root.addChild(row("Cycle", progress));

        status = new UIText("");
        status.setId(ID_STATUS);
        status.addClass(MachineStyles.STATUS_CLASS);
        root.addChild(status);

        purge = new Button("Purge");
        purge.setId(ID_PURGE);
        root.addChild(purge);

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
         * MachineClient, by finding them in this tree and calling attachListener -- purely local,
         * never crossing the wire. The button cannot tell which happened to it, and neither can the
         * stylesheet.
         */
        UIText demoTitle = new UIText("Protocol demo");
        demoTitle.addClass(MachineStyles.TITLE_CLASS);
        root.addChild(demoTitle);

        UIText demoHint = new UIText(
                "Press one. The result appears at the bottom, and every step is in the game log.");
        demoHint.addClass(MachineStyles.HINT_CLASS);
        root.addChild(demoHint);

        pingClient = new Button("Ping client");
        pingClient.setId(ID_PING_CLIENT);
        root.addChild(demoEntry(pingClient, KIND_REQUEST, "server asks client",
                "machine/clientInfo",
                "The server asks who is drawing this. The client answers; the reply shows below."));

        announce = new Button("Announce");
        announce.setId(ID_ANNOUNCE);
        root.addChild(demoEntry(announce, KIND_NOTIFY, "server tells client",
                "machine/announce",
                "The server sends a message. Nothing comes back, and nothing is waiting for one."));

        askStats = new Button("Ask stats");
        askStats.setId(ID_ASK_STATS);
        root.addChild(demoEntry(askStats, KIND_REQUEST, "client asks server",
                "machine/stats",
                "The client asks for the cycle and heartbeat counts. The server answers."));

        heartbeat = new Button("Heartbeat");
        heartbeat.setId(ID_HEARTBEAT);
        root.addChild(demoEntry(heartbeat, KIND_NOTIFY, "client tells server",
                "machine/heartbeat",
                "The client reports in. The server counts it and replies with nothing."));

        badRename = new Button("Rename to ''");
        badRename.setId(ID_BAD_RENAME);
        root.addChild(demoEntry(badRename, KIND_REFUSED, "client asks server",
                "machine/rename",
                "Asks for a blank name. The server REFUSES with the code EMPTY_NAME -- which is a "
                        + "normal answer, not an error and not a timeout."));

        UIText wireLabel = new UIText("Result");
        wireLabel.addClass(MachineStyles.LABEL_CLASS);
        root.addChild(wireLabel);

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
        serverLine = new UIText("nothing yet");
        serverLine.setId(ID_RESULT_SERVER);
        root.addChild(resultRow(MachineStyles.WHO_SERVER_CLASS, "SERVER", serverLine));

        clientLine = new UIText("nothing yet");
        clientLine.setId(ID_RESULT_CLIENT);
        root.addChild(resultRow(MachineStyles.WHO_CLIENT_CLASS, "CLIENT", clientLine));
    }

    /** A fixed side badge and the line only that side writes. */
    /**
     * Typed hold of a tree a <b>client</b> rebuilt from this panel's description.
     *
     * <p>The client has no {@code MachinePanel} and cannot have one: its tree is decoded from a
     * description that carries tags, not classes, which is exactly what lets an old client draw a new
     * panel. What this produces is a <b>binding</b> — the same class, over the rebuilt tree, with the
     * same field names. Android's View Binding and JavaFX's {@code @FXML} injection solve the same
     * problem the same way.</p>
     *
     * <p>So the two panels are different instances over different trees. A client-side
     * {@code panel.power.setChecked(…)} is a local write that the next state delta overwrites — the
     * preview-not-a-fact rule, unchanged.</p>
     *
     * <p><b>{@code require} rather than {@code find}</b>, because every part of this panel is in every
     * description of it. {@code find} is for a part that may be absent — a widget a newer server added
     * that this client has never heard of — and using it here would trade a loud failure for the
     * silent skip the binding exists to remove.</p>
     */
    public static MachinePanel bindTo(UIElement rebuilt) {
        return new MachinePanel(rebuilt);
    }

    /** @see #bindTo */
    private MachinePanel(UIElement rebuilt) {
        root = rebuilt;
        power = rebuilt.require("#" + ID_POWER, Switch.class);
        throughput = rebuilt.require("#" + ID_THROUGHPUT, Slider.class);
        label = rebuilt.require("#" + ID_LABEL, TextField.class);
        progress = rebuilt.require("#" + ID_PROGRESS, ProgressBar.class);
        status = rebuilt.require("#" + ID_STATUS, UIText.class);
        purge = rebuilt.require("#" + ID_PURGE, Button.class);
        pingClient = rebuilt.require("#" + ID_PING_CLIENT, Button.class);
        announce = rebuilt.require("#" + ID_ANNOUNCE, Button.class);
        askStats = rebuilt.require("#" + ID_ASK_STATS, Button.class);
        heartbeat = rebuilt.require("#" + ID_HEARTBEAT, Button.class);
        badRename = rebuilt.require("#" + ID_BAD_RENAME, Button.class);
        serverLine = rebuilt.require("#" + ID_RESULT_SERVER, UIText.class);
        clientLine = rebuilt.require("#" + ID_RESULT_CLIENT, UIText.class);
    }

    private static UIElement resultRow(String badgeClass, String side, UIText line) {
        UIElement row = new UIElement();
        row.addClass(MachineStyles.ROW_CLASS);

        UIText badge = new UIText(side);
        badge.addClass(MachineStyles.KIND_CLASS);
        badge.addClass(badgeClass);
        badge.neverSelfSizeWidth();
        row.addChild(badge);

        // neverSelfSizeWidth for the opposite reason to the method names above: this is in a ROW and
        // its text is long, so sizing itself would push the row past the panel edge. Sized by the
        // sheet, it wraps inside its box.
        line.addClass(MachineStyles.WIRE_CLASS);
        line.neverSelfSizeWidth();
        row.addChild(line);

        return row;
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
        head.addChild(button);

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
        badge.neverSelfSizeWidth();
        head.addChild(badge);

        UIText where = new UIText(direction);
        where.addClass(MachineStyles.DIRECTION_CLASS);
        where.neverSelfSizeWidth();
        head.addChild(where);

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
        wireName.forceSelfSizeWidth();
        head.addChild(wireName);

        entry.addChild(head);

        UIText what = new UIText(outcome);
        what.addClass(MachineStyles.OUTCOME_CLASS);
        entry.addChild(what);

        return entry;
    }

    /**
     * A label beside a control.
     *
     * <p>The fixed-width slot is the one pixel value in this class, and it is here because
     * {@link UIText} measures itself after layout and writes its own width back at {@code IMPORTANT}
     * origin — so a stylesheet {@code width} on the text loses to the text. Wrapping it in a sized
     * box is the standing idiom for keeping a column of labels aligned; every harness scene in the
     * repository does the same thing for the same reason.</p>
     */
    private static UIElement row(String caption, UIElement control) {
        UIElement row = new UIElement();
        row.addClass(MachineStyles.ROW_CLASS);

        UIElement slot = new UIElement().layout(l -> l.width(90));
        UIText text = new UIText(caption);
        text.addClass(MachineStyles.LABEL_CLASS);
        slot.addChild(text);

        row.addChild(slot);
        row.addChild(control);
        return row;
    }
}
