package com.crystalgui.language.java.assist;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    /** Long enough to be worth reading, short enough that a popup does not become a document. */
    private static final int MAX_LENGTH = 4000;

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
            String text = flatten(tag.fragments(), name != null);
            if (text.isBlank()) continue;
            if (name == null) {
                // THE DESCRIPTION, which is the one tag with no name and always comes first.
                if (out.length() > 0) out.append("<p>");
                out.append(text);
            } else {
                tagged.add(new Section(rankOf(name), label(name) + text));
            }
        }

        // SECTION ORDER, NOT SOURCE ORDER -- ported from IntelliJ's `JavaDocInfoGenerator`, whose
        // method path emits deprecated, then the parameters, then the return, then the throws, then
        // since, author/version, the three API tags, see-also, and anything it does not recognise last.
        // A doc comment may write its tags in any order and plenty do, so rendering them as authored
        // means two comments describing the same method lay out differently -- which is exactly what a
        // reader uses position to avoid. `sort` is stable, so several `@param`s keep the order the
        // author declared them in, and that order IS meaningful: it is the parameter order.
        tagged.sort(Comparator.comparingInt(section -> section.rank));

        for (Section section : tagged) {
            // EACH BLOCK TAG IS ITS OWN PARAGRAPH. They were newline-separated, which the parser
            // collapses away like any other authored line break -- correctly, since a doc comment's
            // wrapping is not structure. A `@param` that runs on from the sentence before it is worse
            // than one on its own line, so the structure is stated rather than implied by whitespace.
            out.append("<p>").append(section.text);
        }
        String rendered = out.toString();
        if (rendered.isBlank()) return null;
        return rendered.length() <= MAX_LENGTH ? rendered
                : rendered.substring(0, MAX_LENGTH).stripTrailing() + "…";
    }

    /** A block tag paired with the section it sorts into. */
    private static final class Section {
        final int rank;
        final String text;

        Section(int rank, String text) {
            this.rank = rank;
            this.text = text;
        }
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
     * {@code @param} → {@code "param "}, and so on.
     *
     * <p>The tag's own spelling rather than a translated word, so an unrecognised or custom tag renders
     * as itself instead of vanishing — a doc comment that silently loses a line is worse than one that
     * shows a tag name.</p>
     */
    private static String label(String tagName) {
        String bare = tagName.startsWith("@") ? tagName.substring(1) : tagName;
        // BOLD, because it is a heading for the line that follows it. Emitted as markup rather than left
        // plain so the renderer can tell the label from the prose without knowing what a javadoc tag is.
        return "<b>" + escape(bare) + "</b> ";
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
        return out.toString().trim();
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
                case TagElement.TAG_SEE:
                    // THE TARGET IS CARRIED even though nothing navigates yet. It is the whole content of
                    // the tag, and dropping it here means re-resolving it later from a rendered label.
                    return "<a href=\"java:" + escape(subject) + "\">" + escape(subject) + "</a>";
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
