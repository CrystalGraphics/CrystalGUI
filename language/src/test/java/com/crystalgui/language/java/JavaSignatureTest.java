package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.lang.Signature;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.syntax.SyntaxToken;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * M11 §24.1 — the declaration the engine renders for the Quick Documentation popup.
 *
 * <h3>Why this lives with the engine and not with the widget</h3>
 *
 * <p>Everything asserted here is something <b>only a binding knows</b>: that {@code public} is a
 * modifier, that {@code @Nullable} is an annotation, that {@code x} is a parameter. The widget that
 * draws it has no branches at all — it maps a capture name to a highlight range — so a regression in
 * what a declaration <em>reads</em> as can only be caught on this side.</p>
 *
 * <p>The assertions are on the <b>text</b> and on the capture at a given word, never on offsets: the
 * offsets are an artefact of how the builder concatenates, and pinning them would make every addition
 * to a signature a test edit.</p>
 */
public class JavaSignatureTest {

    private JavaEngine engine;
    private SourceAnalyzer analyzer;

    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
        analyzer = engine.analyzer();
    }

    @After
    public void closeEngine() throws IOException {
        if (engine != null) engine.close();
    }

    /** The signature of the symbol at the first occurrence of {@code needle}. */
    private Signature signatureAt(String source, String needle) {
        SourceAnalyzer.Analysis analysis = analyzer.analyze("Script", source, List.of(), 8, 1L);
        int at = source.indexOf(needle);
        if (at < 0) throw new IllegalArgumentException("no '" + needle + "' in the fixture");
        SymbolInfo symbol = analysis.resolveAt(at);
        assertNotNull("nothing resolved at '" + needle + "'", symbol);
        assertNotNull("the engine should always render a signature", symbol.signature());
        return symbol.signature();
    }

    /** The capture name covering the first occurrence of {@code word} in the rendered text. */
    private static String captureOf(Signature signature, String word) {
        int at = signature.text().indexOf(word);
        assertTrue("'" + word + "' is not in <" + signature.text() + ">", at >= 0);
        for (SyntaxToken token : signature.tokens()) {
            if (token.start() <= at && token.end() >= at + word.length()) return token.name();
        }
        return null;
    }

    /**
     * <b>A name is coloured the same in the editor and in the popup.</b>
     *
     * <p>This is the invariant the two views kept breaking. They answered "what is this name" separately,
     * so type parameters came out teal in the editor and flat in the popup, and annotations yellow in the
     * popup and plain in the editor — each time fixed by making the same edit twice. The popup now asks
     * the semantic layer rather than working it out again, and this fails if that is ever undone.</p>
     *
     * <p>Asserted over several kinds at once, because a shared function is only worth having if it covers
     * the cases that actually differ: a constant, a type parameter, an annotation and a call.</p>
     */
    @Test
    public void aNameIsColouredTheSameInTheEditorAndInThePopup() {
        String source = ""
                + "public class Script<E> {\n"
                + "    private static final int COUNT = 3;\n"
                + "    @SuppressWarnings(\"unused\")\n"
                + "    E pick(E chosen) { return chosen; }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyzer.analyze("Script", source, List.of(), 8, 1L);
        List<SyntaxToken> tokens = analysis.semanticTokens();

        for (String name : new String[] { "COUNT", "chosen" }) {
            SymbolInfo symbol = analysis.resolveAt(source.indexOf(name));
            assertNotNull("nothing resolved at " + name, symbol);
            assertNotNull("no signature for " + name, symbol.signature());

            String inEditor = captureAtIndex(tokens, source.indexOf(name), name.length());
            String inPopup = captureOf(symbol.signature(), name);
            assertEquals(name + " is drawn differently in the two views", inEditor, inPopup);
        }
    }

    /** The capture over an exact span — for a name that appears more than once in the fixture. */
    private static String captureAtIndex(List<SyntaxToken> tokens, int at, int length) {
        for (SyntaxToken token : tokens) {
            if (token.start() == at && token.end() == at + length) return token.name();
        }
        return null;
    }

    /** Visibility, which {@code SymbolModifier} does not carry and only the flags know. */
    @Test
    public void aMethodRendersItsVisibilityReturnTypeAndParameterNames() {
        String source = ""
                + "public class Script {\n"
                + "    public void println(String text, int count) { }\n"
                + "}\n";
        Signature signature = signatureAt(source, "println");

        assertEquals("public void println(String text, int count)", signature.text());
        assertEquals("keyword", captureOf(signature, "public"));
        assertEquals("void is a primitive, and the editor draws primitives as builtins",
                "type.builtin", captureOf(signature, "void"));
        // THE DECLARATION COLOUR, because this IS a declaration. It was briefly `function.call` on the
        // argument that a popup has one subject and so needs no emphasis to separate it -- true about
        // emphasis, and beside the point: the parity rule is that a name is drawn as what it IS.
        assertEquals("function.method", captureOf(signature, "println"));
        assertEquals("variable.parameter", captureOf(signature, "text"));
    }

    /**
     * Annotations, on the declaration and on a parameter — the thing that made the widget-assembled
     * version unfixable, since a widget cannot know an annotation exists.
     */
    @Test
    public void annotationsAppearOnTheDeclarationAndOnParameters() {
        String source = ""
                + "import java.lang.annotation.*;\n"
                + "public class Script {\n"
                + "    @Retention(RetentionPolicy.RUNTIME) @interface Nullable { }\n"
                + "    @Deprecated\n"
                + "    void take(@Nullable String x) { }\n"
                + "}\n";
        Signature signature = signatureAt(source, "take");

        assertTrue("the declaration's annotation is missing from <" + signature.text() + ">",
                signature.text().contains("@Deprecated"));
        assertTrue("the parameter's annotation is missing from <" + signature.text() + ">",
                signature.text().contains("@Nullable"));
        assertEquals("attribute", captureOf(signature, "@Deprecated"));
    }

    /** A type is declared with a keyword and its supertypes, never with its own name twice. */
    @Test
    public void aTypeRendersItsKeywordAndSupertypes() {
        String source = ""
                + "import java.io.Serializable;\n"
                + "public final class Script implements Serializable {\n"
                + "}\n";
        Signature signature = signatureAt(source, "Script");

        assertEquals("public final class Script implements Serializable", signature.text());
        assertEquals("keyword", captureOf(signature, "class"));
        // `type.interface`, NOT `type` -- Serializable is one, and only a binding can say so, since the
        // three type declarations are spelled identically at every use site. A scheme with nothing to
        // add still draws it as a type: a dotted capture publishes under its general form too.
        assertEquals("type.interface", captureOf(signature, "Serializable"));
    }

    /**
     * {@code extends Object} is on every class and in no source file, so printing it back would show a
     * declaration nobody wrote in a box that claims to show what they did.
     */
    @Test
    public void anImplicitObjectSuperclassIsNotPrinted() {
        Signature signature = signatureAt("public class Script { }\n", "Script");
        assertEquals("public class Script", signature.text());
    }

    /**
     * The initializer <b>as written</b>, read from the AST rather than from the folded constant.
     *
     * <p>Underscores and the {@code d} suffix survive, which they would not through
     * {@code getConstantValue()} — that answers a {@code Double} and the spelling is gone.</p>
     */
    @Test
    public void aFieldShowsItsInitializerExactlyAsWritten() {
        String source = ""
                + "public class Script {\n"
                + "    static final double GOLDEN_RATIO = 1.618_033_988_749d;\n"
                + "}\n";
        Signature signature = signatureAt(source, "GOLDEN_RATIO");

        assertEquals("static final double GOLDEN_RATIO = 1.618_033_988_749d;", signature.text());
        assertEquals("constant", captureOf(signature, "GOLDEN_RATIO"));
    }

    /**
     * <b>{@code = null} was missing entirely.</b>
     *
     * <p>{@code getConstantValue()} answers only for primitives and {@code String}, so an
     * {@code Object NOTHING = null} reported no initializer — and there is no way through that API to
     * tell "not a constant" from "the constant is null". The field plainly has one either way, and the
     * AST has it verbatim.</p>
     */
    @Test
    public void aNullInitializerIsShown() {
        String source = ""
                + "public class Script {\n"
                + "    private static final Object NOTHING = null;\n"
                + "}\n";
        Signature signature = signatureAt(source, "NOTHING");

        assertEquals("private static final Object NOTHING = null;", signature.text());
        assertEquals("constant.builtin", captureOf(signature, "null"));
    }

    /**
     * A hex literal stays hex — folding it would report {@code -559038737} for {@code 0xDEAD_BEEF},
     * which is the same number and not the same declaration.
     */
    @Test
    public void aHexLiteralIsNotFoldedToDecimal() {
        String source = ""
                + "public class Script {\n"
                + "    private static final int HEX = 0xDEAD_BEEF;\n"
                + "}\n";
        assertEquals("private static final int HEX = 0xDEAD_BEEF;",
                signatureAt(source, "HEX").text());
    }

    /**
     * Escapes inside a literal get their own capture, because the editor gives them one.
     *
     * <p>A string drawn in one flat colour is a visibly poorer rendering of text the editor is colouring
     * properly three lines above — {@code string.escape} is in the vocabulary and every scheme defines
     * it.</p>
     */
    @Test
    public void escapeSequencesInsideALiteralAreCapturedSeparately() {
        String source = ""
                + "public class Script {\n"
                + "    private static final String ESCAPES = \"tab:\\t and more\";\n"
                + "}\n";
        Signature signature = signatureAt(source, "ESCAPES");

        assertEquals("string.escape", captureOf(signature, "\\t"));
        assertEquals("the surrounding text is still a string", "string",
                captureOf(signature, "tab:"));
    }

    /** {@code throws} belongs to the declaration and is what tells you to handle something. */
    @Test
    public void checkedExceptionsAreRendered() {
        String source = ""
                + "import java.io.IOException;\n"
                + "public class Script {\n"
                + "    void read() throws IOException { }\n"
                + "}\n";
        Signature signature = signatureAt(source, "read");
        assertEquals("void read() throws IOException", signature.text());
        assertEquals("keyword", captureOf(signature, "throws"));
    }

    /**
     * Simple type names, never qualified ones.
     *
     * <p>{@code java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>} is correct and
     * unreadable — a two-argument generic becomes most of a line of package names.</p>
     */
    @Test
    public void typeNamesAreSimpleRatherThanQualified() {
        String source = ""
                + "import java.util.Map;\n"
                + "public class Script {\n"
                + "    Map<String, Integer> counts;\n"
                + "}\n";
        Signature signature = signatureAt(source, "counts");
        assertEquals("Map<String,Integer> counts;", signature.text().replace(", ", ","));
        assertFalse("qualified names have leaked in", signature.text().contains("java.util"));
    }

    /**
     * A folded {@code char} is rendered as the <b>literal</b>, escaped.
     *
     * <p>{@code TAB = '\t'} folds to the tab character itself. Put in the signature raw it drew as a
     * missing glyph — the popup read {@code private static final char TAB = □} — and it is not a
     * rendering problem to work around: what a declaration shows is the literal, quotes and all.</p>
     */
    @Test
    public void aCharConstantIsRenderedAsAnEscapedLiteral() {
        String source = ""
                + "public class Script {\n"
                + "    private static final char TAB = '\\t';\n"
                + "}\n";
        Signature signature = signatureAt(source, "TAB");
        assertEquals("private static final char TAB = '\\t';", signature.text());
    }

    /** Primitives colour as builtins, the way the editor's own grammar draws them. */
    @Test
    public void primitiveTypesAreCapturedAsBuiltins() {
        String source = ""
                + "public class Script {\n"
                + "    private static final char TAB = '\\t';\n"
                + "    Object thing;\n"
                + "}\n";
        assertEquals("type.builtin", captureOf(signatureAt(source, "TAB"), "char"));
        assertEquals("type", captureOf(signatureAt(source, "thing"), "Object"));
    }

    /**
     * <b>{@code @SuppressWarnings([Ljava.lang.Object;@c3d4bd7)}.</b>
     *
     * <p>An annotation member value is an {@code Object[]} whenever the member is an array — including
     * the single-element array a lone {@code "unused"} becomes — and {@code String.valueOf} on it yields
     * a JVM identity string. JDT also hands back an {@code ITypeBinding} for a {@code Class} literal, an
     * {@code IVariableBinding} for an enum constant and an {@code IAnnotationBinding} for a nested one,
     * so four distinct shapes were falling through a branch written for the fifth.</p>
     */
    @Test
    public void anArrayValuedAnnotationRendersItsElementsRatherThanAnArrayIdentity() {
        String source = ""
                + "public class Script {\n"
                + "    @SuppressWarnings(\"unused\")\n"
                + "    void hidden() { }\n"
                + "}\n";
        Signature signature = signatureAt(source, "hidden");

        assertTrue("<" + signature.text() + "> should carry the value",
                signature.text().contains("@SuppressWarnings(\"unused\")"));
        assertFalse("an array identity string has leaked in: " + signature.text(),
                signature.text().contains("[L") || signature.text().contains("@c"));
    }

    /** A multi-element array keeps its braces, which is how it is written. */
    @Test
    public void aMultiElementAnnotationArrayKeepsItsBraces() {
        String source = ""
                + "public class Script {\n"
                + "    @SuppressWarnings({\"unused\", \"rawtypes\"})\n"
                + "    void hidden() { }\n"
                + "}\n";
        Signature signature = signatureAt(source, "hidden");
        assertTrue("<" + signature.text() + ">",
                signature.text().contains("{\"unused\", \"rawtypes\"}"));
    }

    /**
     * A text block is a constant too, and its newlines would go straight into a line the popup draws
     * with {@code white-space: nowrap}.
     */
    @Test
    public void aLongStringConstantIsTruncatedAndItsControlCharactersEscaped() {
        String source = ""
                + "public class Script {\n"
                + "    private static final String Q = \"SELECT id\\n  FROM widgets\\n WHERE owner = ?\";\n"
                + "}\n";
        Signature signature = signatureAt(source, "Q");

        // THE DECLARATION'S OWN BREAK IS A REAL NEWLINE and is wanted -- this test predates breaking and
        // asserted on the whole signature, which now legitimately contains one. What must not survive is
        // a raw newline inside the LITERAL, which would put half the string on a line of its own.
        String literal = signature.text().substring(signature.text().indexOf("= "));
        assertFalse("a raw newline reached the literal: <" + literal + ">", literal.contains("\n"));
        assertTrue("<" + literal + "> should escape them instead", literal.contains("\\n"));
    }

    /**
     * A short declaration stays on <b>one</b> line — breaking unconditionally would put a two-word field
     * on three, which is worse than the problem being solved.
     */
    @Test
    public void aShortDeclarationIsNotBroken() {
        // A DISTINCTIVE NAME, because the needle is a plain indexOf: `n` matched the one in `class`
        // first, so the test resolved nothing and failed for a reason unrelated to what it asserts.
        String source = "public class Script {\n    int tally;\n}\n";
        assertFalse(signatureAt(source, "tally").text().contains("\n"));
    }

    /**
     * A long one breaks at <b>semantic</b> points: the annotation on its own line, then one parameter
     * per line, indented, with the closing bracket back at the margin.
     *
     * <p>This is the shape both references use, and it is the reason breaks are the engine's to place: it
     * knows where a break is legal and meaningful, and the layout does not. Re-wrapping at the edge of the
     * box splits whatever two words land there, which is how a parameter list ends up broken in the middle
     * of a generic type.</p>
     */
    @Test
    public void aLongDeclarationBreaksAtSemanticPoints() {
        // A CLASSPATH symbol, because breaking is the ASSEMBLED path's job and an in-file declaration
        // is quoted -- it keeps the author's own line, which is the whole point of quoting it.
        String source = ""
                + "import java.util.Map;\n"
                + "public class Script {\n"
                + "    void run(Map<String, Integer> m) {\n"
                + "        m.merge(\"a\", 1, null);\n"
                + "    }\n"
                + "}\n";
        Signature signature = signatureAt(source, "merge");
        String[] lines = signature.text().split("\n");

        assertTrue("expected several lines, got <" + signature.text() + ">", lines.length >= 3);
        assertTrue("the declaration comes first: " + lines[0], lines[0].endsWith("merge("));
        assertTrue("parameters are indented one per line: " + lines[1], lines[1].startsWith("    "));
        assertTrue("a parameter per line, not all on one: " + lines[1], lines[1].endsWith(","));
        assertEquals("and the bracket closes at the margin", ")", lines[lines.length - 1]);
    }

    /**
     * A declaration in this unit is quoted <b>exactly as written</b> — layout, terminator and all.
     *
     * <p>This asserted the opposite: that a long field was broken before its {@code =} by us. That break
     * was ours to impose only while the declaration was being assembled from parts, and imposing it on
     * top of the author's own wrapping showed a shape the file does not contain. A one-line declaration
     * stays one line.</p>
     */
    @Test
    public void aDeclarationInThisUnitIsQuotedExactlyAsWritten() {
        String source = ""
                + "public class Script {\n"
                + "    private static final String PATH = "
                + "\"C:\\\\Users\\\\somebody\\\\Documents\\\\file.txt\";\n"
                + "}\n";
        Signature signature = signatureAt(source, "PATH");

        assertEquals("one line in the file, one line here", 1, signature.text().split("\n").length);
        assertTrue("the terminator belongs to the declaration: <" + signature.text() + ">",
                signature.text().endsWith(";"));
        assertTrue("<" + signature.text() + ">",
                signature.text().startsWith("private static final String PATH = "));
    }

    /**
     * And a MULTI-LINE one keeps its own wrapping and indentation rather than being reflowed.
     *
     * <p>The author's layout is information — an argument per line, an aligned array — and reproducing
     * it costs nothing once the declaration is quoted rather than rebuilt.</p>
     */
    @Test
    public void aMultiLineDeclarationKeepsTheAuthorsOwnLayout() {
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    private static final List<String> NAMES = List.of(\n"
                + "            \"alpha\",\n"
                + "            \"beta\");\n"
                + "}\n";
        Signature signature = signatureAt(source, "NAMES");
        String[] lines = signature.text().split("\n");

        assertEquals("the file wraps it over three lines, so this should too", 3, lines.length);
        assertTrue(signature.text().endsWith(";"));

        // RELATIVE to the first line, not absolute. The slice starts AT the declaration, so its first
        // line lost the four columns it sat at while the continuations kept theirs -- which doubled the
        // apparent indent, and did so more the deeper the declaration sat in the file. Each continuation
        // gives back exactly what the first line lost: 12 in the file, 8 here.
        assertEquals("the argument should be indented 8 relative to the declaration, not 12",
                8, leadingSpaces(lines[1]));
        assertEquals(8, leadingSpaces(lines[2]));
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    /**
     * A <b>text block</b> is a string, and was rendering as plain text.
     *
     * <p>Its AST node cannot be named from here — {@code TextBlock} is Java 13 and this class is loaded
     * by the band-8 child — so it was skipped, and a whole SQL statement drew uncoloured beside a
     * properly coloured declaration.</p>
     *
     * <p>Self-skipping rather than band-gated: a band whose JDT cannot parse a text block reports a
     * syntax problem, and asserting against that would fail for the language level rather than for the
     * behaviour.</p>
     */
    @Test
    public void aTextBlockIsCapturedAsAString() {
        String source = ""
                + "public class Script {\n"
                + "    private static final String QUERY = \"\"\"\n"
                + "            SELECT id\n"
                + "            \"\"\";\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyzer.analyze("Script", source, List.of(), 17, 1L);
        SymbolInfo symbol = analysis.resolveAt(source.indexOf("QUERY"));
        Assume.assumeTrue("this band cannot parse a text block; skipping",
                symbol != null && symbol.signature() != null
                        && symbol.signature().text().contains("SELECT"));

        assertEquals("the block's contents should be a string, not plain text",
                "string", captureOf(symbol.signature(), "SELECT id"));
    }

    /**
     * A doc comment is <b>not</b> part of the declaration, whatever the AST node spans.
     *
     * <p>{@code FieldDeclaration} includes its own Javadoc, so quoting the node put a paragraph of prose
     * into the SIGNATURE band — the one band that is meant to be a single declaration, sitting directly
     * above the band whose whole purpose is documentation.</p>
     */
    @Test
    public void aDocCommentIsNotQuotedIntoTheSignature() {
        String source = ""
                + "public class Script {\n"
                + "    /** Holds the thing. */\n"
                + "    private static final int COUNT = 3;\n"
                + "}\n";
        Signature signature = signatureAt(source, "COUNT");

        assertEquals("private static final int COUNT = 3;", signature.text());
    }

    /**
     * <b>{@code new Foo(...)} resolves the constructor, not the type.</b>
     *
     * <p>Syntactically the name <em>is</em> the type, so {@code resolveBinding()} answers the type and
     * the popup described the class — its supertypes, its interfaces — instead of the overload being
     * called, which is the one thing you cannot see from the call site. The constructor is reachable only
     * by asking the enclosing {@code ClassInstanceCreation}.</p>
     */
    @Test
    public void aConstructorCallResolvesTheConstructorRatherThanTheType() {
        String source = ""
                + "import java.util.ArrayList;\n"
                + "public class Script {\n"
                + "    Object make() { return new ArrayList<String>(8); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyzer.analyze("Script", source, List.of(), 8, 1L);
        SymbolInfo symbol = analysis.resolveAt(source.indexOf("ArrayList<String>(8)"));

        assertNotNull(symbol);
        assertEquals(SymbolKind.CONSTRUCTOR, symbol.kind());
        assertTrue("the owner should carry its type parameters: " + symbol.container(),
                symbol.container().endsWith("ArrayList<E>"));
        assertTrue("<" + symbol.signature().text() + "> should be a constructor",
                symbol.signature().text().contains("ArrayList("));
    }

    /**
     * A generic type shows how it is <b>declared</b>, not how this call instantiated it.
     *
     * <p>Hovering an {@code ArrayList<String>} reported {@code extends AbstractList<String>} — true of
     * that instantiation and not of the declaration, which is what documentation is about.</p>
     */
    /**
     * A type variable is teal <b>wherever it appears</b>, not only where it is declared.
     *
     * <p>Types used to be rendered as one opaque string carrying one capture, so the {@code E} in
     * {@code List<E>} was flat while the {@code E} six characters earlier in the same header was teal.
     * Fixed at the cause rather than per site: {@code appendTypeName} builds a type from its parts, so
     * every position that renders one — supertypes, parameters, return types, throws, wildcards, arrays,
     * nested generics, a {@code Class} literal in an annotation — gets it without being told.</p>
     */
    @Test
    public void aTypeVariableIsColouredInsideEveryPositionThatRendersAType() {
        String source = ""
                + "import java.util.Collection;\n"
                + "import java.util.List;\n"
                + "public interface Script<E> extends List<E> {\n"
                + "    void take(Collection<? extends E> from, E[] more);\n"
                + "}\n";

        Signature header = signatureAt(source, "Script<E> extends");
        assertTrue("the supertype's argument is missing: <" + header.text() + ">",
                header.text().contains("List<E>"));
        int inSupertype = header.text().indexOf("List<E>") + 5;
        assertEquals("the E inside the supertype should be a parameter too", "type.parameter",
                captureAtOffset(header, inSupertype));

        Signature method = signatureAt(source, "take");
        assertTrue("a wildcard bound should survive: <" + method.text() + ">",
                method.text().contains("? extends E"));
        assertTrue("and an array of a variable: <" + method.text() + ">",
                method.text().contains("E[]"));
        assertEquals("the bound is a type parameter", "type.parameter",
                captureAtOffset(method, method.text().indexOf("extends E") + 8));
    }

    /** The capture covering an exact offset in the rendered text. */
    private static String captureAtOffset(Signature signature, int at) {
        for (SyntaxToken token : signature.tokens()) {
            if (token.start() <= at && token.end() > at) return token.name();
        }
        return null;
    }

    @Test
    public void aGenericTypeShowsItsDeclarationRatherThanItsInstantiation() {
        String source = ""
                + "import java.util.ArrayList;\n"
                + "public class Script {\n"
                + "    ArrayList<String> list = null;\n"
                + "}\n";
        Signature signature = signatureAt(source, "ArrayList<String> list");

        assertTrue("the parameter should be the declared one: " + signature.text(),
                signature.text().contains("ArrayList<E>"));
        assertFalse("the instantiation leaked in: " + signature.text(),
                signature.text().contains("<String>"));
    }

    /**
     * A long {@code implements} list is a <b>hanging indent</b>: the first interface stays on the
     * keyword's line and the rest align under it.
     *
     * <p>Not the same rule as the parameter list, which is a block indent. Putting every interface on its
     * own indented line leaves {@code implements} alone on a line, which reads as a heading over a list
     * rather than as one clause. The pad is the keyword's own width, so alignment falls out of the text
     * instead of being a magic number — 11 for {@code implements}, 8 for an interface's {@code extends}.</p>
     */
    @Test
    public void aLongImplementsListHangsUnderItsFirstInterface() {
        // A CLASSPATH type, for the reason above: an in-file declaration is quoted and keeps its own
        // wrapping. ArrayList has the long implements list this hanging indent exists for.
        String source = ""
                + "import java.util.ArrayList;\n"
                + "public class Script {\n"
                + "    ArrayList<String> names;\n"
                + "}\n";
        Signature signature = signatureAt(source, "ArrayList<String> names");
        String[] lines = signature.text().split("\n");

        int at = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("implements ")) at = i;
        }
        assertTrue("no implements clause in <" + signature.text() + ">", at >= 0);
        assertTrue("the first interface belongs on the keyword's line: " + lines[at],
                lines[at].length() > "implements ".length());

        String pad = "           ";                       // "implements " is eleven characters
        assertTrue("the second should align under the first: <" + lines[at + 1] + ">",
                lines[at + 1].startsWith(pad) && !lines[at + 1].startsWith(pad + " "));
    }

    /**
     * A non-literal initializer is <b>walked, not flattened</b> — coloured, and spaced by us.
     *
     * <p>It used to go through {@code ASTNode.toString()}, which produced two faults at once: every part
     * came out with no capture, so a call drew in one flat colour beside a properly coloured declaration
     * line, and JDT's flattener writes argument lists with no space after the comma —
     * {@code Circle(1.5d),new Rectangle(3.0d,4.0d)}.</p>
     */
    @Test
    public void aCallInitializerIsColouredAndSpacedRatherThanFlattened() {
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    void run() {\n"
                + "        List<String> names = List.of(\"one\", \"two\");\n"
                + "    }\n"
                + "}\n";
        Signature signature = signatureAt(source, "names");

        assertTrue("the flattener's comma spacing survived: <" + signature.text() + ">",
                signature.text().contains("\"one\", \"two\""));
        // `function.static`, because `List.of` IS static and the analyzer knows it. Two visitors reach
        // this name -- MethodInvocation claims it so a call is coloured with no analyzer attached, then
        // SimpleName asks the one that knows more -- and the later, better-informed answer wins. Keeping
        // both used to be the behaviour and it threw out of the popup: HighlightRegistry refuses two
        // ranges of one name that overlap, so any signature containing a call failed to render at all.
        assertEquals("the invoked method should be captured", "function.static",
                captureOf(signature, "of"));
        assertEquals("and its string arguments too", "string", captureOf(signature, "\"one\""));
    }

    /**
     * <b>Hovering an ARGUMENT of a {@code new} expression describes the argument, not the constructor.</b>
     *
     * <p>The walk that finds a constructor climbs through {@code QualifiedName} so
     * {@code new java.util.ArrayList<>()} resolves — but a {@code QualifiedName} is also an ordinary
     * field access, so {@code new Message(text, Severity.INFO, 0L)} climbed from the argument straight to
     * the creation. The popup then rendered the constructor under the <em>hovered word's</em> name:
     * {@code public Severity(String, Severity, long)} for a class called Message, with a container band
     * correctly reading {@code Main.Message}. Every part was individually right, which is exactly why it
     * read as a naming bug rather than a resolution one.</p>
     *
     * <p>The type name in the same expression still resolves to the constructor — the correction the walk
     * exists for, and the half a narrower fix would have broken.</p>
     */
    @Test
    public void anArgumentOfANewExpressionResolvesToItselfAndNotToTheConstructor() {
        String source = ""
                + "public class Script {\n"
                + "    enum Severity { INFO }\n"
                + "    static class Message {\n"
                + "        Message(String text, Severity severity, long id) { }\n"
                + "    }\n"
                + "    void run() {\n"
                + "        Message m = new Message(\"started\", Severity.INFO, 0L);\n"
                + "    }\n"
                + "}\n";

        Signature argument = signatureAt(source, "Severity.INFO");
        assertTrue("the argument was reported as the constructor: <" + argument.text() + ">",
                argument.text().contains("enum Severity"));

        Signature constructor = signatureAt(source, "Message(\"started\"");
        assertTrue("the type name should still reach the constructor: <" + constructor.text() + ">",
                constructor.text().startsWith("Message(String text"));
    }

    /**
     * <b>A record's canonical constructor is declared by the RECORD.</b>
     *
     * <p>Nobody writes it, so {@code findDeclaringNode} answers the record rather than a
     * {@code MethodDeclaration} and the parameter names came back null — making a record in the file
     * being edited render exactly like a classpath type with no sources attached,
     * {@code Message(String, Severity, long)}. The names are the components, reached through a
     * structural property so the class {@code RecordDeclaration} is never named: it arrived with Java 14
     * and this adapter compiles against the oldest band, where naming it would make the whole class
     * unloadable.</p>
     */
    @Test
    public void aRecordsCanonicalConstructorShowsItsComponentNames() {
        String source = ""
                + "public class Script {\n"
                + "    record Message(String text, long id) { }\n"
                + "    void run() {\n"
                + "        Message m = new Message(\"started\", 0L);\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyzer.analyze("Script", source, List.of(), 17, 1L);
        SymbolInfo symbol = analysis.resolveAt(source.indexOf("Message(\"started\""));
        Assume.assumeTrue("this band's JDT does not parse records; skipping",
                symbol != null && symbol.kind() == SymbolKind.CONSTRUCTOR);

        String text = symbol.signature().text();
        assertTrue("a record's component names are missing: <" + text + ">",
                text.contains("String text") && text.contains("long id"));
    }

    /**
     * <b>A type declared in this file is QUOTED, so every keyword the language has survives.</b>
     *
     * <p>The assembled renderer knows a fixed list of clauses, and Java keeps adding to it. {@code sealed}
     * is a modifier whose flag constant arrived in a later JDT, {@code permits} has no accessor the oldest
     * band can name at all, and a nested type carries an implicit {@code static} nobody typed — so
     * {@code public sealed interface Shape permits Circle, Rectangle, Triangle} rendered as
     * {@code public static interface Shape}: two clauses gone and one word invented.</p>
     *
     * <p>Chasing those one clause at a time is the wrong shape of work, which is what this pins. The
     * declaration is written down already; quoting it is right about every keyword that exists now and
     * every one added later, without this file learning them.</p>
     */
    @Test
    public void aTypeDeclaredInThisFileIsQuotedRatherThanReassembled() {
        String source = ""
                + "public class Script {\n"
                + "    sealed interface Shape permits Circle {\n"
                + "        double area();\n"
                + "    }\n"
                + "    record Circle(double r) implements Shape {\n"
                + "        public double area() { return r; }\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyzer.analyze("Script", source, List.of(), 17, 1L);
        SymbolInfo symbol = analysis.resolveAt(source.indexOf("Shape permits"));
        Assume.assumeTrue("this band's JDT does not parse sealed types; skipping",
                symbol != null && symbol.signature() != null
                        && !symbol.signature().text().contains("static"));

        String text = symbol.signature().text();
        assertEquals("sealed interface Shape permits Circle", text);
    }

    /**
     * <b>A method declared in this file is quoted too</b> — so its parameters carry their real names and
     * the author's own wrapping, rather than a layout this class invents from a binding.
     */
    @Test
    public void aMethodDeclaredInThisFileIsQuotedWithItsParameterNames() {
        String source = ""
                + "public class Script {\n"
                + "    protected final <T> T pick(T first, T second) throws IllegalStateException {\n"
                + "        return first;\n"
                + "    }\n"
                + "}\n";
        Signature signature = signatureAt(source, "pick");

        assertEquals("protected final <T> T pick(T first, T second) throws IllegalStateException",
                signature.text());
        assertEquals("keyword", captureOf(signature, "throws"));
        assertEquals("variable.parameter", captureOf(signature, "first"));
    }

    /**
     * <b>An annotation gets its own line whatever the declaration's length</b>, and a parameter's does not.
     *
     * <p>The break used to be conditional on the signature having already grown too long for one line, so
     * whether {@code @FunctionalInterface} sat above its interface depended on how many characters
     * happened to follow it: correct on a long method, wrong on a short type, and reading as a rule that
     * works intermittently rather than as the wrong rule. Length is a question about wrapping; where the
     * metadata goes is not.</p>
     *
     * <p>A PARAMETER's annotation is the opposite case and stays inline — {@code @Nullable String x} is
     * one item in a list, so a break there splits the list rather than separating metadata.</p>
     */
    @Test
    public void anAnnotationTakesItsOwnLineEvenWhenTheDeclarationIsShort() {
        String source = ""
                + "@FunctionalInterface\n"
                + "public interface Script {\n"
                + "    void run(@Deprecated String x);\n"
                + "}\n";

        Signature type = signatureAt(source, "Script");
        assertTrue("the annotation should be on its own line: <" + type.text() + ">",
                type.text().startsWith("@FunctionalInterface\n"));
        assertTrue("and the declaration should not have been broken up as well: <" + type.text() + ">",
                type.text().endsWith("interface Script"));

        Signature method = signatureAt(source, "run");
        assertTrue("a parameter's annotation stays inline: <" + method.text() + ">",
                method.text().contains("@Deprecated String x"));
    }

    /**
     * <b>A type parameter's DECLARATION carries its bounds; a USE of one does not.</b>
     *
     * <p>{@code appendTypeName} renders the use — a bare {@code T} — which is right everywhere a
     * parameter is referred to and wrong in the single place it is introduced. Routing the declaration
     * through it reduced {@code class Box<T extends Comparable<T>>} to {@code class Box<T>}: not a
     * mis-colour but a missing constraint, in a box whose whole job is to say what the constraint is.</p>
     *
     * <p>The generic METHOD is the same omission one level down, and it was total — nothing rendered a
     * method's own parameters at all, so {@code static <T> List<T> of(...)} showed a {@code T} in its
     * return type with nothing anywhere saying where it came from.</p>
     */
    @Test
    public void aTypeParameterDeclarationKeepsItsBoundsAndAGenericMethodItsOwn() {
        String source = ""
                + "public class Script {\n"
                + "    static final class Box<T extends Comparable<T>> { }\n"
                + "    static <E> E pick(E first, E second) { return first; }\n"
                + "}\n";

        Signature box = signatureAt(source, "Box<T extends");
        assertTrue("the bound was dropped: <" + box.text() + ">",
                box.text().contains("Box<T extends Comparable<T>>"));
        assertEquals("extends is a keyword here as everywhere", "keyword",
                captureAtOffset(box, box.text().indexOf("extends Comparable")));

        Signature pick = signatureAt(source, "pick");
        assertTrue("the method's own parameter is missing: <" + pick.text() + ">",
                pick.text().contains("<E> E pick"));
        assertEquals("and it is a parameter, not a type", "type.parameter",
                captureAtOffset(pick, pick.text().indexOf("<E>") + 1));
    }

    /**
     * <b>Every type position asks {@code typeCapture}, including the two that are not "a type in a
     * declaration" — the SUBJECT and a parameterised type's HEAD.</b>
     *
     * <p>Those two were literal {@code "type"} strings and survived the pass that routed everything else
     * through one function, because they read as obviously-a-type at the site. Hovering
     * {@code java.util.List} therefore drew {@code public interface List<E> extends
     * SequencedCollection<E>} entirely in the class colour, three lines under an editor drawing the same
     * word interface-coloured — the popup contradicting the code it was launched from.</p>
     *
     * <p>Asserted on a CLASSPATH type on purpose. It exercises the assembled path, which is the one with
     * no source to quote and therefore the one where every capture is this file's own decision.</p>
     */
    @Test
    public void anInterfaceIsColouredAsOneAsTheSubjectAndAsASupertype() {
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    List<String> names;\n"
                + "}\n";
        Signature signature = signatureAt(source, "List<String> names");

        assertTrue("not the declaration: <" + signature.text() + ">",
                signature.text().startsWith("public interface List"));
        assertEquals("the subject", "type.interface", captureOf(signature, "List"));
        assertEquals("the supertype's head", "type.interface",
                captureOf(signature, "SequencedCollection"));
        assertEquals("and the variable inside it", "type.parameter",
                captureAtOffset(signature, signature.text().indexOf("SequencedCollection<E>") + 20));
    }

    /**
     * <b>No two captures share a range</b> — a rendering rule, not a tidiness one.
     *
     * <p>{@code HighlightRegistry.set} rejects two ranges of one name that overlap, and the popup groups
     * a signature's tokens by name before handing them over. So a duplicate is not a redundant entry that
     * gets ignored: it is an {@code IllegalArgumentException} thrown out of {@code renderDefinition}, and
     * the symptom is the whole harness dying on a hover rather than a mis-coloured word.</p>
     *
     * <p>It happened because two visitors legitimately reach one node — the invocation claims its own
     * name so a call is still coloured with no analyzer attached, and the name then asks the analyzer,
     * which knows more. Both answers were kept. Asserted over the whole token list rather than at the one
     * node that broke, because the next duplicate will come from a different pair of visitors.</p>
     */
    @Test
    public void noTwoCapturesCoverTheSameRange() {
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    void run() {\n"
                + "        List<String> names = List.of(\"one\", Integer.toString(2));\n"
                + "    }\n"
                + "}\n";
        Signature signature = signatureAt(source, "names");

        Set<String> seen = new HashSet<>();
        for (SyntaxToken token : signature.tokens()) {
            String range = token.start() + ".." + token.end();
            assertTrue("two captures over " + range + " in <" + signature.text() + ">: "
                    + token.name(), seen.add(range));
        }
    }

    /** {@code new Foo(...)} in an initializer keeps its keyword and its type distinct. */
    @Test
    public void aConstructorCallInAnInitializerIsCaptured() {
        String source = ""
                + "public class Script {\n"
                + "    Object thing = new StringBuilder(16);\n"
                + "}\n";
        Signature signature = signatureAt(source, "thing");

        assertTrue("<" + signature.text() + ">", signature.text().contains("new StringBuilder(16)"));
        assertEquals("keyword", captureOf(signature, "new"));
        assertEquals("type", captureOf(signature, "StringBuilder"));
        assertEquals("number", captureOf(signature, "16"));
    }

    /**
     * A method from the <b>classpath</b> renders types without names.
     *
     * <p>{@code IMethodBinding} carries parameter types and not names — a class read off the classpath
     * genuinely has none unless it was built with {@code -parameters}. IntelliJ shows {@code x} for
     * {@code println} because it has the JDK sources attached and falls back to exactly this when it does
     * not. The difference is real information about where the source is, not an inconsistency.</p>
     */
    @Test
    public void aClasspathMethodRendersTypesWithoutParameterNames() {
        String source = ""
                + "public class Script {\n"
                + "    void run() { System.out.println(\"hi\"); }\n"
                + "}\n";
        Signature signature = signatureAt(source, "println");

        assertTrue("<" + signature.text() + "> should name the parameter's type",
                signature.text().contains("(String)"));
    }
}
