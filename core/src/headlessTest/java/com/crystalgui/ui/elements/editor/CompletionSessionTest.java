package com.crystalgui.ui.elements.editor;

import com.crystalgui.text.Change;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.Versioned;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * §18's session, tested without a widget.
 *
 * <p>Headless on purpose, and it is a claim rather than a convenience: everything difficult about
 * completion — what the prefix is, when a keystroke re-filters versus re-queries, when the session is
 * over, what one accept does to the document — is a question about <b>text</b>. If any of it needed a
 * window, the session and the popup would not be separable and the awkward cases below could only be
 * reached through pixels.</p>
 */
public class CompletionSessionTest {

    /** A provider that answers from a fixed list and counts how often it was asked. */
    private static final class StubProvider implements CompletionProvider {
        final List<CompletionItem> items = new ArrayList<>();
        boolean incomplete;
        int requests;
        Consumer<Versioned<CompletionList>> pending;
        boolean deferred;

        /** Every callback handed out, so a test can answer an OLD one after a newer request. */
        final List<Consumer<Versioned<CompletionList>>> handedOut = new ArrayList<>();

        @Override
        public void complete(Request request, Consumer<Versioned<CompletionList>> answer) {
            requests++;
            handedOut.add(answer);
            CompletionList list = incomplete
                    ? CompletionList.partial(List.copyOf(items))
                    : CompletionList.complete(List.copyOf(items));
            if (deferred) pending = answer;
            else answer.accept(Versioned.of(0, list));
        }

        @Override
        public void resolveItem(CompletionItem item, Consumer<CompletionItem> answer) {
            answer.accept(item);
        }

        void releaseDeferred(List<CompletionItem> what) {
            if (pending != null) pending.accept(Versioned.of(0, CompletionList.complete(what)));
        }
    }

    private static CompletionItem item(String label, SymbolKind kind) {
        return CompletionItem.of(label, kind);
    }

    private static List<String> labelsOf(CompletionSession session) {
        List<String> labels = new ArrayList<>();
        for (CompletionSession.Row row : session.visibleRows()) labels.add(row.item().label());
        return labels;
    }

    // ── Opening and filtering ───────────────────────────────────────────────────────────────────

    @Test
    public void anEmptyPrefixKeepsEveryItemInTheProvidersOwnOrder() {
        // The provider's order is a real signal -- it put the receiver's own members first -- and an
        // empty query has nothing to rank by. Running one through the matcher returns null for every
        // row, which would empty the list rather than showing it.
        TextBuffer buffer = new TextBuffer("x.");
        StubProvider provider = new StubProvider();
        provider.items.add(item("zebra", SymbolKind.METHOD));
        provider.items.add(item("alpha", SymbolKind.METHOD));

        CompletionSession session = CompletionSession.open(buffer, provider, 2,
                CompletionProvider.TriggerKind.CHARACTER, ".");

        assertNotNull(session);
        assertEquals(List.of("zebra", "alpha"), labelsOf(session));
    }

    @Test
    public void typingFiltersLocallyWithoutAskingTheProviderAgain() {
        TextBuffer buffer = new TextBuffer("");
        StubProvider provider = new StubProvider();
        provider.items.add(item("println", SymbolKind.METHOD));
        provider.items.add(item("printf", SymbolKind.METHOD));
        provider.items.add(item("close", SymbolKind.METHOD));

        CompletionSession session = CompletionSession.open(buffer, provider, 0,
                CompletionProvider.TriggerKind.EXPLICIT, null);
        assertEquals(1, provider.requests);

        buffer.insert(0, "pri");
        session.caretMoved(3);

        assertEquals("a keystroke must not cost an engine round trip", 1, provider.requests);
        assertEquals(List.of("printf", "println"), labelsOf(session));
    }

    /**
     * A truncated list re-queries instead.
     *
     * <p>The one case where local filtering is wrong: the provider said it cut the list short, so
     * narrowing can reach items it never sent. Filtering locally would silently omit exactly the item the
     * user is typing towards — the list looks complete and is missing the answer.</p>
     */
    @Test
    public void anIncompleteListReQueriesOnEveryKeystroke() {
        TextBuffer buffer = new TextBuffer("");
        StubProvider provider = new StubProvider();
        provider.incomplete = true;
        provider.items.add(item("alpha", SymbolKind.CLASS));

        CompletionSession session = CompletionSession.open(buffer, provider, 0,
                CompletionProvider.TriggerKind.EXPLICIT, null);
        assertEquals(1, provider.requests);

        buffer.insert(0, "a");
        session.caretMoved(1);

        assertEquals(2, provider.requests);
        assertTrue(session.isIncomplete());
    }

