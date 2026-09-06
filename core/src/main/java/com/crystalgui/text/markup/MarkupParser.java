package com.crystalgui.text.markup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * obligation the repository has not already taken on — see {@code plan/editor-javadoc.md} §3.4, where the
 * licences of the three obvious sources are laid out. The spec is a specification; nobody's code is
 * copied here.</p>
 *
 * <h3>Why this is not {@code CgMarkupParser}</h3>
 *
 * <p>CrystalGraphics already has one, and it answers a different question. {@code CgMarkupParser} turns
 * markup into a {@code CgStyledText} — <b>one flat string plus non-overlapping style spans</b> — which is
 * what a renderer needs to draw a line: a chat message, a label, a tooltip with a bold word in it. Its
 * vocabulary is {@code <b>}/{@code <i>}/{@code <u>}/{@code <s>}/{@code <color=#RRGGBB>}, a bespoke set
 * rather than HTML's, with no entities and no attributes.</p>
 *
 * <p>A doc comment is not a line. Its paragraphs, its {@code <pre>} samples and its lists are
 * <em>blocks</em>, and a code sample has to become an element with its own background and its own
 * coloured runs. There is no way to say that in one string and a span list; encoding it in one is
 * precisely the wall of text this exists to stop.</p>
 *
 * <p><b>And the boundary forbids the merge anyway.</b> {@code CgStyledText} is in CrystalGraphics
 * <em>core</em>, and {@code core/src/headlessTest} takes {@code com.crystalgraphics:platform} and
 * deliberately not core — the absence is the assertion that a dedicated server can build and hold
 * documents with no GL and no fonts. Documentation is one of those: {@code SymbolInfo} lives beside the
 * language SPIs, which run headlessly for exactly this reason. A parser that reached CG core could not be
 * tested there and could not ship on a server.</p>
 *
 * <p>Where the two genuinely meet is <b>at draw time</b>, one layer down from here: when the popup paints
 * a paragraph, a run marked {@link MarkupSpan#STRONG} is a {@code CgStyleSpan}, and turning the one into
 * the other is the renderer's job and belongs on that side of the seam. This layer decides what the
 * document says; CrystalGraphics decides what a run of glyphs looks like.</p>
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

    /**
     * A start tag, an end tag, or a run of text.
     *
     * <p>{@code value} is the text for a text token and the {@code href} for a tag, which is what every
     * consumer of a link reads. {@code attributes} carries the rest of the small set below — kept apart
     * rather than folded in because {@code href} is on the hot path and the map is usually empty.</p>
     */
    private record Token(boolean text, String name, String value, boolean end, boolean selfClosing,
                         Map<String, String> attributes) {

        static Token text(String value) {
            return new Token(true, "", value, false, false, Map.of());
        }

        static Token tag(String name, boolean end, boolean selfClosing, Map<String, String> attributes) {
            return new Token(false, name, attributes.get("href"), end, selfClosing,
                    attributes.isEmpty() ? Map.of() : Map.copyOf(attributes));
        }

        String attribute(String key) {
            return attributes.get(key);
        }
    }

    /**
     * The attributes this layer acts on. Everything else is read and dropped.
     *
     * <p>Read rather than skipped, because a {@code >} inside a quoted value ends the tag early
     * otherwise — that is the class of bug an ad-hoc scanner ships with. Kept to a named set rather
     * than stored wholesale so a document cannot make this hold arbitrary strings.</p>
     */
    private static boolean wanted(CharSequence attribute) {
        return "href".contentEquals(attribute) || "alt".contentEquals(attribute)
                || "src".contentEquals(attribute) || "colspan".contentEquals(attribute)
                || "rowspan".contentEquals(attribute);
    }

    /**
     * Runs the state machine.
     *
     * <p>Attributes are read and all but {@link #wanted} ones discarded. They still have to be
     * <em>parsed</em> rather than skipped to the next {@code >}, or a {@code >} inside a quoted value
     * ends the tag early — which is exactly the class of bug an ad-hoc scanner ships with.</p>
     */
    private static List<Token> tokenize(String html) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StringBuilder name = new StringBuilder();
        StringBuilder attribute = new StringBuilder();
        StringBuilder value = new StringBuilder();
        Map<String, String> attributes = new HashMap<>(4);
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
                        attributes.clear();
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
                        attributes.clear();
                        state = State.TAG_NAME;
                    } else {
                        text.append("</").append(c);
                        state = State.DATA;
                    }
                    break;

                case TAG_NAME:
                    if (c == '>') {
                        tokens.add(Token.tag(name.toString(), end, false, attributes));
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
                        tokens.add(Token.tag(name.toString(), end, false, attributes));
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
                        tokens.add(Token.tag(name.toString(), end, false, attributes));
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
                        tokens.add(Token.tag(name.toString(), end, false, attributes));
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
                        tokens.add(Token.tag(name.toString(), end, false, attributes));
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
                        if (wanted(attribute)) attributes.put(attribute.toString(), value.toString());
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
                        if (wanted(attribute)) attributes.put(attribute.toString(), value.toString());
                        state = State.BEFORE_ATTRIBUTE_NAME;
                    } else if (c == '>') {
                        if (wanted(attribute)) attributes.put(attribute.toString(), value.toString());
                        tokens.add(Token.tag(name.toString(), end, false, attributes));
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
                        tokens.add(Token.tag(name.toString(), end, false, attributes));
                        state = State.DATA;
                    } else {
                        attribute.setLength(0);
                        attribute.append(Character.toLowerCase(c));
                        state = State.ATTRIBUTE_NAME;
                    }
                    break;

                case SELF_CLOSING_START_TAG:
                    if (c == '>') {
                        tokens.add(Token.tag(name.toString(), end, true, attributes));
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
     * The named references, which are <b>HTML 4.01's</b> and deliberately not HTML5's 2,231.
     *
     * <p>Where the line is drawn matters more than how many fall on each side of it. HTML5's table is a
     * generated file in every browser, and most of what it adds over HTML 4 is mathematical
     * ({@code &fjlig;}, {@code &nvrtrie;}) — notation for a typesetting system rather than for prose
     * about code. HTML 4's set is the one every authoring convention was written against, it is closed
     * and will not grow, and it covers what documentation actually contains: an accented letter in a
     * name, Greek in a formula, an arrow in a description of flow, and the typographic marks.</p>
     *
     * <p><b>It used to be twelve</b>, picked as "what javadoc must escape, plus a few" — which is true
     * of javadoc's own OUTPUT and not of what an author writes. {@code &eacute;} in a name and
     * {@code &rarr;} in a sentence both reached the reader as their own source text, and an entity shown
     * raw does not read as an unsupported entity; it reads as the renderer having broken the line.</p>
     *
     * <p>The four XML ones stay written out because they are most of the traffic — javadoc escapes them
     * because it must, so they turn up in comments containing no other entity at all. An unrecognised
     * name is still left as written, which reads as the author's own text rather than as a hole.</p>
     */
    private static String named(String body) {
        switch (body) {
            case "lt": return "<";
            case "gt": return ">";
            case "amp": return "&";
            case "quot": return "\"";
            default: return NAMED.get(body);
        }
    }

    /**
     * {@code name:hex} pairs, parsed once — the table as DATA rather than as 248 switch cases.
     *
     * <p>A case per entity is the obvious spelling and is a page and a half of code whose every line is
     * a chance to mistype a codepoint. This is the same information in a form that can be checked
     * against a generated list, and the map costs one small allocation per process.</p>
     */
    private static final Map<String, String> NAMED = namedTable(""
            + "AElig:c6 Aacute:c1 Acirc:c2 Agrave:c0 Alpha:391 Aring:c5 Atilde:c3 Auml:c4 Beta:392 Ccedil:c7 "
            + "Chi:3a7 Dagger:2021 Delta:394 ETH:d0 Eacute:c9 Ecirc:ca Egrave:c8 Epsilon:395 Eta:397 Euml:cb "
            + "Gamma:393 Iacute:cd Icirc:ce Igrave:cc Iota:399 Iuml:cf Kappa:39a Lambda:39b Mu:39c Ntilde:d1 "
            + "Nu:39d OElig:152 Oacute:d3 Ocirc:d4 Ograve:d2 Omega:3a9 Omicron:39f Oslash:d8 Otilde:d5 Ouml:d6 "
            + "Phi:3a6 Pi:3a0 Prime:2033 Psi:3a8 Rho:3a1 Scaron:160 Sigma:3a3 THORN:de Tau:3a4 Theta:398 "
            + "Uacute:da Ucirc:db Ugrave:d9 Upsilon:3a5 Uuml:dc Xi:39e Yacute:dd Yuml:178 Zeta:396 aacute:e1 "
            + "acirc:e2 acute:b4 aelig:e6 agrave:e0 alefsym:2135 alpha:3b1 and:2227 ang:2220 aring:e5 "
            + "asymp:2248 atilde:e3 auml:e4 bdquo:201e beta:3b2 brvbar:a6 bull:2022 cap:2229 ccedil:e7 "
            + "cedil:b8 cent:a2 chi:3c7 circ:2c6 clubs:2663 cong:2245 copy:a9 crarr:21b5 cup:222a curren:a4 "
            + "dArr:21d3 dagger:2020 darr:2193 deg:b0 delta:3b4 diams:2666 divide:f7 eacute:e9 ecirc:ea "
            + "egrave:e8 empty:2205 emsp:2003 ensp:2002 epsilon:3b5 equiv:2261 eta:3b7 eth:f0 euml:eb "
            + "euro:20ac exist:2203 fnof:192 forall:2200 frac12:bd frac14:bc frac34:be frasl:2044 gamma:3b3 "
            + "ge:2265 hArr:21d4 harr:2194 hearts:2665 hellip:2026 iacute:ed icirc:ee iexcl:a1 igrave:ec "
            + "image:2111 infin:221e int:222b iota:3b9 iquest:bf isin:2208 iuml:ef kappa:3ba lArr:21d0 "
            + "lambda:3bb lang:2329 laquo:ab larr:2190 lceil:2308 ldquo:201c le:2264 lfloor:230a lowast:2217 "
            + "loz:25ca lrm:200e lsaquo:2039 lsquo:2018 macr:af mdash:2014 micro:b5 middot:b7 minus:2212 "
            + "mu:3bc nabla:2207 nbsp:a0 ndash:2013 ne:2260 ni:220b not:ac notin:2209 nsub:2284 ntilde:f1 "
            + "nu:3bd oacute:f3 ocirc:f4 oelig:153 ograve:f2 oline:203e omega:3c9 omicron:3bf oplus:2295 "
            + "or:2228 ordf:aa ordm:ba oslash:f8 otilde:f5 otimes:2297 ouml:f6 para:b6 part:2202 permil:2030 "
            + "perp:22a5 phi:3c6 pi:3c0 piv:3d6 plusmn:b1 pound:a3 prime:2032 prod:220f prop:221d psi:3c8 "
            + "rArr:21d2 radic:221a rang:232a raquo:bb rarr:2192 rceil:2309 rdquo:201d real:211c reg:ae "
            + "rfloor:230b rho:3c1 rlm:200f rsaquo:203a rsquo:2019 sbquo:201a scaron:161 sdot:22c5 sect:a7 "
            + "shy:ad sigma:3c3 sigmaf:3c2 sim:223c spades:2660 sub:2282 sube:2286 sum:2211 sup:2283 sup1:b9 "
            + "sup2:b2 sup3:b3 supe:2287 szlig:df tau:3c4 there4:2234 theta:3b8 thetasym:3d1 thinsp:2009 "
            + "thorn:fe tilde:2dc times:d7 trade:2122 uArr:21d1 uacute:fa uarr:2191 ucirc:fb ugrave:f9 uml:a8 "
            + "upsih:3d2 upsilon:3c5 uuml:fc weierp:2118 xi:3be yacute:fd yen:a5 yuml:ff zeta:3b6 zwj:200d "
            + "zwnj:200c");

    private static Map<String, String> namedTable(String pairs) {
        Map<String, String> table = new HashMap<>(512);
        for (String pair : pairs.split(" ")) {
            int colon = pair.indexOf(':');
            if (colon <= 0) continue;
            table.put(pair.substring(0, colon),
                    new String(Character.toChars(Integer.parseInt(pair.substring(colon + 1), 16))));
        }
        return table;
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

        /**
         * One open list. Nesting is a stack of these, innermost last.
         *
         * <p><b>One object per list rather than parallel arrays.</b> This began as a {@code lists} stack
         * with an {@code ordered} stack beside it, and adding {@code <dl>} wanted a third — at which
         * point the row kind wanted a fourth and was written as a single field instead, which is a bug
         * waiting for the first {@code <dl>} nested inside a {@code <ul>}: the inner list would close a
         * row under the outer list's kind. State that is per-list belongs to the list.</p>
         */
        private static final class OpenList {
            /** Rows closed so far. */
            final List<MarkupBlock> items = new ArrayList<>();
            /**
             * Blocks gathered for the row currently being built.
             *
             * <p><b>Per list, and that is a fix rather than tidiness.</b> One shared list meant a nested
             * list inherited whatever its parent's row had gathered so far: {@code <li>before<ul>…}
             * opened the inner list with {@code before} still pending, so the inner list's first row
             * closed over it and the text moved from the outer item into the nested one. Same for a
             * {@code <dl>} inside an {@code <li>}, where it also arrived under the wrong KIND.</p>
             */
            final List<MarkupBlock> rowBlocks = new ArrayList<>();
            /**
             * What this container closes as — {@code LIST}, {@code DEFINITIONS}, {@code TABLE} or
             * {@code ROW}.
             *
             * <p>Was two booleans, which is the shape that wanted a third the moment {@code <dl>}
             * arrived and a fourth for {@code <table>}. A container has ONE kind; asking it to be
             * spelled as a set of flags means every reader has to reconstruct which combinations are
             * legal, and two of the four here are not.</p>
             */
            final MarkupBlock.Kind kind;
            final boolean ordered;
            /**
             * What the row currently being built will close as.
             *
             * <p>A {@code <ul>} has only {@link MarkupBlock.Kind#ITEM}. A {@code <dl>}'s rows alternate
             * term and detail, and since those are siblings in HTML rather than nested, the row that is
             * ending is the PREVIOUS one — so this tracks what was opened, not what is opening.</p>
             */
            MarkupBlock.Kind rowKind = MarkupBlock.Kind.ITEM;

            /** {@code 1} marks the row being built as a header cell. @see MarkupBlock.Kind#CELL */
            int rowLevel;

            /** How many columns and rows the cell being built covers. One unless it said otherwise. */
            int rowColspan = 1;
            int rowRowspan = 1;

            OpenList(MarkupBlock.Kind kind, boolean ordered) {
                this.kind = kind;
                this.ordered = ordered;
            }
        }

        /** Nested lists, innermost last. */
        private final List<OpenList> lists = new ArrayList<>();

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
                else openTag(token.name(), token.value(), token);
            }
            // A list left open by a missing `</ul>` still has to reach the document -- and its LAST item
            // has to be closed first, or the item everything after the final `<li>` went into is dropped
            // along with the close tag that never came.
            while (!lists.isEmpty()) {
                if (openList().kind == MarkupBlock.Kind.QUOTE) {
                    closeQuote();
                    continue;
                }
                closeOpenRow();
                closeList();
            }
            closeBlock();
            return blocks.isEmpty() ? MarkupDocument.EMPTY : new MarkupDocument(blocks);
        }

        private void openTag(String tag, String href, Token token) {
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
                case "dl":
                    closeBlock();
                    lists.add(new OpenList("dl".equals(tag)
                            ? MarkupBlock.Kind.DEFINITIONS : MarkupBlock.Kind.LIST,
                            "ol".equals(tag)));
                    break;
                case "table":
                    closeBlock();
                    lists.add(new OpenList(MarkupBlock.Kind.TABLE, false));
                    break;
                // TRANSPARENT. They group rows for styling and say nothing a reader needs, so the rows
                // inside them belong to the table rather than to a container of their own.
                case "thead":
                case "tbody":
                case "tfoot":
                    break;
                case "caption":
                    closeOpenRow();
                    setRowKind(MarkupBlock.Kind.CAPTION);
                    break;
                case "tr":
                    // A ROW IS ITS OWN CONTAINER, because a table nests two levels where a list nests
                    // one: the table collects rows and each row collects cells. Closing any row still
                    // open first is the same rule `<li>` follows -- rows are siblings, so the previous
                    // one ends where this begins whether or not `</tr>` was written.
                    if (openList() != null && openList().kind == MarkupBlock.Kind.ROW) {
                        closeOpenRow();
                        closeList();
                    }
                    lists.add(new OpenList(MarkupBlock.Kind.ROW, false));
                    break;
                case "td":
                case "th":
                    closeOpenRow();
                    setRowKind(MarkupBlock.Kind.CELL);
                    setRowLevel("th".equals(tag) ? 1 : 0);
                    setRowSpans(countOf(token.attribute("colspan")),
                            countOf(token.attribute("rowspan")));
                    break;
                /*
                 * AN IMAGE IS ITS ALT TEXT, which is the whole of what can be honoured here.
                 *
                 * Nothing can be drawn: a doc comment's `src` is relative to the generated page, so
                 * `doc-files/x.png` names a file that exists beside the HTML javadoc would have written
                 * and nowhere in a jar. There is no path to resolve and no loader to resolve it with.
                 *
                 * `alt` is not a consolation prize -- it is what the attribute is FOR, it is what every
                 * text-mode reader shows, and an author who wrote one wrote it to be read. An image
                 * without one is decorative by definition (HTML says so: `alt=""` is how you spell
                 * "skip me"), so it contributes nothing rather than a placeholder, which would put a
                 * box of apology in the middle of a sentence.
                 */
                case "img": {
                    String alt = token.attribute("alt");
                    if (alt != null && !alt.isEmpty()) {
                        if (inCode) verbatim.append(alt);
                        else run.append(alt);
                    }
                    break;
                }
                case "li":
                    closeOpenRow();
                    setRowKind(MarkupBlock.Kind.ITEM);
                    break;
                // A `<dt>` or `<dd>` closes whatever row was open before it, exactly as an `<li>` does --
                // these are siblings in HTML rather than nested, so the previous one ends where the next
                // begins and nothing closes them explicitly.
                case "dt":
                case "dd":
                    // CLOSES UNDER THE KIND THE OPEN ROW ALREADY IS, then becomes the new kind. Closing
                    // a `<dt>` under TERM reads correctly and is wrong every other time: it is the
                    // PREVIOUS row that is ending, and after a `<dd>` that row is a detail. Written the
                    // obvious way, every value in a section table was filed as a label -- so the label
                    // column held the values, the value column held nothing, and the block rendered as a
                    // stack of empty rows.
                    closeOpenRow();
                    setRowKind("dt".equals(tag) ? MarkupBlock.Kind.TERM : MarkupBlock.Kind.DETAIL);
                    break;
                case "blockquote":
                    // A CONTAINER, which it had never been. `MarkupBlock.QUOTE` has existed since the
                    // model did and `MarkupView` has always known how to draw one -- rule down the left
                    // edge and all -- but nothing ever produced one: this case closed the open block and
                    // stopped, so a `<blockquote>` was a paragraph break and its content came out as
                    // ordinary prose. Silent, and it looked like the sheet not styling quotes.
                    closeBlock();
                    lists.add(new OpenList(MarkupBlock.Kind.QUOTE, false));
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
                case "ul": case "ol": case "dl":
                    closeOpenRow();
                    closeList();
                    break;
                case "li": case "dt": case "dd":
                case "thead": case "tbody": case "tfoot":
                case "td": case "th":
                    break;
                case "caption":
                    closeOpenRow();
                    break;
                case "tr":
                    closeOpenRow();
                    closeList();
                    break;
                case "table":
                    // A `<table>` MISSING ITS `</tr>` still has to reach the document, so the row is
                    // closed here as well -- the same reason `build` unwinds every open list at the end.
                    if (openList() != null && openList().kind == MarkupBlock.Kind.ROW) {
                        closeOpenRow();
                        closeList();
                    }
                    closeOpenRow();
                    closeList();
                    break;
                case "p":
                    closeBlock();
                    break;
                case "blockquote":
                    closeQuote();
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

        /** The innermost open list, or {@code null} at the top level. */
        private OpenList openList() {
            return lists.isEmpty() ? null : lists.get(lists.size() - 1);
        }

        /** Records what the row now being built will close as. */
        private void setRowKind(MarkupBlock.Kind kind) {
            OpenList list = openList();
            if (list != null) list.rowKind = kind;
        }

        /** Records the level the row now being built closes with — a header cell's marker. */
        private void setRowLevel(int level) {
            OpenList list = openList();
            if (list != null) list.rowLevel = level;
        }

        /** How far the cell being built reaches. @see MarkupBlock#colspan */
        private void setRowSpans(int colspan, int rowspan) {
            OpenList list = openList();
            if (list == null) return;
            list.rowColspan = colspan;
            list.rowRowspan = rowspan;
        }

        /**
         * An attribute read as a count, or 1.
         *
         * <p>Anything unparseable is 1 rather than an error: a malformed {@code colspan} is a typo in
         * somebody's comment, and a cell that keeps its place is a better answer than a table that
         * collapses. The same reasoning as everywhere else here -- markup degrades, it does not throw.</p>
         */
        private static int countOf(String value) {
            if (value == null) return 1;
            try {
                return Math.max(1, Integer.parseInt(value.trim()));
            } catch (NumberFormatException notANumber) {
                return 1;
            }
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
            else openList().rowBlocks.add(block);
        }

        /**
         * Ends the row being built, under the kind the open list says it is.
         *
         * <p>Read off the list rather than passed in: a {@code <dl>}'s rows are not all the same, and
         * which one is ending is a fact about the list that is open, not about the tag that ended it.
         * Passing it worked and put the answer in the caller's hands, which is how a nested list came to
         * close its rows under its parent's kind.</p>
         */
        private void closeOpenRow() {
            closeBlock();
            OpenList list = openList();
            if (list == null || list.rowBlocks.isEmpty()) return;
            list.items.add(list.rowKind == MarkupBlock.Kind.CELL
                    ? MarkupBlock.cell(new ArrayList<>(list.rowBlocks), list.rowLevel,
                            list.rowColspan, list.rowRowspan)
                    : MarkupBlock.of(list.rowKind, new ArrayList<>(list.rowBlocks), list.rowLevel));
            list.rowBlocks.clear();
            list.rowKind = MarkupBlock.Kind.ITEM;
            list.rowLevel = 0;
            list.rowColspan = 1;
            list.rowRowspan = 1;
        }

        /**
         * Ends a {@code <blockquote>}, taking the blocks inside it as its CHILDREN.
         *
         * <p>Not through {@link #closeOpenRow}, which wraps what it has gathered in a row — a quote has
         * no rows, and wrapping would put an anonymous {@code ITEM} between the quote and its own
         * prose. Everything a quote holds is simply a block it holds.</p>
         */
        private void closeQuote() {
            closeBlock();
            OpenList list = openList();
            if (list == null || list.kind != MarkupBlock.Kind.QUOTE) return;
            lists.remove(lists.size() - 1);
            if (list.rowBlocks.isEmpty()) return;
            MarkupBlock quote = MarkupBlock.of(MarkupBlock.Kind.QUOTE,
                    new ArrayList<>(list.rowBlocks), 0);
            OpenList parent = openList();
            if (parent == null) blocks.add(quote);
            else parent.rowBlocks.add(quote);
        }

        private void closeList() {
            OpenList list = openList();
            if (list == null) return;
            lists.remove(lists.size() - 1);
            if (list.items.isEmpty()) return;
            MarkupBlock closed = MarkupBlock.of(list.kind, list.items, list.ordered ? 1 : 0);
            OpenList parent = openList();
            if (parent == null) {
                blocks.add(closed);
            } else if (parent.kind == MarkupBlock.Kind.TABLE
                    && closed.kind() == MarkupBlock.Kind.ROW) {
                // A ROW IS THE TABLE'S OWN ITEM, not a block inside the row the table is building.
                // Every other container is content that happens to sit in an item -- a `<ul>` inside an
                // `<li>` -- and goes through `rowBlocks`. A table has no such thing: its rows ARE its
                // items, so routing them the ordinary way would wrap each one in an anonymous ITEM.
                parent.items.add(closed);
            } else {
                parent.rowBlocks.add(closed);
            }
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
