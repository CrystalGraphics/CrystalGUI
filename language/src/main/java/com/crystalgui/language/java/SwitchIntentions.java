package com.crystalgui.language.java;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.ThrowStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * "Replace if chain with switch" — a run of {@code else if} all testing one variable against constants.
 *
 * <h3>The narrowest version that is always right</h3>
 *
 * <p>Two condition shapes and no others: {@code subject == <literal>} for a number or a character, and
 * {@code subject.equals("literal")} either way round for a string. Both have a case label that is written
 * exactly as the literal already is, so nothing has to be re-derived and nothing can be re-derived wrongly.</p>
 *
 * <p><b>Enum constants are deliberately out</b>, and they are the case that looks most obviously in. A
 * {@code switch} over an enum requires the case label <em>unqualified</em> — {@code case RED}, never
 * {@code case Colour.RED} — while the {@code if} it came from almost always writes the qualified form. So
 * the conversion would have to strip a qualifier it cannot always identify, and get it wrong on a static
 * import or a constant that merely looks like one. A conversion that is right for two shapes beats one
 * that is usually right for three.</p>
 *
 * <h3>Every branch gets a {@code break}, unless it already leaves</h3>
 *
 * <p>Fall-through is the difference between an {@code if} chain and a {@code switch}, and it is silent. A
 * branch ending in {@code return}, {@code throw}, {@code break} or {@code continue} already leaves and
 * gets nothing added; every other branch gets a {@code break;}. Missing one turns a chain that ran one
 * branch into a switch that runs the rest of them.</p>
 *
 * <p>Bodies are copied as written and re-indented one level. Nothing regenerates them, so comments and
 * formatting inside a branch survive.</p>
 */
final class SwitchIntentions {

    static final String TO_SWITCH = "java.intention.ifChainToSwitch";

    /** Below this a switch is longer than what it replaces, which is not an improvement. */
    private static final int MINIMUM_BRANCHES = 3;

    private SwitchIntentions() {
    }

    static List<Correction> all() {
        return List.of(new IfChainToSwitch());
    }

    private static final class IfChainToSwitch implements Correction {

        @Override public String id() {
            return TO_SWITCH;
        }

        @Override public int[] problems() {
            return new int[0];
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            IfStatement chain = context.at(IfStatement.class, candidate -> triggered(context, candidate));
            if (chain == null) return;
            Chain parsed = parse(chain);
            if (parsed == null) return;

            String source = context.source();
            String indent = indentAt(source, chain.getStartPosition());
            StringBuilder built = new StringBuilder("switch (").append(parsed.subject).append(") {\n");
            for (Branch branch : parsed.branches) {
                built.append(indent).append("    case ").append(branch.label).append(":\n");
                appendBody(built, branch.body, source, indent + "        ");
            }
            if (parsed.otherwise != null) {
                built.append(indent).append("    default:\n");
                appendBody(built, parsed.otherwise, source, indent + "        ");
            }
            built.append(indent).append('}');

            ChangeSet edit = context.changeSet(new Change(chain.getStartPosition(),
                    chain.getStartPosition() + chain.getLength(), built.toString()));
            if (edit == null) return;
            out.add(context.preferredIntention(TO_SWITCH, "Replace if chain with switch",
                    "Rewrites the chain as a switch on the value every branch tests.", edit));
        }

        private static boolean triggered(FixContext context, IfStatement chain) {
            // ONLY THE HEAD OF THE CHAIN. Every `else if` is itself an IfStatement, so without this the
            // same conversion is offered from halfway down and converts only the tail.
            if (chain.getParent() instanceof IfStatement
                    && ((IfStatement) chain.getParent()).getElseStatement() == chain) {
                return false;
            }
            return parse(chain) != null && chain.getThenStatement() != null
                    && context.touches(chain.getStartPosition(), chain.getThenStatement().getStartPosition());
        }

        /** One branch's case label and the statement it runs. */
        private static void appendBody(StringBuilder built, Statement body, String source, String indent) {
            String text = source.substring(body.getStartPosition(),
                    body.getStartPosition() + body.getLength()).trim();
            if (body instanceof Block) {
                text = text.substring(1, text.length() - 1).trim();
            }
            for (String line : text.split("\n", -1)) {
                built.append(indent).append(line.trim()).append('\n');
            }
            if (!leaves(body)) built.append(indent).append("break;\n");
        }

