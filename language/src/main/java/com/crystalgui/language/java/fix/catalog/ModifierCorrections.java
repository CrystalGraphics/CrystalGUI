package com.crystalgui.language.java.fix.catalog;

import com.crystalgui.language.java.fix.Correction;
import com.crystalgui.language.java.fix.FixContext;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.List;

/**
 * One keyword added or taken away — {@code final} on something that is assigned, {@code abstract} on a
 * class that needs it or on a method that should not have it.
 *
 * <h3>Half the catalogue's modifier rows do not survive being measured, and that is the finding</h3>
 *
 * <p><b>"Make 'x' final" has no trigger at this floor.</b> The row was written from
 * {@code OuterLocalMustBeFinal}, which is a Java 7 problem: before 8, a local captured by an inner class
 * had to be <em>declared</em> final even when it was never reassigned. Java 8 accepts "effectively
 * final", so ECJ now complains only when the local really is reassigned — where inserting {@code final}
 * does not fix the code, it moves the error to the assignment. The fix for what ECJ actually reports is
 * a copy or a field, which is a refactoring rather than a keyword.</p>
 *
 * <p><b>"Make method static" stays off.</b> {@code methodCanBeStatic} is an option, and switching it on
 * marks nearly every helper method in a script — it is a style preference about a method that <em>could</em>
 * be static, not a defect. IntelliJ ships the same inspection disabled.</p>
 *
 * <p><b>"Change visibility" barely exists in one file.</b> Measured: a private member of a nested type
 * called from the enclosing class is <em>legal Java</em> and reports nothing, so the same-file case the
 * catalogue scoped this to is mostly empty. The case that does occur is a declaration in a jar, which is
 * where the catalogue already puts it — out of reach.</p>
 *
 * <h3>What is left, and where ECJ points for each</h3>
 *
 * <p>The three below are all reported as <b>errors</b> with no configuration, which is the other half of
 * why they are worth having: they are on code that does not compile, so the fix is not a tidy.</p>
 */
public final class ModifierCorrections {

    static final String REMOVE_FINAL = "java.modifier.removeFinal";
    static final String MAKE_ABSTRACT = "java.modifier.makeAbstract";
    static final String REMOVE_ABSTRACT = "java.modifier.removeAbstract";

    private ModifierCorrections() {
    }

    public static List<Correction> all() {
        return List.of(new RemoveFinal(), new MakeTypeAbstract(), new RemoveAbstract());
    }

    // ── Remove 'final' ──────────────────────────────────────────────────────────────────────────

    /**
     * <b>ECJ reports the ASSIGNMENT and the fix edits the DECLARATION</b>, which is the only one of these
     * three that has to travel. The name under the problem is resolved to its binding and the binding back
     * to the node that declares it — {@code findDeclaringNode}, so a field declared eighty lines up is
     * found without a search — and a declaration in another file simply is not there, which is the honest
     * answer for one.
     */
    private static final class RemoveFinal implements Correction {

        @Override public String id() {
            return REMOVE_FINAL;
        }

        @Override public int[] problems() {
            return new int[] {
                    IProblem.FinalFieldAssignment,
                    IProblem.NonBlankFinalLocalAssignment,
                    IProblem.DuplicateFinalLocalInitialization};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            SimpleName name = context.enclosing(problem, SimpleName.class);
            if (name == null) return;
            IBinding binding = name.resolveBinding();
            if (binding == null) return;
            ASTNode declared = context.unit().findDeclaringNode(binding);
            if (declared == null) return;

            // The fragment is what the binding names; the modifiers belong to the declaration around it.
            ASTNode owner = declared;
            while (owner != null && !(owner instanceof BodyDeclaration)
                    && !(owner instanceof VariableDeclarationStatement)) {
                owner = owner.getParent();
            }
            if (owner == null) return;
            if (!context.claim(REMOVE_FINAL + ":" + owner.getStartPosition())) return;

            Modifier keyword = modifier(owner, Modifier.ModifierKeyword.FINAL_KEYWORD);
            if (keyword == null) return;

            ASTRewrite rewrite = context.rewrite();
            rewrite.remove(keyword, null);
            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;
            out.add(context.preferredFix(REMOVE_FINAL, "Remove 'final' modifier", edit));
        }
    }

