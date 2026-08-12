package com.crystalgui.syntax.treesitter;

import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import org.junit.Assume;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every grammar this module ships parses its language and produces the captures a scheme styles.
 *
 * <p>Deliberately shallow per language and broad across them: the deep assertions live beside the
 * feature they pin (predicates, precedence, numeric forms), while this is the one that fails when a jar
 * is vendored without its query, a query without its jar, or a registration without either.</p>
 */
public class EveryShippedGrammarTest {

    private void assumeNativeAvailable() {
        try {
            TreeSitterTokenizer.java().close();
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
        }
    }

    private Set<String> captures(SyntaxTokenizer tokenizer, String source) {
        Set<String> names = new HashSet<>();
        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
        for (SyntaxToken token : tokens) names.add(token.name());
        return names;
    }

    @Test
    public void cssParsesAndCaptures() {
        assumeNativeAvailable();
        TreeSitterTokenizer tokenizer = TreeSitterTokenizer.css(null);
        Set<String> names = captures(tokenizer, ".panel > a:hover { color: #FF0000; margin: 4px; }\n");
        assertFalse("the CSS grammar produced nothing", names.isEmpty());
        assertTrue("expected a property capture, got " + names,
                names.contains("property") || names.contains("attribute"));
        tokenizer.close();
    }

    @Test
    public void javascriptParsesAndCaptures() {
        assumeNativeAvailable();
        TreeSitterTokenizer tokenizer = TreeSitterTokenizer.javascript(null);
        Set<String> names = captures(tokenizer,
                "const x = 1;\nfunction go(a) { return `hi ${a}`; }\nclass K {}\n");
        assertFalse("the JavaScript grammar produced nothing", names.isEmpty());
        assertTrue("expected a keyword capture, got " + names,
                names.stream().anyMatch(n -> n.startsWith("keyword")));
        assertTrue("expected a string capture, got " + names,
                names.stream().anyMatch(n -> n.startsWith("string")));
        tokenizer.close();
    }

    @Test
    public void htmlParsesAndCaptures() {
        assumeNativeAvailable();
        TreeSitterTokenizer tokenizer = TreeSitterTokenizer.html(null);
        Set<String> names = captures(tokenizer,
                "<div class=\"a\"><p>text</p><!-- note --></div>\n");
        assertFalse("the HTML grammar produced nothing", names.isEmpty());
        assertTrue("expected a tag capture, got " + names,
                names.stream().anyMatch(n -> n.startsWith("tag")));
        tokenizer.close();
    }

    @Test
    public void glslParsesAndCaptures() {
        assumeNativeAvailable();
        TreeSitterTokenizer tokenizer = TreeSitterTokenizer.glsl(null);
        Set<String> names = captures(tokenizer,
                "#version 330 core\n"
                        + "uniform mat4 u_mvp;\n"
                        + "in vec3 a_pos;\n"
                        + "out vec2 v_uv;\n"
                        + "void main() {\n"
                        + "    v_uv = a_pos.xy * 0.5 + 0.5;\n"
                        + "    gl_Position = u_mvp * vec4(a_pos, 1.0);\n"
                        + "}\n");
        assertFalse("the GLSL grammar produced nothing", names.isEmpty());
        assertTrue("expected a type capture, got " + names,
                names.stream().anyMatch(n -> n.startsWith("type")));
        // The dialect fold: this grammar says @delimiter where every other one says
        // @punctuation.delimiter, and a scheme must not have to know that.
        assertFalse("@delimiter should have been normalized away, got " + names,
                names.contains("delimiter"));
        tokenizer.close();
    }

    /**
     * <b>GLSL separates a declaration from a call, and names its {@code gl_} builtins.</b>
     *
     * <p>Two defects with one shape: the grammar captures both halves of each pair under a single name,
     * and the scheme then has to pick a side. Its query says {@code @function} for both
     * {@code (function_declarator declarator:)} and {@code (call_expression function:)}, and it guards
     * the {@code gl_} builtins with {@code #lua-match?} — a predicate form the lift did not handle, so
     * that pattern stayed inert and every builtin rendered as an ordinary variable.</p>
     *
     * <p>The Lua half is the one worth a test rather than a glance: {@code ^gl_} happens to mean the same
     * thing in Lua and in Java regex, which is exactly why translating it blind is dangerous — {@code %d}
     * would not have been.</p>
     */
    @Test
    public void glslSeparatesDeclarationsFromCallsAndNamesItsBuiltins() {
        assumeNativeAvailable();
        TreeSitterTokenizer tokenizer = TreeSitterTokenizer.glsl(null);
        String source = "float saturate(float x) { return clamp(x, 0.0, 1.0); }\n"
                + "void main() { gl_FragDepth = gl_FragCoord.z; }\n";

        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
        String saturate = lastNameOf(source, tokens, "saturate");
        String clamp = lastNameOf(source, tokens, "clamp");
        String builtin = lastNameOf(source, tokens, "gl_FragDepth");

        assertTrue("a declaration should be function.method, was " + saturate,
                saturate.equals("function.method"));
        assertTrue("a call should be function.call, was " + clamp, clamp.equals("function.call"));
        assertTrue("gl_ builtins need the #lua-match? predicate lifted, was " + builtin,
                builtin.equals("variable.builtin"));
        tokenizer.close();
    }

