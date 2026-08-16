package com.crystalgui.language.run;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;

import javax.annotation.Nullable;

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
public final class RunPanel extends UIElement implements UIFrameTicker {

    public static final String NOTICE_CLASS = "__run-notice__";
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
     * {@link RunConsole} and knows nothing about a {@code ScriptHost}. Clearing could arguably live here;
     * stopping certainly could not, and having the two work differently would be worse than routing both
     * the same way.</p>
     */
    public final Signal.Action onClearRequested = new Signal.Action();
    public final Signal.Action onStopRequested = new Signal.Action();

    /**
     * Asked to run one script again.
     *
     * <p>Carries the script, because unlike Stop this one has a <em>subject</em>: stopping is a question
     * about whatever is currently running, and there is only ever one answer to that. Re-running is a
     * question about a particular file.</p>
     */
    public final Signal.Value<Resource> onRerunRequested = new Signal.Value<>();

    /**
     * Asked to show one script's output, or everything when null.
     *
     * <p>Applied here as well as announced — unlike Stop, filtering IS this view's business, and a host
     * that wants to know (a rail highlighting the same script) can listen. @see RunConsole#setFilter</p>
     */
    public final Signal.Value<String> onFilterRequested = new Signal.Value<>();

    private final RunConsoleView view = new RunConsoleView();
    private final UIText notice = new UIText("");
    private final UIElement stripe = new UIElement();
    /**
     * The rail and its own toolbar, as one column.
     *
     * <p>IntelliJ puts the RUN controls in a horizontal bar above the tree and the CONSOLE controls in a
     * vertical stripe beside the output, and the split is not decorative: rerun and stop act on a
     * <em>script</em>, which is what the rail lists, while wrap and scroll act on the <em>transcript</em>.
     * Each toolbar sits with the thing it operates on.</p>
     */
    private final UIElement leftColumn = new UIElement();
    private final UIElement runBar = new UIElement();
    /**
     * The rule under the run bar.
     *
     * <p>An ELEMENT, because a border cannot draw one here: the paint path takes {@code border().left} as
     * its stroke width and strokes a uniform box, so a bottom-only hairline resolves, lays out, and draws
     * nothing at all. The find bar spent a session on exactly this, and {@code statusbarview} spells its
     * separators the same way.</p>
     */
    private final UIElement separator = new UIElement();

    /**
     * The row under the toolbar: the rail, the transcript, and the console's control stripe.
     *
     * <p>The rail REPLACED the head's dropdown rather than joining it — both answer the same question,
     * which script's output am I looking at, and two controls for one question is the arrangement where
     * they drift apart.</p>
     */
    private final UIElement body = new UIElement();
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
    @Nullable private Connection watch;
    private long noticed = -1;
    private boolean ticking;

