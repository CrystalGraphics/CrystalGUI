package com.crystalgui.ui.elements.dock;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One panel's place in the layout: <b>what it is</b> and <b>which one it is</b> — never what it contains.
 *
 * <p>This is VS Code's {@code IViewDeserializer.fromJSON(json)} seam, Golden Layout's
 * {@code componentType} + {@code componentState}, and ImGui's window name. The layout stores an id and
 * hands it to a factory on restore; it has no idea a shader graph exists.</p>
 *
 * <h3>Why the state is {@code Map<String, String>} and not a {@code StateMap}</h3>
 *
 * <p>{@code StateMap<T>} is generic over its {@code DynamicOps}, and threading that through the whole tree
 * would make {@code DockLayout<T>} viral for the sake of a payload the layout never reads. More to the
 * point, what a <em>layout</em> needs to remember about a panel is identity — which file, which document,
 * which project — and that is string-shaped. A panel's rich internal state is the panel's own problem: it
 * re-reads it from the document on restore, which is exactly the split that stops a saved layout from
 * being a stale snapshot of somebody's screen.</p>
 *
 * <p>The map is copied and unmodifiable, so a ref cannot be mutated out from under a serialised tree.</p>
 */
public final class DockPanelRef {

    /**
     * The one state key the dock itself reads: a per-instance tab label.
     *
     * <p>Everything else in the map is the panel's business. This one is not, because the strip has to
     * draw a label before the panel exists — a restored layout paints its tabs on the frame it is decoded,
     * and the content behind them may be built lazily or not at all.</p>
     */
    public static final String TITLE = "title";

    private final String typeId;
    private final Map<String, String> state;

    public DockPanelRef(String typeId) {
        this(typeId, Collections.emptyMap());
    }

    public DockPanelRef(String typeId, Map<String, String> state) {
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.state = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(state, "state")));
    }

    public String typeId() {
        return typeId;
    }

    /** Unmodifiable, insertion-ordered — the order is what keeps a serialised layout byte-stable. */
    public Map<String, String> state() {
        return state;
    }

    public String state(String key, String fallback) {
        String value = state.get(key);
        return value != null ? value : fallback;
    }

    /** A copy with one state entry replaced. Refs are immutable, so mutation is always a new ref. */
    public DockPanelRef withState(String key, String value) {
        Map<String, String> copy = new LinkedHashMap<>(state);
        copy.put(key, value);
        return new DockPanelRef(typeId, copy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DockPanelRef)) return false;
        DockPanelRef other = (DockPanelRef) o;
        return typeId.equals(other.typeId) && state.equals(other.state);
    }

    @Override
    public int hashCode() {
        return 31 * typeId.hashCode() + state.hashCode();
    }

    @Override
    public String toString() {
        return state.isEmpty() ? typeId : typeId + state;
    }
}