    /**
     * <b>A builtin type is a reserved word; a declared one is a name.</b>
     *
     * <p>The C-family query captures {@code (primitive_type)} and {@code (type_identifier)} under one
     * name, where Java's distinguishes them — so a scheme that colours {@code int} as a keyword left
     * {@code void} and {@code float} at the default foreground, and the two languages disagreed about a
     * decision the scheme is supposed to own.</p>
     *
     * <p>Also pinned here: {@code layout} and {@code uniform} are keywords, not types. They arrive as
     * {@code @type.qualifier}, whose dotted fallback is {@code type} — and with types at the default
     * foreground that fallback silently erased every storage qualifier in a shader. A reminder that the
     * fallback is a guess, and worth a test wherever it guesses wrong.</p>
     */
    @Test
    public void glslBuiltinTypesAndQualifiersAreNotPlainTypes() {
        assumeNativeAvailable();
        TreeSitterTokenizer tokenizer = TreeSitterTokenizer.glsl(null);
        String source = "struct Surface { float roughness; };\n"
                + "layout(location = 0) uniform sampler2D u_albedo;\n"
                + "void main() { Surface s; }\n";

        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
        assertTrue("void is a builtin type, was " + lastNameOf(source, tokens, "void"),
                lastNameOf(source, tokens, "void").equals("type.builtin"));
        assertTrue("float too, was " + lastNameOf(source, tokens, "float"),
                lastNameOf(source, tokens, "float").equals("type.builtin"));
        assertTrue("a declared type stays a plain type, was " + lastNameOf(source, tokens, "Surface"),
                lastNameOf(source, tokens, "Surface").equals("type"));
        assertTrue("layout is a qualifier, was " + lastNameOf(source, tokens, "layout"),
                lastNameOf(source, tokens, "layout").equals("type.qualifier"));
        assertTrue("and so is uniform, was " + lastNameOf(source, tokens, "uniform"),
                lastNameOf(source, tokens, "uniform").equals("type.qualifier"));
        tokenizer.close();
    }

    /**
     * <b>SCREAMING_CASE means a constant in GLSL too.</b>
     *
     * <p>Java's query tests for it and the C-family's does not, so the identical naming convention got two
     * answers depending on which grammar author happened to write the rule — {@code MAX_RETRIES} purple in
     * one file and {@code MAX_LIGHTS} plain in the next. Which of the two is right is a decision the scheme
     * owns, so the rule is added rather than left to the grammar.</p>
     */
    @Test
    public void glslScreamingCaseIsAConstant() {
        assumeNativeAvailable();
        TreeSitterTokenizer tokenizer = TreeSitterTokenizer.glsl(null);
        String source = "#define MAX_STEPS 64\n"
                + "const float PI = 3.14159;\n"
                + "const int MAX_LIGHTS = 8;\n"
                + "void go() { float radiance = PI; }\n";

        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
        assertTrue("PI, was " + lastNameOf(source, tokens, "PI"),
                lastNameOf(source, tokens, "PI").equals("constant"));
        assertTrue("MAX_LIGHTS, was " + lastNameOf(source, tokens, "MAX_LIGHTS"),
                lastNameOf(source, tokens, "MAX_LIGHTS").equals("constant"));
        assertTrue("an object-like #define is covered by the same rule, was "
                        + lastNameOf(source, tokens, "MAX_STEPS"),
                lastNameOf(source, tokens, "MAX_STEPS").equals("constant"));
        // The predicate is the only thing separating these; without it every identifier is a constant.
        assertTrue("an ordinary local must not be, was " + lastNameOf(source, tokens, "radiance"),
                lastNameOf(source, tokens, "radiance").equals("variable"));
        tokenizer.close();
    }

