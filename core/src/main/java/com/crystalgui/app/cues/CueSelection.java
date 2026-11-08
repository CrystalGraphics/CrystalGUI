package com.crystalgui.app.cues;

import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.cue.CgCueDefinition;
import com.crystalgraphics.cue.CgCueManager;
import com.crystalgraphics.cue.CgCueParameters;
import com.crystalgraphics.cue.CgCuePlaying;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.vfx.CgVfx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client's cue under audition: which cue is picked, how it is loading or playing, and where its hit stands.
 * {@link CueSelector} is one view of it; a HUD or a scene reads and drives the same one.
 *
 * <pre>{@code
 * CueSelection cues = CueSelection.client();
 * cues.at(0, 1.2, 0);                          // where hits play; unset, at the viewer
 * cues.play("mymod:hit/spark");                // loads it if need be, plays it the frame it is ready
 * String hud = cues.current() + " " + cues.age() + " / " + cues.duration() + " s";
 * cues.seek(0.5);                              // the hit at 0.5 s, as a scrub lands
 * }</pre>
 *
 * <ul>
 *   <li>{@link #placement} supplies each play's anchor and seed; {@link #namespaces} limits the kits listed;
 *       {@link #step} walks a kit's cues in tree order.</li>
 *   <li>{@link #play}, {@link #fire} and {@link #step} may be called from a view's thread: the load and the fire run on the world's. The rest runs on the thread that runs the client world's frame; it ticks in {@link CgTickGroup#BEGIN}, so a fire plays in that frame's {@code PLAYS}.</li>
 *   <li>The hit's duration is its longest sequence's length; a cue with no sequence has a duration of 0 and an age
 *       that keeps counting.</li>
 *   <li>A cue plays once it and the sequences it names are loaded; a kit loaded whole has them already.</li>
 * </ul>
 */
public final class CueSelection {

    /** Where the picked cue stands. */
    public enum State {
        /** Nothing picked. */
        IDLE,
        /** Picked and loading; it plays the frame it is ready. */
        LOADING,
        /** Fired; {@link #age()} counts. */
        PLAYING,
        /** Failed to load or to fire; {@link #failure()} says why. */
        FAILED
    }

    /** Fills the parameters a fire plays with: the position, an anchor, a seed. */
    @FunctionalInterface
    public interface Placement {
        /** Called on each fire with {@code params} reset; set at least {@link CgCueParameters#at}. */
        void place(CgCueParameters params);
    }

    /** The tree's order: by namespace, then by path part by part, a cue before the cues under it. */
    private static final Comparator<String> TREE = (a, b) -> {
        int c = a.substring(0, a.indexOf(':')).compareTo(b.substring(0, b.indexOf(':')));
        if (c != 0) return c;
        String[] x = a.substring(a.indexOf(':') + 1).split("/"), y = b.substring(b.indexOf(':') + 1).split("/");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            c = x[i].compareTo(y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    };

    private static CueSelection client;

    private final Map<String, CgAssetRef<CgCueDefinition>> refs = new HashMap<>();
    private final List<String> ids = new ArrayList<>();
    private final CgCueParameters params = new CgCueParameters();
    private final double[] at = new double[3];
    private final List<CgSequencePlayer> hit = new ArrayList<>();
    private final List<CgSequencePlayer> before = new ArrayList<>();
    private String current = "";
    private State state = State.IDLE;
    private String failure = "";
    private CgCueDefinition.Kind kind;
    private CgCuePlaying looping;
    private double age, duration;
    private final Set<String> namespaces = new LinkedHashSet<>();
    private Placement placement;
    private int fires;
    private final float[] normal = {0f, 1f, 0f};
    private final List<CgAssetRef<CgSequenceDefinition>> sequences = new ArrayList<>();
    private boolean collecting, placed, asked, sequencesAsked;
    private int version;

    private CueSelection() {
        CgWorld.client().schedule().add(CgTickGroup.BEGIN, "crystalgui:cue-selection", this::tick);
    }

    /** The client world's selection, made on first use. */
    public static synchronized CueSelection client() {
        if (client == null) client = new CueSelection();
        return client;
    }

    /** Re-reads the cues the asset registry holds. */
    public CueSelection refresh() {
        ids.clear();
        for (CgAssetEntry e : CgVfxAssets.instance().registry().entries(CgGameplayKinds.CUE)) {
            if (namespaces.isEmpty() || namespaces.contains(e.namespace())) ids.add(e.id());
        }
        ids.sort(TREE);
        version++;
        return this;
    }

    /** Lists only these namespaces' cues, in {@link #ids} and {@link #step}; none lists all. Re-reads the registry. */
    public CueSelection namespaces(String... only) {
        namespaces.clear();
        namespaces.addAll(Arrays.asList(only));
        return refresh();
    }

    /** Every listed cue, by asset id ({@code namespace:path/to/cue}), in tree order; read at the first call and by {@link #refresh}. */
    public List<String> ids() {
        if (ids.isEmpty()) refresh();
        return Collections.unmodifiableList(ids);
    }

    /** The picked cue's id; empty before one is. */
    public String current() {
        return current;
    }

    public State state() {
        return state;
    }

    /** Why {@link State#FAILED}; empty otherwise. */
    public String failure() {
        return failure;
    }

    /** The picked cue's kind once it is loaded; null before. */
    public CgCueDefinition.Kind kind() {
        return kind;
    }

    /** Seconds since the hit fired, on the world's clock, so a paused or slowed world holds or slows it; set by {@link #seek}. */
    public double age() {
        return age;
    }

    /** The hit's length in seconds: its longest sequence's. */
    public double duration() {
        return duration;
    }

    /** How many times a cue has fired since this selection was made. */
    public int fires() {
        return fires;
    }

    /** The sequences the hit plays, read-only. */
    public List<CgSequencePlayer> hit() {
        return Collections.unmodifiableList(hit);
    }

    /** Bumps whenever the list, the pick or the state changes: a view redraws when it differs from the last it saw. */
    public int version() {
        return version;
    }

    /**
     * Supplies each fire's parameters, for an anchor or a seed per play; overrides {@link #at} and {@link #normal}.
     * Null goes back to them.
     */
    public CueSelection placement(Placement placement) {
        this.placement = placement;
        return this;
    }

    /** Hits play at this point from now on, rather than at the viewer. */
    public CueSelection at(double x, double y, double z) {
        at[0] = x;
        at[1] = y;
        at[2] = z;
        placed = true;
        return this;
    }

    /** Hits face along this normal; up unless set. */
    public CueSelection normal(float x, float y, float z) {
        normal[0] = x;
        normal[1] = y;
        normal[2] = z;
        return this;
    }

    /** Picks {@code id}, loads it if need be, and fires it the frame it is ready. */
    public synchronized void play(String id) {
        current = id;
        asked = false;
        sequencesAsked = false;
        kind = null;
        failure = "";
        state = State.LOADING;
        version++;
    }

    /**
     * Picks the cue {@code by} places on from the picked one, in its namespace's tree order, wrapping at the ends,
     * and plays it where the last one did. With none picked, the first (or the last for a negative {@code by}).
     */
    public synchronized void step(int by) {
        List<String> all = ids();
        String ns = current.isEmpty() ? "" : current.substring(0, current.indexOf(':'));
        List<String> kit = new ArrayList<>();
        for (String id : all) if (ns.isEmpty() || id.startsWith(ns + ":")) kit.add(id);
        if (kit.isEmpty()) return;
        int at = kit.indexOf(current);
        int next = at < 0 ? (by < 0 ? kit.size() - 1 : 0) : Math.floorMod(at + by, kit.size());
        play(kit.get(next));
    }

    /** Fires the picked cue again, its last hit gone at once. */
    public synchronized void fire() {
        if (!current.isEmpty()) play(current);
    }

    /** The hit at {@code seconds}: each of its sequences sought there, and each system they play played again from its age. */
    public void seek(double seconds) {
        for (CgSequencePlayer p : hit) p.seek(seconds);
        age = seconds;
    }

    private synchronized void tick(float dt) {
        if (collecting) collect();
        if (state == State.LOADING) {
            // Declared and requested here, on the world's thread: a view may call play from its own
            CgAssetRef<CgCueDefinition> ref = refs.computeIfAbsent(current, i -> CgVfxAssets.instance().declare(CgGameplayKinds.CUE, i));
            if (!asked) {
                asked = true;
                if (!ref.ready()) ref.request(CgLoadPriority.MOVE);
            }
            if (ref.failure() != null) {
                fail(ref.failure());
            } else if (ref.ready() && sequencesReady(ref.loaded())) {
                start(ref.loaded());
            }
        } else if (state == State.PLAYING) {
            // A tick function gets the raw delta: each system scales it by the world's dilation itself
            age += dt * CgWorld.client().timeDilation();
        }
    }

    /**
     * Whether the sequences {@code cue} plays are loaded, asking for them once: a cue plays only what is loaded, so one
     * fired before its sequences are would play its colour regions and no systems. A kit loaded whole has them already.
     */
    private boolean sequencesReady(CgCueDefinition cue) {
        if (!sequencesAsked) {
            sequencesAsked = true;
            sequences.clear();
            for (CgCueDefinition.Effects phase : List.of(cue.burst(), cue.application(), cue.looping(), cue.recurring(), cue.removal())) {
                for (CgCueDefinition.SequenceEntry e : phase.sequences()) {
                    CgAssetRef<CgSequenceDefinition> seq = CgVfxAssets.instance().declare(CgSequenceKinds.SEQUENCE, e.sequence());
                    if (!seq.ready()) seq.request(CgLoadPriority.MOVE);
                    sequences.add(seq);
                }
            }
        }
        for (CgAssetRef<CgSequenceDefinition> seq : sequences) {
            if (seq.failure() != null) {
                fail(seq.id() + ": " + seq.failure());
                return false;
            }
            if (!seq.ready()) return false;
        }
        return true;
    }

    private void fail(String why) {
        state = State.FAILED;
        failure = current + ": " + why;
        version++;
    }

    private void start(CgCueDefinition cue) {
        if (cue.tag() == null) {
            fail("no tag to fire it by");
            return;
        }
        CgCueManager cues = CgCueManager.client();
        params.reset();
        if (placement != null) {
            placement.place(params);
        } else {
            if (!placed && !cues.viewerAt(at)) at[0] = at[1] = at[2] = 0;
            params.at(at[0], at[1], at[2]).normal(normal[0], normal[1], normal[2]);
        }
        // The last hit gone at once: a fire starts the hit again, with new seeds
        for (CgSequencePlayer p : hit) p.kill();
        CgVfx.world().clear();
        before.clear();
        CgSequencer sequencer = CgWorld.client().subsystem(CgSequencer.class);
        if (sequencer != null) before.addAll(sequencer.players());
        fires++;
        kind = cue.kind();
        age = 0;
        collecting = true;
        // A burst is executed once; a looping cue is added, and the last one stops as the next fires
        if (kind == CgCueDefinition.Kind.BURST) {
            cues.execute(cue.tag(), params);
        } else {
            if (looping != null) looping.remove();
            looping = cues.add(cue.tag(), params);
        }
        state = State.PLAYING;
        version++;
    }

    /** The hit's players, the ones its fire added, and its duration. The cue plays in the world's frame, so its own are the ones after it. */
    private void collect() {
        collecting = false;
        hit.clear();
        duration = 0;
        CgSequencer sequencer = CgWorld.client().subsystem(CgSequencer.class);
        if (sequencer == null) return;
        for (CgSequencePlayer p : sequencer.players()) {
            if (before.contains(p) || p.stopped()) continue;
            hit.add(p);
            CgSequenceDefinition d = p.definition();
            duration = Math.max(duration, d.seconds(d.end() - d.start()));
        }
    }
}