    // ── Make the class abstract ─────────────────────────────────────────────────────────────────

    /**
     * <b>Two problems, one answer.</b> An abstract method in a concrete class is reported twice — once on
     * the type ({@code AbstractMethodsInConcreteClass}) and once on the method
     * ({@code AbstractMethodInAbstractClass}) — and the same keyword fixes both. Keyed on both so the
     * action is there wherever the caret is, and claimed by the type's position so two problems in range
     * at once produce one row rather than two identical ones.
     */
    private static final class MakeTypeAbstract implements Correction {

        @Override public String id() {
            return MAKE_ABSTRACT;
        }

        @Override public int[] problems() {
            return new int[] {
                    IProblem.AbstractMethodsInConcreteClass,
                    IProblem.AbstractMethodInAbstractClass};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            // TWO ROUTES, because `enclosing` STOPS AT A BODY DECLARATION on purpose -- that guard is what
            // keeps an unused local from walking up and offering to delete the method around it. So the
            // type-side problem finds its TypeDeclaration directly, and the method-side one has to be
            // asked for its method first and then for that method's parent. Asking only the first way
            // silently answered for the type's own squiggle and not for the method's, which is the one the
            // caret is actually on.
            TypeDeclaration type = context.enclosing(problem, TypeDeclaration.class);
            if (type == null) {
                MethodDeclaration method = context.enclosing(problem, MethodDeclaration.class);
                if (method != null && method.getParent() instanceof TypeDeclaration) {
                    type = (TypeDeclaration) method.getParent();
                }
            }
            if (type == null || type.isInterface()) return;
            if (modifier(type, Modifier.ModifierKeyword.ABSTRACT_KEYWORD) != null) return;
            if (!context.claim(MAKE_ABSTRACT + ":" + type.getStartPosition())) return;

            ASTRewrite rewrite = context.rewrite();
            AST ast = context.unit().getAST();
            ListRewrite modifiers = rewrite.getListRewrite(type, TypeDeclaration.MODIFIERS2_PROPERTY);
            // LAST, so `public abstract class` reads the way Java is written -- an access modifier first.
            modifiers.insertLast(ast.newModifier(Modifier.ModifierKeyword.ABSTRACT_KEYWORD), null);

            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;
            out.add(context.preferredFix(MAKE_ABSTRACT,
                    "Make '" + type.getName().getIdentifier() + "' abstract", edit));
        }
    }

    // ── Remove 'abstract' from a method with a body ─────────────────────────────────────────────

    /**
     * The other way round: the method has a body, so the keyword is what is wrong. Removing the body would
     * also compile and would throw away what the author wrote, which is why this is the offer and that is
     * not.
     */
    private static final class RemoveAbstract implements Correction {

        @Override public String id() {
            return REMOVE_ABSTRACT;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.BodyForAbstractMethod};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            MethodDeclaration method = context.enclosing(problem, MethodDeclaration.class);
            if (method == null) return;
            Modifier keyword = modifier(method, Modifier.ModifierKeyword.ABSTRACT_KEYWORD);
            if (keyword == null) return;

            ASTRewrite rewrite = context.rewrite();
            rewrite.remove(keyword, null);
            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;
            out.add(context.preferredFix(REMOVE_ABSTRACT, "Remove 'abstract' modifier", edit));
        }
    }

    /** The node's own {@code keyword} modifier, or null — annotations share this list and are skipped. */
    private static Modifier modifier(ASTNode declaration, Modifier.ModifierKeyword keyword) {
        List<?> modifiers = declaration instanceof BodyDeclaration
                ? ((BodyDeclaration) declaration).modifiers()
                : ((VariableDeclarationStatement) declaration).modifiers();
        for (Object each : modifiers) {
            if (each instanceof Modifier && ((Modifier) each).getKeyword() == keyword) {
                return (Modifier) each;
            }
        }
        return null;
    }
}
