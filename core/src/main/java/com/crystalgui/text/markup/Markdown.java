package com.crystalgui.text.markup;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown to the HTML {@link MarkupParser} already reads.
 *
 * <h3>Why a converter rather than a second parser</h3>
 *
 * <p>JSDoc descriptions are <b>Markdown</b> where javadoc's are HTML, and the plan called that "a second
 * parser feeding the same {@code MarkupDocument}". It is one step simpler than that: the seam a symbol's
 * documentation crosses is a <b>string</b> — {@code SymbolInfo.documentation()} — and the popup parses it
 * at the far end. So the two languages do not need two parsers, they need one output format, and HTML is
 * already it. A Markdown parser producing blocks directly would also need the seam to carry a document,
 * which is a change to every engine rather than to this one.</p>
 *
 * <p>The consequence worth stating: this emits only tags {@code MarkupParser} knows. Emitting anything
 * else would be writing a document nothing can read — the tag is dropped and its content kept, so the
 * failure would be silent and shaped like missing markup.</p>
 *
 * <h3>Raw HTML passes through</h3>
 *
 * <p>Markdown allows it and JSDoc authors use it, and {@code JavaDocs} already treats an author's HTML as
 * structure rather than as text. Prose survives because the parser only reads {@code <} as a tag when a
 * letter or a slash follows it — so {@code a < b} is text, which is what an author writing about
 * comparisons means by it.</p>
 *
 * <h3>What it does not do</h3>
 *
 * <p>No reference links ({@code [text][label]} with the target defined elsewhere), no footnotes, no
 * setext headings ({@code ===} underlines), no HTML blocks with blank lines inside them, no nested
 * blockquotes. Each is rare in a doc comment and none degrades badly: an unrecognised construct arrives
 * as its own text, which is what it looks like in the source.</p>
 */
public final class Markdown {

    private Markdown() {
    }

