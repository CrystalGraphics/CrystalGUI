package com.crystalgui.language.java;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;

/**
 * <b>How this file would have written that type</b> — the one answer every correction that puts a type
 * name into source reads.
 *
 * <p>Extracted from {@code CastCorrections} when the third caller appeared. A cast, a declaration and a
 * generated method signature are three different edits asking one question, and the question has enough
 * wrong answers to be worth having once: {@code getQualifiedName()} renders a parameterized type as
 * {@code java.util.List<java.lang.String>}, which compiles and is nobody's idea of source.</p>
 */
final class TypeNames {

    private TypeNames() {
    }

    /**
     * The type as this file may write it, or <b>null when it cannot be written at all</b>.
     *
     * <p>Built up part by part, each through {@link ImportPlan} separately, so the result is what a person
     * would have typed rather than what the binding prints.</p>
     *
     * <p>Null for anything not denotable — a capture, a wildcard, an intersection, an anonymous class.
     * There is no source form for these, so a fix that wrote one would not compile.</p>
     *
     * <p><b>A TYPE VARIABLE IS NOT WRITABLE EITHER, and the corpus is what said so:</b> three files offered
     * "Cast argument to 'E'" and each came back with one MORE error than it started with. A cast to a type
     * variable is erased, so it proves nothing at runtime and only silences the compiler — and it is
     * writable at all only where that variable is in scope, which a static method or a different type's
     * parameter is not. What failed in those cases was inference, and no amount of naming repairs
     * inference.</p>
     *
     * @param at where the name will be written, which decides whether a nested type needs qualifying
     */
    static String writtenName(ITypeBinding type, ImportPlan imports, ASTNode at) {
        if (type == null) return null;
        // A RECOVERED BINDING IS A NAME THE COMPILER COULD NOT RESOLVE, and it still answers
        // getQualifiedName() — so writing it produces a declaration against a type that does not exist.
        // ImplementCorrections met this first (five files, six to sixteen new errors each) and guarded its
        // own signatures; the corpus then found the identical fault in "Introduce variable", which is when
        // it became clear the rule belongs to the one thing that writes type names rather than to each
        // caller that happens to remember.
        if (type.isRecovered()) return null;
        if (type.isPrimitive()) return type.getName();
        if (type.isArray()) {
            String component = writtenName(type.getComponentType(), imports, at);
            return component == null ? null : component + "[]";
        }
        if (type.isCapture() || type.isWildcardType() || type.isIntersectionType()
                || type.isAnonymous() || type.isNullType()) {
            return null;
        }
        if (type.isTypeVariable()) return null;
        if (type.isParameterizedType()) {
            String raw = imports.nameFor(type.getErasure().getQualifiedName());
            StringBuilder built = new StringBuilder(raw).append('<');
            ITypeBinding[] arguments = type.getTypeArguments();
            for (int i = 0; i < arguments.length; i++) {
                String argument = writtenName(arguments[i], imports, at);
                if (argument == null) return null;
                if (i > 0) built.append(", ");
                built.append(argument);
            }
            return built.append('>').toString();
        }
        if (inScopeUnqualified(type, at)) return type.getName();
        String qualified = type.getQualifiedName();
        return qualified.isEmpty() ? null : imports.nameFor(qualified);
    }

    /**
     * Whether a NESTED type's simple name resolves where it is about to be written.
     *
     * <p>{@link ImportPlan} shortens by package, which is the right rule for a type somewhere else and no
     * rule at all for one declared in this file: {@code Script.Dog} has no package to strip, so it came out
     * qualified — correct, compiling, and not what anyone writing by hand would put. The scope that makes
     * the short form legal is lexical, so it is answered lexically: the simple name resolves when the site
     * sits inside the type that declares it, or inside the type itself.</p>
     *
     * <p>A sibling top-level class in the same file is deliberately <em>not</em> in that scope and keeps the
     * qualified form, which is what makes this a check rather than an assumption that a file has one type.</p>
     */
    private static boolean inScopeUnqualified(ITypeBinding type, ASTNode at) {
        ITypeBinding declaring = type.getDeclaringClass();
        if (declaring == null) return false;
        for (ASTNode walk = at; walk != null; walk = walk.getParent()) {
            if (!(walk instanceof AbstractTypeDeclaration)) continue;
            ITypeBinding enclosing = ((AbstractTypeDeclaration) walk).resolveBinding();
            if (enclosing != null && (enclosing.isEqualTo(declaring) || enclosing.isEqualTo(type))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A value of {@code type} that is always legal to write — {@code 0}, {@code false}, {@code null}.
     *
     * <p>What "add a return statement" and "initialise this variable" both need, and the reason they are
     * one family. Not a guess at what the author meant: it is the value the JVM would have used for a
     * field of that type, which is the only defensible default and is what both references write.</p>
     *
     * <p>{@code 0} covers {@code byte}, {@code short} and {@code char} as well, because a constant
     * expression narrows in an assignment context and a {@code return} is one.</p>
     */
    static String defaultValue(ITypeBinding type) {
        if (type == null || "void".equals(type.getName())) return null;
        if (!type.isPrimitive()) return "null";
        switch (type.getName()) {
            case "boolean": return "false";
            case "long":    return "0L";
            case "float":   return "0.0f";
            case "double":  return "0.0";
            default:        return "0";
        }
    }
}
