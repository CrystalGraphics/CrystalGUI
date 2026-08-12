package com.crystalgui.ui.elements.editor;

import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.WordClassifier;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.Versioned;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

/**
 * One completion interaction, from trigger to accept — §18.1's session, with no widget in it.
 *
 * <h3>Why the session is not the popup</h3>
 *
 * <p>Everything difficult here is a question about text and state: what the prefix is, whether a keystroke
 * re-filters or re-queries, when the session is over, and what a single accept does to the document. None of
 * it needs a window, and separating it is what makes the awkward cases testable — a callback arriving after
 * the session died, an {@code isIncomplete} list, an accept that also inserts an import. The popup reads
 * this and draws it.</p>
 *
 * <h3>A session survives typing</h3>
 *
 * <p>Each keystroke re-filters the list <em>locally</em> — no engine round trip — which is what makes the
 * list feel attached to the caret rather than trailing it. The exception is a list the provider marked
 * {@link CompletionList#incomplete}, which means "I truncated this, ask again as you narrow"; that
 * re-queries. Getting this backwards in either direction is visible: always re-querying makes every
 * keystroke wait on a compiler, and never re-querying makes a truncated list silently miss the item the
 * user is typing towards.</p>
 *
 * <h3>The callback may never fire, and that is the contract</h3>
 *
 * <p>{@link CompletionProvider} is explicit about it. So every answer is checked against the request that
 * asked for it — a late list from a superseded request is dropped rather than shown, which is the same
 * version discipline the diagnostics path uses and for the same reason: the alternative is a list that
 * describes a caret position the user has already left.</p>
 */
public final class CompletionSession {

    /** Fires whenever the visible rows change — opened, re-filtered, or a late answer landed. */
    public final Signal.Action onChanged = new Signal.Action();

    /** Fires when the session ends, for any reason. The popup hides on it. */
    public final Signal.Action onClosed = new Signal.Action();

    private final TextBuffer buffer;
    private final CompletionProvider provider;

    /**
     * Where the word being completed starts — fixed for the session's life.
     *
     * <p>The <b>replacement range's</b> start, not the caret: accepting replaces the partial word rather
     * than inserting beside it. Fixed rather than recomputed, because recomputing it from the caret after
     * every keystroke is how a session that began mid-word starts replacing the wrong span.</p>
     */
    private final int wordStart;

    private List<CompletionItem> unfiltered = List.of();
    private List<Row> rows = List.of();
    private int selected = -1;
    private boolean incomplete;
    private boolean closed;

    /** Whether any answer has landed. See {@link #caretMoved} — an empty list means nothing until one has. */
    private boolean answered;

    /** Bumped per request; an answer stamped with anything else is stale. */
    private int requestSerial;

    private CompletionSession(TextBuffer buffer, CompletionProvider provider, int wordStart) {
        this.buffer = buffer;
        this.provider = provider;
        this.wordStart = wordStart;
    }

    /**
     * A row: an item plus where the query hit it, so the popup bands the same characters that ranked it.
     *
     * <p>One object rather than two parallel lists, which is the arrangement that lets a highlight drift
     * out of step with the ranking — {@code QuickPickSource} already records why.</p>
     */
    public record Row(CompletionItem item, @Nullable SearchMatch match) {
    }

    // ── Opening ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Starts a session at {@code caret}, or returns null when there is nothing to complete.
     *
     * <p>Null rather than an empty session, because "no provider" and "a session showing nothing" are
     * different states and only one of them should put a popup on screen.</p>
     */
    @Nullable
    public static CompletionSession open(TextBuffer buffer, @Nullable CompletionProvider provider,
                                         int caret, CompletionProvider.TriggerKind trigger,
                                         @Nullable String triggerCharacter) {
        if (buffer == null || provider == null || provider == CompletionProvider.NONE) return null;
        int start = wordStartBefore(buffer, caret);
        CompletionSession session = new CompletionSession(buffer, provider, start);
        // BEFORE the request, and this is not bookkeeping. refilter() reads the caret to work out the
        // prefix, and a synchronous provider answers from inside request() -- so leaving it at zero meant
        // the very first filter ran against an EMPTY prefix and showed the whole list unranked. Visible
        // only as "the popup ignores what I already typed", and only for the first list of each session.
        session.lastKnownCaret = caret;
        session.request(caret, trigger, triggerCharacter);
        return session;
    }

