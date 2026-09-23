package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceAggregate;
import com.crystalgraphics.trace.CgTraceLog;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.display.CounterTrack;
import com.crystalgui.widget.display.FrameScrollbar;
import com.crystalgui.widget.display.FrameSeriesTrack;
import com.crystalgui.widget.display.FrameStripTrack;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.text.UIText;
import dev.vfyjxf.taffy.style.FlexDirection;

import javax.annotation.Nullable;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The frame profiler — the whole window's body, and a function of one {@link ProfilerModel}.
 *
 * <pre>{@code
 * FrameProfilerPanel panel = new FrameProfilerPanel();
 * window.setContent(panel);
 * panel.model().selectWorst();
 * }</pre>
 *
 * <h3>Where the eye goes, top to bottom</h3>
 *
 * <ol>
 *   <li><b>The strip</b> — every frame in the ring on a fixed scale with the budget drawn on it. Only
 *       misses carry colour, so a hitch is visible before anything is read.</li>
 *   <li><b>The header</b> — the selected frame's time, large, and its CPU, GPU and GC beside it, small.</li>
 *   <li><b>The flame chart</b> — what that frame spent its time on, under a millisecond ruler. It takes
 *       all the height the window has to spare.</li>
 *   <li><b>The tabs</b> — the same frame as a sortable table, as a call tree, and its counters.</li>
 * </ol>
 *
 * <h3>Live, or paused on one frame</h3>
 *
 * <p>Live, the window follows the newest frame four times a second. <b>Any selection pauses it</b> —
 * a click on the strip, a zone, a range, an arrow key — because a window cannot both follow the newest
 * frame and stay on the one somebody chose. Recording carries on behind a paused window; Live resumes
 * following.</p>
 *
 * <h3>Nothing is rebuilt that has not changed</h3>
 *
 * <p>The flame chart and the tables are rebuilt only when the frame or range they show changes. A
 * rebuild on every refresh reset the chart's zoom four times a second and destroyed spans under the
 * pointer mid-hover — a window that measures dropped frames cannot afford to drop gestures.</p>
 */
public class FrameProfilerPanel extends UIElement {

    public static final Name NAME = Name.of("frameprofiler");

    public static final String TOOLBAR_CLASS = "__toolbar__";
    public static final String SPACER_CLASS = "__spacer__";
    public static final String STATS_CLASS = "__stats__";
    public static final String RECORD_CLASS = "__record__";
    public static final String RECORDING_CLASS = "__recording__";
    public static final String LIVE_CLASS = "__live__";
    public static final String FOLLOWING_CLASS = "__following__";
    public static final String HEADER_CLASS = "__frame-header__";
    public static final String CAPTION_CLASS = "__caption__";
    public static final String HEADLINE_CLASS = "__headline__";
    public static final String FIGURE_CLASS = "__figure__";
    public static final String ABSENT_CLASS = "__absent__";
    public static final String BADGE_CLASS = "__badge__";

    private final ProfilerModel model = new ProfilerModel();

    private final Button record = new Button("Record");
    private final Button live = new Button("Live");
    private final ChannelsControl channels = new ChannelsControl();
    private final UIText stats = new UIText("");

    private final FrameStripTrack strip = new FrameStripTrack();
    private final FrameScrollbar scrollbar = new FrameScrollbar(strip);
    private final UIElement header = new UIElement();
    private final FlameChart chart = new FlameChart();

    private final SplitView split = new SplitView();
    private UIElement toolbar;

    /** Built on first open. @see #toggleSettings */
    @Nullable
    private ProfilerSettingsPage settingsPage;

    /** Whether the settings page is showing — what the caption's gear is drawn pressed from. */
    public final Signal.Value<Boolean> onSettingsToggled = new Signal.Value<>();
    private final TabView tabs = new TabView();
    private final ZonesTab zones = new ZonesTab();
    private final CallTreeTab callers = new CallTreeTab();
    private final CountersTab counters = new CountersTab();
    private final HintsTab hints = new HintsTab();
    private final ChainsTab chains = new ChainsTab();
    private final CompareTab compare = new CompareTab(model);
    /** What the Compare tab was last built for — it re-reads only when a pinned side changes. */
    @Nullable
    private String shownCompare;

    /** What the chart and the tables were last built for — a rebuild happens only when this moves. */
    @Nullable
    private String shownKey;
    @Nullable
    private String shownZone;