    @Test
    public void scatteredCharactersMatch() {
        // fMS -> fooMethodStuff, the headline behaviour of every modern completion list and the reason
        // the matcher grew an opt-in subsequence tier rather than a second matcher.
        TextBuffer buffer = new TextBuffer("fMS");
        StubProvider provider = new StubProvider();
        provider.items.add(item("fooMethodStuff", SymbolKind.METHOD));
        provider.items.add(item("unrelated", SymbolKind.METHOD));

        CompletionSession session = CompletionSession.open(buffer, provider, 3,
                CompletionProvider.TriggerKind.EXPLICIT, null);

        assertEquals(List.of("fooMethodStuff"), labelsOf(session));
    }

    @Test
    public void aPrefixHitOutranksAScatteredOne() {
        TextBuffer buffer = new TextBuffer("set");
        StubProvider provider = new StubProvider();
        provider.items.add(item("sELECTED_tEXT", SymbolKind.CONSTANT));
        provider.items.add(item("setText", SymbolKind.METHOD));

        CompletionSession session = CompletionSession.open(buffer, provider, 3,
                CompletionProvider.TriggerKind.EXPLICIT, null);

        assertEquals("the tier gap must hold: a real prefix beats a scattered hit",
                "setText", labelsOf(session).get(0));
    }

    @Test
    public void aLocalOutranksAKeywordAtEqualMatchQuality() {
        TextBuffer buffer = new TextBuffer("fi");
        StubProvider provider = new StubProvider();
        provider.items.add(CompletionItem.builder("final", SymbolKind.KEYWORD).sortText("~final").build());
        provider.items.add(item("first", SymbolKind.LOCAL_VARIABLE));

        CompletionSession session = CompletionSession.open(buffer, provider, 2,
                CompletionProvider.TriggerKind.EXPLICIT, null);

        assertEquals("proximity decides a tie, and a keyword is never nearer than a local",
                "first", labelsOf(session).get(0));
    }

    // ── Ending ──────────────────────────────────────────────────────────────────────────────────

    @Test
    public void movingTheCaretBeforeTheWordEndsTheSession() {
        // The word starts at 4, not at 0 -- a session on a word at the very start of the buffer has nowhere
        // before it to move to, so testing it there would pass against a session that never closes at all.
        TextBuffer buffer = new TextBuffer("int value");
        StubProvider provider = new StubProvider();
        provider.items.add(item("value", SymbolKind.LOCAL_VARIABLE));

        CompletionSession session = CompletionSession.open(buffer, provider, 9,
                CompletionProvider.TriggerKind.EXPLICIT, null);
        assertFalse(session.isClosed());

        session.caretMoved(2);

        assertTrue(session.isClosed());
    }

    @Test
    public void aListThatFiltersDownToNothingEndsTheSession() {
        // Leaving an empty popup up means the next Enter is eaten by a widget showing nothing -- so it
        // neither accepts anything nor inserts a newline.
        TextBuffer buffer = new TextBuffer("");
        StubProvider provider = new StubProvider();
        provider.items.add(item("alpha", SymbolKind.METHOD));

        CompletionSession session = CompletionSession.open(buffer, provider, 0,
                CompletionProvider.TriggerKind.EXPLICIT, null);
        buffer.insert(0, "zzz");
        session.caretMoved(3);

        assertTrue(session.isClosed());
    }

    /**
     * A late answer from a superseded request is dropped.
     *
     * <p>{@link CompletionProvider} promises the callback may never fire; the corollary nobody writes down
     * is that it may fire <em>too late</em>. Without the serial check, an answer computed for a caret the
     * user has left replaces the list they are looking at.</p>
     */
    @Test
    public void aLateAnswerFromASupersededRequestIsIgnored() {
        TextBuffer buffer = new TextBuffer("");
        StubProvider provider = new StubProvider();
        // Incomplete, so a keystroke re-queries rather than filtering locally -- which is what produces
        // two outstanding requests and therefore the chance for the older one to answer last.
        provider.incomplete = true;
        provider.items.add(item("first", SymbolKind.METHOD));

        CompletionSession session = CompletionSession.open(buffer, provider, 0,
                CompletionProvider.TriggerKind.EXPLICIT, null);
        Consumer<Versioned<CompletionList>> stale = provider.handedOut.get(0);

        // A second request supersedes the first...
        provider.deferred = true;
        buffer.insert(0, "f");
        session.caretMoved(1);
        provider.releaseDeferred(List.of(item("fresh", SymbolKind.METHOD)));
        assertEquals(List.of("fresh"), labelsOf(session));

        // ...and only now does the first one answer.
        stale.accept(Versioned.of(0, CompletionList.complete(List.of(item("fossil", SymbolKind.METHOD)))));

        assertEquals("a list computed for a caret the user has left must not replace the live one",
                List.of("fresh"), labelsOf(session));
    }

