package com.crystalgui.language.java;

import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.Signature;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.syntax.SyntaxToken;

import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The JDT DOM, driven — diagnostics, semantic colouring and name resolution from real bindings.
 *
 * <h3>What is adopted rather than built (§15.1)</h3>
 *
 * <p>Everything hard is the binding model's: generic substitution, overload resolution in all three
 * phases, member lookup with bridge methods filtered and accessibility computed, pattern-variable
 * scoping, lambda target typing. None of it is implemented here. This class parses, walks and
 * translates — the intelligence is {@code ITypeBinding} and friends, which is the trade the plan makes
 * and the reason the semantic layer is a milestone rather than a project.</p>
 *
 * <h3>Three parser flags carry the entire works-while-typing story</h3>
 *
 * <p>{@code setResolveBindings}, {@code setStatementsRecovery} and {@code setBindingsRecovery}. A
 * script under the caret is nearly always incomplete — that is what typing looks like — so an analyser
 * that answers only for well-formed input answers exactly when it is not needed. Recovery is JDT's
 * shipped answer, hardened over twenty years, and it is a flag rather than a project. It is also
 * verified on the oldest band under a real Java 8 JVM (`BandSmoke`), because "the old jar probably
 * honours it" was an assumption worth killing early.</p>
 *
 * <h3>Semantic tokens say only what a grammar cannot</h3>
 *
 * <p>A parameter told apart from a local from a field; a name that is a type; a static final field that
 * is really a constant. Re-stating what tree-sitter already gets right — keywords, strings, comments —
 * would be work whose only effect is to overwrite an identical answer, and it would make every merge a
 * question of who was more recently correct rather than who knew more.</p>
 */
public final class EcjSourceAnalyzer implements SourceAnalyzer {

    @Override
    public Analysis analyze(String className, String source, List<String> classpath,
                            int releaseLevel, long version) {
        ASTParser parser = ASTParser.newParser(jlsLevel());
        parser.setSource(source.toCharArray());
        // THE PATH THE SOURCE ITSELF IMPLIES, not the caller's guess. A file declaring a package
        // and named from its file stem makes ECJ report "the declared package does not match the
        // expected package" on line 1 -- about its own bookkeeping, on the author's first line.
        parser.setUnitName(SourcePackages.unitPath(className, source));
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        // THE TWO THAT MATTER WHILE TYPING. Without them a half-written statement yields an AST with no
        // bindings at all, so every name in the file loses its colour on the keystroke that breaks it
        // and gets it back on the one that fixes it -- which reads as the highlighter flickering rather
        // than as the file being briefly invalid.
        parser.setStatementsRecovery(true);
        parser.setBindingsRecovery(true);
        parser.setCompilerOptions(compilerOptions(releaseLevel));

        String[] entries = classpath == null ? new String[0] : classpath.toArray(new String[0]);
        // includeRunningVMBootclasspath = true: rt.jar on Java 8, the jrt image on 9+. Different
        // mechanisms, and which one is used is a property of the host rather than of the jar.
        parser.setEnvironment(entries, new String[0], new String[0], true);

        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        return new EcjAnalysis(unit, source, version);
    }

    /**
     * The newest level this band's JDT offers.
     *
     * <p>Read reflectively rather than named, for the reason {@code JlsLevel} sets out at length: an
     * adapter compiled against the oldest band cannot name {@code JLS21}, and naming {@code JLS8}
     * instead compiles everywhere and silently caps the newest band at Java 8 syntax — which is worse,
     * because it works. Duplicated here rather than called because {@code JlsLevel} lives on the host
     * side of the bridge and this class is loaded by the child.</p>
     */
    private static int jlsLevel() {
        int highest = 0;
        for (Field field : AST.class.getFields()) {
            String name = field.getName();
            if (!name.startsWith("JLS") || field.getType() != int.class) continue;
            if (field.isAnnotationPresent(Deprecated.class)) continue;
            if (!name.substring(3).chars().allMatch(Character::isDigit)) continue;
            try {
                highest = Math.max(highest, field.getInt(null));
            } catch (IllegalAccessException unreachable) {
                // A public static final int that cannot be read does not happen; skipping is right.
            }
        }
        if (highest == 0) throw new IllegalStateException("no AST.JLS* constant in this band");
        return highest;
    }

    private static Map<String, String> compilerOptions(int releaseLevel) {
        String level = releaseLevel <= 8 ? "1." + releaseLevel : Integer.toString(releaseLevel);
        Map<String, String> options = new HashMap<>();
        options.put("org.eclipse.jdt.core.compiler.source", level);
        options.put("org.eclipse.jdt.core.compiler.compliance", level);
        options.put("org.eclipse.jdt.core.compiler.codegen.targetPlatform", level);
        // Deprecation reported rather than silent: a script calling a removed-next-version API is worth
        // a squiggle, and SymbolModifier.DEPRECATED already has a drawing contract.
        options.put("org.eclipse.jdt.core.compiler.problem.deprecation", "warning");
        return options;
    }

    /** One resolved file, held on the engine's side. */
    private static final class EcjAnalysis implements Analysis {

        private final long version;
        private CompilationUnit unit;
        /**
         * The text the unit was parsed from — retained so a declaration can quote it verbatim.
         *
         * <p>A signature used to re-render an initializer node by node, inventing its own spacing. That
         * is work to do, rules to get wrong, and it throws away the answer: the author already wrote the
         * spacing, and an AST node knows exactly which characters it came from. Slicing them back out is
         * both less code and more faithful — {@code 1.618_033_988_749d} keeps its underscores,
         * {@code 0xDEAD_BEEF} stays hex, and an argument list is spaced however it was typed.</p>
         */
        private final String source;

        EcjAnalysis(CompilationUnit unit, String source, long version) {
            this.unit = unit;
            this.source = source == null ? "" : source;
            this.version = version;
        }

        @Override
        public long version() {
            return version;
        }

        /**
         * True unless the unit carries a <b>syntax</b> problem, which is what makes ECJ skip
         * {@code analyseCode()} and with it every optional problem in the file.
         *
         * <p>Asked as ECJ's own category rather than as "are there errors": a semantic error leaves the
         * optional passes running, so a resolve failure with no warnings is a complete answer and a parse
         * failure with no warnings is an absent one. The two are indistinguishable from the list alone.</p>
         *
         * <p>{@code CategorizedProblem} rather than an ID range — the ranges are internal and the category
         * is the published API. A problem that is not categorized at all is not a syntax error.</p>
         */
        @Override
        public boolean optionalProblemsAnalysed() {
            CompilationUnit resolved = unit;
            if (resolved == null) return true;
            for (IProblem problem : resolved.getProblems()) {
                if (problem instanceof CategorizedProblem categorized
                        && categorized.getCategoryID() == CategorizedProblem.CAT_SYNTAX) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public List<Diagnostic> diagnostics() {
            List<Diagnostic> found = new ArrayList<>();
            CompilationUnit resolved = unit;
            if (resolved == null) return found;
            for (IProblem problem : resolved.getProblems()) {
                DiagnosticSeverity severity = problem.isError() ? DiagnosticSeverity.ERROR
                        : problem.isWarning() ? DiagnosticSeverity.WARNING : DiagnosticSeverity.INFORMATION;
                // getSourceEnd is INCLUSIVE in JDT and exclusive in every range this codebase has, so
                // the +1 is a real conversion rather than an off-by-one waiting to happen: without it a
                // one-character problem produces a zero-width squiggle, which paints as nothing at all.
                TextPoint start = pointOf(resolved, problem.getSourceStart());
                TextPoint end = pointOf(resolved, problem.getSourceEnd() + 1);
                found.add(new Diagnostic(start, end, severity, problem.getMessage(),
                        "java", Integer.toString(problem.getID())));
            }
            return found;
        }

        private static TextPoint pointOf(CompilationUnit unit, int position) {
            // JDT counts lines from 1 and columns from 0; TextPoint counts both from 0.
            int line = unit.getLineNumber(position);
            int column = unit.getColumnNumber(position);
            if (line < 1 || column < 0) return Diagnostic.NO_POSITION;
            return new TextPoint(line - 1, column);
        }

        @Override
        public List<SyntaxToken> semanticTokens() {
            final List<SyntaxToken> tokens = new ArrayList<>();
            CompilationUnit resolved = unit;
            if (resolved == null) return tokens;
            resolved.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName name) {
                    String capture = captureFor(name);
                    if (capture != null) {
                        tokens.add(new SyntaxToken(name.getStartPosition(),
                                name.getStartPosition() + name.getLength(), capture));
                    }
                    return true;
                }

                /**
                 * A name that resolves to nothing, and a name whose target is deprecated.
                 *
                 * <p>Emitted as a SECOND token over the same range rather than instead of the first,
                 * because they answer a different question. {@code count} being a field and
                 * {@code count} being unresolved are both worth saying, and a scheme draws the first
                 * as a colour and the second as an underline — so replacing one with the other would
                 * throw away the piece of information the highlighter actually had.</p>
                 *
                 * <p>The editor's merge takes the last overlapping token, so these are added after the
                 * kind token above and win. That is the correct order: the more specific statement is
                 * the one about the world, not the one about the shape.</p>
                 */
                @Override
                public void endVisit(SimpleName name) {
                    IBinding binding = name.resolveBinding();
                    int start = name.getStartPosition();
                    int end = start + name.getLength();
                    if (binding == null) {
                        // ONLY WHERE A NAME WAS EXPECTED TO RESOLVE. A label, a package fragment and
                        // the name in a declaration position legitimately have no binding, and
                        // underlining those would mark correct code as broken on every file.
                        if (isResolvable(name)) tokens.add(new SyntaxToken(start, end, "unresolved"));
                        return;
                    }
                    if (binding.isDeprecated()) {
                        tokens.add(new SyntaxToken(start, end, "deprecated"));
                    }
                }
            });
            return tokens;
        }