    /** The document, as markup {@link MarkupParser} can read. */
    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (String line : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            lines.add(line);
        }
        StringBuilder out = new StringBuilder();
        new Blocks(lines, out).run();
        return out.toString();
    }

    /** The block walk — one cursor over the lines, one branch per construct. */
    private static final class Blocks {

        private final List<String> lines;
        private final StringBuilder out;
        private int at;

        Blocks(List<String> lines, StringBuilder out) {
            this.lines = lines;
            this.out = out;
        }

        void run() {
            while (at < lines.size()) {
                String line = lines.get(at);
                if (line.isBlank()) {
                    at++;
                } else if (fence(line) != null) {
                    fencedCode();
                } else if (headingLevel(line) > 0) {
                    heading();
                } else if (isRule(line)) {
                    // NOTHING TO EMIT. `<hr>` is not a tag the parser knows, and a rule between two
                    // paragraphs is a separation the blank line already provides -- so it ends the
                    // paragraph and adds nothing, rather than arriving as a literal `---`.
                    at++;
                } else if (line.startsWith(">")) {
                    quote();
                } else if (bulletAt(line) >= 0 || orderedAt(line) >= 0) {
                    list(0);
                } else if (isTableDivider(at + 1)) {
                    table();
                } else if (indented(line)) {
                    indentedCode();
                } else {
                    paragraph();
                }
            }
        }

        // ── Leaf blocks ─────────────────────────────────────────────────────────────────────────

        private void paragraph() {
            StringBuilder text = new StringBuilder();
            while (at < lines.size()) {
                String line = lines.get(at);
                if (line.isBlank() || headingLevel(line) > 0 || fence(line) != null
                        || isRule(line) || line.startsWith(">")
                        || bulletAt(line) >= 0 || orderedAt(line) >= 0
                        || isTableDivider(at + 1)) {
                    break;
                }
                if (text.length() > 0) text.append(' ');
                text.append(line.trim());
                at++;
            }
            if (text.length() == 0) return;
            out.append("<p>").append(Inline.render(text.toString()));
        }

        private void heading() {
            String line = lines.get(at++);
            int level = headingLevel(line);
            String text = line.substring(level).trim();
            // TRAILING HASHES ARE A CLOSING FENCE, not content: `## Title ##` is a heading called Title.
            while (text.endsWith("#")) text = text.substring(0, text.length() - 1).stripTrailing();
            int shown = Math.min(6, level);
            out.append("<h").append(shown).append('>').append(Inline.render(text))
               .append("</h").append(shown).append('>');
        }

        private void quote() {
            StringBuilder text = new StringBuilder();
            while (at < lines.size() && lines.get(at).startsWith(">")) {
                String line = lines.get(at++).substring(1);
                if (line.startsWith(" ")) line = line.substring(1);
                if (line.isBlank()) continue;
                if (text.length() > 0) text.append(' ');
                text.append(line.trim());
            }
            out.append("<blockquote>").append(Inline.render(text.toString())).append("</blockquote>");
        }

        private void fencedCode() {
            String open = fence(lines.get(at++));
            StringBuilder code = new StringBuilder();
            while (at < lines.size()) {
                String line = lines.get(at);
                String close = fence(line);
                if (close != null && close.charAt(0) == open.charAt(0)) {
                    at++;
                    break;
                }
                code.append(line).append('\n');
                at++;
            }
            emitCode(code.toString());
        }

        private void indentedCode() {
            StringBuilder code = new StringBuilder();
            while (at < lines.size() && (indented(lines.get(at)) || lines.get(at).isBlank())) {
                String line = lines.get(at);
                // A TRAILING BLANK LINE IS NOT PART OF THE BLOCK, and consuming it here would swallow
                // the separation before whatever follows. Only blanks with more code after them are.
                if (line.isBlank() && !(at + 1 < lines.size() && indented(lines.get(at + 1)))) break;
                code.append(line.isBlank() ? "" : line.substring(4)).append('\n');
                at++;
            }
            emitCode(code.toString());
        }

        /**
         * A code block, escaped.
         *
         * <p>The one place escaping is not optional: a sample is full of angle brackets that are
         * content, and passing them through would have the parser read {@code <String>} as a tag and
         * drop it — losing the type argument silently, and only for generic code. Same reason
         * {@code JavaDocs} escapes {@code @code}.</p>
         */
        private void emitCode(String code) {
            String body = code;
            while (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
            if (body.isEmpty()) return;
            out.append("<pre>").append(escape(body)).append("</pre>");
        }

        // ── Lists ───────────────────────────────────────────────────────────────────────────────

        /**
         * A list, and any list nested inside its items.
         *
         * <p>Nesting is by INDENT, which is markdown's own rule and the only one available: a nested
         * list is written as an indented bullet inside the item above it, with no tag to say so.</p>
         */
        private void list(int indent) {
            boolean ordered = orderedAt(lines.get(at)) >= 0;
            out.append(ordered ? "<ol>" : "<ul>");
            while (at < lines.size()) {
                String line = lines.get(at);
                if (line.isBlank()) {
                    // A BLANK INSIDE A LIST is a loose item, not the end of the list -- unless what
                    // follows is not an item at all.
                    int next = at + 1;
                    while (next < lines.size() && lines.get(next).isBlank()) next++;
                    if (next >= lines.size() || markerAt(lines.get(next)) < 0) break;
                    if (leading(lines.get(next)) < indent) break;
                    at = next;
                    continue;
                }
                int marker = markerAt(line);
                if (marker < 0 || leading(line) < indent) break;
                if (leading(line) > indent) {
                    list(leading(line));
                    continue;
                }
                if ((orderedAt(line) >= 0) != ordered) break;
                at++;
                out.append("<li>").append(Inline.render(line.substring(marker).trim()));
                // CONTINUATION LINES belong to the item they follow -- an item wrapped across two lines
                // is one item, which is how a doc comment writes anything longer than a few words.
                while (at < lines.size() && !lines.get(at).isBlank()
                        && markerAt(lines.get(at)) < 0 && leading(lines.get(at)) > indent) {
                    out.append(' ').append(Inline.render(lines.get(at++).trim()));
                }
                out.append("</li>");
            }
            out.append(ordered ? "</ol>" : "</ul>");
        }

        // ── Tables ──────────────────────────────────────────────────────────────────────────────

        /**
         * A GFM table: a header row, a divider, then body rows.
         *
         * <p>Recognised by the DIVIDER rather than by the header, because a header row on its own is
         * indistinguishable from a paragraph containing pipes — and a sentence with a pipe in it is
         * ordinary prose, while {@code |---|---|} is not a sentence anybody writes by accident.</p>
         */
        private void table() {
            List<String> header = cells(lines.get(at++));
            at++; // the divider
            out.append("<table><tr>");
            for (String cell : header) {
                out.append("<th>").append(Inline.render(cell)).append("</th>");
            }
            out.append("</tr>");
            while (at < lines.size() && lines.get(at).contains("|") && !lines.get(at).isBlank()) {
                out.append("<tr>");
                for (String cell : cells(lines.get(at++))) {
                    out.append("<td>").append(Inline.render(cell)).append("</td>");
                }
                out.append("</tr>");
            }
            out.append("</table>");
        }

        private boolean isTableDivider(int index) {
            if (index <= 0 || index >= lines.size()) return false;
            String line = lines.get(index).trim();
            if (!line.contains("-") || !line.contains("|")) return false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c != '|' && c != '-' && c != ':' && c != ' ') return false;
            }
            return lines.get(index - 1).contains("|");
        }

        private static List<String> cells(String row) {
            String line = row.trim();
            if (line.startsWith("|")) line = line.substring(1);
            if (line.endsWith("|")) line = line.substring(0, line.length() - 1);
            List<String> cells = new ArrayList<>();
            for (String cell : line.split("\\|", -1)) cells.add(cell.trim());
            return cells;
        }

        // ── Line shapes ─────────────────────────────────────────────────────────────────────────

        private static int headingLevel(String line) {
            int hashes = 0;
            while (hashes < line.length() && line.charAt(hashes) == '#') hashes++;
            boolean spaced = hashes < line.length() && line.charAt(hashes) == ' ';
            return hashes > 0 && hashes <= 6 && spaced ? hashes : 0;
        }

        /** The fence's characters, or null — three or more backticks or tildes. */
        private static String fence(String line) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) return "```";
            if (trimmed.startsWith("~~~")) return "~~~";
            return null;
        }

        private static boolean isRule(String line) {
            String bare = line.trim().replace(" ", "");
            if (bare.length() < 3) return false;
            char first = bare.charAt(0);
            if (first != '-' && first != '*' && first != '_') return false;
            for (int i = 0; i < bare.length(); i++) {
                if (bare.charAt(i) != first) return false;
            }
            return true;
        }

        private static boolean indented(String line) {
            return line.startsWith("    ") && !line.isBlank();
        }

        private static int leading(String line) {
            int spaces = 0;
            while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
            return spaces;
        }

        /** Where an item's content starts, or {@code -1} when the line is not a list item. */
        private static int markerAt(String line) {
            int bullet = bulletAt(line);
            return bullet >= 0 ? bullet : orderedAt(line);
        }

        private static int bulletAt(String line) {
            int spaces = leading(line);
            if (spaces + 1 >= line.length()) return -1;
            char marker = line.charAt(spaces);
            // A RULE IS NOT A BULLET, and `- - -` is both by the letter of it. The rule test wins,
            // because a line of nothing but markers is not an item with content.
            if (isRule(line)) return -1;
            boolean isMarker = marker == '-' || marker == '*' || marker == '+';
            return isMarker && line.charAt(spaces + 1) == ' ' ? spaces + 2 : -1;
        }

        private static int orderedAt(String line) {
            int spaces = leading(line);
            int digits = spaces;
            while (digits < line.length() && Character.isDigit(line.charAt(digits))) digits++;
            if (digits == spaces || digits + 1 >= line.length()) return -1;
            char after = line.charAt(digits);
            boolean punctuated = after == '.' || after == ')';
            return punctuated && line.charAt(digits + 1) == ' ' ? digits + 2 : -1;
        }
    }

    /** Escapes the three characters that would otherwise be read as markup. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
