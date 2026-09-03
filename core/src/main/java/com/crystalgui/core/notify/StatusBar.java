package com.crystalgui.core.notify;

import com.crystalgui.core.signal.Signal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ambient text about how things are right now — VS Code's {@code IStatusbarService}, IntelliJ's status bar
 * widgets.
 *
 * <p>Ported from {@code vs/workbench/services/statusbar/browser/statusbar.ts} and
 * {@code vs/workbench/browser/parts/statusbar/statusbarModel.ts}.</p>
 *
 * <h3>Entries are handed out, not written into a map</h3>
 *
 * <p>{@link #addEntry} returns a {@link StatusBarEntryAccessor} — the reference's shape, and the reason is
 * on that interface: a string-keyed {@code set(id, text)} makes withdrawal a thing a writer must remember
 * and makes two writers on one id a silent collision. An accessor is the entry's identity and its
 * lifetime.</p>
 *
 * <h3>Order is declared, not accidental</h3>
 *
 * <p>Entries used to render in the order their writers happened to run, so the right-hand group's layout
 * was decided by whichever line of the view's activation came first. Both references order
 * explicitly — VS Code by {@code priority}, IntelliJ by widget {@code anchor} — because the bar is glanced
 * at, and a readout that moves between sessions cannot be glanced at at all.</p>
 *
 * <p><b>Higher priority is further left</b> — the reference's rule verbatim, and in <em>both</em> groups
 * rather than "closer to the outer edge", which is the plausible-sounding version that gets the right-hand
 * group backwards. VS Code's own right-hand entries are ordered {@code selection 100, indentation 99,
 * encoding 98, eol 97} and render in exactly that sequence left to right. Ties break by registration
 * order, which is VS Code's secondary-priority default and is what makes this port behave exactly as the
 * old insertion-ordered map did for every writer that does not care.</p>
 *
 * <h3>Ambient, not events — see {@link Notifications}</h3>
 *
 * <p>A status entry is replaced rather than accumulated and has no history: it describes the present, and
 * an out-of-date one is worse than none. Anything that <em>happened</em> — a failure, a completed
 * operation, something with an action attached — is a notification instead.</p>
 */
public final class StatusBar {

    private StatusBar() {
    }

    /**
     * The set of entries, or one of them, changed.
     *
     * <p><b>Carries nothing on purpose.</b> It used to carry the composed line, which meant
     * {@link #text()} — a walk of every entry building a string — ran on <em>every</em> write, including
     * the caret readout on every selection change and the shader graph's line-owner readout on every caret
     * move, whether or not anything was listening for a string. A listener re-reads what it needs, exactly
     * as {@code DiagnosticSet.onChanged} and {@code TreeObserver.stateChanged} already do.</p>
     */
    public static final Signal.Action onDidChange = new Signal.Action();

    /** Registration order, which is the tiebreak when two entries share a priority. */
    private static int sequence;

    /**
     * Entry ids the user has switched off — VS Code's status bar context menu, IntelliJ's
     * Settings → Appearance → Status Bar Widgets.
     *
     * <p><b>This is what {@link StatusBarEntry#name()} is for.</b> A hide menu lists entries by what they
     * <em>are</em> and not by what they currently show — you cannot offer "hide 51:39" as a checkbox, and
     * the text it would name changes on every keystroke. The name/text split looked like redundancy until
     * something had to enumerate the bar.</p>
     *
     * <p>By id rather than by accessor, so the choice survives the entry: the caret readout is registered
     * afresh every time a document is activated, and a preference that died with the tab would have to be
     * made again on every file. Which is also why it is a set of ids and not a flag on the entry.</p>
     *
     * <p>Not persisted yet — the same honest limit as notification suppression, and for the same reason.</p>
     */
    private static final Set<String> HIDDEN = new LinkedHashSet<>();

    /** Switches an entry id off, or back on. @see #HIDDEN */
    public static void setHidden(String id, boolean hidden) {
        if (id == null || id.isEmpty()) return;
        boolean changed = hidden ? HIDDEN.add(id) : HIDDEN.remove(id);
        if (changed) onDidChange.emit();
    }

    public static boolean isHidden(String id) {
        return HIDDEN.contains(id);
    }

    /**
     * Every registered entry, hidden ones included, in visual order — what a hide menu enumerates.
     *
     * <p>Distinct from {@link #entries()}, which is what a bar renders: a menu has to list what you have
     * switched off, or there is no way to switch it back on.</p>
     */
    public static List<StatusBarEntryAccessor> allEntries() {
        List<Live> all = new ArrayList<>(ENTRIES);
        all.sort(BY_PRIORITY);
        return new ArrayList<>(all);
    }

