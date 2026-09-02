package com.crystalgui.language.run.view;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.run.RunSessions;
import com.crystalgui.language.run.RunState;
import com.crystalgui.language.run.ScriptCommands;
import com.crystalgui.language.run.console.ConsoleFilter;
import com.crystalgui.language.run.console.ConsoleSettings;
import com.crystalgui.language.run.console.RunConsole;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;

import javax.annotation.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * What the running scripts have printed — a toolbar over a read-only text area.
 *
 * <h3>This was a list, and the list was wrong</h3>
 *
 * <p>A row-based list cannot express the selection an IDE console has: from the middle of one line to
 * the middle of another, ten rows down, copied exactly. Its selection unit is the row. So the transcript
 * is a {@link RunConsoleView} — a configured {@code TextEditor} — and everything the list version had to
 * invent (per-row hover, a navigable-row class, a repeat badge) either comes free or was only ever
 * compensating for rows.</p>
 *
 * <p>What survives unchanged is the toolbar and the eviction notice, because neither was about rows.</p>
 */
public final class RunPanel extends UINode  {

    /**
     * This panel's kind, which 49 rules in {@code ua/panels.css} are written against.
     *
     * <p>The old engine derived one: {@code tagName()} fell back to the class's lowercased simple
     * name, so a class that registered nothing still answered {@code runpanel} and its rules matched.
     * This engine has no fallback — a kind is a {@code NAME} declared on the class and INHERITED when
     * absent — so without this the panel answered {@code crystalgui:element} and every one of those
     * rules matched nothing. The panel drew, laid out and worked, unstyled, which is why it reads as
     * a missing stylesheet rather than a missing constant.</p>
     */
    public static final Name NAME = Name.of("runpanel");

    public static final String NOTICE_CLASS = "__run-notice__";
    public static final String EMPTY_CLASS = "__run-empty__";
    /** The block inside it — see {@link #buildEmptyState} for why the note needs two containers. */
    public static final String EMPTY_LINES_CLASS = "__run-empty-lines__";
    public static final String EMPTY_HEAD_CLASS = "__run-empty-head__";
    public static final String EMPTY_LINE_CLASS = "__run-empty-line__";
    public static final String INPUT_CLASS = "__run-input__";
    public static final String BODY_CLASS = "__run-body__";

    public static final String STRIPE_CLASS = "__run-stripe__";
    public static final String LEFT_CLASS = "__run-left__";
    public static final String RUNBAR_CLASS = "__run-bar__";
    public static final String SEP_CLASS = "__run-sep__";
    public static final String RERUN_CLASS = "__run-rerun__";
    public static final String ACTION_CLASS = "__run-action__";
    public static final String STOP_CLASS = "__run-stop__";
    public static final String WRAP_CLASS = "__run-wrap__";
    public static final String END_CLASS = "__run-end__";
    /** On the soft-wrap button while wrapping is on. A CLASS, not a pseudo-class: the widget flips it. */
    public static final String ON_CLASS = "__on__";
    public static final String CLEAR_CLASS = "__run-clear__";

    /** Fired when a navigable span is clicked — the line it was on, and the span. @see ConsoleFilter */
    public final Signal.Pair<RunConsole.Line, ConsoleFilter.Link> onLinkActivated = new Signal.Pair<>();

    /**
     * Asked to clear, and asked to stop.
     *
     * <p>Signals rather than the panel doing either itself, because it is a view over a
     * {@link RunConsole} and knows nothing about a {@code ScriptRuntime}. Clearing could arguably live here;
     * stopping certainly could not, and having the two work differently would be worse than routing both
     * the same way.</p>
     */
    public final Signal.Action onClearRequested = new Signal.Action();

    /**
     * Asked to stop a script — the one the button was offering, or null for "whatever is running".
     *
     * <p><b>Carries a subject even though nothing needs one yet</b>, and that is the point. {@code
     * ScriptRuntime} holds exactly one live run, so today the answer is never ambiguous — but
     * {@link RunSessions} is a map, the rail lists several rows, and {@link RunState#LIVE} exists
     * precisely so that more than one script can be alive at once. The day that lands, a payload-free
     * signal does not fail: it stops the wrong script, silently, and every call site already written
     * keeps compiling. One signature now is the cheapest this ever gets.</p>
     */
    public final Signal.Value<Resource> onStopRequested = new Signal.Value<>();

    /**
     * Asked to run one script again.
     *
     * <p>Carries the script, because unlike Stop this one has a <em>subject</em>: stopping is a question
     * about whatever is currently running, and there is only ever one answer to that. Re-running is a
     * question about a particular file.</p>
     */
    public final Signal.Value<Resource> onRerunRequested = new Signal.Value<>();

    /**
     * Asked to forget one script entirely — its rail row and everything it printed.
     *
     * <p>A signal rather than the panel doing it, for the reason Clear is one: the panel is a view over a
     * {@code RunConsole} and a {@code RunSessions}, and which of them a "remove" touches is the
     * application's decision. Here it is both, and {@code RunPanels} is where both are in scope.</p>
     */
    public final Signal.Value<Resource> onRemoveRequested = new Signal.Value<>();

    // NO onFilterRequested SIGNAL. It existed for a host that wanted to know which script was being
    // shown -- "a rail highlighting the same script" was the case named -- and the rail turned out to be
    // the thing that RAISES the filter rather than something that follows it, so nothing ever listened.
    // An announcement with no audience is not free: it is a public signal that reads as a supported
    // integration point, so the next person wires to it and finds it fires only for gestures the rail
    // happens to make. `RunConsole.filter()` answers the same question at the moment it is asked.

