package com.crystalgui.text.syntax;

import com.crystalgui.text.Rope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A hand-written lexer for C-family languages — Java, GLSL, C, and anything close enough.
 *
 * <h3>Why this exists alongside tree-sitter</h3>
 * <p><b>It is the degradation path, not a stepping stone.</b> A native library fails to load somewhere —
 * the fork carries no aarch64-Windows build, and a Minecraft client is the least predictable deployment
 * target there is. An editor that falls back to lexer highlighting is far better than one that throws,
 * and a dedicated server has no natives at all.</p>
 *
 * <p>It also gives the tree-sitter backend a <b>differential oracle</b>: over valid code the two must
 * agree on where comments, strings and numbers begin and end. Two independent implementations that agree
 * are much stronger evidence than one implementation with expectations written to match it.</p>
 *
 * <h3>What it cannot do, stated plainly</h3>
 * <p>It has no idea what anything <em>is</em>. It highlights the four things a regular language can
 * actually recognise: comments, strings, numbers and a fixed keyword set. Anything beyond that is why
 * tree-sitter is worth a native dependency.</p>
 *
 * <p><b>There are three tiers, and the two things above this one fail differently</b>, which is worth
 * separating because it decides where a missing colour has to be fixed:</p>
 *
 * <ul>
 *   <li><b>This</b> — comments, strings, numbers, keywords. Every identifier is one colour.</li>
 *   <li><b>A grammar</b> ({@code SyntaxTokenizer} from the language module) sees <em>shape</em>, so it
 *       separates a declaration from a call and a constructor from a method. It still cannot separate a
 *       field from a local, because nothing in the shape of {@code count} says which it is.</li>
 *   <li><b>An engine</b> ({@link com.crystalgui.text.lang.SemanticTokenProvider}) has resolved the names,
 *       so it can. That is the whole reason the semantic layer colours anything at all.</li>
 * </ul>
 *
 * <p>Each tier is absent independently and each absence is silent. This one is what remains when both of
 * the others are — which is the case on a dedicated server, and the reason this file is not deleted.</p>
 */
public final class KeywordTokenizer implements SyntaxTokenizer {

    private final Set<String> keywords;
    private final Set<String> types;

    /**
     * Which characters open a string, as data rather than as a condition.
     *
     * <p>C-family is {@code "} and {@code '}; JavaScript adds the backtick. It matters more than it
     * looks: an <em>unhandled</em> quote character is not a missing colour, it is a lexer that walks into
     * the literal and reads its contents as code — so a {@code "} inside a template literal opens a
     * string that runs to the end of the line, and every keyword in between is painted. Handling the
     * character is what bounds that.</p>
     */
    private final String quotes;

    public KeywordTokenizer(Set<String> keywords, Set<String> types) {
        this(keywords, types, "\"'");
    }

    public KeywordTokenizer(Set<String> keywords, Set<String> types, String quotes) {
        this.keywords = new HashSet<>(keywords);
        this.types = new HashSet<>(types);
        this.quotes = quotes;
    }

    /** Java's reserved words, plus the primitives as types. */
    public static KeywordTokenizer java() {
        return new KeywordTokenizer(
                setOf("abstract assert break case catch class const continue default do else enum extends "
                        + "final finally for goto if implements import instanceof interface native new "
                        + "package private protected public return static strictfp super switch "
                        + "synchronized this throw throws transient try volatile while var record "
                        + "sealed permits yield true false null"),
                setOf("boolean byte char double float int long short void String Object"));
    }

    /** GLSL — the shader graph's language, and the reason 6.1.7 exists at all. */
    public static KeywordTokenizer glsl() {
        return new KeywordTokenizer(
                setOf("attribute const uniform varying buffer shared coherent volatile restrict readonly "
                        + "writeonly layout centroid flat smooth noperspective patch sample break continue "
                        + "do for while switch case default if else subroutine in out inout true false "
                        + "invariant precise discard return struct precision highp mediump lowp"),
                setOf("void bool int uint float double vec2 vec3 vec4 dvec2 dvec3 dvec4 bvec2 bvec3 bvec4 "
                        + "ivec2 ivec3 ivec4 uvec2 uvec3 uvec4 mat2 mat3 mat4 mat2x2 mat2x3 mat2x4 mat3x2 "
                        + "mat3x3 mat3x4 mat4x2 mat4x3 mat4x4 sampler1D sampler2D sampler3D samplerCube "
                        + "sampler2DArray samplerBuffer image2D atomic_uint"));
    }

    /**
     * JavaScript — every reserved word, including the ones this engine will refuse to run.
     *
     * <h4>Why {@code class} and {@code await} are in the list even though Rhino rejects them</h4>
     *
     * <p>This tier <b>colours</b>; it does not judge. A file using {@code class} is still a file whose
     * {@code class} is a keyword, and painting it as an ordinary identifier would say the opposite of
     * what the engine is about to say about it — the diagnostic names the construct, so the word had
     * better look like the construct. Which words an engine <em>accepts</em> is `RhinoProblemPolicy`'s
     * question, and the answer differs per band, which is exactly why it cannot live in a constant here.
     * The completion list is where the distinction is enforced, because offering a keyword is teaching
     * it.</p>
     *
     * <p>The "types" set is JavaScript's <b>built-in constructors</b>, which is the nearest honest
     * analogue: they are the capitalised global names a reader treats as types, and colouring them as
     * such is what every editor does. {@code undefined}/{@code NaN}/{@code Infinity} sit with the
     * keywords beside {@code null}, since they are values rather than constructors.</p>
     */
    public static KeywordTokenizer javascript() {
        return new KeywordTokenizer(
                setOf("await async break case catch class const continue debugger default delete do else "
                        + "export extends finally for from function get if import in instanceof let new "
                        + "of return set static super switch this throw try typeof var void while with "
                        + "yield true false null undefined NaN Infinity"),
                setOf("Array Boolean Date Error EvalError Function JSON Map Math Number Object Promise "
                        + "Proxy RangeError ReferenceError Reflect RegExp Set String Symbol SyntaxError "
                        + "TypeError URIError WeakMap WeakSet BigInt ArrayBuffer DataView Float32Array "
                        + "Float64Array Int8Array Int16Array Int32Array Uint8Array Uint16Array Uint32Array "
                        + "Packages Java"),
                // THE BACKTICK, which is the whole reason this factory does not just call the two-argument
                // constructor. @see #quotes
                "\"'`");
    }

