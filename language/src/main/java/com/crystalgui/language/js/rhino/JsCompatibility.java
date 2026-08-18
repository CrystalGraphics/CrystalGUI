package com.crystalgui.language.js.rhino;

import com.crystalgui.language.js.rhino.resolve.LineIndex;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;

import org.mozilla.javascript.ast.ArrayLiteral;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.Comment;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.ObjectLiteral;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.StringLiteral;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * <b>§10.3b — what your Rhino takes and a Java 8 player's does not.</b>
 *
 * <h3>The problem this exists for</h3>
 *
 * <p>A modder on Java 17 writes {@code player?.name ?? 'nobody'}, their Rhino parses it, their editor
 * says nothing, and it fails to load for every player on 1.7.10. Six constructs sit in that gap,
 * measured rather than listed: <b>default parameters, array spread, destructuring defaults, computed
 * properties, optional chaining and nullish coalescing</b> — accepted by 1.9.1 and refused by
 * 1.7.15.1.</p>
 *
 * <h3>Why this cannot simply ask band 8's parser</h3>
 *
 * <p>Because those jars are not there. A deployment ships <em>one</em> band, so the obvious
 * implementation — parse it again with the target's Rhino and report what it says — is available in a
 * test and nowhere else. The answer therefore travels as <b>data</b>: band 8's probe output ships as a
 * resource, and the host hands this the set of construct names that band refused. Which is why what
 * arrives here is a {@code Set<String>} and not a band, a version or an engine.</p>
 *
 * <h3>Detected from TEXT over located nodes, never from new accessors</h3>
 *
 * <p>The tempting implementation is {@code FunctionNode.getDefaultParams()} and friends. This module
 * <em>compiles</em> against band 8's Rhino and <em>runs</em> against the host's, and that gap has now
 * produced four distinct failures — an inlined {@code Token} constant that renumbered, two accessors
 * that exist on one band and throw {@code NoSuchMethodError} on the other, and {@code getFirstChild()}
 * answering <b>null</b> because a node's parts are fields rather than child-list entries. So the AST is
 * used only to <em>locate</em> a candidate, and the question is asked of the source it covers.</p>
 *
 * <p>It also removes a compile-time obstacle that would otherwise decide the design: an accessor for a
 * construct band 8 refuses is, fairly often, an accessor band 8's jar does not have.</p>
 *
 * <h3>A warning, never an error, and only ever upward</h3>
 *
 * <p>The code is valid where it was written. The finding is about somewhere else, so it reads like one
 * — and it only fires when the target is <em>older</em> than the host, because a host that already
 * refuses the construct has reported a syntax error and does not need telling twice.</p>
 */
public final class JsCompatibility {

    /** The probe's own key for each construct — the same names {@code rhino-8.properties} uses. */
    static final String DEFAULT_PARAMS = "defaultParams";
    static final String DESTRUCTURING_DEFAULTS = "destructuringDefaults";
    static final String SPREAD_ARRAY = "spreadArray";
    static final String COMPUTED_PROPERTY = "computedProperty";
    static final String OPTIONAL_CHAINING = "optionalChaining";
    static final String NULLISH_COALESCING = "nullishCoalescing";

    private JsCompatibility() {
    }

    /**
     * One warning per use of a construct {@code refusedByTarget} names.
     *
     * @param refusedByTarget probe keys the target band refuses — {@code optionalChaining} and the rest,
     *                        bare, without the {@code syntax.} prefix. Empty means no target is set and
     *                        nothing is reported, which is the default.
     * @param targetLabel     how to name the target to the author: {@code "Java 8"}
     */
    public static List<Diagnostic> warningsIn(@Nullable AstRoot root, String source, LineIndex lines,
                                              Set<String> refusedByTarget, String targetLabel) {
        if (root == null || refusedByTarget == null || refusedByTarget.isEmpty()) return List.of();
        List<Finding> found = new ArrayList<>();
        Spans literal = literalSpansOf(root, source);

        if (refusedByTarget.contains(OPTIONAL_CHAINING)) {
            markOperator(found, source, lines, literal, "?.", "optional chaining ('?.')", targetLabel);
        }
        if (refusedByTarget.contains(NULLISH_COALESCING)) {
            markOperator(found, source, lines, literal, "??", "nullish coalescing ('??')", targetLabel);
        }
        markNodes(found, root, source, lines, refusedByTarget, targetLabel);
        // IN SOURCE ORDER, because two passes contributed and the Problems panel lists them as given.
        found.sort((a, b) -> Integer.compare(a.at, b.at));
        List<Diagnostic> problems = new ArrayList<>(found.size());
        for (Finding finding : found) problems.add(finding.diagnostic);
        return problems;
    }

    /** A diagnostic and the offset it was found at — kept because a TextPoint does not sort. */
    private static final class Finding {
        final int at;
        final Diagnostic diagnostic;

        Finding(int at, Diagnostic diagnostic) {
            this.at = at;
            this.diagnostic = diagnostic;
        }
    }

    // ── The two operators, found in the text ────────────────────────────────────────────────────

