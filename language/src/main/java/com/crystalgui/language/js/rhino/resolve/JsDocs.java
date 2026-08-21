package com.crystalgui.language.js.rhino.resolve;

import com.crystalgui.text.markup.Markdown;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSDoc comment, as the markup the documentation popup reads.
 *
 * <h3>The counterpart of {@code JavaDocs}, and deliberately the same shape</h3>
 *
 * <p>A description, then a section table of block tags — {@code <dl>} with a heading beside each
 * value, in a fixed order rather than the author's. Everything downstream is language-neutral by
 * design: the model, the renderer, the link gesture and {@code Resolver.describe} all take a string of
 * markup and never ask which language produced it. That is what makes this one class rather than a
 * parallel stack.</p>
 *
 * <h3>The description is MARKDOWN, and that is the whole difference</h3>
 *
 * <p>Javadoc's is HTML, so {@code JavaDocs} passes an author's markup straight through. JSDoc's is
 * Markdown, so it is converted first — by {@link Markdown}, which lives in {@code core} because
 * nothing about it is JavaScript's, and which emits only tags {@code MarkupParser} already reads.</p>
 *
 * <p>Untouched prose was what shipped before this: {@code RhinoResolution} handed the description
 * across raw, so a documented function hovered with its markdown markers intact — asterisks around
 * words meant to be bold, backticks around names meant to be code, and a fenced example as three lines
 * of prose beginning with three backticks.</p>
 *
 * <h3>Child-side, and what that permits</h3>
 *
 * <p>This reads {@link RhinoJsDoc}, which reads Rhino's AST, so it is loaded by the engine band. The
 * rule for such a class is that it may name JDK types, the bridge, and {@code com.crystalgui.text.*} —
 * everything else under {@code com.crystalgui.language.*} is child-first and would be silently
 * redefined inside the band. {@code Markdown} is in {@code com.crystalgui.text.markup}, so it crosses
 * as one type rather than two, which is the same reason the tag grammar may hand back plain
 * strings.</p>
 */
final class JsDocs {

    private JsDocs() {
    }

    /**
     * The comment as markup, or null when it says nothing.
     *
     * <p>Null rather than empty for the reason the popup needs: an empty band is a gap under the
     * declaration that reads as a rendering failure, and no band is a popup that is simply shorter.</p>
     */
    @Nullable
    static String render(RhinoJsDoc doc) {
        if (doc == null) return null;
        StringBuilder out = new StringBuilder();
        out.append(Markdown.toHtml(doc.markdown()));

        List<Section> sections = new ArrayList<>();
        for (RhinoJsDoc.Tag tag : doc.tags()) {
            String text = bodyOf(tag);
            if (text.isBlank()) continue;
            sections.add(new Section(rankOf(tag.name()), tag.name(), headingFor(tag.name()), text));
        }
        // A STABLE SORT, so several `@param`s keep the order the author declared them in -- which IS the
        // parameter order, and is the one ordering in a doc comment that carries meaning.
        sections.sort(Comparator.comparingInt(section -> section.rank));

        Map<String, List<Section>> grouped = new LinkedHashMap<>();
        for (Section section : sections) {
            grouped.computeIfAbsent(section.heading, key -> new ArrayList<>()).add(section);
        }
        if (!grouped.isEmpty()) {
            out.append("<dl>");
            for (Map.Entry<String, List<Section>> entry : grouped.entrySet()) {
                out.append("<dt>").append(escape(entry.getKey())).append("</dt><dd>");
                List<Section> group = entry.getValue();
                if (group.size() == 1 || joinsInline(group.get(0).tag)) {
                    out.append("<p>");
                    for (int i = 0; i < group.size(); i++) {
                        if (i > 0) out.append(", ");
                        out.append(group.get(i).text);
                    }
                } else {
                    for (Section section : group) out.append("<p>").append(section.text);
                }
                out.append("</dd>");
            }
            out.append("</dl>");
        }

        String rendered = out.toString();
        return rendered.isBlank() ? null : rendered;
    }

    /** A block tag paired with the section it sorts into. */
    private static final class Section {
        final int rank;
        final String tag;
        final String heading;
        final String text;

        Section(int rank, String tag, String heading, String text) {
            this.rank = rank;
            this.tag = tag;
            this.heading = heading;
            this.text = text;
        }
    }

    /**
     * One tag's value, rendered.
     *
     * <p>Three shapes, because three kinds of tag carry three kinds of thing. A tag with a SUBJECT —
     * {@code @param {string} name what it is for} — is a name, a type and prose about them, and the
     * name is what a reader scans for. A tag that is CODE is a code block, because an
     * {@code @example} rendered as prose is a paragraph of semicolons. Everything else is prose, and
     * prose is Markdown.</p>
     */
    private static String bodyOf(RhinoJsDoc.Tag tag) {
        String text = tag.text();
        if (isCode(tag.name())) {
            // AS WRITTEN, not as markdown. An example is code even when it contains an asterisk, and
            // `Markdown` would read that asterisk as emphasis. A fence the author wrote themselves still
            // works, because a fenced block is passed through verbatim by the converter either way.
            return text.contains("```") ? Markdown.toHtml(text)
                    : "<pre>" + escape(text.stripTrailing()) + "</pre>";
        }
        if (!hasASubject(tag.name())) return inline(text);

        String type = braced(text);
        String rest = type == null ? text : text.substring(text.indexOf('}') + 1).stripLeading();
        String name = firstWord(rest);
        String prose = rest.substring(name.length()).stripLeading();

        StringBuilder row = new StringBuilder();
        if (!name.isEmpty()) row.append("<code>").append(escape(bareName(name))).append("</code>");
        if (type != null && !type.isBlank()) {
            if (row.length() > 0) row.append(' ');
            row.append("<code>").append(escape(type)).append("</code>");
        }
        // THE DASH, and only once there is something after it to introduce. `@param {string} name` with
        // no prose is a complete tag, and a trailing dash pointing at nothing is the fault `JavaDocs`
        // documents having shipped.
        if (!prose.isBlank()) {
            if (row.length() > 0) row.append(" — ");
            row.append(inline(prose));
        }
        return row.toString();
    }

