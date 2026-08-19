package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.Rope;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.SemanticTokenProvider;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.text.TextRange;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * An engine's colouring lands over the grammar's, in the same vocabulary, and wins where they overlap.
 *
 * <h3>What this is actually protecting</h3>
 *
 * <p>The entire value of semantic tokens is <em>correcting</em> what the grammar guessed. A grammar sees
 * shape, so every plain identifier is one capture — a parameter, a local and a field are one colour and no
 * scheme can separate them. An engine has resolved them. If the two answers merely coexisted, which one
 * painted would depend on the order the paint path happened to walk a list in, and <b>both names are
 * legitimate and both resolve to a real colour</b> — so the wrong one looks like a scheme problem rather
 * than an ordering bug. That is the same class as the capture-precedence bug M1 spent two rounds on, which
 * is why the rule is asserted rather than left to emerge.</p>
 */
public class SemanticOverlayTest extends UiTestBase {

    /** Stands in for a grammar: calls every run of letters a plain {@code variable}, as a real one would. */
    private static final class BlanketTokenizer implements SyntaxTokenizer {

        @Override
        public List<SyntaxToken> tokenize(Rope document, int from, int to) {
            String text = document.toString();
            List<SyntaxToken> tokens = new ArrayList<>();
            int index = 0;
            while (index < text.length()) {
                if (Character.isLetter(text.charAt(index))) {
                    int start = index;
                    while (index < text.length() && Character.isLetter(text.charAt(index))) index++;
                    if (index > from && start < to) tokens.add(new SyntaxToken(start, index, "variable"));
                } else {
                    index++;
                }
            }
            return tokens;
        }
    }

    /** Stands in for an engine: knows that one particular span is a parameter. */
    private static final class FakeSemantics implements SemanticTokenProvider {
        private final List<SyntaxToken> tokens = new ArrayList<>();
        private long version;
        private SyntaxTokenizer.InvalidationListener listener;
        int queries;
        boolean closed;

        @Override
        public List<SyntaxToken> tokensIn(int fromOffset, int toOffset) {
            queries++;
            List<SyntaxToken> overlapping = new ArrayList<>();
            for (SyntaxToken token : tokens) {
                if (token.start() < toOffset && fromOffset < token.end()) overlapping.add(token);
            }
            return overlapping;
        }

        @Override
        public long version() {
            return version;
        }

        @Override
        public void setInvalidationListener(SyntaxTokenizer.InvalidationListener newListener) {
            this.listener = newListener;
        }

        @Override
        public void close() {
            closed = true;
        }

        /** What a landing compile does: new answers, a new version, and a range to re-query. */
        void land(long newVersion, List<SyntaxToken> newTokens) {
            tokens.clear();
            tokens.addAll(newTokens);
            version = newVersion;
            if (listener != null) listener.tokensChanged(0, SyntaxTokenizer.InvalidationListener.EVERYTHING);
        }
    }

    private TextEditor editor;
    private UIWindow window;
    private FakeSemantics semantics;

