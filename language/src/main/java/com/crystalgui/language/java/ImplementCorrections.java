package com.crystalgui.language.java;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Implement methods" — a concrete type that has not implemented everything it inherited.
 *
 * <h3>The only measured gap that grew when the classpath resolved</h3>
 *
 * <p>The coverage probe found {@code AbstractMethodMustBeImplemented} unanswered at 28 occurrences across
 * 8 files with an empty classpath, and at <b>32 across 11</b> once the repository's own classes were on it.
 * Every other uncovered row shrank, because most of them were {@code "refers to the missing type X"} — the
 * shape of an unresolvable classpath rather than a gap. Going <em>up</em> is the signature that separates
 * the two, and it is why this is here rather than something with a bigger raw count.</p>
 *
 * <h3>N problems, one action</h3>
 *
 * <p>ECJ reports this <b>once per missing method</b>, all of them on the type's name. A class missing five
 * methods is five problems, and five identical rows offering to write all five would be five ways to do one
 * edit — so the type is claimed and the first problem answers for the lot. {@code FixContext.claim} exists
 * for exactly this and the surround-with-try family met it first.</p>
 *
 * <h3>All or nothing</h3>
 *
 * <p>If any missing method cannot be written the whole action is refused, rather than writing the ones that
 * can be. A partly-implemented class still does not compile, so a fix that leaves it that way has spent the
 * user's click and moved nothing — and, worse, it looks finished. The case that triggers it is a method
 * with its <em>own</em> type parameters ({@code <T> T make()}), whose signature {@link TypeNames} correctly
 * refuses to spell; a generic <em>supertype</em> is fine, because {@code Comparator<String>} hands over a
 * method binding whose types are already substituted.</p>
 */
final class ImplementCorrections {

    static final String IMPLEMENT = "java.create.implementMethods";

    private ImplementCorrections() {
    }

    static List<Correction> all() {
        return List.of(new ImplementInheritedMethods());
    }

    private static final class ImplementInheritedMethods implements Correction {

        @Override public String id() {
            return IMPLEMENT;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.AbstractMethodMustBeImplemented,
                    IProblem.AbstractMethodMustBeImplementedOverConcreteMethod,
                    IProblem.EnumAbstractMethodMustBeImplemented};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            AbstractTypeDeclaration declaration = context.enclosing(problem, AbstractTypeDeclaration.class);
            if (!(declaration instanceof TypeDeclaration)) return;
            ITypeBinding type = declaration.resolveBinding();
            if (type == null) return;
            if (!context.claim(IMPLEMENT + "@" + declaration.getStartPosition())) return;

            List<IMethodBinding> missing = unimplementedIn(context, type);
            if (missing.isEmpty()) return;

            ImportPlan imports = context.importPlan();
            List<String> stubs = new ArrayList<>();
            for (IMethodBinding method : missing) {
                String stub = stubFor(context, method, imports, declaration);
                // ALL OR NOTHING. A class missing five methods and given four still does not compile, so
                // the click bought nothing -- and the result looks finished, which is worse than an offer
                // that never appeared.
                if (stub == null) return;
                stubs.add(stub);
            }

            ASTRewrite rewrite = context.rewrite();
            ListRewrite body = rewrite.getListRewrite(declaration, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
            for (String stub : stubs) {
                body.insertLast(rewrite.createStringPlaceholder(stub, ASTNode.METHOD_DECLARATION), null);
            }
            ChangeSet edit = context.changesFrom(rewrite, imports);
            if (edit == null) return;
            out.add(context.preferredFix(IMPLEMENT, missing.size() == 1
                    ? "Implement method '" + missing.get(0).getName() + "'"
                    : "Implement " + missing.size() + " methods", edit));
        }
    }

    // ── What is missing ─────────────────────────────────────────────────────────────────────────

    /**
     * Every abstract method {@code type} inherits and does not have a body for.
     *
     * <p>Computed as (all abstract methods in the supertype closure) minus (every method with a body
     * anywhere at or above it), keyed on erased signature. JDT has no "unimplemented methods" query, and
     * the alternative — reading the names out of the problems' own arguments — would be parsing prose that
     * is localised and changes between releases, which is the reason this layer keys on ids at all.</p>
     *
     * <p><b>{@code getDeclaredMethods()} is not source order</b>, which a test caught immediately: an
     * interface declaring {@code greet} then {@code count} hands them back the other way round. So the
     * result is sorted by where each method is <em>written</em> when that is in this file — which is the
     * script case, and puts the generated stubs in the order somebody reading both files would expect —
     * and by name otherwise, because an order nobody can predict is still better arbitrary than unstable.</p>
     */
    private static List<IMethodBinding> unimplementedIn(FixContext context, ITypeBinding type) {
        Map<String, IMethodBinding> abstracts = new LinkedHashMap<>();
        Set<String> concrete = new HashSet<>();
        collect(type, abstracts, concrete, new HashSet<>());

        List<IMethodBinding> missing = new ArrayList<>();
        for (Map.Entry<String, IMethodBinding> each : abstracts.entrySet()) {
            if (!concrete.contains(each.getKey())) missing.add(each.getValue());
        }
        missing.sort(Comparator.comparingInt((IMethodBinding method) -> declaredAt(context, method))
                .thenComparing(IMethodBinding::getName));
        return missing;
    }

