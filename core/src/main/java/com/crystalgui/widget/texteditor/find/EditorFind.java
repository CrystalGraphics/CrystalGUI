package com.crystalgui.widget.texteditor.find;

import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Selection;
import com.crystalgui.text.WordOperations;
import com.crystalgui.text.search.SearchResults;
import com.crystalgui.text.search.TextSearch;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.text.TextRange;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.widget.texteditor.doc.HoverDocumentation;
import dev.vfyjxf.taffy.style.TaffyPosition;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Find and replace</b> — the query, the matches, the cursor over them, and the bar.
 *
 * <p>One of the four subsystems that lived inline on {@code TextEditor} with their fields scattered
 * through it. It is a contribution given the editor, exactly as {@code SearchReplaceBar} and
 * {@code HoverDocumentation} already are, and it reaches the editor through the same package-private
 * accessors the view parts use.</p>
 *
 * <h3>What is the document's, and what is the view's</h3>
 *
 * <p>The <b>scan</b> is {@link TextSearch}'s and lives on the model side; what stays here is what a view
 * genuinely owns — which match is selected, whether the highlights need repainting, and where the caret
 * goes. That line was already drawn and is worth restating, because it is why this class holds a cursor
 * and not an algorithm.</p>
 *
 * <h3>Two ways to select a match, and the difference is the whole of the feature</h3>
 *
 * <p><b>Stepping</b> (Enter, F3, the bar's arrows) anchors on the caret, wants a match it is not already
 * on, and centres one it had to scroll to — arriving somewhere new deserves the most context.
 * <b>Typing</b> a query anchors on the first visible line, accepts a match starting exactly there, and
 * must not move the document under the reader. Running the stepping version on every keystroke is what
 * used to scroll the file back to wherever the caret was last clicked.</p>
 */
public final class EditorFind {

    private final TextEditor editor;

    public EditorFind(TextEditor editor) {
        this.editor = editor;
    }

    // ── The query and its matches ───────────────────────────────────────────────────────────────

    private SearchResults results = SearchResults.EMPTY;

    @Nullable
    private SearchQuery lastSearch;

    /** Replace edits the buffer, which re-enters the change listener; one pass is enough. */
    private boolean reentrant;

    private boolean preserveCase;

    @Nullable
    private SearchReplaceBar bar;

    /**
     * Finds every occurrence and publishes them under {@code ::highlight(search)}.
     *
     * <p>Whole-document rather than viewport-bounded, unlike syntax highlighting, and for a reason: the
     * match <em>count</em> is the answer the user wants, and "3 of 47" cannot be computed from what is on
     * screen. The ranges themselves are still only rendered for realised rows.</p>
     *
     * <p>An <b>uncompilable pattern finds nothing</b> and does not throw — it is recompiled on every
     * keystroke while a regex is being typed.</p>
     *
     * @return how many matches there are
     */
    public int find(@Nullable SearchQuery query) {
        lastSearch = query == null || query.isEmpty() ? null : query;
        // THE SCAN IS THE DOCUMENT'S, not this widget's -- see TextSearch.
        results = results.withMatches(TextSearch.findAll(editor.buffer().document(), lastSearch));
        editor.markHighlightsDirty();
        return results.size();
    }

    public int find(String query, boolean caseSensitive) {
        return find(SearchQuery.of(query, SearchQuery.Options.DEFAULT.withMatchCase(caseSensitive)));
    }

    /**
     * Re-runs the last query after an edit — <b>the reason undo does not have to know search exists</b>.
     *
     * <p>Offsets found against the old text describe the new one wrongly: the count goes stale and the
     * highlights sit over whatever moved into their place. Every edit passes through one signal, so
     * re-running from there covers typing, paste, replace, undo and redo without any of them naming this.</p>
     */
    public void refreshAfterEdit() {
        if (lastSearch == null || reentrant) return;
        reentrant = true;
        try {
            find(lastSearch);
        } finally {
            reentrant = false;
        }
    }

    public SearchResults results() {
        return results;
    }

    public List<TextRange> matches() {
        return results.matches();
    }

    public List<TextRange> excludedRanges() {
        return results.excludedRanges();
    }

    public int matchCount() {
        return results.size();
    }

    /** Which match is selected, 1-based for display, or 0 when none is. */
    public int currentMatchNumber() {
        return results.currentNumber();
    }

    /**
     * Excludes the selected match from Replace All, or puts it back — IntelliJ's <b>Exclude</b>.
     *
     * <p>The span stays in the list and stays visible; it is struck through instead.</p>
     */
    public boolean toggleExcludeCurrentMatch() {
        boolean changed = results.toggleExcludeCurrent();
        if (changed) editor.markHighlightsDirty();
        return changed;
    }

    // ── Stepping ────────────────────────────────────────────────────────────────────────────────

    /** Selects the next match after the caret, wrapping. */
    public boolean findNext() {
        if (results.isEmpty()) return false;
        int caret = editor.getCaret();
        int next = 0;
        for (int i = 0; i < results.size(); i++) {
            if (results.matches().get(i).start() > caret) {
                next = i;
                break;
            }
            // No else. `next` starts at 0 and stays there, which IS the wrap: running off the end of a
            // document whose last match is behind the caret returns to the first one.
        }
        return selectMatch(next, true);
    }

    /** Selects the previous match before the caret, wrapping. */
    public boolean findPrevious() {
        if (results.isEmpty()) return false;
        int caret = editor.getSelectionStart();
        int previous = results.size() - 1;
        for (int i = results.size() - 1; i >= 0; i--) {
            if (results.matches().get(i).start() < caret) {
                previous = i;
                break;
            }
        }
        return selectMatch(previous, true);
    }

    /**
     * Selects the first match at or after {@code offset}, wrapping — what a <b>fresh</b> query does.
     *
     * <p>See the class note: this takes an anchor rather than reading the caret, and accepts a match
     * starting exactly <em>at</em> it.</p>
     */
    public boolean findFrom(int offset) {
        if (results.isEmpty()) return false;
        if (!results.moveToFirstAtOrAfter(offset)) return false;
        // NOT CENTRED. This runs on every keystroke in the find box.
        return selectMatch(results.current(), false);
    }

    /** Searches for the word under the caret — {@code Ctrl+F3}. */
    public boolean findWordUnderCaret() {
        Selection primary = editor.selections().primary();
        int start = primary.start();
        int end = primary.end();
        if (primary.isEmpty()) {
            int[] word = WordOperations.wordAt(
                    editor.buffer().document(), primary.head(), editor.wordClassifier());
            if (word == null) return false;
            start = word[0];
            end = word[1];
        }
        if (end <= start) return false;
        find(editor.buffer().document().slice(start, end).toString(), false);
        return findNext();
    }

    /**
     * @param centre whether an off-screen match is <b>centred</b> (stepping) or merely brought into view
     *               (a query being typed, which must not move the document under the reader)
     */
    private boolean selectMatch(int index, boolean centre) {
        if (!results.moveTo(index)) return false;
        TextRange match = results.currentMatch();
        if (match == null) return false;
        editor.setSelection(match.start(), match.end());
        // CENTRED, AND ONLY WHEN IT HAS TO MOVE AT ALL -- IntelliJ's ScrollType.CENTER. Minimal scrolling
        // frames the destination hard against an edge with all the surrounding code on one side; a match
        // already on screen must not move the view, or every press of Enter would lurch the file.
        if (centre) {
            if (!editor.caretIsInView()) editor.revealCaretCentred();
        } else {
            editor.ensureCaretVisible();
        }
        return true;
    }

    // ── Replacing ───────────────────────────────────────────────────────────────────────────────

    /** Whether a replacement should take the case of what it replaced. @see TextSearch#preserveCase */
    public void setPreserveCase(boolean value) {
        this.preserveCase = value;
    }

    public boolean preserveCase() {
        return preserveCase;
    }

    /** Replaces the selected match and finds the next. */
    public boolean replaceCurrent(String replacement) {
        TextRange match = results.currentMatch();
        if (match == null) return false;
        String text = replacement == null ? "" : replacement;
        editor.buffer().replace(match.start(), match.end(), replacementFor(match, text));
        editor.buffer().breakUndoCoalescing();
        find(lastSearch);
        return true;
    }

    /**
     * Replaces every match as <b>one</b> edit.
     *
     * <p>One {@link ChangeSet} rather than a loop of replacements: a loop would invalidate every later
     * offset after the first, and would put each replacement on the undo stack separately — so undoing a
     * replace-all would take one press per match.</p>
     *
     * @return how many were replaced
     */
    public int replaceAll(String replacement) {
        // WHAT THE USER DID NOT STRIKE OUT. `included()` is the whole point of Exclude: the excluded spans
        // stay in the list and stay drawn, and only this skips them.
        List<TextRange> targets = results.included();
        if (targets.isEmpty()) return 0;
        String text = replacement == null ? "" : replacement;
        List<Change> changes = new ArrayList<>(targets.size());
        for (TextRange match : targets) {
            changes.add(new Change(match.start(), match.end(), replacementFor(match, text)));
        }
        int replaced = changes.size();
        ChangeSet edit = ChangeSet.of(editor.buffer().length(), changes);
        editor.buffer().edit(edit, editor.selections().all());
        editor.buffer().breakUndoCoalescing();
        editor.selections().mapThrough(edit).collapseEachToHead();
        find(lastSearch);
        return replaced;
    }

    /** The replacement as it will be written — shaped like what it replaces when Preserve case is on. */
    private String replacementFor(TextRange match, String text) {
        return preserveCase ? TextSearch.preserveCase(textIn(match), text) : text;
    }

    /** The document text a match covers — what Preserve case reads to decide the replacement's shape. */
    private String textIn(TextRange range) {
        // SLICED, not substringed out of a copy of the file. `replaceAll` calls this once PER MATCH, so a
        // document copy here made replacing n things cost n documents.
        int length = editor.buffer().length();
        int from = Math.max(0, Math.min(range.start(), length));
        int to = Math.max(from, Math.min(range.end(), length));
        return editor.buffer().document().slice(from, to).toString();
    }

    // ── The bar ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The find &amp; replace bar, built on first use and floated over the editor's top edge.
     *
     * <p>Floated rather than stacked above, which is what Monaco does: the editor's layout maths — view
     * lines, the gutter, scroll extents — all measure against its own box, and pushing content down would
     * put the bar inside every one of those sums.</p>
     */
    public SearchReplaceBar bar() {
        if (bar == null) {
            bar = new SearchReplaceBar(editor);
            bar.addClass("__editor-find__");
            // PINNED TO THE VIEWPORT, not to the text. An absolute child of a scroller still moves with
            // the content -- `top: 0` means the top of the DOCUMENT, so the bar scrolled away and left the
            // editor behind it. `setScrollExempt` is what holds a decoration still while the text moves.
            bar.setScrollExempt(true);
            bar.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).top(0f).left(0f).widthPercent(100f));
            bar.setDisplayed(false);
            editor.append(bar);
            bar.onClosed.connect(() -> {
                UIDocument window = editor.document();
                if (window != null) window.focus().requestPointerFocus(editor);
            });
        }
        return bar;
    }
}
