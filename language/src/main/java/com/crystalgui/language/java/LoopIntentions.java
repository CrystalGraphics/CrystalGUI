package com.crystalgui.language.java;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * "Convert to enhanced for" — an index loop whose index is only ever used to fetch the element.
 *
 * <h3>The condition is not "it is a counted loop", it is "the index is invisible afterwards"</h3>
 *
 * <p>{@code for (int i = 0; i < xs.length; i++)} converts only when <b>every</b> use of {@code i} inside the
 * body is {@code xs[i]} — or, for a {@code List}, {@code xs.get(i)}. One use of {@code i} for anything else
 * (an arithmetic, a nested index, a call taking it) and the enhanced form cannot express the loop at all,
 * because it has no index to offer. That single check is what makes this safe; without it the conversion
 * produces code that does not compile and looks like it should.</p>
 *
 * <p><b>And {@code xs[i]} on the left of an {@code =} is not a fetch at all</b> — see
 * {@link #writtenThrough}, which is the one shape here that compiled afterwards and meant something
 * else.</p>
 *
 * <h3>And the sequence has to be repeatable</h3>
 *
 * <p>{@code for (int i = 0; i < list().size(); i++)} calls {@code list()} every iteration, and the enhanced
 * form calls it once. That is usually what the author wanted and is not the same program, so the sequence
 * must be a plain name or a field — something with no side effect and one value.</p>
 *
 * <p>Three text ranges at most: the header, and each fetch replaced by the new variable. The body's own
 * formatting and comments are untouched.</p>
 */
final class LoopIntentions {

    static final String ENHANCED_FOR = "java.intention.enhancedFor";

    private LoopIntentions() {
    }

    static List<Correction> all() {
        return List.of(new ConvertToEnhancedFor());
    }

    private static final class ConvertToEnhancedFor implements Correction {

        @Override public String id() {
            return ENHANCED_FOR;
        }

        @Override public int[] problems() {
            return Correction.NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            ForStatement loop = context.at(ForStatement.class, candidate -> triggered(context, candidate));
            if (loop == null) return;
            Counted counted = countedShapeOf(loop);
            if (counted == null) return;

            List<Expression> fetches = fetchesOf(loop, counted);
            if (fetches == null || fetches.isEmpty()) return;

            ITypeBinding element = fetches.get(0).resolveTypeBinding();
            ImportPlan imports = context.importPlan();
            String written = TypeNames.writtenName(element, imports, loop);
            if (written == null) return;

            String source = context.source();
            // THE INDEX IS NOT TAKEN -- the conversion is what deletes it. Leaving it in the set made the
            // derived name collide with the very declaration being removed, so every `int` loop over an
            // `int[]` produced `for (int i1 : xs)` beside no `i` at all.
            Set<String> taken = Names.declaredIn(Scopes.enclosingMethodOrRoot(loop));
            taken.remove(counted.index.getName());
            String name = Names.derive(null, element, taken);
            String sequence = source.substring(counted.sequence.getStartPosition(),
                    counted.sequence.getStartPosition() + counted.sequence.getLength());

            List<Change> changes = new ArrayList<>();
            changes.add(new Change(loop.getStartPosition(),
                    loop.getBody().getStartPosition(),
                    "for (" + written + " " + name + " : " + sequence + ") "));
            for (Expression fetch : fetches) {
                changes.add(new Change(fetch.getStartPosition(),
                        fetch.getStartPosition() + fetch.getLength(), name));
            }

            ChangeSet edit = context.changeSet(changes, imports);
            if (edit == null) return;
            out.add(context.preferredIntention(ENHANCED_FOR, "Convert to enhanced for",
                    "Replaces the index with the element it was only ever used to fetch.", edit));
        }

        private static boolean triggered(FixContext context, ForStatement loop) {
            if (loop.getBody() == null) return false;
            Counted counted = countedShapeOf(loop);
            if (counted == null) return false;
            List<Expression> fetches = fetchesOf(loop, counted);
            return fetches != null && !fetches.isEmpty()
                    && context.touches(loop.getStartPosition(), loop.getBody().getStartPosition());
        }
    }

    // ── The shape ───────────────────────────────────────────────────────────────────────────────

    /** The parts of {@code for (int i = 0; i < seq.length; i++)} once it has been recognised. */
    private static final class Counted {
        final IVariableBinding index;
        final Expression sequence;
        final boolean array;

        Counted(IVariableBinding index, Expression sequence, boolean array) {
            this.index = index;
            this.sequence = sequence;
            this.array = array;
        }
    }

    /**
     * Whether this loop counts from zero to the length of one sequence, and what those are.
     *
     * <p>Every part is checked because every part can differ: an initialiser that is not zero, a condition
     * that is {@code <=}, a step that is not one, two variables in the header. Any of them and the enhanced
     * form is a different loop.</p>
     */
    private static Counted countedShapeOf(ForStatement loop) {
        if (loop.initializers().size() != 1 || loop.updaters().size() != 1) return null;
        if (!(loop.initializers().get(0) instanceof VariableDeclarationExpression)) return null;
        VariableDeclarationExpression declared = (VariableDeclarationExpression) loop.initializers().get(0);
        if (declared.fragments().size() != 1) return null;
        VariableDeclarationFragment fragment = (VariableDeclarationFragment) declared.fragments().get(0);
        if (!isZero(fragment.getInitializer())) return null;
        IVariableBinding index = fragment.resolveBinding();
        if (index == null) return null;

        if (!stepsByOne(loop.updaters().get(0), index)) return null;
        if (!(loop.getExpression() instanceof InfixExpression)) return null;
        InfixExpression condition = (InfixExpression) loop.getExpression();
        if (condition.getOperator() != InfixExpression.Operator.LESS) return null;
        if (!isNamed(condition.getLeftOperand(), index)) return null;

        Expression bound = condition.getRightOperand();
        if (bound instanceof QualifiedName
                && "length".equals(((QualifiedName) bound).getName().getIdentifier())) {
            Expression sequence = ((QualifiedName) bound).getQualifier();
            return repeatable(sequence) ? new Counted(index, sequence, true) : null;
        }
        if (bound instanceof MethodInvocation) {
            MethodInvocation call = (MethodInvocation) bound;
            if (!"size".equals(call.getName().getIdentifier()) || !call.arguments().isEmpty()) return null;
            Expression sequence = call.getExpression();
            return sequence != null && repeatable(sequence)
                    ? new Counted(index, sequence, false) : null;
        }
        return null;
    }

    /**
     * Every {@code seq[i]} or {@code seq.get(i)} in the body — or <b>null when the index is used for
     * anything else</b>, which is the whole safety condition.
     */
    private static List<Expression> fetchesOf(ForStatement loop, Counted counted) {
        List<Expression> fetches = new ArrayList<>();
        boolean[] otherUse = {false};
        loop.getBody().accept(new ASTVisitor() {
            @Override public boolean visit(SimpleName name) {
                if (!isNamed(name, counted.index)) return true;
                Expression fetch = fetchAt(name, counted);
                if (fetch == null || writtenThrough(fetch)) {
                    otherUse[0] = true;
                    return false;
                }
                fetches.add(fetch);
                return true;
            }
        });
        return otherUse[0] ? null : fetches;
    }

    /** The {@code seq[i]} or {@code seq.get(i)} this use of the index is the index of, or null. */
    private static Expression fetchAt(SimpleName name, Counted counted) {
        ASTNode parent = name.getParent();
        if (counted.array && parent instanceof ArrayAccess
                && ((ArrayAccess) parent).getIndex() == name
                && sameSequence(((ArrayAccess) parent).getArray(), counted.sequence)) {
            return (Expression) parent;
        }
        if (!counted.array && parent instanceof MethodInvocation) {
            MethodInvocation call = (MethodInvocation) parent;
            if ("get".equals(call.getName().getIdentifier()) && call.arguments().size() == 1
                    && call.arguments().get(0) == name && call.getExpression() != null
                    && sameSequence(call.getExpression(), counted.sequence)) {
                return call;
            }
        }
        return null;
    }

    /**
     * Whether the loop <b>writes</b> through this fetch — {@code xs[i] = 0}, {@code xs[i]++},
     * {@code xs[i] += 1}.
     *
     * <p>A write is not a fetch, and reading it as one is the worst outcome this layer has: the element
     * variable replaces the array slot, so {@code xs[i] = 0} becomes {@code element = 0}, which assigns to
     * the loop's own copy. It compiles, it is offered as <em>preferred</em>, and the loop it produces does
     * nothing at all. The enhanced form has no way to say "store here" — that is exactly the index it does
     * not give back — so the only correct answer is to refuse the conversion.</p>
     */
    private static boolean writtenThrough(Expression fetch) {
        ASTNode parent = fetch.getParent();
        if (parent instanceof Assignment) return ((Assignment) parent).getLeftHandSide() == fetch;
        if (parent instanceof PostfixExpression) return ((PostfixExpression) parent).getOperand() == fetch;
        if (parent instanceof PrefixExpression) {
            PrefixExpression.Operator operator = ((PrefixExpression) parent).getOperator();
            return ((PrefixExpression) parent).getOperand() == fetch
                    && (operator == PrefixExpression.Operator.INCREMENT
                        || operator == PrefixExpression.Operator.DECREMENT);
        }
        return false;
    }

    // ── Small questions ─────────────────────────────────────────────────────────────────────────

    /** Whether this expression may be evaluated once instead of every iteration. */
    private static boolean repeatable(Expression sequence) {
        return sequence instanceof SimpleName || sequence instanceof QualifiedName;
    }

    private static boolean sameSequence(Expression used, Expression declared) {
        return used.toString().equals(declared.toString());
    }

    private static boolean isZero(Expression value) {
        return value != null && "0".equals(value.toString());
    }

    private static boolean isNamed(Expression candidate, IVariableBinding variable) {
        if (!(candidate instanceof SimpleName)) return false;
        IVariableBinding bound = ((SimpleName) candidate).resolveBinding() instanceof IVariableBinding
                ? (IVariableBinding) ((SimpleName) candidate).resolveBinding() : null;
        return bound != null && bound.isEqualTo(variable);
    }

    private static boolean stepsByOne(Object updater, IVariableBinding index) {
        if (updater instanceof PostfixExpression) {
            PostfixExpression step = (PostfixExpression) updater;
            return step.getOperator() == PostfixExpression.Operator.INCREMENT
                    && isNamed(step.getOperand(), index);
        }
        if (updater instanceof PrefixExpression) {
            PrefixExpression step = (PrefixExpression) updater;
            return step.getOperator() == PrefixExpression.Operator.INCREMENT
                    && isNamed(step.getOperand(), index);
        }
        return false;
    }

}
