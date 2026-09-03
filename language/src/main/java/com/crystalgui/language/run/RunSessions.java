package com.crystalgui.language.run;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.run.console.RunConsole;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Which scripts are live, and in what state — the rail beside the console, and the source the running
 * indicator reads.
 *
 * <h3>Keyed by the file, and that is not a simplification</h3>
 *
 * <p>M7's own exit criterion is that re-running a script <b>replaces</b> its loader with nothing pinning
 * the old one, so there is exactly one live instance per script file at any moment. File ↔ instance is
 * one-to-one, which is what makes a file-keyed map correct here rather than a lossy summary of several
 * runs — and what lets the indicator be an ordinary {@code FileDecorationProvider} instead of needing a
 * notion of "which run" the decoration is about.</p>
 *
 * <p><b>If that ever stops being true</b> — if two instances of one script can be live at once — this
 * map is the thing that breaks first, and it breaks quietly: the second run would overwrite the first's
 * state and the rail would show one entry for two instances.</p>
 *
 * <h3>What the mark means</h3>
 *
 * <p>Neither reference marks a file as running: IntelliJ marks the run tab, VS Code marks the terminal,
 * and in both a run is a process rather than a file. The trap that avoids is real here — <b>edit a
 * script while it is live and the file's text is no longer what is running</b> — so the state recorded
 * here means "this file's compiled instance is live", never "this text is running". A workspace that
 * wants to say the stronger thing has to compare against what was last compiled, which is a separate
 * fact this class deliberately does not hold.</p>
 *
 * <h3>Threading</h3>
 *
 * <p>Written from whatever thread a script starts or stops on and read on the UI thread, so every member
 * is synchronized and the queries answer snapshots. Same reasoning as {@link RunConsole}.</p>
 */
public final class RunSessions {

    /**
     * Fired with the script whose state changed. <b>Never null</b> — every emitter names one.
     *
     * <p>It used to say "or null when the whole set was cleared", which was true of a {@code clear()}
     * nothing called and is now true of nothing at all. Listeners still guard, and should: a null check
     * that can never fire is cheaper than a contract two classes have to agree about.</p>
     */
    public final Signal.Value<Resource> onDidChange = new Signal.Value<>();

    /**
     * One script's current state, how many handlers it left registered, and when that run began.
     *
     * <h4>The timestamps are NOT part of what counts as a change</h4>
     *
     * <p>{@link #set} no-ops when nothing changed, and that is what keeps a per-tick script from emitting
     * a signal on every invocation. Comparing whole records would defeat it outright — two readings taken
     * a nanosecond apart are never equal — so the comparison is on {@code state} and {@code handlers}
     * alone, and the clock rides along rather than participating.</p>
     */
    public record Session(RunState state, int handlers, long startedNanos, long endedNanos) {

        public boolean isActive() {
            return state.isActive();
        }

        /** Whether this session is the same STATE as another — what decides if anything is announced. */
        boolean sameStateAs(@Nullable Session other) {
            return other != null && other.state == state && other.handlers == handlers;
        }

        /**
         * How long this run has been going, or lasted.
         *
         * <p><b>No sentinel.</b> {@code System.nanoTime()} has an arbitrary origin and may be negative, so
         * a "not finished yet" marker like {@code 0} or {@code Long.MIN_VALUE} is a real timestamp
         * somewhere and the arithmetic silently produces a nonsense duration. {@code isActive()} already
         * answers the question, so {@code endedNanos} is simply not read while it is true.</p>
         */
        public long elapsedNanos(long nowNanos) {
            return Math.max(0L, (isActive() ? nowNanos : endedNanos) - startedNanos);
        }
    }

    private final Map<Resource, Session> sessions = new LinkedHashMap<>();

    /**
     * Bumped whenever the map actually changes — what lets a per-frame reader skip a snapshot.
     *
     * <p>{@link #scripts()} and {@link #active()} both copy under the lock, and the panel asked for one
     * or the other three times a frame to answer questions whose answer changes a handful of times in a
     * session: has anything run, is anything running, has something just started. A counter turns all
     * three into an int comparison on the frames where nothing happened, which is nearly all of them.</p>
     *
     * <p>It counts <em>changes</em>, not calls to {@link #set} — {@code set} no-ops when the state is
     * unchanged, which is what keeps a per-tick script from bumping this twenty times a second.</p>
     */
    private int version;

    /**
     * The clock, injectable so a test can step it.
     *
     * <p>{@code nanoTime} rather than wall time: this measures a duration, and wall time can go backwards
     * across an NTP correction — which would show a script that has been live for an hour as having
     * started in the future.</p>
     */
    private final LongSupplier clock;

    public RunSessions() {
        this(System::nanoTime);
    }

    public RunSessions(LongSupplier clock) {
        this.clock = clock == null ? System::nanoTime : clock;
    }

    /** Records a state with no handler count — everything except {@link RunState#LIVE}. */
    public void set(Resource script, RunState state) {
        set(script, state, 0);
    }

    /**
     * Records a state.
     *
     * <p>No-ops when nothing changed, which is what keeps a per-tick script from emitting a signal on
     * every invocation: a handler firing does not change the fact that the script is {@code LIVE}, and a
     * rail that redrew on each one would be doing twenty rebuilds a second to show the same word.</p>
     */
    public void set(Resource script, RunState state, int handlers) {
        if (script == null || state == null) return;
        synchronized (this) {
            Session previous = sessions.get(script);
            long now = clock.getAsLong();
            // THE CLOCK STARTS WHEN THE RUN DOES, not when the script was compiled -- and it SURVIVES a
            // transition between two active states, so a one-shot that registers handlers and becomes
            // LIVE keeps counting from when it began rather than restarting at the handover.
            long started = previous != null && previous.isActive() ? previous.startedNanos() : now;
            Session updated = new Session(state, Math.max(0, handlers), started, now);
            if (updated.sameStateAs(previous)) return;
            sessions.put(script, updated);
            version++;
        }
        onDidChange.emit(script);
    }

