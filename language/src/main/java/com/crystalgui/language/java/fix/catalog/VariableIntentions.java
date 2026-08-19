package com.crystalgui.language.java.fix.catalog;

import com.crystalgui.language.java.fix.Correction;
import com.crystalgui.language.java.fix.FixContext;
import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.crystalgui.language.java.fix.ast.Precedence;
import com.crystalgui.language.java.fix.ast.Scopes;
import com.crystalgui.language.java.fix.ast.SideEffects;
import com.crystalgui.language.java.fix.edit.ImportPlan;
import com.crystalgui.language.java.fix.edit.Indent;
import com.crystalgui.language.java.fix.edit.Names;
import com.crystalgui.language.java.fix.edit.TypeNames;

/**
 * <b>Introduce variable ↔ inline variable</b> — the most-reached-for pair in any IDE, and a pair for the
 * same reason the others are: you use one having just used the other.
 *
 * <h3>Inlining is the dangerous half, and in two separate ways</h3>
 *
 * <p>Introducing a variable cannot change what a program does — the expression is evaluated in the same
 * place, once, and read once. Inlining can change it twice over:</p>
 *
 * <ul>
 *   <li><b>It can duplicate work.</b> A local read three times, initialised from {@code compute()}, becomes
 *       three calls where there was one. Refused whenever the initialiser contains a call and there is more
 *       than one use — IntelliJ warns here and lets you continue, which a popup with no dialog cannot.</li>
 *   <li><b>It can rebind operators.</b> {@code int sum = a + b;} inlined into {@code sum * 2} gives
 *       {@code a + b * 2}, which compiles and is a different number. Anything that is not a single term is
 *       parenthesised on the way in.</li>
 * </ul>
 *
 * <p>And a variable that is <em>assigned</em> after its declaration is refused outright: its initialiser is
 * not its value, so substituting one for the other is simply wrong.</p>
 *
 * <h3>Within this file, which is not a limitation here</h3>
 *
 * <p>A local's uses are all in the method that declares it, so "find every use" is a walk of one node and
 * complete by construction. That is what makes inline a local-only intention and why §10 scopes it that
 * way: a field would need the whole program, which is the same wall every cross-file entry hits.</p>
 */
public final class VariableIntentions {

    static final String INTRODUCE = "java.intention.introduceVariable";
    static final String INLINE = "java.intention.inlineVariable";

    private VariableIntentions() {
    }

    public static List<Correction> all() {
        return List.of(new IntroduceVariable(), new InlineVariable());
    }

    // ── Introduce ───────────────────────────────────────────────────────────────────────────────

    /**
     * "Introduce variable" — the selected expression becomes a local, declared above the statement it is in.
     *
     * <p>Two ranges: an inserted declaration line and the expression replaced by the new name. The
     * expression's own text is what is moved, so its formatting and any comment inside it survive.</p>
     */
    private static final class IntroduceVariable implements Correction {

        @Override public String id() {
            return INTRODUCE;
        }