    /**
     * <b>The parts of a preprocessor line that the grammar actually parses.</b>
     *
     * <p>A directive's shape is a tree and its payload is one opaque token, so this is a boundary rather
     * than a gap: the macro's parameters, the extension's name and its behaviour are real nodes and are
     * captured, while anything inside a {@code preproc_arg} — {@code 330 core}, {@code 64},
     * {@code clamp(x, 0.0, 1.0)} — is a single undifferentiated token with no number node to colour.</p>
     *
     * <p>Asserted so the boundary is visible in a test rather than rediscovered from a screenshot. Also
     * guards the {@code @constant} lift: these additions had to avoid naming {@code @constant} a second
     * time, because the predicate is only lifted when every use of a name is guarded, and a stray use
     * would silently switch SCREAMING_CASE colouring back off.</p>
     */
    @Test
    public void glslColoursThePartsOfADirectiveThatAreParsed() {
        assumeNativeAvailable();
        TreeSitterTokenizer tokenizer = TreeSitterTokenizer.glsl(null);
        String source = "#version 330 core\n"
                + "#define SATURATE(x) clamp(x, 0.0, 1.0)\n"
                + "#extension GL_ARB_gpu_shader5 : enable\n"
                + "const int MAX_LIGHTS = 8;\n";

        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
        assertTrue("a macro parameter is a real node, was " + lastNameOf(source, tokens, "x"),
                lastNameOf(source, tokens, "x").equals("variable.parameter"));
        assertTrue("so is the extension name, was "
                        + lastNameOf(source, tokens, "GL_ARB_gpu_shader5"),
                lastNameOf(source, tokens, "GL_ARB_gpu_shader5").equals("attribute"));
        assertTrue("and its behaviour, was " + lastNameOf(source, tokens, "enable"),
                lastNameOf(source, tokens, "enable").equals("keyword"));

        // The additions must not have disturbed the lift they sit beside.
        assertTrue("SCREAMING_CASE must still be a constant, was "
                        + lastNameOf(source, tokens, "MAX_LIGHTS"),
                lastNameOf(source, tokens, "MAX_LIGHTS").equals("constant"));

        // The boundary itself: a preproc_arg has no interior to colour.
        assertTrue("330 is inside an opaque preproc_arg and stays uncaptured",
                lastNameOf(source, tokens, "330").isEmpty());
        tokenizer.close();
    }

    /** The last capture emitted for a piece of text — the one that wins under last-write-wins. */
    private String lastNameOf(String source, List<SyntaxToken> tokens, String text) {
        String last = "";
        for (SyntaxToken token : tokens) {
            if (source.substring(token.start(), token.end()).equals(text)) last = token.name();
        }
        return last;
    }

    /**
     * <b>{@code <style>} is CSS and {@code <script>} is JavaScript.</b>
     *
     * <p>An HTML file is mostly not markup, so colouring those bodies as markup text is not "incomplete",
     * it asserts something false about them. The injected tokens are added after the host's, so they win
     * on the shared last-write-wins rule — the host captures the body as raw text and the injected grammar
     * gives the more specific answer.</p>
     *
     * <p>The offsets are the part that fails invisibly: the fragment is tokenized standalone, so every
     * token has to be rebased onto the host document. Without the shift the colours land at the top of the
     * file, which looks like injection working and offsets being random. Asserted by checking the captured
     * TEXT rather than that some token exists.</p>
     */
    @Test
    public void htmlInjectsCssAndJavaScript() {
        assumeNativeAvailable();
        TreeSitterTokenizer tokenizer = TreeSitterTokenizer.html(null);
        String source = "<html>\n"
                + "<style>\n"
                + "  .panel { color: #FF0000; }\n"
                + "</style>\n"
                + "<script>\n"
                + "  const answer = 42;\n"
                + "</script>\n"
                + "</html>\n";

        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
        boolean sawCssProperty = false;
        boolean sawJsKeyword = false;
        boolean sawJsNumber = false;
        for (SyntaxToken token : tokens) {
            String text = source.substring(token.start(), token.end());
            if (text.equals("color") && token.name().startsWith("property")) sawCssProperty = true;
            if (text.equals("const") && token.name().startsWith("keyword")) sawJsKeyword = true;
            if (text.equals("42") && token.name().equals("number")) sawJsNumber = true;
        }

        assertTrue("the <style> body should be tokenized as CSS", sawCssProperty);
        assertTrue("the <script> body should be tokenized as JavaScript", sawJsKeyword);
        assertTrue("and its offsets rebased onto the host document", sawJsNumber);
        tokenizer.close();
    }

    /**
     * <b>Registration is the half that fails silently.</b> A grammar can load perfectly and still never
     * reach an editor, which is exactly how the harness spent a whole session on the word-list lexer while
     * the real parser sat unused on the classpath.
     */
    @Test
    public void registrationPutsEachGrammarInFrontOfTheLexer() {
        assumeNativeAvailable();
        TreeSitterLanguages.register(null);
        for (String fileName : List.of("A.java", "a.css", "a.js", "a.html", "a.glsl", "a.frag")) {
            SyntaxTokenizer tokenizer = LanguageRegistry.forFileName(fileName).newTokenizer();
            assertTrue(fileName + " still resolves to " + tokenizer.getClass().getSimpleName(),
                    tokenizer instanceof TreeSitterTokenizer);
            tokenizer.close();
        }
    }
}