    private final RunConsoleView view = new RunConsoleView();
    private final UIText notice = new UIText("");
    private final UINode stripe = new UINode();
    /**
     * The rail and its own toolbar, as one column.
     *
     * <p>IntelliJ puts the RUN controls in a horizontal bar above the tree and the CONSOLE controls in a
     * vertical stripe beside the output, and the split is not decorative: rerun and stop act on a
     * <em>script</em>, which is what the rail lists, while wrap and scroll act on the <em>transcript</em>.
     * Each toolbar sits with the thing it operates on.</p>
     */
    private final UINode leftColumn = new UINode();
    private final UINode runBar = new UINode();
    /**
     * The rule under the run bar.
     *
     * <p>An ELEMENT, because a border cannot draw one here: the paint path takes {@code border().left} as
     * its stroke width and strokes a uniform box, so a bottom-only hairline resolves, lays out, and draws
     * nothing at all. The find bar spent a session on exactly this, and {@code statusbarview} spells its
     * separators the same way.</p>
     */
    private final UINode separator = new UINode();

    /**
     * The row under the toolbar: the rail, the transcript, and the console's control stripe.
     *
     * <p>The rail REPLACED the head's dropdown rather than joining it — both answer the same question,
     * which script's output am I looking at, and two controls for one question is the arrangement where
     * they drift apart.</p>
     */
    private final UINode body = new UINode();
    private final RunRail rail = new RunRail();
    private boolean railShown;

    /**
     * The rail beside the transcript, with a draggable divider.
     *
     * <p><b>Built lazily, and it has to be.</b> A {@code SplitView} cannot go below two panes —
     * {@code removePane} refuses outright — and it cannot collapse one to nothing either: {@code
     * applySplit} writes {@code flex-grow}, which divides only FREE space, and {@code setPaneSizeLimits}
     * clamps dragging rather than layout. So "no rail until something has run" cannot be a pane sized to
     * zero; it has to be the split not existing yet, with the transcript sitting in the body on its own
     * until it does.</p>
     */
    @Nullable private SplitView split;

    /** A quarter of the panel -- the rail is a list of filenames, not the thing you are reading. */
    private static final float DEFAULT_SPLIT = 24f;

    /**
     * Where a line for {@code System.in} is typed.
     *
     * <p>A row of its own rather than the transcript's editable last line: that would need a genuine
     * editable-REGION in {@code TextEditor} — a caret that cannot move above the boundary, a selection
     * that cannot span it, a backspace that stops at it, and a paste and an undo that respect it.
     * {@code setReadOnly} is one flag and none of that exists.</p>
     */
    private final TextField inputField = new TextField();
    private boolean inputShown;

    private final Button stop = new Button("");
    private final Button clear = new Button("");
    private final Button wrap = new Button("");
    private final Button rerun = new Button("");
    private final Button toEnd = new Button("");

    /** What the soft-wrap button is currently showing, so an unchanged state writes no class. */
    private boolean wrapShown;

    /**
     * The same, for the tail-follow button.
     *
     * <p>False rather than true, which is not an arbitrary initial value: it mirrors what is on the
     * ELEMENT, and the element starts with no {@code __on__} class. The lock itself starts armed, so
     * seeding this to match <em>it</em> instead would make the first frame agree that nothing had
     * changed — and the button would sit unlit over a console that was following.</p>
     */
    private boolean followShown;

    @Nullable private RunConsole console;
    /**
     * What the rail lists.
     *
     * <p>Separate from the console because they are different models: the console holds <em>output</em>
     * and knows a script only by the name on a line, while a session is keyed by the {@code Resource} and
     * carries the state and the clock. The panel is the one place both are in scope.</p>
     */
    @Nullable private RunSessions sessions;

    /** The rail's current row, or null for "All output" — what Rerun acts on. @see #refreshActions */
    @Nullable private Resource selected;

    /**
     * The script Stop is currently offering to stop — what its tooltip names and what its press carries.
     *
     * <p>Not {@link #selected}. Stop is a question about what is <em>running</em> and Rerun a question
     * about what is <em>selected</em>, and the two differ constantly: reading one script's output while
     * another is still going is the normal case.</p>
     */
    @Nullable private Resource stopping;
    @Nullable private Connection watch;
    private long noticed = -1;
    private boolean ticking;

    public RunPanel() {
        super(NAME);
        buildHead();
        notice.addClass(NOTICE_CLASS);
        notice.setHitTest(false);

        // NO markAsInternal() ON THIS. It recurses and would make the panel's own selector subject
        // unstyleable -- `runpanel .__run-console__` would match while `runpanel` itself did not, so the
        // panel would have no size and children that looked fine. `addInternalChild` marks each part
        // individually, which is the correct half of that pair.
        inputField.addClass(INPUT_CLASS);
        inputField.setPlaceholder("Type a line and press Enter");
        // SUBMIT CLEARS THE FIELD, always -- even when nothing was waiting. A field that keeps its text
        // after Enter reads as the key not having worked, and the next Enter would send it twice.
        inputField.onSubmit.connect(text -> {
            RunConsole showing = console;
            if (showing != null) showing.submitInput(text);
            inputField.setText("");
        });

        body.addClass(BODY_CLASS);
        leftColumn.addClass(LEFT_CLASS);
        leftColumn.append(rail);

        body.append(view.element());
        // THE STRIPE AND THE RUN BAR ARE NOT ATTACHED YET, and neither is anything else that acts on a
        // run -- nothing has run. @see #showControls
        buildEmptyState();
        body.append(emptyNote);
        rail.onScriptChosen.connect(script -> {
            selected = script;
            RunConsole showing = console;
            // THE NAME, because RunConsole filters on the script name a message carries -- a Resource is
            // what the rail holds and what a session is keyed by, and the two meet here rather than one
            // of them having to know about the other.
            emitFilter(script == null ? null : script.name());
        });

        // THE STRIP DIRECTLY UNDER THE PANEL'S HEADER, spanning its whole width and carrying a separator
        // of its own -- which is where IntelliJ's run controls are, and one level shallower than the first
        // attempt put them. Inside the rail's column they were indented behind a second boundary and read
        // as belonging to the list rather than to the run.
        runBar.addClass(RUNBAR_CLASS);
        separator.addClass(SEP_CLASS);
        separator.setHitTest(false);
        append(body);
        // THE NOTICE IS NOT ATTACHED YET. An empty UIText still measures a full LINE of height, so a
        // permanently-attached one puts a blank band under the toolbar on every console that has never
        // evicted anything -- which is every console, nearly always. Attached by refreshNotice when it has
        // something to say. The same reason the input row and the rail attach rather than hide.
        // NOT ADDED YET. It is attached only while something is blocked on a read -- see refreshInput --
        // because a permanent field would sit under every transcript claiming input is wanted, and would
        // take a tab stop and a `gap-all` slot to say nothing.

        view.onLinkActivated.connect(onLinkActivated::emit);
    }