        @Override public int[] problems() {
            return Correction.NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            Expression expression = context.at(Expression.class, candidate -> extractable(context, candidate));
            if (expression == null) return;
            Statement statement = Scopes.enclosingStatement(expression);
            if (statement == null || !(statement.getParent() instanceof Block)) return;
            // NOTHING GOES ABOVE `super(…)` OR `this(…)`. An explicit constructor invocation must be the
            // first statement in its constructor, so a declaration inserted before one is not legal
            // wherever it would otherwise have been fine.
            if (statement instanceof SuperConstructorInvocation
                    || statement instanceof ConstructorInvocation) {
                return;
            }
            // NOT OUT THROUGH A LAMBDA, and not past a name the statement itself declares.
            //
            // Both are the same fault: the new declaration goes ABOVE the statement, so anything the
            // expression reads has to already exist there. A lambda with an EXPRESSION body has no
            // statement of its own, so the walk lands outside it and hoisting the expression takes it out
            // of the parameter's scope -- and out of the loop, so it is evaluated once instead of per
            // element even when it compiles. A `for` header declares its own variable, so extracting
            // `i < n` moves `i` above its declaration.
            //
            // A lambda with a BLOCK body needs neither check: the walk stops at a statement inside it,
            // which is already the right scope.
            if (crossesALambda(expression, statement)) return;
            if (readsSomethingDeclaredIn(expression, statement)) return;
            // AND NOT OUT OF A GUARD. `if (x != null && x.foo())` only evaluates `x.foo()` when the first
            // half held; hoisting it above the statement evaluates it always, which is what the guard was
            // written to prevent. Same for a ternary's branches. This is the one hazard here that produces
            // code compiling perfectly and throwing at runtime, so it is refused rather than warned about.
            if (guardedByShortCircuit(expression, statement)) return;

            ITypeBinding type = expression.resolveTypeBinding();
            ImportPlan imports = context.importPlan();
            String written = TypeNames.writtenName(type, imports, expression);
            if (written == null) return;

            String source = context.source();
            String name = freshName(expression, type, statement);
            String indent = Indent.at(source, statement.getStartPosition());
            String value = FixContext.text(expression, source);

            List<Change> changes = new ArrayList<>();
            changes.add(new Change(statement.getStartPosition(), statement.getStartPosition(),
                    written + " " + name + " = " + value + ";\n" + indent));
            changes.add(new Change(expression.getStartPosition(),
                    expression.getStartPosition() + expression.getLength(), name));

            ChangeSet edit = context.changeSet(changes, imports);
            if (edit == null) return;
            out.add(context.intention(INTRODUCE, "Introduce variable '" + name + "'",
                    "Moves this expression into a local declared just above, and uses the local here.",
                    edit));
        }

        /**
         * Whether this expression is worth naming.
         *
         * <p>Five refusals, and each is a shape where the result would be silly or would not compile: a
         * bare name is already a variable, a {@code void} call has no value to hold, an assignment is a
         * statement wearing an expression's clothes, a lambda's type is inferred from where it sits and
         * would have to be written out, and an expression that is <em>already</em> a declaration's whole
         * initialiser is a local somebody has just introduced.</p>
         */
        private static boolean extractable(FixContext context, Expression expression) {
            // A NAME CHAIN IS ALREADY A NAME. `System.out` is a QualifiedName, and introducing
            // `PrintStream printStream = System.out;` renames rather than names — the same argument that
            // refuses a bare SimpleName, one dot further along. The fixture found it: asked over a whole
            // line, `System.out.println(s.trim())` refuses (it is the statement), and the next candidate
            // in visit order was the receiver rather than the argument anybody meant.
            // JDT'S Expression IS WIDER THAN "A VALUE", and this list is what the corpus taught rather than
            // what anybody would have predicted. An ANNOTATION is an Expression (`Override override =
            // @Override;`), so is a `for` header's declaration list, so is an `instanceof` pattern. A
            // LAMBDA and a METHOD REFERENCE have no type of their own — they take one from where they sit,
            // which is the same refusal CreateCorrections makes about them as arguments.
            if (expression instanceof Name || expression instanceof Assignment
                    || expression instanceof LambdaExpression || expression instanceof MethodReference
                    || expression instanceof Annotation) {
                return false;
            }
            // `this` IS ALREADY A NAME, and the corpus showed what naming it again costs: extracting it
            // from `this.owner = …` produced `JobKey jobKey = this; jobKey.owner = …`, which turns a
            // constructor's assignment to its own blank final field into an assignment to a final field
            // through a reference. Two errors in a file that had none.
            if (expression instanceof ThisExpression) return false;
            if (expression instanceof FieldAccess
                    && ((FieldAccess) expression).getExpression() instanceof ThisExpression) {
                return false;
            }
            // NOTHING ON THE LEFT OF AN ASSIGNMENT. A target is a place, not a value -- naming any part of
            // one substitutes a copy for the thing being written to. The general form of the row above.
            if (withinAnAssignmentTarget(expression)) return false;
            // AND NOTHING THAT DECLARES A NAME. `other instanceof JobKey key` is an Expression whose value
            // is a boolean and whose effect is to bind `key` for the rest of the condition; hoisting it
            // into `boolean flag = …` leaves every later use of `key` unresolved.
            if (declaresAName(expression)) return false;
            // NOT EVERY Expression IS A VALUE, which the corpus made unmissable. JDT models a `for` header's
            // `int i = 0, n = size()` as a VariableDeclarationExpression and an `instanceof` pattern's
            // `Gradient gradient` as a Pattern, and both extend Expression — so extracting them produced
            // `int i1 = int i = 0, n = slots.size();`. They DECLARE rather than evaluate.
            if (expression instanceof VariableDeclarationExpression) return false;
            // By class name because the pattern types arrived in JDT 3.28 and this module compiles against
            // the floor band, where naming them would not resolve; a `instanceof PatternInstanceofExpression`
            // would fail to verify on band 8 at runtime rather than fail here.
            if (expression.getClass().getSimpleName().endsWith("Pattern")) return false;
            if (expression.getParent() instanceof VariableDeclarationFragment
                    && ((VariableDeclarationFragment) expression.getParent()).getInitializer() == expression) {
                return false;
            }
            if (expression.getParent() instanceof ExpressionStatement) return false;
            ITypeBinding type = expression.resolveTypeBinding();
            if (type == null || "void".equals(type.getName()) || type.isNullType()) return false;
            return context.touches(expression.getStartPosition(),
                    expression.getStartPosition() + expression.getLength());
        }

