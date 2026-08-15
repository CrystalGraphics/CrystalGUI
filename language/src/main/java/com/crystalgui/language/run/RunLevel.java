package com.crystalgui.language.run;

/**
 * What kind of line a script produced — <b>not</b> a diagnostic severity.
 *
 * <h3>Why this is not {@code DiagnosticSeverity}</h3>
 *
 * <p>A diagnostic is a statement about <em>source</em>, carries a range, and is answered by re-analysing
 * the file. A console line is an <em>event</em>: it happened, it has a stack rather than a range, and no
 * amount of re-analysis will produce it again. Reusing the severity enum would put console lines one
 * short step from the Problems panel, which M9.5 decided against on exactly that distinction.</p>
 *
 * <p>Three levels rather than the five a logging framework would offer. The two that matter are the two
 * streams every language already has — ordinary output and error output — and {@link #WARN} exists
 * because {@code console.warn} does and a JS author will reach for it. Anything finer is a filter the
 * script can implement itself, in its own text.</p>
 */
public enum RunLevel {

    /** {@code System.out}, {@code console.log}, {@code print}. */
    OUT,

    /** {@code console.warn}. Nothing routes here by accident — a language has to mean it. */
    WARN,

    /** {@code System.err}, {@code console.error}, and an uncaught throw's stack. */
    ERROR
}