        /** Whether this branch already leaves the switch it is about to become part of. */
        private static boolean leaves(Statement body) {
            Statement last = body;
            if (body instanceof Block) {
                List<?> statements = ((Block) body).statements();
                if (statements.isEmpty()) return false;
                last = (Statement) statements.get(statements.size() - 1);
            }
            return last instanceof ReturnStatement || last instanceof ThrowStatement
                    || last instanceof BreakStatement || last instanceof ContinueStatement;
        }
    }

    // ── Reading the chain ───────────────────────────────────────────────────────────────────────

    private static final class Branch {
        final String label;
        final Statement body;

        Branch(String label, Statement body) {
            this.label = label;
            this.body = body;
        }
    }

    private static final class Chain {
        final String subject;
        final List<Branch> branches;
        final Statement otherwise;

        Chain(String subject, List<Branch> branches, Statement otherwise) {
            this.subject = subject;
            this.branches = branches;
            this.otherwise = otherwise;
        }
    }

    /** The chain as a switch, or null when any part of it is not one. */
    private static Chain parse(IfStatement head) {
        List<Branch> branches = new ArrayList<>();
        String subject = null;
        Statement otherwise = null;

        for (IfStatement at = head; at != null; ) {
            String[] test = testOf(at.getExpression());
            if (test == null) return null;
            if (subject == null) {
                subject = test[0];
            } else if (!subject.equals(test[0])) {
                // EVERY BRANCH MUST TEST THE SAME VALUE. A chain that changes subject halfway is a chain,
                // not a switch, and converting it would silently drop the other conditions.
                return null;
            }
            if (at.getThenStatement() == null) return null;
            branches.add(new Branch(test[1], at.getThenStatement()));

            Statement next = at.getElseStatement();
            if (next instanceof IfStatement) {
                at = (IfStatement) next;
            } else {
                otherwise = next;
                at = null;
            }
        }
        return branches.size() >= MINIMUM_BRANCHES ? new Chain(subject, branches, otherwise) : null;
    }

    /**
     * {@code {subject, caseLabel}} for a condition this can switch on, or null.
     *
     * <p>The subject is compared as <b>source text</b> rather than by binding, which is deliberate and is
     * the conservative direction: two spellings of the same variable read as different subjects and refuse
     * the conversion, where a binding comparison would accept them and then have to choose which spelling
     * to write into the {@code switch} header.</p>
     */
    private static String[] testOf(Expression condition) {
        if (condition instanceof InfixExpression) {
            InfixExpression comparison = (InfixExpression) condition;
            if (comparison.getOperator() != InfixExpression.Operator.EQUALS
                    || comparison.hasExtendedOperands()) {
                return null;
            }
            Expression left = comparison.getLeftOperand();
            Expression right = comparison.getRightOperand();
            if (isName(left) && isSwitchableLiteral(right)) return new String[] {left.toString(), right.toString()};
            if (isName(right) && isSwitchableLiteral(left)) return new String[] {right.toString(), left.toString()};
            return null;
        }
        if (condition instanceof MethodInvocation) {
            MethodInvocation call = (MethodInvocation) condition;
            if (!"equals".equals(call.getName().getIdentifier()) || call.arguments().size() != 1) return null;
            Expression receiver = call.getExpression();
            Expression argument = (Expression) call.arguments().get(0);
            if (receiver == null) return null;
            if (isName(receiver) && argument instanceof StringLiteral) {
                return new String[] {receiver.toString(), argument.toString()};
            }
            // `"literal".equals(subject)` is the null-safe spelling and is at least as common.
            if (receiver instanceof StringLiteral && isName(argument)) {
                return new String[] {argument.toString(), receiver.toString()};
            }
        }
        return null;
    }

    private static boolean isName(Expression expression) {
        return expression instanceof SimpleName;
    }

    /** A number or a character — never an enum constant. @see SwitchIntentions */
    private static boolean isSwitchableLiteral(Expression expression) {
        return expression instanceof NumberLiteral || expression instanceof CharacterLiteral;
    }

    private static String indentAt(String source, int position) {
        int lineStart = source.lastIndexOf('\n', Math.max(0, position - 1)) + 1;
        int at = lineStart;
        while (at < source.length() && at < position
                && (source.charAt(at) == ' ' || source.charAt(at) == '\t')) {
            at++;
        }
        return source.substring(lineStart, at);
    }
}