        /** Whether this expression is part of what an assignment writes <em>to</em>. */
        private static boolean withinAnAssignmentTarget(Expression expression) {
            for (ASTNode walk = expression; walk != null; walk = walk.getParent()) {
                if (walk.getParent() instanceof Assignment) {
                    return ((Assignment) walk.getParent()).getLeftHandSide() == walk;
                }
                if (walk instanceof Statement) return false;
            }
            return false;
        }

        /** Whether this expression binds a name — a pattern, or anything carrying a declaration. */
        private static boolean declaresAName(Expression expression) {
            boolean[] found = {false};
            expression.accept(new ASTVisitor() {
                @Override public void preVisit(ASTNode node) {
                    if (node instanceof SingleVariableDeclaration
                            || node instanceof VariableDeclarationFragment
                            || node.getClass().getSimpleName().endsWith("Pattern")) {
                        found[0] = true;
                    }
                }
            });
            return found[0];
        }

        /**
         * Whether anything between the expression and the statement only evaluates it conditionally.
         *
         * <p>The right operand of {@code &&} or {@code ||}, and either branch of a ternary. Hoisting out of
         * one of those turns "evaluated when the guard held" into "evaluated always" — which is precisely
         * what the guard exists to stop.</p>
         */
        private static boolean guardedByShortCircuit(Expression expression, Statement statement) {
            for (ASTNode walk = expression; walk != null && walk != statement; walk = walk.getParent()) {
                ASTNode parent = walk.getParent();
                if (parent instanceof InfixExpression) {
                    InfixExpression infix = (InfixExpression) parent;
                    boolean shortCircuit = infix.getOperator() == InfixExpression.Operator.CONDITIONAL_AND
                            || infix.getOperator() == InfixExpression.Operator.CONDITIONAL_OR;
                    if (shortCircuit && infix.getLeftOperand() != walk) return true;
                }
                if (parent instanceof ConditionalExpression
                        && ((ConditionalExpression) parent).getExpression() != walk) {
                    return true;
                }
            }
            return false;
        }

        /** Whether a lambda sits between the expression and the statement the declaration would go above. */
        private static boolean crossesALambda(Expression expression, Statement statement) {
            for (ASTNode walk = expression; walk != null && walk != statement; walk = walk.getParent()) {
                if (walk instanceof LambdaExpression) return true;
            }
            return false;
        }

        /** Whether the expression reads a variable {@code statement} itself declares. */
        private static boolean readsSomethingDeclaredIn(Expression expression, Statement statement) {
            Set<String> declaredHere = new LinkedHashSet<>();
            statement.accept(new ASTVisitor() {
                @Override public boolean visit(VariableDeclarationFragment fragment) {
                    declaredHere.add(fragment.getName().getIdentifier());
                    return true;
                }

                @Override public boolean visit(SingleVariableDeclaration declared) {
                    declaredHere.add(declared.getName().getIdentifier());
                    return true;
                }
            });
            if (declaredHere.isEmpty()) return false;
            boolean[] reads = {false};
            expression.accept(new ASTVisitor() {
                @Override public boolean visit(SimpleName name) {
                    if (declaredHere.contains(name.getIdentifier())) reads[0] = true;
                    return !reads[0];
                }
            });
            return reads[0];
        }

