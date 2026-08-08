package com.crystalgui.core.notify;

import com.crystalgui.core.signal.Signal;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Which end of the bar an item belongs to — VS Code's {@code StatusBarAlignment}, IntelliJ's widget
     * anchors.
     *
     * <h3>Why the model carries this and the view does not decide it</h3>
     *
     * <p>Only the writer knows. "Ln 51, Col 39" belongs on the right because it is <em>about the thing you
     * are looking at</em> and sits in a fixed place you can glance at; "created notes.txt" belongs on the
     * left because it is about what just happened and is read as prose. A view sorting by id prefix, or by
     * guessing at the text, would be inventing an answer the writer already has.</p>
     */
    public enum Align {
        /** The reading half: ambient prose, breadcrumbs, what just happened. The default. */
        LEFT,
        /** The glancing half: caret position, line ending, encoding, indent. */
        RIGHT
    }

    /**
     * One writer's item.
     *
     * <p>{@code tooltip} is what the item does not have room to say. A status bar is glanced at, so the
     * text has to be short enough to read without stopping — and the detail behind it then has nowhere to
     * live unless the item carries it. Both references explain every widget on hover for exactly this
     * reason; it is what lets {@code compiled 12n/9e} be the item rather than the whole sentence.</p>
     */
    public record Item(String id, String text, Align align, @Nullable String tooltip, Severity severity) {

        public Item {
            if (severity == null) severity = Severity.NORMAL;
        }
    }

    /**
     * How an item should read — {@link Severity#NORMAL} unless it is reporting a failure.
     *
     * <h3>Ambient does not mean unimportant</h3>
     *
     * <p>"compiled 12n/9e" and "1 error(s)" are both ambient — both describe how things are right now, and
     * both are replaced by the next compile — so both belong here rather than in {@link Notifications}. But
     * they are not the same news, and rendered identically the failure reads as a statistic. The severity
     * travels so the view can say so, and the view says it with a <b>class</b> rather than a colour, which
     * is what lets one palette serve this, the Problems rows and the notification cards.</p>
     */
    public enum Severity {
        NORMAL,
        WARNING,
        ERROR
    }

    /** Insertion-ordered, so the composed line is stable rather than reordering as items update. */
    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();

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
        set(id, text, Align.LEFT);
    }

    /**
     * As {@link #set(String, String)}, choosing which end of the bar the item sits at.
     *
     * <p>An item keeps whichever alignment its most recent write gave it, so a writer that uses the
     * two-argument form is choosing {@link Align#LEFT} rather than "leave it where it was" — the same way
     * every other field here is replaced rather than merged.</p>
     */
    public static void set(String id, @Nullable String text, Align align) {
        set(id, text, align, null);
    }

    /**
     * As {@link #set(String, String, Align)}, with the longer form shown on hover.
     *
     * <p>A tooltip is part of the item, not decoration a view adds: only the writer knows what the short
     * text left out.</p>
     */
    public static void set(String id, @Nullable String text, Align align, @Nullable String tooltip) {
        set(id, text, align, tooltip, Severity.NORMAL);
    }

    /** As {@link #set(String, String, Align, String)}, saying whether this item is reporting a failure. */
    public static void set(String id, @Nullable String text, Align align, @Nullable String tooltip,
                           Severity severity) {
        if (id == null) return;
        // Blank and absent are the same state, normalised once here so every comparison below has one
        // spelling to consider rather than three.
        String wanted = text == null || text.isEmpty() ? null : text;
        Align wantedAlign = align == null ? Align.LEFT : align;
        Item previous = wanted == null
                ? ITEMS.remove(id)
                : ITEMS.put(id, new Item(id, wanted, wantedAlign, tooltip,
                        severity == null ? Severity.NORMAL : severity));
        // Silent when nothing moved, for the reason every announcement here is: a status item written on
        // a per-frame path -- and the line-owner readout is exactly that -- would otherwise emit on every
        // frame whether or not it changed.
        //
        // ALIGNMENT COUNTS AS A CHANGE. Comparing the text alone would drop a move from one end of the bar
        // to the other whenever the words happened to stay the same, which is precisely when it is hardest
        // to notice that nothing redrew.
        if (wanted == null && previous == null) return;
        if (wanted != null && previous != null
                && previous.text().equals(wanted) && previous.align() == wantedAlign
                && java.util.Objects.equals(previous.tooltip(), tooltip)
                && previous.severity() == (severity == null ? Severity.NORMAL : severity)) {
            return;
        }
        onDidChange.emit(text());
    }

    /**
     * Every live item, in the order they were first set.
     *
     * <p>What a view renders. {@link #text()} stays for anything that wants one flat line — a log, a
     * headless assertion, the harness's single label — and deliberately ignores alignment, since a line has
     * no ends.</p>
     */
    public static List<Item> items() {
        return new ArrayList<>(ITEMS.values());
    }

    public static void clear(String id) {
        set(id, null);
    }

    /** Every live item as one line, in the order they were first set. Empty when there are none. */
    public static String text() {
        StringBuilder out = new StringBuilder();
        for (Item item : ITEMS.values()) {
            if (out.length() > 0) out.append(SEPARATOR);
            out.append(item.text());
        }
        return out.toString();
    }

    /** Empties every item. For tests that need isolation, never for production. */
    public static void resetForTesting() {
        ITEMS.clear();
        onDidChange.disconnectAll();
    }
}
