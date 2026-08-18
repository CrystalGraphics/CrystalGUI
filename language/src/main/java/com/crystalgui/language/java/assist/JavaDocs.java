package com.crystalgui.language.java.assist;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;

import javax.annotation.Nullable;

import java.util.ArrayList;
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
     * The comment as text, or null when there is nothing worth showing.
     *
     * <p>Null rather than empty, because {@code DocumentationPopup} hides the band on blank and an
     * empty string that reached it would be a gap under the definition that reads as a rendering
     * failure.</p>
     */
    @Nullable
    public static String render(@Nullable Javadoc javadoc) {
        if (javadoc == null) return null;
        StringBuilder out = new StringBuilder();
        List<String> tagged = new ArrayList<>();

        for (Object each : javadoc.tags()) {
            if (!(each instanceof TagElement)) continue;
            TagElement tag = (TagElement) each;
            String name = tag.getTagName();
            String text = flatten(tag.fragments(), name != null);
            if (text.isBlank()) continue;
            if (name == null) {
                // THE DESCRIPTION, which is the one tag with no name and always comes first.
                if (out.length() > 0) out.append("\n\n");
                out.append(text);
            } else {
                tagged.add(label(name) + text);
            }
        }

        for (String line : tagged) {
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        String rendered = collapse(out.toString());
        if (rendered.isBlank()) return null;
        return rendered.length() <= MAX_LENGTH ? rendered
                : rendered.substring(0, MAX_LENGTH).stripTrailing() + "…";
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
        return bare + " ";
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
        for (Object fragment : fragments) {
            String piece = pieceOf(fragment);
            if (piece == null) continue;
            if (out.length() > 0 && !piece.startsWith(" ") && needsSpace(out)) out.append(' ');
            out.append(piece);
            if (first && keepFirstAsSubject) out.append(" —");
            first = false;
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
            return stripHtml(((TextElement) fragment).getText());
        }
        if (fragment instanceof TagElement) {
            // AN INLINE TAG IS ITS SUBJECT. `{@link List#add}` reads as `List#add`; the braces are for a
            // renderer that does not exist yet, and showing them would be showing markup.
            return flatten(((TagElement) fragment).fragments(), false);
        }
        if (fragment instanceof ASTNode) {
            // A `@link`'s target is a Name or a MemberRef rather than text -- it is the whole point of
            // the tag, so it is rendered as written rather than skipped.
            return ((ASTNode) fragment).toString().trim();
        }
        return null;
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