    /**
     * A keystroke before the first answer arrives must not kill the session.
     *
     * <p>The provider contract is asynchronous and explicitly says the callback may never fire, so "no
     * answer yet" is the ordinary state for any engine that does not answer inline. Concluding from an
     * empty list that the session is over closes it before its first list ever lands — which presents as
     * the popup simply never appearing, on exactly the engines slow enough to need it.</p>
     */
    @Test
    public void typingBeforeTheFirstAnswerArrivesKeepsTheSessionAlive() {
        TextBuffer buffer = new TextBuffer("");
        StubProvider provider = new StubProvider();
        provider.deferred = true;
        provider.items.add(item("alpha", SymbolKind.METHOD));

        CompletionSession session = CompletionSession.open(buffer, provider, 0,
                CompletionProvider.TriggerKind.EXPLICIT, null);
        buffer.insert(0, "a");
        session.caretMoved(1);

        assertFalse("the session must survive until something has answered", session.isClosed());

        provider.releaseDeferred(List.of(item("alpha", SymbolKind.METHOD)));
        assertEquals(List.of("alpha"), labelsOf(session));
    }

    // ── Accepting ───────────────────────────────────────────────────────────────────────────────

    @Test
    public void acceptingReplacesThePartialWordRatherThanInsertingBesideIt() {
        TextBuffer buffer = new TextBuffer("int x = val");
        StubProvider provider = new StubProvider();
        provider.items.add(item("value", SymbolKind.LOCAL_VARIABLE));

        CompletionSession session = CompletionSession.open(buffer, provider, 11,
                CompletionProvider.TriggerKind.EXPLICIT, null);
        session.caretMoved(11);
        assertTrue(session.accept());

        assertEquals("int x = value", buffer.toString());
    }

    /**
     * The name and the import it brought are <b>one</b> undo step.
     *
     * <p>Two steps for one keystroke is the behaviour every editor that has auto-import is criticised for:
     * Ctrl+Z takes the name away and leaves the import behind, so the file is left in a state the user
     * never typed and did not ask for.</p>
     */
    @Test
    public void acceptingWithAnAutoImportIsASingleUndoStep() {
        TextBuffer buffer = new TextBuffer("class A {\n    ArrayL\n}\n");
        int caret = buffer.toString().indexOf("ArrayL") + "ArrayL".length();
        StubProvider provider = new StubProvider();
        provider.items.add(CompletionItem.builder("ArrayList", SymbolKind.CLASS)
                .insertText("ArrayList")
                .additionalTextEdits(new Change(0, 0, "import java.util.ArrayList;\n"))
                .build());

        CompletionSession session = CompletionSession.open(buffer, provider, caret,
                CompletionProvider.TriggerKind.EXPLICIT, null);
        session.caretMoved(caret);
        assertTrue(session.accept());

        String after = buffer.toString();
        assertTrue("the import must be inserted", after.startsWith("import java.util.ArrayList;\n"));
        assertTrue("and the name completed", after.contains("ArrayList\n}"));

        buffer.history().undo();

        assertEquals("one keystroke, one undo step -- both edits go together",
                "class A {\n    ArrayL\n}\n", buffer.toString());
    }

    @Test
    public void theCaretLandsAfterTheInsertedNameEvenWhenAnImportShiftedTheFile() {
        TextBuffer buffer = new TextBuffer("class A {\n    ArrayL\n}\n");
        int caret = buffer.toString().indexOf("ArrayL") + "ArrayL".length();
        String importText = "import java.util.ArrayList;\n";
        CompletionItem completed = CompletionItem.builder("ArrayList", SymbolKind.CLASS)
                .insertText("ArrayList")
                .additionalTextEdits(new Change(0, 0, importText))
                .build();

        StubProvider provider = new StubProvider();
        provider.items.add(completed);
        CompletionSession session = CompletionSession.open(buffer, provider, caret,
                CompletionProvider.TriggerKind.EXPLICIT, null);
        session.caretMoved(caret);

        int after = session.caretAfterAccept(completed, caret);
        session.accept();

        assertEquals("the caret must follow the text, not the offset it used to be at",
                buffer.toString().indexOf("ArrayList\n}") + "ArrayList".length(), after);
    }

    @Test
    public void acceptingWithNothingSelectedClosesRatherThanEditing() {
        TextBuffer buffer = new TextBuffer("abc");
        StubProvider provider = new StubProvider();

        CompletionSession session = CompletionSession.open(buffer, provider, 3,
                CompletionProvider.TriggerKind.EXPLICIT, null);
        assertFalse(session.accept());
        assertEquals("abc", buffer.toString());
    }
}
