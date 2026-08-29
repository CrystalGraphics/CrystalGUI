package com.crystalgui.ui.elements.chrome;

import com.crystalgui.text.lang.SymbolKind;

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
<h3>{@code description} sits AFTER the label; {@code category} sits before it</h3>
 *
 * <p>Two dim fields either side of the name, and they are not interchangeable. A category <em>qualifies</em>
 * the label — {@code View: Toggle Sidebar} — and reads as part of the command's name, which is why it is
 * matched. A description <em>locates</em> it: {@code ArrayList  java.util}. Both references put a symbol's
 * package on the right of its name and neither highlights it, because a class picker's query is about the
 * name and lighting up the package claims it contributed to the ranking when it did not.</p>
 *
 * <p>So {@code description} is <b>dim, trailing, and unmatched</b>. That is also why
 * {@link QuickPickEntry} carries no ranges for it — there are none to carry.</p>
 *
 * <h3>{@code kind} is a {@link SymbolKind}, not an icon name</h3>
 *
 * <p>Because {@link com.crystalgui.ui.elements.SymbolIcon} already exists and is already the union point:
 * the completion popup and a library viewer's tab draw the same glyph from the same
 * {@code completion-kind-*} vocabulary. A picker resolving its own icon name would be the third table
 * saying one thing, and the failure is invisible — a class glyph on an interface looks like a row with an
 * icon, not a row with the wrong icon.</p>
 *
 * @param id          what {@link QuickPick#onAccepted} reports when this row is chosen
 * @param label       primary text — matched at {@code FIELD_PRIMARY}
 * @param description optional dim text shown AFTER the label. Never matched — see above
 * @param category    optional secondary text shown before the label, matched at {@code FIELD_CONTEXT}
 * @param accelerator optional right-aligned text; a keybinding for commands, anything for other pickers
 * @param enabled     whether the row can be chosen; a disabled row still matches and still lists
 * <h3>...and {@code iconName} is the other kind of glyph, for rows that are not symbols</h3>
 *
 * <p>A file has no {@link SymbolKind} — it is a {@code .java}, not a class — and its picture comes from
 * {@code FileIconTheme}, keyed on the name. Two fields rather than one union because the two resolve
 * through genuinely different machinery: a kind stacks {@code static}/{@code final} layers over a glyph,
 * a name resolves to one drawable. A row sets at most one; a row that sets neither draws no glyph.</p>
 *
 * <p>They coexist in one list on purpose. Go to File lists project files <em>and</em> classpath types
 * together, so half the rows are symbols and half are files — and a list where only half the rows have a
 * picture reads as broken rather than as mixed.</p>
 *
 * <h3>{@code contextual} is a RANKING signal, and it is not drawn at all</h3>
 *
 * <p>"This row is available <em>because of where the picker was opened</em>." A command that resolves an
 * editor from the focused element is contextual; one that works anywhere — Reload from Disk, Restore
 * Window Layout — is not. It sorts contextual rows above global ones within a match tier, which is what
 * makes a palette opened over an editor lead with the editor's own verbs.</p>
 *
 * <p>Deliberately invisible. {@code enabled} earns dimming because it changes what Enter does; this only
 * changes the order, and a row that looked different for being <em>more</em> relevant would read as a
 * second kind of disabled. Default false, so every picker that is not the command palette is unaffected
 * — the same arrangement {@code enabled} already has.</p>
 *
 * @param kind        optional symbol kind; drives the row's glyph, absent for a row that is not a symbol
 * @param isAbstract  refines {@code kind} — an abstract class draws differently, it is not a kind of its own
 * @param iconName    optional {@code "ns:name"} icon for a row that is not a symbol. Ignored when
 *                    {@code kind} is set, so the two can never both draw
 * @param contextual  whether this row is available because of where the picker was opened. Ranking only
 */
public record QuickPickItem(String id, String label, @Nullable String description,
                            @Nullable String category, @Nullable String accelerator, boolean enabled,
                            @Nullable SymbolKind kind, boolean isAbstract, @Nullable String iconName,
                            boolean contextual) {

    public QuickPickItem {
        if (id == null) throw new IllegalArgumentException("QuickPickItem id must not be null");
        if (label == null) throw new IllegalArgumentException("QuickPickItem label must not be null");
    }

    /** The shape every caller before {@link #contextual} existed used — never contextual, which is right
     * for every picker that is not the command palette. */
    public QuickPickItem(String id, String label, @Nullable String description,
                         @Nullable String category, @Nullable String accelerator, boolean enabled,
                         @Nullable SymbolKind kind, boolean isAbstract, @Nullable String iconName) {
        this(id, label, description, category, accelerator, enabled, kind, isAbstract, iconName, false);
    }

    /** The shape every caller before symbols existed used, unchanged. */
    public QuickPickItem(String id, String label, @Nullable String category,
                         @Nullable String accelerator, boolean enabled) {
        this(id, label, null, category, accelerator, enabled, null, false, null, false);
    }

    /** Enabled by default — a source that never sets it gets rows that all work, which is the common case
     * for anything that is not a command. */
    public QuickPickItem(String id, String label, @Nullable String category,
                         @Nullable String accelerator) {
        this(id, label, null, category, accelerator, true, null, false, null, false);
    }

    public static QuickPickItem of(String id, String label) {
        return new QuickPickItem(id, label, null, null);
    }

    public static QuickPickItem of(String id, String label, @Nullable String category) {
        return new QuickPickItem(id, label, category, null);
    }

    // ── Withers ─────────────────────────────────────────────────────────────────
    //
    // EVERY ONE CARRIES EVERY FIELD. Not style -- the identical mistake is recorded against SymbolInfo,
    // whose withers routed through a seven-component constructor and silently DROPPED whichever field the
    // caller had set first, so `of(...).withSignature(s).withType(t)` lost the signature. An
    // order-sensitive builder on a shared type is a trap for whoever writes the next caller.

    public QuickPickItem withAccelerator(@Nullable String accelerator) {
        return new QuickPickItem(id, label, description, category, accelerator, enabled, kind, isAbstract,
                iconName, contextual);
    }

    public QuickPickItem withEnabled(boolean enabled) {
        return new QuickPickItem(id, label, description, category, accelerator, enabled, kind, isAbstract,
                iconName, contextual);
    }

    public QuickPickItem withDescription(@Nullable String description) {
        return new QuickPickItem(id, label, description, category, accelerator, enabled, kind, isAbstract,
                iconName, contextual);
    }

    /** @see com.crystalgui.ui.elements.SymbolIcon */
    public QuickPickItem withKind(@Nullable SymbolKind kind, boolean isAbstract) {
        return new QuickPickItem(id, label, description, category, accelerator, enabled, kind, isAbstract,
                iconName, contextual);
    }

    /** @see #iconName */
    public QuickPickItem withIconName(@Nullable String iconName) {
        return new QuickPickItem(id, label, description, category, accelerator, enabled, kind, isAbstract,
                iconName, contextual);
    }

    /** @see #contextual */
    public QuickPickItem withContextual(boolean contextual) {
        return new QuickPickItem(id, label, description, category, accelerator, enabled, kind, isAbstract,
                iconName, contextual);
    }

    /** {@code Category: Label}, or just the label. What a row reads as, and what a test asserts on. */
    public String displayText() {
        return category == null || category.isEmpty() ? label : category + ": " + label;
    }
}
