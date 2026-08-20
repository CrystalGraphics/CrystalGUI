package com.crystalgui.language.java.assist;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * <b>A doc comment, rendered as the popup's body</b> — M13 §25.6.
 *
 * <h3>Why the body was empty for every symbol until now</h3>
 *
 * <p>{@code SymbolInfo.documentation} had never been populated by any engine, in dev or production, so
 * {@code DocumentationPopup} showed a declaration and a location and nothing else. Two things had to be
 * true and neither was: ECJ's doc-comment support had to be on ({@code EcjOptions} now sets it, and its
 * comment says why it was off), and something had to turn a {@code Javadoc} node into a string.</p>
 *
 * <h3>Javadoc is not prose with tags in it</h3>
 *
 * <p>{@code {@link}}, {@code {@code}}, {@code @param}, {@code @return}, embedded HTML and entities all
 * have to become something a reader wants. The rules here are small and each is a decision:</p>
 *
 * <ul>
 *   <li><b>An inline tag becomes its subject.</b> {@code {@link List#add}} is {@code List#add} and
 *       {@code {@code null}} is {@code null} — the reference itself is the information, and the braces
 *       are markup for a renderer that has not arrived yet.</li>
 *   <li><b>Block tags become labelled lines</b>, in the order written. {@code @param} and
 *       {@code @throws} keep their subject on the same line, because "name — description" is how every
 *       reference renders them and dropping the name loses which parameter is being described.</li>
 *   <li><b>HTML is stripped, not escaped.</b> A {@code <p>} is a paragraph break and everything else is
 *       removed. The alternative is showing angle brackets to a reader who did not write them.</li>
 * </ul>
 *
 * <h3>What this deliberately does not do yet</h3>
 *
 * <p>The output is <b>plain text</b>, which is what {@code SymbolInfo.documentation} is declared to be
 * and what the popup's body element takes today. §24.1 picked {@code CgMarkupParser} for the eventual
 * styled version and gave the reason it is right for the body and wrong for the signature — the body is
 * prose and may have its colours baked, the signature is code and may not. Nothing here forecloses it:
 * the band already exists and takes a string, so styling it is a change at the widget rather than a
 * change to this seam.</p>
 */
public final class JavaDocs {

    private JavaDocs() {
    }

    /**
     * The comment as <b>markup</b>, or null when there is nothing worth showing.
     *
     * <h3>Markup out, not plain text — and the HTML is the author's own</h3>
     *
     * <p>This used to strip every tag and collapse the result, which is what made the popup a wall: a
     * doc comment's {@code <p>}, {@code <pre>} and {@code <li>} are the only structure it has, and
     * throwing them away leaves prose with the paragraph breaks removed and its code samples inlined
     * into sentences.</p>
     *
     * <p>So the author's HTML is <b>passed through</b> and the javadoc-specific tags are resolved into
     * it — {@code {@code x}} becomes {@code <code>x</code>}, {@code {@link X}} becomes an anchor. That is
     * what IntelliJ's {@code JavaDocInfoGenerator} does, and for the same reason: the tags are the part
     * only a Java engine can resolve, and the HTML is already a thing the consumer can parse.
     * {@code MarkupParser} is that consumer; the whitespace collapsing that used to happen here is its
     * job now, because it is the only side that knows which text is inside a {@code <pre>}.</p>
     *
     * <p>Null rather than empty, because {@code DocumentationPopup} hides the band on blank and an
     * empty string that reached it would be a gap under the definition that reads as a rendering
     * failure.</p>
     */
    @Nullable
    public static String render(@Nullable Javadoc javadoc) {
        if (javadoc == null) return null;
        StringBuilder out = new StringBuilder();
        List<Section> tagged = new ArrayList<>();

        for (Object each : javadoc.tags()) {
            if (!(each instanceof TagElement)) continue;
            TagElement tag = (TagElement) each;
            String name = tag.getTagName();
            String text = TagElement.TAG_SEE.equals(name)
                    ? seeText(tag.fragments())
                    : flatten(tag.fragments(), hasASubject(name));
            if (text.isBlank()) continue;
            if (name == null) {
                // THE DESCRIPTION, which is the one tag with no name and always comes first.
                if (out.length() > 0) out.append("<p>");
                out.append(text);
            } else {
                tagged.add(new Section(rankOf(name), name, labelFor(name), text));
            }
        }

        // GROUPED BY TAG, so four `@author` lines are one Author row rather than four paragraphs, and
        // four `@see` lines are one See Also. IntelliJ does this and it is not cosmetic: a class with four
        // authors and four references produced eight stacked rows that read as eight unrelated facts, and
        // the eye has to notice the repeated label to group them itself.
        // SECTION ORDER, NOT SOURCE ORDER -- ported from IntelliJ's `JavaDocInfoGenerator`, whose
        // method path emits deprecated, then the parameters, then the return, then the throws, then
        // since, author/version, the three API tags, see-also, and anything it does not recognise last.
        // A doc comment may write its tags in any order and plenty do, so rendering them as authored
        // means two comments describing the same method lay out differently -- which is exactly what a
        // reader uses position to avoid. `sort` is stable, so several `@param`s keep the order the
        // author declared them in, and that order IS meaningful: it is the parameter order.
        tagged.sort(Comparator.comparingInt(section -> section.rank));

        // A DEFINITION LIST, which is what a section table IS: a label beside its value. Emitting
        // `<dl>` rather than a bespoke shape means the renderer needs no idea what a javadoc tag is, and
        // a JSDoc emitter producing the same shape gets the same two-column layout for free.
        //
        // Grouped by LABEL rather than by tag, because two tags can share one heading: `@throws` and
        // `@exception` are one Throws row. `tagged` is already in section order, so a LinkedHashMap
        // keeps the groups in it.
        Map<String, List<Section>> sections = new LinkedHashMap<>();
        for (Section section : tagged) {
            sections.computeIfAbsent(section.label(), key -> new ArrayList<>()).add(section);
        }
        if (!sections.isEmpty()) {
            out.append("<dl>");
            for (Map.Entry<String, List<Section>> entry : sections.entrySet()) {
                List<Section> group = entry.getValue();
                out.append("<dt>").append(escape(entry.getKey())).append("</dt><dd>");
                if (group.size() == 1 || joinsInline(group.get(0).tagName())) {
                    // ONE LINE, comma-separated. Four authors are four names in one answer, not four
                    // statements about authorship -- which is how IntelliJ renders them and how a reader
                    // reads them.
                    out.append("<p>");
                    for (int i = 0; i < group.size(); i++) {
                        if (i > 0) out.append(", ");
                        out.append(group.get(i).text());
                    }
                } else {
                    // ONE PER LINE, as a paragraph each. `<br>` was the obvious spelling and is silently
                    // wrong: the parser collapses whitespace per run, so a newline from `<br>` comes back
                    // out as a SPACE and four references ran together into one wrapped line.
                    for (Section section : group) out.append("<p>").append(section.text());
                }
                out.append("</dd>");
            }
            out.append("</dl>");
        }
        // NO LENGTH CAP. There was one -- 4000 characters, "short enough that a popup does not become
        // a document" -- and it was a reasonable trade when the popup was a fixed band of text. It is now
        // scrollable and resizable, so a long comment costs a scrollbar rather than a truncation, and the
        // reader decides when they have read enough.
        //
        // It was also cutting the WRONG THING. The value here is rendered markup, so the cut lands
        // wherever 4000 characters happens to fall -- mid-tag as readily as mid-word -- and the parser
        // downstream is handed markup that does not close. `java.lang.Class` is the everyday case: its
        // comment is several times the cap, so the nesting section ended in `, w…` and everything after
        // it was simply gone, with the ellipsis the only sign that it had been.
        String rendered = out.toString();
        return rendered.isBlank() ? null : rendered;
    }

    /**
     * A block tag paired with the section it sorts into.
     *
     * @param tagName the tag as written, which is what BEHAVIOUR keys on
     * @param label   the heading it is shown under, which is what it is GROUPED by — {@code @throws}
     *                and {@code @exception} are one Throws row, not two
     */
    private record Section(int rank, String tagName, String label, String text) {
    }

    /**
     * Whether a tag's first fragment is a <b>subject</b> that the rest describes.
     *
     * <p>{@code @param count the number of rows} is a name and a description, and the dash between them
     * is what makes that readable. Every other tag is one run of prose — and the dash was being
     * inserted into all of them, after whatever the first fragment happened to be, so
     * {@code @implNote The implementation ... is left to {@code javac} ...} rendered as
     * "left to — the discretion", a dash dropped mid-sentence at the first inline tag. It reads as a
     * stray character rather than as a rule misapplied, which is why it survived being looked at.</p>
     */
    private static boolean hasASubject(String tagName) {
        return TagElement.TAG_PARAM.equals(tagName)
                || TagElement.TAG_THROWS.equals(tagName)
                || TagElement.TAG_EXCEPTION.equals(tagName);
    }

    /**
     * The heading a tag is shown under — IntelliJ's own wording.
     *
     * <p>Its {@code JavaDocInfoGenerator} names these outright ("API Note", "Implementation
     * Requirements", "Implementation Note") and the rest follow the same shape: a human label with a
     * colon, not the tag's own spelling. {@code implNote} is a tag; "Implementation Note:" is what it
     * means, and a reader should not have to know javadoc to read a hover.</p>
     *
     * <p>An unrecognised tag keeps its own name, which is IntelliJ's answer too — {@code @jls} shows
     * as {@code jls}. Inventing a label for a tag nobody has defined would be guessing at what somebody
     * else's convention means.</p>
     */
    private static String labelFor(String tagName) {
        switch (tagName) {
            case TagElement.TAG_DEPRECATED:
                return "Deprecated:";
            case TagElement.TAG_PARAM:
                return "Params:";
            case TagElement.TAG_RETURN:
                return "Returns:";
            case TagElement.TAG_THROWS:
            case TagElement.TAG_EXCEPTION:
                return "Throws:";
            case TagElement.TAG_SINCE:
                return "Since:";
            case TagElement.TAG_AUTHOR:
                return "Author:";
            case TagElement.TAG_VERSION:
                return "Version:";
            case TagElement.TAG_SEE:
                return "See Also:";
            case "@apiNote":
                return "API Note:";
            case "@implSpec":
                return "Implementation Requirements:";
            case "@implNote":
                return "Implementation Note:";
            default:
                return tagName.startsWith("@") ? tagName.substring(1) : tagName;
        }
    }

    /**
     * Whether a section's values read as ONE list rather than as separate statements.
     *
     * <p>{@code @author} is the case: four of them are four names in one answer, and IntelliJ joins them
     * with commas on a single line. {@code @see} is the other: four references are four places to look,
     * and it gives each its own line. The split is per tag because it is a fact about what the tag means,
     * not about how many there happen to be — which is why this asks about the TAG and not about the
     * heading it is drawn under. Keying it on the wording made the rendering of a section depend on the
     * string somebody chose to title it with.</p>
     */
    private static boolean joinsInline(String tagName) {
        return TagElement.TAG_AUTHOR.equals(tagName)
                || TagElement.TAG_VERSION.equals(tagName)
                || TagElement.TAG_SINCE.equals(tagName);
    }

    /**
     * Which section a block tag belongs to, lower first.
     *
     * <p>The sequence is {@code JavaDocInfoGenerator}'s and the numbers are only its positions. An
     * unrecognised tag sorts last rather than being dropped: a doc comment may carry anything, and a
     * custom tag is still something its author wrote on purpose.</p>
     */
    private static int rankOf(String tagName) {
        switch (tagName) {
            case TagElement.TAG_DEPRECATED: return 0;
            case TagElement.TAG_PARAM:      return 1;
            case TagElement.TAG_RETURN:     return 2;
            case TagElement.TAG_THROWS:
            case TagElement.TAG_EXCEPTION:  return 3;
            case TagElement.TAG_SINCE:      return 4;
            case TagElement.TAG_AUTHOR:     return 5;
            case TagElement.TAG_VERSION:    return 6;
            case "@apiNote":                return 7;
            case "@implSpec":               return 8;
            case "@implNote":               return 9;
            case TagElement.TAG_SEE:        return 10;
            default:                        return 11;
        }
    }


    /**
     * A tag's fragments, flattened.
     *
     * <p>{@code keepFirstAsSubject} is what puts a {@code @param}'s name on the same line as its
     * description: the first fragment of a block tag is its subject, and separating it from the prose
     * would leave a list of descriptions with no way to tell which parameter each belongs to.</p>
     */
    private static String flatten(List<?> fragments, boolean keepFirstAsSubject) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        boolean previousWasText = false;
        boolean dashPending = false;
        for (Object fragment : fragments) {
            String piece = pieceOf(fragment);
            if (piece == null) continue;
            boolean isText = fragment instanceof TextElement;
            if (out.length() > 0) {
                // THE SUBJECT DASH, WRITTEN ONLY ONCE SOMETHING FOLLOWS IT. It separates a block tag's
                // subject from its description -- `name — the row's label` -- and was appended the
                // moment the subject was seen, which is right for `@param` and wrong for every tag whose
                // subject IS the whole content. `@author nobody` rendered as `author nobody —` and
                // `@see java.util.stream.Stream` as `... Stream —`, a dash pointing at nothing.
                if (dashPending) {
                    out.append(" —");
                    dashPending = false;
                }
                // A LINE BREAK IS TWO ADJACENT TEXT FRAGMENTS, and that is a fact about JDT rather than a
                // guess: it breaks a doc comment into one `TextElement` per SOURCE LINE, and subdivides a
                // line only around inline tags -- so two text fragments are never adjacent within a line,
                // and where they are adjacent there was a newline between them.
                //
                // The newline has to survive, because inside a `<pre>` it IS the sample's structure and
                // the emitter cannot know it is inside one -- only the parser tracks that. Joining with a
                // space instead is why `char data[] = {'a','b','c'};` and `String str = new String(data);`
                // arrived as one long line that ran out of the popup: two statements, correct characters,
                // and the shape of the sample destroyed. Everywhere else `MarkupParser` collapses the
                // newline to a space exactly as it collapses the author's own wrapping, so prose is
                // unaffected and no caller has to say which case it is in.
                if (isText && previousWasText) out.append('\n');
                else if (!piece.startsWith(" ") && needsSpace(out)) out.append(' ');
            }
            out.append(piece);
            if (first && keepFirstAsSubject) dashPending = true;
            first = false;
            previousWasText = isText;
        }
        // STRIP, NOT TRIM. `trim()` removes every character <= U+0020, control codes included, so it ate
        // the leading half of INHERIT_DOC whenever a comment OPENED with `{@inheritDoc}` -- which is the
        // commonest way to write one. The marker survived as `inheritDoc`, matched nothing, and
        // rendered its own name to the reader. `strip()` asks `Character.isWhitespace`, which U+0001 is
        // not; `render` already uses `stripTrailing()`, so nothing new is required of the toolchain.
        return out.toString().strip();
    }

    private static boolean needsSpace(StringBuilder out) {
        char last = out.charAt(out.length() - 1);
        return last != ' ' && last != '\n';
    }

    @Nullable
    private static String pieceOf(Object fragment) {
        if (fragment instanceof TextElement) {
            // VERBATIM. The author's HTML is the structure; see the note on render().
            return ((TextElement) fragment).getText();
        }
        if (fragment instanceof TagElement) {
            TagElement tag = (TagElement) fragment;
            // BEFORE the empty check, because this is the one inline tag whose whole content is its
            // absence: `{@inheritDoc}` has no fragments, so it would be dropped as an empty tag.
            if (TagElement.TAG_INHERITDOC.equals(tag.getTagName())) return INHERIT_DOC;
            String subject = flatten(tag.fragments(), false);
            if (subject.isEmpty()) return null;
            String name = tag.getTagName();
            if (name == null) return subject;
            switch (name) {
                case TagElement.TAG_CODE:
                case TagElement.TAG_LITERAL:
                    // ESCAPED, and this is the one place it is not optional: `{@code List<String>}` is
                    // full of angle brackets that are content. Passing them through would have the parser
                    // read `<String>` as a tag and drop it, so the sample would lose its type argument --
                    // silently, and only for generic code.
                    return "<code>" + escape(subject) + "</code>";
                case TagElement.TAG_LINK:
                case TagElement.TAG_LINKPLAIN:
                case TagElement.TAG_SEE: {
                    // A LINK IS A REFERENCE AND AN OPTIONAL LABEL, and the label is what a reader wants.
                    //
                    // `{@link java.lang.Character Character}` has both, and flattening the fragments into
                    // one string showed BOTH -- `java.lang.Character Character`, the qualified name with
                    // its own short form stuck to the end. It reads as a rendering fault because it is
                    // one; IntelliJ shows `Character`, which is what the author asked for by writing a
                    // label at all.
                    //
                    // The first fragment is the reference and the rest are the label, which is javadoc's
                    // own grammar rather than a guess. The reference still goes in the href: it is the
                    // half that can be resolved, and dropping it means re-deriving a target from display
                    // text later.
                    return referenceLink(tag.fragments());
                }
                case TagElement.TAG_VALUE:
                    return "<code>" + escape(subject) + "</code>";
                default:
                    return subject;
            }
        }
        if (fragment instanceof ASTNode) {
            // A `@link`'s target is a Name or a MemberRef rather than text -- it is the whole point of
            // the tag, so it is rendered as written rather than skipped.
            return ((ASTNode) fragment).toString().trim();
        }
        return null;
    }

    /**
     * Where an {@code {@inheritDoc}} stood, for whoever can resolve it.
     *
     * <p><b>A marker rather than the text</b>, because this class renders one comment and the tag asks
     * for a different one. Resolving it needs the binding's supertypes, which is
     * {@code JavaSignatures}' walk and not something a {@code Javadoc} node can answer — so the
     * emitter marks the spot and the caller that already knows how to find an inherited comment fills
     * it. Splitting it the other way would mean handing this class a binding it has no other use for.</p>
     *
     * <p>{@code U+0001} for the same reason a placeholder path uses it: it is legal in no real doc
     * comment, so the substitution cannot collide with something an author wrote. {@code U+0000} is
     * refused outright by other parts of this codebase, which is why it is not that one.</p>
     */
    public static final String INHERIT_DOC = "\u0001inheritDoc\u0001";

    /**
     * A block {@code @see}'s content — as a link when it names something, verbatim when it does not.
     *
     * <h3>From the fragments, not from the rendered text</h3>
     *
     * <p>An inline {@code {@link}} already becomes a link, because it is a {@code TagElement} and goes
     * through {@link #pieceOf}. A <b>block</b> {@code @see} does not: its reference is a bare
     * {@code Name} or {@code MemberRef} at the top level, so it fell through to the generic text path
     * and rendered fully qualified while the identical reference written inline rendered short — two
     * spellings of one thing, disagreeing on screen, in the same popup.</p>
     *
     * <p>The first version fixed that by splitting the <em>flattened</em> text on its first space and
     * re-escaping the halves, which works and is the wrong shape: {@code flatten} emits markup, so that
     * is a parser for this method's own output, and anything the author had already escaped went round
     * a second time. Asking the fragments is both simpler and exact — and it means the block form and
     * the inline form are now rendered by literally the same method, so they cannot drift.</p>
     *
     * <p>{@code @see} has two forms that are not references: a quoted string
     * ({@code @see "The Java Language Specification"}) and an author's own anchor
     * ({@code @see <a href="...">}). Both arrive as a leading {@code TextElement} and are left exactly
     * as written — shortening a sentence at its last dot would take the end off it.</p>
     */
    private static String seeText(List<?> fragments) {
        if (!fragments.isEmpty() && !(fragments.get(0) instanceof TextElement)) {
            String link = referenceLink(fragments);
            if (link != null) return link;
        }
        return flatten(fragments, false);
    }

    /**
     * A reference and its optional label, as a link — javadoc's own grammar for both
     * {@code {@link}} and {@code @see}.
     *
     * <p>The first fragment is the reference and the rest are the label. The reference goes in the
     * href because it is the half that can be resolved; the label is what a reader wants to see, and
     * when there is none the reference is shortened for display.</p>
     */
    private static String referenceLink(List<?> parts) {
        Object first = parts.isEmpty() ? null : parts.get(0);
        String reference = parts.isEmpty() ? "" : flatten(parts.subList(0, 1), false);
        String label = parts.size() > 1 ? flatten(parts.subList(1, parts.size()), false) : "";
        // THE LABEL IS ALREADY MARKUP; A DERIVED NAME IS NOT. `flatten` passes an author's HTML
        // through verbatim -- that is the whole design, the author's markup is the structure -- so a
        // label has been rendered by the time it arrives here and escaping it again turns it back into
        // text. `{@linkplain Class#isHidden() <em>hidden</em>}` is the JDK's own spelling and it drew as
        // a literal `<em>hidden</em>`, angle brackets and all, in the middle of a sentence.
        //
        // Same mistake as the block `@see` made before it, from the other end: that one PARSED this
        // method's output, this one re-escaped it. A value that has already been rendered is finished.
        String shown = label.isEmpty() ? escape(simpleReference(reference)) : label;
        if (shown.isEmpty()) return null;
        return "<a href=\"java:" + escape(targetOf(first, reference)) + "\">" + shown + "</a>";
    }

    /**
     * The reference to put in the href — <b>qualified</b>, where the binding can say so.
     *
     * <p>A reference is written against the imports and package of the file it appears in, and the
     * popup that follows it has neither. {@code java.text.Collator} says {@code @see RuleBasedCollator},
     * which is unambiguous there and resolves to nothing anywhere else: {@code Resolver.describe} builds
     * {@code class $Probe { RuleBasedCollator $x; }}, the probe does not compile, and the link is
     * refused — correctly, and to the reader it is a blue word that does nothing. Every {@code @see} in
     * the JDK's own sources is written this way, so it was most of them.</p>
     *
     * <p>The binding is right there. Both units a comment can come from resolve them — the editor's own,
     * and an attached source, which {@code AttachedSources} parses with {@code setResolveBindings(true)}
     * — so this asks the name what it actually resolved to rather than guessing at an import list.</p>
     *
     * <p><b>The erasure, not the binding's own qualified name.</b> A generic type answers
     * {@code java.util.List<E>}, which is not a name any probe can declare. The erasure gives
     * {@code java.util.List}, and it keeps the SOURCE spelling of a nested type
     * ({@code java.util.Map.Entry} rather than the binary {@code Map$Entry}), which is what the probe
     * has to be able to write down.</p>
     *
     * <p>Falls back to the reference as written whenever there is no binding — an unresolved reference,
     * or a comment parsed without one. That is exactly the previous behaviour, so nothing that worked
     * before can regress.</p>
     */
    private static String targetOf(@Nullable Object fragment, String asWritten) {
        if (!(fragment instanceof Name)) return asWritten;
        IBinding binding = ((Name) fragment).resolveBinding();
        if (!(binding instanceof ITypeBinding)) return asWritten;
        ITypeBinding erasure = ((ITypeBinding) binding).getErasure();
        if (erasure == null) return asWritten;
        String qualified = erasure.getQualifiedName();
        return qualified == null || qualified.isEmpty() ? asWritten : qualified;
    }

    /**
     * A reference as a reader wants to see it — {@code java.lang.Object#toString()} becomes
     * {@code Object.toString()}.
     *
     * <p>The package is dropped and the {@code #} becomes a dot, which is javadoc's own spelling of a
     * member for display. A bare {@code #member}, meaning "on this class", loses only the marker.</p>
     */
    private static String simpleReference(String reference) {
        int hash = reference.indexOf('#');
        String type = hash < 0 ? reference : reference.substring(0, hash);
        String member = hash < 0 ? "" : reference.substring(hash + 1);
        int lastDot = type.lastIndexOf('.');
        String simple = lastDot < 0 ? type : type.substring(lastDot + 1);
        if (member.isEmpty()) return simple;
        return simple.isEmpty() ? member : simple + "." + member;
    }

    /** Makes text safe to put inside emitted markup — the four that would otherwise re-parse. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Tags out, paragraph breaks in, entities decoded.
     *
     * <p>Only the four entities that actually appear. A general decoder would be a table nobody
     * maintains, and an unrecognised {@code &something;} is more readable left alone than turned into a
     * question mark.</p>
     */
    private static String stripHtml(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c != '<') {
                out.append(c);
                continue;
            }
            int close = text.indexOf('>', at);
            if (close < 0) {
                out.append(c);
                continue;
            }
            String tag = text.substring(at + 1, close).trim().toLowerCase(java.util.Locale.ROOT);
            if (tag.startsWith("p") || tag.startsWith("/p") || tag.startsWith("br")
                    || tag.startsWith("li") || tag.startsWith("/li")) {
                out.append('\n');
            }
            at = close;
        }
        return out.toString()
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&nbsp;", " ");
    }

    /** At most one blank line, and no trailing space — a doc comment's own wrapping is not layout. */
    private static String collapse(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        int blanks = 0;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                blanks++;
                if (blanks > 1 || out.length() == 0) continue;
                out.append('\n');
                continue;
            }
            blanks = 0;
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append(' ');
            out.append(trimmed);
        }
        return out.toString().strip();
    }
}
