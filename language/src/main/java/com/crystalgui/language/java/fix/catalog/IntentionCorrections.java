package com.crystalgui.language.java.fix.catalog;

import com.crystalgui.language.java.fix.Correction;
import com.crystalgui.language.java.fix.FixContext;
import com.crystalgui.language.java.fix.edit.Indent;
import com.crystalgui.language.java.fix.edit.Negation;
import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <b>Intentions</b> — the other half of Alt+Enter, where nothing is wrong and something could be different.
 *
 * <h3>Why a coverage probe can never find these</h3>
 *
 * <p>An intention has <b>no diagnostic</b>, which is what makes it an intention. So no histogram over
 * reported problems can say one is missing, however many are: the instrument built to rank the fix
 * catalogue is blind to this entire axis by construction. Two were written before this file — organize
 * imports and the lambda conversion — and both arrived because somebody asked for them, not because
 * anything measured a gap.</p>
 *
 * <h3>All four are pairs, and the pairing is the point</h3>
 *
 * <p>Split ↔ join, add braces ↔ remove braces. Each direction is trivial alone and useless alone: you
 * reach for one having just used the other. Writing them as pairs is also what forces the refusals to be
 * stated, because the dangerous half of a reversible edit is always the same one — the direction that
 * <em>removes</em> structure, where the language quietly changes meaning underneath.</p>
 *
 * <h3>Text edits, not rewrites</h3>
 *
 * <p>Every one of these is expressible as one or two ranges, and expressing them that way is strictly
 * better output rather than a shortcut: the author's own formatting, line breaks and comments inside the
 * moved parts survive because nothing regenerates them. {@code LambdaCorrections} records the same finding
 * from the other end, where {@code ASTRewrite} garbled a nested rename and silently dropped comments.</p>
 */
public final class IntentionCorrections {

    static final String SPLIT_DECLARATION = "java.intention.splitDeclaration";
    static final String JOIN_DECLARATION = "java.intention.joinDeclaration";
    static final String ADD_BRACES = "java.intention.addBraces";
    static final String REMOVE_BRACES = "java.intention.removeBraces";
    static final String FLIP_IF_ELSE = "java.intention.flipIfElse";
    static final String NEGATE_COMPARISON = "java.intention.negateComparison";

    private IntentionCorrections() {
    }

    public static List<Correction> all() {
        return List.of(new SplitDeclaration(), new JoinDeclaration(), new AddBraces(), new RemoveBraces(),
                new FlipIfElse(), new NegateComparison());
    }

    // ── Declaration and assignment ──────────────────────────────────────────────────────────────

    /**
     * "Split into declaration and assignment" — {@code int a = 1;} becomes {@code int a;} and {@code a = 1;}.
     *
     * <p><b>One replaced range.</b> The text between the name and the initialiser is {@code " = "}; putting
     * {@code ";\n<indent>a = "} there is the whole edit, and the initialiser is never touched — so its
     * formatting, its line breaks and any comment inside it come through exactly as typed.</p>
     */
    private static final class SplitDeclaration implements Correction {

        @Override public String id() {
            return SPLIT_DECLARATION;
        }

        @Override public int[] problems() {
            return Correction.NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            VariableDeclarationStatement statement =
                    context.at(VariableDeclarationStatement.class, candidate -> triggered(context, candidate));
            if (statement == null) return;
            VariableDeclarationFragment fragment = soleFragment(statement);
            if (fragment == null || fragment.getInitializer() == null) return;
            // `var` CARRIES NO TYPE OF ITS OWN. Splitting it leaves `var a;`, which is not legal Java at
            // any level -- the initialiser IS the declaration's type.
            if (statement.getType().isVar()) return;
            // AND AN ARRAY INITIALISER CANNOT STAND ALONE. `int[] a = {1, 2};` splits to `a = {1, 2};`,
            // which does not parse: the braces are part of the DECLARATION's syntax, not an expression --
            // outside one it has to be `new int[] {1, 2}`. The same fault as `var` from the other side, and
            // the corpus is what found it, on `boolean[] found = {false};` -- a line this codebase writes
            // in every visitor it has.
            if (fragment.getInitializer() instanceof ArrayInitializer) return;

            SimpleName name = fragment.getName();
            int after = name.getStartPosition() + name.getLength();
            int value = fragment.getInitializer().getStartPosition();
            if (value <= after) return;

            String indent = Indent.at(context.source(), statement.getStartPosition());
            ChangeSet edit = context.changeSet(new Change(after, value,
                    ";\n" + indent + name.getIdentifier() + " = "));
            if (edit == null) return;
            out.add(context.intention(SPLIT_DECLARATION, "Split into declaration and assignment",
                    "Separates the declaration from its initial value, leaving the value as an "
                            + "assignment on its own line.", edit));
        }