    /**
     * The start of the identifier ending at {@code caret}.
     *
     * <p>Through {@link WordClassifier}, not a private {@code isLetterOrDigit} loop — a second definition of
     * "a word" is a second answer to what Ctrl+Left does, and the two would drift.</p>
     */
    private static int wordStartBefore(TextBuffer buffer, int caret) {
        String text = buffer.toString();
        int start = Math.max(0, Math.min(caret, text.length()));
        while (start > 0 && WordClassifier.DEFAULT.isWordPart(text.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    // ── Querying ────────────────────────────────────────────────────────────────────────────────

    private void request(int caret, CompletionProvider.TriggerKind trigger, @Nullable String character) {
        final int serial = ++requestSerial;
        CompletionProvider.Request ask = new CompletionProvider.Request(
                caret, prefixAt(caret), trigger, character);
        provider.complete(ask, answer -> accept(serial, answer));
    }

    /** Late or superseded answers are dropped — see the class note on the callback contract. */
    private void accept(int serial, @Nullable Versioned<CompletionList> answer) {
        if (closed || serial != requestSerial || answer == null) return;
        CompletionList list = answer.orElse(CompletionList.EMPTY);
        unfiltered = list.items();
        incomplete = list.incomplete();
        answered = true;
        refilter();
    }

    /** What has been typed since {@link #wordStart} — the string the list filters on. */
    public String prefix() {
        return prefixAt(caretOrEnd());
    }

    private String prefixAt(int caret) {
        String text = buffer.toString();
        int end = Math.max(0, Math.min(caret, text.length()));
        int start = Math.max(0, Math.min(wordStart, end));
        return text.substring(start, end);
    }

    private int caretOrEnd() {
        return lastKnownCaret;
    }

    private int lastKnownCaret;

    // ── Reacting to the document ────────────────────────────────────────────────────────────────

    /**
     * Told where the caret is now. Re-filters, re-queries, or ends the session.
     *
     * <p>Called from the editor rather than subscribed here, because the session must react to the caret
     * <em>and</em> the text and only the editor sees both settle together. Subscribing to the buffer alone
     * would miss a plain arrow-key move, which is one of the two ways a session should end.</p>
     */
    public void caretMoved(int caret) {
        if (closed) return;
        lastKnownCaret = caret;
        // OUT OF THE REPLACEMENT RANGE, so this is no longer the word the session was about. Before the
        // start is unambiguous; past the end cannot happen without the text growing, which arrives here too.
        if (caret < wordStart) {
            close();
            return;
        }
        // NOTHING HAS ANSWERED YET, so there is nothing to filter and nothing to conclude from the list
        // being empty. The provider contract is asynchronous, so this is the ordinary case for any engine
        // that does not answer inline -- and without the guard the first keystroke after opening killed the
        // session before its first list ever arrived, which reads as the popup never appearing at all.
        if (!answered) return;
        if (incomplete) {
            // The provider said it truncated. Narrowing the prefix can reach items it never sent, so the
            // only correct answer is to ask again -- a local filter over a truncated list silently omits
            // exactly the item being typed towards.
            request(caret, CompletionProvider.TriggerKind.RETRIGGER, null);
            return;
        }
        refilter();
    }

    /**
     * Re-ranks the held list against the current prefix.
     *
     * <p><b>An empty prefix skips the MATCHER but not the ranking</b>, and the distinction is the whole of
     * this method. There is nothing to match against, and running an empty query through the matcher returns
     * null for every row — which would empty the list rather than show it. But the rows still have to be
     * <em>ordered</em>, and this is exactly the case where the order matters most, because an empty prefix
     * is the moment the popup opens and the user is reading rather than typing.</p>
     *
     * <h3>The provider's own order is not a signal, and assuming it was hid three rows</h3>
     *
     * <p>This used to hand the list through untouched on the grounds that the engine had put the useful
     * things first. It had not: {@code collectMembers} walks every declared <em>method</em> and then every
     * declared <em>field</em>, which is an artefact of two loops, not a judgement. So {@code System.} opened
     * with forty methods and put {@code out}, {@code err} and {@code in} at position forty-one — below an
     * eleven-row window, and therefore invisible. It read as the fields being missing entirely, and a test
     * asserting the list was non-empty passed straight through it.</p>
     *
     * <p>Ranked, the same list opens with the three fields, because {@code CompletionRanking} scores a field
     * nearer than a method. That is not a coincidence — it is the weigher chain doing the job the provider
     * was wrongly credited with.</p>
     */
    private void refilter() {
        String prefix = prefix();
        List<Row> next = new ArrayList<>();
        if (prefix.isEmpty()) {
            for (CompletionItem item : unfiltered) next.add(new Row(item, null));
            // Every row ties on match tier (there is no match), so the chain falls straight through to
            // deprecation, then proximity, then the provider's sortText -- which is the order to show.
            next.sort(CompletionRanking.byQuality());
        } else {
            SearchQuery query = SearchQuery.of(prefix);
            for (CompletionItem item : unfiltered) {
                // SUBSEQUENCE ON: fMS reaching fooMethodStuff is the headline behaviour of a completion
                // list, and the tier sits below every real substring hit so nothing else re-ranks.
                SearchMatch match = SearchMatcher.match(query, item.filterKey(),
                        SearchMatch.FIELD_PRIMARY, true);
                if (match != null) next.add(new Row(item, match));
            }
            next.sort(CompletionRanking.byQuality());
        }
        rows = List.copyOf(next);
        selected = rows.isEmpty() ? -1 : 0;
        onChanged.emit();
        // A SESSION WITH NOTHING LEFT IS OVER. Leaving an empty popup on screen means the next character
        // typed is aimed at a widget showing nothing, and Enter -- which the popup would still be eating --
        // does nothing at all rather than inserting a newline.
        if (rows.isEmpty() && !incomplete) close();
    }

    // ── Selection ───────────────────────────────────────────────────────────────────────────────

    public List<Row> visibleRows() {
        return rows;
    }

    public int selectedIndex() {
        return selected;
    }

    @Nullable
    public CompletionItem selectedItem() {
        return selected < 0 || selected >= rows.size() ? null : rows.get(selected).item();
    }

    /** Moves the selection, wrapping — the list is a cycle, as every reference implementation's is. */
    public void moveSelection(int delta) {
        if (rows.isEmpty()) return;
        selected = Math.floorMod(selected + delta, rows.size());
        onChanged.emit();
    }

    public void setSelectedIndex(int index) {
        if (index < 0 || index >= rows.size() || index == selected) return;
        selected = index;
        onChanged.emit();
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isIncomplete() {
        return incomplete;
    }

    /** Where accepting would replace from — exposed so the popup can anchor itself to the word, not the caret. */
    public int replacementStart() {
        return wordStart;
    }

    // ── Accepting ───────────────────────────────────────────────────────────────────────────────

    /**
     * Applies the selected item to the document as <b>one</b> edit, and ends the session.
     *
     * <h3>One {@link ChangeSet}, which is what makes it one undo step</h3>
     *
     * <p>The primary edit inserts the name; {@link CompletionItem#additionalTextEdits()} is everywhere else
     * that has to change, which in practice means the import. Applying them separately gives two undo steps
     * for one keystroke — press Ctrl+Z after accepting an unimported type and the name goes but the import
     * stays, which is the behaviour every editor that has it is criticised for.</p>
     *
     * <p>{@code ChangeSet.of} refuses overlapping changes, so a provider that emits an additional edit
     * covering the primary one fails loudly here rather than producing whichever result the ordering
     * happened to give.</p>
     *
     * @return false when there was nothing selected to accept
     */
    public boolean accept() {
        return accept(false);
    }

    /**
     * @param replace whether to consume the rest of the identifier the caret sits in — Tab's behaviour,
     *                against Enter's insert. Editing {@code getNa|meOf} and accepting {@code getName}
     *                leaves {@code getNameOf} on insert and {@code getName} on replace, and both are
     *                wanted often enough that every reference implementation binds a key to each. The
     *                strip at the bottom of the popup says which key does which, so this must actually
     *                differ or the strip is a promise the widget does not keep.
     */
    public boolean accept(boolean replace) {
        CompletionItem item = selectedItem();
        if (item == null) {
            close();
            return false;
        }
        int caret = Math.max(wordStart, Math.min(lastKnownCaret, buffer.length()));
        if (replace) caret = Math.max(caret, identifierEndAt(caret));
        Change primary = item.textEdit() != null
                ? item.textEdit()
                : new Change(wordStart, caret, item.textToInsert());

        List<Change> changes = new ArrayList<>(item.additionalTextEdits());
        changes.add(primary);
        // Sorted because ChangeSet.of REQUIRES it and refuses to normalise -- two changes that overlap have
        // no defined combined meaning, so accepting them unsorted would make the result depend on iteration
        // order. An import edit is near the top of the file and the name is wherever the caret is, so the
        // list arrives out of order essentially always.
        changes.sort(Comparator.comparingInt(Change::from));

        buffer.edit(ChangeSet.of(buffer.length(), changes));
        close();
        return true;
    }

    /** The end of the identifier {@code from} sits inside — where a replacing accept consumes to. */
    private int identifierEndAt(int from) {
        String text = buffer.toString();
        int end = Math.max(0, Math.min(from, text.length()));
        while (end < text.length() && WordClassifier.DEFAULT.isWordPart(text.charAt(end))) end++;
        return end;
    }

    /** Where the caret should land after {@link #accept} — the end of the inserted text. */
    public int caretAfterAccept(CompletionItem item, int caretBefore) {
        Change primary = item.textEdit() != null
                ? item.textEdit()
                : new Change(wordStart, caretBefore, item.textToInsert());
        int shift = 0;
        for (Change extra : item.additionalTextEdits()) {
            if (extra.from() <= primary.from()) shift += extra.delta();
        }
        return primary.from() + primary.inserted() + shift;
    }

    public void close() {
        if (closed) return;
        closed = true;
        rows = List.of();
        unfiltered = List.of();
        selected = -1;
        onClosed.emit();
    }
}
