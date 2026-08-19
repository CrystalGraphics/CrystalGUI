package com.crystalgui.ui;

import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;

import javax.annotation.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Widget state that outlives the widget — a bag of {@link UIElement#writeState} payloads keyed by id.
 *
 * <h3>Why this and not an interface per panel</h3>
 *
 * <p>The first version of this was a {@code PanelViewState} interface a tool window implemented, and that
 * was the wrong shape: the engine <em>already</em> has a way for a widget to say what it wants preserved
 * ({@code writeState}/{@code readState}) and a way to name one ({@code setId}). A second, parallel
 * mechanism meant every panel re-implemented persistence for widgets that could already describe
 * themselves, and it could only ever reach a panel's <b>root</b> — a divider three levels down had to be
 * proxied out by hand.</p>
 *
 * <p>So this stores what the existing hook produces. A widget opts in with
 * {@link UIElement#setSessionPersistent}, and everything else follows from parts that were already
 * there.</p>
 *
 * <h3>Applied on REGISTRATION, which is what makes lazily-built widgets work</h3>
 *
 * <p>{@link UIWindow#registerElement} is the one moment every element joins a window, whenever it is
 * created — and "whenever" is the point. A tool window is built the first time it is opened, and a widget
 * inside one may be built later still: the Run panel's split does not exist until a script runs, which
 * can be minutes after the session was restored or never. Anything applied once at startup misses all of
 * that, silently, and the widget looks correct because it has its default.</p>
 *
 * <h3>Once, and only once</h3>
 *
 * <p>An id is spent when it is applied. Re-applying would drag a divider back to the session's position
 * every time its panel was rebuilt, undoing wherever the user had since dragged it — the same rule a
 * document's caret restore follows, and for the same reason.</p>
 *
 * <p>Entries that are never claimed are <b>kept</b>, and {@link #capture} re-emits them. Writing only
 * what is on screen makes every save an erasure for every widget not built that session, so a divider
 * would survive exactly as long as the habit of opening its panel — an erosion that is invisible because
 * each individual save looks correct.</p>
 *
 * @param <T> the serialized form, from whichever {@link DynamicOps} the caller persists with
 */
public final class SessionState<T> {

    private final DynamicOps<T> ops;

    /** Raw payloads by element id — both what was read in and what was captured out. */
    private final Map<String, T> stored = new LinkedHashMap<>();

    /** Ids already handed to a live element this session. */
    private final Set<String> applied = new HashSet<>();

    public SessionState(DynamicOps<T> ops) {
        this.ops = ops;
    }

    /** Replaces everything held. Anything previously applied is forgotten, so a restore can re-apply. */
    public void load(Map<String, T> entries) {
        stored.clear();
        applied.clear();
        stored.putAll(entries);
    }

    /** What to write out — see the class note on why unclaimed entries survive. */
    public Map<String, T> entries() {
        return stored;
    }

    public boolean isEmpty() {
        return stored.isEmpty();
    }

    /**
     * Hands one element its remembered state, if it asked for any and has not had it yet.
     *
     * <p>A refusal is swallowed by the caller rather than here — see {@link UIWindow#registerElement} —
     * because a widget that cannot read a stale payload must not be a widget that cannot be added to a
     * window.</p>
     */
    public void applyTo(@Nullable UIElement element) {
        if (element == null || !element.isSessionPersistent()) return;
        String id = element.getId();
        if (id.isEmpty() || applied.contains(id)) return;
        T payload = stored.get(id);
        if (payload == null) return;
        applied.add(id);
        element.readStateFrom(new StateMap<>(ops, payload));
    }

    /**
     * Reads every opted-in element under {@code root} back out, over the top of what is held.
     *
     * <p>Over the top rather than instead of: an id with no live element keeps whatever it came in with.
     * Internal children are walked too — a widget's parts are exactly where a divider lives.</p>
     */
    public void capture(@Nullable UIElement root) {
        if (root == null) return;
        collect(root);
    }

    private void collect(UIElement element) {
        captureFrom(element);
        for (UIElement child : element.getChildren()) collect(child);
    }

    /**
     * Reads one element back out — the mirror of {@link #applyTo}, called as it <em>leaves</em> a window.
     *
     * <p>Without this, closing a panel loses everything in it. A hidden tool window is detached, so a save
     * afterwards walks a tree the widget is no longer in and writes nothing: drag the Run panel's divider,
     * close the panel, quit, and the width is gone — which is precisely the erosion this class exists to
     * prevent, arriving through the one door that was left open.</p>
     *
     * <p>Capturing on the way out rather than only at save time also means the value stored is the one the
     * widget had while it was alive, which is the only moment it can be read at all.</p>
     */
    public void captureFrom(@Nullable UIElement element) {
        if (element == null || !element.isSessionPersistent()) return;
        String id = element.getId();
        if (id.isEmpty()) return;
        StateMap<T> out = new StateMap<>(ops);
        element.writeStateTo(out);
        stored.put(id, out.encode());
    }
}
