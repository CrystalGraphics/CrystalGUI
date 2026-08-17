package com.crystalgui.language.java;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "Create method 'compute(int, String)'" — the first correction that generates a <em>declaration</em>
 * from the shape of a use.
 *
 * <h3>Only into a type declared in this file</h3>
 *
 * <p>Anything else is a second file, and a multi-document edit is the one thing the carrier deliberately
 * cannot express — see the catalogue's §14-G. So the receiver's type has to resolve to a declaration in
 * this unit; a call on {@code String} or on anything from a jar is refused quietly, and the popup still
 * offers "did you mean" for it.</p>
 *
 * <h3>What is inferred, and from where</h3>
 *
 * <ul>
 *   <li><b>Parameter types</b> from the argument bindings, written through an {@link ImportPlan}. A type
 *       variable or capture becomes its erasure, because the calling method's {@code T} is not in scope in
 *       the new one; an anonymous or local type becomes {@code Object} for the same reason.</li>
 *   <li><b>Parameter names</b> from the arguments when they are simple names, otherwise from the type
 *       ({@code String} → {@code string}, {@code int} → {@code i}), de-duplicated.</li>
 *   <li><b>Return type</b> from the use site: {@code void} for a statement, the declared type for an
 *       initialiser, the target's type for an assignment, the enclosing method's for a {@code return},
 *       {@code boolean} under a condition or a {@code !}, the cast type under a cast, else
 *       {@code Object}. The body returns the type's zero, so what is generated compiles.</li>
 *   <li><b>Modifiers</b>: {@code private} into the enclosing type, package-private into another;
 *       {@code static} when the receiver is a type name or the call sits in a static context.</li>
 * </ul>
 *
 * <p>Not preferred, and neither is anything else for an unresolved method — "did you mean" competes, and
 * which is right depends on whether the name was a typo or an intention.</p>
 */
@SuppressWarnings("unchecked")   // JDT's DOM lists are raw; every add below is to a list of the declared node type
final class CreateCorrections {

    static final String CREATE_METHOD = "java.create.method";
    static final String CREATE_CONSTRUCTOR = "java.create.constructor";

    private CreateCorrections() {
    }

    static List<Correction> all() {
        return List.of(new CreateMethod(), new CreateConstructor());
    }

    private static final class CreateMethod implements Correction {

        @Override public String id() {
            return CREATE_METHOD;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UndefinedMethod};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            SimpleName name = context.enclosing(problem, SimpleName.class);
            if (name == null || !(name.getParent() instanceof MethodInvocation)) return;
            MethodInvocation call = (MethodInvocation) name.getParent();
            if (call.getName() != name) return;

            ITypeBinding here = Scopes.enclosingTypeBinding(call);
            ITypeBinding receiver = call.getExpression() == null ? here : call.getExpression().resolveTypeBinding();
            if (receiver == null) return;
            AbstractTypeDeclaration target = declarationOf(context, receiver);
            if (target == null) return;

            // A LAMBDA OR METHOD REFERENCE ARGUMENT HAS NO TYPE OF ITS OWN -- it takes one from the
            // parameter it is passed to, and the parameter is what does not exist yet. Writing `Object`
            // there produces a signature the call still cannot use, which is a fix that looks finished
            // and is not; nothing offered is the honest answer. (IntelliJ guesses a functional
            // interface from the lambda's shape, which is a fine thing to add later and is not this.)
            for (Object each : call.arguments()) {
                if (each instanceof LambdaExpression || each instanceof MethodReference) return;
            }

            AST ast = context.unit().getAST();
            ASTRewrite rewrite = context.rewrite();
            ImportPlan imports = context.importPlan();
            MethodDeclaration method = ast.newMethodDeclaration();
            method.setName(ast.newSimpleName(name.getIdentifier()));