        private static boolean triggered(FixContext context, VariableDeclarationStatement statement) {
            VariableDeclarationFragment fragment = soleFragment(statement);
            if (fragment == null || fragment.getInitializer() == null) return false;
            // OFFERED ON THE DECLARATION, NEVER INSIDE THE VALUE. A caret in a forty-character initialiser
            // is asking about the expression it is in; putting this in that popup competes with whatever
            // the expression actually needs.
            return context.touches(statement.getStartPosition(),
                    fragment.getName().getStartPosition() + fragment.getName().getLength());
        }
    }

    /**
     * "Join declaration and assignment" — the inverse, and the reason each is worth having.
     *
     * <p>Requires the assignment to be the <b>very next statement</b>. Anything between them may read the
     * variable, and moving the initialiser up past a read changes what the program does — the one way this
     * direction can be wrong, and it is invisible in the result.</p>
     */
    private static final class JoinDeclaration implements Correction {

        @Override public String id() {
            return JOIN_DECLARATION;
        }

        @Override public int[] problems() {
            return Correction.NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            VariableDeclarationStatement statement =
                    context.at(VariableDeclarationStatement.class, candidate -> triggered(context, candidate));
            if (statement == null) return;
            VariableDeclarationFragment fragment = soleFragment(statement);
            Assignment assignment = followingAssignmentTo(statement, fragment);
            if (assignment == null) return;

            SimpleName name = fragment.getName();
            int after = name.getStartPosition() + name.getLength();
            int value = assignment.getRightHandSide().getStartPosition();
            ChangeSet edit = context.changeSet(new Change(after, value, " = "));
            if (edit == null) return;
            out.add(context.intention(JOIN_DECLARATION, "Join declaration and assignment",
                    "Moves the assigned value up onto the declaration it belongs to.", edit));
        }

        private static boolean triggered(FixContext context, VariableDeclarationStatement statement) {
            VariableDeclarationFragment fragment = soleFragment(statement);
            if (fragment == null || fragment.getInitializer() != null) return false;
            if (followingAssignmentTo(statement, fragment) == null) return false;
            return context.touches(statement.getStartPosition(),
                    fragment.getName().getStartPosition() + fragment.getName().getLength());
        }

        /** The {@code a = …;} immediately after {@code statement}, assigning exactly its variable. */
        private static Assignment followingAssignmentTo(VariableDeclarationStatement statement,
                                                        VariableDeclarationFragment fragment) {
            if (fragment == null || !(statement.getParent() instanceof Block)) return null;
            List<?> siblings = ((Block) statement.getParent()).statements();
            int index = siblings.indexOf(statement);
            if (index < 0 || index + 1 >= siblings.size()) return null;
            Object next = siblings.get(index + 1);
            if (!(next instanceof ExpressionStatement)) return null;
            Expression expression = ((ExpressionStatement) next).getExpression();
            if (!(expression instanceof Assignment)) return null;
            Assignment assignment = (Assignment) expression;
            if (assignment.getOperator() != Assignment.Operator.ASSIGN) return null;
            if (!(assignment.getLeftHandSide() instanceof SimpleName)) return null;
            return ((SimpleName) assignment.getLeftHandSide()).getIdentifier()
                    .equals(fragment.getName().getIdentifier()) ? assignment : null;
        }
    }

    // ── Braces ──────────────────────────────────────────────────────────────────────────────────

    /** "Add braces" — a single-statement body of an {@code if}, a loop, or an {@code else}. */
    private static final class AddBraces implements Correction {

        @Override public String id() {
            return ADD_BRACES;
        }

