package com.crystalgui.language.java;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

import java.util.ArrayList;
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
final class IntentionCorrections {

    static final String SPLIT_DECLARATION = "java.intention.splitDeclaration";
    static final String JOIN_DECLARATION = "java.intention.joinDeclaration";
    static final String ADD_BRACES = "java.intention.addBraces";
    static final String REMOVE_BRACES = "java.intention.removeBraces";

    private IntentionCorrections() {
    }

    static List<Correction> all() {
        return List.of(new SplitDeclaration(), new JoinDeclaration(), new AddBraces(), new RemoveBraces());
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
            return NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            VariableDeclarationStatement statement =
                    context.at(VariableDeclarationStatement.class, candidate -> triggered(context, candidate));
            if (statement == null) return;
            VariableDeclarationFragment fragment = soleFragment(statement);
            if (fragment == null || fragment.getInitializer() == null) return;
            // `var` CARRIES NO TYPE OF ITS OWN. Splitting it leaves `var a;`, which is not legal Java at
            // any level -- the initialiser IS the declaration's type.
            if ("var".equals(statement.getType().toString())) return;

            SimpleName name = fragment.getName();
            int after = name.getStartPosition() + name.getLength();
            int value = fragment.getInitializer().getStartPosition();
            if (value <= after) return;

            String indent = indentAt(context.source(), statement.getStartPosition());
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
            return NONE;
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
            return NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            Statement owner = context.at(Statement.class, candidate -> branchToBrace(context, candidate) != null);
            if (owner == null) return;
            Statement body = branchToBrace(context, owner);
            if (body == null) return;

            String indent = indentAt(context.source(), owner.getStartPosition());
            int gap = backOverWhitespace(context.source(), body.getStartPosition());
            int end = body.getStartPosition() + body.getLength();

            List<Change> changes = new ArrayList<>();
            changes.add(new Change(gap, body.getStartPosition(), " {\n" + indent + "    "));
            changes.add(new Change(end, end, "\n" + indent + "}"));
            ChangeSet edit = context.changeSet(changes);
            if (edit == null) return;
            out.add(context.intention(ADD_BRACES, "Add braces",
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
            return NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            Statement owner = context.at(Statement.class, candidate -> branchToUnbrace(context, candidate) != null);
            if (owner == null) return;
            Branch branch = branchToUnbrace(context, owner);
            if (branch == null) return;
            Statement only = (Statement) ((Block) branch.body).statements().get(0);

            String indent = indentAt(context.source(), owner.getStartPosition());
            int gap = backOverWhitespace(context.source(), branch.body.getStartPosition());
            int blockEnd = branch.body.getStartPosition() + branch.body.getLength();
            int innerEnd = only.getStartPosition() + only.getLength();

            List<Change> changes = new ArrayList<>();
            changes.add(new Change(gap, only.getStartPosition(), "\n" + indent + "    "));
            changes.add(new Change(innerEnd, blockEnd, ""));
            ChangeSet edit = context.changeSet(changes);
            if (edit == null) return;
            out.add(context.intention(REMOVE_BRACES, "Remove braces",
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
    private static final int[] NONE = new int[0];

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

    /** The leading whitespace of the line {@code position} is on. */
    private static String indentAt(String source, int position) {
        int lineStart = source.lastIndexOf('\n', Math.max(0, position - 1)) + 1;
        int at = lineStart;
        while (at < source.length() && at < position && (source.charAt(at) == ' ' || source.charAt(at) == '\t')) {
            at++;
        }
        return source.substring(lineStart, at);
    }

    /** Back up over the whitespace before {@code position} — the gap after {@code )} or {@code else}. */
    private static int backOverWhitespace(String source, int position) {
        int at = position;
        while (at > 0 && Character.isWhitespace(source.charAt(at - 1))) at--;
        return at;
    }
}
