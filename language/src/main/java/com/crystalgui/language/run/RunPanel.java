package com.crystalgui.language.run;

import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.list.ListRenderer;
import com.crystalgui.ui.elements.list.ListView;

import javax.annotation.Nullable;

import java.util.List;

/**
 * What the running scripts have printed — the panel over {@link RunConsole}.
 *
 * <h3>A transcript is a list, not a tree</h3>
 *
 * <p>{@code ProblemsPanel} is a {@code TreeView} because a problem belongs to a file and files group.
 * Output does not group: it is one sequence in the order it happened, and the moment rows are nested
 * under headings the thing a console is <em>for</em> — reading what just happened, in order — stops
 * working. So this is a flat {@link ListView}, filtered rather than grouped.</p>
 *
 * <h3>The refresh is deferred, and that is not an optimisation</h3>
 *
 * <p>{@link RunConsole#onDidChange} fires on every appended line. A tick handler printing once a tick
 * emits twenty a second, per script, and rebuilding the row model on each would spend the frame doing
 * it. So a change only sets a flag and the rebuild happens once, in {@link #tickFrame} — the same
 * deferred-refresh shape {@code ProjectFileTree} uses for decorations, and for the second reason it
 * gives too: a rebuild recycles every realised row, which must never happen inside the signal that
 * caused it.</p>
 *
 * <h3>It follows the tail, but only if you were already there</h3>
 *
 * <p>A console that always jumps to the bottom cannot be read while anything is running, and one that
 * never does is not a console. Both references resolve it the same way: follow while the newest row is
 * on screen, and stop the moment the reader scrolls away.</p>
 */
public final class RunPanel extends UIElement implements UIFrameTicker {

    public static final String PANEL_CLASS = "__run-panel__";
    public static final String LIST_CLASS = "__run-list__";
    public static final String ROW_CLASS = "__run-row__";
    public static final String TEXT_CLASS = "__run-text__";
    public static final String COUNT_CLASS = "__run-count__";
    public static final String NOTICE_CLASS = "__run-notice__";
    public static final String HEAD_CLASS = "__head__";
    public static final String ACTION_CLASS = "__run-action__";
    public static final String STOP_CLASS = "__run-stop__";
    public static final String CLEAR_CLASS = "__run-clear__";
    public static final String DIVIDER_CLASS = "__run-divider__";
    /** On a row whose origin can be opened, so it can be given a link's affordance. */
    public static final String NAVIGABLE_CLASS = "__run-navigable__";

    /** {@code runlevel-out}, {@code runlevel-warn}, {@code runlevel-error} — the palette hook. */
    public static final String LEVEL_PREFIX = "runlevel-";

    /** Fired when a row is activated and it has somewhere to go. @see RunConsole.Entry#isNavigable */
    public final Signal.Value<RunConsole.Entry> onEntryActivated = new Signal.Value<>();

    /**
     * Asked to clear, and asked to stop.
     *
     * <p>Signals rather than the panel doing either itself, because it is a view over a
     * {@link RunConsole} and knows nothing about a {@code ScriptHost}. Clearing it could arguably live
     * here; stopping certainly could not, and having the two work differently would be worse than
     * routing both the same way.</p>
     */
    public final Signal.Action onClearRequested = new Signal.Action();
    public final Signal.Action onStopRequested = new Signal.Action();

    private final ObservableList<RunConsole.Entry> rows = new ObservableList<>();
    private final ListView<RunConsole.Entry> list = new ListView<>(rows);
    private final UIText notice = new UIText("");
    private final UIElement head = new UIElement();
    private final UIElement spacer = new UIElement();
    private final Button stop = new Button("");
    private final Button clear = new Button("");

    @Nullable private RunConsole console;
    @Nullable private Connection watch;

    /**
     * Set from whatever thread appended, drained on the UI thread.
     *
     * <p>Volatile rather than synchronized: a script prints from its own thread or the game's, and the
     * only thing crossing that boundary is one boolean. Losing a race here would cost one frame of
     * latency and never a row, because the rebuild reads the console's own snapshot rather than a diff.</p>
     */
    private volatile boolean pendingRefresh;
    private boolean ticking;

