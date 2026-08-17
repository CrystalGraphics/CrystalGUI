package com.crystalgui.language.js;

import com.crystalgui.text.lang.TypeRef;

import org.mozilla.javascript.ast.ArrayLiteral;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.KeywordLiteral;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NewExpression;
import org.mozilla.javascript.ast.NumberLiteral;
import org.mozilla.javascript.ast.ObjectLiteral;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.ParenthesizedExpression;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.RegExpLiteral;
import org.mozilla.javascript.ast.StringLiteral;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What an expression's value is, as far as the syntax can say — the {@code InferenceTier}.
 *
 * <h3>The ordinary answer, and deliberately a shallow one</h3>
 *
 * <p>A literal is its own type; {@code new X()} is an X; a function expression is a function. That is
 * nearly all of it, and it is nearly all that is knowable without executing the program — which is what
 * the tier above this one (the live scope) does instead. There is no flow analysis here and there should
 * not be: JavaScript's types change under assignment, so an inference that tried to follow them would be
 * confidently wrong exactly where a dynamic language is hardest, and the honest answer for "I cannot say"
 * is null.</p>
 *
 * <h3>The Java half is the part that earns its keep</h3>
 *
 * <p>{@code new java.util.ArrayList()}, {@code Java.type("java.util.List")} and a bare
 * {@code java.util.List} are the three spellings of "reach a Java class", and all three are read here
 * into a binary name — after which the <em>Java</em> engine answers everything else about it. A package
 * chain is only treated as one when its root is not a declared name, so a local called {@code com}
 * shadows the package root exactly as it does at run time.</p>
 */
final class RhinoInference {

    /** @see RhinoGlobals#PACKAGE_ROOTS — one definition, because three of them had already drifted. */
    private static final Set<String> PACKAGE_ROOTS = RhinoGlobals.PACKAGE_ROOTS;

    private RhinoInference() {
    }

    /**
     * The type of {@code expression}, or null when the syntax does not say.
     *
     * @param isDeclared answers whether a name is declared in this file, so a local named {@code com}
     *                   shadows the package root rather than being read as one
     */
    @Nullable
    static TypeRef typeOf(@Nullable AstNode expression, Predicate<String> isDeclared) {
        AstNode node = unwrap(expression);
        if (node == null) return null;

        // BY CLASS, and for the keywords by TEXT -- never by a Token constant. Those are inlined at
        // compile time and the bands renumbered them, so `getType() == Token.TRUE` is true for a NUMBER
        // literal on band 11+. Measured, not feared. @see RhinoTokens
        if (node instanceof StringLiteral) return JsTypeRef.js(JsTypeRef.STRING);
        if (node instanceof NumberLiteral) return JsTypeRef.js(JsTypeRef.NUMBER);
        if (node instanceof RegExpLiteral) return JsTypeRef.js(JsTypeRef.REGEXP);
        if (node instanceof ArrayLiteral) return JsTypeRef.js(JsTypeRef.ARRAY);
        // AN OBJECT LITERAL CARRIES ITS KEYS, so `var o = { a: 1 }; o.` can list them without a run.
        // Statically that is everything knowable about the object, and it is the one JS shape whose members
        // are written down in the file.
        if (node instanceof ObjectLiteral) return JsTypeRef.object(keysOf(node));
        if (node instanceof FunctionNode) return JsTypeRef.js(JsTypeRef.FUNCTION);

        if (node instanceof KeywordLiteral) {
            String keyword = RhinoTokens.keywordOf(node);
            if ("true".equals(keyword) || "false".equals(keyword)) {
                return JsTypeRef.js(JsTypeRef.BOOLEAN);
            }
            // `null` IS A TYPE AND `undefined` IS NOT A KEYWORD LITERAL -- it parses as an ordinary name,
            // which is a JavaScript oddity rather than a gap here, and the live tier types it after a run.
            return "null".equals(keyword) ? JsTypeRef.js(JsTypeRef.NULL) : null;
        }

        if (node instanceof NewExpression) {
            // `new java.util.ArrayList()` is an INSTANCE; the target names the class.
            String created = javaNameOf(((NewExpression) node).getTarget(), isDeclared);
            return created == null ? null : JsTypeRef.javaInstance(created);
        }
        if (node instanceof FunctionCall) {
            String named = javaTypeCall((FunctionCall) node);
            // `Java.type(...)` answers the CLASS OBJECT, whose members are the statics -- not an instance.
            return named == null ? null : JsTypeRef.javaClass(named);
        }
        // A bare `java.util.List` is the class object too, by the same rule.
        String chain = javaNameOf(node, isDeclared);
        return chain == null ? null : JsTypeRef.javaClass(chain);
    }

