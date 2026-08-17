package com.crystalgui.language.js;

import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.syntax.SyntaxToken;

import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.PropertyGet;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The colours a grammar cannot produce — every name drawn as what the scopes say it is.
 *
 * <h3>Only what needs an engine</h3>
 *
 * <p>The tree-sitter grammar already colours keywords, strings, numbers, comments and the shape of a
 * call. Restating any of that here would be work whose only effect is to overwrite an identical answer,
 * and worse: semantic tokens <b>replace</b> grammar tokens where they overlap rather than layering, so
 * a redundant token is a chance to be wrong about something the grammar had right.</p>
 *
 * <p>What is left is the set of distinctions that need resolved scopes, and it is exactly the set a
 * reader most wants:</p>
 *
 * <ul>
 *   <li><b>parameter vs local vs const</b> — nothing in the shape of {@code total} says which it is,
 *       and JavaScript has no type to hint with either</li>
 *   <li><b>reassigned</b> — a name written to after its declaration. Both reference editors draw this,
 *       and in a language with no {@code final} it is the only signal that a binding moves</li>
 *   <li><b>captured</b> — used from inside a nested function, which is what a closure <em>is</em>.
 *       The single most important thing to be able to see in JavaScript and the least visible</li>
 *   <li><b>unresolved</b> — a free name that is not a known global. In a language with no compiler
 *       this is the nearest thing to a typo check that exists before the script runs</li>
 * </ul>
 *
 * <h3>The vocabulary is the shared one</h3>
 *
 * <p>{@link SymbolKind#captureName()} for the kinds, and the dotted refinements — {@code
 * variable.parameter.reassigned}, {@code variable.captured} — that the Java engine already publishes.
 * A dotted capture also publishes under its general form, so a scheme that draws every variable alike
 * needs no entry and one that wants to distinguish a capture can. Inventing a second vocabulary here
 * would mean a scheme colours Java and JavaScript from different tables for the same idea.</p>
 */
final class RhinoSemanticTokens {

    private RhinoSemanticTokens() {
    }

    /**
     * The names a script may use without declaring them.
     *
     * <p><b>Read from the engine, never typed here.</b> Which globals exist differs per band — 1.9.1 has
     * {@code Proxy} and {@code Reflect} where 1.7.15.1 does not — so a list in this file would be wrong
     * on one of them, and wrong in the direction that matters: an unresolved-name mark on a name that
     * works. {@link RhinoGlobals} asks the running engine once.</p>
     */
    private static Set<String> globals() {
        return RhinoGlobals.names();
    }

    /**
     * Every token one parse can justify.
     *
     * @param hostBindings names the host put in scope — not declared in the file and not JavaScript's,
     *                     so without them every binding a mod offers would be marked unresolved
     */
    static List<SyntaxToken> of(@Nullable AstRoot root, RhinoScopes scopes, Set<String> hostBindings) {
        if (root == null) return List.of();
        List<SyntaxToken> tokens = new ArrayList<>();

        // DECLARATIONS FIRST, so a name is coloured where it is introduced as well as where it is used.
        for (RhinoScopes.Declaration declared : scopes.declarations()) {
            if (declared.offset < 0) continue;
            add(tokens, declared.offset, declared.length, captureFor(declared, false));
            for (int[] reference : declared.references) {
                add(tokens, reference[0], reference[1], captureFor(declared, true));
            }
        }

        // THEN THE FREE NAMES, which are the ones no scope claimed: a JavaScript global, something the
        // host bound, a Java package root, or a mistake. Only the last is worth a mark, and telling it
        // from the other three is the whole reason this needs an engine.
        Set<String> known = globals();
        for (Name free : scopes.freeNames()) {
            String name = free.getIdentifier();
            if (name == null || name.isEmpty()) continue;
            String capture;
            if (hostBindings.contains(name)) {
                capture = "variable.global";
            } else if (known.contains(name)) {
                // BUILTIN, and the distinction from a host binding is worth drawing: one is JavaScript
                // and travels everywhere, the other is this application's and does not.
                capture = "variable.builtin";
            } else if (isCallTarget(free)) {
                // LEFT TO markUnresolvedCalls, which marks the same span `function.unresolved`. Emitting
                // both put two tokens on one range under unrelated names, and which one painted was left
                // to order -- the exact overlap the engine's own rule says never to create, and it reads
                // as a colour-scheme bug rather than an ordering one because both names resolve.
                continue;
            } else {
                capture = "variable.unresolved";
            }
            add(tokens, free.getAbsolutePosition(), free.getLength(), capture);
        }

        // AND THE CALLS. A grammar can see `foo(` and colour it; what it cannot see is whether `foo`
        // resolved -- so this only marks a call whose callee is a plain name the scopes did NOT claim
        // and that is not known, leaving the grammar's answer alone everywhere else.
        markUnresolvedCalls(root, scopes, known, hostBindings, tokens);

        // AND THE JAVA REACHED FROM HERE, which is the half of this file a grammar cannot begin to see:
        // `java.util` is a PACKAGE and `java.util.ArrayList` is a TYPE, and the difference is a lookup
        // rather than a shape. Drawn as `module` and `type`, the same captures the Java engine publishes,
        // so one colour scheme answers for both languages.
        markJavaChains(root, scopes, tokens);

        // SORTED BY START, because the editor merges these into one per-row bucket and a consumer that
        // binary-searches an unsorted list silently misses ranges. The walk produces declaration order,
        // which is not document order.
        tokens.sort(Comparator.comparingInt(SyntaxToken::start));
        return tokens;
    }

