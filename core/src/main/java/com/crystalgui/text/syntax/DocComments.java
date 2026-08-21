package com.crystalgui.text.syntax;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Rope;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <b>The inside of a documentation comment</b> — its tags and its markup, as separate tokens.
 *
 * <h3>Why a grammar cannot do this</h3>
 *
 * <p>tree-sitter-java captures a doc comment as one {@code (block_comment) @comment} and stops there,
 * which is correct: to a Java parser {@code /** … *}{@code /} is a single token, and everything inside it
 * is a <em>convention</em> rather than syntax. The same is true of every C-family grammar. Reading the
 * inside means either injecting a second grammar for javadoc — which nothing ships and which would have
 * to be vendored per language — or lexing it, which is what every editor actually does.</p>
 *
 * <p>So this is a refinement pass over tokens a grammar has already produced, and it lives in
 * {@code core} for the same reason {@link KeywordTokenizer} does: it needs no native, no engine and no
 * language-specific knowledge beyond a convention that Java, JavaScript, C#, PHP and Kotlin all share.
 * A language whose comments happen not to use it produces no extra tokens and pays one character
 * comparison per comment.</p>
 *
 * <h3>What it emits</h3>
 *
 * <ul>
 *   <li>{@code comment.doc} replacing the grammar's {@code comment}, so a doc comment can be coloured
 *       apart from an ordinary one — which every reference scheme does.</li>
 *   <li>{@code comment.doc.tag} for {@code @param}, {@code @see}, and the {@code @code} inside an
 *       inline {@code {@code …}}.</li>
 *   <li>{@code comment.doc.value} for the name a block tag names — the {@code x} in {@code @param x}.
 *       Only for the tags whose next word IS a name; {@code @since 1.2} is prose about a version and
 *       colouring it as an identifier would say something untrue.</li>
 *   <li>{@code comment.doc.markup} for embedded HTML — {@code <p>}, {@code </ol>}, {@code <li>}.</li>
 * </ul>
 *
 * <p>The names are dotted so {@link SyntaxToken#generalName()} degrades them: a scheme that styles only
 * {@code comment.doc} still colours all four, and one that styles only {@code comment} still colours the
 * comment. That is the same fallback the grammar captures rely on and is why these are not four
 * unrelated names.</p>
 *
 * <h3>Coarse first, and that is the whole of the layering</h3>
 *
 * <p>The whole-comment token is emitted <em>before</em> the pieces inside it. A character belongs to
 * whichever name was written last, so emitting the pieces first would let the comment's own colour
 * overwrite every one of them — the same inversion the general-form fallback has to avoid, one level
 * up. Nothing here needs to compute the gaps between tags: the coarse token already covers them.</p>
 */
public final class DocComments {

    private DocComments() {
    }

    /** The capture a doc comment as a whole carries. */
    public static final String DOC = "comment.doc";

    /** {@code @param}, {@code @see}, {@code @code} — the tag itself, marker included. */
    public static final String TAG = "comment.doc.tag";

    /** The name a block tag names, where it names one. */
    public static final String VALUE = "comment.doc.value";

    /** Embedded HTML — {@code <p>}, {@code </li>}. */
    public static final String MARKUP = "comment.doc.markup";

    /**
     * The block tags whose next word is a <b>name</b> rather than prose.
     *
     * <p>IntelliJ's {@code DOC_COMMENT_TAG_VALUE} is for the thing a tag is <em>about</em>, which only
     * some tags have: {@code @param count} names a parameter and {@code @throws IOException} names a
     * type, while {@code @since 1.2} and {@code @author nobody} are followed by prose. Colouring those
     * as values says they are identifiers, which is a claim rather than a decoration.</p>
     */
    private static final Set<String> TAGS_WITH_A_NAME =
            Set.of("@param", "@throws", "@exception", "@see", "@link", "@linkplain", "@value");

    /**
     * Wraps {@code tokenizer} so its comment tokens are refined.
     *
     * <p>A decorator rather than a change to each backend, because the pass is identical for all of them
     * and the backends are in another module. Everything else is forwarded: an incremental tokenizer
     * still gets its edits, its invalidation listener and its {@code close}, and losing any of those to a
     * wrapper is the kind of thing that shows up as highlighting that stops updating rather than as an
     * error.</p>
     */
    public static SyntaxTokenizer refining(SyntaxTokenizer tokenizer) {
        if (tokenizer == null || tokenizer == SyntaxTokenizer.NONE) return tokenizer;
        return new SyntaxTokenizer() {

            @Override
            public List<SyntaxToken> tokenize(Rope document, int from, int to) {
                return refine(document, tokenizer.tokenize(document, from, to));
            }

            @Override
            public void edited(Rope before, ChangeSet change) {
                tokenizer.edited(before, change);
            }

            @Override
            public void setInvalidationListener(InvalidationListener listener) {
                tokenizer.setInvalidationListener(listener);
            }

            @Override
            public boolean recoveredAround(int fromOffset, int toOffset) {
                return tokenizer.recoveredAround(fromOffset, toOffset);
            }

            @Override
            public void close() {
                tokenizer.close();
            }
        };
    }

    /**
     * Every token, with each documentation comment replaced by itself plus its contents.
     *
     * <p>Returns the original list when nothing is a doc comment, which is the common case for a file of
     * code and every case for a language that has none.</p>
     */
    public static List<SyntaxToken> refine(Rope document, List<SyntaxToken> tokens) {
        List<SyntaxToken> out = null;
        for (int i = 0; i < tokens.size(); i++) {
            SyntaxToken token = tokens.get(i);
            if (!isDocComment(document, token)) {
                if (out != null) out.add(token);
                continue;
            }
            if (out == null) out = new ArrayList<>(tokens.subList(0, i));
            // THE WHOLE COMMENT FIRST, under the doc name rather than the grammar's -- see the class note
            // on ordering, and on why this REPLACES rather than joins it.
            out.add(new SyntaxToken(token.start(), token.end(), DOC));
            scan(document.text(token.start(), token.end()), token.start(), out);
        }
        return out == null ? tokens : out;
    }

    private static boolean isDocComment(Rope document, SyntaxToken token) {
        if (!"comment".equals(token.name())) return false;
        if (token.end() - token.start() < 5) return false;
        // `/**` and not `/**/`, which is an empty ordinary comment and has no inside to read.
        String head = document.text(token.start(), Math.min(token.end(), token.start() + 4));
        return head.startsWith("/**") && !head.equals("/**/");
    }

    /**
     * Reads one comment's body, emitting a token per tag and per markup element.
     *
     * <p>A hand-written scan rather than a regex, because the three shapes overlap: {@code {@code <p>}}
     * is a tag whose argument contains what looks like markup, and an author writing about HTML in a doc
     * comment is not a corner case. Walking once with a cursor keeps "inside a brace tag" a fact rather
     * than something to re-derive.</p>
     */
    private static void scan(String text, int base, List<SyntaxToken> out) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '<') {
                int close = text.indexOf('>', i + 1);
                // A `<` with no `>` on the same LINE is prose -- `a < b` is written in comments constantly,
                // and scanning to a `>` three lines down would paint a paragraph as one tag.
                int lineEnd = text.indexOf('\n', i + 1);
                if (close > i && (lineEnd < 0 || close < lineEnd) && isMarkupName(text, i + 1, close)) {
                    out.add(new SyntaxToken(base + i, base + close + 1, MARKUP));
                    i = close + 1;
                    continue;
                }
                i++;
                continue;
            }
            if (c == '@' && startsATag(text, i)) {
                int end = i + 1;
                while (end < text.length() && isTagChar(text.charAt(end))) end++;
                if (end > i + 1) {
                    out.add(new SyntaxToken(base + i, base + end, TAG));
                    i = valueAfter(text, base, text.substring(i, end), end, out);
                    continue;
                }
            }
            i++;
        }
    }

    /**
     * Whether the {@code @} at {@code i} introduces a tag rather than sitting inside a word.
     *
     * <p>Two shapes only: after a brace ({@code {@code}}) or at the start of a line's content, which in a
     * doc comment means after the leading {@code *}. An {@code @} anywhere else is an email address, an
     * annotation being discussed, or a Scaladoc-style reference — and none of those is a tag.</p>
     */
    private static boolean startsATag(String text, int i) {
        if (i > 0 && text.charAt(i - 1) == '{') return true;
        for (int back = i - 1; back >= 0; back--) {
            char c = text.charAt(back);
            if (c == '\n') return true;
            if (c == '*' || c == ' ' || c == '\t' || c == '\r') continue;
            return false;
        }
        return false;
    }

    private static boolean isTagChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** Emits the name a block tag names, and answers where scanning resumes. */
    private static int valueAfter(String text, int base, String tag, int from, List<SyntaxToken> out) {
        if (!TAGS_WITH_A_NAME.contains(tag)) return from;
        int at = from;
        while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\t')) at++;
        int end = at;
        // UP TO WHITESPACE OR THE TAG'S OWN CLOSE. `{@link List#add}` ends at the brace, and taking the
        // brace with it would colour punctuation as a name.
        while (end < text.length()) {
            char c = text.charAt(end);
            if (Character.isWhitespace(c) || c == '}' || c == '<') break;
            end++;
        }
        if (end > at) out.add(new SyntaxToken(base + at, base + end, VALUE));
        return end;
    }

    /**
     * Whether {@code <…>} is an HTML element rather than a comparison or a generic.
     *
     * <p>A name, optionally closing, optionally self-closing, with attributes allowed after it. Without
     * the test, {@code List<String>} written in prose and {@code a < b} both light up as markup — and a
     * doc comment is exactly where people write both.</p>
     */
    private static boolean isMarkupName(String text, int from, int to) {
        int at = from;
        if (at < to && text.charAt(at) == '/') at++;
        // LOWERCASE FIRST, which is what separates an element from a TYPE ARGUMENT. `List<String>` and
        // `Map<K, V>` are written in doc comments constantly and every one of them ends in a `>`, so a
        // name test alone accepts them: `<String>` is a perfectly well-formed element name.
        //
        // HTML is case-insensitive and `<P>` is therefore legal, so this is a convention rather than a
        // rule -- but it is the convention every javadoc in existence follows, and the alternative is a
        // fixed list of element names that silently drops the first one somebody uses that is not on it.
        if (at >= to || !Character.isLetter(text.charAt(at))) return false;
        if (!Character.isLowerCase(text.charAt(at))) return false;
        while (at < to && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '-')) at++;
        // Either the name filled the element, or what follows it is attribute territory rather than more
        // of an expression: `<p>`, `</ol>`, `<a href="x">`, `<br/>`.
        if (at == to) return true;
        char next = text.charAt(at);
        return next == ' ' || next == '/' || next == '\t' || next == '\n';
    }
}