    /**
     * A tag's prose, as markdown — with the paragraph wrapper taken back off.
     *
     * <p>{@link Markdown} answers a document and every document opens a block; here the value is going
     * INTO one, and a {@code <p>} inside a {@code <dd>} that already starts a paragraph is an empty line
     * before every row.</p>
     */
    private static String inline(String text) {
        String html = Markdown.toHtml(text.strip());
        return html.startsWith("<p>") ? html.substring(3) : html;
    }

    /** Whether the tag's first word is a NAME the rest describes. */
    private static boolean hasASubject(String tag) {
        switch (tag) {
            case "param":
            case "arg":
            case "argument":
            case "property":
            case "prop":
            case "throws":
            case "exception":
            case "returns":
            case "return":
            case "type":
            case "typedef":
            case "callback":
            case "augments":
            case "extends":
            case "implements":
            case "yields":
            case "template":
                return true;
            default:
                return false;
        }
    }

    /** Tags whose value is a sample rather than a sentence. */
    private static boolean isCode(String tag) {
        return tag.equals("example");
    }

    /** Tags whose several values read as one list rather than as separate statements. */
    private static boolean joinsInline(String tag) {
        return tag.equals("author") || tag.equals("version") || tag.equals("since")
                || tag.equals("license") || tag.equals("copyright");
    }

    /**
     * The heading a tag is shown under.
     *
     * <p>The same wording {@code JavaDocs} uses wherever the two languages share a tag, because they
     * are the same section to a reader and a popup that calls one "Returns:" and the other "Return
     * value:" is telling them the two are different. An unrecognised tag keeps its own name — inventing
     * a heading for somebody else's convention would be guessing.</p>
     */
    private static String headingFor(String tag) {
        switch (tag) {
            case "deprecated":   return "Deprecated:";
            case "param":
            case "arg":
            case "argument":     return "Params:";
            case "property":
            case "prop":         return "Properties:";
            case "returns":
            case "return":       return "Returns:";
            case "yields":       return "Yields:";
            case "throws":
            case "exception":    return "Throws:";
            case "type":         return "Type:";
            case "typedef":      return "Type definition:";
            case "callback":     return "Callback:";
            case "template":     return "Type parameters:";
            case "augments":
            case "extends":      return "Extends:";
            case "implements":   return "Implements:";
            case "example":      return "Example:";
            case "since":        return "Since:";
            case "author":       return "Author:";
            case "version":      return "Version:";
            case "see":          return "See Also:";
            case "todo":         return "To do:";
            case "license":      return "Licence:";
            case "copyright":    return "Copyright:";
            default:             return tag;
        }
    }

    /** Which section a tag sorts into — {@code JavaDocs}' order, extended with JavaScript's own. */
    private static int rankOf(String tag) {
        switch (tag) {
            case "deprecated":   return 0;
            case "template":     return 1;
            case "param":
            case "arg":
            case "argument":     return 2;
            case "property":
            case "prop":         return 3;
            case "returns":
            case "return":
            case "yields":       return 4;
            case "throws":
            case "exception":    return 5;
            case "type":
            case "typedef":
            case "callback":     return 6;
            case "augments":
            case "extends":
            case "implements":   return 7;
            case "example":      return 8;
            case "since":        return 9;
            case "author":       return 10;
            case "version":      return 11;
            case "see":          return 12;
            default:             return 13;
        }
    }

    /** The {@code {T}} at the front of a tag's text, or null. */
    @Nullable
    private static String braced(String text) {
        String trimmed = text.stripLeading();
        if (!trimmed.startsWith("{")) return null;
        int depth = 0;
        for (int at = 0; at < trimmed.length(); at++) {
            char c = trimmed.charAt(at);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return trimmed.substring(1, at);
        }
        return null;
    }

    private static String firstWord(String text) {
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        return text.substring(0, end);
    }

    /**
     * A parameter's name without JSDoc's optionality decoration.
     *
     * <p>{@code [count]} is an optional parameter and {@code [count=3]} one with a default. The
     * brackets are a fact about the SIGNATURE, and the signature is drawn from the declaration a few
     * lines above — repeating them in the row would be the popup disagreeing with itself about how many
     * arguments a function takes.</p>
     */
    private static String bareName(String name) {
        String bare = name;
        if (bare.startsWith("[") && bare.endsWith("]")) bare = bare.substring(1, bare.length() - 1);
        int equals = bare.indexOf('=');
        return equals > 0 ? bare.substring(0, equals) : bare;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
