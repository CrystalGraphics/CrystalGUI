package com.crystalgui.language.run;

import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.list.ListRenderer;
import com.crystalgui.ui.elements.list.ListView;
import com.crystalgui.ui.elements.list.SelectionMode;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private final ObservableList<Resource> items = new ObservableList<>();

    private final ListView<Resource> list = new ListView<>(items);

    /** What {@link #tick} last built, so a frame that changed nothing rebuilds nothing. */
    private List<Resource> known = List.of();

    /**
     * The session version {@link #known} was built from — the cheap half of the same guard.
     *
     * <p>{@link Integer#MIN_VALUE} rather than 0, so a rail bound to a brand-new {@code RunSessions}
     * still builds once: a real version of 0 means "nothing has changed yet", which is exactly the state
     * a fresh one is in, and seeding this to 0 would agree with it and never fill the list.</p>
     */
    private int knownVersion = Integer.MIN_VALUE;

    @Nullable private RunSessions sessions;

    public RunRail() {
        addClass(RAIL_CLASS);
        list.setRenderer(new Rows());
        list.setSelectionMode(SelectionMode.SINGLE);
        list.onSelectionChanged.connect(indices -> {
            if (indices.isEmpty()) return;
            int index = indices.iterator().next();
            onScriptChosen.emit(index <= 0 ? null : items.get(index));
        });
        addInternalChild(list);
    }

    public RunRail bindTo(@Nullable RunSessions sessions) {
        this.sessions = sessions;
        known = List.of();
        knownVersion = Integer.MIN_VALUE;
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

        // ASKED AS AN INT FIRST. `scripts()` copies the key set under the lock, and this ran every frame
        // to compare it against a list that changes a handful of times in a session -- so the common
        // frame allocated a list and walked it to conclude nothing had happened. @see RunSessions#version
        int now = showing.version();
        if (now != knownVersion) {
            knownVersion = now;
            List<Resource> scripts = showing.scripts();
            // STILL COMPARED. The version moves on every state transition, most of which leave the row
            // SET alone -- a script going LIVE is not a new row, and rebuilding for it would recycle
            // every row twenty times a second, which is the thing this method exists to avoid.
            if (!scripts.equals(known)) {
                // CAPTURED BY ITEM, BEFORE THE REBUILD. The list selects by INDEX, and this list is
                // ordered newest-first -- so a re-run reorders it and an index that is still perfectly
                // valid now points at a DIFFERENT script. The row would highlight one file while the
                // transcript went on showing another, and nothing would announce the change because no
                // selection event fired. The engine already records this trap for trees; a sorted list
                // has it for the same reason.
                Resource chosen = selectedScript();
                known = scripts;
                items.clear();
                // THE NULL IS THE "All output" ROW. @see #items
                items.add(null);
                for (Resource script : scripts) items.add(script);
                restoreSelection(chosen);
            }
        }
        // IN PLACE, never through the list: refreshing would rebind every row and take the press out from
        // under anything being clicked. @see ListView#refresh
        //
        // AND THE STATE, not only the clock -- which the first version got wrong. The row SET changes when
        // a script is first run; the STATE changes on every transition, and a script finishing changes no
        // set at all. So a finished script kept its green dot until something else happened to rebuild the
        // list, and running a second script appeared to "fix" the first one's mark.
        for (Map.Entry<Integer, UIElement> realised : list.realisedRows().entrySet()) {
            writeRow(realised.getKey(), realised.getValue());
        }
    }

    /**
     * The script a context menu was opened over, or null for the All row and for no menu.
     *
     * <p><b>Not the selection.</b> A right-click names the row it is about and leaves the selection where
     * it was — the same rule {@code ListView.setContextRow} states for Copy, and the same one the file
     * tree follows. Removing the row you are <em>reading</em> because you right-clicked a different one
     * would be the worst possible reading of the gesture.</p>
     */
    @Nullable
    public Resource contextScript() {
        return contextIndex <= 0 || contextIndex >= items.size() ? null : items.get(contextIndex);
    }

    /** Told by whoever built the menu which row it was opened over. @see #contextScript */
    public void setContextIndex(int index) {
        this.contextIndex = index;
    }

    private int contextIndex = -1;

    /**
     * The script the rail is showing, or null for the All row — <b>and for nothing selected</b>.
     *
     * <p>The two collapse deliberately: both mean "not narrowed to a script", and both restore to the
     * same row. Distinguishing them would buy a caller nothing and cost it a case to get wrong.</p>
     */
    @Nullable
    private Resource selectedScript() {
        for (int index : list.getSelectedIndices()) {
            return index <= 0 || index >= items.size() ? null : items.get(index);
        }
        return null;
    }

    /** Puts the selection back on the same SCRIPT after a rebuild, wherever it has moved to. */
    private void restoreSelection(@Nullable Resource script) {
        int index = script == null ? 0 : known.indexOf(script) + 1;
        // A SCRIPT THAT IS NO LONGER LISTED falls back to All rather than to whatever took its index --
        // `forget` can remove the very row that was selected.
        if (index <= 0 || index >= items.size()) index = 0;
        list.select(index);
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
        if (index >= 0 && index < items.size()) list.select(index);
    }

    /**
     * Writes everything about a row that can change without the row set changing: its state mark, its
     * clock and its tooltip.
     *
     * <p>Shared by {@code bind} and the per-frame pass so the two cannot disagree — which is exactly what
     * happened when only the clock was written here and the mark was left to {@code bind}.</p>
     */
    private void writeRow(int index, UIElement element) {
        RunSessions showing = sessions;
        Row row = rows(element);
        if (showing == null || row == null || index <= 0 || index - 1 >= known.size()) return;
        RunSessions.Session session = showing.sessionOf(known.get(index - 1));

        row.time.setText(session == null ? ""
                : RunElapsed.format(session.elapsedNanos(System.nanoTime())));
        swapState(row.glyph, session == null ? null : session.state());
        row.describe(describe(session));
    }

    /**
     * SWAPS the state class rather than adding it — a template is a different row every time the view
     * reuses it, so adding {@code runstate-live} without removing {@code runstate-failed} leaves both on
     * the element and the cascade resolves whichever rule happens to win. That reads as a random colour
     * rather than as a stale class.
     */
    private void swapState(UIElement glyph, @Nullable RunState state) {
        for (RunState value : RunState.values()) {
            glyph.removeClass(STATE_CLASS_PREFIX + value.name().toLowerCase(Locale.ROOT));
        }
        if (state != null) {
            glyph.addClass(STATE_CLASS_PREFIX + state.name().toLowerCase(Locale.ROOT));
        }
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
    private static final String ALL_TOOLTIP = "Output from every script";

    private static String describe(@Nullable RunSessions.Session session) {
        if (session == null) return "Never run";
        if (session.state() == RunState.LIVE) {
            int handlers = session.handlers();
            return "Live (" + handlers + (handlers == 1 ? " handler)" : " handlers)");
        }
        String name = session.state().name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * A row's parts, held rather than looked up.
     *
     * <p><b>Built in {@code createTemplate}, which is the pattern the tree rows already use</b> — and
     * here it is a per-frame cost rather than a correctness one. {@link #writeRow} runs for every
     * realised row on every frame, and it used to reach each part with a {@code querySelector}: two
     * selector walks per row, sixty times a second, to find children this class created itself and could
     * simply have kept.</p>
     *
     * <p>The tooltip is retained for a different and older reason: {@code Tooltip.attach} <b>adds</b> a
     * listener pair rather than replacing one, so attaching from {@code bind} — which runs for every
     * visible row on every scroll step — leaves the previous tooltip attached and showing, and the text
     * then never appears to update however correct the lookup is.</p>
     */
    private static final class Row {
        private final UIElement glyph = new UIElement();
        private final UIText name = new UIText("");
        private final UIText time = new UIText("");
        @Nullable private Tooltip tooltip;

        /** What the tooltip currently says, so an unchanged one is not rebuilt sixty times a second. */
        private String described = "";

        void describe(String text) {
            if (tooltip == null || text.equals(described)) return;
            described = text;
            tooltip.setText(text);
        }
    }

    private final Map<UIElement, Row> rows = new HashMap<>();

    @Nullable
    private Row rows(UIElement element) {
        return rows.get(element);
    }

    /** One row: a state glyph, the file's name, and how long it has been going. */
    private final class Rows implements ListRenderer<Resource> {

        @Override
        public UIElement createTemplate() {
            UIElement element = new UIElement();
            Row row = new Row();
            row.glyph.addClass(ROW_GLYPH_CLASS);
            row.glyph.setHitTest(false);
            row.name.addClass(ROW_NAME_CLASS);
            row.name.setHitTest(false);
            row.time.addClass(ROW_TIME_CLASS);
            row.time.setHitTest(false);

            element.addChild(row.glyph);
            element.addChild(row.name);
            element.addChild(row.time);
            row.tooltip = Tooltip.attach(element, "");
            rows.put(element, row);
            return element;
        }

        @Override
        public void bind(@Nullable Resource item, int index, UIElement template) {
            Row row = rows(template);
            if (row == null) return;

            if (item == null) {
                row.name.setText(ALL_LABEL);
                row.time.setText("");
                swapState(row.glyph, null);
                row.describe(ALL_TOOLTIP);
                return;
            }

            // THE NAME IS THE ONLY THING BIND OWNS. Everything else about a row changes without the row
            // set changing, so it is written by writeRow -- from here for the first fill, and from the
            // per-frame pass thereafter.
            row.name.setText(item.name());
            writeRow(index, template);
        }
    }
}
