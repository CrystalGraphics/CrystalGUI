package com.crystalgui.language.run;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Dropdown;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;

import javax.annotation.Nullable;

import java.util.List;

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
    public static final String SPACER_CLASS = "__spacer__";
    public static final String FILTER_CLASS = "__run-filter__";
    public static final String INPUT_CLASS = "__run-input__";

    /** The picker's first row — everything, whoever wrote it. */
    public static final String ALL_SCRIPTS = "All output";
    public static final String HEAD_CLASS = "__head__";
    public static final String ACTION_CLASS = "__run-action__";
    public static final String STOP_CLASS = "__run-stop__";
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
     * Asked to show one script's output, or everything when null.
     *
     * <p>Applied here as well as announced — unlike Stop, filtering IS this view's business, and a host
     * that wants to know (a rail highlighting the same script) can listen. @see RunConsole#setFilter</p>
     */
    public final Signal.Value<String> onFilterRequested = new Signal.Value<>();

    private final RunConsoleView view = new RunConsoleView();
    private final UIText notice = new UIText("");
    private final UIElement head = new UIElement();
    private final UIElement spacer = new UIElement();
    private final Dropdown filterPicker = new Dropdown(ALL_SCRIPTS);

    /**
     * Where a line for {@code System.in} is typed.
     *
     * <h4>A row of its own, not the transcript's last line</h4>
     *
     * <p>The sketch was "a text area that is read-only except for the last line", which is what a terminal
     * looks like — and what it would take is a genuine editable-REGION feature in {@code TextEditor}: not
     * three guard sites but a caret that cannot be moved above the boundary, a selection that cannot span
     * it, a backspace that stops at it, and a paste and an undo that respect it. {@code setReadOnly} is
     * one flag and none of that exists.</p>
     *
     * <p>So the input is a {@code TextField}, which already has every one of those behaviours for the one
     * line it owns. The cost is that the prompt and the answer are on different rows; the alternative was
     * an editor that is <em>mostly</em> read-only, which is the state where a user discovers they have
     * silently edited the transcript. If the editable region lands later this row is where it plugs in.</p>
     */
    private final TextField inputField = new TextField();
    private boolean inputShown;

    /** What the picker currently offers, so it is rebuilt only when the script set actually moves. */
    private List<String> offered = List.of();
    private final Button stop = new Button("");
    private final Button clear = new Button("");

    @Nullable private RunConsole console;
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

        addInternalChild(head);
        addInternalChild(notice);
        addInternalChild(view.element());
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
        head.addClass(HEAD_CLASS);
        // THE CLASS IS WHAT DOES IT. Written without one first, so `> .__spacer__` matched nothing, the
        // element never grew, and the toolbar sat against the leading edge looking deliberate.
        spacer.addClass(SPACER_CLASS);
        spacer.setHitTest(false);

        // THE GLYPH COMES FROM THE SHEET. `overlay` takes a drawable rather than `icon(...)` text, and
        // more to the point a widget writing its own artwork is the rule this codebase keeps: structure
        // and state in Java, appearance in CSS.
        stop.addClass(ACTION_CLASS);
        stop.addClass(STOP_CLASS);
        stop.onPressed.connect(onStopRequested::emit);

        clear.addClass(ACTION_CLASS);
        clear.addClass(CLEAR_CLASS);
        clear.onPressed.connect(onClearRequested::emit);

        // THE LEADING EDGE, because the trailing one belongs to the run controls. A filter is about what
        // you are looking at; Stop and Clear are about what is happening. Keeping them apart is what stops
        // a reader hunting along one undifferentiated row of glyphs.
        filterPicker.addClass(FILTER_CLASS);
        filterPicker.attachSelectionListener(index -> {
            String label = filterPicker.getSelectedOption();
            // INDEX 0 IS "everything", spelled as null rather than as a magic label the console has to
            // know: RunConsole filters on a script name, and "All output" is not one.
            emitFilter(index == 0 || label == null ? null : label);
        });

        head.addChild(spacer);
        head.addChild(stop);
        head.addChild(clear);
    }

    private void emitFilter(@Nullable String script) {
        RunConsole showing = console;
        if (showing != null) showing.setFilter(script);
        onFilterRequested.emit(script);
    }

    /**
     * Keeps the picker's rows in step with what has actually written to the console.
     *
     * <p><b>Only when the set moves</b>, which is why {@code RunConsole.scripts()} is a kept list rather
     * than a derived one — this is asked on every frame the console changes.</p>
     *
     * <p><b>And hidden below two scripts.</b> A filter offering one choice is a control that cannot do
     * anything, and the panel's head is four glyphs wide; the same reason the rail stays out of the way
     * until there is something to choose between.</p>
     */
    private void refreshPicker(@Nullable RunConsole showing) {
        List<String> scripts = showing == null ? List.of() : showing.scripts();
        if (scripts.equals(offered)) return;
        offered = scripts;

        boolean worthShowing = scripts.size() > 1;
        if (!worthShowing) {
            // REMOVED, not merely hidden. A hidden child still counts for the head's `gap-all`, so a
            // display:none picker would put a permanent notch to the left of a console that has no filter
            // to offer -- the trap SearchField's option strip already paid for.
            if (filterPicker.getParent() != null) head.removeInternalChild(filterPicker);
            return;
        }

        String selected = showing == null ? null : showing.filter();
        filterPicker.clearOptions();
        filterPicker.addOption(ALL_SCRIPTS);
        for (String script : scripts) filterPicker.addOption(script);
        // RE-SELECTED AFTER THE REBUILD, because clearOptions drops the selection with the rows. Without
        // this, a script starting while a filter is on would silently reset the view to everything.
        if (selected != null && scripts.contains(selected)) filterPicker.select(selected);
        else filterPicker.select(0);

        if (filterPicker.getParent() == null) head.insertInternalChildAt(filterPicker, 0);
    }

    /**
     * Shows the input row exactly while a script is blocked reading {@code System.in}.
     *
     * <p><b>Attached and detached rather than hidden</b>, for the reason the picker is: a hidden child
     * still counts for the column's {@code gap-all}, and it would also stay in the Tab sequence — so a
     * console with nothing to answer would have an invisible tab stop under it.</p>
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

    /** Whether Stop is offered — false while nothing runs, so a dead control is never live. */
    public RunPanel setStoppable(boolean stoppable) {
        stop.setEnabled(stoppable);
        // AND OUT OF HIT TESTING, not merely dimmed. `:disabled` and `:hover` tie on specificity, so a
        // disabled control keeps lighting up under the pointer and keeps showing its tooltip -- a dead
        // control explaining what it would have done.
        stop.setHitTest(stoppable);
        return this;
    }

    public boolean acceptsPublicChildren() {
        return false;
    }

    /** The transcript, for a host that wants its selection or its scroll position. */
    public RunConsoleView view() {
        return view;
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
        refreshPicker(showing);
        refreshInput(showing);
        long dropped = showing == null ? 0 : showing.dropped();
        if (dropped != noticed) {
            noticed = dropped;
            // SAID, NEVER SILENT. A transcript that quietly begins in the middle reads as the console
            // having missed something rather than as the ring having done its job.
            notice.setText(dropped == 0 ? ""
                    : dropped + (dropped == 1 ? " earlier line dropped" : " earlier lines dropped"));
        }
        return true;
    }
}