    /**
     * What the panel says before anything has run.
     *
     * <h4>A blank rectangle is not a neutral answer</h4>
     *
     * <p>This shipped as one: a black area under a toolbar offering Rerun and Stop with nothing to rerun
     * or stop. It reads as broken, which is the worst thing a panel can say to somebody opening it for
     * the first time — and it is the panel most likely to <em>be</em> opened first, since the activity
     * bar has a button for it. IntelliJ centres a note here, VS Code's panels do, and the Problems view
     * in this very application already does ("No problems").</p>
     *
     * <h4>The toolbar goes with it, which IntelliJ's screenshot also shows</h4>
     *
     * <p>Rerun and Stop over nothing are exactly the dead controls this panel refuses everywhere else —
     * see {@link #refreshActions}, which takes three of them out of hit testing rather than leave them
     * lit. The difference here is that there is no <em>state</em> in which they could be live yet, so the
     * honest thing is not a greyed row but no row: the same argument {@code ScriptWorkbench.install}
     * makes for answering null instead of wiring a dead Run command.</p>
     *
     * <h4>Attached and detached, never hidden</h4>
     *
     * <p>A hidden child keeps its place in the Tab sequence, so a hidden run bar would be two invisible
     * tab stops in front of the transcript — the same reason the rail and the input row attach rather
     * than hide.</p>
     */
    private final UINode emptyNote = new UINode();
    private final UINode emptyLines = new UINode();
    private final UIText emptyHeading = new UIText("To run a script, do one of the following:");
    private final UIText emptyRunLine = new UIText("");
    private final UIText emptyPaletteLine =
            new UIText("— Find “Run Script” in the command palette");

    /** What {@link #refreshEmptyState} last wrote, so an unchanged line is not rebuilt every frame. */
    private String emptyRunText = "";

    /**
     * Two containers, because the block is centred and its lines are not.
     *
     * <p>Centring the lines <em>individually</em> — one column with {@code align-items: center} — puts
     * every line's left edge at a different x, so the two dashes do not line up with each other and the
     * heading sits indented between them. IntelliJ's note is a left-aligned block that happens to be
     * centred, which is why its dashes form a column.</p>
     *
     * <p>So the outer element centres, and the inner one shrinks to its widest line and left-aligns
     * inside it. There is no way to say that with one container: {@code align-items} is the cross-axis
     * rule for a container's children, and a child cannot both be centred and align its own children to
     * a shared edge.</p>
     */
    private void buildEmptyState() {
        emptyNote.addClass(EMPTY_CLASS);
        // NOT HIT-TESTABLE, all of it. It is a caption over the console's own surface, and a caption that
        // swallowed a press would make the area behind it dead to a click for no visible reason.
        emptyNote.setHitTest(false);
        emptyLines.addClass(EMPTY_LINES_CLASS);
        emptyLines.setHitTest(false);
        emptyHeading.addClass(EMPTY_HEAD_CLASS);
        emptyRunLine.addClass(EMPTY_LINE_CLASS);
        emptyPaletteLine.addClass(EMPTY_LINE_CLASS);
        emptyLines.append(emptyHeading);
        emptyLines.append(emptyRunLine);
        emptyLines.append(emptyPaletteLine);
        emptyNote.append(emptyLines);
    }

    /** What the empty state says can run — {@code java}, {@code java, javascript}. @see RunPanels */
    private String runnableLanguages = "";

    /** Names the languages that can run here, for the empty state's caption. Empty for "a script". */
    public RunPanel setRunnableLanguages(String languages) {
        this.runnableLanguages = languages == null ? "" : languages.trim();
        refreshEmptyState();
        return this;
    }

    /**
     * Keeps the note's accelerator honest.
     *
     * <p><b>Read from the keymap, never spelled.</b> A literal is a promise the panel cannot keep the
     * moment anything rebinds Run, and it fails in the worst possible place: the one screen whose entire
     * job is telling somebody which key to press. {@link #describeAction} is the same lookup the tooltips
     * use, and an unbound command simply drops the parenthesis rather than printing an empty one.</p>
     */
    private void refreshEmptyState() {
        String what = runnableLanguages.isEmpty() ? "a script" : "a " + runnableLanguages + " file";
        String wanted = "— Open " + what + " and press " + describeAction("Run", null, ScriptCommands.RUN);
        if (wanted.equals(emptyRunText)) return;
        emptyRunText = wanted;
        emptyRunLine.setText(wanted);
    }

    /** Puts the run controls back, once there is a run for them to act on. @see #emptyNote */
    /**
     * Detaches a child whether it is public or internal.
     *
     * <h4>{@code removeChild} silently refuses an internal child, and almost everything here is one</h4>
     *
     * <p>{@code markAsInternal()} <b>recurses</b>, so {@code append(body)} marks not only
     * {@code body} but everything already under it — the transcript, the console column, the note. A
     * later {@code body.remove(note)} then hits {@code if (child.isInternalUI()) return false} and
     * does nothing, <b>returning a boolean nobody was checking</b>.</p>
     *
     * <p>That is exactly how the empty-state note came to be drawn over a live console with a full rail
     * beside it: every other line of {@code showControls} worked, so the toolbar appeared, the stripe
     * appeared, the rail appeared, and the one call that was supposed to take the note away was a no-op.
     * Nothing threw and nothing logged.</p>
     *
     * <p>The engine already knows this shape — {@code addChildAtInternal} reparents with
     * {@code if (!previous.remove(child)) previous.remove(child)}. This is that pair,
     * named, so a caller cannot half-remember it.</p>
     */
    private static void detach(UINode parent, UINode child) {
        if (child.parent() != parent) return;
        if (!parent.remove(child)) parent.remove(child);
    }

