package com.crystalgui.language.run;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Fired with the script whose state changed, or null when the whole set was cleared. */
    public final Signal.Value<Resource> onDidChange = new Signal.Value<>();

    /** One script's current state and how many handlers it left registered. */
    public record Session(RunState state, int handlers) {

        public boolean isActive() {
            return state.isActive();
        }
    }

    private final Map<Resource, Session> sessions = new LinkedHashMap<>();

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
        Session updated = new Session(state, Math.max(0, handlers));
        synchronized (this) {
            Session previous = sessions.get(script);
            if (updated.equals(previous)) return;
            sessions.put(script, updated);
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

    /** Every script this workspace knows about, in the order it first ran them. */
    public synchronized List<Resource> scripts() {
        return Collections.unmodifiableList(new ArrayList<>(sessions.keySet()));
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
        }
        if (removed) onDidChange.emit(script);
    }

    public void clear() {
        synchronized (this) {
            if (sessions.isEmpty()) return;
            sessions.clear();
        }
        onDidChange.emit(null);
    }
}
