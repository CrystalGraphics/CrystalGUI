package com.crystalgui.language.run;

import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.list.ListRenderer;
import com.crystalgui.ui.elements.list.ListView;

import javax.annotation.Nullable;

import java.util.List;

/**
 * The column of scripts this workspace has run — IntelliJ's run tree, in the one shape that fits.
 *
 * <h3>Live scripts, not runs</h3>
 *
 * <p>IntelliJ lists <em>runs</em>, each with a beginning, an end and an exit code. Two of those three do
 * not exist here: a tick handler never ends, and nothing has an exit code. So a row is a <b>script</b> and
 * what it carries is a {@link RunState} — {@code Live (3 handlers)} rather than {@code exited 0} — which
 * is the whole reason §9.5.3 says the reference is Unity's Console and not IntelliJ's Run window.</p>
 *
 * <h3>Elapsed time instead of a spinner</h3>
 *
 * <p>IntelliJ spins an icon because a process gives it no other sign of life. <b>A spinner would be
 * actively wrong here</b>: the steady state of an event-driven script is {@code LIVE} — loaded, handlers
 * registered, waiting to fire — and nothing is executing at all. A spinner claims work is happening.</p>
 *
 * <p>The ticking elapsed time says everything the spinner would and more: that it is alive, and for how
 * long. It ticks for an active script and freezes at the final duration for one that has stopped, so a
 * row reads the same whether you are watching it or came back to it.</p>
 *
 * <h3>Selecting a row filters the transcript</h3>
 *
 * <p>Which is what makes the rail a control rather than a caption, and it is why the per-script filter was
 * built first: this is the picker for it. The head's dropdown was a stand-in for exactly this and is gone.</p>
 */
public final class RunRail extends UIElement {

    public static final String RAIL_CLASS = "__run-rail__";
    public static final String ROW_NAME_CLASS = "__run-rail-name__";
    public static final String ROW_TIME_CLASS = "__run-rail-time__";
    public static final String ROW_GLYPH_CLASS = "__run-rail-glyph__";

    /** On the glyph, so the sheet gives each state its own colour. One at a time. */
    public static final String STATE_CLASS_PREFIX = "runstate-";

    /** The first row: everything, whoever wrote it. Selected when no filter is set. */
    public static final String ALL_LABEL = "All output";

    /** Asked to show one script's output, or everything when null. */
    public final Signal.Value<Resource> onScriptChosen = new Signal.Value<>();

    /**
     * The rows. Index 0 is always {@link #ALL_LABEL}; a null entry <em>is</em> that row.
     *
     * <p>Nullable rather than a wrapper type, because the alternative is a record whose only job is to
     * hold "or nothing" — and every consumer would then unwrap it to ask the same question.</p>
     */
    private final ObservableList<Resource> rows = new ObservableList<>();

    private final ListView<Resource> list = new ListView<>(rows);

    /** What {@link #sync} last built, so a frame that changed nothing rebuilds nothing. */
    private List<Resource> known = List.of();

    @Nullable private RunSessions sessions;

    public RunRail() {
        addClass(RAIL_CLASS);
        list.setRenderer(new Rows());
        list.setSelectionMode(com.crystalgui.ui.elements.list.SelectionMode.SINGLE);
        list.onSelectionChanged.connect(indices -> {
            if (indices.isEmpty()) return;
            int index = indices.iterator().next();
            onScriptChosen.emit(index <= 0 ? null : rows.get(index));
        });
        addInternalChild(list);
    }

    public RunRail bindTo(@Nullable RunSessions sessions) {
        this.sessions = sessions;
        known = List.of();
        return this;
    }

    /** The list, for a host that wants its focus or its scroll position. */
    public ListView<Resource> list() {
        return list;
    }

    /**
     * Rebuilds the rows when the script set moves, and repaints the times every frame.
     *
     * <p><b>Two different cadences, deliberately.</b> The row SET changes when a script is first run,
     * which is rare; the elapsed TIME changes continuously. Rebuilding the list to advance a clock would
     * recycle every row twenty times a second — and a widget must never rebuild the elements it is being
     * clicked on.</p>
     */
    public void tick() {
        RunSessions showing = sessions;
        if (showing == null) return;

        List<Resource> scripts = showing.scripts();
        if (!scripts.equals(known)) {
            known = scripts;
            rows.clear();
            // THE NULL IS THE "All output" ROW. @see #rows
            rows.add(null);
            for (Resource script : scripts) rows.add(script);
            if (list.getSelectedIndices().isEmpty()) list.select(0);
        }
        // IN PLACE, never through the list: refreshing would rebind every row and take the press out from
        // under anything being clicked. @see ListView#refresh
        //
        // AND THE STATE, not only the clock -- which the first version got wrong. The row SET changes when
        // a script is first run; the STATE changes on every transition, and a script finishing changes no
        // set at all. So a finished script kept its green dot until something else happened to rebuild the
        // list, and running a second script appeared to "fix" the first one's mark.
        for (java.util.Map.Entry<Integer, UIElement> realised : list.realisedRows().entrySet()) {
            writeRow(realised.getKey(), realised.getValue());
        }
    }