        /**
         * Whether a name with no binding is actually a failure.
         *
         * <p>Several names legitimately resolve to nothing and marking them would report correct code
         * as broken in every file: the segments of a package or import path (each is a fragment, not a
         * type), a label, and the {@code name} in a {@code MemberValuePair}. Being conservative here is
         * the right direction — a missed underline is invisible, a false one is a red mark on working
         * code, and the diagnostic already says the same thing where it matters.</p>
         */
        private static boolean isResolvable(SimpleName name) {
            ASTNode parent = name.getParent();
            if (parent == null) return false;
            int type = parent.getNodeType();
            return type != ASTNode.PACKAGE_DECLARATION
                    && type != ASTNode.IMPORT_DECLARATION
                    && type != ASTNode.QUALIFIED_NAME
                    && type != ASTNode.LABELED_STATEMENT
                    && type != ASTNode.BREAK_STATEMENT
                    && type != ASTNode.CONTINUE_STATEMENT
                    && type != ASTNode.MEMBER_VALUE_PAIR;
        }

        /**
         * The capture a resolved name should take, or null to leave it to the grammar.
         *
         * <p>Null is the common and correct answer for most names. This exists to say the handful of
         * things a parse cannot: which <em>kind</em> of variable a bare identifier is.</p>
         */
        private static String captureFor(SimpleName name) {
            IBinding binding = name.resolveBinding();
            if (binding == null) return null;
            if (binding instanceof IVariableBinding) {
                IVariableBinding variable = (IVariableBinding) binding;
                if (variable.isEnumConstant()) return SymbolKind.ENUM_MEMBER.captureName();
                if (variable.isField()) {
                    int flags = variable.getModifiers();
                    boolean constant = Modifier.isStatic(flags) && Modifier.isFinal(flags);
                    return constant ? SymbolKind.CONSTANT.captureName()
                            : SymbolKind.FIELD.captureName();
                }
                if (variable.isParameter()) return SymbolKind.PARAMETER.captureName();
                return SymbolKind.LOCAL_VARIABLE.captureName();
            }
            if (binding instanceof ITypeBinding) {
                // A type NAME only. The grammar gets declarations right; what it cannot do is tell that
                // a bare identifier in an expression is a type rather than a variable.
                return SymbolKind.CLASS.captureName();
            }
            if (binding instanceof IMethodBinding) {
                return ((IMethodBinding) binding).isConstructor()
                        ? SymbolKind.CONSTRUCTOR.captureName() : SymbolKind.METHOD.captureName();
            }
            return null;
        }

        @Override
        public SymbolInfo resolveAt(int offset) {
            CompilationUnit resolved = unit;
            if (resolved == null) return null;
            SimpleName name = nameAt(resolved, offset);
            if (name == null) return null;
            IBinding binding = bindingFor(name);
            if (binding == null) return null;
            return describe(resolved, name, binding);
        }

        /**
         * The identifier covering {@code offset}, or null.
         *
         * <p>{@code NodeFinder} with a zero length finds the node at a caret, which is what a hover or a
         * go-to asks about. A caret sits <em>inside</em> a word far more often than before it, so the
         * covering node is the right question rather than the one starting there.</p>
         */
        private static SimpleName nameAt(CompilationUnit unit, int offset) {
            ASTNode node = NodeFinder.perform(unit, offset, 0);
            while (node != null && !(node instanceof SimpleName)) node = node.getParent();
            return (SimpleName) node;
        }

        /**
         * What the name at a caret actually refers to — which for {@code new Foo(...)} is the
         * <b>constructor</b>, not the type.
         *
         * <p>{@code resolveBinding()} on that {@code SimpleName} answers the type, because syntactically
         * the name <em>is</em> the type: the constructor is reachable only by asking the
         * {@link ClassInstanceCreation} that encloses it. So hovering {@code new ArrayList<>(...)}
         * described the class — its supertypes, its interfaces — rather than the overload being called,
         * which is the one thing you cannot see from the call site and the entire reason to ask.</p>
         *
         * <p>The walk up through {@code SimpleType}/{@code ParameterizedType} is what makes it work for a
         * parameterized creation: {@code new ArrayList<>()} nests the name two levels down, so testing the
         * immediate parent alone catches only the raw form.</p>
         *
         * <p>Go-to-definition gets the same correction for free, and wants it for the same reason.</p>
         */
        private static IBinding bindingFor(SimpleName name) {
            ASTNode node = name.getParent();
            while (node instanceof SimpleType || node instanceof ParameterizedType
                    || node instanceof QualifiedType || node instanceof QualifiedName) {
                node = node.getParent();
            }
            if (node instanceof ClassInstanceCreation) {
                IMethodBinding constructor = ((ClassInstanceCreation) node).resolveConstructorBinding();
                if (constructor != null) return constructor;
            }
            return name.resolveBinding();
        }

        /**
         * {@code java.util.ArrayList<E>} — the declaring type, with its parameters.
         *
         * <p>The owner band names where a member is declared, and for a generic type the parameters are
         * part of that name: {@code java.util.ArrayList} alone is the raw type, which is not what declares
         * {@code ArrayList(Collection<? extends E>)}. Both references show them.</p>
         */
        private static String containerName(ITypeBinding declaring) {
            if (declaring == null) return null;
            ITypeBinding declaration = declaring.getTypeDeclaration();
            String qualified = declaration.getQualifiedName();
            if (qualified == null || qualified.isEmpty()) qualified = declaration.getName();
            ITypeBinding[] parameters = declaration.getTypeParameters();
            if (parameters == null || parameters.length == 0) return qualified;
            StringBuilder out = new StringBuilder(qualified).append('<');
            for (int i = 0; i < parameters.length; i++) {
                if (i > 0) out.append(", ");
                out.append(parameters[i].getName());
            }
            return out.append('>').toString();
        }

        private SymbolInfo describe(CompilationUnit unit, SimpleName name, IBinding binding) {
            Set<SymbolModifier> modifiers = modifiersOf(binding);
            if (binding instanceof IVariableBinding) {
                IVariableBinding variable = (IVariableBinding) binding;
                SymbolKind kind = variable.isEnumConstant() ? SymbolKind.ENUM_MEMBER
                        : variable.isField() ? SymbolKind.FIELD
                        : variable.isParameter() ? SymbolKind.PARAMETER
                        : SymbolKind.LOCAL_VARIABLE;
                ITypeBinding declaring = variable.getDeclaringClass();
                return new SymbolInfo(name.getIdentifier(), kind, typeRef(variable.getType()),
                        containerName(declaring), null, modifiers,
                        declarationOf(unit, binding))
                        .withSignature(signatureOf(unit, binding, kind, name.getIdentifier()));
            }
            if (binding instanceof IMethodBinding) {
                IMethodBinding method = (IMethodBinding) binding;
                ITypeBinding declaring = method.getDeclaringClass();
                SymbolKind kind = method.isConstructor()
                        ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;
                return new SymbolInfo(name.getIdentifier(), kind, typeRef(method.getReturnType()),
                        containerName(declaring), null, modifiers,
                        declarationOf(unit, binding), parameterTypesOf(method))
                        .withSignature(signatureOf(unit, binding, kind, name.getIdentifier()));
            }
            if (binding instanceof ITypeBinding) {
                ITypeBinding type = (ITypeBinding) binding;
                SymbolKind kind = type.isInterface() ? SymbolKind.INTERFACE
                        : type.isEnum() ? SymbolKind.ENUM
                        : type.isAnnotation() ? SymbolKind.ANNOTATION
                        : type.isTypeVariable() ? SymbolKind.TYPE_PARAMETER
                        : SymbolKind.CLASS;
                return new SymbolInfo(name.getIdentifier(), kind, typeRef(type),
                        type.getPackage() == null ? null : type.getPackage().getName(), null,
                        modifiers, declarationOf(unit, binding))
                        .withSignature(signatureOf(unit, binding, kind, name.getIdentifier()));
            }
            return SymbolInfo.of(name.getIdentifier(), SymbolKind.UNKNOWN);
        }

        // ── The rendered declaration ────────────────────────────────────────────────────────────
        //
        // WHY THIS IS HERE AND NOT IN THE WIDGET. Only a binding knows that `public` is a modifier, that
        // `@Nullable` is an annotation and that `x` is a parameter -- and only the language knows what
        // order a declaration reads in. A widget assembling this from name/kind/type can produce Java's
        // shape and nothing else, and every refinement (annotations, visibility, generics, throws,
        // varargs, defaults) would be another field on the seam that no other language populates.
        //
        // The capture names below are §10.1's, the same vocabulary the grammar and the semantic provider
        // speak, so the popup colours this with the rules that colour the editor. @see Signature

