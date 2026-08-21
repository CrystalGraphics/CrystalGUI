package com.crystalgui.language.java.assist;

/**
 * <b>Method bodies out, declarations and javadoc in — and the output is still Java.</b>
 *
 * <p>M13 §25.2. A {@code src.zip} is 42.9 MB of which a documentation popup reads the header of one
 * declaration at a time; the bodies are the overwhelming majority of the bytes and are never quoted by
 * anything. This cuts them.</p>
 *
 * <h3>Why the output stays valid Java, and why that is the whole design</h3>
 *
 * <p>{@link SourceArchives} finds a source file, {@link AttachedSources} parses it with JDT, and
 * {@code JavaSignatures} quotes a substring of it. A new format would need a new reader at every one of
 * those, and the reader is the half that every developer with a JDK installed exercises daily while the
 * producer is the half almost nobody does. So a stripped archive is an ordinary archive: same index, same
 * parse, same quote, and the only thing that has ever seen the transform is its own test.</p>
 *
 * <p>Bodies become {@code &#123;&#125;} rather than vanishing, because a concrete method with no body is a
 * <em>parse</em> error while one with an empty body is at worst a missing return — and a missing return is
 * a semantic error in a unit nothing ever compiles. That distinction is the difference between a file
 * whose declarations all resolve and one that produces no bindings at all.</p>
 *
 * <h3>What it is NOT: a parser</h3>
 *
 * <p>A scanner, deliberately. The transform has to run on a Minecraft client, on the host side of the
 * bridge, where JDT lives behind the engine band and reaching it would mean opening a band to strip a
 * download. It also has to be right about text a parser would reject — {@code src.zip} for a JDK newer
 * than the running band is exactly the case — and a scanner degrades to "copied it verbatim" where a
 * parser degrades to nothing.</p>
 *
 * <p>What a scanner has to get right is the four places a brace can appear, and it is a short list:</p>
 *
 * <table>
 *   <tr><th>Brace</th><th>Recognised by</th><th>Kept?</th></tr>
 *   <tr><td>a <b>type</b> body</td><td>{@code class}/{@code interface}/{@code enum}/{@code record}
 *       followed by a name</td><td>descended into</td></tr>
 *   <tr><td>an <b>annotation</b> array argument — {@code @Target(&#123;METHOD&#125;)}</td>
 *       <td>bracket depth above zero</td><td>copied</td></tr>
 *   <tr><td>an <b>array initializer</b> — {@code int[] x = &#123;1,2&#125;}</td><td>neither of the above</td>
 *       <td>emptied, and {@code int[] x = &#123;&#125;} is legal</td></tr>
 *   <tr><td>a <b>member</b> body — method, constructor, initializer, enum constant, anonymous class</td>
 *       <td>neither of the above</td><td>emptied</td></tr>
 * </table>
 *
 * <p>The last two collapse into one rule on purpose: every one of them is legal empty, so nothing has to
 * tell them apart. {@code Runnable r = new Runnable() &#123;&#125;;} parses, {@code A &#123;&#125;} is an
 * enum constant with an empty body, and {@code static &#123;&#125;} is an initializer that does nothing.</p>
 *
 * <h3>The one contextual keyword, and why the name check exists</h3>
 *
 * <p>{@code class}, {@code interface} and {@code enum} are reserved, so seeing one is proof. {@code record}
 * is <b>contextual</b> — {@code void f(Foo record)} is ordinary Java, and treating that {@code (} … {@code
 * &#123;} as a type body would keep the whole method body and then scan its statements as if they were
 * declarations, turning every {@code if (x) &#123; … &#125;} into {@code if (x) &#123;&#125;}. So a keyword
 * counts only when a <em>name</em> follows it, which is the actual grammar and costs one lookahead. The
 * same check disposes of {@code String.class}, where the next token is a {@code ;}.</p>
 *
 * <h3>Field initializers are kept, which is a departure from the plan</h3>
 *
 * <p>§25.2 paired the body cut with {@code JavaSignatures}' rule for initializers — keep a literal, drop an
 * expression. The cut is here and that half is not, for two reasons found while writing it. Dropping an
 * initializer turns {@code static final int X = compute();} into an <b>uninitialised final</b>, which is a
 * definite-assignment error where the body cut produces none; and the popup <em>quotes the initializer</em>
 * — that is what {@code appendInitializerExpression} exists for — so dropping it would degrade the one
 * output this transform exists to feed. Initializers are also not where the bytes are. Bodies are.</p>
 */
public final class SourceHeaders {

    private SourceHeaders() {
    }