    public RunPanel() {
        list.addClass(LIST_CLASS);
        list.setRenderer(new Rows());
        notice.addClass(NOTICE_CLASS);
        notice.setHitTest(false);

        // NO markAsInternal() ON THIS. It recurses and would make the panel's own selector subject
        // unstyleable -- `runpanel .__run-row__` would match while `runpanel` itself did not, so the
        // panel would have no size, no background and children that looked fine. `addInternalChild`
        // marks each part individually, which is the correct half of that pair.
        buildHead();
        addInternalChild(head);
        addInternalChild(notice);
        addInternalChild(list);

        list.onRowActivated.connect(index -> {
            if (index == null || index < 0 || index >= rows.size()) return;
            RunConsole.Entry entry = rows.get(index);
            if (entry.isNavigable()) onEntryActivated.emit(entry);
        });
    }

    /**
     * The toolbar — Stop and Clear, in that order.
     *
     * <p>Stop first because it is the one with a deadline: it is the only affordance that rescues a
     * runaway script, and both references put it at the leading edge of the run toolbar for that reason.
     * They are also the two that were keyboard-only until now, which is the same as absent for anybody
     * who has not read the keymap.</p>
     *
     * <p>Icon-only, like every IDE console toolbar. A label would double the head's height to say what a
     * universally recognised glyph already says, and the accelerator lives in the tooltip.</p>
     */
    private void buildHead() {
        head.addClass(HEAD_CLASS);
        spacer.setHitTest(false);

        // THE GLYPH COMES FROM THE SHEET, not from here. `overlay` takes a drawable rather than the
        // `icon(...)` text, and more to the point a widget writing its own artwork is the rule this
        // codebase already keeps: structure and state in Java, appearance in CSS. Each button carries a
        // class the sheet hangs the icon on.
        stop.addClass(ACTION_CLASS);
        stop.addClass(STOP_CLASS);
        stop.onPressed.connect(onStopRequested::emit);

        clear.addClass(ACTION_CLASS);
        clear.addClass(CLEAR_CLASS);
        clear.onPressed.connect(onClearRequested::emit);

        // THE SPACER IS WHAT PUTS THE GROUP AT THE TRAILING EDGE, rather than either button carrying a
        // margin -- the same shape `statusbarview` uses, and for the same reason: a margin has to be
        // recomputed by whoever adds the next control.
        head.addChild(spacer);
        head.addChild(stop);
        head.addChild(clear);
    }

    /** Whether Stop is offered — false while nothing is running, so a dead control is never live. */
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

    /** The list, for a host that wants to install a context menu or read the selection. */
    public ListView<RunConsole.Entry> list() {
        return list;
    }

    /** The rows currently shown — the console's snapshot as of the last frame, never live. */
    public List<RunConsole.Entry> rows() {
        return rows.asUnmodifiableList();
    }

    /** Shows {@code console}, replacing whatever was shown before. */
    public RunPanel bindTo(@Nullable RunConsole console) {
        if (watch != null) {
            watch.disconnect();
            watch = null;
        }
        this.console = console;
        if (console != null) {
            // ONLY A FLAG. @see the class note -- this fires per line.
            watch = console.onDidChange.connect(changed -> pendingRefresh = true);
        }
        pendingRefresh = true;
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
        pendingRefresh = true;
        if (!ticking) {
            current.registerTicker(this);
            ticking = true;
        }
    }

    @Override
    public boolean tickFrame(float deltaSeconds) {
        UIWindow window = getAttachedWindow();
        if (window == null) {
            ticking = false;
            return false;
        }
        if (pendingRefresh) {
            pendingRefresh = false;
            rebuild();
        }
        return true;
    }