    private void build(String text) {
        editor = new TextEditor(text);
        editor.layout(l -> l.width(400).height(300));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));
        editor.setTokenizer(new BlanketTokenizer());

        semantics = new FakeSemantics();
        editor.setLanguageServices(new LanguageServices() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public SemanticTokenProvider semanticTokens() {
                return semantics;
            }

            // THE SERVICES OWN THEIR PROVIDER AND RELEASE IT. There is one close() on the seam, on
            // purpose -- an editor that closed the provider itself would be releasing something it was
            // only handed, and the document's other view would still be using it.
            @Override
            public void close() {
                semantics.close();
            }
        });

        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        settle(20);
    }

    private void settle(int frames) {
        for (int i = 0; i < frames; i++) {
            editor.updateWindow();
            window.updateWithoutPainting();
        }
    }

    /**
     * The line elements the editor has realised — where highlight ranges actually live.
     *
     * <p>A {@code HighlightRegistry} belongs to a {@code UIText}, not to the editor, and its offsets are
     * into that element's own string. Every fixture here is one unwrapped line, so a document offset is a
     * line offset; anything wrapping would need the rebasing {@code refreshHighlights} does.</p>
     */
    private static void collect(UIElement element, List<UIText> out) {
        if (element instanceof UIText text) out.add(text);
        for (UIElement child : element.getChildren()) collect(child, out);
    }

    private List<UIText> lines(TextEditor target) {
        List<UIText> found = new ArrayList<>();
        for (UIElement child : target.getChildren()) collect(child, found);
        return found;
    }

    /** Whether some realised line publishes {@code name} over exactly {@code [start, end)}. */
    private boolean covers(String name, int start, int end) {
        for (UIText line : lines(editor)) {
            for (TextRange range : line.highlights().get(name)) {
                if (range.start() <= start && range.end() >= end) return true;
            }
        }
        return false;
    }

    /**
     * The engine's name replaces the grammar's rather than joining it.
     *
     * <h3>Why this asserts on {@code type} and not on {@code variable.parameter}</h3>
     *
     * <p>The obvious version of this test — grammar says {@code variable}, engine says
     * {@code variable.parameter} — cannot fail. {@code refreshHighlights} deliberately publishes a dotted
     * capture under its general form as well, so that a theme naming only {@code variable} still colours
     * the specialisation; {@code variable} is therefore present either way and the assertion is vacuous.
     * That is a real feature and not a leak, but it does mean the override rule has to be asserted through
     * a pair of names where <b>neither is the other's general form</b>.</p>
     *
     * <p>{@code Colour} in {@code Colour c = ...} is exactly that pair, and it is the realistic one: a
     * grammar has no way to know a bare identifier names a type, and an engine does.</p>
     */
    @Test
    public void theEngineOverridesTheGrammarWhereTheyOverlap() {
        build("Colour tint = read();");
        int colour = 0;

        assertTrue("the grammar's blanket answer is what we start from", covers("variable", colour, 6));

        semantics.land(1, List.of(new SyntaxToken(colour, 6, "type")));
        settle(4);

        assertTrue("the engine's answer must be the one published", covers("type", colour, 6));
        assertFalse("and the grammar's must be GONE, not merely outranked -- two overlapping ranges under "
                        + "unrelated names leave the winner to paint order",
                covers("variable", colour, 6));
    }

    @Test
    public void whatTheEngineSaysNothingAboutKeepsTheGrammarsColour() {
        build("void f(int width) { return width; }");
        int firstWidth = "void f(int ".length();

        semantics.land(1, List.of(new SyntaxToken(firstWidth, firstWidth + 5, "variable.parameter")));
        settle(4);

        // `void`, `f` and `return` were never mentioned by the engine. An overlay that replaced the row
        // wholesale rather than the overlapping spans would have blanked them, which looks like the engine
        // "turning off" highlighting for everything it does not personally know.
        assertTrue(covers("variable", 0, 4));
        assertTrue(covers("variable", "void f(int width) { ".length(), "void f(int width) { return".length()));
    }

    @Test
    public void anEditorWithNoServicesBehavesExactlyAsBefore() {
        // The feature flag, and there is no other one. Asserted because "absent means unchanged" is the
        // claim every dedicated-server guarantee in this stack rests on.
        TextEditor plain = new TextEditor("void f(int width) {}");
        plain.layout(l -> l.width(400).height(300));
        plain.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));
        plain.setTokenizer(new BlanketTokenizer());

        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        root.addChild(plain);
        UIWindow plainWindow = new UIWindow(Ui.of(root));
        plainWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        plainWindow.init(800, 600);
        for (int i = 0; i < 20; i++) {
            plain.updateWindow();
            plainWindow.updateWithoutPainting();
        }

        assertNull(plain.languageServices());
        boolean coloured = false;
        for (UIText line : lines(plain)) {
            if (!line.highlights().get("variable").isEmpty()) coloured = true;
        }
        assertTrue("the grammar still colours everything it did before", coloured);
    }

    @Test
    public void aLandingCompileReachesTheScreenWithoutTheDocumentChanging() {
        // The reason SemanticTokenProvider has an invalidation listener at all. Nothing about the document
        // changed when the compile finished, so no existing signal would prompt a re-query and the colours
        // would sit one compile behind until an unrelated repaint happened to occur.
        build("void f(int width) {}");
        int firstWidth = "void f(int ".length();

        settle(10);
        int queriesBefore = semantics.queries;

        semantics.land(1, List.of(new SyntaxToken(firstWidth, firstWidth + 5, "variable.parameter")));
        settle(4);

        assertTrue("landing must have caused a re-query", semantics.queries > queriesBefore);
        assertTrue(covers("variable.parameter", firstWidth, firstWidth + 5));
    }

    @Test
    public void detachingServicesDropsTheirColoursAndStopsTheSubscription() {
        build("void f(int width) {}");
        int firstWidth = "void f(int ".length();
        semantics.land(1, List.of(new SyntaxToken(firstWidth, firstWidth + 5, "variable.parameter")));
        settle(4);
        assertTrue(covers("variable.parameter", firstWidth, firstWidth + 5));

        editor.setLanguageServices(null);
        settle(4);

        assertNull(editor.languageServices());
        assertFalse("the engine's colours must not outlive the engine",
                covers("variable.parameter", firstWidth, firstWidth + 5));
        assertTrue("and the grammar's answer comes back", covers("variable", firstWidth, firstWidth + 5));
        assertFalse("replacing services must NOT close them -- the document owns them, and the same file "
                + "in two panes is two editors sharing one set", semantics.closed);
    }

    @Test
    public void disposingTheDocumentIsWhatClosesTheEngineAndTheTokenizer() {
        build("void f(int width) {}");

        editor.disposeLanguage();

        assertTrue("dispose is the one path that closes -- nothing else did, which is how a native parse "
                + "tree outlived every document in the application", semantics.closed);
        assertNull(editor.languageServices());
        assertEquals(SyntaxTokenizer.NONE, editor.tokenizer());

        // Idempotent: a file deleted while its tab is open can plausibly arrive from both ends.
        editor.disposeLanguage();
    }
}