    /**
     * Puts the run controls back, once there is a run for them to act on.
     *
     * <p><b>Idempotent, and called every frame rather than on a transition.</b> The transition version
     * had exactly one chance to get it right and no way to notice it had not — which is the other half of
     * the bug above. Guarding on what is actually attached makes a frame that finds the tree wrong
     * correct it, and costs three null checks. {@code tickFrame}'s eviction notice has always worked this
     * way; the empty state simply did not copy it.</p>
     */
    private void showControls() {
        detach(body, emptyNote);
        // THE TRANSCRIPT COMES BACK WITH THEM. `showRail` has usually already moved it into the split's
        // pane by now, in which case it has a parent and this does nothing.
        if (view.element().parent() == null) body.insertAt(0, view.element());
        if (runBar.parent() != null) return;
        insertAt(0, runBar);
        insertAt(1, separator);
        // LAST IN THE BODY, so it sits on the trailing edge. IntelliJ's console keeps its controls in a
        // narrow vertical stripe there rather than in a full-width bar above, and the reason is space: a
        // row across the whole panel spends 22px of height to hold four glyphs, on a panel whose entire
        // job is showing as many lines as it can.
        body.append(stripe);
    }

    /**
     * Takes them away again — reachable, because {@code RunSessions.forget} can empty the list.
     *
     * <p>A workspace that deletes the only script it has ever run goes back to having run nothing, and a
     * panel that kept a Rerun button for a file that no longer exists would be offering to run it.</p>
     */
    private void hideControls() {
        // THE TRANSCRIPT GOES TOO, which is what IntelliJ's empty Run window shows: a note on the tool
        // window's own ground, and no console at all. Leaving it attached is not neutral -- an editor
        // paints its OWN surface, several shades darker than the panel around it, so an empty console
        // reads as a black hole where a panel should be. There is also nothing in it by definition.
        detach(body, view.element());
        if (emptyNote.parent() == null) body.append(emptyNote);
        if (runBar.parent() == null) return;
        detach(body, stripe);
        remove(runBar);
        remove(separator);
    }

    /**
     * The toolbar — Stop and Clear, in that order.
     *
     * <p>Stop first because it is the one with a deadline: it is the only affordance that rescues a
     * runaway script, and both references put it at the leading edge of the run toolbar. Icon-only, like
     * every IDE console toolbar — a label would double the head's height to say what a universally
     * recognised glyph already says.</p>
     */
    private void buildHead() {
        stripe.addClass(STRIPE_CLASS);
        // THE GLYPH COMES FROM THE SHEET. `overlay` takes a drawable rather than `icon(...)` text, and
        // more to the point a widget writing its own artwork is the rule this codebase keeps: structure
        // and state in Java, appearance in CSS.
        stop.addClass(ACTION_CLASS);
        stop.addClass(STOP_CLASS);
        // THE SCRIPT THE BUTTON WAS OFFERING TO STOP -- the same one its tooltip names, kept current by
        // refreshActions. Emitting whatever is selected instead would stop the script you are READING
        // rather than the one that is running, and those differ constantly.
        stop.onPressed.connect(() -> onStopRequested.emit(stopping));

        clear.addClass(ACTION_CLASS);
        clear.addClass(CLEAR_CLASS);
        clear.onPressed.connect(onClearRequested::emit);

        // SOFT WRAP IS A TOGGLE, so its on-ness is a class this widget writes rather than a pseudo-class
        // the engine evaluates -- :checked is re-evaluated on the engine's terms and has cost a round here
        // before. @see RunPanel#ON_CLASS
        wrap.addClass(ACTION_CLASS);
        wrap.addClass(WRAP_CLASS);
        // THE STATE IS NOT WRITTEN HERE. This flips the editor; `refreshActions` reads the editor back
        // and writes the class. Doing both here looks tighter and is what left Alt+Z -- which reaches
        // the same setting through the editor's own keymap -- showing an off button over a wrapped
        // transcript. @see #refreshActions
        wrap.onPressed.connect(() -> view.setSoftWrap(!view.isSoftWrap()));

        toEnd.addClass(ACTION_CLASS);
        toEnd.addClass(END_CLASS);
        toEnd.onPressed.connect(view::scrollToEnd);

        rerun.addClass(ACTION_CLASS);
        rerun.addClass(RERUN_CLASS);
        rerun.onPressed.connect(() -> {
            Resource script = selected;
            if (script != null) onRerunRequested.emit(script);
        });

        runBar.append(rerun);
        runBar.append(stop);

        stripe.append(wrap);
        stripe.append(toEnd);
        stripe.append(clear);

        // ATTACHED ONCE, HERE. `Tooltip.attach` ADDS a listener pair rather than replacing one, so calling
        // it again to update the text leaves the first tooltip attached and showing -- the new text never
        // appears however correct the lookup is. The two that change are retained and re-worded through
        // `setText`; the three that never change are attached and forgotten. `StatusBarView` carries the
        // same note.
        rerunTip = Tooltip.attach(rerun, "Rerun");
        stopTip = Tooltip.attach(stop, "Stop");
        Tooltip.attach(wrap, "Soft-Wrap");
        Tooltip.attach(toEnd, "Scroll to End");
        Tooltip.attach(clear, "Clear All");
    }

    /**
     * The two tooltips that name a script, retained so they can be re-worded rather than re-attached.
     *
     * @see #describeAction
     */
    @Nullable private Tooltip rerunTip;
    @Nullable private Tooltip stopTip;

    /** What the last refresh wrote, so an unchanged tooltip is not rebuilt sixty times a second. */
    private String rerunText = "";
    private String stopText = "";

    /** The buffer size last pushed into the console. @see ConsoleSettings */
    private int budgetApplied = -1;

