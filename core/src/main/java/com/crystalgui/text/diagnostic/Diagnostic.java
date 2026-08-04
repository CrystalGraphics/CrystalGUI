package com.crystalgui.text.diagnostic;

import com.crystalgui.text.TextPoint;

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

    public Diagnostic {
        if (start == null || end == null) {
            throw new IllegalArgumentException("A diagnostic needs both ends of its range");
        }
        if (severity == null) throw new IllegalArgumentException("A diagnostic needs a severity");
        if (message == null) message = "";
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
