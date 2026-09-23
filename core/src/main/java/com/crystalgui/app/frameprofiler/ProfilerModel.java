package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceAggregate;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.trace.CgTraceHints;
import com.crystalgraphics.trace.CgTraceSnapshot;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.trace.UiHints;
import com.crystalgui.widget.display.CounterTrack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What the profiler window is looking at: one snapshot, and a selection inside it.
 *
 * <pre>{@code
 * ProfilerModel model = new ProfilerModel();
 * model.onChanged.connect(this::redraw);
 * model.refresh();                 // take a snapshot
 * model.selectFrame(412);
 * List<CgTraceAggregate.Node> tree = model.treeOfSelection();
 * }</pre>
 *
 * <h3>A snapshot, never the live ring</h3>
 *
 * <p>Every band of the window is a function of this object and nothing else, and it changes only when
 * {@link #refresh()} is called. Reading the ring directly would let a value change between two rows of
 * the same table — a zone count that disagrees with the zones listed under it, which reads as a bug in
 * the aggregate rather than as a race in the viewer.</p>
 *
 * <h3>Freezing stops capture, it does not stop the window</h3>
 *
 * <p>{@link #setFrozen} turns the engine's recording off. The window keeps working on what it already
 * has, which is the only way an in-process viewer can hold still while somebody reads it; without it a
 * selection drifts off the end of the ring while being inspected.</p>
 *
 * <h3>An index here is an index into THIS snapshot</h3>
 *
 * <p>Not {@link CgFrameRecord#index()}, which is absolute and keeps counting. The strip draws position
 * and the keyboard steps position, so position is what a selection is; {@link #selectedFrame()} is how
 * the absolute number is recovered when one is wanted.</p>
 */
public final class ProfilerModel {

    /** Fired whenever the snapshot or the selection moved — one signal, because a redraw is one job. */
    public final Signal.Action onChanged = new Signal.Action();

    private CgTraceSnapshot snapshot = CgTrace.snapshot();

    private int selected = -1;
    private int rangeFrom = -1;
    private int rangeTo = -1;

    @Nullable
    private String selectedZone;

    /** What was recording before the freeze, or null while capture is live. */
    @Nullable
    private List<String> frozenChannels;

    /**
     * Takes a fresh snapshot.
     *
     * <p><b>Live</b>, the selection follows the newest completed frame, so an open window shows what
     * the application is doing now. <b>Paused</b>, it stays on the same FRAME — found again by its
     * absolute index, since keeping the position would slide the selection backwards by however many
     * frames arrived, and a window left open would drift through the run on its own.</p>
     */
    public void refresh() {
        try (CgTrace.Zone ignored = CgTrace.zone(VIEWER, REFRESH_ZONE)) {
            refreshNow();
        }
    }

    private static final int REFRESH_ZONE = CgTrace.name("viewer:refresh");

    private void refreshNow() {
        long was = selected >= 0 && selected < snapshot.frames().size()
                ? snapshot.frames().get(selected).index() : -1L;
        // FRAMES AND COUNTERS ONLY. A full snapshot copies every zone held, which at ten thousand frames
        // is millions of objects four times a second; zones are fetched for the selection alone.
        snapshot = CgTrace.frameSnapshot();
        cachedZonesKey = null;
        List<CgFrameRecord> frames = snapshot.frames();
        if (following || was < 0L) {
            selected = frames.size() - 1;
            rangeFrom = rangeTo = -1;
            selectedZone = null;
        } else {
            int found = -1;
            for (int i = 0; i < frames.size(); i++) {
                if (frames.get(i).index() == was) {
                    found = i;
                    break;
                }
            }
            // The paused frame fell off the end of the ring: there is nothing to hold, so show the
            // oldest that is left rather than jumping somewhere unrelated.
            selected = found >= 0 ? found : frames.isEmpty() ? -1 : 0;
            if (found < 0) rangeFrom = rangeTo = -1;
        }
        onChanged.emit();
    }

    // ── Live or paused ──────────────────────────────────────────────────────────────────────

    /** Whether the view follows the newest frame. Paused holds one snapshot still to be read. */
    private boolean following = true;

    public boolean isFollowing() {
        return following;
    }

    /**
     * Follows the newest frame, or holds the current one.
     *
     * <p>A separate question from recording: paused, the ring keeps filling behind a window that is not
     * redrawing, which is the only way to read one frame while the application keeps producing more.
     * Selecting anything is a pause — the window cannot both follow the newest frame and stay on the
     * one somebody clicked.</p>
     */
    public void setFollowing(boolean value) {
        if (following == value) return;
        following = value;
        if (following) {
            refresh();
        } else {
            onChanged.emit();
        }
    }

    /**
     * Selects the slowest frame and pauses on it — and, pressed again, the next slowest.
     *
     * <p>A hitch hunt is a walk down the worst frames, not a single jump: the slowest one in a ring is
     * almost always startup, real and never the one being looked for. So a repeat steps to the next
     * slowest, the way "find next" steps through matches.</p>
     *
     * <p>Frames recorded under the CURRENT channel set come first. One recorded before a channel was
     * switched on measured different work, and ranking it against the rest is ranking two runs.</p>
     */
    public void selectWorst() {
        List<CgFrameRecord> frames = snapshot.frames();
        if (frames.isEmpty()) return;
        int from = comparableFrom();
        if (from >= frames.size()) from = 0;
        List<Integer> order = new ArrayList<>();
        for (int i = from; i < frames.size(); i++) order.add(i);
        order.sort((a, b) -> Long.compare(frames.get(b).wallNanos(), frames.get(a).wallNanos()));

        int at = order.indexOf(selected);
        int next = order.get(at >= 0 && at + 1 < order.size() && steppingWorst ? at + 1 : 0);
        following = false;
        selected = next;
        rangeFrom = rangeTo = -1;
        selectedZone = null;
        steppingWorst = true;
        onChanged.emit();
    }

    /**
     * Whether the last selection came from {@link #selectWorst} — so the next press steps on rather than
     * restarting. Any other selection clears it: after a click elsewhere, "worst" means the worst again.
     */
    private boolean steppingWorst;

    private int worstIndex() {
        List<CgFrameRecord> frames = snapshot.frames();
        int worst = -1;
        long longest = -1L;
        for (int i = 0; i < frames.size(); i++) {
            long wall = frames.get(i).wallNanos();
            if (wall > longest) {
                longest = wall;
                worst = i;
            }
        }
        return worst;
    }

    public CgTraceSnapshot snapshot() {
        return snapshot;
    }

    public List<CgFrameRecord> frames() {
        return snapshot.frames();
    }

    public int frameCount() {
        return snapshot.frames().size();
    }

    // ── Capture ─────────────────────────────────────────────────────────────────────────────

    public boolean isFrozen() {
        return frozenChannels != null;
    }

    /**
     * Stops or resumes capture.
     *
     * <p>Freezing remembers the enabled set and clears the mask, so thawing restores exactly what was
     * recording rather than a default — a window that froze a narrow channel selection and thawed to
     * everything would flood the ring it was being used to read.</p>
     */
    public void setFrozen(boolean frozen) {
        if (frozen == isFrozen()) return;
        if (frozen) {
            frozenChannels = CgTrace.enabledNames();
            CgTrace.disableAll();
        } else {
            List<String> restore = frozenChannels;
            frozenChannels = null;
            if (restore != null) CgTrace.enableOnly(restore);
        }
        onChanged.emit();
    }

    /**
     * Whether any channel that produces data is on.
     *
     * <p>NOT {@code CgTrace.isRecording()}: the engine keeps its own {@code trace} channel on whenever
     * anything was ever enabled, to carry the mask-change markers, so the mask stays non-zero after
     * every real channel is off. Reading that as "recording" put Stop Capture on a window capturing
     * nothing.</p>
     */
    public boolean isCapturing() {
        for (String name : CgTrace.enabledNames()) {
            if (!CgTrace.isEngineOwn(name)) return true;
        }
        return false;
    }

    /**
     * The one recording button: stops, resumes what was stopped, or starts the defaults.
     *
     * <p>Stopping REMEMBERS the channel set rather than forgetting it, so a narrow selection made in
     * the channel menu survives being paused and resumed.</p>
     */
    public void toggleRecording() {
        if (isCapturing()) {
            setFrozen(true);
        } else if (CgTrace.stopReason() != null) {
            restartAfterStop();
        } else if (isFrozen()) {
            setFrozen(false);
        } else {
            setCapturing(true);
        }
    }

    /**
     * Why recording stopped by itself — a full keep-first ring, or the frames after a hitch — or null
     * while recording or when it was stopped by hand.
     */
    @Nullable
    public String stopReason() {
        return isCapturing() ? null : CgTrace.stopReason();
    }

    /**
     * Records again after the engine stopped by itself, with the channels it had.
     *
     * <p>A full keep-first ring is cleared first — it can take nothing more, so resuming without a clear
     * would stop again on the next frame. After a hitch the ring is kept: resuming carries on from it.</p>
     */
    private void restartAfterStop() {
        List<String> channels = CgTrace.stoppedChannels();
        if (CgTrace.isFull()) CgTrace.clear();
        frozenChannels = null;
        if (channels.isEmpty()) {
            for (String prefix : ProfilerSettings.channels()) CgTrace.enable(prefix);
        } else {
            CgTrace.enableOnly(channels);
        }
        setFollowing(true);
        refresh();
    }

    /**
     * Turns the default channels on, or everything off.
     *
     * <p>A window that opens with nothing recording shows nothing, and the button that fixes it has to
     * be in the window — sending somebody to a system property to make the tool produce output is how
     * a diagnostic goes unused.</p>
     */
    public void setCapturing(boolean capturing) {
        frozenChannels = null;
        if (capturing) {
            for (String prefix : ProfilerSettings.channels()) CgTrace.enable(prefix);
        } else {
            CgTrace.disableAll();
        }
        onChanged.emit();
    }

    // ── Channels ────────────────────────────────────────────────────────────────────────────

    /**
     * The name that carries a mask change.
     *
     * <p>Written by the engine itself, unconditionally, whenever the enabled set moves — so the
     * boundary is in the trace rather than something the window has to remember.</p>
     */
    public static final String MASK_MARKER = "trace:mask";

    /** Every channel registered SO FAR. A channel appears when its declaring class first loads. */
    public List<String> channelNames() {
        List<String> names = new ArrayList<>();
        for (CgTraceChannel channel : CgTrace.channels()) names.add(channel.name());
        return names;
    }

    public Set<String> enabledChannels() {
        return new LinkedHashSet<>(CgTrace.enabledNames());
    }

    /**
     * Replaces the enabled set wholesale.
     *
     * <p>Not a per-channel toggle, because the control edits a SET: applying one tick at a time would
     * write a mask marker per box and leave the strip striped with boundaries nobody asked for.</p>
     */
    public void setEnabledChannels(Set<String> names) {
        frozenChannels = null;
        List<String> chosen = names == null ? new ArrayList<>() : new ArrayList<>(names);
        // THE ENGINE'S OWN STAY AS THEY ARE: the menu does not list them, so a set written from it would
        // otherwise switch the viewer's own channel off whenever a box was ticked.
        for (String name : CgTrace.enabledNames()) {
            if (CgTrace.isEngineOwn(name) && !chosen.contains(name)) chosen.add(name);
        }
        CgTrace.enableOnly(chosen);
        onChanged.emit();
    }

    /**
     * The first frame recorded under the CURRENT mask, or 0 when the mask never moved in this ring.
     *
     * <p>Frames before it happened and are shown; what they are not is comparable with the frames
     * after, since a channel that stopped recording makes every total below it smaller for a reason
     * that has nothing to do with the application.</p>
     */
    public int comparableFrom() {
        long latest = Long.MIN_VALUE;
        for (CgTraceSnapshot.MarkerView marker : snapshot.markers()) {
            if (MASK_MARKER.equals(marker.name())) latest = Math.max(latest, marker.nanos());
        }
        if (latest == Long.MIN_VALUE) return 0;
        List<CgFrameRecord> frames = snapshot.frames();
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).beginNanos() >= latest) return i;
        }
        // The change is newer than every frame in the ring: nothing here is under the current mask.
        return frames.size();
    }

    // ── Counters ────────────────────────────────────────────────────────────────────────────

    /**
     * {@code frame} with its GPU figure, if one has landed since the snapshot was taken — the ring's
     * current record then, else {@code frame} itself.
     *
     * <p>A GPU figure lands frames after its frame, and a PAUSED window keeps one snapshot: without this
     * every frame it held that was still pending stayed pending however long it was looked at.</p>
     */
    public CgFrameRecord withGpu(CgFrameRecord frame) {
        if (frame.hasGpu()) return frame;
        CgFrameRecord now = CgTrace.frame(frame.index());
        return now != null && now.hasGpu() ? now : frame;
    }

    /**
     * Whether the selection holds a frame still waiting on a GPU figure that is on its way — what a
     * paused window keeps polling for.
     */
    /** Frames after its own within which a GPU figure has landed, measured at four; with room to spare. */
    private static final int GPU_LANDS_WITHIN = 16;

    public boolean selectionAwaitsGpu() {
        if (!CgGpuTrace.isMeasuring()) return false;
        List<CgFrameRecord> frames = snapshot.frames();
        int from = hasRange() ? rangeFrom : selected;
        int to = hasRange() ? rangeTo : selected;
        // A FIGURE LANDS WITHIN A HANDFUL OF FRAMES OR NEVER: one recorded with the channel off will not
        // arrive, and waiting on it would repaint a paused window four times a second for good.
        long givenUp = CgTrace.frameCount() - GPU_LANDS_WITHIN;
        for (int i = Math.max(0, from); i <= to && i < frames.size(); i++) {
            CgFrameRecord frame = frames.get(i);
            if (frame.index() >= givenUp && !withGpu(frame).hasGpu()) return true;
        }
        return false;
    }

    /** Each frame's absolute number, in the order {@link #counterSeries()} lays its values out. */
    public long[] frameIndices() {
        List<CgFrameRecord> frames = snapshot.frames();
        long[] out = new long[frames.size()];
        for (int i = 0; i < out.length; i++) out[i] = frames.get(i).index();
        return out;
    }

    /** The series holding each frame's GPU total, in nanoseconds. @see #counterSeries() */
    public static final String GPU_SERIES = "gpu";

    /** Whether a series holds nanoseconds: the GPU total, and every GPU zone's counter. */
    public static boolean isDuration(String series) {
        return GPU_SERIES.equals(series) || series.startsWith(CgGpuTrace.PREFIX);
    }

    /**
     * Every counter in the ring, as one value per frame, oldest first.
     *
     * <p>{@link CounterTrack#ABSENT} where a frame recorded none — a
     * counter nothing wrote did not measure zero, and a row that drew one would invent a reading.
     * The LAST value in a frame wins where a counter was written more than once, which is what a
     * counter means: the count at the end of the frame.</p>
     */
    public Map<String, long[]> counterSeries() {
        List<CgFrameRecord> frames = snapshot.frames();
        Map<String, long[]> series = new LinkedHashMap<>();
        if (frames.isEmpty()) return series;

        // THE FRAME'S GPU TOTAL, beside the per-zone figures it is the sum of — absent until it lands.
        long[] gpu = absentSeries(frames.size());
        boolean anyGpu = false;
        for (int i = 0; i < frames.size(); i++) {
            CgFrameRecord frame = withGpu(frames.get(i));
            if (!frame.hasGpu()) continue;
            gpu[i] = frame.gpuNanos();
            anyGpu = true;
        }
        if (anyGpu) series.put(GPU_SERIES, gpu);

        Map<Long, Integer> positionOf = new LinkedHashMap<>();
        for (int i = 0; i < frames.size(); i++) positionOf.put(frames.get(i).index(), i);

        for (CgTraceSnapshot.CounterView counter : snapshot.counters()) {
            Integer at = positionOf.get(counter.frameIndex());
            if (at == null) continue;
            long[] values = series.computeIfAbsent(counter.name(), name -> absentSeries(frames.size()));
            values[at] = counter.value();
        }
        return series;
    }

    private static long[] absentSeries(int length) {
        long[] values = new long[length];
        Arrays.fill(values, CounterTrack.ABSENT);
        return values;
    }

    // ── Selection ───────────────────────────────────────────────────────────────────────────

    public int selectedIndex() {
        return selected;
    }

    @Nullable
    public CgFrameRecord selectedFrame() {
        List<CgFrameRecord> frames = snapshot.frames();
        return selected >= 0 && selected < frames.size() ? frames.get(selected) : null;
    }

    public void selectFrame(int index) {
        steppingWorst = false;
        int wanted = Math.max(0, Math.min(frameCount() - 1, index));
        if (frameCount() == 0 || wanted == selected) return;
        selected = wanted;
        rangeFrom = rangeTo = -1;
        selectedZone = null;
        onChanged.emit();
    }

    /**
     * Selects the frame whose {@link CgFrameRecord#index()} is {@code frameIndex}, or the nearest one
     * still held — how a frame named somewhere else (the readout, a report) is opened here.
     */
    public void selectFrameIndex(long frameIndex) {
        List<CgFrameRecord> frames = snapshot.frames();
        int best = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < frames.size(); i++) {
            long distance = Math.abs(frames.get(i).index() - frameIndex);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        if (best >= 0) selectFrame(best);
    }

    /** Selects the frame that {@code nanos} falls in, or the nearest one held. */
    public void selectFrameAtNanos(long nanos) {
        List<CgFrameRecord> frames = snapshot.frames();
        int best = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < frames.size(); i++) {
            CgFrameRecord frame = frames.get(i);
            long distance = frame.contains(nanos) ? 0L
                    : Math.min(Math.abs(frame.beginNanos() - nanos), Math.abs(frame.endNanos() - nanos));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        if (best >= 0) selectFrame(best);
    }

    /** Steps by {@code by} frames — what an arrow key does. */
    public void stepFrame(int by) {
        selectFrame(selected < 0 ? 0 : selected + by);
    }

    /** Selects a span of frames; every table below re-aggregates over it. */
    public void selectRange(int from, int to) {
        steppingWorst = false;
        int low = Math.max(0, Math.min(from, to));
        int high = Math.min(frameCount() - 1, Math.max(from, to));
        if (low > high) return;
        rangeFrom = low;
        rangeTo = high;
        selected = low;
        selectedZone = null;
        onChanged.emit();
    }

    public boolean hasRange() {
        return rangeFrom >= 0 && rangeTo > rangeFrom;
    }

    public int rangeFrom() {
        return rangeFrom;
    }

    public int rangeTo() {
        return rangeTo;
    }

    @Nullable
    public String selectedZone() {
        return selectedZone;
    }

    public void selectZone(@Nullable String name) {
        if (Objects.equals(name, selectedZone)) return;
        selectedZone = name;
        onChanged.emit();
    }

    // ── What the bands read ─────────────────────────────────────────────────────────────────

    /**
     * Every zone in the selection — one frame, or the whole range.
     *
     * <p>A range answers the union, not a merge: two instances of {@code paint:tree} in two frames are
     * two zones, and rolling them into one would invent a call that lasted across a frame boundary.
     * {@link CgTraceAggregate#byCost} is what turns them into one row.</p>
     */
    public List<CgTraceSnapshot.ZoneView> zonesOfSelection() {
        List<CgFrameRecord> frames = snapshot.frames();
        if (frames.isEmpty() || selected < 0 || selected >= frames.size()) return List.of();
        CgFrameRecord first = frames.get(hasRange() ? rangeFrom : selected);
        CgFrameRecord last = frames.get(hasRange() ? Math.min(rangeTo, frames.size() - 1) : selected);
        String key = first.index() + ":" + last.index();
        // ONE FETCH PER SELECTION, not per reader: the chart, both tables and the header each ask.
        String wanted = key + ":" + showViewer;
        if (!wanted.equals(cachedZonesKey)) {
            List<CgTraceSnapshot.ZoneView> all = CgTrace.zonesBetween(first.beginNanos(), last.endNanos());
            List<CgTraceSnapshot.ZoneView> shown = new ArrayList<>(all.size());
            long viewer = 0L;
            long reach = Long.MIN_VALUE;
            for (CgTraceSnapshot.ZoneView zone : all) {
                boolean own = VIEWER.name().equals(zone.channel());
                // THE UNION, not the sum: the viewer's zones nest inside each other and inside the
                // frame's, so their recorded depth says nothing; ordered by start, overlap is one pass.
                if (own && !zone.isOpen()) {
                    long from = Math.max(zone.startNanos(), reach);
                    if (zone.endNanos() > from) viewer += zone.endNanos() - from;
                    reach = Math.max(reach, zone.endNanos());
                }
                if (!own || showViewer) shown.add(zone);
            }
            cachedZones = shown;
            cachedAllZones = all.size();
            cachedViewerNanos = viewer;
            cachedZonesKey = wanted;
        }
        return cachedZones;
    }

    /**
     * The channel the window's OWN work is recorded on. Hidden from every table and the chart unless
     * {@link #setShowViewer} says otherwise — and the hiding is stated, never silent.
     */
    public static final CgTraceChannel VIEWER = CgTrace.channel("trace.viewer");

    private boolean showViewer;
    private int cachedAllZones;
    private long cachedViewerNanos;

    public boolean isShowingViewer() {
        return showViewer;
    }

    public void setShowViewer(boolean value) {
        if (showViewer == value) return;
        showViewer = value;
        cachedHintsKey = null;
        onChanged.emit();
    }

    /** The viewer's own work in the selection, per frame, whether it is shown or not. */
    public long viewerNanosPerFrame() {
        zonesOfSelection();
        return cachedViewerNanos / Math.max(1, selectionFrameCount());
    }

    /** Every zone recorded in the selection, per frame, the viewer's own included. */
    public int zonesPerFrame() {
        zonesOfSelection();
        return cachedAllZones / Math.max(1, selectionFrameCount());
    }

    @Nullable
    private String cachedZonesKey;
    private List<CgTraceSnapshot.ZoneView> cachedZones = List.of();

    public List<CgTraceSnapshot.CounterView> countersOfSelection() {
        List<CgFrameRecord> frames = snapshot.frames();
        if (frames.isEmpty() || selected < 0) return List.of();
        if (!hasRange()) return snapshot.countersIn(frames.get(selected));
        List<CgTraceSnapshot.CounterView> all = new ArrayList<>();
        for (int i = rangeFrom; i <= rangeTo && i < frames.size(); i++) {
            all.addAll(snapshot.countersIn(frames.get(i)));
        }
        return all;
    }

    public List<CgTraceAggregate.Node> treeOfSelection() {
        return CgTraceAggregate.tree(zonesOfSelection());
    }

    public List<CgTraceAggregate.Stat> statsOfSelection() {
        return CgTraceAggregate.byCost(zonesOfSelection());
    }

    /** How many frames the tables below are speaking for. */
    public int selectionFrameCount() {
        return hasRange() ? rangeTo - rangeFrom + 1 : selected >= 0 ? 1 : 0;
    }

    // ── Hints ───────────────────────────────────────────────────────────────────────────────

    static {
        // THE RULES THE WINDOW RUNS are the ones the report runs; a window opened before UiTrace was ever
        // touched would otherwise accuse nothing.
        UiHints.install();
    }

    /**
     * A hint as the window lists it: for a range, each rule once, with how many frames it fired in and
     * the first of them.
     *
     * @param frames        frames in the selection it fired in; 1 for a single frame
     * @param firstPosition where in {@link #frames()} it first fired — what a click on the row selects
     */
    public record HintRow(CgTraceHints.Hint hint, int frames, int firstPosition) {
    }

    /** The most frames a range is judged over, so a whole-ring drag cannot stall the window. */
    public static final int MAX_HINT_FRAMES = 400;

    /**
     * What the selection is accused of — {@link UiHints}' rules over each frame in it.
     *
     * <p>Cached per selection: the chart, the tab and its title all ask, and a range runs every rule over
     * every frame in it.</p>
     */
    public List<HintRow> hintsOfSelection() {
        List<CgFrameRecord> frames = snapshot.frames();
        if (frames.isEmpty() || selected < 0 || selected >= frames.size()) return List.of();
        int from = hasRange() ? rangeFrom : selected;
        int to = hasRange() ? Math.min(rangeTo, frames.size() - 1) : selected;
        String key = frames.get(from).index() + ":" + frames.get(to).index();
        if (key.equals(cachedHintsKey)) return cachedHints;

        Map<String, HintRow> byCode = new LinkedHashMap<>();
        for (int i = from; i <= to && i < from + MAX_HINT_FRAMES; i++) {
            CgFrameRecord frame = frames.get(i);
            List<CgTraceAggregate.Node> tree = CgTraceAggregate.tree(withoutViewer(CgTrace.zonesIn(frame)));
            for (CgTraceHints.Hint hint : CgTraceHints.forFrame(withGpu(frame), tree, summed(snapshot.countersIn(frame)))) {
                HintRow was = byCode.get(hint.code());
                byCode.put(hint.code(), was == null ? new HintRow(hint, 1, i)
                        : new HintRow(was.hint(), was.frames() + 1, was.firstPosition()));
            }
        }
        cachedHints = List.copyOf(byCode.values());
        cachedHintsKey = key;
        return cachedHints;
    }

    /** {@code zones} less the viewer's own, unless they are being shown. */
    private List<CgTraceSnapshot.ZoneView> withoutViewer(List<CgTraceSnapshot.ZoneView> zones) {
        if (showViewer) return zones;
        List<CgTraceSnapshot.ZoneView> out = new ArrayList<>(zones.size());
        for (CgTraceSnapshot.ZoneView zone : zones) {
            if (!VIEWER.name().equals(zone.channel())) out.add(zone);
        }
        return out;
    }

    @Nullable
    private String cachedHintsKey;
    private List<HintRow> cachedHints = List.of();

    /** Counters summed per name — what a hint rule is handed. */
    static Map<String, Long> summed(List<CgTraceSnapshot.CounterView> counters) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (CgTraceSnapshot.CounterView counter : counters) out.merge(counter.name(), counter.value(), Long::sum);
        return out;
    }

    // ── Compare ─────────────────────────────────────────────────────────────────────────────

    /**
     * One side of a comparison: frames by their INDEX, not their place in the ring, so a side stays the
     * frames it was pinned to while the ring moves on.
     */
    public record Side(long fromIndex, long toIndex) {

        public long count() {
            return toIndex - fromIndex + 1;
        }

        public String label() {
            return fromIndex == toIndex ? "#" + fromIndex : "#" + fromIndex + " \u2013 #" + toIndex;
        }
    }

    /** A zone on both sides, as milliseconds per frame. {@code a} or {@code b} is 0 where it never ran. */
    public record CompareRow(String name, @Nullable String source, double aMillis, double bMillis) {

        public double delta() {
            return bMillis - aMillis;
        }
    }

    @Nullable
    private Side sideA;
    @Nullable
    private Side sideB;

    @Nullable
    public Side sideA() {
        return sideA;
    }

    @Nullable
    public Side sideB() {
        return sideB;
    }

    /** Pins the current selection — a frame or a range — as side A. */
    public void pinA() {
        sideA = selectionSide();
        onChanged.emit();
    }

    /** Pins the current selection as side B. */
    public void pinB() {
        sideB = selectionSide();
        onChanged.emit();
    }

    public void swapSides() {
        Side a = sideA;
        sideA = sideB;
        sideB = a;
        onChanged.emit();
    }

    @Nullable
    private Side selectionSide() {
        List<CgFrameRecord> frames = snapshot.frames();
        if (frames.isEmpty() || selected < 0 || selected >= frames.size()) return null;
        int from = hasRange() ? rangeFrom : selected;
        int to = hasRange() ? Math.min(rangeTo, frames.size() - 1) : selected;
        return new Side(frames.get(from).index(), frames.get(to).index());
    }

    /** The frames of {@code side} still in the ring; empty once they have been overwritten. */
    public List<CgFrameRecord> framesOf(@Nullable Side side) {
        if (side == null) return List.of();
        List<CgFrameRecord> out = new ArrayList<>();
        for (CgFrameRecord frame : snapshot.frames()) {
            if (frame.index() >= side.fromIndex() && frame.index() <= side.toIndex()) out.add(frame);
        }
        return out;
    }

    /** Mean wall time per frame on {@code side}, or -1 when none of it is held. */
    public double meanFrameMillis(@Nullable Side side) {
        List<CgFrameRecord> frames = framesOf(side);
        if (frames.isEmpty()) return -1d;
        long total = 0L;
        for (CgFrameRecord frame : frames) total += frame.wallNanos();
        return total / 1_000_000d / frames.size();
    }

    /**
     * Mean GPU time per frame on {@code side}, over the frames whose figure has landed; -1 when none
     * has — absent, never zero.
     */
    public double meanGpuMillis(@Nullable Side side) {
        long total = 0L;
        int timed = 0;
        for (CgFrameRecord held : framesOf(side)) {
            CgFrameRecord frame = withGpu(held);
            if (!frame.hasGpu()) continue;
            total += frame.gpuNanos();
            timed++;
        }
        return timed == 0 ? -1d : total / 1_000_000d / timed;
    }

    /**
     * Every zone on either side, as time per frame, biggest change first.
     *
     * <p>Per FRAME, not summed: two sides are rarely the same length, and totals over 40 frames against
     * 12 would read as a regression that is only a longer range. Inclusive time, since a zone's cost to
     * the frame is what it and everything under it took.</p>
     */
    public List<CompareRow> compare() {
        if (sideA == null || sideB == null) return List.of();
        Map<String, CgTraceAggregate.Stat> a = statsByName(framesOf(sideA));
        Map<String, CgTraceAggregate.Stat> b = statsByName(framesOf(sideB));
        int aFrames = Math.max(1, framesOf(sideA).size());
        int bFrames = Math.max(1, framesOf(sideB).size());
        Set<String> names = new LinkedHashSet<>(a.keySet());
        names.addAll(b.keySet());
        List<CompareRow> rows = new ArrayList<>();
        for (String name : names) {
            CgTraceAggregate.Stat left = a.get(name);
            CgTraceAggregate.Stat right = b.get(name);
            String source = left != null ? left.source() : right.source();
            rows.add(new CompareRow(name, source,
                    left == null ? 0d : left.totalMillis() / aFrames,
                    right == null ? 0d : right.totalMillis() / bFrames));
        }
        // GPU ZONES as rows of their own, per frame whose GPU figure landed: a frame still pending would
        // otherwise count as one that cost the GPU nothing.
        Map<String, Double> gpuA = gpuMillisByZone(framesOf(sideA));
        Map<String, Double> gpuB = gpuMillisByZone(framesOf(sideB));
        Set<String> gpuNames = new LinkedHashSet<>(gpuA.keySet());
        gpuNames.addAll(gpuB.keySet());
        for (String name : gpuNames) {
            rows.add(new CompareRow(name, GPU_SOURCE, gpuA.getOrDefault(name, 0d), gpuB.getOrDefault(name, 0d)));
        }
        rows.sort((x, y) -> Double.compare(Math.abs(y.delta()), Math.abs(x.delta())));
        return rows;
    }

    /** What a GPU zone's Compare row gives as its source: it has no line of Java behind it. */
    public static final String GPU_SOURCE = "GPU";

    private Map<String, Double> gpuMillisByZone(List<CgFrameRecord> frames) {
        Map<String, Double> out = new LinkedHashMap<>();
        int timed = 0;
        for (CgFrameRecord frame : frames) {
            if (!withGpu(frame).hasGpu()) continue;
            timed++;
            for (CgTraceSnapshot.CounterView counter : snapshot.countersIn(frame)) {
                if (counter.name().startsWith(CgGpuTrace.PREFIX)) {
                    out.merge(counter.name(), counter.value() / 1_000_000d, Double::sum);
                }
            }
        }
        if (timed > 1) {
            for (Map.Entry<String, Double> e : out.entrySet()) e.setValue(e.getValue() / timed);
        }
        return out;
    }

    private static Map<String, CgTraceAggregate.Stat> statsByName(List<CgFrameRecord> frames) {
        Map<String, CgTraceAggregate.Stat> out = new LinkedHashMap<>();
        if (frames.isEmpty()) return out;
        List<CgTraceSnapshot.ZoneView> zones = new ArrayList<>();
        for (CgTraceSnapshot.ZoneView zone : CgTrace.zonesBetween(frames.get(0).beginNanos(),
                frames.get(frames.size() - 1).endNanos())) {
            if (!VIEWER.name().equals(zone.channel())) zones.add(zone);
        }
        for (CgTraceAggregate.Stat stat : CgTraceAggregate.byCost(zones)) out.put(stat.name(), stat);
        return out;
    }
}