    /**
     * Every occurrence of {@code operator} that is really one.
     *
     * <p>Skipping string and comment spans is the whole of the care needed here, and it is not
     * optional: {@code "why??"} in a message and {@code // TODO?.} in a comment are both ordinary, and
     * warning about them would train the author to ignore the warning.</p>
     *
     * <p><b>{@code ?.} in a ternary is the one shape text cannot separate</b> — {@code a ? .5 : 1} is a
     * conditional whose consequent is a number. Vanishingly rare (nobody writes a leading-dot literal
     * after a {@code ?} with no space) and the failure is a spurious warning rather than a missed one,
     * which is the right way round: the author reads it, sees the line is fine, and the cost is one
     * glance rather than a script that will not load.</p>
     */
    private static void markOperator(List<Finding> into, String source, LineIndex lines,
                                     Spans literal, String operator, String description,
                                     String targetLabel) {
        int at = source.indexOf(operator);
        while (at >= 0) {
            if (!literal.covers(at)) {
                into.add(new Finding(at, warning(lines, at, at + operator.length(), description, targetLabel)));
            }
            at = source.indexOf(operator, at + operator.length());
        }
    }

    // ── The four that need a node to point at ───────────────────────────────────────────────────

    private static void markNodes(List<Finding> into, AstRoot root, String source, LineIndex lines,
                                  Set<String> refused, String targetLabel) {
        boolean defaults = refused.contains(DEFAULT_PARAMS)
                || refused.contains(DESTRUCTURING_DEFAULTS);
        boolean spread = refused.contains(SPREAD_ARRAY);
        boolean computed = refused.contains(COMPUTED_PROPERTY);
        if (!defaults && !spread && !computed) return;

        root.visit(node -> {
            if (defaults && node instanceof FunctionNode) {
                int[] span = parameterSpanOf((FunctionNode) node, source);
                if (span != null && hasAssignment(source, span[0], span[1])) {
                    into.add(new Finding(span[0], warning(lines, span[0], span[1],
                            "a default value in a parameter list", targetLabel)));
                }
            }
            if (spread && node instanceof ArrayLiteral) {
                int at = source.indexOf("...", node.getAbsolutePosition());
                int end = node.getAbsolutePosition() + node.getLength();
                if (at >= 0 && at < end) {
                    into.add(new Finding(at, warning(lines, at, at + 3, "spread in an array literal", targetLabel)));
                }
            }
            if (computed && node instanceof ObjectLiteral) {
                for (ObjectProperty property : ((ObjectLiteral) node).getElements()) {
                    int at = property.getAbsolutePosition();
                    if (at >= 0 && at < source.length() && source.charAt(at) == '[') {
                        into.add(new Finding(at, warning(lines, at, at + 1, "a computed property name", targetLabel)));
                    }
                }
            }
            return true;
        });
    }

    /**
     * The {@code (...)} of a function's parameter list, or null.
     *
     * <p>Measured off the source rather than off the parameter nodes, because an empty list has no nodes
     * to measure and a <em>destructured</em> one has a node whose span is the pattern rather than the
     * list. Both are cases this has to answer for.</p>
     */
    @Nullable
    private static int[] parameterSpanOf(FunctionNode function, String source) {
        int from = function.getAbsolutePosition();
        int limit = Math.min(source.length(), from + Math.max(0, function.getLength()));
        int open = source.indexOf('(', from);
        if (open < 0 || open >= limit) return null;
        int depth = 0;
        for (int at = open; at < limit; at++) {
            char c = source.charAt(at);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) return new int[] {open, at + 1};
            }
        }
        return null;
    }

    /** A bare {@code =} — not {@code ==}, {@code ===}, {@code =>}, {@code <=}, {@code >=} or {@code !=}. */
    private static boolean hasAssignment(String source, int from, int to) {
        for (int at = from; at < to; at++) {
            if (source.charAt(at) != '=') continue;
            char before = at > from ? source.charAt(at - 1) : ' ';
            char after = at + 1 < to ? source.charAt(at + 1) : ' ';
            if (before == '=' || before == '!' || before == '<' || before == '>') continue;
            if (after == '=' || after == '>') continue;
            return true;
        }
        return false;
    }

    private static Diagnostic warning(LineIndex lines, int from, int to, String description,
                                      String targetLabel) {
        return new Diagnostic(lines.pointAt(from), lines.pointAt(to), DiagnosticSeverity.WARNING,
                description + " is not supported on " + targetLabel + " hosts, which run an older Rhino",
                RhinoProblemPolicy.OWNER, null);
    }

    // ── Where not to look ───────────────────────────────────────────────────────────────────────

    /**
     * The string and comment spans of a file, so a text search can step over them.
     *
     * <p>Both come off the tree rather than out of a hand-written scanner: Rhino has already lexed the
     * file correctly, escapes and nesting and all, and a second lexer here would be a second thing to
     * get wrong about exactly the inputs that are hard.</p>
     */
    private static Spans literalSpansOf(AstRoot root, String source) {
        Spans spans = new Spans();
        root.visit(node -> {
            if (node instanceof StringLiteral) {
                spans.add(node.getAbsolutePosition(), node.getAbsolutePosition() + node.getLength());
            }
            return true;
        });
        // COMMENTS ARE NOT IN THE TREE WALK -- they hang off the root separately, which is why a visitor
        // alone finds none of them and every `// TODO?.` would be a warning.
        if (root.getComments() != null) {
            for (Comment comment : root.getComments()) {
                spans.add(comment.getAbsolutePosition(),
                        comment.getAbsolutePosition() + comment.getLength());
            }
        }
        return spans;
    }

    /** Sorted non-overlapping-enough ranges, asked only "does anything cover this offset". */
    private static final class Spans {

        private final TreeMap<Integer, Integer> byStart = new TreeMap<>();

        void add(int from, int to) {
            if (to <= from) return;
            Integer existing = byStart.get(from);
            byStart.put(from, existing == null ? to : Math.max(existing, to));
        }

        boolean covers(int offset) {
            java.util.Map.Entry<Integer, Integer> at = byStart.floorEntry(offset);
            return at != null && offset < at.getValue();
        }
    }
}