    public FrameProfilerPanel() {
        super(NAME);
        refusePublicChildren();
        layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100f).heightPercent(100f));
        setFocusPolicy(FocusPolicy.CLICK);

        toolbar = buildToolbar();
        appendStructural(toolbar);
        appendStructural(strip);
        // THE WAY ALONG THE RING, under it: a thumb to drag, and ends to drag for a zoom.
        appendStructural(scrollbar);
        header.addClass(HEADER_CLASS);
        appendStructural(header);
        // A SPLIT THE READER OWNS, not a ratio the sheet fixes. Nesting depth varies by an order of
        // magnitude between frames, and only the person reading knows whether the chart or the table
        // deserves the room this time.
        split.setOrientation(SplitView.Orientation.VERTICAL);
        split.first(chart);
        split.second(buildTabs());
        split.setPercentage(58f);
        appendStructural(split);
        appendStructural(buildFooter());

        // EVERY SELECTION PAUSES, and pauses FIRST: the refresh a live window runs would otherwise
        // move the selection straight back to the newest frame on the next tick.
        // PAUSED ON THE PRESS, not the release: a live window re-reads the ring four times a second,
        // and a range dragged across a strip that is scrolling under the pointer lands on frames that
        // were not the ones under it when the drag began.
        strip.onGestureStart(() -> model.setFollowing(false));
        strip.onSelected(index -> {
            model.setFollowing(false);
            model.selectFrame(index);
        });
        strip.onRangeSelected(range -> {
            model.setFollowing(false);
            model.selectRange(range.from(), range.to());
        });
        // THE STRIP AND THE COUNTERS SHARE ONE VIEW, whichever of them was wheeled.
        strip.onViewChanged(() -> {
            counters.showView(strip.viewFrom(), strip.isZoomed() ? strip.visible() : 0d);
            // LOOKING AWAY FROM THE NEWEST FRAME PAUSES, as a selection does: a live window would drag
            // the view straight back to the end on its next refresh.
            if (model.isFollowing() && strip.isZoomed() && strip.viewFrom() + strip.visible() < strip.frames() - 1) {
                model.setFollowing(false);
            }
        });
        counters.onViewChanged(() -> {
            CounterTrack moved = counters.rows().isEmpty() ? null : counters.rows().get(0);
            if (moved != null) strip.showView(moved.viewFrom(), moved.isZoomed() ? moved.visible() : 0d);
        });
        // A HINT'S LINK GOES SOMEWHERE: a zone is selected and shown in its table, a counter's track is
        // opened, and a range row can take the reader to the first frame it fired in.
        hints.onZone(name -> {
            model.setFollowing(false);
            model.selectZone(name);
            tabs.selectTab(zonesTab);
        });
        hints.onCounter(name -> {
            tabs.selectTab(countersTab);
            counters.highlight(name);
        });
        chains.onStartSelected(nanos -> {
            model.setFollowing(false);
            model.selectFrameAtNanos(nanos);
        });
        hints.onFrame(position -> {
            model.setFollowing(false);
            model.selectFrame(position);
        });
        counters.onFrameSelected(index -> {
            model.setFollowing(false);
            model.selectFrame(index);
        });
        chart.onZoneSelected(span -> {
            model.setFollowing(false);
            model.selectZone(span.name());
        });
        callers.onZoneSelected(name -> {
            model.setFollowing(false);
            model.selectZone(name);
        });
        model.onChanged.connect(this::render);

        onKeyDown.attachListener((element, event) -> {
            if (handleKey(event)) event.stopPropagation();
        }, false, true);

        onConnected(() -> {
            model.refresh();
            // A SNAPSHOT ON A SLOW CLOCK, not every frame. Rendering from the live ring would let a
            // value change between two rows of the same table, and re-snapshotting sixty times a
            // second would make the window's own cost the loudest thing in the trace it is showing.
            UIDocument document = document();
            if (document != null) document.animation().every(this, this::tick);
        });
    }

    private float sinceRefresh;

    // ── The footer: what the window is not showing, and what tracing costs ─────────────────

    public static final String FOOTER_CLASS = "__footer__";

    private final UIElement footer = new UIElement();
    private final UIText footerText = new UIText("");
    private final Button viewerToggle = new Button("Show");

    private UIElement buildFooter() {
        footer.addClass(FOOTER_CLASS);
        footerText.addClass(CAPTION_CLASS);
        viewerToggle.attachListener(() -> model.setShowViewer(!model.isShowingViewer()));
        footer.append(footerText, viewerToggle);
        return footer;
    }

    public UIText footerText() {
        return footerText;
    }

    public Button viewerToggle() {
        return viewerToggle;
    }

    /**
     * A profiler inside the application it profiles costs something, and says what: its own work, hidden
     * or shown; what the instrumentation itself costs a frame; and anything the log writer dropped. The
     * two ways a trace can be quietly wrong are both on screen.
     */
    private void renderFooter() {
        StringBuilder text = new StringBuilder();
        text.append(model.isShowingViewer() ? "Showing" : "Hiding").append(" the viewer's own work: ")
                .append(shortMs(model.viewerNanosPerFrame())).append(" a frame (trace.viewer)");
        int zones = model.zonesPerFrame();
        text.append("  \u00b7  tracing \u2248 ").append(shortMicros(zones * zoneCostNanos()))
                .append(" a frame (").append(zones).append(" zones)");
        text.append("  \u00b7  ").append(gpuStatus());
        long dropped = CgTraceLog.dropped();
        if (dropped > 0L) text.append("  \u00b7  ").append(dropped).append(" log lines dropped");
        if (!text.toString().equals(footerText.getText())) footerText.setText(text.toString());
        String label = model.isShowingViewer() ? "Hide" : "Show";
        if (!label.equals(viewerToggle.getText())) viewerToggle.setText(label);
    }

    /**
     * What one enabled zone costs, measured once on this machine: two clock reads, which is what the
     * engine's own gate says a zone is, plus the array writes between them — estimated as the clock pair.
     * An ESTIMATE, and the footer says so with its "about".
     */
    private static long zoneCostNanos() {
        long cost = zoneCost;
        if (cost > 0L) return cost;
        long sink = 0L;
        long start = System.nanoTime();
        for (int i = 0; i < CLOCK_SAMPLES; i++) sink += System.nanoTime();
        long each = Math.max(1L, (System.nanoTime() - start) / CLOCK_SAMPLES);
        // KEPT, or the JIT may drop the reads being timed.
        clockSink = sink;
        zoneCost = each * 2L;
        return zoneCost;
    }

    private static final int CLOCK_SAMPLES = 20_000;
    private static long zoneCost;
    private static volatile long clockSink;

    private static String shortMicros(long nanos) {
        return nanos >= 1_000_000L ? String.format("%.2f ms", nanos / 1_000_000d)
                : String.format("%.0f \u00b5s", nanos / 1_000d);
    }

    /** How many frames the strip showed last render; fewer now means the recording was cleared. */
    private int shownFrames;

    /** The selection the strip last scrolled to. @see #render */
    private int revealedIndex = -1;

    private boolean tick(float deltaSeconds) {
        boolean following = model.isFollowing() && model.isCapturing();
        // PAUSED, ONLY THE GPU IS WAITED FOR: a held frame's figure lands frames after it, and the
        // snapshot is kept still on purpose, so the figures are read from the ring rather than the whole
        // snapshot taken again under somebody reading it.
        if (!following && !model.selectionAwaitsGpu()) return true;
        sinceRefresh += deltaSeconds;
        // A SETTING, and four times a second by default: fast enough that the strip visibly fills, slow
        // enough to read, and cheap enough that the viewer does not become the thing worth profiling.
        if (sinceRefresh < ProfilerSettings.refreshSeconds()) return true;
        sinceRefresh = 0f;
        if (following) {
            model.refresh();
        } else {
            render();
        }
        return true;
    }

    /**
     * Shows the settings page in place of the profiler, or the profiler again.
     *
     * <p>In place, not over: every band is hidden rather than removed, so the chart's zoom and the tables'
     * selection are where they were when the page closes. Recording carries on underneath.</p>
     */
    public void toggleSettings() {
        setSettingsOpen(!isSettingsOpen());
    }

    public boolean isSettingsOpen() {
        return settingsPage != null && settingsPage.isDisplayed();
    }

    public void setSettingsOpen(boolean open) {
        if (open == isSettingsOpen()) return;
        if (open && settingsPage == null) {
            settingsPage = new ProfilerSettingsPage();
            settingsPage.onDone(() -> setSettingsOpen(false));
            appendStructural(settingsPage);
        }
        toolbar.setDisplayed(!open);
        strip.setDisplayed(!open);
        scrollbar.setDisplayed(!open);
        header.setDisplayed(!open);
        split.setDisplayed(!open);
        footer.setDisplayed(!open);
        if (settingsPage != null) settingsPage.setDisplayed(open);
        if (!open) {
            // WHAT CHANGED WHILE IT WAS OPEN: the budget moves the strip's lines, a resize empties the ring.
            shownKey = null;
            model.refresh();
        }
        onSettingsToggled.emit(open);
    }

    @Nullable
    public ProfilerSettingsPage settingsPage() {
        return settingsPage;
    }

    public ProfilerModel model() {
        return model;
    }

    /** The parts a scripted run drives, and a test reaches past the chrome for. */
    public FrameStripTrack strip() {
        return strip;
    }

    public FrameScrollbar scrollbar() {
        return scrollbar;
    }

    public FlameChart chart() {
        return chart;
    }

    public TabView tabs() {
        return tabs;
    }

    public CountersTab counters() {
        return counters;
    }

    public CallTreeTab callTree() {
        return callers;
    }

    public ZonesTab zones() {
        return zones;
    }

    public Button recordButton() {
        return record;
    }

    public Button liveButton() {
        return live;
    }

    // ── Structure ───────────────────────────────────────────────────────────────────────────

    private UIElement buildToolbar() {
        UIElement bar = new UIElement();
        bar.addClass(TOOLBAR_CLASS);

        // RECORD FIRST, because it is the answer to an empty window: nothing records until a channel
        // is on, and sending somebody to a system property to make a diagnostic produce output is how
        // a diagnostic goes unused.
        record.addClass(RECORD_CLASS);
        record.attachListener(model::toggleRecording);
        bar.append(record);

        live.addClass(LIVE_CLASS);
        live.attachListener(() -> model.setFollowing(!model.isFollowing()));
        bar.append(live);

        Button worst = new Button("Worst frame");
        worst.attachListener(model::selectWorst);
        bar.append(worst);

        // THE CHANNEL MASK. Ticking `crystalgraphics` takes everything beneath it, because the names
        // are hierarchical — so the common gesture really is `CrystalGraphics | CrystalGUI` while a
        // mod's own channel stays separately reachable in the same list.
        channels.bindTo(model);
        bar.append(channels);

        UIElement spacer = new UIElement();
        spacer.addClass(SPACER_CLASS);
        bar.append(spacer);

        stats.addClass(STATS_CLASS);
        bar.append(stats);
        return bar;
    }

    private UIElement buildTabs() {
        // HINTS FIRST: it is the answer, and the other tabs are the working for it.
        hintsTab = tabs.addTab("Hints");
        hintsTab.content().append(hints);
        zonesTab = tabs.addTab("Zones");
        zonesTab.content().append(zones);
        callTreeTab = tabs.addTab("Call tree");
        callTreeTab.content().append(callers);
        countersTab = tabs.addTab("Counters");
        countersTab.content().append(counters);
        chainsTab = tabs.addTab("Chains");
        chainsTab.content().append(chains);
        compareTab = tabs.addTab("Compare");
        compareTab.content().append(compare);
        return tabs;
    }

    private Tab hintsTab;
    private Tab chainsTab;
    private Tab compareTab;

    public Tab chainsTab() {
        return chainsTab;
    }

    public ChainsTab chains() {
        return chains;
    }

    public Tab hintsTab() {
        return hintsTab;
    }

    public Tab compareTab() {
        return compareTab;
    }

    public HintsTab hints() {
        return hints;
    }

    public CompareTab compare() {
        return compare;
    }

    private Tab zonesTab;
    private Tab callTreeTab;
    private Tab countersTab;

    public Tab zonesTab() {
        return zonesTab;
    }

    public Tab callTreeTab() {
        return callTreeTab;
    }

    public Tab countersTab() {
        return countersTab;
    }

    // ── Keyboard ────────────────────────────────────────────────────────────────────────────

    private boolean handleKey(KeyboardEvent.Down event) {
        switch (event.getKeyCode()) {
            case CgKeyCodes.KEY_LEFT -> step(-1);
            case CgKeyCodes.KEY_RIGHT -> step(1);
            case CgKeyCodes.KEY_PRIOR -> step(-10);
            case CgKeyCodes.KEY_NEXT -> step(10);
            case CgKeyCodes.KEY_HOME -> {
                model.setFollowing(false);
                model.selectFrame(0);
            }
            case CgKeyCodes.KEY_END -> {
                model.setFollowing(false);
                model.selectFrame(model.frameCount() - 1);
            }
            case CgKeyCodes.KEY_F -> chart.fit();
            // THE WHOLE RING across the strip again, after a wheel zoom.
            case CgKeyCodes.KEY_A -> strip.showWhole();
            case CgKeyCodes.KEY_W -> model.selectWorst();
            case CgKeyCodes.KEY_SPACE -> model.setFollowing(!model.isFollowing());
            default -> {
                return false;
            }
        }
        return true;
    }

    private void step(int by) {
        model.setFollowing(false);
        model.stepFrame(by);
    }

    // ── Rendering the whole window from the model ───────────────────────────────────────────

    private void render() {
        // THE VIEWER'S OWN WORK, on its own channel: recorded exactly while anything else is, so it is in
        // the ring beside what it measures -- and hidden from every view unless asked for, which the
        // footer says.
        boolean viewer = model.isCapturing();
        if (CgTrace.isEnabled(ProfilerModel.VIEWER) != viewer) CgTrace.setEnabled(ProfilerModel.VIEWER, viewer);
        try (CgTrace.Zone ignored = CgTrace.zone(ProfilerModel.VIEWER, RENDER_ZONE)) {
            renderNow();
        }
    }

    private static final int RENDER_ZONE = CgTrace.name("viewer:render");

    private void renderNow() {
        List<CgFrameRecord> frames = model.frames();
        long budget = budgetNanos();

        long[] wall = new long[frames.size()];
        boolean[] gc = new boolean[frames.size()];
        FrameStripTrack.Health[] health = new FrameStripTrack.Health[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            wall[i] = frames.get(i).wallNanos();
            gc[i] = frames.get(i).hadGc();
            health[i] = wall[i] > budget * 2L ? FrameStripTrack.Health.BAD
                    : wall[i] > budget ? FrameStripTrack.Health.WARN
                    : FrameStripTrack.Health.GOOD;
        }
        strip.setBudgetNanos(budget);
        // A NEW RECORDING, whether cleared by a resize or by Record again, opens on the newest frames like
        // the first one did -- not on wherever the last one had been scrolled to.
        if (frames.size() < shownFrames) strip.resetView();
        shownFrames = frames.size();
        strip.setFrames(wall, health);
        strip.setGcFrames(gc);
        // WHERE THE KEPT START ENDS AND THE NEWEST BEGIN: the frames between were not kept, and a strip
        // drawn edge to edge across the join would read as consecutive frames.
        int gapAt = -1;
        for (int i = 1; i < frames.size(); i++) {
            if (frames.get(i).index() != frames.get(i - 1).index() + 1) {
                gapAt = i;
                break;
            }
        }
        strip.setGap(gapAt, gapAt < 0 ? 0L : frames.get(gapAt).index() - frames.get(gapAt - 1).index() - 1);
        strip.showSelected(model.selectedIndex());
        // A ZOOMED STRIP KEEPS ITS FRAMES IN SIGHT: live, the newest stays at the right edge; paused, a
        // selection made off-screen (a key, Worst frame, Home) is scrolled to.
        if (model.isFollowing()) {
            strip.followEnd();
        } else if (model.selectedIndex() != revealedIndex) {
            strip.reveal(model.selectedIndex());
        }
        revealedIndex = model.selectedIndex();
        strip.showRange(model.hasRange()
                ? new FrameSeriesTrack.Range(model.rangeFrom(), model.rangeTo()) : null);
        int comparable = model.comparableFrom();
        strip.setComparableFrom(comparable);

        renderToolbar(wall, budget);
        channels.readBack();

        CgFrameRecord frame = model.selectedFrame();
        renderHeader(frame);

        // THE CHART AND THE TABLES ONLY WHEN WHAT THEY SHOW HAS MOVED. See the class note.
        String key = frame == null ? null
                : frame.index() + ":" + model.rangeFrom() + ":" + model.rangeTo();
        if (!Objects.equals(key, shownKey)) {
            shownKey = key;
            if (frame != null) {
                // A RANGE SPANS THE RANGE: every frame's zones on one axis from the first frame's start
                // to the last one's end. Spanning only the first frame put every other frame's zones
                // off the right edge, so a range looked exactly like a single frame.
                long from = frame.beginNanos();
                long to = frame.endNanos();
                long[] boundaries = new long[0];
                if (model.hasRange()) {
                    from = frames.get(model.rangeFrom()).beginNanos();
                    to = frames.get(model.rangeTo()).endNanos();
                    boundaries = new long[model.rangeTo() - model.rangeFrom()];
                    for (int i = 0; i < boundaries.length; i++) {
                        boundaries[i] = frames.get(model.rangeFrom() + 1 + i).beginNanos();
                    }
                }
                chart.show(model.zonesOfSelection(), from, to, frameThread(), boundaries);
            } else {
                chart.show(List.of(), 0L, 1L, null, new long[0]);
            }
            shownZone = null;
            refreshTables();
        } else if (!Objects.equals(model.selectedZone(), shownZone)) {
            refreshTables();
        }
        counters.show(model.counterSeries(), model.frameIndices(), model.selectedIndex(), comparable);
        refreshCompare();
        chains.show(model.snapshot().spans());
        renderFooter();
        counters.showView(strip.viewFrom(), strip.isZoomed() ? strip.visible() : 0d);
    }

    private void refreshTables() {
        shownZone = model.selectedZone();
        zones.show(model.statsOfSelection(), shownZone, selectionWallNanos());
        callers.show(model.treeOfSelection(), shownZone);
        List<ProfilerModel.HintRow> found = model.hintsOfSelection();
        hints.show(found, model.selectionFrameCount());
        // THE COUNT ON THE TAB, so a hint is seen from whichever tab is open.
        hintsTab.setText(found.isEmpty() ? "Hints" : "Hints (" + found.size() + ")");
    }

    /** Rebuilds Compare when a pinned side, or how much of it the ring still holds, has changed. */
    private void refreshCompare() {
        String key = model.sideA() + "|" + model.sideB() + "|" + model.framesOf(model.sideA()).size()
                + "|" + model.framesOf(model.sideB()).size();
        if (key.equals(shownCompare)) return;
        shownCompare = key;
        compare.show();
    }

    /** The wall time the tables' percentages are OF: the frame, or the whole range. */
    private long selectionWallNanos() {
        List<CgFrameRecord> frames = model.frames();
        if (model.selectedFrame() == null) return 0L;
        if (!model.hasRange()) return model.selectedFrame().wallNanos();
        long total = 0L;
        for (int i = model.rangeFrom(); i <= model.rangeTo(); i++) total += frames.get(i).wallNanos();
        return total;
    }

    private long budgetNanos() {
        return ProfilerSettings.budgetNanos();
    }

    @Nullable
    private String frameThread() {
        Thread thread = CgTrace.frameThread();
        return thread == null ? null : thread.getName();
    }

    private void renderToolbar(long[] wall, long budget) {
        boolean recording = model.isCapturing();
        String stopped = model.stopReason();
        record.setText(recording ? "Recording" : stopped != null ? "Record again"
                : model.isFrozen() ? "Resume" : "Record");
        toggleClass(record, RECORDING_CLASS, recording);

        // A TOGGLE THAT IS LIT, not a label that flips: "Paused" on a button read as an instruction to
        // pause. The header's badge says which state the window is in; this says how to change it.
        toggleClass(live, FOLLOWING_CLASS, model.isFollowing());

        // THE RING AT A GLANCE, on the right where it never moves: how many frames, what a typical
        // one cost, what the slow tail cost, and how many missed.
        if (wall.length == 0) {
            stats.setText(recording ? "waiting for a frame" : "not recording");
            return;
        }
        long[] sorted = Arrays.copyOf(wall, wall.length);
        Arrays.sort(sorted);
        int over = 0;
        for (long each : wall) {
            if (each > budget) over++;
        }
        StringBuilder text = new StringBuilder();
        // WHY IT STOPPED BY ITSELF, before anything else: a ring that stopped filling looks exactly like
        // an application that stopped producing frames.
        if (stopped != null) text.append("Stopped: ").append(stopped).append("  ·  ");
        // DROPPED RECORDS FIRST, and said: a silently short trace is the one thing a profiler may not
        // show, and on the right of a line that ellipsises it would be the first thing cut.
        long dropped = model.snapshot().droppedZones();
        if (dropped > 0L) text.append(dropped).append(" dropped  \u00b7  ");
        // WHAT MISSED FIRST: it is the one number here anybody acts on, and on the right of a line that
        // ellipsises it was the part cut off.
        // THE TAIL BEFORE THE MIDDLE: p95 is what a hitch hunt is about, and p50 is the part to lose
        // when the line runs out of room.
        // GC NAMED BESIDE THE MISSES, which is what the violet ticks under the strip are.
        int collected = 0;
        for (CgFrameRecord frame : model.frames()) {
            if (frame.hadGc()) collected++;
        }
        text.append(over).append(" slow of ").append(wall.length);
        // A COUNT OF FRAMES, said as one: "GC in 4" read as "a collection in four frames' time".
        if (collected > 0) text.append("  \u00b7  ").append(collected).append(collected == 1 ? " frame" : " frames")
                .append(" had a GC");
        text.append("  \u00b7  p95 ").append(shortMs(sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95))]))
                .append("  \u00b7  p50 ").append(shortMs(sorted[sorted.length / 2]));
        stats.setText(text.toString());
    }

    private void renderHeader(@Nullable CgFrameRecord frame) {
        header.removeAll();
        if (frame == null) {
            // TWO DIFFERENT ABSENCES, and saying which is the whole value of the line: recording off
            // is something to press a button about, recording on with no frames is something to go and
            // use the application about.
            header.append(absent(model.isCapturing()
                    ? "Recording — no frame has completed yet."
                    : "Not recording. Press Record to capture CrystalGraphics and CrystalGUI."));
            return;
        }
        if (model.hasRange()) {
            renderRangeHeader();
            return;
        } else {
            header.append(caption("Frame"));
            header.append(figure("#" + frame.index()));
            header.append(headline(ms(frame.wallNanos())));
            // WHEN, against the process: frame #0 is the first frame RECORDED, and whether that was the
            // program's first frame or one an hour in is exactly what this answers.
            header.append(caption("at"));
            header.append(figure(sinceLaunch(frame.beginNanos())));
        }
        header.append(caption("CPU"));
        header.append(frame.hasCpu() ? figure(String.format("%.2f ms", frame.cpuMillis())) : absent("—"));
        // ABSENT IS NOT ZERO. A GPU timer resolves one to three frames late, and printing 0.00 ms
        // would read as "the GPU did nothing".
        header.append(caption("GPU"));
        CgFrameRecord timed = model.withGpu(frame);
        header.append(timed.hasGpu() ? figure(String.format("%.2f ms", timed.gpuMillis())) : absent(gpuAbsence()));
        // LIVE ALWAYS SHOWS THE NEWEST FRAME, whose figure is always still on its way — so beside it,
        // the latest one that has landed, or the header never shows a GPU number while following.
        CgFrameRecord landed = timed.hasGpu() ? null : latestWithGpu();
        if (landed != null) {
            header.append(caption("last"));
            header.append(figure(String.format("%.2f ms (#%d)", landed.gpuMillis(), landed.index())));
        }
        if (frame.hadGc()) {
            header.append(caption("GC"));
            header.append(figure(frame.gcSummary()));
        }
        // TIME NO ZONE RECORDED, said in the header rather than left to subtraction. A 144 ms frame whose
        // zones cover 5 ms opens with a chart fitted to those 5 ms and looks like any other frame; the
        // answer to "why was this one slow" is that almost none of it was spent in anything measured,
        // and that is exactly the thing a picture of the measured part cannot show.
        long outside = frame.wallNanos() - coveredNanos(frame);
        if (outside > 1_000_000L) {
            header.append(caption("outside zones"));
            UIElement untracked = figure(ms(outside));
            if (outside * 2L > frame.wallNanos()) untracked.addClass(MOSTLY_CLASS);
            header.append(untracked);
        }
        if (frame.leakedZones() > 0) header.append(absent(frame.leakedZones() + " zones left open"));

        UIElement spacer = new UIElement();
        spacer.addClass(SPACER_CLASS);
        header.append(spacer);

        header.append(stateBadge());
    }

    /** LIVE, PAUSED, or STOPPED when the engine stopped by itself — the toolbar says why. */
    private UIText stateBadge() {
        boolean stopped = model.stopReason() != null;
        UIText badge = new UIText(stopped ? "STOPPED" : model.isFollowing() ? "LIVE" : "PAUSED");
        badge.addClass(BADGE_CLASS);
        if (!stopped && model.isFollowing()) badge.addClass(FOLLOWING_CLASS);
        return badge;
    }

    /** The JVM's start on the {@link System#nanoTime} clock the frames are stamped with. */
    private static final long PROCESS_START_NANOS =
            System.nanoTime() - ManagementFactory.getRuntimeMXBean().getUptime() * 1_000_000L;

    /** {@code 0.84 s}, {@code 12.4 s}, {@code 3 min 20 s} after the process started. */
    static String sinceLaunch(long nanos) {
        double seconds = Math.max(0L, nanos - PROCESS_START_NANOS) / 1_000_000_000d;
        if (seconds < 10d) return String.format("%.2f s", seconds);
        if (seconds < 60d) return String.format("%.1f s", seconds);
        long whole = (long) seconds;
        return whole / 60 + " min " + whole % 60 + " s";
    }

    /**
     * A range's header: what a typical frame in it cost, the worst, and how many missed.
     *
     * <p>The AVERAGE is the headline because it is the number a range is dragged out to get — "what
     * does this stretch cost per frame". One frame's CPU figure beside it, which the single-frame
     * header shows, would be the first frame's and read as the range's.</p>
     */
    /**
     * How much of {@code frame} the frame thread's outermost zones cover — their union, not their sum,
     * so two overlapping roots are not counted twice.
     */
    private long coveredNanos(CgFrameRecord frame) {
        String thread = frameThread();
        long covered = 0L;
        long reach = Long.MIN_VALUE;
        List<CgTraceAggregate.Node> roots = new ArrayList<>();
        for (CgTraceAggregate.Node root : model.treeOfSelection()) {
            if (thread == null || thread.equals(root.thread())) roots.add(root);
        }
        roots.sort(Comparator.comparingLong(CgTraceAggregate.Node::startNanos));
        for (CgTraceAggregate.Node root : roots) {
            long start = Math.max(root.startNanos(), Math.max(reach, frame.beginNanos()));
            long end = Math.min(root.endNanos(), frame.endNanos());
            if (end > start) covered += end - start;
            reach = Math.max(reach, root.endNanos());
        }
        return covered;
    }

    /** On the "outside zones" figure when that time is most of the frame. */
    public static final String MOSTLY_CLASS = "__mostly__";

    private void renderRangeHeader() {
        List<CgFrameRecord> frames = model.frames();
        long total = 0L;
        long worst = 0L;
        int over = 0;
        long budget = budgetNanos();
        for (int i = model.rangeFrom(); i <= model.rangeTo(); i++) {
            long wall = frames.get(i).wallNanos();
            total += wall;
            worst = Math.max(worst, wall);
            if (wall > budget) over++;
        }
        int count = model.selectionFrameCount();
        header.append(caption("Frames"));
        header.append(figure("#" + frames.get(model.rangeFrom()).index() + " \u2013 #"
                + frames.get(model.rangeTo()).index()));
        header.append(caption("avg"));
        header.append(headline(ms(total / Math.max(1, count))));
        header.append(caption("worst"));
        header.append(figure(ms(worst)));
        header.append(caption("over budget"));
        header.append(figure(over + " of " + count));

        UIElement spacer = new UIElement();
        spacer.addClass(SPACER_CLASS);
        header.append(spacer);
        header.append(stateBadge());
    }

    private static String shortMs(long nanos) {
        return String.format("%.1f ms", nanos / 1_000_000d);
    }

    private static String ms(long nanos) {
        double millis = nanos / 1_000_000d;
        return millis >= 100d ? String.format("%.0f ms", millis) : String.format("%.2f ms", millis);
    }

    private static void toggleClass(UIElement element, String name, boolean on) {
        if (on) element.addClass(name);
        else element.removeClass(name);
    }

    private static UIElement caption(String text) {
        UIText element = new UIText(text);
        element.addClass(CAPTION_CLASS);
        return element;
    }

    private static UIElement headline(String text) {
        UIText element = new UIText(text);
        element.addClass(HEADLINE_CLASS);
        return element;
    }

    private static UIElement figure(String text) {
        UIText element = new UIText(text);
        element.addClass(FIGURE_CLASS);
        return element;
    }

    /** The newest frame whose GPU figure has landed, or null. */
    @Nullable
    private CgFrameRecord latestWithGpu() {
        List<CgFrameRecord> frames = model.snapshot().frames();
        for (int i = frames.size() - 1; i >= 0; i--) {
            CgFrameRecord frame = model.withGpu(frames.get(i));
            if (frame.hasGpu()) return frame;
        }
        return null;
    }

    /** Whether GPU time is being measured at all — the one thing no single frame's header can say. */
    private static String gpuStatus() {
        if (CgGpuTrace.support() == CgGpuTrace.Support.UNSUPPORTED) return "GPU: no timer queries on this context";
        if (!CgGpuTrace.isMeasuring()) return "GPU: not recorded (the gpu channel is off)";
        if (CgGpuTrace.support() == CgGpuTrace.Support.UNKNOWN) return "GPU: on, nothing timed yet";
        return "GPU: timed, each figure lands a few frames late";
    }

    /** Why a frame has no GPU figure: never zero, and never "pending" when nothing is on its way. */
    private static String gpuAbsence() {
        if (CgGpuTrace.support() == CgGpuTrace.Support.UNSUPPORTED) return "no timer queries";
        return CgGpuTrace.isMeasuring() ? "pending" : "not recorded";
    }

    private static UIElement absent(String text) {
        UIText element = new UIText(text);
        element.addClass(ABSENT_CLASS);
        return element;
    }
}
