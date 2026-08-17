package com.crystalgui.language.java;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Statement;

/**
 * <b>What encloses this node</b> — the parent walks, which had accumulated a private copy per family.
 *
 * <h3>The walks are trivial and the STOPPING RULES are not, which is the whole reason for this file</h3>
 *
 * <p>Eleven copies said "walk up to a {@code MethodDeclaration}", and no two of them agreed about what to
 * do on the way. One stopped at a lambda, one at a lambda or an initialiser or an anonymous class, one at
 * neither and returned the compilation unit when there was no method at all. Each rule is right for its
 * caller and reads like an accident at the copy next to it — so the rule is a <b>parameter</b> here, named
 * at the call site where its reason lives.</p>
 *
 * <p>Where two callers were asking genuinely different questions, this offers two methods rather than one
 * merged answer: {@link #enclosingTypeBinding} counts an anonymous class as a type and
 * {@link #enclosingTypeDeclaration} does not, because one is being asked "which type am I in" and the
 * other "which declaration may I add a member to". Collapsing those loses a distinction the compiler
 * makes.</p>
 */
final class Scopes {

    private Scopes() {
    }

    /**
     * A construct the walk must not pass through.
     *
     * <p>Each is somewhere the enclosing method stops being the callable that matters: a lambda body's
     * {@code return} is the lambda's, an initialiser's {@code throws} has nowhere to go, and an anonymous
     * class's method is a different method entirely.</p>
     */
    enum Stop { LAMBDA, INITIALIZER, ANONYMOUS }

    /** The method or constructor declaring {@code at}, or null — stopping at any of {@code stops}. */
    static MethodDeclaration enclosingMethod(ASTNode at, Stop... stops) {
        for (ASTNode walk = at; walk != null; walk = walk.getParent()) {
            if (walk instanceof MethodDeclaration) return (MethodDeclaration) walk;
            if (stopsAt(walk, stops)) return null;
        }
        return null;
    }

    /** The same, resolved — for the analyzer, which wants the binding rather than the declaration. */
    static IMethodBinding enclosingMethodBinding(ASTNode at) {
        MethodDeclaration method = enclosingMethod(at);
        return method == null ? null : method.resolveBinding();
    }

    /**
     * The enclosing method <b>or the outermost node</b> when there is none — never null.
     *
     * <p>A different question from {@link #enclosingMethod} despite the identical walk: this one is asked
     * for a <em>scope to collect declared names from</em>, where "no method" wants the whole file rather
     * than an answer of null. It deliberately walks straight past a lambda, because a name declared in the
     * method around one may not be shadowed inside it — so the method is the honest scope and the lambda
     * would be an under-count that produces code the compiler rejects.</p>
     */
    static ASTNode enclosingMethodOrRoot(ASTNode at) {
        ASTNode walk = at;
        while (walk.getParent() != null && !(walk instanceof MethodDeclaration)) {
            walk = walk.getParent();
        }
        return walk;
    }

    /**
     * The innermost method, lambda or initialiser containing {@code at} — <b>the scope a local name lives
     * in</b>, which is a third question again.
     *
     * <p>{@link #enclosingMethodOrRoot} deliberately walks past a lambda because a name declared outside
     * one may not be shadowed inside it, so the method is the honest scope for <em>choosing</em> a name.
     * This one stops there, because it is asked what names are <em>already declared</em> around a point,
     * and a lambda body is its own body of declarations.</p>
     */
    static ASTNode enclosingNameScope(ASTNode at) {
        for (ASTNode walk = at; walk != null; walk = walk.getParent()) {
            if (walk instanceof MethodDeclaration || walk instanceof LambdaExpression
                    || walk instanceof Initializer) {
                return walk;
            }
        }
        return null;
    }

    /** The innermost statement containing {@code at}, or null. */
    static Statement enclosingStatement(ASTNode at) {
        for (ASTNode walk = at; walk != null; walk = walk.getParent()) {
            if (walk instanceof Statement) return (Statement) walk;
        }
        return null;
    }

    /**
     * The binding of the type {@code at} is written inside — <b>anonymous classes included</b>.
     *
     * <p>Which is what "the type I am in" means for accessibility and for {@code this}: a private member of
     * an anonymous class is reachable from inside its body, and the enclosing named type is a different
     * question. Three copies disagreed about this and one of them was the analyzer's, so a caret inside an
     * anonymous class judged what it could see from the outer type.</p>
     */
    static ITypeBinding enclosingTypeBinding(ASTNode at) {
        for (ASTNode walk = at; walk != null; walk = walk.getParent()) {
            if (walk instanceof AbstractTypeDeclaration) {
                return ((AbstractTypeDeclaration) walk).resolveBinding();
            }
            if (walk instanceof AnonymousClassDeclaration) {
                return ((AnonymousClassDeclaration) walk).resolveBinding();
            }
        }
        return null;
    }

    /**
     * The nearest <b>named</b> type declaration, as a node — for a correction that adds a member to it.
     *
     * <p>Not the same walk as {@link #enclosingTypeBinding} and not a lesser version of it: this one needs
     * something with a body to insert into and a name to report, which an anonymous class declaration is
     * only half of.</p>
     */
    static AbstractTypeDeclaration enclosingTypeDeclaration(ASTNode at) {
        for (ASTNode walk = at; walk != null; walk = walk.getParent()) {
            if (walk instanceof AbstractTypeDeclaration) return (AbstractTypeDeclaration) walk;
        }
        return null;
    }

    /**
     * Whether {@code at} sits somewhere with no {@code this}.
     *
     * <p>A static method is the obvious one and was the only one the two copies agreed on. A <b>static
     * initialiser</b> and the initialiser of a <b>static field</b> are equally static, and the copy that
     * missed them would have generated an instance member to be referenced from a place that cannot reach
     * one. Stopping at any other body declaration is what keeps the walk from leaving the member it
     * started in.</p>
     */
    static boolean isStaticContext(ASTNode at) {
        for (ASTNode walk = at; walk != null; walk = walk.getParent()) {
            if (walk instanceof MethodDeclaration) {
                return Modifier.isStatic(((MethodDeclaration) walk).getModifiers());
            }
            if (walk instanceof Initializer) {
                return Modifier.isStatic(((Initializer) walk).getModifiers());
            }
            if (walk instanceof FieldDeclaration) {
                return Modifier.isStatic(((FieldDeclaration) walk).getModifiers());
            }
            // ANY OTHER member declaration, or an anonymous class body, ends the question: whatever
            // encloses THAT is not the context this node is evaluated in.
            if (walk instanceof BodyDeclaration || walk instanceof AnonymousClassDeclaration) return false;
        }
        return false;
    }

    private static boolean stopsAt(ASTNode node, Stop[] stops) {
        for (Stop stop : stops) {
            if (stop == Stop.LAMBDA && node instanceof LambdaExpression) return true;
            if (stop == Stop.INITIALIZER && node instanceof Initializer) return true;
            if (stop == Stop.ANONYMOUS && node instanceof AnonymousClassDeclaration) return true;
        }
        return false;
    }
}
