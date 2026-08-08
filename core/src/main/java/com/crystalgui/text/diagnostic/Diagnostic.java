package com.crystalgui.text.diagnostic;

import com.crystalgui.text.TextPoint;

import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * One problem reported about a document — the Language Server Protocol's {@code Diagnostic}, ported.
 *
 * <h3>Positions are row/column, not offsets, and that is the whole design decision here</h3>
 *
 * <p>Everything else in {@code com.crystalgui.text} addresses the document by <b>flat offset</b>
 * ({@link com.crystalgui.text.Selection} is a pair of ints), so storing a diagnostic the same way looks
 * like the consistent choice. It is the wrong one, for two reasons that both bite in practice.</p>
 *
 * <p><b>Every producer speaks row/column.</b> A GLSL driver says {@code 0(278) : error C1503}; Janino and
 * Nashorn throw with a line number; a stylesheet warning knows its rule's line. Converting to an offset at
 * the boundary means resolving against a buffer — and the buffer that matters is the one that was
 * <em>compiled</em>, which by the time the answer arrives may not be the one on screen. An offset computed
 * against the wrong snapshot is silently wrong and points at innocent text; a row that no longer exists is
 * obviously wrong and can be discarded.</p>
 *
 * <p><b>Diagnostics are inherently stale.</b> They describe the document as it was when something last
 * compiled it, and they stay on screen while you keep typing. A row survives edits on other rows, which is
 * the common case; an offset does not survive an edit <em>anywhere earlier in the file</em>. Storing rows
 * means an unrelated edit at the top does not slide every squiggle in the file sideways.</p>
 *
 * <p>The conversion to offsets happens at render time against the live buffer, where being one edit out of
 * date is visible and recoverable rather than baked in.</p>
 *
 * <h3>{@code source} and {@code code} are separate, and both optional</h3>
 *
 * <p>LSP's split. {@code source} is who is complaining ({@code "glsl"}, {@code "janino"}), {@code code} is
 * the machine-readable identity of the complaint ({@code "C1503"}). A Problems panel groups by the first
 * and a future "suppress this warning" acts on the second; glued into the message, neither is available
 * without re-parsing prose.</p>
 *
 * @param start    first character covered, inclusive
 * @param end      last character covered, exclusive; equal to {@code start} for a zero-width marker
 * @param severity how bad it is
 * @param message  what to show a human
 * @param source   who reported it, or null
 * @param code     the reporter's identifier for this class of problem, or null
 */
public record Diagnostic(TextPoint start, TextPoint end, DiagnosticSeverity severity, String message,
                         @Nullable String source, @Nullable String code) implements Comparable<Diagnostic> {

    /**
     * Any run of whitespace, newlines included.
     *
     * <p><b>Two backslashes, and Java 15 is why the one-backslash version compiled.</b> {@code "\s"} is a
     * string escape for a space since Java 15, so {@code "\s+"} is not the regex it looks like — it is the
     * two characters {@code " +"}, matching runs of <em>spaces only</em>. A trailing newline then survived
     * the collapse and was removed by the {@code trim()} beside it, which made the bug look fixed while an
     * interior break still shaped a second line.</p>
     */
    private static final Pattern COLLAPSE_WHITESPACE = Pattern.compile("\\s+");

    /**
     * The range for a diagnostic that is not about a place in text.
     *
     * <p>A shader graph's compiler reports about a <b>node</b>: there is no row for it to point at, and
     * the nearest lie — {@code (0,0)} — renders as a confident "line 1" over a document that may have no
     * lines at all. A negative row is out of band for every real producer and lets a panel say "no line"
     * instead of naming one.</p>
     *
     * <p>Not a separate diagnostic type, because everything else about one still applies: severity, a
     * message, who reported it, and its code. Only the position is absent.</p>
     */
    public static final TextPoint NO_POSITION = new TextPoint(-1, -1);

    /** Whether this points at somewhere in the text. @see #NO_POSITION */
    public boolean hasPosition() {
        return start.row() >= 0;
    }

    public Diagnostic {
        if (start == null || end == null) {
            throw new IllegalArgumentException("A diagnostic needs both ends of its range");
        }
        if (severity == null) throw new IllegalArgumentException("A diagnostic needs a severity");
        if (message == null) message = "";
        // ONE LINE, ALWAYS. A diagnostic is rendered as a row in a list, a line in a tooltip, or a hover
        // over a squiggle — every consumer draws it on one line, and none of them asked for the newline a
        // producer happened to include.
        //
        // The producer that forced this is a GLSL driver: an info log is newline-TERMINATED, so a
        // message ending `undefined variable "cg_Normal"` plus a trailing break shaped as two lines in a
        // sixteen-pixel row. `white-space: nowrap` does not help, because it stops text WRAPPING and says
        // nothing about an explicit break. The box came out twice as tall as its row, was centred, and
        // overhung it by half a line each way.
        // The glyphs then drew from the box top, a few pixels above where the icon beside them sat, and it
        // read as the row being misaligned rather than as the message being two lines.
        //
        // Collapsed rather than rejected: the text is still the whole message, and a producer that formats
        // across lines is being reasonable about a medium this one does not have.
        message = COLLAPSE_WHITESPACE.matcher(message).replaceAll(" ").trim();
        // Normalised rather than rejected: a producer that reports a backwards range has a bug, but
        // refusing the whole diagnostic would hide the problem it was trying to tell us about.
        if (end.compareTo(start) < 0) {
            TextPoint swap = start;
            start = end;
            end = swap;
        }
    }

    /** A whole-line diagnostic, which is what a compiler that reports only a line number produces. The end
     * column is {@link Integer#MAX_VALUE} so it clamps to the real line length at render time — the length
     * is a property of the buffer, and this type deliberately does not have one. */
    public static Diagnostic onRow(int row, DiagnosticSeverity severity, String message) {
        return new Diagnostic(new TextPoint(row, 0), new TextPoint(row, Integer.MAX_VALUE),
                severity, message, null, null);
    }

    public static Diagnostic error(TextPoint start, TextPoint end, String message) {
        return new Diagnostic(start, end, DiagnosticSeverity.ERROR, message, null, null);
    }

    public static Diagnostic warning(TextPoint start, TextPoint end, String message) {
        return new Diagnostic(start, end, DiagnosticSeverity.WARNING, message, null, null);
    }

    public Diagnostic withSource(@Nullable String source, @Nullable String code) {
        return new Diagnostic(start, end, severity, message, source, code);
    }

    /** Whether this covers any part of {@code row} — the query a per-line renderer makes. */
    public boolean touchesRow(int row) {
        return row >= start.row() && row <= end.row();
    }

    public boolean isSingleRow() {
        return start.row() == end.row();
    }

    /** Position first, then severity. Position because a Problems panel and next/previous navigation both
     * read in document order; severity as the tiebreak so two problems on one spot show the worse first. */
    @Override
    public int compareTo(Diagnostic other) {
        int byStart = start.compareTo(other.start);
        if (byStart != 0) return byStart;
        return Integer.compare(severity.ordinal(), other.severity.ordinal());
    }
}
