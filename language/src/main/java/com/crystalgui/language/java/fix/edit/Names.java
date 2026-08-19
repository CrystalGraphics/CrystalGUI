package com.crystalgui.language.java.fix.edit;

import com.crystalgui.text.DerivedNames;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * <b>Java's half of naming something the author has not named</b> — the part that needs a binding.
 *
 * <p>The mechanism moved to {@link DerivedNames} in {@code core} when JavaScript's fix catalog grew an
 * "extract to local" of its own: deduplication, the accessor stem and the lowercase convention know
 * nothing about types, so two copies of them would have been two copies to keep in step. What stays here
 * is what only a resolved type can answer, and <b>the reserved-word set, which is deliberately not
 * shared</b> — {@code int} is a Java keyword and an ordinary JavaScript name, {@code function} the
 * reverse, so one merged list would refuse legal names in both languages to be safe in one.</p>
 *
 * <p>The trap that made this class worth extracting in the first place is still the reason
 * {@link DerivedNames#derive} takes a reserved set: <b>a type name is not always a legal variable
 * name</b>. {@code int} lowercases to {@code int}, so the first version of "Introduce variable" produced
 * {@code int int = getSize();}, which does not parse. Every primitive hits it, and so does any type whose
 * name happens to be a keyword.</p>
 */
public final class Names {

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
    public static String derive(String base, ITypeBinding type, Set<String> taken) {
        String stem = base == null || base.isEmpty() ? fromType(type) : base;
        return DerivedNames.derive(stem, taken, KEYWORDS);
    }

    /** @see DerivedNames#free */
    public static String free(Set<String> taken, String... stems) {
        return DerivedNames.free(taken, stems);
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

    /** @see DerivedNames#fromAccessor */
    public static String fromAccessor(String method) {
        return DerivedNames.fromAccessor(method);
    }

    /** @see DerivedNames#lower */
    public static String lower(String name) {
        return DerivedNames.lower(name);
    }

    /**
     * Every name <b>declared</b> anywhere inside {@code scope}.
     *
     * <p>Declarations only. Collecting every {@code SimpleName} instead sweeps up method names, type names
     * and field references — so extracting {@code s.trim()} once found {@code trim} already "taken" by the
     * call it was named after, and produced {@code trim1} for the only variable of that name in the file.</p>
     */
    public static Set<String> declaredIn(ASTNode scope) {
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