    /**
     * {@code source} with every member body replaced by {@code &#123;&#125;}.
     *
     * <p>Everything else is copied byte for byte: package and imports, javadoc and ordinary comments,
     * annotations, modifiers, type parameters, {@code extends}/{@code implements}/{@code permits} clauses,
     * field declarations and their initializers, and the author's own line breaks and indentation. A quote
     * taken from the result is therefore the same substring it would have been taken from the original,
     * which is the property {@code SourceHeadersTest} asserts rather than assumes.</p>
     *
     * <p>Never throws. Text this cannot make sense of — an unterminated comment, a stray brace, a file
     * that is not Java at all — comes back with whatever could not be interpreted left intact, because a
     * verbatim file is a worse saving and a perfectly good answer.</p>
     */
    public static String strip(String source) {
        if (source == null || source.isEmpty()) return source;
        int length = source.length();
        StringBuilder out = new StringBuilder(length);
        int at = 0;
        // Parentheses AND brackets together: both mean "inside an expression", which is the only
        // question asked of the count, and an annotation argument is the case that needs it.
        int nested = 0;
        boolean typeAhead = false;

        while (at < length) {
            int literal = endOfLiteral(source, at);
            if (literal > at) {
                out.append(source, at, literal);
                at = literal;
                continue;
            }
            char c = source.charAt(at);
            if (c == '(' || c == '[') {
                nested++;
                out.append(c);
                at++;
            } else if (c == ')' || c == ']') {
                nested = Math.max(0, nested - 1);
                out.append(c);
                at++;
            } else if (c == ';' || c == '}') {
                // A DECLARATION ENDED. Anything a keyword promised about the next brace is spent.
                if (nested == 0) typeAhead = false;
                out.append(c);
                at++;
            } else if (c == '{') {
                if (nested > 0 || typeAhead) {
                    typeAhead = false;
                    out.append(c);
                    at++;
                } else {
                    int close = matchingBrace(source, at);
                    out.append("{}");
                    // An unbalanced brace means the rest of the file is inside a body we cannot find the
                    // end of. Stopping here loses less than guessing: the declarations above it survive.
                    at = close < 0 ? length : close + 1;
                }
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = at + 1;
                while (end < length && Character.isJavaIdentifierPart(source.charAt(end))) end++;
                if (nested == 0 && isTypeKeyword(source, at, end) && namesATypeAt(source, end)) {
                    typeAhead = true;
                }
                out.append(source, at, end);
                at = end;
            } else {
                out.append(c);
                at++;
            }
        }
        return out.toString();
    }

    // ── The four keywords, and what has to follow one ───────────────────────────────────────────

    private static boolean isTypeKeyword(String source, int from, int end) {
        int length = end - from;
        if (length < 4 || length > 9) return false;
        String word = source.substring(from, end);
        return word.equals("class") || word.equals("interface")
                || word.equals("enum") || word.equals("record");
    }

    /**
     * Whether a <b>name</b> follows — the difference between a declaration and an ordinary identifier.
     *
     * <p>{@code String.class;} and {@code void f(Foo record)} both put a keyword where no declaration is,
     * and both are answered by the same question the grammar asks.</p>
     */
    private static boolean namesATypeAt(String source, int from) {
        int at = from;
        int length = source.length();
        while (at < length) {
            int literal = endOfLiteral(source, at);
            if (literal > at) {
                at = literal;
                continue;
            }
            char c = source.charAt(at);
            if (Character.isWhitespace(c)) {
                at++;
                continue;
            }
            return Character.isJavaIdentifierStart(c);
        }
        return false;
    }

    // ── Literals and comments, which every scan above has to step over ──────────────────────────

    /**
     * The index just past the comment or literal starting at {@code at}, or {@code at} itself.
     *
     * <p>One method rather than a state flag, so the three walks that need it — the main pass, the name
     * lookahead, and the brace match — cannot disagree about where a string ends. They did, in the first
     * draft: the brace matcher counted a {@code &#125;} inside a string literal.</p>
     */
    private static int endOfLiteral(String source, int at) {
        int length = source.length();
        char c = source.charAt(at);
        if (c == '/' && at + 1 < length) {
            char next = source.charAt(at + 1);
            if (next == '/') {
                int newline = source.indexOf('\n', at + 2);
                // The newline itself is left to the caller, so line structure is copied by one rule.
                return newline < 0 ? length : newline;
            }
            if (next == '*') {
                int close = source.indexOf("*/", at + 2);
                return close < 0 ? length : close + 2;
            }
            return at;
        }
        if (c == '"' && isTextBlockOpener(source, at)) return endOfTextBlock(source, at);
        if (c == '"' || c == '\'') return endOfQuoted(source, at, c);
        return at;
    }

    /**
     * {@code """} that actually opens a text block.
     *
     * <p>The delimiter must be followed by whitespace and then a line terminator — the JLS says so, and it
     * is also the only way to tell the opener apart from an empty string beside another quote. Without the
     * test, {@code "" + ""} scans as a text block and swallows the rest of the file.</p>
     */
    private static boolean isTextBlockOpener(String source, int at) {
        if (!source.startsWith("\"\"\"", at)) return false;
        for (int scan = at + 3; scan < source.length(); scan++) {
            char c = source.charAt(scan);
            if (c == '\n') return true;
            if (!Character.isWhitespace(c)) return false;
        }
        return false;
    }

    private static int endOfTextBlock(String source, int at) {
        int length = source.length();
        for (int scan = at + 3; scan < length; scan++) {
            char c = source.charAt(scan);
            if (c == '\\') {
                scan++;
            } else if (c == '"' && source.startsWith("\"\"\"", scan)) {
                return scan + 3;
            }
        }
        return length;
    }

    private static int endOfQuoted(String source, int at, char quote) {
        int length = source.length();
        for (int scan = at + 1; scan < length; scan++) {
            char c = source.charAt(scan);
            if (c == '\\') {
                scan++;
            } else if (c == quote) {
                return scan + 1;
            } else if (c == '\n') {
                // UNTERMINATED, which a scanner meets in half-written source and in text that is not Java.
                // Ending at the line keeps the damage to one line instead of consuming the file.
                return scan;
            }
        }
        return length;
    }

    /** The index of the {@code &#125;} closing the {@code &#123;} at {@code from}, or {@code -1}. */
    private static int matchingBrace(String source, int from) {
        int length = source.length();
        int depth = 0;
        int at = from;
        while (at < length) {
            int literal = endOfLiteral(source, at);
            if (literal > at) {
                at = literal;
                continue;
            }
            char c = source.charAt(at);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return at;
            }
            at++;
        }
        return -1;
    }
}