    @Nullable
    public synchronized Session sessionOf(Resource script) {
        return sessions.get(script);
    }

    /** The state, or {@code null} when this workspace has never run the script. */
    @Nullable
    public synchronized RunState stateOf(Resource script) {
        Session session = sessions.get(script);
        return session == null ? null : session.state();
    }

    public synchronized boolean isActive(Resource script) {
        Session session = sessions.get(script);
        return session != null && session.isActive();
    }

    /**
     * How many times this has changed — an int a per-frame reader can compare instead of copying.
     *
     * @see #version
     */
    public synchronized int version() {
        return version;
    }

    /** Whether anything has ever run. {@code scripts().isEmpty()} without the copy. */
    public synchronized boolean isEmpty() {
        return sessions.isEmpty();
    }

    /** Whether anything can still do something. {@code !active().isEmpty()} without the copy. */
    public synchronized boolean anyActive() {
        for (Session session : sessions.values()) {
            if (session.isActive()) return true;
        }
        return false;
    }

    /**
     * The first script that is still active, or null.
     *
     * <p>What Stop names. The panel asked this as {@code active().stream().findFirst()}, which builds a
     * list and a stream to look at one element — once a frame, forever.</p>
     */
    @Nullable
    public synchronized Resource firstActive() {
        for (Map.Entry<Resource, Session> entry : sessions.entrySet()) {
            if (entry.getValue().isActive()) return entry.getKey();
        }
        return null;
    }

    /**
     * Every script this workspace knows about, <b>most recently started first</b>.
     *
     * <h4>Newest first, and by the CLOCK rather than by insertion</h4>
     *
     * <p>The rail is a list you read downward, and the run you care about is nearly always the one that
     * just happened — it is the reason you opened the panel. Insertion order put it at the bottom, under
     * everything that had already finished, and grew in the wrong direction all session.</p>
     *
     * <p>Reversing insertion order would be the cheap version and it is wrong for the case that matters:
     * <b>a re-run</b>. A script first run an hour ago and re-run just now is the newest thing here, and
     * insertion order still has it wherever it was. {@code startedNanos} is reset when a run begins from
     * a state that was not already active, so sorting on it puts a re-run where it belongs.</p>
     *
     * <p><b>Compared by subtraction, never by {@code <}.</b> {@code System.nanoTime()} has an arbitrary
     * origin and may be negative, so an ordinary comparison of two readings is wrong across the point
     * where it wraps; the difference is correct for any two instants within ~292 years of each other,
     * which is the same reason this class refuses a sentinel timestamp elsewhere.</p>
     */
    public synchronized List<Resource> scripts() {
        List<Map.Entry<Resource, Session>> entries = new ArrayList<>(sessions.entrySet());
        entries.sort((left, right) ->
                Long.signum(right.getValue().startedNanos() - left.getValue().startedNanos()));
        List<Resource> found = new ArrayList<>(entries.size());
        for (Map.Entry<Resource, Session> entry : entries) found.add(entry.getKey());
        return Collections.unmodifiableList(found);
    }

    /** Only the ones that can still do something — what the indicator marks. */
    public synchronized List<Resource> active() {
        List<Resource> found = new ArrayList<>();
        for (Map.Entry<Resource, Session> entry : sessions.entrySet()) {
            if (entry.getValue().isActive()) found.add(entry.getKey());
        }
        return Collections.unmodifiableList(found);
    }

    /**
     * Drops a script entirely — it was deleted, or the workspace closed it.
     *
     * <p>Distinct from setting {@link RunState#STOPPED}, which keeps the row so its transcript still has
     * an owner in the rail.</p>
     */
    public void forget(Resource script) {
        boolean removed;
        synchronized (this) {
            removed = sessions.remove(script) != null;
            if (removed) version++;
        }
        if (removed) onDidChange.emit(script);
    }

    /**
     * Follows a script that was renamed — <b>the run does not end.</b>
     *
     * <p>A session is about the script, and a script renamed while it is running is still running: the
     * process is alive, its transcript is still filling, and its Stop button still means something. The
     * workspace used to report a rename as a delete, so this arrived as {@link #forget} and took all
     * three away, leaving a live process with nothing pointing at it.</p>
     *
     * <p>Nothing happens when the old name has no session, which is the ordinary case — most files that
     * are renamed have never been run.</p>
     */
    public void retarget(Resource from, Resource to) {
        Session moved;
        synchronized (this) {
            moved = sessions.remove(from);
            if (moved == null) return;
            sessions.put(to, moved);
            version++;
        }
        onDidChange.emit(to);
    }

    // NO clear(). It was the obvious counterpart to `forget` and nothing ever wanted it: a workspace
    // closing takes the whole `RunSessions` with it, and "forget every run" is not a gesture either
    // reference offers -- IntelliJ's run tabs are closed one at a time. The signal it emitted carried a
    // null script, which is why `onDidChange` is documented as "or null when the whole set was cleared";
    // that sentence now describes nothing, and the only emitter left always names a script.
}
