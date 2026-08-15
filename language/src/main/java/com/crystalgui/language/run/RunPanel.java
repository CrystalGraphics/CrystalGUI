package com.crystalgui.language.run;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.UIText;

import javax.annotation.Nullable;

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

    private final RunConsoleView view = new RunConsoleView();
    private final UIText notice = new UIText("");
    private final UIElement head = new UIElement();
    private final UIElement spacer = new UIElement();
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
        addInternalChild(head);
        addInternalChild(notice);
        addInternalChild(view.element());

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

        head.addChild(spacer);
        head.addChild(stop);
        head.addChild(clear);
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

    @Override
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