    /**
     * "Rerun 'Main.java' (Shift+F10)" — the verb, the subject, and what would actually fire it.
     *
     * <p><b>The accelerator is READ FROM THE KEYMAP, never spelled here.</b> A literal is a promise the
     * widget cannot keep the moment anything rebinds the command, and it fails silently: the tooltip goes
     * on confidently naming a key that now does something else. {@code Keymap.acceleratorFor} resolves
     * outward from this element, which is the same lookup the menus already use.</p>
     *
     * <p>Unbound is an ordinary answer rather than an error, so the parenthesis is simply absent — most
     * commands are never bound, and a tooltip reading "Soft-Wrap ()" is worse than one reading
     * "Soft-Wrap".</p>
     */
    private String describeAction(String verb, @Nullable Resource script, String commandId) {
        StringBuilder out = new StringBuilder(verb);
        if (script != null) out.append(" '").append(script.name()).append('\'');
        KeyChord chord = Keymap.acceleratorFor(this, commandId);
        if (chord != null) out.append(" (").append(chord).append(')');
        return out.toString();
    }

    private void emitFilter(@Nullable String script) {
        RunConsole showing = console;
        if (showing != null) showing.setFilter(script);
    }

    /**
     * Shows the rail once anything has run, and advances its clocks.
     *
     * <p><b>One script is enough, and that reverses the first attempt.</b> It hid below two on the
     * argument that a rail listing one thing is a caption — which was written before the row had a clock
     * on it. A single row now says the two things worth knowing: that the script is alive, and for how
     * long. And a control that appears and disappears as scripts come and go is worse than one that is
     * simply there; IntelliJ's run tree is always present for the same reason.</p>
     *
     * <p>Still keyed on <b>seen this session</b> rather than <em>live now</em>, so a finished script keeps
     * its row and its final duration instead of the rail emptying the moment things settle.</p>
     *
     * <p>Attached and detached rather than hidden: a hidden child still counts for the body's layout and
     * would leave a permanent notch beside a console that has never run anything.</p>
     */
    private void refreshRail() {
        RunSessions listing = sessions;
        // ASKED WITHOUT A COPY. `scripts()` snapshots the key set under the lock, and this is a question
        // about emptiness asked once a frame forever. @see RunSessions#isEmpty
        boolean wanted = listing != null && !listing.isEmpty();
        // THE SPLIT IS BUILT ON THE TRANSITION, because building one is not idempotent -- it reparents
        // the transcript and takes a remembered divider position with it.
        if (wanted != railShown) {
            railShown = wanted;
            if (wanted) showRail();
            else hideRail();
        }
        // THE CONTROLS ARE ASKED EVERY FRAME, because these ARE idempotent and the transition version had
        // one chance to be right with no way to notice it was not. @see #showControls
        if (wanted) {
            showControls();
        } else {
            hideControls();
            refreshEmptyState();
        }
        // EVERY FRAME while it is up, because the elapsed time is the liveness signal -- see RunRail for
        // why that stands in for a spinner rather than beside one.
        if (railShown) rail.tick();
        // ONLY WHEN SOMETHING MOVED. `active()` copies under the lock, and the question this answers --
        // has a script started since last frame -- can only change when the version does.
        if (listing != null) {
            int now = listing.version();
            if (now != knownRunVersion) {
                knownRunVersion = now;
                followNewRuns(listing);
            }
        }
    }

    /** When the run this last followed began, so only a NEWER one moves the rail. @see #followNewRuns */
    private long followedAt;
    private boolean followedAny;

    /** The session version {@link #followNewRuns} last examined. @see RunSessions#version */
    private int knownRunVersion = Integer.MIN_VALUE;

    /**
     * Moves the rail to the script that has just started, and takes the view to its output.
     *
     * <h4>A run always selects its own row</h4>
     *
     * <p>Running is the strongest statement anybody makes about what they want to see, so it wins over
     * whatever the rail was showing — including <em>All output</em>. That reverses the first version,
     * which left All alone on the reasoning that it already shows the new run. True, and beside the
     * point: it shows the new run <em>at the bottom of everything else</em>, so a second script started
     * from All appended its output below a screenful of the first one's and nothing moved. <b>All output
     * is a place you go deliberately</b>, and every run defaults to its own row.</p>
     *
     * <h4>Keyed on the run's START, not on what is active now</h4>
     *
     * <p>This asked {@code active()} once a frame and treated anything newly in it as a new run, which
     * misses the case it most needs to catch: a script that runs in twenty milliseconds is {@code
     * RUNNING} and then {@code FINISHED} between two frames, so it is <em>never</em> sampled as active
     * and never selected. Its own start time cannot be missed that way — {@code scripts()} is ordered
     * newest-first, so the head of that list is the last run to have begun whether or not it is still
     * going.</p>
     *
     * <p>Compared by subtraction, and only ever forward. {@code nanoTime} has an arbitrary origin, and
     * "only when something NEWER began" is also what keeps this out of the way of {@code forget}: remove
     * the newest row and an older one becomes the head of the list, which is not a run starting.</p>
     *
     * <p><b>Pulled, never pushed.</b> {@code RunSessions} announces from the thread whose run just
     * changed state, and selecting a row rebuilds list elements; doing it from there would build widgets
     * off the UI thread, which is the crash this panel has already paid for once.</p>
     */
    private void followNewRuns(RunSessions listing) {
        // AFTER rail.tick(), which is what puts a first-time script into the rail's own row list -- ask
        // to select it before that and there is no row to select.
        List<Resource> scripts = listing.scripts();
        if (scripts.isEmpty()) return;
        Resource newest = scripts.get(0);
        RunSessions.Session session = listing.sessionOf(newest);
        if (session == null) return;

        long startedAt = session.startedNanos();
        if (followedAny && startedAt - followedAt <= 0) return;
        followedAny = true;
        followedAt = startedAt;

        // THE RAIL ANNOUNCES IT BACK, which is what updates `selected` and the filter. One path in and
        // one path out, so a run-driven change and a click are indistinguishable downstream.
        rail.showing(newest);
        // AND THE VIEW GOES TO THE TAIL EVEN WHEN THE SELECTION DID NOT MOVE. Re-running the script
        // already showing changes no filter, so the view would be left wherever the reader had scrolled
        // to during the last run -- which for anything that printed more than a screenful is nowhere
        // near the run that just started. `scrollToEnd` re-arms the follow, so the rest of the run keeps
        // pulling the view down.
        view.scrollToEnd();
    }

