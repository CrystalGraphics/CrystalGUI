package com.crystalgui.text.markup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the HTML subset documentation is written in into a {@link MarkupDocument}.
 *
 * <h3>The tokenizer follows the WHATWG state machine</h3>
 *
 * <p>Its states are the spec's, by name — {@code DATA}, {@code TAG_OPEN}, {@code TAG_NAME},
 * {@code BEFORE_ATTRIBUTE_NAME} and the rest — so this can be checked against §13.2.5 line by line
 * rather than against somebody's idea of how angle brackets work. That is the whole reason for writing it
 * this way: a hand-rolled scanner is shorter and is wrong in a different place for every input, and every
 * production parser (browsers, jsoup, the one Eclipse's converter uses) implements this machine.</p>
 *
 * <p>Following the specification rather than porting an implementation is also what keeps this free of an
 * obligation the repository has not already taken on — see {@code plan_javadoc.md} §3.4, where the
 * licences of the three obvious sources are laid out. The spec is a specification; nobody's code is
 * copied here.</p>
 *
 * <h3>A subset, and it says which</h3>
 *
 * <p>Not implemented: the full insertion-mode tree construction, foreign content, {@code <table>},
 * scripting, and the error-recovery rules that make a browser agree with another browser on malformed
 * input. Documentation is not a web page — it is prose with emphasis, code and lists in it, written by a
 * compiler's own doc tool — so what is here is the block and inline set §3.6 of the plan enumerates, and
 * <b>anything unrecognised degrades to its text</b>. An unknown tag is dropped and its content kept,
 * which is what stripping already did and is the right failure for a hover.</p>
 */
public final class MarkupParser {

    private MarkupParser() {
    }

    // ── The tokenizer ───────────────────────────────────────────────────────────────────────────

    /** WHATWG §13.2.5, the subset this needs. Named for the spec so the two can be read together. */
    private enum State {
        DATA,
        TAG_OPEN,
        END_TAG_OPEN,
        TAG_NAME,
        BEFORE_ATTRIBUTE_NAME,
        ATTRIBUTE_NAME,
        AFTER_ATTRIBUTE_NAME,
        BEFORE_ATTRIBUTE_VALUE,
        ATTRIBUTE_VALUE_DOUBLE_QUOTED,
        ATTRIBUTE_VALUE_SINGLE_QUOTED,
        ATTRIBUTE_VALUE_UNQUOTED,
        AFTER_ATTRIBUTE_VALUE_QUOTED,
        SELF_CLOSING_START_TAG,
        MARKUP_DECLARATION_OPEN,
        COMMENT
    }

    /** A start tag, an end tag, or a run of text. */
    private record Token(boolean text, String name, String value, boolean end, boolean selfClosing) {

        static Token text(String value) {
            return new Token(true, "", value, false, false);
        }

        static Token tag(String name, boolean end, boolean selfClosing, String href) {
            return new Token(false, name, href, end, selfClosing);
        }
    }

    /**
     * Runs the state machine.
     *
     * <p>Attributes are read and all but {@code href} discarded: it is the only one anything downstream
     * can act on. They still have to be <em>parsed</em> rather than skipped to the next {@code >}, or a
     * {@code >} inside a quoted value ends the tag early — which is exactly the class of bug an ad-hoc
     * scanner ships with.</p>
     */
    private static List<Token> tokenize(String html) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StringBuilder name = new StringBuilder();
        StringBuilder attribute = new StringBuilder();
        StringBuilder value = new StringBuilder();
        String href = null;
        boolean end = false;
        State state = State.DATA;

        for (int at = 0; at < html.length(); at++) {
            char c = html.charAt(at);
            switch (state) {
                case DATA:
                    if (c == '<') {
                        state = State.TAG_OPEN;
                    } else if (c == '&') {
                        int consumed = appendEntity(html, at, text);
                        at = consumed < 0 ? at : consumed;
                        if (consumed < 0) text.append(c);
                    } else {
                        text.append(c);
                    }
                    break;

                case TAG_OPEN:
                    if (c == '/') {
                        state = State.END_TAG_OPEN;
                    } else if (c == '!') {
                        state = State.MARKUP_DECLARATION_OPEN;
                    } else if (Character.isLetter(c)) {
                        // FLUSHED HERE, not when the tag closes: the text before a tag belongs to what
                        // came before it, and a tag that never closes must not swallow it.
                        flush(tokens, text);
                        name.setLength(0);
                        name.append(Character.toLowerCase(c));
                        end = false;
                        href = null;
                        state = State.TAG_NAME;
                    } else {
                        // Not a tag at all. The spec emits the `<` as character data, which is what makes
                        // `a < b` in prose survive.
                        text.append('<').append(c);
                        state = State.DATA;
                    }
                    break;

                case END_TAG_OPEN:
                    if (Character.isLetter(c)) {
                        flush(tokens, text);
                        name.setLength(0);
                        name.append(Character.toLowerCase(c));
                        end = true;
                        href = null;
                        state = State.TAG_NAME;
                    } else {
                        text.append("</").append(c);
                        state = State.DATA;
                    }
                    break;

                case TAG_NAME:
                    if (c == '>') {
                        tokens.add(Token.tag(name.toString(), end, false, href));
                        state = State.DATA;
                    } else if (c == '/') {
                        state = State.SELF_CLOSING_START_TAG;
                    } else if (isSpace(c)) {
                        state = State.BEFORE_ATTRIBUTE_NAME;
                    } else {
                        name.append(Character.toLowerCase(c));
                    }
                    break;

                case BEFORE_ATTRIBUTE_NAME:
                    if (isSpace(c)) break;
                    if (c == '>') {
                        tokens.add(Token.tag(name.toString(), end, false, href));
                        state = State.DATA;
                    } else if (c == '/') {
                        state = State.SELF_CLOSING_START_TAG;
                    } else {
                        attribute.setLength(0);
                        attribute.append(Character.toLowerCase(c));
                        state = State.ATTRIBUTE_NAME;
                    }
                    break;

                case ATTRIBUTE_NAME:
                    if (c == '=') {
                        state = State.BEFORE_ATTRIBUTE_VALUE;
                    } else if (c == '>') {
                        tokens.add(Token.tag(name.toString(), end, false, href));
                        state = State.DATA;
                    } else if (isSpace(c)) {
                        state = State.AFTER_ATTRIBUTE_NAME;
                    } else if (c == '/') {
                        state = State.SELF_CLOSING_START_TAG;
                    } else {
                        attribute.append(Character.toLowerCase(c));
                    }
                    break;

                case AFTER_ATTRIBUTE_NAME:
                    if (isSpace(c)) break;
                    if (c == '=') {
                        state = State.BEFORE_ATTRIBUTE_VALUE;
                    } else if (c == '>') {
                        tokens.add(Token.tag(name.toString(), end, false, href));
                        state = State.DATA;
                    } else if (c == '/') {
                        state = State.SELF_CLOSING_START_TAG;
                    } else {
                        attribute.setLength(0);
                        attribute.append(Character.toLowerCase(c));
                        state = State.ATTRIBUTE_NAME;
                    }
                    break;

                case BEFORE_ATTRIBUTE_VALUE:
                    if (isSpace(c)) break;
                    value.setLength(0);
                    if (c == '"') {
                        state = State.ATTRIBUTE_VALUE_DOUBLE_QUOTED;
                    } else if (c == '\'') {
                        state = State.ATTRIBUTE_VALUE_SINGLE_QUOTED;
                    } else if (c == '>') {
                        tokens.add(Token.tag(name.toString(), end, false, href));
                        state = State.DATA;
                    } else {
                        value.append(c);
                        state = State.ATTRIBUTE_VALUE_UNQUOTED;
                    }
                    break;

                case ATTRIBUTE_VALUE_DOUBLE_QUOTED:
                case ATTRIBUTE_VALUE_SINGLE_QUOTED: {
                    char quote = state == State.ATTRIBUTE_VALUE_DOUBLE_QUOTED ? '"' : '\'';
                    if (c == quote) {
                        if ("href".contentEquals(attribute)) href = value.toString();
                        state = State.AFTER_ATTRIBUTE_VALUE_QUOTED;
                    } else if (c == '&') {
                        int consumed = appendEntity(html, at, value);
                        at = consumed < 0 ? at : consumed;
                        if (consumed < 0) value.append(c);
                    } else {
                        value.append(c);
                    }
                    break;
                }

                case ATTRIBUTE_VALUE_UNQUOTED:
                    if (isSpace(c)) {
                        if ("href".contentEquals(attribute)) href = value.toString();
                        state = State.BEFORE_ATTRIBUTE_NAME;
                    } else if (c == '>') {
                        if ("href".contentEquals(attribute)) href = value.toString();
                        tokens.add(Token.tag(name.toString(), end, false, href));
                        state = State.DATA;
                    } else {
                        value.append(c);
                    }
                    break;

                case AFTER_ATTRIBUTE_VALUE_QUOTED:
                    if (isSpace(c)) {
                        state = State.BEFORE_ATTRIBUTE_NAME;
                    } else if (c == '/') {
                        state = State.SELF_CLOSING_START_TAG;
                    } else if (c == '>') {
                        tokens.add(Token.tag(name.toString(), end, false, href));
                        state = State.DATA;
                    } else {
                        attribute.setLength(0);
                        attribute.append(Character.toLowerCase(c));
                        state = State.ATTRIBUTE_NAME;
                    }
                    break;

                case SELF_CLOSING_START_TAG:
                    if (c == '>') {
                        tokens.add(Token.tag(name.toString(), end, true, href));
                        state = State.DATA;
                    } else {
                        state = State.BEFORE_ATTRIBUTE_NAME;
                        at--;
                    }
                    break;

                case MARKUP_DECLARATION_OPEN:
                    // `<!-- … -->` and `<!DOCTYPE …>` alike: consumed and dropped. Documentation carries
                    // neither meaningfully, and a comment's content is not prose.
                    state = State.COMMENT;
                    at--;
                    break;

                case COMMENT:
                    if (c == '>') state = State.DATA;
                    break;

                default:
                    state = State.DATA;
                    break;
            }
        }
        flush(tokens, text);
        return tokens;
    }

    private static void flush(List<Token> tokens, StringBuilder text) {
        if (text.length() == 0) return;
        tokens.add(Token.text(text.toString()));
        text.setLength(0);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /**
     * A character reference, named or numeric.
     *
     * @return the index of the terminating {@code ;}, or -1 when this is not a reference — in which case
     *         the caller appends the {@code &} as data, which is what the spec does and what keeps
     *         "{@code a & b}" in prose intact.
     */
    private static int appendEntity(String html, int at, StringBuilder out) {
        int semicolon = html.indexOf(';', at + 1);
        // A bare `&` in prose is common and a reference is short; without a bound, "&" followed by a
        // semicolon anywhere later in the document swallows everything between them.
        if (semicolon < 0 || semicolon - at > 10) return -1;
        String body = html.substring(at + 1, semicolon);
        if (body.isEmpty()) return -1;

        if (body.charAt(0) == '#') {
            try {
                int code = body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X')
                        ? Integer.parseInt(body.substring(2), 16)
                        : Integer.parseInt(body.substring(1));
                if (code <= 0 || code > Character.MAX_CODE_POINT) return -1;
                out.appendCodePoint(code);
                return semicolon;
            } catch (NumberFormatException notANumber) {
                return -1;
            }
        }
        String named = named(body);
        if (named == null) return -1;
        out.append(named);
        return semicolon;
    }

    /**
     * The named references that actually appear in documentation.
     *
     * <p>The full HTML5 table is 2,231 entries and ships as a generated file in every browser. Nothing
     * here needs {@code &fjlig;}: javadoc escapes the four XML ones because it must, {@code &nbsp;} for
     * layout, and the quotes occasionally. An unrecognised name is left as written, which reads as the
     * author's own text rather than as a hole.</p>
     */
    private static String named(String body) {
        switch (body) {
            case "lt": return "<";
            case "gt": return ">";
            case "amp": return "&";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            case "hellip": return "…";
            case "mdash": return "—";
            case "ndash": return "–";
            case "copy": return "©";
            case "reg": return "®";
            case "trade": return "™";
            default: return null;
        }
    }

    // ── The tree ────────────────────────────────────────────────────────────────────────────────

    /**
     * Parses {@code html} into blocks.
     *
     * <p>Null and blank both give {@link MarkupDocument#EMPTY} rather than an empty paragraph: a consumer
     * hides an empty document and would draw a blank band for an empty block.</p>
     */
    public static MarkupDocument parse(String html) {
        if (html == null || html.isBlank()) return MarkupDocument.EMPTY;
        return new Builder().build(tokenize(html));
    }

    /**
     * Turns the token stream into blocks and spans.
     *
     * <p>Not the spec's insertion-mode machine, and this is where the subset stops being the spec. That
     * machine exists to make two browsers agree about {@code <p><table><b>} — malformed markup nobody
     * writes on purpose. What is here is a block stack and an inline style stack, which is enough for
     * documentation and is legible; a full tree builder would be several hundred lines serving inputs a
     * doc comment does not contain.</p>
     */
    private static final class Builder {

        private final List<MarkupBlock> blocks = new ArrayList<>();
        private final List<MarkupSpan> pending = new ArrayList<>();
        private final StringBuilder run = new StringBuilder();
        private final List<String> openInline = new ArrayList<>();

        /** Nested lists, innermost last. Each holds the items closed so far. */
        private final List<List<MarkupBlock>> lists = new ArrayList<>();
        private final List<Boolean> ordered = new ArrayList<>();
        private final List<MarkupBlock> itemBlocks = new ArrayList<>();

        private int styles;
        private String link;
        private boolean inCode;
        private int headingLevel;
        private final StringBuilder verbatim = new StringBuilder();

        MarkupDocument build(List<Token> tokens) {
            for (Token token : tokens) {
                if (token.text()) {
                    if (inCode) verbatim.append(token.value());
                    else run.append(token.value());
                    continue;
                }
                if (token.end()) closeTag(token.name());
                else openTag(token.name(), token.value());
            }
            // A list left open by a missing `</ul>` still has to reach the document -- and its LAST item
            // has to be closed first, or the item everything after the final `<li>` went into is dropped
            // along with the close tag that never came.
            while (!lists.isEmpty()) {
                closeItem();
                closeList();
            }
            closeBlock();
            return blocks.isEmpty() ? MarkupDocument.EMPTY : new MarkupDocument(blocks);
        }

        private void openTag(String tag, String href) {
            switch (tag) {
                case "p":
                    closeBlock();
                    break;
                case "br":
                    run.append('\n');
                    break;
                case "pre":
                    closeBlock();
                    inCode = true;
                    verbatim.setLength(0);
                    break;
                case "ul":
                case "ol":
                    closeBlock();
                    lists.add(new ArrayList<>());
                    ordered.add("ol".equals(tag));
                    break;
                case "li":
                    closeItem();
                    break;
                case "blockquote":
                    closeBlock();
                    break;
                case "h1": case "h2": case "h3": case "h4": case "h5": case "h6":
                    closeBlock();
                    headingLevel = tag.charAt(1) - '0';
                    break;
                case "code": case "tt":
                    pushInline(tag, MarkupSpan.CODE, null);
                    break;
                case "b": case "strong":
                    pushInline(tag, MarkupSpan.STRONG, null);
                    break;
                case "i": case "em": case "cite": case "var":
                    pushInline(tag, MarkupSpan.EMPHASIS, null);
                    break;
                case "a":
                    pushInline(tag, MarkupSpan.LINK, href);
                    break;
                default:
                    // Unrecognised: dropped, content kept. @see the class note.
                    break;
            }
        }

        private void closeTag(String tag) {
            switch (tag) {
                case "pre":
                    if (inCode) {
                        inCode = false;
                        String text = trimBlankEdges(verbatim.toString());
                        if (!text.isEmpty()) blocks.add(MarkupBlock.code(text));
                        verbatim.setLength(0);
                    }
                    break;
                case "ul": case "ol":
                    closeItem();
                    closeList();
                    break;
                case "li":
                    break;
                case "p": case "blockquote":
                    closeBlock();
                    break;
                case "h1": case "h2": case "h3": case "h4": case "h5": case "h6":
                    closeBlock();
                    break;
                case "code": case "tt":
                    popInline(tag, MarkupSpan.CODE);
                    break;
                case "b": case "strong":
                    popInline(tag, MarkupSpan.STRONG);
                    break;
                case "i": case "em": case "cite": case "var":
                    popInline(tag, MarkupSpan.EMPHASIS);
                    break;
                case "a":
                    popInline(tag, MarkupSpan.LINK);
                    link = null;
                    break;
                default:
                    break;
            }
        }

        private void pushInline(String tag, int style, String href) {
            endRun();
            openInline.add(tag);
            styles |= style;
            if (href != null) link = href;
        }

        private void popInline(String tag, int style) {
            endRun();
            int last = openInline.lastIndexOf(tag);
            if (last < 0) return;   // a close with no open — the author's, not ours to repair
            openInline.remove(last);
            if (!openInline.contains(tag)) styles &= ~style;
        }

        /**
         * Ends the current styled run and files it as a span.
         *
         * <p>A LEADING SPACE IS KEPT WHEN A SPAN CAME BEFORE IT, and dropped when none did. The space
         * between {@code </a>} and the next word is the only place that gap can live — the run before the
         * tag ended at the tag — so collapsing it away joins the two words. At the start of a block there
         * is nothing to be separated from and the same space is indentation.</p>
         */
        private void endRun() {
            if (run.length() == 0) return;
            String text = collapse(run.toString(), !pending.isEmpty());
            run.setLength(0);
            if (text.isEmpty()) return;
            pending.add(new MarkupSpan(text, styles, (styles & MarkupSpan.LINK) != 0 ? link : null));
        }

        private void closeBlock() {
            endRun();
            if (pending.isEmpty()) {
                headingLevel = 0;
                return;
            }
            MarkupBlock block = headingLevel > 0
                    ? MarkupBlock.heading(new ArrayList<>(pending), headingLevel)
                    : MarkupBlock.paragraph(new ArrayList<>(pending));
            pending.clear();
            headingLevel = 0;
            if (lists.isEmpty()) blocks.add(block);
            else itemBlocks.add(block);
        }

        private void closeItem() {
            closeBlock();
            if (lists.isEmpty() || itemBlocks.isEmpty()) return;
            lists.get(lists.size() - 1)
                    .add(MarkupBlock.of(MarkupBlock.Kind.ITEM, new ArrayList<>(itemBlocks), 0));
            itemBlocks.clear();
        }

        private void closeList() {
            if (lists.isEmpty()) return;
            List<MarkupBlock> items = lists.remove(lists.size() - 1);
            boolean isOrdered = ordered.remove(ordered.size() - 1);
            if (items.isEmpty()) return;
            MarkupBlock list = MarkupBlock.of(MarkupBlock.Kind.LIST, items, isOrdered ? 1 : 0);
            if (lists.isEmpty()) blocks.add(list);
            else itemBlocks.add(list);
        }
    }

    /**
     * Collapses runs of whitespace to one space — everywhere except inside {@code <pre>}.
     *
     * <p>Which is why it is applied per RUN rather than to the finished document: a doc comment is wrapped
     * by its author at whatever column their editor used, and those newlines are not paragraph breaks. In
     * a code sample the identical characters are the content.</p>
     */
    private static String collapse(String text, boolean keepLeadingSpace) {
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        boolean any = false;
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ' || c == '\f') {
                space = true;
                continue;
            }
            if (space && (any || keepLeadingSpace)) out.append(' ');
            space = false;
            any = true;
            out.append(c);
        }
        // A trailing space is kept when there was text before it: "one <b>two</b>" needs the gap, and the
        // spans either side of a tag boundary are the only place it can live.
        if (space && out.length() > 0) out.append(' ');
        // ...and a run that is NOTHING BUT space still separates its neighbours, which is the case
        // "</code> <code>" produces and which returning "" would silently join.
        if (out.length() == 0 && space && keepLeadingSpace) return " ";
        return out.toString();
    }

    /** Drops leading and trailing blank lines from a code sample without touching its indentation. */
    private static String trimBlankEdges(String text) {
        String[] lines = text.split("\n", -1);
        int first = 0;
        int last = lines.length - 1;
        while (first <= last && lines[first].isBlank()) first++;
        while (last >= first && lines[last].isBlank()) last--;
        if (first > last) return "";
        StringBuilder out = new StringBuilder();
        for (int at = first; at <= last; at++) {
            if (out.length() > 0) out.append('\n');
            out.append(stripTrailing(lines[at]));
        }
        return out.toString();
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && isSpace(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }

    /** Lower-cases a tag name the way the spec does — ASCII only, locale-independent. */
    static String normalise(String tag) {
        return tag == null ? "" : tag.toLowerCase(Locale.ROOT);
    }
}
