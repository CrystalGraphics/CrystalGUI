package com.crystalgui.language.grammar;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * <b>Every javadoc construct the stack can meet, in one file.</b>
 *
 * <p>{@code Main.java} is the fixture for COLOURS. This one is for the <em>documentation</em> stack —
 * the emitter that turns a {@code Javadoc} node into markup, the parser that reads it back, and the
 * popup that draws it. Hover any declaration below with Mod+Q; each one is here because some rule
 * somewhere is supposed to do something visible to it.</p>
 *
 * <h3>What is being exercised</h3>
 * <ol>
 *   <li>the HTML the parser supports, and the entities</li>
 *   <li>every inline tag: {@code @code}, {@code @literal}, {@code @link}, {@code @linkplain},
 *       {@code @value}, {@code @inheritDoc}</li>
 *   <li>every block tag that has a heading, and one that has not</li>
 *   <li>the three legal shapes of {@code @see}, which are not all references</li>
 *   <li>grouping: several {@code @author}s are one row, several {@code @see}s are one row of many
 *       lines</li>
 *   <li>the shapes that used to break: an unqualified reference, a label with markup in it, and a
 *       comment past the old length cap</li>
 * </ol>
 *
 * <h3>Lists, both kinds</h3>
 *
 * <p>An unordered list, which is what most prose uses:</p>
 * <ul>
 *   <li>a plain item</li>
 *   <li>an item with <b>bold</b>, <i>italic</i>, <code>code</code> and <tt>teletype</tt> in it</li>
 *   <li>an item holding a nested list:
 *     <ul>
 *       <li>inner one</li>
 *       <li>inner two</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>A definition list, which is what a section table is drawn as — so this should look exactly like
 * the {@code Since:}/{@code Author:} rows at the bottom of this popup:</p>
 * <dl>
 *   <dt>Term</dt>
 *   <dd>Its definition, which may run long enough to wrap inside its own column while the label
 *       stays beside it rather than drifting up.</dd>
 *   <dt>A much longer term than the first</dt>
 *   <dd>Which is what makes the label column size itself to the widest of them.</dd>
 * </dl>
 *
 * <h3>Tables</h3>
 *
 * <p>Worth having because the JDK's own documentation is full of them — measured:
 * {@code java.util.Formatter} carries nineteen in 88k characters of comment and its whole purpose is
 * the conversion reference, {@code java.util.regex.Pattern} three, {@code DateTimeFormatter} two.
 * Every column below should line up across its rows, and the header should be the only bold row:</p>
 * <table>
 *   <caption>Modes</caption>
 *   <thead>
 *     <tr><th>Mode</th><th>Meaning</th><th>Since</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>READ_WRITE</td><td>Reads and writes</td><td>1.0</td></tr>
 *     <tr><td>READ_ONLY</td><td>Reads only</td><td>1.2</td></tr>
 *     <tr><td>LEGACY</td><td>A much longer cell, which is what makes its column wide</td>
 *         <td>0.9</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>A table with no header row and no caption, which is equally legal:</p>
 * <table>
 *   <tr><td>one</td><td>two</td></tr>
 *   <tr><td>three</td><td>four</td></tr>
 * </table>
 *
 * <h3>Quoted and preformatted</h3>
 *
 * <blockquote>A blockquote, which the renderer draws with a rule down its left edge rather than a
 * border, because a border cannot be one-sided here.</blockquote>
 *
 * <p>A code block, which is lexed with the language of the file that carries the comment:</p>
 * <pre>{@code
 * Map<String, List<Integer>> byName = new HashMap<>();
 * byName.computeIfAbsent("k", key -> new ArrayList<>()).add(1);
 * for (var entry : byName.entrySet()) {
 *     System.out.println(entry.getKey() + " = " + entry.getValue());
 * }
 * }</pre>
 *
 * <h3>Entities and escapes</h3>
 *
 * <p>The parser knows &amp;lt; &amp;gt; &amp;amp; &amp;quot; &amp;apos; &amp;nbsp; &amp;hellip;
 * &amp;mdash; &amp;ndash; &amp;copy; &amp;reg; &amp;trade; — which render as
 * &lt; &gt; &amp; &quot; &apos; &hellip; &mdash; &ndash; &copy; &reg; &trade;.</p>
 *
 * <p>{@literal @literal} passes its content through untouched, so {@literal <b>this</b>} is text
 * rather than markup and {@literal a < b && c > d} keeps its operators. {@code @code} escapes too but
 * draws on a plate: {@code List<String>} keeps its type argument.</p>
 *
 * <h3>Links, in every shape</h3>
 *
 * <p>A qualified link: {@link java.util.List}. An unqualified one, which resolves through this file's
 * imports and must still be followable: {@link Charset}. A member: {@link java.util.List#add(Object)}.
 * A link with a plain label: {@link java.util.Map Map}. A link whose label carries markup, which is
 * the JDK's own habit: {@linkplain java.util.Map <em>the</em> map}. A link to something nobody has,
 * which must do nothing rather than open an empty box: {@link no.such.Type}.</p>
 *
 * <p>A raw anchor the author wrote themselves, which is not a javadoc reference at all:
 * <a href="https://openjdk.org">openjdk.org</a>.</p>
 *
 * <h3>Non-ASCII, which is where offset bugs live</h3>
 *
 * <p>Grüße, здравствуйте, こんにちは, 🙂 — and an ellipsis … that the bundled font cannot draw.</p>
 *
 * @author nobody
 * @author somebody else
 * @author a third, so the row reads as one list rather than three statements
 * @version 2.1
 * @since 1.0
 * @see java.util.List
 * @see java.nio.charset.Charset
 * @see Map#entrySet()
 * @see "The Java Language Specification, section 15.18.1"
 * @see <a href="https://docs.oracle.com/javase/specs/">The specifications</a>
 * @apiNote An API note is prose for the caller, and IntelliJ gives it the heading "API Note:".
 * @implSpec An implementation specification binds a subclass. Its heading is "Implementation
 *           Requirements:", which is longer than the label column and must wrap inside it.
 * @implNote An implementation note describes this implementation and binds nobody.
 * @jls 15.18.1 String Concatenation Operator + — an unrecognised tag keeps its own name, because
 *      inventing a heading for somebody else's convention would be guessing.
 */
public final class DocShowcase {

    /**
     * A constant, whose value the popup can quote.
     *
     * <p>{@code @value} on the field itself reads as {@value}; naming another field reads as
     * {@value #GREETING}.</p>
     */
    public static final int MAX_RETRIES = 3;

    /** A second constant, so {@code @value #GREETING} above has something to point at. */
    public static final String GREETING = "hello";

    /** A field with the shortest possible comment. */
    private int count;

    /**
     * A constructor, which is a declaration like any other and carries the same tags.
     *
     * @param count how many times to try before giving up
     * @throws IllegalArgumentException if {@code count} is negative
     */
    public DocShowcase(int count) {
        if (count < 0) throw new IllegalArgumentException("count");
        this.count = count;
    }

    /**
     * A method with every subject-carrying tag, to check the dash and the ordering.
     *
     * <p>The tags below are deliberately written OUT OF ORDER — {@code @return} before
     * {@code @param}, {@code @deprecated} last — because the emitter sorts them into IntelliJ's
     * section order rather than the author's. If they render in the order typed, that is the bug.</p>
     *
     * @return the number of rows written, or {@code -1} when nothing was
     * @param name the name to write under, which may not be blank
     * @param rows the rows themselves; an empty list is legal and writes nothing
     * @throws IOException if the underlying stream refuses the write
     * @throws IllegalStateException if this instance has already been closed
     * @exception IllegalArgumentException {@code @exception} is {@code @throws}' older spelling, and
     *            both belong under one Throws heading rather than two
     * @deprecated Use {@link #writeAll(String, List)} instead. A deprecated method's own note sorts
     *             FIRST, above the parameters, because it is the thing you need before the rest.
     */
    @Deprecated
    public int write(String name, List<String> rows) throws IOException {
        return rows.isEmpty() ? -1 : rows.size();
    }

    /** The replacement the deprecation points at. */
    public int writeAll(String name, List<String> rows) {
        return rows.size();
    }

    /**
     * A generic method, where a type parameter is documented with angle brackets.
     *
     * @param <T> the element type, which is a type parameter rather than a value parameter
     * @param <R> what the mapper produces
     * @param items the elements to map
     * @param mapper turns one element into another
     * @return the mapped elements, in order
     */
    public <T, R> List<R> mapAll(List<T> items, java.util.function.Function<T, R> mapper) {
        return items.stream().map(mapper).collect(java.util.stream.Collectors.toList());
    }

    /**
     * A method whose comment is <b>only</b> prose, with no tags at all — the commonest shape there is,
     * and the one a section table must not appear under.
     */
    public void plain() {
    }

    /** A method with a one-line comment, which is the second commonest shape. */
    public void terse() {
    }

    /**
     * A comment long enough to have been cut by the old four-thousand-character cap.
     *
     * <p>Everything below this line is filler, and it is here for one reason: to prove that a long
     * comment now runs to its end. The cap cut RENDERED MARKUP, so it landed wherever four thousand
     * characters happened to fall — mid-tag as readily as mid-word — and the parser downstream was
     * handed markup that never closed. {@code java.lang.Class} is several times over it, which is why
     * its nesting section used to end in a comma and an ellipsis with everything after it gone.</p>
     *
     * <p>Filler one. The quick brown fox jumps over the lazy dog, and does so at some length, because
     * the point of this paragraph is its size rather than its content. It should wrap several times
     * inside the popup and the popup should scroll rather than truncate.</p>
     *
     * <p>Filler two. The quick brown fox jumps over the lazy dog, and does so at some length, because
     * the point of this paragraph is its size rather than its content. It should wrap several times
     * inside the popup and the popup should scroll rather than truncate.</p>
     *
     * <p>Filler three. The quick brown fox jumps over the lazy dog, and does so at some length,
     * because the point of this paragraph is its size rather than its content. It should wrap several
     * times inside the popup and the popup should scroll rather than truncate.</p>
     *
     * <p>Filler four. The quick brown fox jumps over the lazy dog, and does so at some length, because
     * the point of this paragraph is its size rather than its content. It should wrap several times
     * inside the popup and the popup should scroll rather than truncate.</p>
     *
     * <p>Filler five. The quick brown fox jumps over the lazy dog, and does so at some length, because
     * the point of this paragraph is its size rather than its content. It should wrap several times
     * inside the popup and the popup should scroll rather than truncate.</p>
     *
     * <p>Filler six. The quick brown fox jumps over the lazy dog, and does so at some length, because
     * the point of this paragraph is its size rather than its content. It should wrap several times
     * inside the popup and the popup should scroll rather than truncate.</p>
     *
     * <p><b>The last paragraph.</b> If you can read this sentence, the cap is gone. If the section
     * table below is missing, it is not — the tags are emitted after the description, so a cap takes
     * them first.</p>
     *
     * @since 1.0
     * @see #plain()
     */
    public void veryLong() {
    }

    /** A nested interface, so an override below has something to inherit a comment from. */
    public interface Writer {

        /**
         * Writes one row.
         *
         * <p>This is the comment an override inherits. If {@code @inheritDoc} works, the two methods
         * below show this paragraph — one on its own, one with the implementation's own prose around
         * it.</p>
         *
         * @param row the row to write, which may not be null
         * @return true when the row was written
         * @throws IOException if the stream refuses it
         */
        boolean writeRow(String row) throws IOException;

        /** A default method, which is a declaration with a body and a comment of its own. */
        default boolean writeNothing() {
            return false;
        }
    }

    /**
     * An implementation whose comment is <b>only</b> the inherited one.
     *
     * <p>This is the bare case, and the one that matters most: an override usually carries
     * {@code @Override} and nothing else, so the popup has to go and find the supertype's comment
     * without being asked.</p>
     */
    public static final class BareWriter implements Writer {
        @Override
        public boolean writeRow(String row) throws IOException {
            return row != null;
        }
    }

    /** An implementation that asks for the inherited text at a point inside its own prose. */
    public static final class ChattyWriter implements Writer {

        /**
         * {@inheritDoc}
         *
         * <p>Additionally, this implementation refuses a blank row. The paragraph above came from
         * {@link Writer#writeRow(String)}; this one did not.</p>
         *
         * @param row {@inheritDoc}
         * @return {@inheritDoc}
         */
        @Override
        public boolean writeRow(String row) throws IOException {
            return row != null && !row.isBlank();
        }
    }

    /**
     * An enum, whose constants each carry their own comment.
     *
     * @see #values()
     */
    public enum Mode {

        /** Reads and writes. The commonest mode, and the default. */
        READ_WRITE,

        /**
         * Reads only.
         *
         * @implNote An enum constant takes block tags exactly as a method does.
         */
        READ_ONLY,

        /** @deprecated A constant may be deprecated on its own. */
        @Deprecated
        LEGACY
    }

    /**
     * A record, whose components are documented with {@code @param} on the type itself.
     *
     * @param name what the row is called
     * @param rows how many rows it holds
     */
    public record Summary(String name, int rows) {

        /** A compact constructor, which is a declaration the popup can be asked about. */
        public Summary {
            if (rows < 0) throw new IllegalArgumentException("rows");
        }
    }

    /**
     * An annotation type, which is a declaration with members of its own.
     *
     * @see java.lang.annotation.Retention
     */
    public @interface Marked {

        /**
         * Why it is marked.
         *
         * @return the reason, which defaults to nothing
         */
        String value() default "";
    }

    // ── PARITY ──────────────────────────────────────────────────────────────────────────────────
    //
    // Everything below is LEGAL javadoc that this stack does not fully handle yet. It is here so the
    // gap is visible rather than invisible: a construct nobody wrote is a construct nobody notices is
    // missing. Each comment says what a production renderer (javadoc itself, or IntelliJ) does with it,
    // so what you see can be compared against what it should be.

    /**
     * <b>Inline tags beyond the five that resolve.</b>
     *
     * <p>Supported and resolving: {@code @code}, {@code @literal}, {@code @link}, {@code @linkplain},
     * {@code @value}, {@code @inheritDoc}. Everything in this paragraph is standard and is NOT:</p>
     *
     * <p>{@index searchable} — javadoc adds the word to the search index and renders it as plain text.
     * {@summary The first sentence, said explicitly.} — since Java 10, this overrides the
     * end-of-first-sentence guess. {@systemProperty java.home} — renders the property name and indexes
     * it. {@docRoot} — the relative path back to the generated root, meaningless in a popup and
     * usually the right answer is to drop it. And the inline {@return the thing returned}, which since
     * Java 16 is a whole {@code @return} written in the first sentence.</p>
     *
     * <p>A snippet, which is Java 18's replacement for {@code <pre>{@code}}:</p>
     * {@snippet lang=java :
     * var list = new ArrayList<String>();
     * list.add("one");   // @highlight substring="add"
     * }
     *
     * @see #parityBlockTags()
     */
    public void parityInlineTags() {
    }

    /**
     * <b>Block tags with no heading of their own.</b>
     *
     * <p>These are standard, and the emitter has no label for them — so each falls back to its own
     * name. javadoc gives the serialization three a "Serialized Form" section of its own, and treats
     * {@code @hidden} as a command rather than as content: a declaration carrying it does not appear
     * in the output at all.</p>
     *
     * @serial The field's serialized form.
     * @serialField rows int the number of rows, in the serialized form
     * @serialData What {@code writeObject} emits, in order.
     * @hidden This declaration should not be documented at all.
     * @provides java.nio.charset.spi.CharsetProvider a module-level tag
     * @uses java.nio.charset.spi.CharsetProvider the other half of the pair
     * @spec https://www.rfc-editor.org/rfc/rfc2119 RFC 2119
     * @custom.internal An entirely invented tag, which javadoc rejects unless registered with
     *                  {@code -tag} and which a renderer should still show rather than swallow.
     */
    public void parityBlockTags() {
    }

    /**
     * <b>HTML the parser does not know.</b>
     *
     * <p>The supported set is {@code p br pre ul ol dl li dt dd blockquote h1-h6 code tt b strong i em
     * cite var a table caption thead tbody tfoot tr th td}. Everything below is legal in a doc comment
     * and reaches the reader as something else — usually as its own angle brackets, which is the
     * failure worth seeing.</p>
     *
     * <p>A horizontal rule:</p>
     * <hr>
     *
     * <p>Phrase elements: <sub>subscript</sub>, <sup>superscript</sup>, <u>underline</u>,
     * <s>struck</s>, <del>deleted</del>, <ins>inserted</ins>, <kbd>Ctrl+C</kbd>, <samp>output</samp>,
     * <abbr title="as soon as possible">ASAP</abbr>, <dfn>a definition</dfn>, <q>a quotation</q>,
     * <small>small</small> and <big>big</big>.</p>
     *
     * <p>Grouping elements: <span>a span</span> and <div>a div</div>, which carry no meaning of their
     * own and are usually there to hang an attribute on.</p>
     *
     * <p>Attributes. The parser reads {@code href}, {@code alt}, {@code src}, {@code colspan} and
     * {@code rowspan}, and drops the rest — dropping them after PARSING them, so a {@code >} inside a
     * quoted value cannot end the tag early:
     * <a href="https://openjdk.org" target="_blank" title="a title">a link with three</a>, and a
     * <p id="anchor" class="note" style="color: red">paragraph with an id, a class and a style</p></p>
     *
     * <p>An image is its {@code alt} text — nothing can be drawn, because a doc comment's
     * {@code src} names a file beside the page javadoc would have generated:
     * <img src="diagram.png" alt="a diagram">. One with no {@code alt} is decorative by definition and
     * contributes nothing at all: <img src="spacer.png">.</p>
     *
     * <p>Self-closing and uppercase spellings, both legal in a doc comment:<br/>
     * <P>An uppercase paragraph.</P>
     * <BR>
     * <B>Uppercase bold</B> and <EM>uppercase emphasis</EM>.</p>
     *
     * <p>Entities. &#64; is a numeric one, &#x40; the same in hex, and the named set is HTML 4.01's
     * — &sect; &para; &bull; &rarr; &larr; &times; &divide; &deg; &plusmn; &frac12; &alpha; &beta;
     * &eacute; &uuml; &ntilde; &copy; &hellip; &mdash; all decode. An unknown one is left as the
     * author's own text: &fjlig; is HTML5-only and should read as itself.</p>
     *
     * <p>Malformed but legal-in-practice: an unclosed <b>bold that never closes, and a stray
     * &lt;/i&gt; that closes nothing.</p>
     *
     * <p>A table with spanning cells. The header covers two columns, so the rule between them must not
     * be drawn through it, and the columns below must still line up:</p>
     *
     * <table>
     *   <caption>Spans</caption>
     *   <tr><th colspan="2">One header over two</th><th>Third</th></tr>
     *   <tr><td rowspan="2">Reaches down</td><td>b</td><td>c</td></tr>
     *   <tr><td>e</td><td>f</td></tr>
     *   <tr><td>g</td><td>h</td><td>i</td></tr>
     * </table>
     */
    public void parityHtml() {
    }

    /**
     * <b>Comment shapes, rather than comment content.</b>
     *
     * <p>The forms below are all legal and all reach the emitter differently. This one is ordinary.
     * The three declarations under it are not.</p>
     */
    public void parityShapes() {
    }

    /** A one-line comment with the text on the same line as both delimiters. */
    public void parityOneLine() {
    }

    /**
       A comment with no leading asterisks on its body lines, which is legal and which the
       whitespace stripping has to handle: javadoc removes the common indent, not a literal
       "space star space".
     */
    public void parityNoStars() {
    }

    /**
     *
     * <p>A comment whose first line is blank, so the description does not start where it looks like
     * it starts. The first sentence — the bit a completion list shows — is this paragraph.</p>
     *
     * @param unused nothing uses this; a tag on a parameter that does not exist is legal to write and
     *               javadoc warns about it
     */
    public void parityBlankFirstLine() {
    }

    /**
     * A comment ending immediately after its last tag with no trailing prose. @since 1.0 */
    public void parityTrailingTag() {
    }
}