        @Override public int[] problems() {
            return Correction.NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            Statement owner = context.at(Statement.class, candidate -> branchToBrace(context, candidate) != null);
            if (owner == null) return;
            Statement body = branchToBrace(context, owner);
            if (body == null) return;

            String indent = Indent.at(context.source(), owner.getStartPosition());
            int gap = backOverWhitespace(context.source(), body.getStartPosition());
            int end = body.getStartPosition() + body.getLength();

            List<Change> changes = new ArrayList<>();
            changes.add(new Change(gap, body.getStartPosition(), " {\n" + indent + "    "));
            // AND THE CONTINUATION COMES BACK UP ONTO THE BRACE. `} else` is the shape Java is written in,
            // and putting it there is also what makes this the exact inverse of removing them — otherwise
            // the pair drifts a line apart every time somebody uses both.
            int continuation = continuationAfter(context.source(), owner, branchOf(owner, body), end);
            changes.add(continuation < 0
                    ? new Change(end, end, "\n" + indent + "}")
                    : new Change(end, continuation, "\n" + indent + "} "));
            ChangeSet edit = context.changeSet(changes);
            if (edit == null) return;
            out.add(context.layoutIntention(ADD_BRACES, "Add braces",
                    "Wraps this single-statement body in a block, so a second statement can be added "
                            + "without changing what runs.", edit));
        }

        /** The unbraced branch the request is asking about, or null. */
        private static Statement branchToBrace(FixContext context, Statement owner) {
            for (Branch branch : branchesOf(owner)) {
                if (branch.body instanceof Block) continue;
                // `else if` IS NOT AN UNBRACED ELSE. Bracing it produces `else { if (…) … }`, which is
                // legal, means the same thing, and is not what anybody writing a chain wants.
                if (branch.isElse && branch.body instanceof IfStatement) continue;
                if (context.touches(branch.headerFrom, branch.body.getStartPosition())) return branch.body;
            }
            return null;
        }
    }

    /**
     * "Remove braces" — the inverse, and the half where the language changes meaning underneath you.
     *
     * <p>Two refusals, and neither is cosmetic. A <b>declaration</b> is not a legal unbraced body at all:
     * {@code if (x) int a = 1;} does not compile, so removing those braces breaks the file. And an
     * {@code if} without an {@code else} inside a braced then-branch is holding the braces up: take them
     * away and the outer {@code else} <b>re-binds to the inner {@code if}</b> — the dangling-else problem,
     * which compiles perfectly and silently means something else.</p>
     */
    private static final class RemoveBraces implements Correction {

        @Override public String id() {
            return REMOVE_BRACES;
        }

        @Override public int[] problems() {
            return Correction.NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            Statement owner = context.at(Statement.class, candidate -> branchToUnbrace(context, candidate) != null);
            if (owner == null) return;
            Branch branch = branchToUnbrace(context, owner);
            if (branch == null) return;
            Statement only = (Statement) ((Block) branch.body).statements().get(0);

            String indent = Indent.at(context.source(), owner.getStartPosition());
            int gap = backOverWhitespace(context.source(), branch.body.getStartPosition());
            int blockEnd = branch.body.getStartPosition() + branch.body.getLength();
            int innerEnd = only.getStartPosition() + only.getLength();

            List<Change> changes = new ArrayList<>();
            changes.add(new Change(gap, only.getStartPosition(), "\n" + indent + "    "));
            // THE CONSTRUCT MAY NOT BE OVER AT THE BRACE. Deleting `\n<indent>}` closes an `else` — or a
            // `do`'s `while` — up onto the end of the statement that was inside: `println(1); else if (…)`.
            // It is legal Java and it is unreadable, and the second branch keeps the indentation of a block
            // that no longer exists. So when something follows, the brace is replaced by the line break it
            // was providing rather than by nothing.
            int continuation = continuationAfter(context.source(), owner, branch, blockEnd);
            changes.add(continuation < 0
                    ? new Change(innerEnd, blockEnd, "")
                    : new Change(innerEnd, continuation, "\n" + indent));
            ChangeSet edit = context.changeSet(changes);
            if (edit == null) return;
            out.add(context.layoutIntention(REMOVE_BRACES, "Remove braces",
                    "Unwraps a block holding one statement.", edit));
        }