            boolean sameType = here != null && here.getErasure().isEqualTo(receiver.getErasure());
            boolean isStatic = isStaticCall(call, here);
            // INTO AN INTERFACE the instance case is an abstract method: a private method with a body is
            // Java 9 and this engine's floor is 8, and an abstract one is what IntelliJ generates there
            // too -- the implementors then say what it does. A static one keeps its body, which is legal
            // in an interface since 8.
            boolean abstractMember = receiver.getErasure().isInterface() && !isStatic;
            if (sameType && !abstractMember) {
                method.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PRIVATE_KEYWORD));
            }
            if (isStatic) method.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.STATIC_KEYWORD));

            Set<String> taken = new LinkedHashSet<>();
            List<String> shownTypes = new ArrayList<>();
            for (Object each : call.arguments()) {
                Expression argument = (Expression) each;
                ITypeBinding argumentType = argument.resolveTypeBinding();
                SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
                Type type = TypeNames.typeNode(argumentType, ast, imports);
                parameter.setType(type);
                parameter.setName(ast.newSimpleName(parameterName(argument, argumentType, taken)));
                method.parameters().add(parameter);
                shownTypes.add(argumentType == null ? "Object" : argumentType.getErasure().getName());
            }

            Type returnType = returnTypeFor(call, ast, rewrite, imports);
            method.setReturnType2(returnType);
            if (!abstractMember) {
                Block body = ast.newBlock();
                Expression zero = zeroOf(returnType, ast);
                if (zero != null) {
                    ReturnStatement returned = ast.newReturnStatement();
                    returned.setExpression(zero);
                    body.statements().add(returned);
                }
                method.setBody(body);
            }

            rewrite.getListRewrite(target, target.getBodyDeclarationsProperty()).insertLast(method, null);
            ChangeSet edit = context.changesFrom(rewrite, imports);
            if (edit == null) return;
            out.add(context.fix(CREATE_METHOD,
                    "Create method '" + name.getIdentifier() + "(" + String.join(", ", shownTypes) + ")'", edit));
        }

        // ── Where ───────────────────────────────────────────────────────────────────────────────

        /** The declaration of {@code type} in this unit, or null — a type from anywhere else is a second file. */
        private static AbstractTypeDeclaration declarationOf(FixContext context, ITypeBinding type) {
            ITypeBinding wanted = type.getErasure();
            AbstractTypeDeclaration[] found = new AbstractTypeDeclaration[1];
            context.unit().accept(new ASTVisitor() {
                @Override public void preVisit(ASTNode node) {
                    if (found[0] == null && node instanceof AbstractTypeDeclaration) {
                        ITypeBinding declared = ((AbstractTypeDeclaration) node).resolveBinding();
                        if (declared != null && declared.getErasure().isEqualTo(wanted)) {
                            found[0] = (AbstractTypeDeclaration) node;
                        }
                    }
                }
            });
            return found[0];
        }

        /** {@code Foo.bar()} — the receiver is a type — or a bare call from a static method or initialiser. */
        private static boolean isStaticCall(MethodInvocation call, ITypeBinding here) {
            Expression receiver = call.getExpression();
            if (receiver instanceof Name) {
                return ((Name) receiver).resolveBinding() instanceof ITypeBinding;
            }
            if (receiver != null) return false;
            return Scopes.isStaticContext(call);
        }

        // ── Types ───────────────────────────────────────────────────────────────────────────────

        private static Type returnTypeFor(MethodInvocation call, AST ast, ASTRewrite rewrite, ImportPlan imports) {
            ASTNode parent = call.getParent();
            if (parent instanceof ExpressionStatement) return ast.newPrimitiveType(PrimitiveType.VOID);
            if (parent instanceof VariableDeclarationFragment && ((VariableDeclarationFragment) parent).getInitializer() == call) {
                ASTNode declaration = parent.getParent();
                Type declared = declaration instanceof VariableDeclarationStatement
                        ? ((VariableDeclarationStatement) declaration).getType()
                        : declaration instanceof FieldDeclaration ? ((FieldDeclaration) declaration).getType() : null;
                if (declared != null && !declared.isVar()) return (Type) rewrite.createCopyTarget(declared);
            }
            if (parent instanceof Assignment && ((Assignment) parent).getRightHandSide() == call) {
                return TypeNames.typeNode(((Assignment) parent).getLeftHandSide().resolveTypeBinding(), ast, imports);
            }
            if (parent instanceof ReturnStatement) {
                for (ASTNode at = parent; at != null; at = at.getParent()) {
                    if (at instanceof MethodDeclaration) {
                        Type declared = ((MethodDeclaration) at).getReturnType2();
                        if (declared != null) return (Type) rewrite.createCopyTarget(declared);
                        break;
                    }
                }
            }
            if (parent instanceof CastExpression) return (Type) rewrite.createCopyTarget(((CastExpression) parent).getType());
            if (parent instanceof IfStatement || parent instanceof WhileStatement || parent instanceof DoStatement
                    || (parent instanceof ConditionalExpression && ((ConditionalExpression) parent).getExpression() == call)
                    || (parent instanceof PrefixExpression
                        && ((PrefixExpression) parent).getOperator() == PrefixExpression.Operator.NOT)) {
                return ast.newPrimitiveType(PrimitiveType.BOOLEAN);
            }
            return ast.newSimpleType(ast.newSimpleName("Object"));
        }

        /** The literal that makes a body of {@code return …;} compile — null for {@code void}. */
        private static Expression zeroOf(Type type, AST ast) {
            // THE SAME RULE AS `TypeNames.defaultValue`, reached by name rather than by binding: a Type
            // produced by `createCopyTarget` carries no binding, which is why this asks the primitive's
            // code for its name instead of resolving one.
            if (!(type instanceof PrimitiveType)) return ast.newNullLiteral();
            String value = TypeNames.defaultValueOfPrimitive(
                    ((PrimitiveType) type).getPrimitiveTypeCode().toString());
            if (value == null) return null;                                   // void
            if ("false".equals(value)) return ast.newBooleanLiteral(false);
            return ast.newNumberLiteral(value);
        }

        // ── Names ───────────────────────────────────────────────────────────────────────────────

        /**
         * A name for the parameter this argument will become, and never one already used.
         *
         * <p>{@link Names} is the whole of it now. The copy that stood here kept its own keyword list and
         * its own primitive table, and the table disagreed — {@code boolean} came out as {@code b} where
         * everything else in the engine calls it {@code flag}, and {@code int[]} came out as {@code is}
         * rather than {@code ints}, because it stuck an {@code s} on the element's single letter.</p>
         */
        private static String parameterName(Expression argument, ITypeBinding type, Set<String> taken) {
            String base = argument instanceof SimpleName
                    ? ((SimpleName) argument).getIdentifier() : null;
            String name = Names.derive(base, type, taken);
            taken.add(name);
            return name;
        }
    }

    /**
     * "Create constructor 'Box(int, String)'" — a {@code new} whose argument list nothing matches.
     *
     * <h3>The same three rules the method case already settled</h3>
     *
     * <p>Into a type <b>declared in this file</b> and nowhere else, because anything else is a second file
     * and §14-G's deliberate no. Parameter types from the argument bindings and names from the arguments
     * when they are simple names. And <b>refused when any argument is a lambda or method reference</b>,
     * which has no type of its own — it takes one from the parameter it is passed to, and that parameter is
     * exactly what does not exist yet, so {@code Object} produces a signature the call still cannot use
     * while looking finished.</p>
     *
     * <h3>Where it goes, which is not where a method goes</h3>
     *
     * <p>After the last existing constructor, or after the last field when there is none. A generated
     * constructor appended below every method reads as having been bolted on, and the convention it breaks
     * is one every Java reader has — fields, constructors, methods, in that order.</p>
     *
     * <p>The TYPE is read from {@code creation.getType()} rather than from the creation's own binding: for
     * {@code new Box(1) { }} the latter is the ANONYMOUS subclass, which is not a thing a constructor can
     * be added to, while the written type is the one whose constructor is missing.</p>
     */
    private static final class CreateConstructor implements Correction {

        @Override public String id() {
            return CREATE_CONSTRUCTOR;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UndefinedConstructor,
                    IProblem.UndefinedConstructorInDefaultConstructor,
                    IProblem.UndefinedConstructorInImplicitConstructorCall};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            ClassInstanceCreation creation = context.enclosing(problem, ClassInstanceCreation.class);
            if (creation == null || creation.getType() == null) return;
            ITypeBinding target = creation.getType().resolveBinding();
            if (target == null) return;
            AbstractTypeDeclaration declaration = CreateMethod.declarationOf(context, target);
            if (!(declaration instanceof TypeDeclaration)) return;
            // AN INTERFACE HAS NO CONSTRUCTOR TO ADD, and `new` on one is a different error entirely.
            if (((TypeDeclaration) declaration).isInterface()) return;
            for (Object each : creation.arguments()) {
                if (each instanceof LambdaExpression || each instanceof MethodReference) return;
            }
            if (creation.arguments().isEmpty()) return;

            AST ast = context.unit().getAST();
            ASTRewrite rewrite = context.rewrite();
            ImportPlan imports = context.importPlan();
            MethodDeclaration made = ast.newMethodDeclaration();
            made.setConstructor(true);
            made.setName(ast.newSimpleName(declaration.getName().getIdentifier()));

            ITypeBinding here = Scopes.enclosingTypeBinding(creation);
            boolean sameType = here != null && here.getErasure().isEqualTo(target.getErasure());
            made.modifiers().add(ast.newModifier(sameType
                    ? Modifier.ModifierKeyword.PRIVATE_KEYWORD : Modifier.ModifierKeyword.PUBLIC_KEYWORD));

            Set<String> taken = new LinkedHashSet<>();
            List<String> shownTypes = new ArrayList<>();
            for (Object each : creation.arguments()) {
                Expression argument = (Expression) each;
                ITypeBinding argumentType = argument.resolveTypeBinding();
                SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
                parameter.setType(TypeNames.typeNode(argumentType, ast, imports));
                parameter.setName(ast.newSimpleName(CreateMethod.parameterName(argument, argumentType, taken)));
                made.parameters().add(parameter);
                shownTypes.add(argumentType == null ? "Object" : argumentType.getErasure().getName());
            }
            made.setBody(ast.newBlock());

            ListRewrite body = rewrite.getListRewrite(declaration, declaration.getBodyDeclarationsProperty());
            BodyDeclaration after = lastConstructorOrFieldOf((TypeDeclaration) declaration);
            if (after == null) {
                body.insertFirst(made, null);
            } else {
                body.insertAfter(made, after, null);
            }
            ChangeSet edit = context.changesFrom(rewrite, imports);
            if (edit == null) return;
            out.add(context.fix(CREATE_CONSTRUCTOR, "Create constructor '"
                    + declaration.getName().getIdentifier()
                    + "(" + String.join(", ", shownTypes) + ")'", edit));
        }

        /** Fields, constructors, methods — so a generated constructor lands where a reader expects one. */
        private static BodyDeclaration lastConstructorOrFieldOf(TypeDeclaration owner) {
            BodyDeclaration last = null;
            for (Object each : owner.bodyDeclarations()) {
                if (each instanceof FieldDeclaration) {
                    last = (BodyDeclaration) each;
                } else if (each instanceof MethodDeclaration
                        && ((MethodDeclaration) each).isConstructor()) {
                    last = (BodyDeclaration) each;
                }
            }
            return last;
        }
    }
}