    /**
     * Selects the row for {@code script}, or the All row for null.
     *
     * <p><b>This announces</b> — {@code onScriptChosen} fires exactly as it would for a click, because
     * the selection genuinely changed and everything downstream of a selection has to hear about it.
     * The javadoc here used to claim the opposite, which was never true of the implementation and was
     * the sort of comment that gets believed: a caller reading it would have written a second path to
     * apply the filter by hand, and then two of them would run.</p>
     */
    public void showing(@Nullable Resource script) {
        int index = script == null ? 0 : known.indexOf(script) + 1;
        if (index >= 0 && index < rows.size()) list.select(index);
    }

    /**
     * Writes everything about a row that can change without the row set changing: its state mark, its
     * clock and its tooltip.
     *
     * <p>Shared by {@code bind} and the per-frame pass so the two cannot disagree — which is exactly what
     * happened when only the clock was written here and the mark was left to {@code bind}.</p>
     */
    private void writeRow(int index, UIElement row) {
        RunSessions showing = sessions;
        if (showing == null || index <= 0 || index - 1 >= known.size()) return;
        RunSessions.Session session = showing.sessionOf(known.get(index - 1));

        if (row.querySelector("." + ROW_TIME_CLASS) instanceof UIText time) {
            time.setText(session == null ? ""
                    : RunElapsed.format(session.elapsedNanos(System.nanoTime())));
        }
        UIElement glyph = row.querySelector("." + ROW_GLYPH_CLASS);
        if (glyph != null) swapState(glyph, session == null ? null : session.state());
        describeInto(row, session);
    }

    /**
     * SWAPS the state class rather than adding it — a template is a different row every time the view
     * reuses it, so adding {@code runstate-live} without removing {@code runstate-failed} leaves both on
     * the element and the cascade resolves whichever rule happens to win. That reads as a random colour
     * rather than as a stale class.
     */
    private void swapState(UIElement glyph, @Nullable RunState state) {
        for (RunState value : RunState.values()) {
            glyph.removeClass(STATE_CLASS_PREFIX + value.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (state != null) {
            glyph.addClass(STATE_CLASS_PREFIX + state.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private void describeInto(UIElement row, @Nullable RunSessions.Session session) {
        Tooltip tooltip = tooltips.get(row);
        if (tooltip != null) tooltip.setText(describe(session));
    }

    /**
     * The All row's own words.
     *
     * <p>It is not a script, so every answer {@link #describe} can give is wrong about it — and the one
     * it gave was {@code Never run}, which is the most wrong of them: the row that shows EVERYTHING said
     * nothing had. Separate rather than a null branch inside {@code describe}, because null there
     * genuinely does mean "a script this workspace has never run" and both callers need that to stay
     * true.</p>
     */
    private void describeAllRow(UIElement row) {
        Tooltip tooltip = tooltips.get(row);
        if (tooltip != null) tooltip.setText("Output from every script");
    }

    private static String describe(@Nullable RunSessions.Session session) {
        if (session == null) return "Never run";
        if (session.state() == RunState.LIVE) {
            int handlers = session.handlers();
            return "Live (" + handlers + (handlers == 1 ? " handler)" : " handlers)");
        }
        String name = session.state().name();
        return name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * One tooltip per pooled template, retained.
     *
     * <p>{@code Tooltip.attach} <b>adds</b> a listener pair rather than replacing one, so calling it from
     * {@code bind} — which runs for every visible row on every scroll step — leaves the previous tooltip
     * attached and showing, and the text then never appears to update however correct the lookup is.</p>
     */
    private final java.util.Map<UIElement, Tooltip> tooltips = new java.util.HashMap<>();

    /** One row: a state glyph, the file's name, and how long it has been going. */
    private final class Rows implements ListRenderer<Resource> {

        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            UIElement glyph = new UIElement();
            glyph.addClass(ROW_GLYPH_CLASS);
            glyph.setHitTest(false);

            UIText name = new UIText("");
            name.addClass(ROW_NAME_CLASS);
            name.setHitTest(false);

            UIText time = new UIText("");
            time.addClass(ROW_TIME_CLASS);
            time.setHitTest(false);

            row.addChild(glyph);
            row.addChild(name);
            row.addChild(time);
            tooltips.put(row, Tooltip.attach(row, ""));
            return row;
        }

        @Override
        public void bind(@Nullable Resource item, int index, UIElement template) {
            if (!(template.querySelector("." + ROW_NAME_CLASS) instanceof UIText name)) return;

            if (item == null) {
                name.setText(ALL_LABEL);
                if (template.querySelector("." + ROW_TIME_CLASS) instanceof UIText time) time.setText("");
                UIElement glyph = template.querySelector("." + ROW_GLYPH_CLASS);
                if (glyph != null) swapState(glyph, null);
                describeAllRow(template);
                return;
            }

            // THE NAME IS THE ONLY THING BIND OWNS. Everything else about a row changes without the row
            // set changing, so it is written by writeRow -- from here for the first fill, and from the
            // per-frame pass thereafter.
            name.setText(item.name());
            writeRow(index, template);
        }
    }
}
