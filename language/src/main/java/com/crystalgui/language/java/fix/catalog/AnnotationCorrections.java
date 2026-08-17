package com.crystalgui.language.java.fix.catalog;

import com.crystalgui.language.java.Correction;
import com.crystalgui.language.java.FixContext;
import com.crystalgui.language.java.ProblemSpans;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.List;

/**
 * Annotations that are on a declaration and should not be.
 *
 * <h3>Removal only, and that is not half a family</h3>
 *
 * <p>The catalogue (§6) pairs each of these with its opposite — add {@code @Override}, add
 * {@code @Deprecated} — behind one "insert this on its own line, indented to match" helper. Neither
 * insertion has a problem to hang off: {@code MissingOverrideAnnotation} is deliberately <b>off</b>
 * (§18.6 — an override is a relationship, not a defect), and the {@code @Deprecated} family fires only
 * where a Javadoc {@code @deprecated} tag disagrees with the annotation, which is a documentation
 * mismatch rather than something to silently write over. A helper with no consumer is a helper written
 * against a guess, so it is not written.</p>
 *
 * <p>What remains is genuinely a deletion family, and the measurement says it earns its place:
 * {@code MethodMustOverrideOrImplement} is the <b>most frequent unanswered problem in the repository</b>
 * — 410 occurrences across 175 files with an empty classpath, still 15 across 5 once types resolve — and
 * the fix is one node removed. @see CoverageProbeTest</p>
 *
 * <h3>Why removal is the right answer and not a shrug</h3>
 *
 * <p>An {@code @Override} that overrides nothing has three causes: the name is misspelled, the supertype
 * changed under it, or the annotation was always wrong. Only the last is repairable from this file — the
 * first is what "did you mean" is for and the second is not a source edit at all — so both references
 * offer exactly this one action and leave the diagnosis to the reader. Eclipse titles it "Remove
 * '@Override' annotation"; IntelliJ, "Remove annotation".</p>
 */
final class AnnotationCorrections {

    static final String REMOVE_OVERRIDE = "java.annotation.removeOverride";
    static final String REMOVE_SAFE_VARARGS = "java.annotation.removeSafeVarargs";

    private static final String OVERRIDE = "Override";
    private static final String SAFE_VARARGS = "SafeVarargs";

    private static final int[] OVERRIDE_PROBLEMS =
            {IProblem.MethodMustOverride, IProblem.MethodMustOverrideOrImplement};

    // BOTH SafeVarargs IDS FIRE WHERE IT IS WRONGLY APPLIED, never where it is missing — the constant
    // names read the other way round and the catalogue notes the trap. A fixed-arity method has no
    // varargs array to be unsafe about, and a non-final instance method can be overridden by one that
    // is, so the annotation's promise is not the author's to make.
    private static final int[] SAFE_VARARGS_PROBLEMS =
            {IProblem.SafeVarargsOnFixedArityMethod, IProblem.SafeVarargsOnNonFinalInstanceMethod};

    private AnnotationCorrections() {
    }

    static List<Correction> all() {
        return List.of(
                new RemoveAnnotation(REMOVE_OVERRIDE, OVERRIDE, OVERRIDE_PROBLEMS),
                new RemoveAnnotation(REMOVE_SAFE_VARARGS, SAFE_VARARGS, SAFE_VARARGS_PROBLEMS));
    }

    /**
     * The span this problem should <b>mark</b> — the annotation, not the method name ECJ reports.
     *
     * <p>Read by {@link ProblemSpans}, which is the only caller and is also what makes the fix reachable
     * from here. ECJ marks the method name in all four cases, which is the same misdirection
     * {@code ParameterMismatch} already gets corrected for: the method is not what is wrong and is not
     * what anybody can change — the annotation above it is, and it is the only part of the declaration
     * this family will ever touch. IntelliJ and javac both point at the annotation.</p>
     *
     * <p>Found from the declaration's modifier list rather than by scanning for the text, so
     * {@code @java.lang.Override} is the same annotation and a mention of the word in a comment or a
     * string is not.</p>
     *
     * @return the annotation's own range, or null when this is not an annotation problem
     */
    static int[] markedSpan(CompilationUnit unit, IProblem problem, int[] reported) {
        String wanted = annotationFor(problem.getID());
        if (wanted == null) return null;
        if (reported[0] < 0 || reported[1] <= reported[0]) return null;
        ASTNode node = NodeFinder.perform(unit, reported[0], reported[1] - reported[0]);
        while (node != null && !(node instanceof BodyDeclaration)) node = node.getParent();
        if (node == null) return null;
        Annotation found = annotationOn((BodyDeclaration) node, wanted);
        if (found == null || found.getStartPosition() < 0 || found.getLength() <= 0) return null;
        return new int[] {found.getStartPosition(), found.getStartPosition() + found.getLength()};
    }

    /** Which annotation a problem id is about — the one table both the fix and the mark read. */
    private static String annotationFor(int problemId) {
        for (int each : OVERRIDE_PROBLEMS) {
            if (each == problemId) return OVERRIDE;
        }
        for (int each : SAFE_VARARGS_PROBLEMS) {
            if (each == problemId) return SAFE_VARARGS;
        }
        return null;
    }

    /**
     * "Remove '@Override'" / "Remove '@SafeVarargs'" — one class, because the two differ only in a name.
     *
     * <p>The annotation is found on the declaration's own modifier list rather than by the problem's
     * range, which points at the method name in both cases. Matching on the <b>last segment</b> of the
     * type name is what makes {@code @java.lang.Override} work, which is legal, rare, and exactly the
     * spelling a simple-name comparison silently misses.</p>
     */
    private static final class RemoveAnnotation implements Correction {

        private final String id;
        private final String annotation;
        private final int[] problems;

        RemoveAnnotation(String id, String annotation, int[] problems) {
            this.id = id;
            this.annotation = annotation;
            this.problems = problems;
        }

        @Override public String id() {
            return id;
        }

        @Override public int[] problems() {
            return problems.clone();
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            BodyDeclaration declaration = context.enclosing(problem, BodyDeclaration.class);
            if (declaration == null) return;
            Annotation found = annotationOn(declaration, annotation);
            if (found == null) return;

            ASTRewrite rewrite = context.rewrite();
            rewrite.remove(found, null);
            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;
            out.add(context.preferredFix(id, "Remove '@" + annotation + "'", edit));
        }
    }

    /** The annotation of this name on this declaration, or null. */
    private static Annotation annotationOn(BodyDeclaration declaration, String simpleName) {
        for (Object modifier : declaration.modifiers()) {
            if (!(modifier instanceof IExtendedModifier)) continue;
            if (!((IExtendedModifier) modifier).isAnnotation()) continue;
            Annotation each = (Annotation) modifier;
            if (simpleName.equals(lastSegment(each.getTypeName()))) return each;
        }
        return null;
    }

    private static String lastSegment(Name name) {
        return name instanceof QualifiedName
                ? ((QualifiedName) name).getName().getIdentifier() : name.getFullyQualifiedName();
    }
}