        /**
         * A name for it — from the called method when there is one, otherwise from the type.
         *
         * <p>{@code getSize()} suggests {@code size}, a {@code String} suggests {@code string}. Neither is
         * clever and both beat {@code x}: the point of introducing a variable is usually to <em>name</em>
         * something, and a name the reader will keep is worth one line of derivation. @see Names</p>
         */
        private static String freshName(Expression expression, ITypeBinding type, Statement at) {
            String base = null;
            if (expression instanceof MethodInvocation) {
                base = Names.fromAccessor(((MethodInvocation) expression).getName().getIdentifier());
            } else if (expression instanceof ClassInstanceCreation) {
                ITypeBinding created = ((ClassInstanceCreation) expression).getType().resolveBinding();
                if (created != null) base = Names.lower(created.getErasure().getName());
            }
            return Names.derive(base, type, Names.declaredIn(Scopes.enclosingMethodOrRoot(at)));
        }
    }

    // ── Inline ──────────────────────────────────────────────────────────────────────────────────

    /** "Inline variable" — every use becomes the initialiser, and the declaration goes. */
    private static final class InlineVariable implements Correction {

        @Override public String id() {
            return INLINE;
        }

        @Override public int[] problems() {
            return Correction.NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            VariableDeclarationStatement statement = context.at(VariableDeclarationStatement.class,
                    candidate -> inlinable(context, candidate));
            if (statement == null) return;
            VariableDeclarationFragment fragment =
                    (VariableDeclarationFragment) statement.fragments().get(0);
            IVariableBinding variable = fragment.resolveBinding();
            Expression value = fragment.getInitializer();
            if (variable == null || value == null) return;

            List<SimpleName> uses = usesOf(statement, variable, fragment.getName());
            if (uses.isEmpty()) return;
            // AN ASSIGNED VARIABLE'S INITIALISER IS NOT ITS VALUE, and WRITTEN THROUGH IS STILL WRITTEN.
            // The plain reassignment is the zero-hop case of the same question: `x = 5` has the use as an
            // assignment's left-hand side, and `xPos[i] = …` has it one array access out. That second one
            // never reassigns xPos, so a reassignment-only guard passes it — and inlining turns the target
            // into `new double[n][i] = …`, which is a two-dimensional array creation and does not parse.
            // The variable's IDENTITY is what those uses depend on, which is exactly what substituting its
            // initialiser destroys. Four real files, none of them a shape anyone would have written a
            // fixture for.
            for (SimpleName use : uses) {
                if (mutatedThrough(use)) return;
            }
            // A CALL EVALUATED MORE THAN ONCE IS NOT THE SAME PROGRAM. One use is a pure move; several turn
            // one call into several, which is a performance change at best and a behaviour change the
            // moment it does anything. IntelliJ warns and offers to continue -- a popup with no dialog
            // cannot ask, so it refuses.
            if (uses.size() > 1 && SideEffects.addedByRepeating(value)) return;

            String source = context.source();
            String text = FixContext.text(value, source);
            if (needsParentheses(value)) text = "(" + text + ")";

            List<Change> changes = new ArrayList<>();
            // FROM THE START OF THE LINE, not from the statement. A statement begins after its indent, so
            // deleting from there takes the declaration and leaves eight spaces sitting on their own —
            // trailing whitespace on a line that no longer exists, which no test comparing the statement
            // would notice and every diff would show.
            changes.add(new Change(startOfLine(source, statement.getStartPosition()),
                    endOfLine(source, statement.getStartPosition() + statement.getLength()), ""));
            for (SimpleName use : uses) {
                changes.add(new Change(use.getStartPosition(),
                        use.getStartPosition() + use.getLength(), text));
            }
            changes.sort(Comparator.comparingInt(Change::from));

            ChangeSet edit = context.changeSet(changes);
            if (edit == null) return;
            out.add(context.intention(INLINE,
                    "Inline variable '" + fragment.getName().getIdentifier() + "'",
                    "Replaces every use with the value and removes the declaration.", edit));
        }

        private static boolean inlinable(FixContext context, VariableDeclarationStatement statement) {
            if (statement.fragments().size() != 1) return false;
            VariableDeclarationFragment fragment =
                    (VariableDeclarationFragment) statement.fragments().get(0);
            if (fragment.getInitializer() == null || fragment.resolveBinding() == null) return false;
            if (!(statement.getParent() instanceof Block)) return false;
            // WHETHER IT IS WRITTEN TO IS ASKED IN `contribute`, over the resolved uses, because that is
            // where the bindings are. This predicate only has to find the declaration the caret is on.
            return context.touches(statement.getStartPosition(),
                    fragment.getName().getStartPosition() + fragment.getName().getLength());
        }