    /**
     * Shows the input row exactly while a script is blocked reading {@code System.in}.
     *
     * <p><b>Attached and detached rather than hidden</b>, for the reason the rail is: a hidden child still
     * counts for the column's {@code gap-all}, and it would also stay in the Tab sequence — so a console
     * with nothing to answer would have an invisible tab stop under it.</p>
     *
     * <p>Focus follows it in, because the field appearing IS the prompt: a script that stops dead waiting
     * for input, with a field somewhere below that has to be found and clicked, has not asked a question
     * so much as hidden one. Taken back when it goes, or focus would sit on a detached element.</p>
     *
     * <h4>Under the transcript, not across the panel</h4>
     *
     * <p>It was an internal child of the <em>panel</em>, which is a column spanning the rail as well — so
     * the field for answering one script's read was drawn over the list of scripts too, wider than the
     * thing it belongs to and lined up with nothing. A terminal's input sits under its output.</p>
     *
     * <p><b>The split's second pane is already that place</b>, and it needs no wrapper: a pane is a flex
     * column whatever the split's orientation, so the transcript and the field stack there for free. A
     * column of my own was tried first and cost a session — the transcript stopped rendering at all, and
     * nothing about the wrapper's own box was wrong. The pane is a box the engine already lays out
     * correctly, which is the whole argument for using it.</p>
     *
     * <p>And there is always a pane when this is reachable: a script can only be blocked on a read if a
     * script is running, and the rail — and therefore the split — appears the moment one does. The
     * fallback is the panel, so a console being filled by something with no rail still shows its field
     * somewhere rather than nowhere.</p>
     */
    private void refreshInput(@Nullable RunConsole showing) {
        boolean wanted = showing != null && showing.isAwaitingInput();
        if (wanted == inputShown) return;
        inputShown = wanted;

        UINode host = inputHost();
        if (wanted) {
            host.append(inputField);
            inputField.setText("");
            // POINTER focus: this is not a keyboard gesture and the ring would outline the field on every
            // read a script makes. @see UINode#requestPointerFocus
            inputField.document().focus().requestPointerFocus(inputField);
        } else {
            // ASKED OF THE FIELD, not of the window's input handler: UIInputHandler implements
            // CgSystemInput, a CrystalGraphics platform type core takes as compileOnly and does not pass
            // on, so naming it from here fails to compile on a supertype nobody meant to depend on.
            boolean hadFocus = inputField.isFocused();
            UINode parent = inputField.parent();
            if (parent != null) detach(parent, inputField);
            if (hadFocus) document().focus().requestPointerFocus(view.element());
        }
    }

    /** The transcript's own pane while there is one, and the panel otherwise. @see #refreshInput */
    private UINode inputHost() {
        SplitView built = split;
        return built == null ? this : built.pane(1);
    }

    /**
     * Keeps the two run controls honest about what they can actually do.
     *
     * <p><b>They follow different rules, and that is the point.</b> Stop is a question about whatever is
     * running — there is only ever one answer, so it needs no selection and stays live whenever anything
     * is active, including while "All output" is showing. Rerun is a question about a particular file, so
     * it needs a row; with All selected there is no subject and the button says so by going dead.</p>
     *
     * <p>Disabled controls also leave HIT TESTING, not merely gain a {@code :disabled} rule: that
     * pseudo-class ties with {@code :hover} on specificity, so a dead button keeps lighting up under the
     * pointer and keeps showing its tooltip — a control explaining what it would have done.</p>
     */
    private void refreshActions() {
        // THE SAME QUESTION THE STOP COMMAND ASKS, and that is the whole reason it is a supplier. The
        // button used to read `sessions.active()` while `script.stop`'s `enabledWhen` read
        // `host.isRunning()`, and the two disagree exactly when it matters: a stop that has been asked
        // for but not yet obeyed clears the host's run and leaves the session RUNNING until the thread
        // actually dies, so the menu row correctly greyed while the button stayed red and did nothing.
        setStoppable(stoppable.getAsBoolean());

        boolean canRerun = selected != null;
        rerun.setEnabled(canRerun);
        rerun.setHitTest(canRerun);

        // PULLED, NEVER PUSHED. Soft wrap is also bound to Alt+Z on the editor itself, so the button's
        // own handler is not the only thing that can flip it -- a toggle written only where it is
        // clicked goes stale the first time somebody uses the keyboard, and then reports the opposite
        // of the truth.
        boolean wrapping = view.isSoftWrap();
        if (wrapping != wrapShown) {
            wrapShown = wrapping;
            if (wrapping) wrap.addClass(ON_CLASS);
            else wrap.removeClass(ON_CLASS);
        }

        // SCROLL TO END IS A STATE AS WELL AS A VERB, which is IntelliJ's own control: its button stays
        // pressed while the console is following. Pressing it is not the only way to arm the lock --
        // scrolling back to the bottom does too -- so this is pulled for the same reason wrap is, and
        // without it the panel has no answer at all to "will the newest line keep finding me?".
        boolean following = view.isFollowingTail();
        if (following != followShown) {
            followShown = following;
            if (following) toEnd.addClass(ON_CLASS);
            else toEnd.removeClass(ON_CLASS);
        }

        // CLEAR IS DEAD OVER AN EMPTY TRANSCRIPT, as IntelliJ's is. The same rule Stop and Rerun already
        // follow, and the same pairing: out of hit testing too, so it cannot light up or show a tooltip
        // explaining what it would have done.
        boolean anythingToClear = hasOutput();
        clear.setEnabled(anythingToClear);
        clear.setHitTest(anythingToClear);

        // THE BUFFER SIZE, PULLED. Settings resolve outward through the tree, so this cannot be applied
        // at install time -- the panel is not attached yet and would resolve nothing but the default.
        // Reading it per frame also means a change in the Preferences window lands without this panel
        // subscribing to a Settings instance it would have to be handed. Guarded, because setBudgetKb on
        // an unchanged value is still a write. @see ConsoleSettings
        RunConsole target = console;
        if (target != null) {
            int wantedBudget = ConsoleSettings.bufferKb(this);
            if (wantedBudget != budgetApplied) {
                budgetApplied = wantedBudget;
                target.setBudgetKb(wantedBudget);
            }
            // THE STAMP, pulled the same way. `setPrefixStyle` no-ops on an unchanged value and queues a
            // rebuild otherwise, so this costs a comparison on every frame but the one that matters.
            target.setPrefixStyle(ConsoleSettings.prefixStyle(this));
        }

        // The subject is whatever the rail has selected, so both re-word as it moves. Read live rather
        // than written from the selection handler, because the ACCELERATOR can change without the
        // selection changing -- a rebind has no reason to tell this panel about itself.
        String wantRerun = describeAction("Rerun", selected, ScriptCommands.RUN);
        if (!wantRerun.equals(rerunText)) {
            rerunText = wantRerun;
            if (rerunTip != null) rerunTip.setText(wantRerun);
        }
        // STOP NAMES WHAT IS RUNNING, not what is selected. The two differ constantly -- reading one
        // script's output while another is still going is the normal case, and a Stop offering to stop
        // the thing you are merely looking at would be a button that lies about its own effect.
        // ONE LOOKUP, NO LIST. This was `active().stream().findFirst()`, which builds a copy of the map's
        // active keys and a stream over it to look at one element -- once a frame, forever.
        RunSessions running = sessions;
        stopping = running == null ? null : running.firstActive();
        String wantStop = describeAction("Stop", stopping, ScriptCommands.STOP);
        if (!wantStop.equals(stopText)) {
            stopText = wantStop;
            if (stopTip != null) stopTip.setText(wantStop);
        }
    }

