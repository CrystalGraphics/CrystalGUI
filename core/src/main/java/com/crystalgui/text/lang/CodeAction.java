package com.crystalgui.text.lang;

import com.crystalgui.text.ChangeSet;

import java.util.Comparator;

import javax.annotation.Nullable;

/**
 * One thing offered about a problem — LSP's {@code CodeAction}, ported.
 *
 * <h3>Data, with no behaviour, and that is the whole point</h3>
 *
 * <p>An action is produced by an engine that {@code core/} cannot see and is rendered and applied by a
 * widget that knows nothing about Java. IntelliJ's {@code LocalQuickFix} is an object with an
 * {@code applyFix} method, which works there because the inspection, the fix and the editor are all in
 * one process with one type system. Here the fix would have to cross the {@code core/} ↔ {@code language/}
 * boundary that exists so a dedicated server never loads ECJ — so what crosses is a description of an
 * edit, not something that can perform one.</p>
 *
 * <p>Two carriers rather than one because there are genuinely two kinds of action:</p>
 *
 * <ul>
 *   <li>an <b>edit</b> — the overwhelming majority, and the only kind that is undoable for free: a
 *       {@link ChangeSet} through {@code TextBuffer.edit} is one entry on the document's undo stack
 *       however many places it touches;</li>
 *   <li>a <b>command id</b> — for something that is not a text edit at all. "At least one of the problems
 *       in category 'unused' is not analysed due to a compiler option being ignored" is fixed by changing
 *       a setting, and there is no edit that expresses it.</li>
 * </ul>
 *
 * <p>Both may be present: a fix that edits the file <em>and</em> wants a panel opened afterwards. Neither
 * being present is legal too, and means a row that is shown and does nothing — which is what a disabled
 * entry is, and is better than hiding it (the menu rules already argue this at length).</p>
 *
 * @param title     what the row says, already in the user's words — "Remove unused import"
 * @param kind      where it sorts, and what it is
 * @param edit      the change to apply, in offsets against {@link #version}, or null
 * @param commandId a {@code CommandRegistry} id to invoke, or null
 * @param preferred whether this is <em>the</em> fix — LSP's {@code isPreferred}. The popup shows one
 *                  preferred action inline with its accelerator and hides the rest behind "More actions…"
 * @param version   the document version {@link #edit} was computed against
 */
public record CodeAction(String title, CodeActionKind kind, @Nullable ChangeSet edit,
                         @Nullable String commandId, boolean preferred, long version) {

    /**
     * Tier, then preferred, then insertion order — which the sort being <b>stable</b> preserves.
     *
     * <p>Deliberately not a total order. Two contributors that both return an unpreferred quick fix are
     * equal here and stay in the order they were merged in, because nothing knows enough to separate
     * them and inventing a tiebreak is how a ranking starts lying.</p>
     */
    public static final Comparator<CodeAction> ORDER =
            Comparator.<CodeAction>comparingInt(a -> a.kind().tier())
                    .thenComparing(a -> a.preferred() ? 0 : 1);

    public CodeAction {
        if (title == null || title.isEmpty()) throw new IllegalArgumentException("an action needs a title");
        if (kind == null) kind = CodeActionKind.QUICK_FIX;
    }

    /** The common shape: a fix that edits the document. */
    public static CodeAction fix(String title, ChangeSet edit, long version) {
        return new CodeAction(title, CodeActionKind.QUICK_FIX, edit, null, false, version);
    }

    /** As {@link #fix}, and marked as the one to show without being asked. */
    public static CodeAction preferredFix(String title, ChangeSet edit, long version) {
        return new CodeAction(title, CodeActionKind.QUICK_FIX, edit, null, true, version);
    }

    /** An action with no edit — it runs a registered command instead. */
    public static CodeAction command(String title, CodeActionKind kind, String commandId) {
        return new CodeAction(title, kind, null, commandId, false, 0L);
    }

    /**
     * Whether this action can still be applied to a document at {@code currentVersion}.
     *
     * <p><b>The one check that keeps this feature from corrupting files.</b> An edit is a set of offsets,
     * and offsets into a document that has since been typed in still resolve — they simply name different
     * text. So a stale action does not fail, it silently edits the wrong place.</p>
     *
     * <p>An action with no edit is always applicable: a command names what it acts on rather than where,
     * so there is nothing to go stale.</p>
     */
    public boolean isApplicableTo(long currentVersion) {
        return edit == null || version == currentVersion;
    }
}
