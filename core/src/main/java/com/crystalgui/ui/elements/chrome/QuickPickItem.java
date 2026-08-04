package com.crystalgui.ui.elements.chrome;

import javax.annotation.Nullable;

/**
 * One row in a {@link QuickPick} — an id to hand back, text to match, and text to show.
 *
 * <h3>{@code category} is a separate field, not a prefix glued onto the label</h3>
 *
 * <p>VS Code renders a command as {@code Category: Label} and matches against both, but never against the
 * <em>concatenation</em>. The distinction is load-bearing because
 * {@link com.crystalgui.core.search.SearchMatcher} weights fields: a hit in a label must outrank a hit in
 * a category, or querying {@code split} surfaces every command in a category whose name happens to contain
 * those letters. That is the exact failure the matcher's own javadoc records from the node create menu —
 * and gluing the two strings together before matching reintroduces it in a form the matcher cannot see,
 * because by then there is only one field.</p>
 *
 * <p>Keeping them apart also means the row can render them as two elements, which is what lets highlight
 * ranges be used <b>as the matcher produced them</b>. One glued string would need every label range
 * shifted by {@code category.length() + 2}, and that offset would be recomputed at every bind.</p>
 *
 * <h3>{@code accelerator} is rendered text, not a {@code KeyChord}</h3>
 *
 * <p>Because a quick pick is not only ever filled from the command registry. A file picker's rows have no
 * keybinding at all, and a symbol picker's right-hand column is a line number. Keeping it a string means
 * the widget never learns what a keymap is — the caller renders whatever belongs there.</p>
 *
 * <h3>{@code enabled} is shown, not filtered out</h3>
 *
 * <p>An unavailable row is listed dimmed and cannot be chosen — IntelliJ's Find Action behaviour, and a
 * <b>correction</b> of an earlier decision to hide it the way VS Code does.</p>
 *
 * <p>VS Code can afford hiding because its {@code when} clauses read declarative context keys. Every
 * {@code enabledWhen} predicate here instead walks <em>up the tree from the focused element</em> looking
 * for a {@code DockArea} or an editor — so when nothing is focused the honest answer is "unknown", and
 * hiding turns that into "no". Observed: opening the palette in the dock harness before clicking anything
 * listed <b>one</b> command out of nine, and the one row present was the palette's own opener, so choosing
 * it reopened an identical-looking palette and read as a dead widget.</p>
 *
 * @param id          what {@link QuickPick#onAccepted} reports when this row is chosen
 * @param label       primary text — matched at {@code FIELD_PRIMARY}
 * @param category    optional secondary text shown before the label, matched at {@code FIELD_CONTEXT}
 * @param accelerator optional right-aligned text; a keybinding for commands, anything for other pickers
 * @param enabled     whether the row can be chosen; a disabled row still matches and still lists
 */
public record QuickPickItem(String id, String label, @Nullable String category,
                            @Nullable String accelerator, boolean enabled) {

    public QuickPickItem {
        if (id == null) throw new IllegalArgumentException("QuickPickItem id must not be null");
        if (label == null) throw new IllegalArgumentException("QuickPickItem label must not be null");
    }

    /** Enabled by default — a source that never sets it gets rows that all work, which is the common case
     * for anything that is not a command. */
    public QuickPickItem(String id, String label, @Nullable String category,
                         @Nullable String accelerator) {
        this(id, label, category, accelerator, true);
    }

    public static QuickPickItem of(String id, String label) {
        return new QuickPickItem(id, label, null, null);
    }

    public static QuickPickItem of(String id, String label, @Nullable String category) {
        return new QuickPickItem(id, label, category, null);
    }

    public QuickPickItem withAccelerator(@Nullable String accelerator) {
        return new QuickPickItem(id, label, category, accelerator, enabled);
    }

    public QuickPickItem withEnabled(boolean enabled) {
        return new QuickPickItem(id, label, category, accelerator, enabled);
    }

    /** {@code Category: Label}, or just the label. What a row reads as, and what a test asserts on. */
    public String displayText() {
        return category == null || category.isEmpty() ? label : category + ": " + label;
    }
}