    /**
     * Moves the transcript into a split beside the rail.
     *
     * <p>Reparenting a live widget, which is why it happens HERE and not on a gesture: the rail appears
     * when a script first runs — a command, from the editor or the keyboard — so nothing in the console is
     * being pressed or dragged at that moment. Doing it from a click inside the transcript would detach
     * the element the press is being dispatched through.</p>
     */
    private void showRail() {
        if (split != null) return;
        SplitView built = new SplitView();
        built.first(leftColumn);
        // REPARENTED BY THE ADD, not by a remove here. `addChildAtInternal` already detaches from the
        // previous parent and knows to fall back to `removeInternalChild` when the child is internal --
        // which this one is, since `markAsInternal` recursed over `body`. The explicit `removeChild` that
        // used to precede this returned false and did nothing; the add is what always moved it.
        built.second(view.element());
        // A floor in PIXELS rather than a percentage: "at least 150px" stays true at every window size and
        // "at least 15%" does not -- a narrow panel would otherwise clamp the rail to a width that cannot
        // hold a filename.
        built.setPercentage(DEFAULT_SPLIT);
        built.setPaneSizeLimits(0, 150f, 400f);
        // NAMED AND OPTED IN, which is the whole of remembering where the divider was left. The id ties a
        // stored payload to a widget that does not exist for most of a session -- this split is built the
        // first time a script runs -- and UIDocument hands it its state as it joins the tree, so the default
        // above is overwritten before the first frame rather than after it.
        built.setId(SPLIT_ID);
        built.set(Attribute.SESSION_PERSISTENT, true);
        split = built;
        body.insertAt(0, built);
    }

    private void hideRail() {
        SplitView built = split;
        if (built == null) return;
        split = null;
        // THE COLUMN COMES BACK FIRST, so the add reparents it out of the pane before the split itself
        // is detached -- otherwise it would go with the split and there would be no transcript at all.
        body.insertAt(0, view.element());
        detach(body, built);
    }

    /**
     * The split's session identity -- see {@code SessionState}.
     *
     * <p>Namespaced, because the store is keyed by element id across the whole workbench and a bare
     * "split" would collide with the next panel that wanted one.</p>
     *
     * <p>The FILTER is deliberately not persisted, and it would be one more line. A remembered filter
     * naming a script that is not running again opens the console empty for a reason three clicks away --
     * the same document-versus-view boundary the undo stack already draws.</p>
     */
    private static final String SPLIT_ID = "run.rail-split";

    /**
     * What decides whether Stop is offered — asked once a frame.
     *
     * <p><b>A supplier and not a boolean</b>, because the answer belongs to whatever can actually stop
     * something. The panel used to derive it from {@link RunSessions}, which is a different question from
     * the one {@code script.stop}'s own {@code enabledWhen} asks, and two answers to "is anything
     * running" disagree at the worst moment: a script that has been asked to stop and has not yet obeyed
     * is gone from the host and still active in the sessions map, so the menu row greyed while the button
     * stayed lit over a run nothing could stop.</p>
     */
    private BooleanSupplier stoppable = () -> false;

    /** @see #stoppable */
    public RunPanel setStoppableWhen(BooleanSupplier predicate) {
        this.stoppable = predicate == null ? () -> false : predicate;
        return this;
    }

    /** Whether Stop is offered — false while nothing runs, so a dead control is never live. */
    private void setStoppable(boolean stoppable) {
        stop.setEnabled(stoppable);
        // AND OUT OF HIT TESTING, not merely dimmed. `:disabled` and `:hover` tie on specificity, so a
        // disabled control keeps lighting up under the pointer and keeps showing its tooltip -- a dead
        // control explaining what it would have done.
        stop.setHitTest(stoppable);
    }

    public boolean acceptsPublicChildren() {
        return false;
    }

    /**
     * The panel's session identity — see {@code SessionState}.
     *
     * <p>Namespaced like the split's, because the store is keyed by element id across the whole
     * workbench.</p>
     */
    public static final String PANEL_ID = "run.panel";

    private static final String KEY_SOFT_WRAP = "wrap";