    /** The property names an object literal declares, in source order — what {@code o.} should list. */
    static List<String> keysOf(@Nullable AstNode expression) {
        AstNode node = unwrap(expression);
        if (!(node instanceof ObjectLiteral)) return List.of();
        List<String> keys = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ObjectProperty property : ((ObjectLiteral) node).getElements()) {
            String key = keyOf(property);
            if (key != null && seen.add(key)) keys.add(key);
        }
        return keys;
    }

    @Nullable
    private static String keyOf(ObjectProperty property) {
        // THE LEFT NODE BY POSITION, never `getLeft()` -- see RhinoScopes: the two bands disagree about
        // ObjectProperty's supertype, so the accessor that compiles here throws NoSuchMethodError there.
        AstNode left = leftOf(property);
        if (left instanceof Name) return ((Name) left).getIdentifier();
        if (left instanceof StringLiteral) return ((StringLiteral) left).getValue();
        if (left instanceof NumberLiteral) return ((NumberLiteral) left).getValue();
        return null;
    }

    /**
     * An object property's key node — the first thing it visits, which is not the same as its first child.
     *
     * <p>Two accessors are unavailable and one is a trap. {@code getLeft()} is declared on a different
     * supertype in the two Rhino versions we ship, so a call compiled against band 8 throws
     * {@code NoSuchMethodError} on band 11+. And {@code getFirstChild()} answers <b>null</b> here, because
     * on the band we run against a property's key and value are <em>fields</em> rather than entries in the
     * generic child list — so the obvious structural reading found no key for any literal in any file, and
     * every {@code o.} offered the prototype's members and none of the object's own.</p>
     *
     * <p>{@code visit} is overridden per node type and reaches the fields whichever way they are stored,
     * and it goes key before value. That is the one reading that is true on both bands.</p>
     */
    @Nullable
    private static AstNode leftOf(ObjectProperty property) {
        AstNode[] key = new AstNode[1];
        property.visit(visited -> {
            if (key[0] == null && visited != property) key[0] = visited;
            return key[0] == null;
        });
        return key[0];
    }

    // ── Java names ──────────────────────────────────────────────────────────────────────────────

    /**
     * The binary name a {@code PropertyGet} chain spells, or null when it is not a package chain.
     *
     * <p>{@code java.util.ArrayList} → {@code java.util.ArrayList}; {@code Packages.mymod.Thing} →
     * {@code mymod.Thing}, because {@code Packages} is the escape hatch and not part of the name.</p>
     */
    @Nullable
    static String javaNameOf(@Nullable AstNode node, Predicate<String> isDeclared) {
        AstNode at = unwrap(node);
        List<String> segments = new ArrayList<>();
        while (at instanceof PropertyGet) {
            PropertyGet get = (PropertyGet) at;
            Name property = get.getProperty();
            if (property == null || property.getIdentifier() == null) return null;
            segments.add(0, property.getIdentifier());
            at = unwrap(get.getTarget());
        }
        if (!(at instanceof Name) || segments.isEmpty()) return null;
        String root = ((Name) at).getIdentifier();
        if (root == null || !PACKAGE_ROOTS.contains(root)) return null;
        // A DECLARED NAME SHADOWS THE PACKAGE ROOT, exactly as it does at run time: `var com = {…}` makes
        // `com.foo` an ordinary property read and not a class reference.
        if (isDeclared != null && isDeclared.test(root)) return null;
        if (!"Packages".equals(root)) segments.add(0, root);
        // A LOWER-CASE LAST SEGMENT IS STILL A PACKAGE, not a class: `java.util` on its own names a
        // package, and offering it as a type would put a class's member list on a namespace.
        String last = segments.get(segments.size() - 1);
        if (last.isEmpty() || !Character.isUpperCase(last.charAt(0))) return null;
        return String.join(".", segments);
    }

    /** The class name inside {@code Java.type("a.b.C")}, or null when this is any other call. */
    @Nullable
    static String javaTypeCall(@Nullable AstNode node) {
        AstNode at = unwrap(node);
        if (!(at instanceof FunctionCall)) return null;
        FunctionCall call = (FunctionCall) at;
        AstNode target = unwrap(call.getTarget());
        if (!(target instanceof PropertyGet)) return null;
        PropertyGet get = (PropertyGet) target;
        AstNode receiver = unwrap(get.getTarget());
        if (!(receiver instanceof Name) || !"Java".equals(((Name) receiver).getIdentifier())) return null;
        if (get.getProperty() == null || !"type".equals(get.getProperty().getIdentifier())) return null;
        List<AstNode> arguments = call.getArguments();
        if (arguments == null || arguments.isEmpty()) return null;
        AstNode first = unwrap(arguments.get(0));
        // ONLY A STRING LITERAL. `Java.type(name)` with a variable is a real and ordinary thing to write,
        // and its value is not knowable here -- answering null is the honest result, and the live tier
        // will know it after a run.
        if (!(first instanceof StringLiteral)) return null;
        String value = ((StringLiteral) first).getValue();
        return value == null || value.isEmpty() ? null : value;
    }

    /** Whether this node is the {@code Java} of a {@code Java.type(…)} call — for colouring it. */
    static boolean isJavaTypeCall(@Nullable AstNode node) {
        return javaTypeCall(node) != null;
    }

    @Nullable
    private static AstNode unwrap(@Nullable AstNode node) {
        AstNode at = node;
        while (at instanceof ParenthesizedExpression) {
            at = ((ParenthesizedExpression) at).getExpression();
        }
        return at;
    }
}
