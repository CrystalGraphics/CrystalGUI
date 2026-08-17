package com.crystalgui.language.java;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * <h3>And the branch has to mean the same thing once it is a case</h3>
 *
 * <p>Two of them, both silent, both found by a probe rather than by reading: a bare {@code break} in a
 * branch was breaking a loop and would break the new {@code switch} instead, and two branches each
 * declaring the same name were legal as separate {@code if} bodies and are one scope as a switch body. The
 * first is refused, the second keeps its braces. See {@link #containsBareBreak} and
 * {@code declaresAVariable}.</p>
 *
 * <p>Bodies are copied as written and re-indented one level through {@link Indent#reindent}. Nothing
 * regenerates them, so comments and formatting inside a branch survive.</p>
 */
final class SwitchIntentions {

    static final String TO_SWITCH = "java.intention.ifChainToSwitch";
    static final String TO_ARROW = "java.intention.arrowSwitch";

    /** Below this a switch is longer than what it replaces, which is not an improvement. */
    private static final int MINIMUM_BRANCHES = 3;

    private SwitchIntentions() {
    }

    static List<Correction> all() {
        return List.of(new IfChainToSwitch(), new ToArrowSwitch());
    }

    private static final class IfChainToSwitch implements Correction {

        @Override public String id() {
            return TO_SWITCH;
        }

        @Override public int[] problems() {
            return Correction.NONE;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            IfStatement chain = context.at(IfStatement.class, candidate -> triggered(context, candidate));
            if (chain == null) return;
            Chain parsed = parse(chain);
            if (parsed == null) return;

            String source = context.source();
            String indent = Indent.at(source, chain.getStartPosition());
            StringBuilder built = new StringBuilder("switch (").append(parsed.subject).append(") {\n");
            for (Branch branch : parsed.branches) {
                built.append(indent).append("    case ").append(branch.label).append(':');
                appendBody(built, branch.body, source, indent + "    ");
            }
            if (parsed.otherwise != null) {
                built.append(indent).append("    default:");
                appendBody(built, parsed.otherwise, source, indent + "    ");
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

        /**
         * One branch's statements under their label, re-indented, with the {@code break} it needs.
         *
         * <h3>A branch that DECLARES something keeps its braces</h3>
         *
         * <p>A switch body is <b>one scope</b>, however many labels it has. Two branches that each declared
         * {@code int a} were two separate blocks as an {@code if} chain and are one block as a switch — so
         * unwrapping both produced a duplicate-variable error out of code that had none. Keeping the block
         * gives the branch back the scope the {@code if} gave it, which is what IntelliJ writes here and for
         * this reason.</p>
         *
         * <p>Only a declaration <b>directly</b> in the branch matters. One inside a nested {@code if} or
         * block is already scoped by that, and wrapping for it would put braces around most branches.</p>
         */
        private static void appendBody(StringBuilder built, Statement body, String source, String label) {
            boolean scoped = declaresAVariable(body);
            String inner = label + "    ";
            // The brace opens on the LABEL'S OWN LINE, which is why this writes the newline the caller
            // would otherwise have written: `case 1: {` costs no line, and a case that needs a scope is
            // already the longest branch in the switch.
            built.append(scoped ? " {\n" : "\n");
            String text = interiorOf(body, source);
            if (!text.isEmpty()) built.append(Indent.reindent(text, inner)).append('\n');
            if (!leaves(body)) built.append(inner).append("break;\n");
            if (scoped) built.append(label).append("}\n");
        }

        /** The branch's statements, without the braces the {@code if} wrote around them. */
        private static String interiorOf(Statement body, String source) {
            String text = FixContext.text(body, source).trim();
            if (!(body instanceof Block)) return text;
            return text.substring(1, text.length() - 1).trim();
        }

        /** Whether this branch declares a name that the switch body's single scope would have to share. */
        private static boolean declaresAVariable(Statement body) {
            // A lone declaration is not a legal branch on its own -- `if (x) int a = 1;` does not parse --
            // so only a block can be carrying one.
            if (!(body instanceof Block)) return false;
            for (Object each : ((Block) body).statements()) {
                if (each instanceof VariableDeclarationStatement) return true;
            }
            return false;
        }

        /**
         * Whether this branch already leaves the switch it is about to become part of.
         *
         * <p>{@code break} stays in the list even though {@link #containsBareBreak} has refused the whole
         * conversion for an unlabeled one: {@code break outer;} still leaves, and still keeps its meaning.</p>
         */
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
        Set<Object> labels = new LinkedHashSet<>();
        String subject = null;
        Statement otherwise = null;

        for (IfStatement at = head; at != null; ) {
            Test test = testOf(at.getExpression());
            if (test == null) return null;
            if (subject == null) {
                if (!switchable(test.subject)) return null;
                subject = test.subject.toString();
            } else if (!subject.equals(test.subject.toString())) {
                // EVERY BRANCH MUST TEST THE SAME VALUE. A chain that changes subject halfway is a chain,
                // not a switch, and converting it would silently drop the other conditions.
                return null;
            }
            // TWO BRANCHES ON ONE CONSTANT are merely unreachable as a chain and a duplicate case label as
            // a switch, which does not compile.
            if (!labels.add(test.key)) return null;
            if (at.getThenStatement() == null) return null;
            if (containsBareBreak(at.getThenStatement())) return null;
            branches.add(new Branch(test.label, at.getThenStatement()));

            Statement next = at.getElseStatement();
            if (next instanceof IfStatement) {
                at = (IfStatement) next;
            } else {
                otherwise = next;
                at = null;
            }
        }
        if (otherwise != null && containsBareBreak(otherwise)) return null;
        return branches.size() >= MINIMUM_BRANCHES ? new Chain(subject, branches, otherwise) : null;
    }

    /** One condition read as a case: what it tests, the label to write, and the constant that label is. */
    private static final class Test {
        final Expression subject;
        final String label;
        final Object key;

        Test(Expression subject, Expression literal) {
            this.subject = subject;
            this.label = literal.toString();
            Object constant = literal.resolveConstantExpressionValue();
            // `case 'a'` AND `case 97` ARE ONE LABEL in an int switch, so a character compares as its code.
            if (constant instanceof Character) constant = (int) (Character) constant;
            this.key = constant != null ? constant : this.label;
        }
    }

    /**
     * The condition read as a case, or null.
     *
     * <p>The subject is compared across branches as <b>source text</b> rather than by binding, which is
     * deliberate and is the conservative direction: two spellings of the same variable read as different
     * subjects and refuse the conversion, where a binding comparison would accept them and then have to
     * choose which spelling to write into the {@code switch} header.</p>
     */
    private static Test testOf(Expression condition) {
        if (condition instanceof InfixExpression) {
            InfixExpression comparison = (InfixExpression) condition;
            if (comparison.getOperator() != InfixExpression.Operator.EQUALS
                    || comparison.hasExtendedOperands()) {
                return null;
            }
            Expression left = comparison.getLeftOperand();
            Expression right = comparison.getRightOperand();
            if (isName(left) && isSwitchableLiteral(right)) return new Test(left, right);
            if (isName(right) && isSwitchableLiteral(left)) return new Test(right, left);
            return null;
        }
        if (condition instanceof MethodInvocation) {
            MethodInvocation call = (MethodInvocation) condition;
            if (!"equals".equals(call.getName().getIdentifier()) || call.arguments().size() != 1) return null;
            Expression receiver = call.getExpression();
            Expression argument = (Expression) call.arguments().get(0);
            if (receiver == null) return null;
            if (isName(receiver) && argument instanceof StringLiteral) return new Test(receiver, argument);
            // `"literal".equals(subject)` is the null-safe spelling and is at least as common.
            if (receiver instanceof StringLiteral && isName(argument)) return new Test(argument, receiver);
        }
        return null;
    }

    /**
     * Whether a {@code switch} may take this value <b>at all</b>.
     *
     * <p>The switchable types are the integral ones up to {@code int}, their boxes, {@code String} and an
     * enum — and nothing else. {@code if (n == 1)} on a {@code long} is perfectly ordinary code and became
     * {@code switch (n)}, which is an error the chain did not have; the shape of the literal says nothing
     * about the type of what it is compared to, so the subject's own binding is what decides.</p>
     *
     * <p>Enums are switchable and are refused earlier anyway, for the case label's sake — see the class
     * javadoc.</p>
     */
    private static boolean switchable(Expression subject) {
        ITypeBinding type = subject.resolveTypeBinding();
        if (type == null) return false;
        switch (type.isPrimitive() ? type.getName() : type.getQualifiedName()) {
            case "int": case "short": case "byte": case "char":
            case "java.lang.Integer": case "java.lang.Short": case "java.lang.Byte":
            case "java.lang.Character": case "java.lang.String":
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether this statement contains a {@code break} that <b>would stop breaking what it breaks now</b>.
     *
     * <p>An unlabeled {@code break} inside an {@code if} chain has nothing to leave but a loop or a switch
     * that <em>encloses</em> the chain. Make that branch a {@code case} and the nearest breakable thing
     * becomes the new switch, so the loop it was ending now runs on: it compiles, and it is a different
     * program. The reading that hid it is the one that looks most reasonable — {@code leaves()} saw the
     * same {@code break} and concluded the branch already left, so it added nothing and everything looked
     * consistent.</p>
     *
     * <p>The colon-to-arrow conversion asks the same question for a different reason: a switch rule may not
     * complete abruptly with a {@code break} (JLS 14.11.2), so one left inside a group has no arrow form.</p>
     *
     * <p>Nested loops and switches are skipped — a {@code break} inside one of those already belongs to it
     * and still will.</p>
     */
    private static boolean containsBareBreak(Statement body) {
        boolean[] found = {false};
        body.accept(new ASTVisitor() {
            @Override public boolean visit(BreakStatement statement) {
                if (statement.getLabel() == null) found[0] = true;
                return false;
            }

            @Override public boolean visit(ForStatement nested) { return false; }

            @Override public boolean visit(EnhancedForStatement nested) { return false; }

            @Override public boolean visit(WhileStatement nested) { return false; }

            @Override public boolean visit(DoStatement nested) { return false; }

            @Override public boolean visit(SwitchStatement nested) { return false; }
        });
        return found[0];
    }

    private static boolean isName(Expression expression) {
        return expression instanceof SimpleName;
    }

    /** A number or a character — never an enum constant. @see SwitchIntentions */
    private static boolean isSwitchableLiteral(Expression expression) {
        return expression instanceof NumberLiteral || expression instanceof CharacterLiteral;
    }

    /**
     * "Convert to arrow switch" — the colon form rewritten as {@code case X -> …}, where the level allows it.
     *
     * <h3>Gated on the release level, which is a fact rather than a guess</h3>
     *
     * <p>Arrow labels are Java 14. Writing one into a file compiled at 8 turns working code into a syntax
     * error, which is the worst outcome this layer has — worse than offering nothing, because the offer
     * looked like an improvement. The engine already runs in bands and the level is part of the analysis
     * request, so {@link FixContext#releaseLevel()} answers it outright.</p>
     *
     * <h3>Why the arrow form is worth offering at all</h3>
     *
     * <p>It is not cosmetic. The colon form falls through unless every branch says otherwise, and the arrow
     * form cannot — so the conversion removes a whole class of defect from the code rather than restyling
     * it. That is also what makes it safe here: every {@code break} that exists is now redundant and is
     * dropped, and a branch that <em>relied</em> on falling through is refused outright, because it is the
     * one shape the arrow form cannot express.</p>
     */
    private static final class ToArrowSwitch implements Correction {

        /** {@code case X ->} arrived in Java 14. */
        private static final int ARROW_LABELS_FROM = 14;

        @Override
        public String id() {
            return TO_ARROW;
        }

        @Override
        public int[] problems() {
            return Correction.NONE;
        }

        @Override
        public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            if (context.releaseLevel() < ARROW_LABELS_FROM) return;
            SwitchStatement switched = context.at(SwitchStatement.class,
                    candidate -> triggered(context, candidate));
            if (switched == null) return;
            List<Group> groups = groupsOf(switched, context.source());
            if (groups == null) return;

            String source = context.source();
            String indent = Indent.at(source, switched.getStartPosition());
            StringBuilder built = new StringBuilder("switch (")
                    .append(FixContext.text(switched.getExpression(), source)).append(") {\n");
            for (Group group : groups) {
                built.append(indent).append("    ").append(group.label).append(" -> ");
                appendArrowBody(built, group, source, indent);
            }
            built.append(indent).append('}');

            ChangeSet edit = context.changeSet(new Change(switched.getStartPosition(),
                    switched.getStartPosition() + switched.getLength(), built.toString()));
            if (edit == null) return;
            out.add(context.preferredIntention(TO_ARROW, "Convert to arrow switch",
                    "Rewrites the labels as `case X ->`, which cannot fall through, and drops the breaks "
                            + "that were holding it back.", edit));
        }

        /**
         * Whether this statement may follow an arrow directly, or has to be wrapped in a block.
         *
         * <p>The arrow form takes an <b>expression statement, a block, or a {@code throw}</b> — and nothing
         * else. {@code case "float" -> return "0";} does not parse, which is what the corpus found on four
         * real files: every one of them writes its single statement on the label's own line, and every one
         * of them returns. A single statement is not the same question as a legal arrow body.</p>
         */
        private static boolean bareArrowBodyAllowed(Statement only) {
            return only instanceof ExpressionStatement || only instanceof ThrowStatement;
        }

        private static boolean triggered(FixContext context, SwitchStatement switched) {
            return groupsOf(switched, context.source()) != null && switched.getExpression() != null
                    && context.touches(switched.getStartPosition(),
                    switched.getExpression().getStartPosition()
                            + switched.getExpression().getLength());
        }

        /**
         * One arrow branch: its label text, and the statements it runs with any trailing break removed.
         *
         * <h3>Three shapes, and the middle one is why this is not just "braces or not"</h3>
         *
         * <p>A single expression statement or {@code throw} follows the arrow bare. A single statement that
         * may <em>not</em> — a {@code return}, which is the common case for a switch that produces a value
         * — still needs its block, but the block goes <b>on one line</b>. Anything longer opens up.</p>
         *
         * <p>The braces there are not a formatting choice; {@code case "a" -> return "first";} does not
         * parse. Their <em>layout</em> is, and spreading one statement over three lines defeats the reason
         * to convert at all: the arrow form earns its keep by reading as a table, and a three-line block per
         * branch turns a six-line switch into eighteen.</p>
         */
        private static void appendArrowBody(StringBuilder built, Group group, String source, String indent) {
            if (group.body.size() == 1) {
                Statement only = group.body.get(0);
                String text = FixContext.text(only, source).trim();
                if (bareArrowBodyAllowed(only)) {
                    built.append(text).append('\n');
                    return;
                }
                if (!(only instanceof Block) && text.indexOf('\n') < 0) {
                    built.append("{ ").append(text).append(" }\n");
                    return;
                }
            }
            built.append("{\n");
            for (Statement statement : group.body) {
                built.append(Indent.reindent(FixContext.text(statement, source), indent + "        "))
                        .append('\n');
            }
            built.append(indent).append("    }\n");
        }
    }

    /** One {@code case} label and the statements under it, with any closing {@code break} already gone. */
    private record Group(String label, List<Statement> body) {
    }

    /**
     * The switch read as arrow groups, or null when it cannot be one.
     *
     * <p>Three refusals, and the first is the whole reason this conversion is worth having rather than a
     * reason it is dangerous:</p>
     *
     * <ul>
     *   <li><b>A group that falls through</b> — statements, then the next {@code case} with no {@code break}
     *       — has no arrow form at all. It is also the defect the arrow form exists to prevent, so refusing
     *       here is refusing to guess at code that may well be a bug.</li>
     *   <li><b>An empty group stacked on the next</b> ({@code case 1: case 2:}) is the one legal
     *       fall-through, and the arrow form spells it {@code case 1, 2 ->} — handled rather than refused,
     *       because it is common and unambiguous.</li>
     *   <li><b>Anything already using arrows</b> is left alone; there is nothing to convert.</li>
     * </ul>
     */
    private static List<Group> groupsOf(SwitchStatement switched, String source) {
        List<Group> groups = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<Statement> body = new ArrayList<>();
        boolean sawLabel = false;

        for (Object each : switched.statements()) {
            if (each instanceof SwitchCase) {
                SwitchCase label = (SwitchCase) each;
                if (label.isSwitchLabeledRule()) return null;
                if (!body.isEmpty()) {
                    // STATEMENTS, THEN ANOTHER LABEL, AND NOTHING LEFT THE GROUP. That is a fall-through,
                    // which the arrow form cannot express -- and which is the defect it exists to prevent.
                    if (!leaves(body.get(body.size() - 1))) return null;
                    groups.add(new Group(joined(pending), stripTrailingBreak(body)));
                    pending = new ArrayList<>();
                    body = new ArrayList<>();
                }
                pending.add(labelTextOf(label, source));
                sawLabel = true;
            } else if (each instanceof Statement) {
                if (!sawLabel) return null;
                body.add((Statement) each);
            }
        }
        if (!pending.isEmpty()) groups.add(new Group(joined(pending), stripTrailingBreak(body)));
        for (Group group : groups) {
            if (group.body.isEmpty()) return null;
            for (Statement statement : group.body) {
                // A `break` STILL INSIDE THE GROUP breaks this switch, and a switch rule may not complete
                // abruptly with one -- so there is no arrow form of it to write.
                if (containsBareBreak(statement)) return null;
            }
        }
        return groups.size() >= 2 ? groups : null;
    }

    /** {@code case 1: case 2:} becomes one label — the arrow form's own spelling for stacked cases. */
    private static String joined(List<String> labels) {
        if (labels.size() == 1) return labels.get(0);
        List<String> values = new ArrayList<>();
        for (String label : labels) {
            if ("default".equals(label)) return "default";
            values.add(label.substring("case ".length()).trim());
        }
        return "case " + String.join(", ", values);
    }

    /**
     * The label exactly as written — {@code "case 1"} or {@code "default"}.
     *
     * <p><b>From the source, not from the DOM.</b> {@code SwitchCase.getExpression()} is the pre-Java-12
     * accessor and answers {@code MISSING} once the AST is built at a newer level, while its replacement
     * {@code expressions()} throws on an older one — so either call is wrong on half the bands this engine
     * runs. The characters are right on all of them, and they also keep {@code 0xFF} written as {@code 0xFF}.</p>
     */
    private static String labelTextOf(SwitchCase label, String source) {
        String text = FixContext.text(label, source).trim();
        return text.endsWith(":") ? text.substring(0, text.length() - 1).trim() : text;
    }

    /** The group's statements without the {@code break} that was only holding the colon form together. */
    private static List<Statement> stripTrailingBreak(List<Statement> body) {
        if (body.isEmpty()) return body;
        Statement last = body.get(body.size() - 1);
        if (last instanceof BreakStatement && ((BreakStatement) last).getLabel() == null) {
            return new ArrayList<>(body.subList(0, body.size() - 1));
        }
        return body;
    }

    private static boolean leaves(Statement last) {
        return last instanceof BreakStatement || last instanceof ReturnStatement
                || last instanceof ThrowStatement || last instanceof ContinueStatement;
    }
}
