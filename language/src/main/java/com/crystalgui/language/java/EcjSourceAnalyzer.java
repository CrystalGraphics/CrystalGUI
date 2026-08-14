package com.crystalgui.language.java;

import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.DeclarationSite;
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
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
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
        /** Declaration rendering, which shares nothing with this class but the AST. @see JavaSignatures */
        private final JavaSignatures signatures;
        /** Locals and parameters written to after their declaration. @see #collectReassigned */
        private Set<String> reassigned;

        EcjAnalysis(CompilationUnit unit, String source, long version) {
            this.unit = unit;
            this.source = source == null ? "" : source;
            this.version = version;
            this.signatures = new JavaSignatures(unit, this.source);
            this.reassigned = unit == null ? Set.of() : collectReassigned(unit);
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
                        // THE `@` IS PART OF THE ANNOTATION, and a SimpleName does not include it: the
                        // name node starts one character in, so the marker drew in the default colour
                        // beside a yellow name. IntelliJ's DEFAULT_METADATA covers the whole of
                        // `@SuppressWarnings`, and it should -- the `@` is what makes the name metadata
                        // rather than a type reference, so it is the last part that should be left out.
                        int start = "attribute".equals(capture)
                                ? annotationStartFor(name) : name.getStartPosition();
                        tokens.add(new SyntaxToken(start,
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
        private String captureFor(SimpleName name) {
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
                // THREE FACTS ABOUT ONE LOCAL, and IntelliJ draws each of them: what it is, whether
                // it is ever assigned again, and whether it was reached from inside a lambda. The last
                // two are the ones a grammar can never answer, and neither needs dataflow -- both fall
                // out of where the name sits in the tree.
                boolean captured = isCapturedHere(name, variable);
                if (variable.isParameter()) {
                    if (captured) return "variable.captured";
                    return reassigned.contains(variable.getKey())
                            ? "variable.parameter.reassigned" : SymbolKind.PARAMETER.captureName();
                }
                if (captured) return "variable.captured";
                return reassigned.contains(variable.getKey())
                        ? "variable.reassigned" : SymbolKind.LOCAL_VARIABLE.captureName();
            }
            // AN ANNOTATION'S NAME IS METADATA, not a type reference -- DEFAULT_METADATA, yellow, and the
            // third thing this method was flattening. `@SuppressWarnings` resolves to the annotation's
            // TYPE binding, so it came back as `type` and took the default foreground, overwriting the
            // grammar's own `@attribute` capture. It was yellow in the documentation popup the whole time,
            // because JavaSignatures knows the name came from an annotation and says so.
            //
            // Positional rather than kind-based, and it has to be: `@interface Nullable { }` DECLARES a
            // type and is drawn as one, while `@Nullable` USES it as metadata. Same binding, same
            // SymbolKind, two colours -- so the parent chain is the only thing that can tell them apart.
            if (isAnnotationName(name)) return "attribute";
            if (binding instanceof ITypeBinding) {
                // A type NAME only. The grammar gets declarations right; what it cannot do is tell that
                // a bare identifier in an expression is a type rather than a variable.
                //
                // A TYPE VARIABLE IS NOT A CLASS. `<E>` is a placeholder the declaration introduces, and
                // both references give it its own colour; a grammar cannot tell it from a class name
                // because the two are spelled identically.
                return ((ITypeBinding) binding).isTypeVariable()
                        ? SymbolKind.TYPE_PARAMETER.captureName() : SymbolKind.CLASS.captureName();
            }
            if (binding instanceof IMethodBinding) {
                if (((IMethodBinding) binding).isConstructor()) {
                    return SymbolKind.CONSTRUCTOR.captureName();
                }
                return methodCapture(name, (IMethodBinding) binding);
            }
            return null;
        }

        /**
         * A method DECLARATION, a STATIC call and an instance call are three different colours.
         *
         * <h3>This layer was undoing the grammar's own split</h3>
         *
         * <p>{@code Queries.splitMethodDeclarationsFromCalls} exists precisely so a declaration and an
         * invocation can be told apart, because the vendored query captured both as {@code @function.method}
         * — and the scheme has carried {@code --syntax-function-call} at the plain identifier colour ever
         * since, matching {@code DEFAULT_FUNCTION_CALL baseAttributes="DEFAULT_IDENTIFIER"}.</p>
         *
         * <p>None of it had any effect, because <b>semantic tokens replace grammar tokens</b> and this
         * method returned {@code function.method} for every {@code IMethodBinding} it saw. So every call
         * on screen was blue, the grammar's careful distinction was overwritten by a coarser answer from
         * the layer that is supposed to know more, and the split looked broken in the query rather than in
         * the engine.</p>
         *
         * <p>Static calls take a slant on top, which is {@code DEFAULT_STATIC_METHOD}'s only difference
         * from the instance one — the same channel a static field already uses to say "this name does not
         * belong to the object in front of you".</p>
         */
        /**
         * Every local or parameter that is <b>assigned after it is declared</b>.
         *
         * <h3>A syntactic scan, not dataflow</h3>
         *
         * <p>The question IntelliJ answers with {@code DEFAULT_REASSIGNED_LOCAL_VARIABLE} is not "what
         * value does this hold" but "is this name ever written to again" — and that is decided by looking
         * for it on the left of an assignment or under a {@code ++}/{@code --}. No flow analysis, no
         * ordering, one pass over the unit.</p>
         *
         * <p>Keyed on {@link IBinding#getKey()} rather than on the binding itself, because JDT does not
         * promise identity across the two visits and a {@code HashSet} of bindings would quietly miss
         * matches.</p>
         */
        private Set<String> collectReassigned(CompilationUnit resolved) {
            final Set<String> keys = new HashSet<>();
            resolved.accept(new ASTVisitor() {
                private void mark(Expression target) {
                    if (!(target instanceof SimpleName)) return;
                    IBinding binding = ((SimpleName) target).resolveBinding();
                    if (!(binding instanceof IVariableBinding)) return;
                    IVariableBinding variable = (IVariableBinding) binding;
                    // A FIELD IS NOT A REASSIGNED LOCAL. Fields are expected to be written to; the
                    // underline exists to flag a local whose value does not stay put.
                    if (variable.isField()) return;
                    keys.add(variable.getKey());
                }

                @Override
                public boolean visit(Assignment node) {
                    mark(node.getLeftHandSide());
                    return true;
                }

                @Override
                public boolean visit(PrefixExpression node) {
                    if (node.getOperator() == PrefixExpression.Operator.INCREMENT
                            || node.getOperator() == PrefixExpression.Operator.DECREMENT) {
                        mark(node.getOperand());
                    }
                    return true;
                }

                @Override
                public boolean visit(PostfixExpression node) {
                    mark(node.getOperand());
                    return true;
                }
            });
            return keys;
        }

        /**
         * Whether {@code use} reaches {@code variable} from <b>inside a lambda or anonymous class the
         * declaration is outside of</b> — IntelliJ's
         * {@code IMPLICIT_ANONYMOUS_CLASS_PARAMETER_ATTRIBUTES}.
         *
         * <p>Worth drawing because a captured local is not an ordinary one: it is effectively final by
         * language rule, it outlives the frame that declared it, and it is the single most useful thing to
         * know when reading a lambda body — which of these names came from outside.</p>
         *
         * <p>Decided by comparing the nearest enclosing lambda of the <em>use</em> against that of the
         * <em>declaration</em>. If the use is inside one the declaration is not, it was captured. No
         * dataflow, no escape analysis: the question is entirely about position.</p>
         */
        private boolean isCapturedHere(SimpleName use, IVariableBinding variable) {
            CompilationUnit resolved = unit;
            if (resolved == null) return false;
            ASTNode useScope = enclosingCapture(use);
            if (useScope == null) return false;
            ASTNode declaration = resolved.findDeclaringNode(variable);
            // A declaration outside this unit cannot be compared, and one that is not found is not
            // evidence of capture -- saying nothing is the right answer for a name we cannot place.
            if (declaration == null) return false;
            return enclosingCapture(declaration) != useScope;
        }

        private static ASTNode enclosingCapture(ASTNode from) {
            for (ASTNode node = from; node != null; node = node.getParent()) {
                if (node instanceof LambdaExpression || node instanceof AnonymousClassDeclaration) {
                    return node;
                }
            }
            return null;
        }

        /**
         * Whether this name is the <b>name of an annotation being applied</b> — {@code @Nullable}, or the
         * {@code Contract} of {@code @Contract(pure = true)}.
         *
         * <p>Walks out through the type nodes a qualified or parameterised name sits under, so
         * {@code @org.jetbrains.annotations.Nullable} is recognised as readily as the simple form. It
         * deliberately does <em>not</em> match a member-value pair's name: {@code pure} is a method on the
         * annotation type, and IntelliJ gives it no colour of its own either.</p>
         */
        /**
         * Where the enclosing annotation begins — its {@code @} — or the name's own start.
         *
         * <p>Only extends over a <b>simple</b> annotation name. For a qualified one the {@code @} is
         * several nodes away and the span between them is the package, which is not metadata and is not
         * IntelliJ's colour either; taking the whole range would paint
         * {@code @org.jetbrains.annotations.Nullable} yellow end to end.</p>
         */
        private static int annotationStartFor(SimpleName name) {
            ASTNode parent = name.getParent();
            return parent instanceof Annotation ? parent.getStartPosition() : name.getStartPosition();
        }

        private static boolean isAnnotationName(SimpleName name) {
            ASTNode node = name.getParent();
            while (node instanceof QualifiedName || node instanceof SimpleType
                    || node instanceof QualifiedType || node instanceof ParameterizedType) {
                node = node.getParent();
            }
            return node instanceof Annotation;
        }

        private static String methodCapture(SimpleName name, IMethodBinding method) {
            if (name.getParent() instanceof MethodDeclaration) return SymbolKind.METHOD.captureName();
            return Modifier.isStatic(method.getModifiers()) ? "function.static" : "function.call";
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
                        .withSignature(signatures.of(binding, kind, name.getIdentifier()));
            }
            if (binding instanceof IMethodBinding) {
                IMethodBinding method = (IMethodBinding) binding;
                ITypeBinding declaring = method.getDeclaringClass();
                SymbolKind kind = method.isConstructor()
                        ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;
                return new SymbolInfo(name.getIdentifier(), kind, typeRef(method.getReturnType()),
                        containerName(declaring), null, modifiers,
                        declarationOf(unit, binding), parameterTypesOf(method))
                        .withSignature(signatures.of(binding, kind, name.getIdentifier()));
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
                        .withSignature(signatures.of(binding, kind, name.getIdentifier()));
            }
            return SymbolInfo.of(name.getIdentifier(), SymbolKind.UNKNOWN);
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
