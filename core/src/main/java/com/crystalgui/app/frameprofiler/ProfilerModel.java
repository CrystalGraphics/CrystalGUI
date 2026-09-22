package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceAggregate;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.trace.CgTraceSnapshot;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.trace.UiTrace;
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
        long was = selected >= 0 && selected < snapshot.frames().size()
                ? snapshot.frames().get(selected).index() : -1L;
        snapshot = CgTrace.snapshot();
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
     * The two top-level channels a "Capture" button turns on.
     *
     * <p>Named as PREFIXES, so ticking one takes everything beneath it — the engine's hierarchical
     * names are what make {@code CrystalGraphics | CrystalGUI} the common gesture while a mod's own
     * channel stays separately reachable.</p>
     *
     * <p><b>Not {@code crystalgui.blame}.</b> It walks a stack on every invalidation, which slows the
     * very frames being measured; it is asked for by name, from the channel menu, when it is wanted.</p>
     */
    public static final List<String> DEFAULT_CHANNELS =
            List.of("crystalgraphics", UiTrace.FRAME.name(), UiTrace.FLOW.name());

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
            if (!name.equals(CgTrace.TRACE.name())) return true;
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
        } else if (isFrozen()) {
            setFrozen(false);
        } else {
            setCapturing(true);
        }
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
            for (String prefix : DEFAULT_CHANNELS) CgTrace.enable(prefix);
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
        CgTrace.enableOnly(names == null ? List.of() : new ArrayList<>(names));
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
        if (frames.isEmpty() || selected < 0) return List.of();
        if (!hasRange()) return snapshot.zonesIn(frames.get(selected));
        List<CgTraceSnapshot.ZoneView> all = new ArrayList<>();
        for (int i = rangeFrom; i <= rangeTo && i < frames.size(); i++) {
            all.addAll(snapshot.zonesIn(frames.get(i)));
        }
        return all;
    }

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
}