    /** The id an accessor was registered under. */
    public static String idOf(StatusBarEntryAccessor accessor) {
        return accessor instanceof Live ? ((Live) accessor).id : "";
    }

    private static final List<Live> ENTRIES = new ArrayList<>();

    /** The separator between two entries in {@link #text()}. */
    private static final String SEPARATOR = "   ";

    /**
     * Puts an entry on the bar and hands back the handle that owns it.
     *
     * @param entry     what it says
     * @param id        what it is, for a future "hide this entry" menu. Need not be unique — the returned
     *                  accessor is the identity, so two writers cannot collide however they name theirs.
     * @param alignment which end it belongs at
     * @param priority  higher sits closer to that end. Ties break by registration order.
     */
    public static StatusBarEntryAccessor addEntry(StatusBarEntry entry, String id,
                                                  StatusBarAlignment alignment, int priority) {
        if (entry == null) throw new IllegalArgumentException("a status entry needs content");
        Live live = new Live(id == null ? entry.name() : id, entry,
                alignment == null ? StatusBarAlignment.LEFT : alignment, priority, sequence++);
        ENTRIES.add(live);
        onDidChange.emit();
        return live;
    }

    /** As {@link #addEntry(StatusBarEntry, String, StatusBarAlignment, int)}, at the default priority. */
    public static StatusBarEntryAccessor addEntry(StatusBarEntry entry, String id,
                                                  StatusBarAlignment alignment) {
        return addEntry(entry, id, alignment, 0);
    }

    /**
     * Every live entry in one group, in <b>left-to-right order</b> — so a view appends them and is done.
     *
     * <p>Both groups sort the same way, because "higher priority is further left" is a statement about the
     * bar rather than about a group. @see StatusBar
     */
    public static List<StatusBarEntryAccessor> entries(StatusBarAlignment alignment) {
        List<Live> group = new ArrayList<>();
        for (Live live : ENTRIES) {
            if (live.alignment == alignment && !HIDDEN.contains(live.id)) group.add(live);
        }
        group.sort(BY_PRIORITY);
        return new ArrayList<>(group);
    }

    /** Highest priority first; registration order breaks a tie. */
    private static final Comparator<Live> BY_PRIORITY =
            Comparator.<Live>comparingInt(live -> -live.priority).thenComparingInt(live -> live.sequence);

    /** Every live entry, both groups, left group first. */
    public static List<StatusBarEntryAccessor> entries() {
        List<StatusBarEntryAccessor> all = new ArrayList<>(entries(StatusBarAlignment.LEFT));
        all.addAll(entries(StatusBarAlignment.RIGHT));
        return all;
    }

    public static int size() {
        return ENTRIES.size();
    }

    /**
     * Every live entry as one line, in visual order. Empty when there are none.
     *
     * <p>For anything that wants a flat string — a log, a headless assertion, the harness's single label.
     * Deliberately ignores alignment, since a line has no ends.</p>
     */
    public static String text() {
        StringBuilder out = new StringBuilder();
        for (StatusBarEntryAccessor accessor : entries()) {
            if (out.length() > 0) out.append(SEPARATOR);
            out.append(accessor.entry().text());
        }
        return out.toString();
    }

    /** Empties the bar. For tests that need isolation, never for production. */
    public static void resetForTesting() {
        ENTRIES.clear();
        HIDDEN.clear();
        sequence = 0;
        onDidChange.disconnectAll();
    }

    /** One registered entry. Its own accessor, so the handle and the record cannot come apart. */
    private static final class Live implements StatusBarEntryAccessor {

        final String id;
        private final StatusBarAlignment alignment;
        private final int priority;
        private final int sequence;
        private StatusBarEntry entry;
        private boolean disposed;

        Live(String id, StatusBarEntry entry, StatusBarAlignment alignment, int priority, int sequence) {
            this.id = id;
            this.entry = entry;
            this.alignment = alignment;
            this.priority = priority;
            this.sequence = sequence;
        }

        @Override
        public void update(StatusBarEntry replacement) {
            if (disposed || replacement == null) return;
            // SILENT WHEN NOTHING MOVED, and the record's own equals is what decides that. The guard used
            // to be a hand-written six-clause comparison of every field, which meant adding a field to the
            // entry silently skipped it -- an entry whose only change was the new field would announce
            // nothing at all, and the omission is invisible at the call site.
            if (Objects.equals(entry, replacement)) return;
            entry = replacement;
            onDidChange.emit();
        }

        @Override
        public StatusBarEntry entry() {
            return entry;
        }

        /** The id this was registered under — what a "hide this entry" menu would name it by. */
        @SuppressWarnings("unused")
        String id() {
            return id;
        }

        @Override
        public void dispose() {
            if (disposed) return;
            disposed = true;
            ENTRIES.remove(this);
            onDidChange.emit();
        }
    }
}