    /**
     * Replaces the row model from the console's snapshot.
     *
     * <p>A whole replacement rather than a diff, because collapsing means an append can <em>mutate</em>
     * the last row rather than add one — so "what changed" is not a suffix and a diff would have to
     * compare every row to find the one whose count moved. {@code setAll} over a bounded ring is cheap
     * and cannot drift from the truth.</p>
     */
    private void rebuild() {
        RunConsole showing = console;
        if (showing == null) {
            rows.clear();
            notice.setText("");
            return;
        }

        boolean wasAtTail = isAtTail();
        List<RunConsole.Entry> entries = showing.entries();
        rows.setAll(entries);

        long dropped = showing.dropped();
        // SAID, NEVER SILENT. A transcript that quietly begins in the middle reads as the console having
        // missed something rather than as the ring having done its job.
        notice.setText(dropped == 0 ? ""
                : dropped + (dropped == 1 ? " earlier line dropped" : " earlier lines dropped"));

        if (wasAtTail && !entries.isEmpty()) list.scrollToIndex(entries.size() - 1);
    }

    /**
     * Whether the newest row is on screen — asked <b>before</b> the rebuild, because afterwards every
     * index has moved and the question can no longer be answered.
     */
    private boolean isAtTail() {
        if (rows.isEmpty()) return true;
        float viewport = list.getClientHeight();
        // A LIST SHORTER THAN ITS VIEWPORT is always at its tail; getMaxScrollTop is 0 there and the
        // comparison below would be true anyway, but only by accident.
        if (list.getScrollHeight() <= viewport) return true;
        return list.getScrollTop() >= list.getMaxScrollTop() - 1f;
    }

    /**
     * One row: the line, and how many repeats it stands for.
     *
     * <h3>There is no origin column, and there was</h3>
     *
     * <p>Every row carried {@code RunTest.java:50} against its trailing edge, and it made the panel hard
     * to read for a reason worth recording: <b>neither reference shows one</b>. IntelliJ's console is the
     * raw stream with clickable {@code file:line} appearing inline where a stack trace naturally puts it;
     * Unity keeps the trace in a detail pane below the list. A per-row column is an invention, it repeats
     * the same string down the whole panel, and it competes with the script's own formatting — output is
     * usually aligned by its author, and a right-aligned column fights that alignment for the eye.</p>
     *
     * <p>The origin is still carried on the message: it keys the collapse and it is what a double-click
     * navigates by. It simply is not <em>drawn</em>, which is the difference between data and chrome.</p>
     */
    private final class Rows implements ListRenderer<RunConsole.Entry> {

        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            row.addClass(ROW_CLASS);
            UIText text = new UIText("");
            text.addClass(TEXT_CLASS);
            UIText count = new UIText("");
            count.addClass(COUNT_CLASS);
            row.addChild(text);
            row.addChild(count);
            return row;
        }

        @Override
        public void bind(RunConsole.Entry entry, int index, UIElement template) {
            List<UIElement> parts = template.getChildren();
            ((UIText) parts.get(0)).setText(entry.text());
            // SWAPPED, like the level below: a template is a different row every time the view reuses
            // it, so a divider that only ever ADDED its class would leave every row it was ever pooled
            // into looking like a boundary.
            template.removeClass(DIVIDER_CLASS);
            template.removeClass(NAVIGABLE_CLASS);
            if (entry.isDivider()) template.addClass(DIVIDER_CLASS);
            else if (entry.isNavigable()) template.addClass(NAVIGABLE_CLASS);
            // SWAPPED, NEVER ADDED. A template is a different row every time the view reuses it, so
            // adding `runlevel-error` without removing `runlevel-out` leaves both on the element and the
            // cascade resolves whichever rule happens to win -- which reads as a random colour rather
            // than as a stale class. Same rule ProjectFileTree's swapPrefixedClass exists for.
            for (RunLevel level : RunLevel.values()) {
                template.removeClass(LEVEL_PREFIX + level.name().toLowerCase(java.util.Locale.ROOT));
            }
            template.addClass(LEVEL_PREFIX + entry.level().name().toLowerCase(java.util.Locale.ROOT));

            ((UIText) parts.get(1)).setText(entry.count() > 1 ? "×" + entry.count() : "");
        }
    }
}
