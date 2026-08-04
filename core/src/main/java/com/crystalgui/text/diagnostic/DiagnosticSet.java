package com.crystalgui.text.diagnostic;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.text.TextPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Every {@link Diagnostic} currently known about one document, kept in document order.
 *
 * <h3>Replace-all, not incremental</h3>
 *
 * <p>{@link #setAll} is the primary mutator and {@link #add} exists mainly for building a set up before
 * installing it. That is how the producers actually behave: a compile either succeeds or returns the
 * complete list of what is wrong, and there is no such thing as "one error was fixed" arriving on its own.
 * An incremental API would have to answer which of the previous diagnostics survived, and nothing that
 * feeds this can answer that — the honest model is that each compile supersedes the last one wholesale.</p>
 *
 * <p>This is also why the set carries no identity per diagnostic. Two compiles of the same broken file
 * produce equal records, and {@code equals} on the list is what {@link #setAll} uses to stay quiet, so a
 * recompile that changes nothing emits no change and repaints nothing.</p>
 *
 * <h3>One set per document, and it is not the editor's</h3>
 *
 * <p>The same boundary the undo stack draws. Diagnostics describe the <em>document</em>, so two views onto
 * one file show the same problems, and a document with no view open still has them — which is what a
 * Problems panel listing errors in files you have not opened requires.</p>
 */
public final class DiagnosticSet {

    /** Fires whenever the contents change. Carries no value: a listener re-reads, so ten mutations in a
     * tick collapse to one repaint, exactly as {@code UITreeObserver.onStateDirty} does. */
    public final Signal.Action onChanged = new Signal.Action();

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }

    public int size() {
        return diagnostics.size();
    }

    public List<Diagnostic> all() {
        return Collections.unmodifiableList(diagnostics);
    }

    /**
     * Replaces the contents, sorted into document order.
     *
     * <p>No-ops when the new set equals the old one, so a recompile of an unchanged file does not repaint
     * every squiggle in it — the same equality guard {@code replaceOrPutCandidate} uses to make widget
     * geometry settle.</p>
     */
    public DiagnosticSet setAll(@Nullable Collection<Diagnostic> incoming) {
        List<Diagnostic> sorted = new ArrayList<>(incoming == null ? List.of() : incoming);
        sorted.removeIf(java.util.Objects::isNull);
        Collections.sort(sorted);
        if (sorted.equals(diagnostics)) return this;
        diagnostics.clear();
        diagnostics.addAll(sorted);
        onChanged.emit();
        return this;
    }

    public DiagnosticSet add(Diagnostic diagnostic) {
        if (diagnostic == null) return this;
        List<Diagnostic> next = new ArrayList<>(diagnostics);
        next.add(diagnostic);
        return setAll(next);
    }

    public DiagnosticSet clear() {
        return setAll(List.of());
    }

    // ── Queries ─────────────────────────────────────────────────────────────────────────────────

    /** Everything covering {@code row}. What a per-line renderer asks once per visible line. */
    public List<Diagnostic> forRow(int row) {
        List<Diagnostic> hits = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.touchesRow(row)) hits.add(diagnostic);
        }
        return hits;
    }

    /** The worst severity on {@code row}, or null when the row is clean — what decides a squiggle's colour
     * and a gutter icon when several problems overlap. */
    @Nullable
    public DiagnosticSeverity worstOnRow(int row) {
        DiagnosticSeverity worst = null;
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.touchesRow(row)) worst = DiagnosticSeverity.worst(worst, diagnostic.severity());
        }
        return worst;
    }

    public int count(DiagnosticSeverity severity) {
        int count = 0;
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() == severity) count++;
        }
        return count;
    }

    /** The worst severity anywhere, or null when there is nothing to report. Drives the inspection
     * widget's overall state — a green tick versus a red cross. */
    @Nullable
    public DiagnosticSeverity worst() {
        DiagnosticSeverity worst = null;
        for (Diagnostic diagnostic : diagnostics) {
            worst = DiagnosticSeverity.worst(worst, diagnostic.severity());
        }
        return worst;
    }

    // ── Navigation ──────────────────────────────────────────────────────────────────────────────

    /**
     * The first diagnostic starting strictly after {@code from}, wrapping to the first when there is none.
     *
     * <p>Wrapping rather than stopping is IntelliJ's F2 and VS Code's F8: the navigation is a cycle, so
     * repeatedly pressing it walks every problem and returns, and the last one is not a dead end. Strictly
     * after, so pressing it while already sitting on a problem moves.</p>
     */
    @Nullable
    public Diagnostic nextFrom(TextPoint from) {
        if (diagnostics.isEmpty()) return null;
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.start().compareTo(from) > 0) return diagnostic;
        }
        return diagnostics.get(0);
    }

    /** The mirror of {@link #nextFrom}, wrapping to the last. */
    @Nullable
    public Diagnostic previousFrom(TextPoint from) {
        if (diagnostics.isEmpty()) return null;
        for (int i = diagnostics.size() - 1; i >= 0; i--) {
            if (diagnostics.get(i).start().compareTo(from) < 0) return diagnostics.get(i);
        }
        return diagnostics.get(diagnostics.size() - 1);
    }
}