        private static Branch branchToUnbrace(FixContext context, Statement owner) {
            for (Branch branch : branchesOf(owner)) {
                if (!(branch.body instanceof Block)) continue;
                List<?> inside = ((Block) branch.body).statements();
                if (inside.size() != 1) continue;
                Statement only = (Statement) inside.get(0);
                if (only instanceof VariableDeclarationStatement) continue;
                if (danglingElse(owner, branch, only)) continue;
                if (context.touches(branch.headerFrom, branch.body.getStartPosition())) return branch;
            }
            return null;
        }

        /** Whether unbracing would let an {@code else} bind to the wrong {@code if}. */
        private static boolean danglingElse(Statement owner, Branch branch, Statement only) {
            if (branch.isElse || !(owner instanceof IfStatement)) return false;
            if (((IfStatement) owner).getElseStatement() == null) return false;
            return only instanceof IfStatement && ((IfStatement) only).getElseStatement() == null;
        }
    }

    // ── Shared ──────────────────────────────────────────────────────────────────────────────────

    /** An intention answers for no problem. Named so the four declarations say what they mean. */

    /** A body that braces could go around or come off, and where its header starts. */
    private static final class Branch {
        final Statement body;
        final int headerFrom;
        final boolean isElse;

        Branch(Statement body, int headerFrom, boolean isElse) {
            this.body = body;
            this.headerFrom = headerFrom;
            this.isElse = isElse;
        }
    }

    /**
     * Every braceable body of {@code owner}, with the region a caret must touch to be asking about it.
     *
     * <p>An {@code else} branch's header starts where the then-branch ends, which is what puts the
     * {@code else} keyword itself inside the region — the two branches of one {@code if} are separate
     * offers and a caret can only be in one of them.</p>
     */
    private static List<Branch> branchesOf(Statement owner) {
        List<Branch> branches = new ArrayList<>(2);
        int from = owner.getStartPosition();
        if (owner instanceof IfStatement) {
            IfStatement conditional = (IfStatement) owner;
            branches.add(new Branch(conditional.getThenStatement(), from, false));
            if (conditional.getElseStatement() != null) {
                Statement then = conditional.getThenStatement();
                branches.add(new Branch(conditional.getElseStatement(),
                        then.getStartPosition() + then.getLength(), true));
            }
        } else if (owner instanceof ForStatement) {
            branches.add(new Branch(((ForStatement) owner).getBody(), from, false));
        } else if (owner instanceof EnhancedForStatement) {
            branches.add(new Branch(((EnhancedForStatement) owner).getBody(), from, false));
        } else if (owner instanceof WhileStatement) {
            branches.add(new Branch(((WhileStatement) owner).getBody(), from, false));
        } else if (owner instanceof DoStatement) {
            branches.add(new Branch(((DoStatement) owner).getBody(), from, false));
        }
        branches.removeIf(branch -> branch.body == null || branch.body.getStartPosition() < 0);
        return branches;
    }

    private static VariableDeclarationFragment soleFragment(VariableDeclarationStatement statement) {
        // ONE FRAGMENT ONLY. `int a = 1, b = 2;` has a single type node serving both, so either direction
        // would silently rewrite the fragment the caret was never on.
        return statement.fragments().size() == 1
                ? (VariableDeclarationFragment) statement.fragments().get(0) : null;
    }

    /** Back up over the whitespace before {@code position} — the gap after {@code )} or {@code else}. */
    private static int backOverWhitespace(String source, int position) {
        int at = position;
        while (at > 0 && Character.isWhitespace(source.charAt(at - 1))) at--;
        return at;
    }

    // ── The condition ───────────────────────────────────────────────────────────────────────────

    /**
     * "Flip if/else" — negate the condition and swap the branches, which together change nothing.
     *
     * <p>The pair is the point: negating alone changes what the program does, swapping alone changes what
     * the program does, and doing both is a pure reading change. That is why this is one intention and not
     * two, and why the negation lives in {@link Negation} where "Negate comparison" can share it.</p>
     *
     * <p><b>Refused on an {@code else if}.</b> The else branch of a chain is another {@code if}, so
     * swapping would hoist a whole tail into the then-position and leave the chain meaning something else.
     * IntelliJ refuses it there too.</p>
     *
     * <p>Three text ranges — the condition and the two branches — so every brace, comment and line break
     * inside either branch arrives exactly as written. A rewriter would regenerate both bodies.</p>
     */
    private static final class FlipIfElse implements Correction {