        /** Every read of {@code variable} in the method, which for a local is every read there is. */
        private static List<SimpleName> usesOf(VariableDeclarationStatement declaration,
                                               IVariableBinding variable, SimpleName declared) {
            List<SimpleName> uses = new ArrayList<>();
            Scopes.enclosingMethodOrRoot(declaration).accept(new ASTVisitor() {
                @Override public boolean visit(SimpleName name) {
                    if (name == declared) return true;
                    IVariableBinding bound = name.resolveBinding() instanceof IVariableBinding
                            ? (IVariableBinding) name.resolveBinding() : null;
                    if (bound != null && bound.isEqualTo(variable)) uses.add(name);
                    return true;
                }
            });
            return uses;
        }

        /**
         * Whether substituting this text needs wrapping.
         *
         * <p>{@code int sum = a + b;} inlined into {@code sum * 2} gives {@code a + b * 2} — which compiles
         * and is a different number. Wrapping anything that is not a single term is always safe and
         * occasionally redundant, which is the right way round for an edit nobody re-reads.</p>
         */
        private static boolean needsParentheses(Expression value) {
            return Precedence.needsParenthesesWhenWrapped(value);
        }

        /**
         * Through the newline, so removing a declaration does not leave its blank line behind — but only
         * when the declaration <b>owns the rest of the line</b>.
         *
         * <p>The corpus found this on ten real files: {@code int i = 0; while (i < n) i++;} is one line,
         * and taking it to the newline took the loop with it. The uses inside were being replaced at the
         * same time, so the result was an edit that applied cleanly and left the file unparseable — which
         * is the one thing that pass asserts on, and nothing smaller would have caught it.</p>
         */
        private static int endOfLine(String source, int at) {
            int next = source.indexOf('\n', at);
            if (next < 0) return at;
            for (int i = at; i < next; i++) {
                if (!Character.isWhitespace(source.charAt(i))) return at;
            }
            return next + 1;
        }

        /**
         * Whether this use is a write — to the variable, or to something reached <em>through</em> it.
         *
         * <p>Walks out through array indexes and field selections — {@code xPos[i]}, {@code node.left} —
         * and asks whether what it arrives at is being assigned or stepped. Those uses depend on the
         * variable naming <b>one</b> object, and inlining its initialiser gives each of them their own.</p>
         *
         * <p><b>Zero hops is the plain reassignment</b>, which is why this is the only mechanism now. The
         * one beside it asked the same question by NAME over the whole method, so a same-named local in a
         * sibling block or a lambda refused an inline that was perfectly safe — and it could not see a
         * write through an index at all, which is the case that actually broke files.</p>
         */
        private static boolean mutatedThrough(SimpleName use) {
            ASTNode at = use;
            ASTNode parent = at.getParent();
            while (parent instanceof ArrayAccess && ((ArrayAccess) parent).getArray() == at
                    || parent instanceof FieldAccess && ((FieldAccess) parent).getExpression() == at
                    || parent instanceof QualifiedName && ((QualifiedName) parent).getQualifier() == at) {
                at = parent;
                parent = at.getParent();
            }
            if (parent instanceof Assignment) return ((Assignment) parent).getLeftHandSide() == at;
            if (parent instanceof PostfixExpression) return true;
            if (parent instanceof PrefixExpression) {
                PrefixExpression.Operator operator = ((PrefixExpression) parent).getOperator();
                return operator == PrefixExpression.Operator.INCREMENT
                        || operator == PrefixExpression.Operator.DECREMENT;
            }
            return false;
        }

        /** The line's first character, when everything before {@code at} on it is whitespace. */
        private static int startOfLine(String source, int at) {
            int lineStart = source.lastIndexOf('\n', Math.max(0, at - 1)) + 1;
            for (int i = lineStart; i < at; i++) {
                // SOMETHING ELSE ON THE LINE means the declaration does not own it, and taking the line
                // would take that too.
                if (!Character.isWhitespace(source.charAt(i))) return at;
            }
            return lineStart;
        }
    }

    // ── Shared ──────────────────────────────────────────────────────────────────────────────────


}
