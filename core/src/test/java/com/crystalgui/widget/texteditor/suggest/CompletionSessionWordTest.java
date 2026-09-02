package com.crystalgui.widget.texteditor.suggest;

import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.widget.texteditor.suggest.CompletionSession;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>Which word a session is about, and what happens when the caret leaves it.</b>
 *
 * <p>The prefix is {@code text.substring(wordStart, caret)}, and {@code wordStart} was fixed at the
 * moment the session opened. So typing a {@code .} did not begin a new word — it made the prefix
 * {@code "out."}, which still starts with the queried {@code "out"}, so the narrowing check passed and
 * the session went on filtering the <em>previous</em> list against a string containing a dot.</p>
 *
 * <p>Nothing matches that, and an empty list from a filter looks exactly like an empty list from a
 * provider: {@code System.out.} opened a popup with no rows. Where a fuzzy match happened to survive it
 * was worse — a few unrelated rows, which reads as the member list being wrong rather than missing.</p>
 */
public class CompletionSessionWordTest {

    /** Records what it was asked, and answers with whatever the current context should produce. */
    private static final class Recording implements CompletionProvider {
        final List<String> prefixes = new ArrayList<>();
        List<CompletionItem> answer = List.of();

        @Override
        public void complete(Request request, java.util.function.Consumer<Versioned<CompletionList>> to) {
            prefixes.add(request.prefix());
            to.accept(Versioned.of(1, CompletionList.complete(answer)));
        }

        @Override
        public void resolveItem(CompletionItem item, java.util.function.Consumer<CompletionItem> answer) {
            answer.accept(item);
        }
    }

    private static List<CompletionItem> items(String... names) {
        List<CompletionItem> made = new ArrayList<>();
        for (String name : names) made.add(CompletionItem.builder(name, SymbolKind.METHOD).build());
        return made;
    }

    /**
     * <b>Typing a dot asks again instead of filtering the old list.</b>
     *
     * <p>The assertion is on the <em>question</em> as well as the answer: a session that re-queried with
     * the stale prefix would produce rows too, and only asking with an empty prefix means it understood
     * that a new word had begun.</p>
     */
    @Test
    public void aDotStartsANewWordAndReQueries() {
        TextBuffer buffer = new TextBuffer("System.out");
        Recording provider = new Recording();
        provider.answer = items("out", "err", "in");

        CompletionSession session = CompletionSession.open(buffer, provider, buffer.length(),
                CompletionProvider.TriggerKind.EXPLICIT, null);
        assertNotNull(session);
        assertEquals("the first query is for the word under the caret", "out", provider.prefixes.get(0));

        // Type the dot, exactly as the editor would: the text grows, then the caret is reported.
        buffer.replace(buffer.length(), buffer.length(), ".");
        provider.answer = items("println", "printf", "flush");
        session.caretMoved(buffer.length());

        assertEquals("a dot must re-query, not refilter", 2, provider.prefixes.size());
        assertEquals("the new word is empty, not \"out.\"", "", provider.prefixes.get(1));
        assertFalse("the popup was left with no rows", session.visibleRows().isEmpty());
        assertEquals(3, session.visibleRows().size());
    }

    /**
     * And typing an ordinary character still filters rather than re-querying.
     *
     * <p>Without this the fix would be a re-query per keystroke, which is the cost the held list exists
     * to avoid — and the reason the narrowing check is worth keeping beside the new one.</p>
     */
    @Test
    public void anOrdinaryCharacterStillFiltersLocally() {
        TextBuffer buffer = new TextBuffer("System.out.");
        Recording provider = new Recording();
        provider.answer = items("println", "printf", "flush");

        CompletionSession session = CompletionSession.open(buffer, provider, buffer.length(),
                CompletionProvider.TriggerKind.EXPLICIT, null);
        assertNotNull(session);
        assertEquals(1, provider.prefixes.size());

        buffer.replace(buffer.length(), buffer.length(), "pri");
        session.caretMoved(buffer.length());

        assertEquals("narrowing within a word must not go back to the provider",
                1, provider.prefixes.size());
        assertEquals("pri", session.prefix());
        assertEquals(2, session.visibleRows().size());
    }

    /**
     * A second dot behaves like the first — the anchor moves every time the word does.
     *
     * <p>Every step lands on an empty prefix, which is not laziness in the fixture: a session whose filter
     * empties the list <b>closes itself</b> (see the note on {@code refilter}), so answers that fail to
     * match the prefix would end the session before the second dot was ever typed, and the test would fail
     * for a reason that has nothing to do with re-anchoring.</p>
     */
    @Test
    public void eachNewWordReAnchors() {
        TextBuffer buffer = new TextBuffer("a.");
        Recording provider = new Recording();
        provider.answer = items("one", "two");

        CompletionSession session = CompletionSession.open(buffer, provider, buffer.length(),
                CompletionProvider.TriggerKind.EXPLICIT, null);
        assertNotNull(session);

        buffer.replace(buffer.length(), buffer.length(), "b.");
        session.caretMoved(buffer.length());
        buffer.replace(buffer.length(), buffer.length(), "c.");
        session.caretMoved(buffer.length());

        assertTrue("every word boundary should have asked again", provider.prefixes.size() >= 3);
        assertEquals("", provider.prefixes.get(provider.prefixes.size() - 1));
    }
}
