package com.crystalgui.text.lang;

import com.crystalgui.text.Change;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * One row in a completion list — the Language Server Protocol's {@code CompletionItem}, ported whole.
 *
 * <h3>Why four separate {@code *Text} fields rather than one label</h3>
 *
 * <p>They answer four different questions about the same row and a method makes all four differ at once:
 * it <b>shows</b> {@code foo(int, String)}, <b>filters</b> on {@code foo}, <b>sorts</b> under {@code foo},
 * and <b>inserts</b> {@code foo(}. Collapse them and you get the familiar bugs — typing {@code foo} fails
 * to match a row displaying its signature, or accepting a row pastes its parameter list in as text. Each
 * field falls back to {@link #label} when null, so a simple item sets one thing and a method sets four.</p>
 *
 * <h3>{@link #documentation} is null on purpose, and is resolved for one item at a time</h3>
 *
 * <p>A list is hundreds of items and rendering docs for all of them means reading hundreds of javadoc
 * comments to draw one. LSP's answer is {@code completionItem/resolve} and it is the right one:
 * {@link CompletionProvider#resolveItem} fills this in for the selected row only. So <b>null here means
 * "not fetched yet", not "there is none"</b> — a consumer that treats the two the same shows an empty
 * documentation pane forever.</p>
 *
 * <h3>{@link #textEdit} is a {@link Change}, and {@link #additionalTextEdits} is what makes auto-import
 * one undo step</h3>
 *
 * <p>{@link Change} is already exactly LSP's {@code TextEdit} — a range and a replacement, in offsets
 * against the document being edited — so nothing new is needed. The primary edit inserts the name; the
 * additional ones are everywhere else that has to change, which in practice means the import. Both go into
 * a single {@code CompositeEdit} at accept time, so Ctrl+Z removes the name <em>and</em> the import it
 * brought: two undo steps for one keystroke is the behaviour every editor is criticised for.</p>
 *
 * <p><b>The additional edits must not overlap the primary one</b>, and must be expressed against the
 * document as it is <em>before</em> anything is applied. Composing them is the accepting code's job.</p>
 *
 * @param label               what the row shows
 * @param kind                what it is — drives the icon, and reuses {@link SymbolKind} rather than
 *                            introducing a near-identical second enum and a table to map between them
 * @param detail              the dimmed right-hand column: a return type, a declaring class, a package
 * @param documentation       rendered docs, or null for "not resolved yet" — see above
 * @param sortText            sorts under this rather than {@link #label}, or null
 * @param filterText          matches against this rather than {@link #label}, or null
 * @param insertText          inserted verbatim when {@link #textEdit} is null, or null for {@link #label}
 * @param textEdit            the precise replacement, which is what a replace-vs-insert session needs
 * @param additionalTextEdits everything else that must change — the import. Never null
 * @param commitCharacters    characters that accept this item by being typed ({@code .}, {@code (}).
 *                            Never null
 * @param command             a command id to run after acceptance — re-indent, or open the parameter
 *                            hints. Named rather than held, so this layer does not depend on the command
 *                            registry and an unregistered id is simply not run
 * @param insertTextFormat    whether {@link #insertText} is literal or a snippet
 * @param deprecated          drawn struck through, like {@link SymbolModifier#DEPRECATED}
 * @param modifiers           what the symbol is, beyond its kind — {@code static}, {@code abstract}.
 *                            Kind and modifier are <b>orthogonal</b> axes and an icon needs both: a static
 *                            method and an instance method are the same kind and draw differently, so
 *                            folding {@code static} into {@link SymbolKind} would double every entry in it.
 *                            Never null
 */
public record CompletionItem(String label, SymbolKind kind, @Nullable String detail,
                             @Nullable String documentation, @Nullable String sortText,
                             @Nullable String filterText, @Nullable String insertText,
                             @Nullable Change textEdit, List<Change> additionalTextEdits,
                             List<String> commitCharacters, @Nullable String command,
                             InsertTextFormat insertTextFormat, boolean deprecated,
                             Set<SymbolModifier> modifiers) {

    /**
     * How {@link CompletionItem#insertText} should be read.
     *
     * <p>Shaped now and implemented with rename (§18.4): both need linked editing — a set of ranges that
     * type together — and building it twice is how the two end up behaving differently. Until then a
     * provider may produce {@link #SNIPPET} and the accepting code inserts it literally, which is wrong in
     * a visible, reportable way rather than silently.</p>
     */
    public enum InsertTextFormat {
        /** Inserted exactly as written. */
        PLAIN,
        /**
         * Carries {@link #CARET} — and, one day, tab stops and placeholders.
         *
         * <p><b>Only {@code $0} is implemented</b>, deliberately. It is not linked editing and does not
         * pretend to be: it marks where the caret lands, which is the whole of what accepting a method
         * needs ({@code println(|)}) and none of what a template needs. {@code $1}/{@code $2} tab stops
         * arrive with rename, because both want the same linked-edit machinery and building it twice is
         * how the two come to behave differently — §18.4's reasoning, unchanged.</p>
         *
         * <p>An unimplemented placeholder is therefore inserted <em>literally</em>, which is wrong in a way
         * somebody reports rather than wrong in a way that silently swallows text.</p>
         */
        SNIPPET
    }

    /** The caret marker inside a {@link InsertTextFormat#SNIPPET} — LSP's {@code $0}. */
    public static final String CARET = "$0";

    public CompletionItem {
        if (label == null) label = "";
        if (kind == null) kind = SymbolKind.UNKNOWN;
        additionalTextEdits = additionalTextEdits == null || additionalTextEdits.isEmpty()
                ? List.of() : List.copyOf(additionalTextEdits);
        commitCharacters = commitCharacters == null || commitCharacters.isEmpty()
                ? List.of() : List.copyOf(commitCharacters);
        if (insertTextFormat == null) insertTextFormat = InsertTextFormat.PLAIN;
        modifiers = modifiers == null || modifiers.isEmpty() ? Set.of() : Set.copyOf(modifiers);
    }

    /** The simplest item: a word, and what it is. */
    public static CompletionItem of(String label, SymbolKind kind) {
        return builder(label, kind).build();
    }

    /**
     * An item built from a resolved symbol — the path every engine-backed provider takes.
     *
     * <p><b>The label carries the signature and the filter does not</b>, which is the four-field design
     * doing the one job it exists for: {@code getProperty(String, String)} is shown, {@code getProperty} is
     * typed, and two overloads stop being two identical rows the user cannot choose between.</p>
     */
    public static CompletionItem from(SymbolInfo symbol) {
        Builder builder = builder(symbol.name() + symbol.parameterList(), symbol.kind())
                .detail(symbol.type() == null ? symbol.container() : symbol.type().displayName())
                .documentation(symbol.documentation())
                .filterText(symbol.name())
                .sortText(symbol.name())
                .modifiers(symbol.modifiers())
                .deprecated(symbol.is(SymbolModifier.DEPRECATED));
        if (symbol.isInvocable()) {
            // ACCEPTING A METHOD WRITES ITS BRACKETS, with the caret inside when there is an argument to
            // type and after them when there is not. Leaving them out means every acceptance is followed
            // by typing `()` by hand; putting the caret past them means pressing Left before you can
            // start. Both references do exactly this.
            builder.insertText(symbol.parameters().isEmpty()
                    ? symbol.name() + "()" + CARET
                    : symbol.name() + "(" + CARET + ")").snippet();
        } else {
            builder.insertText(symbol.name());
        }
        return builder.build();
    }

    public static Builder builder(String label, SymbolKind kind) {
        return new Builder(label, kind);
    }

    // ── The falling-back readers, so consumers never repeat the null checks ──────────────────────

    /** {@link #sortText}, or {@link #label}. */
    public String sortKey() {
        return sortText == null ? label : sortText;
    }

    /** {@link #filterText}, or {@link #label}. */
    public String filterKey() {
        return filterText == null ? label : filterText;
    }

    /** {@link #insertText}, or {@link #label}. Ignored when {@link #textEdit} is present. */
    public String textToInsert() {
        return insertText == null ? label : insertText;
    }

    /** Whether this symbol carries {@code modifier} — what the icon's second axis is chosen from. */
    public boolean is(SymbolModifier modifier) {
        return modifiers.contains(modifier);
    }

    /** Whether {@link CompletionProvider#resolveItem} could still add anything. */
    public boolean needsResolution() {
        return documentation == null;
    }

    public CompletionItem withDocumentation(@Nullable String docs) {
        return new CompletionItem(label, kind, detail, docs, sortText, filterText, insertText,
                textEdit, additionalTextEdits, commitCharacters, command, insertTextFormat, deprecated,
                modifiers);
    }

    /**
     * Thirteen fields, of which a typical item sets three.
     *
     * <p>Hand-written rather than generated because the two required fields are constructor arguments
     * here: an item with no label is not a partially built item, it is a bug, and a builder that lets one
     * be built and fails later has moved the error away from its cause.</p>
     */
    public static final class Builder {
        private final String label;
        private final SymbolKind kind;
        private String detail;
        private String documentation;
        private String sortText;
        private String filterText;
        private String insertText;
        private Change textEdit;
        private List<Change> additionalTextEdits = List.of();
        private List<String> commitCharacters = List.of();
        private String command;
        private InsertTextFormat insertTextFormat = InsertTextFormat.PLAIN;
        private boolean deprecated;
        private Set<SymbolModifier> modifiers = Set.of();

        private Builder(String label, SymbolKind kind) {
            this.label = label;
            this.kind = kind;
        }

        public Builder detail(@Nullable String value) {
            this.detail = value;
            return this;
        }

        public Builder documentation(@Nullable String value) {
            this.documentation = value;
            return this;
        }

        public Builder sortText(@Nullable String value) {
            this.sortText = value;
            return this;
        }

        public Builder filterText(@Nullable String value) {
            this.filterText = value;
            return this;
        }

        public Builder insertText(@Nullable String value) {
            this.insertText = value;
            return this;
        }

        public Builder textEdit(@Nullable Change value) {
            this.textEdit = value;
            return this;
        }

        public Builder additionalTextEdits(Change... values) {
            this.additionalTextEdits = values == null ? List.of() : List.of(values);
            return this;
        }

        public Builder commitCharacters(String... values) {
            this.commitCharacters = values == null ? List.of() : List.of(values);
            return this;
        }

        public Builder command(@Nullable String commandId) {
            this.command = commandId;
            return this;
        }

        public Builder snippet() {
            this.insertTextFormat = InsertTextFormat.SNIPPET;
            return this;
        }

        public Builder deprecated(boolean value) {
            this.deprecated = value;
            return this;
        }

        public Builder modifiers(Set<SymbolModifier> value) {
            this.modifiers = value == null ? Set.of() : value;
            return this;
        }

        public CompletionItem build() {
            return new CompletionItem(label, kind, detail, documentation, sortText, filterText,
                    insertText, textEdit, additionalTextEdits, commitCharacters, command,
                    insertTextFormat, deprecated, modifiers);
        }
    }
}