    private static Set<String> setOf(String spaceSeparated) {
        // SPLIT ON ANY RUN OF WHITESPACE, and drop what is left over. These lists are written as
        // concatenated string fragments, so a missing space at a seam silently fuses two keywords
        // into one that matches nothing, and a doubled space puts the EMPTY STRING in the set --
        // which then matches every zero-length identifier the lexer considers.
        Set<String> words = new HashSet<>();
        for (String word : spaceSeparated.trim().split("[ \\t\\r\\n]+")) {
            if (!word.isEmpty()) words.add(word);
        }
        return words;
    }

    @Override
    public List<SyntaxToken> tokenize(Rope document, int from, int to) {
        int length = document.length();
        if (length == 0) return List.of();

        // SCANNING STARTS AT THE LINE START, NOT AT `from`. A lexer's state depends on what came before
        // it: starting mid-line would read the inside of a string as code. Starting at the line start is
        // right for everything except a block comment opened on an earlier line, which is handled below.
        int start = Math.max(0, Math.min(from, length));
        int end = Math.max(start, Math.min(to, length));
        int scanFrom = document.lineStartOffset(document.offsetToPoint(start).row());
        // Walk back over any unclosed block comment. Bounded, because an unterminated /* at the top of a
        // huge file would otherwise make every query scan the whole document.
        scanFrom = Math.max(0, backUpOverBlockComment(document, scanFrom));

        String text = document.slice(scanFrom, end).toString();
        List<SyntaxToken> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);

            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                int stop = close < 0 ? text.length() : close + 2;
                tokens.add(new SyntaxToken(scanFrom + i, scanFrom + stop, "comment"));
                i = stop;
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                int stop = text.indexOf('\n', i);
                if (stop < 0) stop = text.length();
                tokens.add(new SyntaxToken(scanFrom + i, scanFrom + stop, "comment"));
                i = stop;
                continue;
            }
            if (quotes.indexOf(c) >= 0) {
                int stop = closingQuote(text, i, c);
                tokens.add(new SyntaxToken(scanFrom + i, scanFrom + stop, "string"));
                i = stop;
                continue;
            }
            if (Character.isDigit(c)) {
                int stop = i;
                while (stop < text.length() && isNumberPart(text.charAt(stop))) stop++;
                tokens.add(new SyntaxToken(scanFrom + i, scanFrom + stop, "number"));
                i = stop;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int stop = i;
                while (stop < text.length() && Character.isJavaIdentifierPart(text.charAt(stop))) stop++;
                String word = text.substring(i, stop);
                if (keywords.contains(word)) {
                    tokens.add(new SyntaxToken(scanFrom + i, scanFrom + stop, "keyword"));
                } else if (types.contains(word)) {
                    tokens.add(new SyntaxToken(scanFrom + i, scanFrom + stop, "type"));
                } else if (isCallName(text, stop)) {
                    // A name followed by '(' is a call. Not a parse -- it cannot tell a call from a
                    // declaration -- but it is right often enough to be worth the two characters it costs.
                    tokens.add(new SyntaxToken(scanFrom + i, scanFrom + stop, "function"));
                }
                i = stop;
                continue;
            }
            i++;
        }

        // Tokens fully before the requested range are dropped; ones that merely START before it are kept,
        // because a block comment spanning the viewport is a token that begins off screen.
        tokens.removeIf(token -> token.end() <= start);
        return Collections.unmodifiableList(tokens);
    }

    /** How far a query has to scan back to see the start of an enclosing block comment. */
    private static final int MAX_BACKSCAN = 16 * 1024;

    private int backUpOverBlockComment(Rope document, int lineStart) {
        int windowStart = Math.max(0, lineStart - MAX_BACKSCAN);
        if (windowStart >= lineStart) return lineStart;
        String before = document.slice(windowStart, lineStart).toString();
        int open = before.lastIndexOf("/*");
        if (open < 0) return lineStart;
        int close = before.lastIndexOf("*/");
        // An opener with no closer after it means this line begins inside a comment.
        return close > open ? lineStart : windowStart + open;
    }

    private static int closingQuote(String text, int from, char quote) {
        int i = from + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            // A backslash escapes the next character, including the quote and another backslash.
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) return i + 1;
            // An unterminated string ends at the line, not at the file: a stray quote must not paint
            // everything after it as a string.
            if (c == '\n') return i;
            i++;
        }
        return text.length();
    }

    private static boolean isNumberPart(char c) {
        return Character.isLetterOrDigit(c) || c == '.';
    }

    private static boolean isCallName(String text, int nameEnd) {
        int i = nameEnd;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        return i < text.length() && text.charAt(i) == '(';
    }
}
