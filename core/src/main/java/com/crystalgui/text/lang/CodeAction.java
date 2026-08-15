package com.crystalgui.text.lang;

import com.crystalgui.text.ChangeSet;

import java.util.Comparator;
import java.util.Map;

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
 * <h3>{@code id} is the identity; {@code title} is prose</h3>
 *
 * <p>They are separate because everything that needs to <em>name</em> an action needs something the title
 * cannot be. A title carries the offending symbol ("Remove variable 's'"), so it is not even constant
 * between two invocations of the same correction; it is worded for a reader, so it gets reworded; and if
 * this UI is ever translated it stops being English while every test, log line and keybinding that named
 * it keeps expecting it to be. IntelliJ separates {@code getName} from {@code getFamilyName} for this,
 * and LSP carries an opaque {@code data} field for the same reason.</p>
 *
 * <p>So an id is a stable dotted string — {@code "java.unused.removeImport"} — chosen by whoever wrote the
 * correction and never shown to anyone. It is what a test asserts on, and what a future "apply this fix
 * without asking" binding would name.</p>
 *
 * <p><b>It names the correction, not the row.</b> One correction may answer with several actions — an
 * unresolved {@code List} offers an import per candidate — and those share an id, because they are one
 * piece of logic offering alternatives rather than several corrections. What separates them is the title,
 * which is the single place a title carries meaning the id does not, and the reason this is written down
 * is that it is the obvious thing to "fix" by making ids unique per row. Doing that would make the id a
 * row identifier, which is what the title already is.</p>
 *
 * <h3>A command carries its arguments</h3>
 *
 * <p>LSP's {@code Command} has an {@code arguments} array and this has a map, for the same reason: an
 * action that runs a command has to be able to say <em>what about</em>. Without it the command re-derives
 * its subject at run time — and the first consumer here was exactly that bug: "Copy problem message"
 * read the problems at the <b>caret</b> when it ran, while the popup it sits in can be opened from a stripe
 * mark or a hover nowhere near the caret. Now the action carries the message it was offered about.</p>
 *
 * <p>Strings only, because this crosses the {@code core/} ↔ {@code language/} boundary as data and a typed
 * payload would need a type both sides agree on for every new command. A command that needs more than a
 * few strings is a command that wants an edit instead.</p>
 *
 * @param id        stable, never displayed — {@code "java.unused.removeImport"}
 * @param title     what the row says, already in the user's words — "Remove unused import"
 * @param kind      where it sorts, and what it is
 * @param edit      the change to apply, in offsets against {@link #version}, or null
 * @param commandId a {@code CommandRegistry} id to invoke, or null
 * @param arguments what the command is about, by name — empty for an edit-only action
 * @param preferred whether this is <em>the</em> fix — LSP's {@code isPreferred}. It <b>ranks</b>, through
 *                  {@link #ORDER}: a preferred fix sorts first among its tier and so takes the popup's
 *                  inline slot. It does not gate that slot — the popup shows whichever fix ranks first,
 *                  because most real fixes come in families where none is unambiguously the answer
 *                  (an import per candidate, a rename per near miss) and those problems must still offer
 *                  one thing to press. @see DocumentationPopup's primary row
 * @param version   the document version {@link #edit} was computed against
 */
public record CodeAction(String id, String title, CodeActionKind kind, @Nullable ChangeSet edit,
                         @Nullable String commandId, Map<String, String> arguments,
                         boolean preferred, long version) {

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
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("an action needs an id");
        if (title == null || title.isEmpty()) throw new IllegalArgumentException("an action needs a title");
        if (kind == null) kind = CodeActionKind.QUICK_FIX;
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /** An edit-only action — the shape every correction produces, with nothing for a command to read. */
    public CodeAction(String id, String title, CodeActionKind kind, @Nullable ChangeSet edit,
                      @Nullable String commandId, boolean preferred, long version) {
        this(id, title, kind, edit, commandId, Map.of(), preferred, version);
    }

    /** The common shape: a fix that edits the document. */
    public static CodeAction fix(String id, String title, ChangeSet edit, long version) {
        return new CodeAction(id, title, CodeActionKind.QUICK_FIX, edit, null, false, version);
    }

    /** As {@link #fix}, and marked as the one to show without being asked. */
    public static CodeAction preferredFix(String id, String title, ChangeSet edit, long version) {
        return new CodeAction(id, title, CodeActionKind.QUICK_FIX, edit, null, true, version);
    }

    /** An action with no edit — it runs a registered command instead. */
    public static CodeAction command(String id, String title, CodeActionKind kind, String commandId) {
        return new CodeAction(id, title, kind, null, commandId, false, 0L);
    }

    /** As {@link #command}, carrying what the command is about. */
    public static CodeAction command(String id, String title, CodeActionKind kind, String commandId,
                                     Map<String, String> arguments) {
        return new CodeAction(id, title, kind, null, commandId, arguments, false, 0L);
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