    /** Where this method is written, when it is written in this file at all. */
    private static int declaredAt(FixContext context, IMethodBinding method) {
        ASTNode declared = context.unit().findDeclaringNode(method.getMethodDeclaration().getKey());
        return declared == null ? Integer.MAX_VALUE : declared.getStartPosition();
    }

    private static void collect(ITypeBinding type, Map<String, IMethodBinding> abstracts,
                                Set<String> concrete, Set<String> seen) {
        if (type == null || !seen.add(type.getKey())) return;
        for (IMethodBinding method : type.getDeclaredMethods()) {
            if (method.isConstructor() || method.isSynthetic()) continue;
            String signature = signatureOf(method);
            if (Modifier.isAbstract(method.getModifiers())) {
                abstracts.putIfAbsent(signature, method);
            } else {
                concrete.add(signature);
            }
        }
        collect(type.getSuperclass(), abstracts, concrete, seen);
        for (ITypeBinding each : type.getInterfaces()) {
            collect(each, abstracts, concrete, seen);
        }
    }

    /** Name plus erased parameter types — what overriding actually keys on. */
    private static String signatureOf(IMethodBinding method) {
        StringBuilder key = new StringBuilder(method.getName()).append('(');
        for (ITypeBinding parameter : method.getParameterTypes()) {
            key.append(parameter.getErasure().getQualifiedName()).append(',');
        }
        return key.append(')').toString();
    }

    // ── What to write ───────────────────────────────────────────────────────────────────────────

    /**
     * One method stub, or null when its signature cannot be spelled in this file.
     *
     * <p>The body is {@link TypeNames#defaultValue}, which is what both references generate and is the same
     * rule {@code ValueCorrections} uses for a missing return — so "the compiler needs a value here and
     * nobody has said which" has one answer in this engine rather than two.</p>
     */
    private static String stubFor(FixContext context, IMethodBinding method, ImportPlan imports,
                                  ASTNode at) {
        // A METHOD WITH ITS OWN TYPE PARAMETERS CANNOT BE SPELLED. A generic SUPERTYPE is fine --
        // Comparator<String> hands over a binding whose types are already substituted -- but `<T> T make()`
        // declares T itself, and TypeNames correctly refuses to write a type variable.
        if (method.getTypeParameters().length > 0) return null;

        // NOTHING IS GENERATED FROM A TYPE THE COMPILER COULD NOT SEE. A RECOVERED binding is JDT's
        // stand-in for a name it failed to resolve, and it still answers getQualifiedName() -- so a stub
        // built from one is written against a type that does not exist, turning "must implement" into one
        // unresolvable reference per parameter. The corpus is what found it: five files, each gaining
        // between six and sixteen errors from a fix that looked right in every fixture.
        //
        // ASKED BY `writtenName`, below, and no longer here: it refuses a recovered binding and recurses
        // through arrays and type arguments doing it, so `List<Missing>` is refused as surely as `Missing`.
        // The private copy that used to stand here said so in its own comment and was left in place anyway.
        String returns = TypeNames.writtenName(method.getReturnType(), imports, at);
        if (returns == null && !"void".equals(method.getReturnType().getName())) return null;
        boolean isVoid = "void".equals(method.getReturnType().getName());

        ITypeBinding[] parameterTypes = method.getParameterTypes();
        List<String> names = parameterNames(context, method, parameterTypes.length);
        StringBuilder built = new StringBuilder();
        built.append("@Override\n")
                .append(Modifier.isProtected(method.getModifiers()) ? "protected " : "public ")
                .append(isVoid ? "void" : returns).append(' ').append(method.getName()).append('(');
        for (int i = 0; i < parameterTypes.length; i++) {
            String written = TypeNames.writtenName(parameterTypes[i], imports, at);
            if (written == null) return null;
            if (i > 0) built.append(", ");
            built.append(written).append(' ').append(names.get(i));
        }
        built.append(") {\n");
        if (!isVoid) built.append("    return ").append(TypeNames.defaultValue(method.getReturnType()))
                .append(";\n");
        // NO TRAILING NEWLINE. The rewriter indents every line of a placeholder, and the empty one a
        // trailing `\n` creates is indented too — which lands in the file as a line of pure whitespace
        // after each generated method.
        return built.append("}").toString();
    }

    /**
     * The parameter names the interface actually used, when the interface is in this file.
     *
     * <p>{@code IMethodBinding} carries no parameter names — they are not in bytecode unless the class was
     * compiled with {@code -parameters} — so the usual answer is {@code arg0, arg1}. But a script's
     * interfaces are overwhelmingly declared beside it, and there the real names are sitting in the tree:
     * {@code greet(String name)} is worth having over {@code greet(String arg0)}, and it is one lookup.</p>
     */
    private static List<String> parameterNames(FixContext context, IMethodBinding method, int count) {
        List<String> names = new ArrayList<>(count);
        ASTNode declared = context.unit().findDeclaringNode(method.getMethodDeclaration().getKey());
        if (declared instanceof MethodDeclaration) {
            for (Object each : ((MethodDeclaration) declared).parameters()) {
                names.add(((SingleVariableDeclaration) each).getName().getIdentifier());
            }
        }
        while (names.size() < count) names.add("arg" + names.size());
        return names.subList(0, count);
    }
}