        /**
         * How long a declaration may be before it is broken across lines.
         *
         * <p>A count of characters rather than of pixels, because the engine cannot see the box — and
         * does not need to: what it knows is where a break is <em>legal and meaningful</em>, which is the
         * half that cannot be recovered downstream. A widget re-wrapping this at the edge of its box
         * breaks between whatever two words happen to land there, which is how a parameter list ends up
         * split in the middle of a generic type.</p>
         */
        private static final int MAX_SIGNATURE_LINE = 72;

        private Signature signatureOf(CompilationUnit unit, IBinding binding, SymbolKind kind,
                                      String name) {
            Signature flat = render(unit, binding, kind, name, false);
            // TRIED FLAT FIRST, and kept if it fits. Breaking unconditionally would put a two-word field
            // declaration on three lines, which is worse than the problem being solved.
            return flat.text().length() <= MAX_SIGNATURE_LINE ? flat
                    : render(unit, binding, kind, name, true);
        }

        private Signature render(CompilationUnit unit, IBinding binding, SymbolKind kind,
                                 String name, boolean broken) {
            Signature.Builder out = new Signature.Builder();
            appendAnnotations(out, binding.getAnnotations(), broken);
            appendModifiers(out, binding.getModifiers(), kind);

            if (binding instanceof IVariableBinding) {
                IVariableBinding variable = (IVariableBinding) binding;
                // QUOTED WHOLE when we have the source for it -- see quotedDeclaration.
                Signature quoted = quotedDeclaration(unit, variable);
                if (quoted != null) return quoted;
                out.word(simpleTypeName(variable.getType()), typeCapture(variable.getType()));
                out.append(name, captureForVariable(variable));
                appendInitializer(out, unit, variable, broken);
                return out.build();
            }

            if (binding instanceof IMethodBinding) {
                // THE DECLARATION, NOT THE INSTANTIATION, for the reason the type branch below records:
                // `new ArrayList<>(List.of("one"))` binds the constructor with its parameters already
                // substituted, so the popup said `ArrayList(Collection<? extends String>)` -- true of
                // this call and not of the declaration anybody is asking about.
                IMethodBinding method = ((IMethodBinding) binding).getMethodDeclaration();
                if (!method.isConstructor()) {
                    out.word(simpleTypeName(method.getReturnType()),
                            typeCapture(method.getReturnType()));
                }
                out.append(name, kind == SymbolKind.CONSTRUCTOR ? "constructor" : "function.method");
                appendParameters(out, unit, method, broken);
                appendThrows(out, method.getExceptionTypes());
                return out.build();
            }

            if (binding instanceof ITypeBinding) {
                // THE DECLARATION, NOT THE INSTANTIATION. Hovering `new ArrayList<>(List.of("one"))`
                // binds the type as `ArrayList<String>`, whose superclass JDT reports as
                // `AbstractList<String>` -- so the popup claimed ArrayList is declared over String.
                // Documentation is about how a type is DECLARED, which is `ArrayList<E> extends
                // AbstractList<E>`, and getTypeDeclaration is the binding that says so. Both references
                // show the declaration here.
                ITypeBinding type = ((ITypeBinding) binding).getTypeDeclaration();
                out.word(declarationKeyword(type), "keyword");
                out.append(name, "type");
                appendTypeParameters(out, type);
                appendSupertypes(out, type, broken);
                return out.build();
            }
            return out.build();
        }

        /**
         * {@code @Nullable}, {@code @Contract(mutates = "this,io")} — simple names, with their arguments.
         *
         * <p>Qualified names would be correct and unreadable: {@code @org.jetbrains.annotations.Nullable}
         * is most of a line for no information a reader wanted. IntelliJ shows the simple name and makes
         * it a link to the full one, which is the same trade with a navigation affordance we do not have
         * yet — {@link Signature} is shaped so that arrives without changing the colouring.</p>
         */
        private static void appendAnnotations(Signature.Builder out,
                                              IAnnotationBinding[] annotations, boolean broken) {
            if (annotations == null) return;
            for (IAnnotationBinding annotation : annotations) {
                ITypeBinding type = annotation.getAnnotationType();
                if (type == null) continue;
                out.append("@" + type.getName(), "attribute");
                IMemberValuePairBinding[] pairs = annotation.getDeclaredMemberValuePairs();
                if (pairs != null && pairs.length > 0) {
                    out.append("(", "punctuation.bracket");
                    for (int i = 0; i < pairs.length; i++) {
                        if (i > 0) out.append(",", "punctuation.delimiter").raw(" ");
                        // A SINGLE `value` MEMBER IS WRITTEN BARE in source -- @Contract("...") rather
                        // than @Contract(value = "..."). Printing the name back would be correct Java and
                        // not what anybody wrote.
                        if (pairs.length > 1 || !"value".equals(pairs[i].getName())) {
                            out.append(pairs[i].getName(), "property").raw(" ")
                                    .append("=", "operator").raw(" ");
                        }
                        appendAnnotationValue(out, pairs[i].getValue());
                    }
                    out.append(")", "punctuation.bracket");
                }
                // EACH ON ITS OWN LINE when broken -- which is what both references do, and what makes a
                // method with a @Contract readable at all: the annotation is about the declaration rather
                // than part of it, so running them together buries the signature after its own metadata.
                if (broken) out.newline(); else out.raw(" ");
            }
        }

        /**
         * Visibility first, then the rest — Java's own conventional order.
         *
         * <p>Read from the modifier flags rather than from {@code SymbolModifier}, which carries no
         * visibility at all. That absence is right on the seam: three more enum constants would be three
         * more things every engine must populate for a fact only Java-shaped languages have. Here there
         * is a binding, so the words come out of the flags and go straight into the rendered text.</p>
         */
        private static void appendModifiers(Signature.Builder out, int flags, SymbolKind kind) {
            if (Modifier.isPublic(flags)) out.word("public", "keyword");
            if (Modifier.isProtected(flags)) out.word("protected", "keyword");
            if (Modifier.isPrivate(flags)) out.word("private", "keyword");
            if (Modifier.isStatic(flags)) out.word("static", "keyword");
            // An interface's methods are implicitly abstract and nobody writes it; an interface itself is
            // implicitly abstract too. Printing it back is noise that is not in the source.
            if (Modifier.isAbstract(flags) && kind != SymbolKind.INTERFACE) out.word("abstract", "keyword");
            if (Modifier.isFinal(flags)) out.word("final", "keyword");
            if (Modifier.isSynchronized(flags)) out.word("synchronized", "keyword");
            if (Modifier.isVolatile(flags)) out.word("volatile", "keyword");
            if (Modifier.isTransient(flags)) out.word("transient", "keyword");
            if (Modifier.isNative(flags)) out.word("native", "keyword");
            if (Modifier.isDefault(flags)) out.word("default", "keyword");
        }

        /**
         * {@code (@Nullable String x, int count)} — with real names when this file declares the method.
         *
         * <p><b>Names only when the declaration is in this unit.</b> {@code IMethodBinding} exposes
         * parameter types and not names, because a class read off the classpath genuinely has none unless
         * it was built with {@code -parameters}; the names live on the {@code MethodDeclaration}, which
         * exists only for source. IntelliJ shows {@code x} for {@code println} because it has the JDK
         * sources attached, and falls back to types exactly as this does when it does not.</p>
         *
         * <p>So a classpath method reads {@code println(String)} and one in the open file reads
         * {@code println(String x)}. That difference is real information — it says where the source is —
         * rather than an inconsistency to paper over with {@code arg0}.</p>
         */
        private static void appendParameters(Signature.Builder out, CompilationUnit unit,
                                             IMethodBinding method, boolean broken) {
            out.append("(", "punctuation.bracket");
            ITypeBinding[] types = method.getParameterTypes();
            List<String> names = parameterNames(unit, method);
            boolean perLine = broken && types.length > 0;
            if (perLine) out.newline();
            for (int i = 0; i < types.length; i++) {
                if (i > 0) {
                    out.append(",", "punctuation.delimiter");
                    if (perLine) out.newline(); else out.raw(" ");
                }
                if (perLine) out.indent();
                IAnnotationBinding[] onParameter = method.getParameterAnnotations(i);
                appendAnnotations(out, onParameter, false);
                boolean varargs = method.isVarargs() && i == types.length - 1;
                String rendered = simpleTypeName(types[i]);
                if (varargs && rendered.endsWith("[]")) {
                    rendered = rendered.substring(0, rendered.length() - 2) + "...";
                }
                out.append(rendered, typeCapture(types[i]));
                if (names != null && i < names.size()) {
                    out.raw(" ").append(names.get(i), "variable.parameter");
                }
            }
            if (perLine) out.newline();
            out.append(")", "punctuation.bracket");
        }

        /** Null when this unit does not declare the method — see the note on appendParameters. */
        private static List<String> parameterNames(CompilationUnit unit, IMethodBinding method) {
            ASTNode declaration = unit.findDeclaringNode(method);
            if (!(declaration instanceof MethodDeclaration)) return null;
            List<String> names = new ArrayList<>();
            for (Object parameter : ((MethodDeclaration) declaration).parameters()) {
                if (parameter instanceof SingleVariableDeclaration) {
                    names.add(((SingleVariableDeclaration) parameter).getName().getIdentifier());
                }
            }
            return names.isEmpty() ? null : names;
        }

        private static void appendThrows(Signature.Builder out, ITypeBinding[] thrown) {
            if (thrown == null || thrown.length == 0) return;
            out.raw(" ").word("throws", "keyword");
            for (int i = 0; i < thrown.length; i++) {
                if (i > 0) out.append(",", "punctuation.delimiter").raw(" ");
                out.append(simpleTypeName(thrown[i]), "type");
            }
        }