    public RunPanel() {
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
        leftColumn.addChild(rail);

        body.addChild(view.element());
        // AFTER the transcript, so it sits on the trailing edge. IntelliJ's console keeps its controls in
        // a narrow vertical stripe there rather than in a full-width bar above, and the reason is space:
        // a row across the whole panel spends 22px of height to hold four glyphs, on a panel whose entire
        // job is showing as many lines as it can.
        body.addChild(stripe);
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
        addInternalChild(runBar);
        addInternalChild(separator);
        addInternalChild(body);
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
        stop.onPressed.connect(onStopRequested::emit);

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

        runBar.addChild(rerun);
        runBar.addChild(stop);

        stripe.addChild(wrap);
        stripe.addChild(toEnd);
        stripe.addChild(clear);

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
        onFilterRequested.emit(script);
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
        boolean wanted = listing != null && !listing.scripts().isEmpty();
        if (wanted != railShown) {
            railShown = wanted;
            if (wanted) showRail();
            else hideRail();
        }
        // EVERY FRAME while it is up, because the elapsed time is the liveness signal -- see RunRail for
        // why that stands in for a spinner rather than beside one.
        if (railShown) rail.tick();
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
     */
    private void refreshInput(@Nullable RunConsole showing) {
        boolean wanted = showing != null && showing.isAwaitingInput();
        if (wanted == inputShown) return;
        inputShown = wanted;

        if (wanted) {
            addInternalChild(inputField);
            inputField.setText("");
            // POINTER focus: this is not a keyboard gesture and the ring would outline the field on every
            // read a script makes. @see UIElement#requestPointerFocus
            inputField.requestPointerFocus();
        } else {
            // ASKED OF THE FIELD, not of the window's input handler: UIInputHandler implements
            // CgSystemInput, a CrystalGraphics platform type core takes as compileOnly and does not pass
            // on, so naming it from here fails to compile on a supertype nobody meant to depend on.
            boolean hadFocus = inputField.isFocused();
            removeInternalChild(inputField);
            if (hadFocus) view.element().requestPointerFocus();
        }
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
        RunConsole showing = console;
        boolean anythingToClear = showing != null
                && (showing.lineCount() > 0 || showing.transcriptSize() > 0);
        clear.setEnabled(anythingToClear);
        clear.setHitTest(anythingToClear);

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
        RunSessions running = sessions;
        Resource stopping = running == null ? null : running.active().stream().findFirst().orElse(null);
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
        body.removeChild(view.element());
        built.first(leftColumn);
        built.second(view.element());
        // A floor in PIXELS rather than a percentage: "at least 150px" stays true at every window size and
        // "at least 15%" does not -- a narrow panel would otherwise clamp the rail to a width that cannot
        // hold a filename.
        built.setPercentage(DEFAULT_SPLIT);
        built.setPaneSizeLimits(0, 150f, 400f);
        // NAMED AND OPTED IN, which is the whole of remembering where the divider was left. The id ties a
        // stored payload to a widget that does not exist for most of a session -- this split is built the
        // first time a script runs -- and UIWindow hands it its state as it joins the tree, so the default
        // above is overwritten before the first frame rather than after it.
        built.setId(SPLIT_ID);
        built.setSessionPersistent(true);
        split = built;
        body.addChildAt(built, 0);
    }

    private void hideRail() {
        SplitView built = split;
        if (built == null) return;
        split = null;
        body.removeChild(built);
        body.addChildAt(view.element(), 0);
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
    @Override
    protected <T> void writeState(StateMap<T> out) {
        super.writeState(out);
        out.putBoolIfNot(KEY_SOFT_WRAP, view.isSoftWrap(), false);
    }

    @Override
    protected <T> void readState(StateMap<T> in) {
        super.readState(in);
        // THROUGH THE VIEW, so the button's own mirror picks it up on the next frame rather than being
        // written here. One writer for the class, one reader -- @see #refreshActions
        view.setSoftWrap(in.getBool(KEY_SOFT_WRAP, false));
    }

    /** The transcript, for a host that wants its selection or its scroll position. */
    public RunConsoleView view() {
        return view;
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
    protected void onWindowChanged(@Nullable UIWindow previous, @Nullable UIWindow current) {
        super.onWindowChanged(previous, current);
        if (current == null) return;
        if (!ticking) {
            current.registerTicker(this);
            ticking = true;
        }
    }

    /**
     * Drains the transcript, then keeps the eviction notice current.
     *
     * <p>ONE ticker for both, and in that order. They are two views of the same drain: reading the drop
     * count before writing the lines it describes would caption a document the reader has not been shown
     * yet, so the notice would run a frame ahead of the transcript it is about.</p>
     */
    @Override
    public boolean tickFrame(float deltaSeconds) {
        UIWindow window = getAttachedWindow();
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
            if (wanted && notice.getParent() == null) addInternalChild(notice);
            else if (!wanted && notice.getParent() != null) removeInternalChild(notice);
        }
        return true;
    }
}
