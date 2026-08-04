package com.crystalgui.text.diagnostic;

/**
 * How bad a {@link Diagnostic} is — the Language Server Protocol's four levels, in its order.
 *
 * <p>Ported rather than invented. LSP's set is what every editor, every language server and every linter
 * already agrees on, so an adapter for a new source is a mapping onto these four rather than a negotiation.
 * The ordinal order is deliberate and load-bearing: <b>most severe first</b>, so a natural sort ranks a
 * mixed set correctly and "the worst thing in this file" is {@code values()[0]}-ward rather than a lookup
 * table.</p>
 *
 * <h3>Why four and not two</h3>
 *
 * <p>Two would cover a compiler and nothing else. {@link #INFO} is where a shader's "this uniform is
 * declared but never read" belongs — true, worth surfacing, and not a problem. {@link #HINT} is the level
 * an editor renders without a squiggle at all (VS Code shows it only as a lightbulb), which is what keeps a
 * style suggestion from looking like a compile error.</p>
 */
public enum DiagnosticSeverity {

    /** The artifact does not compile or does not run. */
    ERROR,

    /** It works, but something is probably wrong. */
    WARNING,

    /** True and worth saying; not a defect. */
    INFORMATION,

    /** A suggestion. Deliberately the level that need not be painted in the text at all. */
    HINT;

    /** Whether this outranks {@code other} — i.e. sorts earlier. Named rather than left to
     * {@code compareTo} because "greater severity" and "smaller ordinal" read as opposites. */
    public boolean isWorseThan(DiagnosticSeverity other) {
        return ordinal() < other.ordinal();
    }

    /** The worse of two, either of which may be null. Null is treated as "nothing to report". */
    public static DiagnosticSeverity worst(DiagnosticSeverity a, DiagnosticSeverity b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isWorseThan(b) ? a : b;
    }
}
