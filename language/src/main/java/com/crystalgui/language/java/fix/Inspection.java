package com.crystalgui.language.java.fix;

import com.crystalgui.language.java.fix.catalog.LambdaCorrections;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.DiagnosticTag;

import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.List;
import java.util.Set;

/**
 * One <b>inspection</b> — something this engine reports that the compiler does not.
 *
 * <h3>Why the engine reports anything of its own</h3>
 *
 * <p>A compiler reports what is <em>wrong</em>. An inspection reports what could be <em>better</em>, and
 * every reference puts the two in one list: IntelliJ's Problems panel shows "Anonymous new
 * Comparator&lt;Message&gt;() can be replaced with lambda" directly beside "Class 'Inner' is never used",
 * both with a warning mark, though one is a refactor by nature and the other a defect. Ours shipped as an
 * intention with no diagnostic at all — correct, and findable only by putting the caret on exactly the
 * right nine characters.</p>
 *
 * <h3>A list, while there is one entry, and that is the point</h3>
 *
 * <p>The single inspection was called by name from inside the analyser, its message built in the same
 * method that converts offsets to rows. Written that way, the second inspection is added by copying that
 * block — and then the message, the severity and the tag are decided twice. It is a seam worth having
 * before it is worth having.</p>
 *
 * <h3>Findings are OFFSETS; the analyser owns the conversion</h3>
 *
 * <p>Deliberately, and it is the invariant the whole diagnostic layer rests on: a row and column mean
 * something only against the document the analysis actually saw. An inspection therefore says what it
 * found and where in the source it is, and never builds a {@code Diagnostic} — so there is exactly one
 * place that turns an offset into a position, and no way for an inspection to answer against a document
 * that has since been edited.</p>
 */
public interface Inspection {

    /**
     * Stable, <b>non-numeric</b>, never displayed — {@code "cgui.lambda.fromAnonymous"}.
     *
     * <p>Non-numeric is what keeps these apart from ECJ's: a problem id is rendered as its integer, so any
     * code with a letter in it is unambiguously ours and no id can collide with one. It also leaves the
     * corrections' routing untouched, since they key on {@code IProblem} ids and never see these.</p>
     */
    String code();

    /** Everything this finds in {@code unit}, as offsets into {@code source}. */
    List<Finding> reportIn(CompilationUnit unit, String source);

    /** One thing found: where it is, what to say about it, and how it should be drawn. */
    record Finding(int from, int to, String message, DiagnosticSeverity severity, Set<DiagnosticTag> tags) {
    }

    /**
     * Every inspection this engine runs.
     *
     * <p>A flat list rather than a registry class, unlike {@link JavaQuickFixes}: a correction is indexed
     * by the problem ids it answers for, and an inspection answers for nothing — it is asked about the
     * whole unit, so there is nothing to index it on.</p>
     */
    static List<Inspection> all() {
        return List.of(LambdaCorrections.anonymousCanBeLambda());
    }
}
