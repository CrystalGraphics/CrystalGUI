package com.crystalgui.widget.texteditor.lang;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.Resolver;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.widget.texteditor.TextEditor;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * M11 §24.2 — go-to-definition.
 *
 * <h3>What is worth pinning here, and what is not</h3>
 *
 * <p>Not the jump itself: {@code setCaret} moving the caret is the widget's oldest behaviour. What these
 * cover is the <b>announcement asymmetry</b> — a same-file declaration is performed and a cross-file one
 * is emitted, and getting that backwards produces a feature that works in the workbench and silently
 * cannot work anywhere else — and the <b>two discards</b>, which are the only reason an asynchronous
 * answer is safe to act on. Both discards fail the same way when absent: not an error, a confident jump
 * to the wrong place.</p>
 *
 * <p>The resolver here answers on demand rather than immediately, because every interesting case is about
 * <em>when</em> the answer arrives relative to what the user did next. A resolver that answered inline
 * could not express any of them.</p>
 */
public class GoToDefinitionTest extends UiDocumentTestBase {

    /** Records the request and hands the test the callback, so an answer can arrive late or never. */
    private static final class Recording implements Resolver {
        private Consumer<Versioned<SymbolInfo>> pending;
        private int asks;
        private int askedAt = -1;

        @Override
        public void resolveAt(int offset, Consumer<Versioned<SymbolInfo>> answer) {
            asks++;
            askedAt = offset;
            pending = answer;
        }

        @Override
        public void expectedTypeAt(int offset, Consumer<Versioned<TypeRef>> answer) {
            answer.accept(Versioned.none(0));
        }

        @Override
        public void membersOf(TypeRef type, int at, Consumer<Versioned<List<SymbolInfo>>> answer) {
            answer.accept(Versioned.none(0));
        }

        void answer(long version, DeclarationSite site) {
            pending.accept(Versioned.of(version, new SymbolInfo("thing", SymbolKind.FIELD, null, null,
                    null, java.util.Set.of(), site)));
        }
    }

    private TextEditor editor;
    private Recording resolver;
    private List<DeclarationSite> announced;

    @Before
    public void openAnEditor() {
        resolver = new Recording();
        announced = new ArrayList<>();
        editor = new TextEditor("alpha\nbeta\ngamma\ndelta\n");
        editor.setLanguageServices(new LanguageServices() {
            @Override public String id() { return "test"; }
            @Override public Resolver resolver() { return resolver; }
        });
        editor.onDefinitionChosen.connect(announced::add);
        editor.layout(l -> l.width(400).height(200));
        UIElement root = new UIElement().layout(l -> l.width(400).height(200));
        root.append(editor);
        document.append(root);
        for (int i = 0; i < 4; i++) frame();
    }

    /**
     * The near half: a declaration in this document is <b>performed, not announced</b>.
     *
     * <p>If this emitted instead, jumping to a local would need a workbench — and would therefore work in
     * the application and be inert in every harness scene and every editor embedded on its own.</p>
     */
    @Test
    public void aSameDocumentDeclarationMovesTheCaretAndAnnouncesNothing() {
        editor.setCaret(0);
        assertTrue("a request should be issued when an engine is present", editor.goToDefinition());

        resolver.answer(editor.buffer().version(),
                DeclarationSite.here(new TextPoint(2, 1), new TextPoint(2, 4)));

        assertEquals("caret should land on the declaration",
                editor.buffer().pointToOffset(new TextPoint(2, 1)), editor.getCaret());
        assertTrue("a same-file jump has nothing to announce", announced.isEmpty());
    }

    /**
     * The far half: a declaration in another file is <b>announced, not performed</b> — the editor cannot
     * open documents, which is the same line ProblemsPanel draws.
     */
    @Test
    public void aCrossDocumentDeclarationIsAnnouncedAndLeavesTheCaretAlone() {
        editor.setCaret(3);
        editor.goToDefinition();

        Resource elsewhere = Resource.of(CgPath.parse("mymod.proj:src/Other.java"));
        resolver.answer(editor.buffer().version(),
                new DeclarationSite(elsewhere, new TextPoint(9, 2), new TextPoint(9, 7)));

        assertEquals("the caret must not move for a file this editor does not hold", 3, editor.getCaret());
        assertEquals(1, announced.size());
        assertEquals(elsewhere, announced.get(0).resource());
        assertEquals(9, announced.get(0).start().row());
    }

    /**
     * Discard one: an answer describing text that has since been edited.
     *
     * <p>The row it names now holds something else, so acting on it lands the caret on innocent text —
     * with nothing to indicate anything went wrong, which is what makes it worth a test rather than a
     * comment.</p>
     */
    @Test
    public void anAnswerStampedAtAnOlderVersionIsDiscarded() {
        editor.setCaret(0);
        editor.goToDefinition();
        long asked = editor.buffer().version();

        editor.buffer().insert(0, "x");
        assertNotEquals("the edit must actually move the version", asked, editor.buffer().version());

        int before = editor.getCaret();
        resolver.answer(asked, DeclarationSite.here(new TextPoint(3, 0), new TextPoint(3, 5)));

        assertEquals("a stale answer must not move the caret", before, editor.getCaret());
        assertTrue(announced.isEmpty());
    }

    /**
     * Discard two: an answer for a request the user has already replaced.
     *
     * <p>Distinct from staleness and not covered by it — nothing here is edited, so the version gate
     * passes and only the serial can tell the two requests apart.</p>
     */
    @Test
    public void anAnswerForASupersededRequestIsDiscarded() {
        editor.setCaret(0);
        editor.goToDefinition();
        Consumer<Versioned<SymbolInfo>> first = resolver.pending;

        editor.setCaret(12);
        editor.goToDefinition();
        assertEquals("the second ask should have reached the resolver", 2, resolver.asks);
        assertEquals(12, resolver.askedAt);

        int before = editor.getCaret();
        first.accept(Versioned.of(editor.buffer().version(), new SymbolInfo("stale", SymbolKind.FIELD,
                null, null, null, java.util.Set.of(),
                DeclarationSite.here(new TextPoint(1, 0), new TextPoint(1, 4)))));

        assertEquals("the superseded answer must not move the caret", before, editor.getCaret());
        assertTrue(announced.isEmpty());
    }

    /**
     * A declaration the engine cannot place — {@code DeclarationSite}'s own documented ordinary case, a
     * member of a compiled class with no source attached. Most of the JDK is this.
     */
    @Test
    public void anAnswerWithNoDeclarationSiteDoesNothingAndThrowsNothing() {
        editor.setCaret(0);
        editor.goToDefinition();

        resolver.answer(editor.buffer().version(), null);

        assertEquals(0, editor.getCaret());
        assertTrue(announced.isEmpty());
    }

    /**
     * The three-tier absence rule: no engine is the ordinary state for a language that will never have
     * one, so this reports that it did not ask and raises nothing.
     */
    @Test
    public void withNoEngineNothingIsAskedAndNothingThrows() {
        editor.setLanguageServices(null);
        assertFalse(editor.goToDefinition());
        assertEquals(0, resolver.asks);
        assertTrue(announced.isEmpty());
    }
}