    /**
     * {@inheritDoc}
     *
     * <p><b>Soft wrap, and deliberately nothing else.</b> It is the one setting this panel has, IntelliJ
     * remembers it on its console, and forgetting it means anybody who prefers wrapped output re-presses
     * the button every launch.</p>
     *
     * <p>The FILTER is not persisted, and it would be one more line. A remembered filter naming a script
     * that is not running again opens the console empty for a reason three clicks away — the same
     * document-versus-view boundary the undo stack already draws. Nor is the tail-follow lock, which is a
     * statement about where the reader is looking right now and is meaningless before the first line
     * arrives.</p>
     */
    /**
     * Soft wrap, kept across a session.
     *
     * <p>Was a {@code writeState}/{@code readState} pair on the element. On this engine what a widget
     * carries is declared by its {@link com.crystalgui.ui.contract.WidgetContract} and read by the
     * mirror — and a Run panel has no wire: it is local chrome a client builds for itself, so there
     * is nothing to declare a contract for. What it does want is the SESSION, which is the
     * {@code SESSION_PERSISTENT} attribute plus these two, called by the session layer rather than
     * by a peer.</p>
     */
    public boolean isSoftWrap() {
        return view.isSoftWrap();
    }

    /** @see #isSoftWrap() */
    public void setSoftWrap(boolean softWrap) {
        // THROUGH THE VIEW, so the button's own mirror picks it up on the next frame rather than
        // being written here. One writer for the class, one reader -- @see #refreshActions
        view.setSoftWrap(softWrap);
    }

    /** The transcript, for a host that wants its selection or its scroll position. */
    public RunConsoleView view() {
        return view;
    }

    /** The list of scripts, for a host attaching a context menu to its rows. */
    public RunRail rail() {
        return rail;
    }

    /**
     * Whether the right-clicked row can be forgotten.
     *
     * <p><b>A live script cannot.</b> Its row is the only handle on a run that is still printing, and
     * removing it would leave output arriving from something with no way to filter to it, no clock and no
     * Stop. IntelliJ will not close a running tab without stopping it either. Dimmed rather than hidden,
     * which is what this codebase's menus do — a row that vanishes is a row nobody learns.</p>
     */
    public boolean canRemoveContextScript() {
        Resource script = rail.contextScript();
        RunSessions listing = sessions;
        return script != null && (listing == null || !listing.isActive(script));
    }

    /** Announces the removal of the right-clicked row. @see #onRemoveRequested */
    public void removeContextScript() {
        Resource script = rail.contextScript();
        // RE-CHECKED AT ACTIVATION. The menu was built when it was opened, and a script can go live
        // between the press that opened it and the press that chose a row.
        if (script != null && canRemoveContextScript()) onRemoveRequested.emit(script);
    }

    /**
     * Whether there is anything to clear — asked by the Clear button and by its menu row.
     *
     * <p>Both, from here, so the row cannot grey on a different rule from the control beside it. And it
     * asks the <b>transcript</b> as well as the document: under a filter the document is a subset, so a
     * console showing one script's empty output still has plenty to forget.</p>
     */
    public boolean hasOutput() {
        RunConsole showing = console;
        return showing != null && (showing.lineCount() > 0 || showing.transcriptSize() > 0);
    }

    /**
     * Gives the rail its model. <b>Required for the rail to show anything</b> — without it {@code tick}
     * has nothing to list and the column comes up empty, which is exactly how it first shipped.
     */
    public RunPanel bindSessions(@Nullable RunSessions sessions) {
        this.sessions = sessions;
        rail.bindTo(sessions);
        return this;
    }

    public RunPanel bindTo(@Nullable RunConsole console) {
        if (watch != null) {
            watch.disconnect();
            watch = null;
        }
        this.console = console;
        view.bindTo(console);
        if (console != null) watch = console.onDidChange.connect(() -> noticed = -1);
        noticed = -1;
        return this;
    }

    @Nullable
    public RunConsole console() {
        return console;
    }

    @Override
    protected void connected() {
        super.connected();
        if (!ticking) {
            document().animation().every(this, this::tickFrame);
            ticking = true;
        }
    }

    /**
     * The hook is the SERVICE's to drop and the latch is ours to clear, and both halves are needed.
     *
     * <p>A hook is dropped when its owner leaves the tree, so a panel that is detached and re-added --
     * moved between docks, torn out, restored from a session -- comes back with no hook and a latch
     * that still says it has one. A freeze does not reach here and must not: that is temporary, the
     * hook is kept dormant, and clearing the latch there would register a second one on the thaw.</p>
     */
    @Override
    protected void disconnected() {
        super.disconnected();
        ticking = false;
    }

    /**
     * Drains the transcript, then keeps the eviction notice current.
     *
     * <p>ONE ticker for both, and in that order. They are two views of the same drain: reading the drop
     * count before writing the lines it describes would caption a document the reader has not been shown
     * yet, so the notice would run a frame ahead of the transcript it is about.</p>
     */
    /**
     * The per-frame hook, owned by this node.
     *
     * <p>No {@code @Override}: a ticker is not an interface a widget implements here. It is a hook
     * registered with {@code animation().every(this, ...)} and OWNED by the node, so a panel that
     * leaves the tree stops being ticked because the owner went — where the old engine's registry was
     * one-way and made "return false when your element has left" a rule each ticker had to remember.
     * The false return below is now an early stop rather than the only one.</p>
     */
    public boolean tickFrame(float deltaSeconds) {
        UIDocument window = document();
        if (window == null) {
            ticking = false;
            return false;
        }
        view.drain();
        RunConsole showing = console;
        refreshRail();
        refreshActions();
        refreshInput(showing);
        long dropped = showing == null ? 0 : showing.dropped();
        if (dropped != noticed) {
            noticed = dropped;
            // SAID, NEVER SILENT. A transcript that quietly begins in the middle reads as the console
            // having missed something rather than as the ring having done its job.
            notice.setText(dropped == 0 ? ""
                    : dropped + (dropped == 1 ? " earlier line dropped" : " earlier lines dropped"));
            boolean wanted = dropped > 0;
            if (wanted && notice.parent() == null) append(notice);
            else if (!wanted && notice.parent() != null) remove(notice);
        }
        return true;
    }
}
