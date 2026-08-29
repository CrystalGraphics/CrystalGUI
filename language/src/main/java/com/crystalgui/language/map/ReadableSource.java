package com.crystalgui.language.map;

import com.crystalgui.text.Change;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Rewrites a <b>source file</b> out of the runtime namespace and into the readable one.
 *
 * <h3>Why this direction, and only this direction</h3>
 *
 * <p>{@link MappingSet} is emphatic that runtime → readable is a <em>function</em> and readable →
 * runtime is not: {@code func_147439_a} names exactly one method in the whole game, while
 * {@code getBlock} names four. That asymmetry is the whole licence for what happens here. A source file
 * gives us no receiver type — {@code plr.field_71075_bZ} is a name and a dot, and nothing in the text
 * says what {@code plr} is — so a rename that needed an owner could not be made at all. In this
 * direction none is needed.</p>
 *
 * <p>Which is also why <b>only the unqualified tier is consulted</b>. An owner-keyed entry exists for
 * formats that carry owners, and in a genuinely obfuscated mapping {@code a} is a method on hundreds of
 * classes with a different readable name on each — so an owner-keyed table read without an owner is a
 * coin toss. {@link MappingSet#readableMethodAnywhere} is the tier whose format <em>guarantees</em> the
 * name is globally unique, and it is the only one a text scan is entitled to.</p>
 *
 * <p>The corollary is that a script's own identifiers are never at risk, which is what makes a blind pass
 * safe here where {@code MemberResolution} exists to stop one going the other way. The keys in this
 * direction are {@code func_*} and {@code field_*}; nobody writes those by accident, and the readable
 * names that <em>are</em> ordinary words — {@code add}, {@code run}, {@code get}, {@code close} — sit on
 * the value side, where they are produced and never matched against.</p>
 *
 * <h3>Edits, not a new document</h3>
 *
 * <p>The answer is a list of {@link Change}s over the text that was scanned rather than the rewritten
 * string, because that is what the editor can use: minimal edits map every tracked range, diagnostic,
 * fold and caret through {@code ChangeSet} instead of throwing them away, and they undo as one step.
 * {@link #readable} exists for callers with no document — a test, a headless tool — and is defined in
 * terms of the same edits so the two can never disagree.</p>
 *
 * <h3>Classes are deliberately not renamed</h3>
 *
 * <p>MCP's CSVs carry methods and fields and no class column at all — {@code McpCsvFormat} says so — so
 * on the one format that ships there is nothing to rename: a Minecraft type already reads as
 * {@code net.minecraft.server.MinecraftServer}, FML's deobfuscating transformer having done that half.
 * Supporting a format that did carry classes would mean recognising a qualified type name in text, which
 * is a parse rather than a scan, and it would be dead code today. {@link MappingSet#readableClass} is
 * where that belongs when something needs it.</p>
 */
public final class ReadableSource {

    private ReadableSource() {
    }

    /**
     * Whether {@code source} names anything {@code mappings} could make readable.
     *
     * <p>Stops at the first, because the only thing anyone asks this is whether to offer the command —
     * and the file that most wants it answers on its first line.</p>
     */
    public static boolean containsRuntimeNames(MappingSet mappings, String source) {
        return !scan(mappings, source, true).isEmpty();
    }

    /** Every rename, as edits over {@code source}, ascending and non-overlapping. */
    public static List<Change> rewrites(MappingSet mappings, String source) {
        return scan(mappings, source, false);
    }

    /** The rewritten text — {@link #rewrites} applied, for a caller with no document to edit. */
    public static String readable(MappingSet mappings, String source) {
        List<Change> edits = rewrites(mappings, source);
        if (edits.isEmpty()) return source;
        StringBuilder out = new StringBuilder(source.length());
        int at = 0;
        for (Change edit : edits) {
            out.append(source, at, edit.from()).append(edit.insert());
            at = edit.to();
        }
        return out.append(source, at, source.length()).toString();
    }

    /**
     * One pass, and the whole of the language knowledge in this class.
     *
     * <h3>What it refuses to touch, and why that is the only rule that has to be right</h3>
     *
     * <p>A string literal is <b>data</b>. {@code getDeclaredMethod("func_71203_ab")} names a member that
     * the runtime really does call that — the bytecode remapper rewrites references and not constants —
     * so renaming inside the quotes would take a working reflective call and break it. Everything else
     * here is a refinement; this is the part that must not be got wrong.</p>
     *
     * <p>A comment is the opposite case and is renamed like ordinary code: a comment quoting
     * {@code plr.func_71033_a(…)} is exactly the text this command exists to make readable. What a
     * comment does need is to be <em>recognised</em>, so that the apostrophe in {@code // don't} is not
     * read as opening a literal and silently swallowing every rename in the rest of the file.</p>
     *
     * <p><b>A quote that does not close on its own line was not opening a literal.</b> Neither language
     * permits a raw newline inside {@code '…'} or {@code "…"}, so this is the grammar rather than a
     * heuristic. What it costs to leave out is not the unterminated quote — that runs to the end of the
     * text and finds nothing either way — but the one that finds a MATCH several lines later: a regular
     * expression like {@code /['"]/} then pairs its apostrophe with the next ordinary string in the file,
     * and every name in between is read as sitting inside a literal. Nothing throws, and the command
     * truthfully reports however few names it managed.</p>
     *
     * <p>A template literal is the one construct that genuinely spans lines, so it runs to its
     * terminator; its {@code ${…}} holes are code and are scanned as such, which needs a stack because a
     * hole may contain another template.</p>
     */
    private static List<Change> scan(MappingSet mappings, String source, boolean firstOnly) {
        if (mappings == null || source == null || source.isEmpty() || mappings.isIdentity()) {
            return List.of();
        }
        List<Change> edits = new ArrayList<>();
        int n = source.length();

        // Where the comment we are inside ends, or -1. Inside one, quotes are not literals and braces are
        // not braces -- but identifiers are still identifiers.
        int commentEnd = -1;
        // Template-literal text: not code, so nothing is renamed and nothing is a literal delimiter.
        boolean inTemplate = false;
        // The brace depth each open ${…} hole will close at, innermost last.
        Deque<Integer> holes = new ArrayDeque<>();
        int braces = 0;

        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            boolean inComment = i < commentEnd;

            if (inTemplate) {
                if (c == '\\') {
                    i += 2;
                } else if (c == '`') {
                    inTemplate = false;
                    i++;
                } else if (c == '$' && i + 1 < n && source.charAt(i + 1) == '{') {
                    holes.push(braces);
                    braces++;
                    inTemplate = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }

            if (!inComment) {
                if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                    commentEnd = lineEnd(source, i);
                    i += 2;
                    continue;
                }
                if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                    int close = source.indexOf("*/", i + 2);
                    commentEnd = close < 0 ? n : close;
                    i += 2;
                    continue;
                }
                if (c == '`') {
                    inTemplate = true;
                    i++;
                    continue;
                }
                if (c == '"' || c == '\'') {
                    int after = quotedEnd(source, i, c);
                    // -1 means it never closed on its line, so it was not a quote at all.
                    i = after < 0 ? i + 1 : after;
                    continue;
                }
                if (c == '{') {
                    braces++;
                    i++;
                    continue;
                }
                if (c == '}') {
                    braces--;
                    if (!holes.isEmpty() && braces == holes.peek()) {
                        holes.pop();
                        inTemplate = true;
                    }
                    i++;
                    continue;
                }
            }

            if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(source.charAt(end))) end++;
                String name = source.substring(i, end);
                String readable = readableOf(mappings, name);
                if (readable != null) {
                    edits.add(new Change(i, end, readable));
                    if (firstOnly) return edits;
                }
                i = end;
                continue;
            }
            i++;
        }
        return edits;
    }

    /** The readable spelling of a runtime member name, or null when nothing maps it. */
    private static String readableOf(MappingSet mappings, String name) {
        // METHOD FIRST, THEN FIELD -- the same order and the same reasoning as ReadableSymbols: a name is
        // one or the other and never both, so a kind vocabulary would be a thing to keep in step for no
        // gain.
        String readable = mappings.readableMethodAnywhere(name);
        if (readable.equals(name)) readable = mappings.readableFieldAnywhere(name);
        return readable.equals(name) ? null : readable;
    }

    /** The offset just past the closing quote, or -1 when the line ends first. */
    private static int quotedEnd(String source, int open, char quote) {
        int n = source.length();
        for (int i = open + 1; i < n; i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i + 1;
            } else if (c == '\n' || c == '\r') {
                return -1;
            }
        }
        return -1;
    }

    /** The offset of the line's terminator, or the end of the text. */
    private static int lineEnd(String source, int from) {
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\n' || c == '\r') return i;
        }
        return source.length();
    }
}
