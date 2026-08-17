package com.crystalgui.language.js;

import org.mozilla.javascript.ast.AstNode;

import javax.annotation.Nullable;

/**
 * Asking what a node <em>is</em> without comparing a {@code Token} constant — because those cannot be
 * compared across bands.
 *
 * <h3>The trap, measured</h3>
 *
 * <p>{@code org.mozilla.javascript.Token}'s members are {@code public static final int}, so <b>javac
 * inlines them into our class files</b>. This module compiles against the band-8 Rhino (the oldest, per
 * the pinning rule) and runs against whichever band the host is on — and the two renumbered the set:</p>
 *
 * <pre>
 *   compiled against 1.7.15.1        observed at runtime on 1.9.1
 *   Token.NUMBER = 40                NumberLiteral.getType()  = 45
 *   Token.TRUE   = 45                KeywordLiteral(true)     = 51
 *   Token.STRING = 41                StringLiteral.getType()  = 46
 * </pre>
 *
 * <p>So {@code node.getType() == Token.TRUE} is <em>true for a number literal</em> on band 11+ — and
 * there is no error to notice. This is the {@code ObjectProperty.getLeft()} divergence again, in its
 * nastier form: that one threw {@code NoSuchMethodError} and was found in a minute, while this one
 * silently answers the wrong question. It reached the shipped code once already, in
 * {@code RhinoScopes.isAssignmentTarget}, where {@code Token.INC}/{@code Token.DEC} meant a
 * {@code count++} stopped counting as a reassignment on exactly the bands most users are on.</p>
 *
 * <h3>The rule</h3>
 *
 * <p><b>Never compare an {@code AstNode}'s type against a {@code Token} constant.</b> Ask the node's
 * <em>class</em> where one exists ({@code NumberLiteral}, {@code VariableDeclaration}, {@code AstRoot}),
 * and ask its <em>text</em> where it does not — which is what this class is for. A {@code Token.*}
 * constant may still be passed <em>to</em> Rhino in the same call it was read from, and enum members
 * ({@code Token.CommentType.JSDOC}) are references rather than inlined ints and are safe.</p>
 */
final class RhinoTokens {

    private RhinoTokens() {
    }

    /** {@code true}, {@code false}, {@code null}, {@code this} … — the keyword a literal node spells. */
    @Nullable
    static String keywordOf(@Nullable AstNode node) {
        String text = textOf(node);
        return text == null || text.isEmpty() ? null : text;
    }

    /**
     * Whether {@code parent} is exactly {@code name++} / {@code ++name} — a write to that one name.
     *
     * <p><b>Asked of the text, and of the whole node.</b> Three things forced it. The operator cannot be
     * compared against {@code Token.INC} for the reason above. The node's <em>class</em> cannot be tested
     * either — a {@code UnaryExpression} check does not match {@code a++} at runtime on the band this
     * runs against, and whatever class does hold it is not on band 8's Rhino to name at compile time. And
     * a looser text test on the parent alone is wrong in a way that is easy to miss: in {@code a + b++}
     * the parent of {@code a} is the whole sum, whose source also ends in {@code ++}, so {@code a} would
     * be reported as mutated. Requiring the parent's source to be precisely this name plus the operator
     * says what is meant with nothing left to guess.</p>
     */
    static boolean isIncrementOrDecrementOf(@Nullable AstNode parent, @Nullable String name) {
        if (parent == null || name == null || name.isEmpty()) return false;
        String text = textOf(parent);
        if (text == null) return false;
        return text.equals(name + "++") || text.equals(name + "--")
                || text.equals("++" + name) || text.equals("--" + name);
    }

    /**
     * A node's own source, or null.
     *
     * <p>Guarded, because {@code toSource} walks the subtree and a tree recovered from a syntax error can
     * hold a node it cannot render — and an analyser that threw while colouring a broken file would fail
     * in exactly the state it exists to be useful in.</p>
     */
    @Nullable
    private static String textOf(@Nullable AstNode node) {
        if (node == null) return null;
        try {
            String source = node.toSource();
            return source == null ? null : source.trim();
        } catch (RuntimeException | StackOverflowError unrenderable) {
            return null;
        }
    }
}
