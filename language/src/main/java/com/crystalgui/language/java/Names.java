package com.crystalgui.language.java;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * <b>A name for something that does not have one yet</b> — shared by every correction that declares a
 * variable the author did not.
 *
 * <p>Extracted when the second caller appeared. It is three lines of judgement and one real trap, and the
 * trap is not obvious: <b>a type name is not always a legal variable name</b>. {@code int} lowercases to
 * {@code int}, so the first version of "Introduce variable" produced {@code int int = getSize();}, which
 * does not parse. Every primitive hits it and so does any type whose name happens to be a keyword.</p>
 */
final class Names {

    private Names() {
    }

    /** Reserved words a derived name must never be. */
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "var", "record", "yield", "sealed", "permits");

    /**
     * A legal, unused name derived from {@code base}, or from {@code type} when there is no base.
     *
     * @param base  a preferred stem — a called method's name, say — or null
     * @param taken names already in use, which the result will not be one of
     */
    static String derive(String base, ITypeBinding type, Set<String> taken) {
        String stem = base == null || base.isEmpty() ? fromType(type) : base;
        if (stem.isEmpty() || KEYWORDS.contains(stem) || !Character.isJavaIdentifierStart(stem.charAt(0))) {
            stem = "value";
        }
        String name = stem;
        for (int n = 1; taken.contains(name); n++) name = stem + n;
        return name;
    }

    /**
     * A name from a type — the conventional single letter for a primitive, the lowercased name else.
     *
     * <p>{@code i} for an {@code int} is what everybody writes and what IntelliJ generates; it also
     * sidesteps the keyword problem for the eight types that have it.</p>
     */
    static String fromType(ITypeBinding type) {
        if (type == null) return "value";
        if (type.isPrimitive()) {
            switch (type.getName()) {
                case "int":     return "i";
                case "long":    return "l";
                case "double":  return "d";
                case "float":   return "f";
                case "boolean": return "flag";
                case "char":    return "c";
                case "byte":    return "b";
                case "short":   return "s";
                default:        return "value";
            }
        }
        return lower(type.getErasure().getName());
    }

    /** {@code getSize} → {@code size}: the stem people actually want from an accessor's name. */
    static String fromAccessor(String method) {
        for (String prefix : new String[] {"get", "is", "to", "as"}) {
            if (method.length() > prefix.length() && method.startsWith(prefix)
                    && Character.isUpperCase(method.charAt(prefix.length()))) {
                return lower(method.substring(prefix.length()));
            }
        }
        return method;
    }

    static String lower(String name) {
        if (name.isEmpty()) return name;
        String cleaned = name.replace("[]", "s");
        return Character.toLowerCase(cleaned.charAt(0)) + cleaned.substring(1);
    }

    /**
     * Every name <b>declared</b> anywhere inside {@code scope}.
     *
     * <p>Declarations only. Collecting every {@code SimpleName} instead sweeps up method names, type names
     * and field references — so extracting {@code s.trim()} once found {@code trim} already "taken" by the
     * call it was named after, and produced {@code trim1} for the only variable of that name in the file.</p>
     */
    static Set<String> declaredIn(ASTNode scope) {
        Set<String> taken = new LinkedHashSet<>();
        scope.accept(new ASTVisitor() {
            @Override public boolean visit(VariableDeclarationFragment fragment) {
                taken.add(fragment.getName().getIdentifier());
                return true;
            }

            @Override public boolean visit(SingleVariableDeclaration declared) {
                taken.add(declared.getName().getIdentifier());
                return true;
            }
        });
        return taken;
    }
}