        /**
         * {@code extends Foo implements Bar} — the supertypes, minus the ones nobody writes.
         *
         * <p>{@code extends Object} is on every class and is in no source file; {@code extends Enum<E>}
         * is compiler bookkeeping for an enum. Printing either back is a declaration the user never wrote
         * appearing in a box that claims to show what they did.</p>
         */
        /**
         * {@code <E>}, {@code <K, V>} — a generic type's own parameters, as declared.
         *
         * <p>Without these {@code ArrayList} renders as a raw type, which is the one thing it is not:
         * {@code class ArrayList} beside {@code extends AbstractList<E>} says the parameter came from
         * nowhere.</p>
         */
        private static void appendTypeParameters(Signature.Builder out, ITypeBinding type) {
            ITypeBinding[] parameters = type.getTypeParameters();
            if (parameters == null || parameters.length == 0) return;
            out.append("<", "punctuation.bracket");
            for (int i = 0; i < parameters.length; i++) {
                if (i > 0) out.append(",", "punctuation.delimiter").raw(" ");
                out.append(simpleTypeName(parameters[i]), "type");
            }
            out.append(">", "punctuation.bracket");
        }

        /**
         * {@code extends Foo implements Bar} — the supertypes, minus the ones nobody writes, and broken
         * one clause per line when the declaration is long.
         *
         * <p>{@code extends Object} is on every class and is in no source file; {@code extends Enum<E>}
         * is compiler bookkeeping for an enum. Printing either back is a declaration the user never wrote
         * appearing in a box that claims to show what they did.</p>
         *
         * <p>The breaks matter more here than anywhere else: {@code ArrayList} implements four interfaces,
         * so its declaration is 110 characters on one line and unreadable. Both references put
         * {@code extends} and {@code implements} on their own lines and give each interface a line.</p>
         */
        private static void appendSupertypes(Signature.Builder out, ITypeBinding type, boolean broken) {
            ITypeBinding superclass = type.getSuperclass();
            if (superclass != null && !"java.lang.Object".equals(superclass.getQualifiedName())
                    && !type.isEnum() && !type.isInterface()) {
                if (broken) out.newline(); else out.raw(" ");
                out.word("extends", "keyword").append(simpleTypeName(superclass), "type");
            }
            ITypeBinding[] interfaces = type.getInterfaces();
            if (interfaces == null || interfaces.length == 0) return;
            if (broken) out.newline(); else out.raw(" ");
            String keyword = type.isInterface() ? "extends" : "implements";
            out.word(keyword, "keyword");

            // A HANGING INDENT, not a block one, and the difference is visible: the FIRST interface stays
            // on the keyword's line and the rest align under it, so the list reads as one clause with its
            // items stacked. Putting every interface on its own indented line instead leaves `implements`
            // alone on a line of its own, which reads as a heading over a list rather than as a sentence.
            //
            // The pad is the keyword's own length plus its space -- 11 for `implements`, 8 for an
            // interface's `extends` -- so it is derived rather than a magic number.
            //
            // IT IS EXACT ONLY IN A MONOSPACE FONT, and we do not ship one: `font-family` here resolves
            // resource PATHS, and the only two assets are Minecraft.otf and MinecraftRegular.otf, both
            // proportional. IntelliJ's definition block is monospace, which is why a space count aligns
            // for them. So this under-indents by however much narrower a space is than an average glyph,
            // and the honest options are a monospace face for code contexts (which would make this exact
            // with no change here) or a two-column layout in the widget, which needs the clause structure
            // on the seam. Tuning the count to one font's metrics would be neither.
            boolean perLine = broken && interfaces.length > 1;
            String pad = spaces(keyword.length() + 1);
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) {
                    out.append(",", "punctuation.delimiter");
                    if (perLine) out.newline().raw(pad); else out.raw(" ");
                }
                out.append(simpleTypeName(interfaces[i]), "type");
            }
        }

        /** {@code String.repeat} is Java 11, and this class is loaded by the band-8 child. */
        private static String spaces(int count) {
            StringBuilder pad = new StringBuilder(count);
            for (int i = 0; i < count; i++) pad.append(' ');
            return pad.toString();
        }

        /**
         * The declaration <b>exactly as it appears in the file</b>, semicolon and all — or null when it
         * is not in this unit.
         *
         * <h3>Quoted, not assembled</h3>
         *
         * <p>Everything else here builds a declaration out of parts and chooses its own layout: modifiers
         * in a fixed order, a space before the {@code =}, a break when the line runs long. For a symbol on
         * the classpath that is the only option. For one declared in the open file it is strictly worse,
         * and it went wrong in two ways at once.</p>
         *
         * <p>The <b>layout</b> stopped matching: the file has {@code List<Shape> shapes = List.of(} on one
         * line, and the assembled form imposed a break before the {@code =} on top of the author's own
         * wrapping — so the popup showed a shape the file does not contain, with the arguments carrying
         * the file's indentation on top of ours. And the <b>semicolon</b> was missing, because an
         * initializer <em>expression</em> ends before it; the statement is the thing that has one.</p>
         *
         * <p>Both are the same mistake — reconstructing what is already written down. The fragment's
         * parent spans modifiers, type, name, initializer and terminator, so quoting it is one substring,
         * and the captures come off the AST at positions into that very string.</p>
         */
        private Signature quotedDeclaration(CompilationUnit unit, IVariableBinding variable) {
            ASTNode fragment = unit.findDeclaringNode(variable);
            if (!(fragment instanceof VariableDeclarationFragment)) return null;
            ASTNode declaration = fragment.getParent();
            // A PARAMETER or a for-init has a parent that is not a declaration of its own, and quoting
            // that would drag in the whole method header or loop. Those keep the assembled form.
            if (!(declaration instanceof FieldDeclaration)
                    && !(declaration instanceof VariableDeclarationStatement)) {
                return null;
            }
            int from = declaration.getStartPosition();
            int length = declaration.getLength();
            if (from < 0 || length <= 0 || from + length > source.length()) return null;
            int end = from + length;

            // THE DOC COMMENT IS NOT PART OF THE DECLARATION, whatever the node spans. A FieldDeclaration
            // covers its own Javadoc, so quoting it put a paragraph of prose into the SIGNATURE band --
            // the one band meant to hold a single declaration, sitting directly above the band whose
            // whole purpose is documentation.
            //
            // Skipped by READING THE TEXT rather than by asking getJavadoc(), which answers null unless
            // the parser was configured with doc-comment support -- and it still is not, so the node
            // covered the comment while the accessor denied it existed. Scanning also catches the ordinary
            // `//` and `/* */` comments a Javadoc node would never have represented.
            from = skipLeadingComments(from, end);
            if (from >= end) return null;

            String slice = source.substring(from, end);
            boolean tooLong = slice.length() > MAX_DECLARATION_CHARS;
            if (tooLong) slice = slice.substring(0, MAX_DECLARATION_CHARS);

            Dedented body = dedent(slice, indentColumnOf(from));

            Signature.Builder out = new Signature.Builder();
            out.raw(body.text);
            if (tooLong) out.raw("…");
            for (Capture capture : capturesIn(declaration, from, slice)) {
                out.tokenAt(body.map(capture.start), body.map(capture.end), capture.name);
            }
            return out.build();
        }

        /** The first position at or after {@code from} that is neither whitespace nor a comment. */
        private int skipLeadingComments(int from, int end) {
            int at = from;
            while (at < end) {
                if (Character.isWhitespace(source.charAt(at))) {
                    at++;
                } else if (source.startsWith("/*", at)) {
                    int close = source.indexOf("*/", at + 2);
                    if (close < 0 || close + 2 > end) return end;
                    at = close + 2;
                } else if (source.startsWith("//", at)) {
                    int newline = source.indexOf('\n', at);
                    if (newline < 0 || newline > end) return end;
                    at = newline + 1;
                } else {
                    return at;
                }
            }
            return end;
        }

        /** How far into its own line the declaration starts — the indent its first line lost. */
        private int indentColumnOf(int offset) {
            int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
            int column = 0;
            while (lineStart + column < offset && isBlank(source.charAt(lineStart + column))) column++;
            return lineStart + column == offset ? column : 0;
        }

        private static boolean isBlank(char c) {
            return c == ' ' || c == '\t';
        }

        /**
         * The quoted text with its continuation lines re-anchored, plus the offset map that goes with it.
         *
         * <h3>The first line loses an indent the others keep</h3>
         *
         * <p>A slice starts <em>at</em> the declaration, so whatever whitespace preceded it on its line is
         * not in the slice — but every line after the first still carries its full column. Quote a
         * statement indented eight columns whose arguments are indented sixteen and the popup shows a
         * first line at zero with arguments at sixteen: the relative indent doubles, and the deeper the
         * declaration sits in the file the worse it gets.</p>
         *
         * <p>So each continuation line gives back up to {@code column} leading blanks — exactly what the
         * first line lost. Relative indentation is preserved, which is the part that carries meaning; the
         * absolute column is a fact about the file, not about the declaration.</p>
         *
         * <p>Removing characters moves every offset after them, so the captures cannot be applied to the
         * result directly — hence the map. Building it here rather than recomputing positions later is
         * what keeps the text and the colours derived from one pass.</p>
         */
        private static Dedented dedent(String slice, int column) {
            if (column <= 0 || slice.indexOf('\n') < 0) return Dedented.identity(slice);

            StringBuilder out = new StringBuilder(slice.length());
            int[] map = new int[slice.length() + 1];
            boolean lineStart = false;
            int i = 0;
            while (i < slice.length()) {
                if (lineStart) {
                    int given = 0;
                    while (given < column && i < slice.length() && isBlank(slice.charAt(i))) {
                        map[i++] = out.length();
                        given++;
                    }
                    lineStart = false;
                    continue;
                }
                char c = slice.charAt(i);
                map[i++] = out.length();
                out.append(c);
                if (c == '\n') lineStart = true;
            }
            map[slice.length()] = out.length();
            return new Dedented(out.toString(), map);
        }

        private static final class Dedented {
            final String text;
            private final int[] map;

            Dedented(String text, int[] map) {
                this.text = text;
                this.map = map;
            }

            static Dedented identity(String text) {
                return new Dedented(text, null);
            }

            int map(int offset) {
                if (map == null) return offset;
                int at = Math.max(0, Math.min(offset, map.length - 1));
                return map[at];
            }
        }

        /** A declaration longer than this stops being a signature and starts being the file. */
        private static final int MAX_DECLARATION_CHARS = 400;

        /**
         * {@code = null}, {@code = '\t'}, {@code = 1.618_033_988_749d} — the initializer <b>as written</b>.
         *
         * <h3>Read from the AST, not from the folded constant</h3>
         *
         * <p>{@code getConstantValue()} only answers for a compile-time constant, which means primitives
         * and {@code String} and nothing else — so {@code private static final Object NOTHING = null}
         * showed no initializer at all, because {@code null} is not one. There is no way to tell "not a
         * constant" from "the constant is null" through that API, and the field plainly has an
         * initializer either way.</p>
         *
         * <p>The declaring node has it verbatim, and verbatim is also <em>better</em> for the cases the
         * folded value did cover: {@code 1.618_033_988_749d} keeps its underscores and its suffix,
         * {@code 0xDEAD_BEEF} stays hex instead of becoming {@code -559038737}, and a string keeps the
         * escapes the author wrote rather than being folded and re-escaped back into a different spelling
         * of the same bytes. IntelliJ shows the source form for exactly this reason.</p>
         *
         * <p>Falls back to the folded constant for a field on the classpath, where there is no AST.</p>
         */
        private void appendInitializer(Signature.Builder out, CompilationUnit unit,
                                       IVariableBinding variable, boolean broken) {
            ASTNode declaring = unit.findDeclaringNode(variable);
            if (declaring instanceof VariableDeclarationFragment) {
                Expression initializer = ((VariableDeclarationFragment) declaring).getInitializer();
                if (initializer != null) {
                    // BEFORE THE `=`, indented -- IntelliJ's own break for a long field, and it keeps the
                    // declaration (which is what you asked about) on a line of its own.
                    if (broken) out.newline().indent(); else out.raw(" ");
                    out.append("=", "operator").raw(" ");
                    appendInitializerExpression(out, initializer);
                    return;
                }
            }
            Object constant = variable.getConstantValue();
            if (constant == null) return;
            if (broken) out.newline().indent(); else out.raw(" ");
            out.append("=", "operator").raw(" ");
            appendLiteral(out, literalOf(constant),
                    constant instanceof String || constant instanceof Character ? "string"
                            : constant instanceof Boolean ? "boolean" : "number");
        }

        /**
         * The initializer, <b>quoted from the source</b> and coloured from the AST.
         *
         * <h3>The text is the author's; only the colours are ours</h3>
         *
         * <p>This used to re-render the expression node by node, choosing where the spaces went — which
         * meant inventing rules ({@code ", "} after an argument, a space around an operator) that are
         * only ever an approximation of what was actually typed, and getting them wrong in ways nobody
         * can correct from the popup. The author already wrote the spacing and an AST node knows exactly
         * which characters it came from, so slicing them back out is both less code and more faithful:
         * a multi-line {@code List.of(...)} keeps its layout, an aligned array keeps its alignment, and
         * anything this walk does not recognise still comes out verbatim rather than reformatted.</p>
         *
         * <p>Before that it was {@code ASTNode.toString()}, which is JDT's {@code NaiveASTFlattener} —
         * that is where {@code Circle(1.5d),new Rectangle} came from, and it had no captures at all.</p>
         *
         * <h3>Captures come from a separate pass, in source coordinates</h3>
         *
         * <p>Every node reports {@code getStartPosition()} into the same string the slice was cut from,
         * so a token's offset within the slice is one subtraction. That is the whole reason this split
         * works: the text and the captures are derived from the same coordinates rather than being
         * rebuilt in parallel and hoped to agree.</p>
         */
        private void appendInitializerExpression(Signature.Builder out, Expression node) {
            int from = node.getStartPosition();
            int length = node.getLength();
            if (from < 0 || length <= 0 || from + length > source.length()) {
                // No usable position -- a recovered node, or a source we were not handed. The flattened
                // form is a poorer answer and still an answer.
                out.raw(truncated(node.toString()));
                return;
            }

            String slice = source.substring(from, from + length);
            boolean tooLong = slice.length() > MAX_INITIALIZER_CHARS;
            if (tooLong) slice = slice.substring(0, MAX_INITIALIZER_CHARS);

            int base = out.length();
            out.raw(slice);
            if (tooLong) out.raw("\u2026");

            for (Capture capture : capturesIn(node, from, slice)) {
                out.tokenAt(base + capture.start, base + capture.end, capture.name);
            }
        }

        /** How much of an initializer is worth showing before it stops being a declaration. */
        private static final int MAX_INITIALIZER_CHARS = 160;

        private static final class Capture {
            final int start;
            final int end;
            final String name;

            Capture(int start, int end, String name) {
                this.start = start;
                this.end = end;
                this.name = name;
            }
        }

        /**
         * Every part of {@code node} worth colouring, as offsets into the slice that starts at
         * {@code from}.
         *
         * <p>A visitor rather than the recursive render it replaced, because it no longer has to produce
         * text in order — it only has to notice the nodes the scheme has a colour for. Anything it does
         * not visit simply stays the surrounding text's colour, which is the right default: an
         * unrecognised construct reads as plain code rather than as a guess.</p>
         */
        private static List<Capture> capturesIn(ASTNode node, int from, String slice) {
            List<Capture> captures = new ArrayList<>();
            node.accept(new ASTVisitor() {
                private void mark(ASTNode at, String name) {
                    int start = at.getStartPosition() - from;
                    int end = start + at.getLength();
                    if (start >= 0 && end <= slice.length() && end > start) {
                        captures.add(new Capture(start, end, name));
                    }
                }

                /**
                 * A literal, split so its ESCAPES get their own capture.
                 *
                 * <p>One `string` span over the whole thing is what the AST would give, and it is a
                 * poorer rendering than the editor three lines above -- `string.escape` is in the
                 * vocabulary and every scheme defines it. There is no node for an escape, so the only
                 * place this can come from is the characters themselves.</p>
                 */
                private void markLiteral(ASTNode at) {
                    int start = at.getStartPosition() - from;
                    int end = start + at.getLength();
                    if (start < 0 || end > slice.length() || end <= start) return;
                    int cursor = start;
                    while (cursor < end) {
                        int escape = slice.indexOf('\\', cursor);
                        if (escape < 0 || escape >= end - 1) break;
                        int stop = slice.charAt(escape + 1) == 'u'
                                ? Math.min(end, escape + 6) : Math.min(end, escape + 2);
                        if (escape > cursor) captures.add(new Capture(cursor, escape, "string"));
                        captures.add(new Capture(escape, stop, "string.escape"));
                        cursor = stop;
                    }
                    if (cursor < end) captures.add(new Capture(cursor, end, "string"));
                }

                /**
                 * A <b>text block</b>, reached by class name because its type cannot be named here.
                 *
                 * <p>{@code TextBlock} is a Java 13 AST node and this class is loaded by the band-8
                 * child, so a {@code visit(TextBlock)} override would put the type in a method signature
                 * — resolved at class load, and {@code NoClassDefFoundError} on the oldest band for a
                 * construct that band cannot parse anyway. The alternative was leaving it uncoloured,
                 * which is what it was: a whole SQL statement rendering as plain text beside a properly
                 * coloured declaration.</p>
                 *
                 * <p>{@code getNodeType()} would work too and would be a bare {@code 105} here; the
                 * class name says what it is.</p>
                 */
                @Override
                public boolean preVisit2(ASTNode it) {
                    if ("TextBlock".equals(it.getClass().getSimpleName())) {
                        markLiteral(it);
                        return false;
                    }
                    return true;
                }

                @Override public boolean visit(Modifier it) { mark(it, "keyword"); return false; }
                @Override public boolean visit(PrimitiveType it) { mark(it, "type.builtin"); return false; }
                @Override public boolean visit(MarkerAnnotation it) { mark(it, "attribute"); return false; }

                @Override public boolean visit(NumberLiteral it) { mark(it, "number"); return false; }
                @Override public boolean visit(BooleanLiteral it) { mark(it, "boolean"); return false; }
                @Override public boolean visit(NullLiteral it) { mark(it, "constant.builtin"); return false; }
                @Override public boolean visit(CharacterLiteral it) { markLiteral(it); return false; }
                @Override public boolean visit(StringLiteral it) { markLiteral(it); return false; }

                /**
                 * {@code new} has NO NODE of its own -- a ClassInstanceCreation simply begins with it --
                 * so the only way to colour it is to claim the three characters the creation starts with.
                 * Checked against the text rather than assumed, since a recovered node may start
                 * somewhere else entirely.
                 */
                @Override
                public boolean visit(ClassInstanceCreation it) {
                    int start = it.getStartPosition() - from;
                    if (start >= 0 && start + 3 <= slice.length()
                            && slice.startsWith("new", start)) {
                        captures.add(new Capture(start, start + 3, "keyword"));
                    }
                    return true;
                }

                @Override
                public boolean visit(SimpleType it) {
                    mark(it, "type");
                    return false;
                }

                @Override
                public boolean visit(MethodInvocation it) {
                    mark(it.getName(), "function.call");
                    // The receiver and the arguments are still worth visiting; only the NAME is claimed
                    // here, or a call's whole span would take one colour.
                    return true;
                }

                @Override
                public boolean visit(SimpleName it) {
                    IBinding binding = it.resolveBinding();
                    if (binding instanceof ITypeBinding) {
                        mark(it, "type");
                    } else if (binding instanceof IVariableBinding) {
                        mark(it, captureForVariable((IVariableBinding) binding));
                    }
                    return false;
                }
            });
            return captures;
        }

        /**
         * A string or char literal, with its <b>escape sequences captured separately</b>.
         *
         * <p>{@code "tab:\t newline:\\n"} drawn in one flat colour is exactly what the editor does not
         * do: {@code string.escape} is in the vocabulary and every scheme defines it, so a literal in the
         * popup was a visibly poorer rendering of the same text three lines above it. Both references
         * colour escapes distinctly, and it is the one part of a string that is not the string.</p>
         */
        private static void appendLiteral(Signature.Builder out, String literal, String capture) {
            int at = 0;
            while (at < literal.length()) {
                int escape = literal.indexOf('\\', at);
                if (escape < 0 || escape + 1 >= literal.length()) break;
                // \\uXXXX is six characters; every other escape is two.
                int end = literal.charAt(escape + 1) == 'u'
                        ? Math.min(literal.length(), escape + 6) : escape + 2;
                out.append(literal.substring(at, escape), capture);
                out.append(literal.substring(escape, end), "string.escape");
                at = end;
            }
            out.append(literal.substring(at), capture);
        }

        private static String truncated(String rendered) {
            String flattened = rendered.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
            return flattened.length() <= MAX_CONSTANT_CHARS ? flattened
                    : flattened.substring(0, MAX_CONSTANT_CHARS) + "…";
        }

        /**
         * One annotation member value — and JDT hands back <b>five different things</b> here.
         *
         * <p>{@code getValue()} answers a boxed primitive or a {@code String} for the simple cases, an
         * {@link ITypeBinding} for a {@code Class} literal, an {@link IVariableBinding} for an enum
         * constant, an {@link IAnnotationBinding} for a nested annotation, and an {@code Object[]} for
         * any array — including the single-element array a lone {@code "unused"} becomes.</p>
         *
         * <p>Every one of those except the first prints as a JVM identity string through
         * {@code String.valueOf}, which is how {@code @SuppressWarnings("unused")} rendered as
         * {@code @SuppressWarnings([Ljava.lang.Object;@c3d4bd7)}. Not a formatting slip: it is four
         * distinct shapes silently falling through one branch that only ever handled the fifth.</p>
         */
        private static void appendAnnotationValue(Signature.Builder out, Object value) {
            if (value instanceof Object[]) {
                Object[] elements = (Object[]) value;
                // A SINGLE ELEMENT IS WRITTEN BARE in source -- @SuppressWarnings("unused"), never
                // @SuppressWarnings({"unused"}) -- so printing the braces back shows something nobody wrote.
                if (elements.length == 1) {
                    appendAnnotationValue(out, elements[0]);
                    return;
                }
                out.append("{", "punctuation.bracket");
                for (int i = 0; i < elements.length; i++) {
                    if (i > 0) out.append(",", "punctuation.delimiter").raw(" ");
                    appendAnnotationValue(out, elements[i]);
                }
                out.append("}", "punctuation.bracket");
                return;
            }
            if (value instanceof ITypeBinding) {
                out.append(simpleTypeName((ITypeBinding) value), "type").append(".class", "keyword");
                return;
            }
            if (value instanceof IVariableBinding) {
                out.append(((IVariableBinding) value).getName(), "constant");
                return;
            }
            if (value instanceof IAnnotationBinding) {
                appendAnnotations(out, new IAnnotationBinding[] { (IAnnotationBinding) value }, false);
                return;
            }
            out.append(literalOf(value), value instanceof String || value instanceof Character ? "string"
                    : value instanceof Boolean ? "boolean" : "number");
        }

        /**
         * A constant rendered as the <b>literal that would produce it</b> — {@code '\t'}, not a tab.
         *
         * <p>{@code TAB = '\t'} folded to the tab character itself and went into the signature raw, where
         * it drew as a missing glyph: the popup said {@code private static final char TAB = □}. A control
         * character is not a rendering problem to work around, it is the wrong text — what a declaration
         * shows is the literal, and the literal has two quotes and a backslash in it.</p>
         *
         * <p>Truncated too, because {@code String} constants in a real file include regexes, Windows
         * paths and <b>text blocks</b>: this fixture's {@code QUERY} is a six-line SQL statement, and its
         * newlines would go straight into a line the popup draws with {@code white-space: nowrap}.</p>
         */
        private static String literalOf(Object constant) {
            if (constant instanceof Character) {
                return "'" + escaped(String.valueOf((char) (Character) constant), '\'') + "'";
            }
            if (constant instanceof String) {
                String value = (String) constant;
                boolean tooLong = value.length() > MAX_CONSTANT_CHARS;
                if (tooLong) value = value.substring(0, MAX_CONSTANT_CHARS);
                return '"' + escaped(value, '"') + (tooLong ? "…\"" : "\"");
            }
            return String.valueOf(constant);
        }

        /** How much of a string constant is worth showing before it stops being a signature. */
        private static final int MAX_CONSTANT_CHARS = 120;

        private static String escaped(String raw, char quote) {
            StringBuilder out = new StringBuilder(raw.length() + 8);
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                switch (c) {
                    case '\t': out.append("\\t"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\b': out.append("\\b"); break;
                    case '\f': out.append("\\f"); break;
                    case '\\': out.append("\\\\"); break;
                    default:
                        if (c == quote) {
                            out.append('\\').append(c);
                        } else if (c < 0x20 || c == 0x7F) {
                            // Anything else unprintable, spelled the way source would spell it rather
                            // than emitted raw to draw as tofu.
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                }
            }
            return out.toString();
        }

        /**
         * {@code type.builtin} for a primitive, {@code type} for everything else.
         *
         * <p>Not cosmetic: the editor's grammar draws {@code int}, {@code char} and {@code void} as
         * builtins, so a flat {@code type} made the popup disagree with the code two lines behind it --
         * exactly the drift that sharing one capture vocabulary between them was meant to make
         * impossible.</p>
         */
        private static String typeCapture(ITypeBinding type) {
            return type != null && type.isPrimitive() ? "type.builtin" : "type";
        }

        private static String declarationKeyword(ITypeBinding type) {
            if (type.isAnnotation()) return "@interface";
            if (type.isInterface()) return "interface";
            if (type.isEnum()) return "enum";
            if (type.isRecord()) return "record";
            return "class";
        }

        private static String captureForVariable(IVariableBinding variable) {
            if (variable.isEnumConstant()) return "constant";
            if (variable.isParameter()) return "variable.parameter";
            if (variable.isField()) {
                return Modifier.isStatic(variable.getModifiers())
                        && Modifier.isFinal(variable.getModifiers())
                        ? "constant" : "variable.member";
            }
            return "variable";
        }

        /**
         * {@code Map<String, List<Integer>>} rather than {@code java.util.Map<java.lang.String, …>}.
         *
         * <p>The qualified form is what {@code getQualifiedName} answers and it is unreadable in a
         * signature — a two-argument generic becomes eighty characters of package names. Both references
         * show simple names here for the same reason.</p>
         */
        private static String simpleTypeName(ITypeBinding type) {
            if (type == null) return "";
            String name = type.getName();
            return name == null || name.isEmpty() ? type.getQualifiedName() : name;
        }

        private static Set<SymbolModifier> modifiersOf(IBinding binding) {
            Set<SymbolModifier> modifiers = EnumSet.noneOf(SymbolModifier.class);
            int flags = binding.getModifiers();
            if (Modifier.isStatic(flags)) modifiers.add(SymbolModifier.STATIC);
            if (Modifier.isFinal(flags)) modifiers.add(SymbolModifier.FINAL);
            if (Modifier.isAbstract(flags)) modifiers.add(SymbolModifier.ABSTRACT);
            if (binding.isDeprecated()) modifiers.add(SymbolModifier.DEPRECATED);
            return modifiers;
        }

        /**
         * Where a binding is declared, when the declaration is <b>in this file</b>.
         *
         * <p>{@code findDeclaringNode} answers only for the unit it is asked, which is exactly the
         * honest scope here: a member of a compiled class on the classpath has no source attached, and
         * inventing a location for it would be worse than saying nothing. Cross-file go-to needs the
         * workspace, and that is M11's problem.</p>
         */
        private static DeclarationSite declarationOf(CompilationUnit unit, IBinding binding) {
            ASTNode declaration = unit.findDeclaringNode(binding);
            if (declaration == null) return null;
            ASTNode named = declaration instanceof VariableDeclarationFragment
                    ? ((VariableDeclarationFragment) declaration).getName() : declaration;
            int start = named.getStartPosition();
            int line = unit.getLineNumber(start);
            if (line < 1) return null;
            int endLine = unit.getLineNumber(start + named.getLength());
            return DeclarationSite.here(
                    new TextPoint(line - 1, unit.getColumnNumber(start)),
                    new TextPoint(Math.max(line, endLine) - 1,
                            unit.getColumnNumber(start + named.getLength())));
        }

        /**
         * The type expected at {@code offset} — the query completion ranking is built on.
         *
         * <p>Three contexts, which are the ones that carry most of the value: the right-hand side of an
         * assignment, an argument position, and a return. <b>Deliberately not exhaustive</b> — a
         * conditional's branches, a cast, an array initialiser and a lambda body all have expected types
         * too. Each is a small addition and none is needed before completion exists to consume it, so
         * they are absent rather than half-written.</p>
         */
        @Override
        public TypeRef expectedTypeAt(int offset) {
            CompilationUnit resolved = unit;
            if (resolved == null) return null;
            ASTNode node = NodeFinder.perform(resolved, offset, 0);
            while (node != null) {
                ASTNode parent = node.getParent();
                if (parent instanceof Assignment && ((Assignment) parent).getRightHandSide() == node) {
                    return typeRef(((Assignment) parent).getLeftHandSide().resolveTypeBinding());
                }
                if (parent instanceof VariableDeclarationFragment
                        && ((VariableDeclarationFragment) parent).getInitializer() == node) {
                    IVariableBinding variable = ((VariableDeclarationFragment) parent).resolveBinding();
                    if (variable != null) return typeRef(variable.getType());
                }
                if (parent instanceof ReturnStatement) {
                    IMethodBinding method = enclosingMethod(parent);
                    if (method != null) return typeRef(method.getReturnType());
                }
                if (parent instanceof MethodInvocation) {
                    TypeRef parameter = parameterTypeFor((MethodInvocation) parent, node);
                    if (parameter != null) return parameter;
                }
                node = parent;
            }
            return null;
        }

        private static IMethodBinding enclosingMethod(ASTNode node) {
            ASTNode walk = node;
            while (walk != null && !(walk instanceof org.eclipse.jdt.core.dom.MethodDeclaration)) {
                walk = walk.getParent();
            }
            return walk == null ? null
                    : ((org.eclipse.jdt.core.dom.MethodDeclaration) walk).resolveBinding();
        }

        /** The declared parameter type for whichever argument slot {@code argument} occupies. */
        private static TypeRef parameterTypeFor(MethodInvocation invocation, ASTNode argument) {
            IMethodBinding method = invocation.resolveMethodBinding();
            if (method == null) return null;
            List<?> arguments = invocation.arguments();
            int index = arguments.indexOf(argument);
            if (index < 0) return null;
            ITypeBinding[] parameters = method.getParameterTypes();
            if (index >= parameters.length) {
                // VARARGS: every argument past the declared count takes the trailing array's ELEMENT
                // type. Answering the array type instead would rank arrays first at a position where an
                // array is exactly what nobody is about to type.
                if (!method.isVarargs() || parameters.length == 0) return null;
                ITypeBinding trailing = parameters[parameters.length - 1];
                return typeRef(trailing.isArray() ? trailing.getComponentType() : trailing);
            }
            return typeRef(parameters[index]);
        }

        /**
         * Every member of {@code type} visible from {@code contextOffset}.
         *
         * <h3>Three filters, and each one is a bug that a naive list ships</h3>
         *
         * <ul>
         *   <li><b>Synthetic and bridge methods are dropped.</b> The compiler adds them — a bridge
         *       exists so an overridden generic method links, and it has the <em>erased</em>
         *       signature. Offering them shows {@code compareTo(Object)} beside
         *       {@code compareTo(String)} on every {@code Comparable}, which is noise the author
         *       cannot act on and cannot explain.</li>
         *   <li><b>Accessibility is computed from the asking context</b>, not from the type. A private
         *       member is a member from inside its own class and not from outside it, and a protected
         *       one depends on the asking type's hierarchy. {@code isSubTypeCompatible} is the
         *       binding's own answer, so the rule is JDT's rather than a reimplementation of JLS 6.6.</li>
         *   <li><b>Superclasses and interfaces are walked</b>, because a member list that stopped at
         *       the declared type would omit {@code toString} from everything.</li>
         * </ul>
         *
         * <p>Generic substitution needs no work here and that is the whole point of {@link TypeRef}
         * carrying a binding: ask {@code List<String>} for its methods and JDT answers
         * {@code String get(int)}. Asking a name would have answered {@code E get(int)}.</p>
         */
        @Override
        public List<SymbolInfo> membersOf(TypeRef type, int contextOffset) {
            List<SymbolInfo> members = new ArrayList<>();
            CompilationUnit resolved = unit;
            if (resolved == null || !(type instanceof EcjTypeRef)) return members;

            ITypeBinding asking = enclosingTypeAt(resolved, contextOffset);
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();

            // THE WHOLE SUPERCLASS CHAIN FIRST, THEN THE INTERFACES.
            //
            // The dedup keeps whichever declaration is reached first, so this order decides which of several
            // declarations of one method the list describes -- and a class's own implementation is what a
            // caller would actually be invoking. Interleaving them (each class, then its interfaces, then
            // its superclass) let `List.isEmpty()` win over `AbstractCollection.isEmpty()`, so completing on
            // an AbstractList reported isEmpty as ABSTRACT: true of the declaration found, false of the
            // method that would run, and visible as a wrong icon on a method that plainly has a body.
            //
            // Interfaces are still walked, and still after: a method declared only on an interface has no
            // class declaration to lose to.
            List<ITypeBinding> interfaces = new ArrayList<>();
            for (ITypeBinding current = ((EcjTypeRef) type).binding();
                 current != null; current = current.getSuperclass()) {
                collectMembers(current, asking, seen, members);
                java.util.Collections.addAll(interfaces, current.getInterfaces());
            }
            for (int i = 0; i < interfaces.size(); i++) {
                ITypeBinding face = interfaces.get(i);
                collectMembers(face, asking, seen, members);
                // Breadth-first through the interface graph, appending as we go -- an interface may extend
                // others, and a default method three levels up is still reachable.
                java.util.Collections.addAll(interfaces, face.getInterfaces());
                if (interfaces.size() > MAX_INTERFACE_WALK) break;
            }
            return members;
        }

        /** A guard on the interface graph, not a budget: a cycle here would append for ever. */
        private static final int MAX_INTERFACE_WALK = 512;

        private static void collectMembers(ITypeBinding owner, ITypeBinding asking,
                                           java.util.Set<String> seen, List<SymbolInfo> into) {
            for (IMethodBinding method : owner.getDeclaredMethods()) {
                if (method.isConstructor() || method.isSynthetic()) continue;
                // A BRIDGE HAS THE ERASED SIGNATURE and exists only so an override links. JDT does not
                // expose isBridge() on every band, so the synthetic flag plus the modifier bit is the
                // portable test -- 0x0040 is ACC_BRIDGE, which is also ACC_VOLATILE for a field and
                // therefore only meaningful on a method.
                if ((method.getModifiers() & 0x0040) != 0) continue;
                if (!isVisible(method, owner, asking)) continue;
                // THE KEY IS THE FULL ERASED SIGNATURE, not the arity.
                //
                // `name + "/" + parameterCount` collapsed every one-argument overload into one row:
                // System.out offered println() and println(boolean) and nothing else, where there are ten.
                // The dedup exists to stop an override appearing twice -- once from the subclass and once
                // from the supertype it overrides -- and that needs the parameter TYPES, which is what
                // "same method" actually means. Erased, so an override with a substituted generic still
                // matches the declaration it overrides.
                if (!seen.add(erasedSignatureOf(method))) continue;
                into.add(new SymbolInfo(method.getName(), SymbolKind.METHOD,
                        typeRef(method.getReturnType()), owner.getQualifiedName(), null,
                        modifiersOf(method), null, parameterTypesOf(method)));
            }
            for (IVariableBinding field : owner.getDeclaredFields()) {
                if (field.isSynthetic()) continue;
                if (!isVisible(field, owner, asking)) continue;
                if (!seen.add("#" + field.getName())) continue;
                into.add(new SymbolInfo(field.getName(),
                        field.isEnumConstant() ? SymbolKind.ENUM_MEMBER : SymbolKind.FIELD,
                        typeRef(field.getType()), owner.getQualifiedName(), null,
                        modifiersOf(field), null));
            }
        }

        /**
         * A method's declared parameter types, already generic-substituted by JDT.
         *
         * <p>Types rather than names, and that is a limit rather than a choice: JDT reports real parameter
         * names only where it has source or a {@code -parameters} build, and answers {@code arg0} otherwise
         * — which is most of the classpath. A label reading {@code getProperty(String, String)} is what
         * Eclipse itself shows and is honest; {@code getProperty(String arg0, String arg1)} is confidently
         * wrong and takes up more room saying it.</p>
         *
         * <p>The <b>varargs</b> tail keeps its array type here. Rendering it as {@code String...} is a
         * display decision and belongs where the label is built, not in what the engine reports.</p>
         */
        private static List<TypeRef> parameterTypesOf(IMethodBinding method) {
            ITypeBinding[] declared = method.getParameterTypes();
            if (declared.length == 0) return List.of();
            List<TypeRef> types = new ArrayList<>(declared.length);
            for (ITypeBinding parameter : declared) types.add(typeRef(parameter));
            return types;
        }

        /** {@code println(java.lang.String)} — what "the same method" means for deduplication. */
        private static String erasedSignatureOf(IMethodBinding method) {
            StringBuilder signature = new StringBuilder(method.getName()).append('(');
            ITypeBinding[] parameters = method.getParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                if (i > 0) signature.append(',');
                ITypeBinding erasure = parameters[i] == null ? null : parameters[i].getErasure();
                signature.append(erasure == null ? "?" : erasure.getQualifiedName());
            }
            return signature.append(')').toString();
        }

        /** JLS 6.6, asked of the bindings rather than reimplemented. */
        private static boolean isVisible(IBinding member, ITypeBinding owner, ITypeBinding asking) {
            int flags = member.getModifiers();
            if (Modifier.isPublic(flags)) return true;
            if (asking == null) return false;
            if (asking.isEqualTo(owner)) return true;
            if (Modifier.isPrivate(flags)) return false;
            if (Modifier.isProtected(flags)) return asking.isSubTypeCompatible(owner);
            // PACKAGE-PRIVATE. Same package, and a null package on either side means the default
            // package, which two types share only if neither has one.
            return owner.getPackage() != null && asking.getPackage() != null
                    && owner.getPackage().getName().equals(asking.getPackage().getName());
        }

        /**
         * Everything usable unqualified at {@code offset}, nearest scope first.
         *
         * <h3>Walking out from the caret, not down from the unit</h3>
         *
         * <p>A visitor over the whole compilation unit would collect every local in every method and then
         * have to work out which are in scope — which is the same walk, done backwards, with a filter that
         * is easy to get subtly wrong. Starting at the node under the caret and walking to the root visits
         * exactly the enclosing scopes and nothing else, and the order it produces is already the proximity
         * order the ranking wants.</p>
         *
         * <h3>"Declared before the caret" is a real filter, and only for locals</h3>
         *
         * <p>A local is in scope from its declaration to the end of its block, so a local declared below the
         * caret must not be offered — completing it produces "cannot be resolved" on a name the list just
         * suggested. Fields and methods have no such rule: JLS lets a method refer to a field declared later
         * in the class, so filtering them by position would hide half of a class from itself.</p>
         */
        @Override
        public List<SymbolInfo> symbolsInScope(int offset) {
            List<SymbolInfo> found = new ArrayList<>();
            CompilationUnit resolved = unit;
            if (resolved == null) return found;

            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            ASTNode node = NodeFinder.perform(resolved, Math.max(0, Math.min(offset, resolved.getLength())), 0);
            for (ASTNode walk = node; walk != null; walk = walk.getParent()) {
                collectLocalsDeclaredIn(walk, offset, seen, found);
                if (walk instanceof org.eclipse.jdt.core.dom.MethodDeclaration) {
                    org.eclipse.jdt.core.dom.MethodDeclaration method =
                            (org.eclipse.jdt.core.dom.MethodDeclaration) walk;
                    for (Object parameter : method.parameters()) {
                        addVariable((SingleVariableDeclaration) parameter, SymbolKind.PARAMETER, seen, found);
                    }
                }
                if (walk instanceof org.eclipse.jdt.core.dom.AbstractTypeDeclaration) {
                    ITypeBinding type = ((org.eclipse.jdt.core.dom.AbstractTypeDeclaration) walk).resolveBinding();
                    // THE SAME collector the dot path uses, so a field is described identically whether it
                    // was reached by name or through a receiver. Two describers is two answers to what a
                    // member's detail column says.
                    if (type != null) collectMembers(type, type, seen, found);
                }
            }
            return found;
        }

        /**
         * Locals declared directly in {@code scope}, before {@code offset}.
         *
         * <p>Direct children only, deliberately: a nested block's locals are not in scope out here, and
         * recursing would offer them. The enclosing walk visits each scope in turn, so every level is
         * reached exactly once and nothing is missed by not recursing.</p>
         */
        private static void collectLocalsDeclaredIn(ASTNode scope, int offset,
                                                    java.util.Set<String> seen, List<SymbolInfo> into) {
            if (scope instanceof Block) {
                for (Object statement : ((Block) scope).statements()) {
                    if (!(statement instanceof VariableDeclarationStatement)) continue;
                    VariableDeclarationStatement declaration = (VariableDeclarationStatement) statement;
                    if (declaration.getStartPosition() > offset) continue;
                    for (Object fragment : declaration.fragments()) {
                        addFragment((VariableDeclarationFragment) fragment, seen, into);
                    }
                }
                return;
            }
            // The three declaring statements that are not blocks. Each binds a name for the body it
            // heads, and each is otherwise invisible to the walk above -- a for-loop's index is the
            // single most likely thing to be completed inside its own body.
            if (scope instanceof EnhancedForStatement) {
                addVariable(((EnhancedForStatement) scope).getParameter(),
                        SymbolKind.LOCAL_VARIABLE, seen, into);
            } else if (scope instanceof CatchClause) {
                addVariable(((CatchClause) scope).getException(), SymbolKind.LOCAL_VARIABLE, seen, into);
            } else if (scope instanceof ForStatement) {
                for (Object initialiser : ((ForStatement) scope).initializers()) {
                    if (!(initialiser instanceof VariableDeclarationExpression)) continue;
                    for (Object fragment : ((VariableDeclarationExpression) initialiser).fragments()) {
                        addFragment((VariableDeclarationFragment) fragment, seen, into);
                    }
                }
            } else if (scope instanceof org.eclipse.jdt.core.dom.LambdaExpression) {
                for (Object parameter : ((org.eclipse.jdt.core.dom.LambdaExpression) scope).parameters()) {
                    if (parameter instanceof SingleVariableDeclaration) {
                        addVariable((SingleVariableDeclaration) parameter, SymbolKind.PARAMETER, seen, into);
                    } else if (parameter instanceof VariableDeclarationFragment) {
                        addFragment((VariableDeclarationFragment) parameter, seen, into);
                    }
                }
            }
        }

        private static void addFragment(VariableDeclarationFragment fragment,
                                        java.util.Set<String> seen, List<SymbolInfo> into) {
            IVariableBinding binding = fragment.resolveBinding();
            String name = fragment.getName().getIdentifier();
            if (!seen.add(name)) return;
            into.add(new SymbolInfo(name, SymbolKind.LOCAL_VARIABLE,
                    binding == null ? null : typeRef(binding.getType()), null, null,
                    binding == null ? java.util.Set.of() : modifiersOf(binding), null));
        }

        private static void addVariable(SingleVariableDeclaration declaration, SymbolKind kind,
                                        java.util.Set<String> seen, List<SymbolInfo> into) {
            IVariableBinding binding = declaration.resolveBinding();
            String name = declaration.getName().getIdentifier();
            if (!seen.add(name)) return;
            into.add(new SymbolInfo(name, kind,
                    binding == null ? null : typeRef(binding.getType()), null, null,
                    binding == null ? java.util.Set.of() : modifiersOf(binding), null));
        }

        /** The type a caret sits inside, which is what accessibility is judged from. */
        private static ITypeBinding enclosingTypeAt(CompilationUnit unit, int offset) {
            ASTNode node = NodeFinder.perform(unit, offset, 0);
            while (node != null && !(node instanceof org.eclipse.jdt.core.dom.AbstractTypeDeclaration)) {
                node = node.getParent();
            }
            return node == null ? null
                    : ((org.eclipse.jdt.core.dom.AbstractTypeDeclaration) node).resolveBinding();
        }

        @Override
        public void close() {
            // The AST and every binding hanging off it. Dropping the reference is the whole release --
            // JDT has no native resources here -- but it is worth being explicit, because a resolved
            // unit with bindings is large and one is held per open document.
            unit = null;
        }

        private static TypeRef typeRef(ITypeBinding type) {
            return type == null ? null : new EcjTypeRef(type);
        }
    }

    /**
     * A type, carrying its binding across the bridge intact.
     *
     * <p>This is why {@code TypeRef} is an interface rather than a string. The host reads only
     * {@link #displayName()} and {@link #qualifiedName()}; when it hands one back, the engine casts and
     * has the binding — so {@code List<String>} survives the round trip as a <em>type</em> rather than
     * as text, and its members come back as {@code String get(int)} rather than {@code E get(int)}.</p>
     */
    static final class EcjTypeRef implements TypeRef {

        private final ITypeBinding binding;

        EcjTypeRef(ITypeBinding binding) {
            this.binding = binding;
        }

        ITypeBinding binding() {
            return binding;
        }

        @Override
        public String displayName() {
            String name = binding.getName();
            return name == null || name.isEmpty() ? binding.getQualifiedName() : name;
        }

        @Override
        public String qualifiedName() {
            ITypeBinding erasure = binding.getErasure();
            String name = (erasure == null ? binding : erasure).getQualifiedName();
            return name == null ? "" : name;
        }

        @Override
        public String toString() {
            return displayName();
        }
    }
}