        @Override public String id() {
            return FLIP_IF_ELSE;
        }

        @Override public int[] problems() {
            return Correction.NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            IfStatement conditional = context.at(IfStatement.class, candidate -> flippable(context, candidate));
            if (conditional == null) return;
            Statement then = conditional.getThenStatement();
            Statement otherwise = conditional.getElseStatement();
            Expression condition = conditional.getExpression();
            String source = context.source();

            List<Change> changes = new ArrayList<>();
            changes.add(new Change(condition.getStartPosition(),
                    condition.getStartPosition() + condition.getLength(), Negation.of(condition, source)));
            changes.add(new Change(then.getStartPosition(), then.getStartPosition() + then.getLength(),
                    FixContext.text(otherwise, source)));
            changes.add(new Change(otherwise.getStartPosition(),
                    otherwise.getStartPosition() + otherwise.getLength(), FixContext.text(then, source)));
            changes.sort(Comparator.comparingInt(Change::from));

            ChangeSet edit = context.changeSet(changes);
            if (edit == null) return;
            out.add(context.intention(FLIP_IF_ELSE, "Flip if/else",
                    "Swaps the two branches and negates the condition, which together change nothing.",
                    edit));
        }

        private static boolean flippable(FixContext context, IfStatement conditional) {
            Statement otherwise = conditional.getElseStatement();
            if (otherwise == null || otherwise instanceof IfStatement) return false;
            if (conditional.getThenStatement() == null || conditional.getExpression() == null) return false;
            return context.touches(conditional.getStartPosition(),
                    conditional.getThenStatement().getStartPosition());
        }
    }

    /**
     * "Negate comparison" — {@code n == 0} becomes {@code n != 0}.
     *
     * <p><b>Only a comparison, and that restriction is what makes it honest.</b> This one changes what the
     * program does — unlike every other intention in this file, which are all meaning-preserving — so it
     * has to be unmistakably an edit somebody asked for rather than something that reads as a tidy. A
     * flipped {@code ==} is exactly that: it says one thing, the reader can see both halves, and there is
     * no version of it that quietly does something else. Wrapping an arbitrary condition in {@code !}
     * would not be.</p>
     */
    private static final class NegateComparison implements Correction {

        @Override public String id() {
            return NEGATE_COMPARISON;
        }

        @Override public int[] problems() {
            return Correction.NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            InfixExpression comparison = context.at(InfixExpression.class,
                    candidate -> negatable(context, candidate));
            if (comparison == null) return;

            ChangeSet edit = context.changeSet(new Change(comparison.getStartPosition(),
                    comparison.getStartPosition() + comparison.getLength(),
                    Negation.of(comparison, context.source())));
            if (edit == null) return;
            out.add(context.alteringIntention(NEGATE_COMPARISON, "Negate comparison",
                    "Replaces this comparison with its opposite. This changes what the code does.", edit));
        }

        private static boolean negatable(FixContext context, InfixExpression candidate) {
            if (candidate.hasExtendedOperands()) return false;
            return Negation.isComparison(candidate.getOperator())
                    && context.touches(candidate.getStartPosition(),
                            candidate.getStartPosition() + candidate.getLength());
        }
    }

    /** Which branch of {@code owner} this body is — needed to tell a then-branch from an else. */
    private static Branch branchOf(Statement owner, Statement body) {
        for (Branch branch : branchesOf(owner)) {
            if (branch.body == body) return branch;
        }
        return new Branch(body, owner.getStartPosition(), false);
    }

    /**
     * Where the construct picks up again after this block — an {@code else}, or a {@code do}'s
     * {@code while} — or {@code -1} when the block ends it.
     *
     * <p>Found by scanning forward over whitespace rather than from the AST, because what is wanted is the
     * <b>keyword's</b> offset and the tree only offers the else <em>branch</em>, which starts after it.</p>
     */
    private static int continuationAfter(String source, Statement owner, Branch branch, int blockEnd) {
        boolean continues = owner instanceof DoStatement
                || owner instanceof IfStatement && !branch.isElse
                        && ((IfStatement) owner).getElseStatement() != null;
        if (!continues) return -1;
        int at = blockEnd;
        while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++;
        return at;
    }


}