    /**
     * The capture name for one declared symbol.
     *
     * <p>{@code atUse} distinguishes the declaration itself from a reference to it, because the two
     * carry different information: a capture is a fact about a <em>use</em> — the declaration is where
     * it was introduced, not where it escaped — while being reassigned is a fact about the binding and
     * belongs on both.</p>
     */
    private static String captureFor(RhinoScopes.Declaration declared, boolean atUse) {
        String base = declared.kind.captureName();
        // A CONST IS NEVER REASSIGNED and never captured-in-the-interesting-sense: it cannot change, so
        // neither refinement says anything a reader did not already know from the colour.
        if (declared.kind == SymbolKind.CONSTANT) return base;
        if (declared.reassigned) return base + ".reassigned";
        if (atUse && declared.captured) return "variable.captured";
        return base;
    }

    /** A call to a name nothing declared and nothing knows — the JavaScript typo check. */
    private static void markUnresolvedCalls(AstRoot root, RhinoScopes scopes, Set<String> known,
                                            Set<String> hostBindings, List<SyntaxToken> tokens) {
        root.visit(node -> {
            if (!(node instanceof FunctionCall)) return true;
            AstNode target = ((FunctionCall) node).getTarget();
            // A METHOD CALL IS NOT THIS QUESTION. `list.add(...)` resolves through the receiver, which
            // needs the type of `list` -- M10.6's work, and answering it from a name alone would mark
            // every method in the file.
            if (target instanceof PropertyGet || !(target instanceof Name)) return true;
            Name callee = (Name) target;
            String name = callee.getIdentifier();
            if (name == null || name.isEmpty()) return true;
            if (scopes.declarationOf(callee) != null) return true;
            if (known.contains(name) || hostBindings.contains(name)) return true;
            add(tokens, callee.getAbsolutePosition(), callee.getLength(), "function.unresolved");
            return true;
        });
    }

    /** Whether this name is the thing being called in {@code name(…)} — never {@code a.name(…)}. */
    private static boolean isCallTarget(Name name) {
        AstNode parent = name.getParent();
        return parent instanceof FunctionCall && ((FunctionCall) parent).getTarget() == name;
    }

    /**
     * The package segments and the type at the end of {@code java.util.ArrayList}.
     *
     * <p>Only the OUTERMOST chain in any expression: walking every {@code PropertyGet} would colour
     * {@code java}, {@code java.util} and {@code java.util.ArrayList} as three overlapping ranges, and
     * overlapping semantic tokens are decided by paint order rather than by intent.</p>
     */
    private static void markJavaChains(AstRoot root, RhinoScopes scopes, List<SyntaxToken> tokens) {
        root.visit(node -> {
            if (!(node instanceof PropertyGet)) return true;
            // NOT IF OUR PARENT IS ONE TOO -- that one is the outer chain and covers this.
            if (node.getParent() instanceof PropertyGet) return true;
            if (RhinoInference.javaNameOf(node, scopes::declaresAnywhere) == null) return true;

            // THE LAST SEGMENT IS THE TYPE; everything before it is the package it lives in.
            PropertyGet chain = (PropertyGet) node;
            Name type = chain.getProperty();
            if (type != null) add(tokens, type.getAbsolutePosition(), type.getLength(), "type");
            for (AstNode at = chain.getTarget(); at != null; ) {
                if (at instanceof PropertyGet) {
                    Name segment = ((PropertyGet) at).getProperty();
                    if (segment != null) {
                        add(tokens, segment.getAbsolutePosition(), segment.getLength(), "module");
                    }
                    at = ((PropertyGet) at).getTarget();
                } else {
                    if (at instanceof Name) add(tokens, at.getAbsolutePosition(), at.getLength(), "module");
                    break;
                }
            }
            return true;
        });
    }

    private static void add(List<SyntaxToken> tokens, int offset, int length, String capture) {
        if (offset < 0 || length <= 0) return;
        tokens.add(new SyntaxToken(offset, offset + length, capture));
    }
}
