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

    public KeywordTokenizer(Set<String> keywords, Set<String> types) {
        this.keywords = new HashSet<>(keywords);
        this.types = new HashSet<>(types);
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
            if (c == '"' || c == '\'') {
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
