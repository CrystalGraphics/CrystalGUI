package com.crystalgui.language.java.fix.edit;

import com.crystalgui.text.Change;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The name to <em>write</em> for a type, and the imports that writing it will need.
 *
 * <h3>Why this exists instead of {@code ImportRewrite}</h3>
 *
 * <p>JDT's own answer to this question needs the Java model and refuses without it — see
 * {@link Rewrites}. So a correction that puts a type name into the file asks here instead: it hands over
 * a qualified name, gets back the shortest form that will resolve, and at the end takes whatever import
 * insertions that cost. Every T3 fix that names a type goes through this, which is the point — one place
 * decides that {@code java.lang.String} is written bare, that a same-package type is written bare, that
 * an already-imported type is written bare, and that a name which would clash with something visible is
 * written in full rather than imported into a conflict.</p>
 *
 * <h3>Nested types, from a string</h3>
 *
 * <p>Callers mostly hold a qualified name rather than a binding — ECJ's own arguments are strings — and
 * {@code java.util.Map.Entry} does not say where the package stops. The convention that package segments
 * are lower-case and type names are not is what everything else that reads Java by eye relies on, so it
 * is what this relies on: the first segment starting with an upper-case letter is the top-level type,
 * that is what gets imported, and the rest is written dotted after it.</p>
 */
public final class ImportPlan {

    private final CompilationUnit unit;
    private final String source;
    private final String unitPackage;
    /** Simple names that already mean something in this file — imported, declared, or on-demand. */
    private final Set<String> taken = new HashSet<>();
    private final Set<String> onDemand = new HashSet<>();
    private final Set<String> imported = new HashSet<>();
    private final TreeSet<String> needed = new TreeSet<>();

    public ImportPlan(CompilationUnit unit, String source) {
        this.unit = unit;
        this.source = source;
        this.unitPackage = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName();
        for (Object each : unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) each;
            if (declaration.isStatic()) continue;
            String name = declaration.getName().getFullyQualifiedName();
            if (declaration.isOnDemand()) {
                onDemand.add(name);
            } else {
                imported.add(name);
                taken.add(name.substring(name.lastIndexOf('.') + 1));
            }
        }
        unit.accept(new ASTVisitor() {
            @Override public void preVisit(ASTNode node) {
                if (node instanceof AbstractTypeDeclaration) {
                    taken.add(((AbstractTypeDeclaration) node).getName().getIdentifier());
                }
            }
        });
    }

    /**
     * What to write for {@code qualifiedName}, recording an import if writing it short needs one.
     *
     * <p>Falls back to the full name rather than importing into a clash: if a different {@code List} is
     * already imported or declared here, {@code java.awt.List} is written in full, because an import
     * that shadows a name in use is a fix that compiles and changes what the rest of the file means.</p>
     */
    public String nameFor(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isEmpty()) return qualifiedName;
        int split = topLevelTypeStart(qualifiedName);
        if (split <= 0) return qualifiedName;                       // no package: already as short as it gets
        String packageName = qualifiedName.substring(0, split - 1);
        String typePath = qualifiedName.substring(split);           // Outer or Outer.Inner
        int dot = typePath.indexOf('.');
        String topLevel = dot < 0 ? typePath : typePath.substring(0, dot);
        String topLevelQualified = packageName + "." + topLevel;

        if (imported.contains(topLevelQualified) || needed.contains(topLevelQualified)) return typePath;
        if (packageName.equals("java.lang") || packageName.equals(unitPackage)
                || onDemand.contains(packageName)) {
            return typePath;
        }
        if (taken.contains(topLevel)) return qualifiedName;         // would clash -- write it in full
        needed.add(topLevelQualified);
        taken.add(topLevel);
        return typePath;
    }

    /** The insertions the names handed out so far require — empty if none did. */
    public List<Change> changes() {
        if (needed.isEmpty()) return List.of();
        StringBuilder text = new StringBuilder();
        for (String each : needed) text.append("import ").append(each).append(";\n");
        return List.of(Change.insert(ImportRegion.insertOffset(unit, source), text.toString()));
    }

    /** Index of the first segment that starts with an upper-case letter, or 0 if there is no package. */
    private static int topLevelTypeStart(String qualifiedName) {
        int start = 0;
        while (start < qualifiedName.length()) {
            int end = qualifiedName.indexOf('.', start);
            if (end < 0) end = qualifiedName.length();
            if (end > start && Character.isUpperCase(qualifiedName.charAt(start))) return start;
            start = end + 1;
        }
        return 0;
    }
}
