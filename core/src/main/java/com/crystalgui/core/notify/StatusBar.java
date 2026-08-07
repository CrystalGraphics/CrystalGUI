package com.crystalgui.core.notify;

import com.crystalgui.core.signal.Signal;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ambient text about how things are right now — VS Code's status bar items.
 *
 * <h3>Keyed, because a single slot is a fight</h3>
 *
 * <p>{@code Workbench.onStatus} was one {@code Signal.Value<String>}, so every writer overwrote every
 * other and the last one to speak won. That is fine with one writer and wrong with two: the shader
 * graph's line-owner readout fires on every caret move in the generated source, so it erased the
 * explorer's "created folder" a few milliseconds after it appeared, and neither writer could tell.</p>
 *
 * <p>An id per writer makes them independent. {@link #text()} composes what is live, so a display can
 * bind one string and never know how many contributors there are.</p>
 *
 * <h3>Ambient, not events — see {@link Notifications}</h3>
 *
 * <p>A status item is replaced rather than accumulated and has no history: it describes the present, and
 * an out-of-date one is worse than none. Anything that <em>happened</em> — a failure, a completed
 * operation, something with an action attached — is a notification instead.</p>
 */
public final class StatusBar {

    private StatusBar() {
    }

    /** Fires with the composed line whenever any item changes. */
    public static final Signal.Value<String> onDidChange = new Signal.Value<>();

    /** Insertion-ordered, so the composed line is stable rather than reordering as items update. */
    private static final Map<String, String> ITEMS = new LinkedHashMap<>();

    /** The separator between two live items in {@link #text()}. */
    private static final String SEPARATOR = "   ";

    /**
     * Sets this writer's item. A null or blank text clears it, which is the same thing said two ways and
     * saves every caller an if.
     *
     * @param id stable per writer — {@code "shadergraph.lineOwner"}. Two writers sharing an id share a
     *           slot, which is only ever correct on purpose.
     */
    public static void set(String id, @Nullable String text) {
        if (id == null) return;
        String previous;
        if (text == null || text.isEmpty()) {
            previous = ITEMS.remove(id);
        } else {
            previous = ITEMS.put(id, text);
        }
        // Silent when nothing moved, for the reason every announcement here is: a status item written on
        // a per-frame path -- and the line-owner readout is exactly that -- would otherwise emit on every
        // frame whether or not it changed.
        if (java.util.Objects.equals(previous, text)) return;
        onDidChange.emit(text());
    }

    public static void clear(String id) {
        set(id, null);
    }

    /** Every live item, in the order they were first set. Empty when there are none. */
    public static String text() {
        return String.join(SEPARATOR, ITEMS.values());
    }

    /** Empties every item. For tests that need isolation, never for production. */
    public static void resetForTesting() {
        ITEMS.clear();
        onDidChange.disconnectAll();
    }
}
