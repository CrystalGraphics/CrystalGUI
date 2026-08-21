package com.crystalgui.language.java.ecj;

import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.language.java.assist.AttachedSources;
import com.crystalgui.language.java.assist.JavaSignatures;
import com.crystalgui.language.java.fix.Inspection;
import com.crystalgui.language.java.fix.JavaQuickFixes;
import com.crystalgui.language.java.fix.ast.Scopes;
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
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
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
import org.eclipse.jdt.core.dom.IPackageBinding;
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
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nullable;

import java.util.function.Function;
import java.util.Map;
import java.util.Set;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.engine.bridge.TypeBytes;
import com.crystalgui.language.engine.bridge.CodeActionContext;

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

    /**
     * What the classpath cannot supply — the SAME object the compiler resolves against.
     *
     * <p>Volatile because it is installed once during startup, on whichever thread opened the engine,
     * and read on every analysis thereafter — which is a scheduler lane, not that thread.</p>
     */
    private volatile TypeBytes types = TypeBytes.NONE;

    @Override
    public SourceAnalyzer resolveAgainst(TypeBytes types) {
        this.types = types == null ? TypeBytes.NONE : types;
        return this;
    }

    @Override
    public Analysis analyze(String className, String source, List<String> classpath,
                            int releaseLevel, long version) {
        // THE LIVE ROUTE FIRST, where there is one. `ASTParser` can only be told about FILES, so on an
        // obfuscated host it resolves nothing a script actually names -- the compiler saw `Minecraft`
        // and the editor saw `ave.class`, and a script that compiled and ran showed as broken.
        // @see DomResolution
        // THE ENVIRONMENT COMES BACK WITH THE UNIT, because the unit still needs it. @see #live
        INameEnvironment[] keep = new INameEnvironment[1];
        CompilationUnit unit = live(className, source, classpath, releaseLevel, keep);
        if (unit == null) unit = parse(className, source, classpath, releaseLevel, true);
        // JDT COULD NOT FINISH, so ask it for the half it can still do. See parse().
        if (unit == null) unit = parse(className, source, classpath, releaseLevel, false);
        if (unit == null) return null;
        // THE SAME CLASSPATH, handed on rather than re-derived: a signature quoted out of a source
        // archive has to resolve against what this parse resolved against, or the binding keys the two
        // are matched by would not be the same strings. @see AttachedSources
        // AND HOW TO ASK ABOUT A NAME THIS UNIT NEVER MENTIONS -- a documentation link's target. The
        // same classpath and release, so the answer resolves against what this parse resolved against.
        // A probe built by `describeName` gets no describer of its own, which is what stops a link
        // inside a probe's own documentation from starting another probe.
        boolean probe = "$Probe".equals(className);
        return new EcjAnalysis(unit, source, version, releaseLevel,
                AttachedSources.forClasspath(classpath), keep[0],
                probe ? null : name -> describeName(name, classpath, releaseLevel));
    }

    /**
     * A resolved unit built against the live name environment, or null where there is none.
     *
     * <h3>The same environment as the compiler, deliberately</h3>
     *
     * <p>Not an equivalent one — the same {@link TypeBytes}, so the editor and the runner cannot
     * disagree about what exists. Two implementations that happen to agree is what this replaces, and
     * they did not agree: the runner resolved through live bytes and the editor through files.</p>
     *
     * <h3>A fresh environment per analysis, like a compile</h3>
     *
     * <p>{@code ScriptNameEnvironment}'s caches are per instance because a transformer can add a member
     * between one analysis and the next; sharing one across keystrokes would answer from before it. The
     * classpath half is ECJ's own {@code FileSystem}, rebuilt with it — which is what the file-based
     * path did per parse anyway.</p>
     *
     * <p>Null when no platform is registered, which is the harness, every test and a plain JVM: those
     * take the {@code ASTParser} path exactly as before, so the route with the coverage is the route
     * they keep.</p>
     */
    private CompilationUnit live(String className, String source, List<String> classpath,
                                 int releaseLevel, INameEnvironment[] keep) {
        TypeBytes available = types;
        if (available == TypeBytes.NONE || !DomResolution.isAvailable()) return null;
        INameEnvironment environment = null;
        try {
            environment = EcjCompilation.environmentFor(classpath, releaseLevel, available);
            CompilationUnit unit = DomResolution.resolve(new InMemoryUnit(className, source),
                    source.toCharArray(), environment, compilerOptions(releaseLevel),
                    EcjOptions.jlsLevel());
            // KEPT OPEN, and this is the whole method's hazard.
            //
            // A resolved unit does NOT hold its bindings; it resolves them LAZILY, on the first question
            // anyone asks. So the environment they resolve through has to outlive the call that built
            // them -- and this used to clean it up in a `finally`, one statement after the unit was made.
            //
            // Nothing failed visibly. `FileSystem.cleanup()` closes every classpath jar and nulls its
            // handle, and `ClasspathJar.getModulesDeclaringPackage` then rebuilds its package cache from
            // `this.zipFile` -- which is now null. The NPE surfaces through `ClasspathLocation.isPackage`
            // into `LookupEnvironment.askForType`, out of `BinaryTypeBinding.availableMethods`, and JDT's
            // DOM CATCHES IT: `getDeclaredMethods()` logs "Could not retrieve declared methods" with no
            // stack and returns an EMPTY ARRAY. So every binary class reported no methods, while its
            // fields were fine (already resolved) and its interfaces were fine (JDT synthesises those).
            // `System.out.` offered nothing, `String.` offered `compareTo` alone out of `Comparable`, and
            // `Minecraft.` offered `IPlayerUsage`'s three.
            //
            // It needs a Java 8 host to reproduce, which is why it only ever appeared in a 1.7.10 client:
            // from 9 onward the JDK is a JRT filesystem rather than a jar, and `ClasspathJrt` survives the
            // same cleanup -- so every test JVM and the harness resolved `java.lang` regardless.
            if (unit != null) {
                keep[0] = environment;
                environment = null;
            }
            return unit;
        } catch (RuntimeException | LinkageError | AssertionError failed) {
            // Falling back is always available and always correct, so nothing here is worth throwing.
            return null;
        } finally {
            // Only what nobody took ownership of -- a failed resolve, or a null unit.
            cleanupQuietly(environment);
        }
    }

    /** Closes an environment's classpath handles, or does nothing. */
    private static void cleanupQuietly(INameEnvironment environment) {
        if (environment == null) return;
        try {
            environment.cleanup();
        } catch (RuntimeException ignored) {
            // Cleanup failing must not lose an analysis that already succeeded.
        }
    }

    /**
     * One parse attempt, or {@code null} if JDT failed outright.
     *
     * <h3>Why this can fail at all, and why a throw here is worse than anywhere else</h3>
     *
     * <p>JDT's binding layer asserts on its own invariants, and one of them does not hold on real code:
     * a record whose component types are unresolvable makes it tag the canonical constructor as
     * containing missing types and then <em>assert that it did not</em> — {@code AssertionError}, out of
     * {@code createAST}, on a file that is perfectly good Java. The corpus pass found it on its first
     * run and its report named this method.</p>
     *
     * <p>An analysis runs on a scheduler lane, so an {@code Error} escaping here does not degrade the
     * feature — it takes the job down. The document then holds no diagnostics, no colouring and no
     * completions, with nothing on screen to say why, and every later keystroke schedules the same
     * failure. A script declaring a record over a type that is not on the classpath — a mod class, on a
     * server without it — is enough.</p>
     *
     * <h3>The fallback is a real answer, not a null</h3>
     *
     * <p>The failure is in <em>resolution</em>, so the retry turns resolution off. What survives is the
     * whole tree: syntax errors, folding regions, structure, and the grammar-level colouring that never
     * needed bindings. What is lost is the semantic layer — which is exactly the tier that was broken
     * anyway, and its absence is already how this stack spells "not available" (see the three
     * independent tiers in AGENTS.md). Returning {@code null} instead would be indistinguishable from
     * a document nobody has analysed.</p>
     *
     * <p><b>{@code OutOfMemoryError} is rethrown</b> and nothing else is: retrying a second, larger parse
     * after the heap has run out is how a recoverable stall becomes an unrecoverable one. A cancellation
     * cannot arrive here — the stop mechanism is the running <em>script</em>'s interrupt status on the
     * execution lane, and this method runs no user code.</p>
     */
    private static CompilationUnit parse(String className, String source, List<String> classpath,
                                         int releaseLevel, boolean resolveBindings) {
        ASTParser parser = ASTParser.newParser(EcjOptions.jlsLevel());
        parser.setSource(source.toCharArray());
        // THE PATH THE SOURCE ITSELF IMPLIES, not the caller's guess. A file declaring a package
        // and named from its file stem makes ECJ report "the declared package does not match the
        // expected package" on line 1 -- about its own bookkeeping, on the author's first line.
        parser.setUnitName(SourcePackages.unitPath(className, source));
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(resolveBindings);
        // THE TWO THAT MATTER WHILE TYPING. Without them a half-written statement yields an AST with no
        // bindings at all, so every name in the file loses its colour on the keystroke that breaks it
        // and gets it back on the one that fixes it -- which reads as the highlighter flickering rather
        // than as the file being briefly invalid.
        parser.setStatementsRecovery(true);
        parser.setBindingsRecovery(resolveBindings);
        parser.setCompilerOptions(compilerOptions(releaseLevel));

        String[] entries = classpath == null ? new String[0] : classpath.toArray(new String[0]);
        // includeRunningVMBootclasspath = true: rt.jar on Java 8, the jrt image on 9+. Different
        // mechanisms, and which one is used is a property of the host rather than of the jar.
        parser.setEnvironment(entries, new String[0], new String[0], true);

        try {
            return (CompilationUnit) parser.createAST(null);
        } catch (OutOfMemoryError exhausted) {
            throw exhausted;
        } catch (RuntimeException | Error failed) {
            return null;
        }
    }

    private static Map<String, String> compilerOptions(int releaseLevel) {
        Map<String, String> options = EcjOptions.forLevel(releaseLevel);
        // WHAT WE CHOOSE TO REPORT, in one table with a reason per line. Applied here and nowhere else --
        // an attached source is read for its shape, not diagnosed, so it gets the level and none of this.
        options.putAll(EcjProblemPolicy.severities());
        return options;
    }

    /** One resolved file, held on the engine's side. */
    /**
     * What a qualified name refers to — a documentation link's target, resolved.
     *
     * <p><b>A probe unit</b>, which is the same trick {@code InteropResolver} uses to describe a Java
     * type for JavaScript and for the same reason: nothing can hand JDT a name and get a binding, but
     * everything can hand it a file. {@code class $Probe { <name> $x; }} declares a field of the type in
     * question, and asking the resulting analysis about the type's own name is a question it can answer.
     * The unlikely names are IntelliJ's own trick for the same problem.</p>
     *
     * <p><b>A member reference answers with the member</b> — {@link #describeMemberOf}, tried first and
     * falling back to the owning type when it cannot. This used to stop at the type, on the reasoning
     * that picking one overload needs a probe that CALLS the member and the only thing building that
     * shape ({@code InteropResolver.describeMember}) is child-side. Both halves were true and the
     * conclusion was not: a call is not the only construct JDT resolves a member through, and the other
     * one is the very thing the author typed.</p>
     *
     * <p>A reference with no type at all — a bare {@code #member}, meaning "on the class this comment
     * is in" — is answered by {@code describeInThisUnit}, which has the declaration right there. It is
     * never reached here.</p>
     */
    @Nullable
    private SymbolInfo describeName(String name, List<String> classpath, int releaseLevel) {
        if (name == null) return null;
        String bare = name.trim();
        int hash = bare.indexOf('#');
        if (hash == 0) return null;
        if (hash > 0) {
            // THE MEMBER FIRST, THE TYPE AS A FALLBACK. Opening `List` for `{@link List#add}` is a useful
            // answer and the wrong one; opening nothing is worse than either, so a member that will not
            // resolve — a typo, an overload written with argument types nobody has — still lands on the
            // type it was qualified by.
            SymbolInfo member = describeMemberOf(bare, classpath, releaseLevel);
            if (member != null) return member;
            bare = bare.substring(0, hash);
        }
        if (bare.isEmpty() || !bare.matches("[\\w.$]+")) return null;

        String probe = "class $Probe { " + bare + " $x; }";
        try (Analysis analysis = analyze("$Probe", probe, classpath, releaseLevel, 0L)) {
            // AT THE LAST SEGMENT'S FIRST CHARACTER. `resolveAt` wants a position inside the NAME, and a
            // qualified name's earlier segments resolve to packages -- asking at offset zero of
            // `java.util.List` describes `java`, which is a real answer to a question nobody asked.
            int lastDot = bare.lastIndexOf('.');
            int at = probe.indexOf(bare) + (lastDot < 0 ? 0 : lastDot + 1);
            // A PROBE THAT DID NOT COMPILE RESOLVED NOTHING, and that is the only reliable way to ask.
            // JDT RECOVERS an unknown qualified name into a plausible binding rather than failing --
            // `no.such.Type` comes back as a CLASS named `Type` in a container `no.such`, so neither the
            // kind nor the shape of the answer distinguishes it from a real one. The probe declares
            // exactly one thing, so any error in it is about that thing.
            //
            // It matters because the alternative is silent: a link to a class nobody has would open a
            // popup showing its own last segment and nothing else, replacing whatever was being read
            // with strictly less than was already there.
            for (Diagnostic problem : analysis.diagnostics()) {
                if (problem.severity() == DiagnosticSeverity.ERROR) return null;
            }
            return analysis.resolveAt(at);
        } catch (Exception unresolvable) {
            // A name that does not resolve is the ordinary case for a link into a class nobody has on the
            // classpath, not an error worth failing a hover over.
            return null;
        }
    }

    /**
     * The member a {@code Type#member} reference names, or null.
     *
     * <h3>The probe is a doc comment, not a call</h3>
     *
     * <p>The obvious probe writes the call — {@code class $Probe { List $x; void $m() { $x.add(...); } }}
     * — and that is what {@code InteropResolver.describeMember} does, because there it is the only thing
     * available: it starts from a {@code SymbolInfo}, which carries parameter TYPES and no binding, so a
     * call is how it re-derives one. Here the input is the reference the author wrote, and JDT resolves
     * that construct directly: a {@code MethodRef}/{@code MemberRef} inside a {@code Javadoc} node has a
     * real binding, by the same doc-comment support that makes {@code @see} colour by kind.</p>
     *
     * <p>So the probe is <b>one line of documentation over an empty class</b>. It needs no argument
     * values (a call needs one of each declared type, and a type variable does not parse as one), it
     * disambiguates overloads exactly as javadoc's own rules do when the author wrote the types, and it
     * costs one parse rather than a parse plus the arithmetic of building a legal call.</p>
     *
     * <h3>Verified by the NAME, because nothing else fails</h3>
     *
     * <p>An unresolvable reference reports <b>no diagnostic at all</b> — the ~40 javadoc problems are
     * options of their own and are deliberately off, so the error check the type probe relies on is
     * blind here. What can be checked is the answer: a reference JDT could not resolve does not come
     * back as the member, so the member's own simple name is the assertion. Without it a mistyped
     * {@code #ad} would open something plausible.</p>
     */
    @Nullable
    private SymbolInfo describeMemberOf(String reference, List<String> classpath, int releaseLevel) {
        int hash = reference.indexOf('#');
        String type = reference.substring(0, hash);
        String member = reference.substring(hash + 1);
        int bracket = member.indexOf('(');
        String simple = bracket < 0 ? member : member.substring(0, bracket);
        if (type.isEmpty() || simple.isEmpty()) return null;
        if (!type.matches("[\\w.$]+") || !simple.matches("[\\w$]+")) return null;
        // THE ARGUMENT LIST IS PART OF THE REFERENCE and is the only thing that tells two overloads
        // apart, so it travels verbatim -- but it goes into a compiled file, so it is checked rather
        // than trusted. Anything outside a Java type list cannot be a legal reference anyway.
        if (bracket >= 0 && !member.matches("[\\w.$]+\\([\\w.$\\[\\], ]*\\)")) return null;

        String probe = "/** {@link " + reference + "} */\nclass $Probe { }\n";
        try (Analysis analysis = analyze("$Probe", probe, classpath, releaseLevel, 0L)) {
            SymbolInfo resolved = analysis.resolveAt(probe.indexOf('#') + 1);
            return resolved != null && simple.equals(resolved.name()) ? resolved : null;
        } catch (Exception unresolvable) {
            return null;
        }
    }

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

        /**
         * The Java level this file is being analysed at.
         *
         * <p>Carried so a correction can refuse to write source the target cannot compile. Read from the
         * REQUEST rather than from the AST: {@code AST.apiLevel()} is the DOM's API generation, which is
         * how the tree may be <em>inspected</em>, and says nothing about what this file may contain.</p>
         */
        private final int releaseLevel;

        /** The classpath the unit's bindings resolve through — released in {@link #close()}, not before. */
        private INameEnvironment environment;

        /**
         * How to describe a name this unit never mentions — see {@code EcjSourceAnalyzer.describeName}.
         *
         * <p>A function rather than a back-reference to the analyzer, because that is the whole of what is
         * needed and an analysis holding its analyzer is a lifetime question nobody asked. Null on the
         * paths that build an analysis without one, which answer nothing rather than throwing.</p>
         */
        private final Function<String, SymbolInfo> describer;

        EcjAnalysis(CompilationUnit unit, String source, long version, int releaseLevel,
                    AttachedSources attached, INameEnvironment environment) {
            this(unit, source, version, releaseLevel, attached, environment, null);
        }

        EcjAnalysis(CompilationUnit unit, String source, long version, int releaseLevel,
                    AttachedSources attached, INameEnvironment environment,
                    Function<String, SymbolInfo> describer) {
            this.describer = describer;
            this.environment = environment;
            this.unit = unit;
            this.source = source == null ? "" : source;
            this.version = version;
            this.releaseLevel = releaseLevel;
            this.signatures = new JavaSignatures(unit, this.source, this::captureFor, attached);
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
        public List<com.crystalgui.text.lang.CodeAction> codeActionsIn(
                int from, int to, com.crystalgui.language.engine.bridge.CodeActionContext context) {
            return JavaQuickFixes.in(unit, source, version, releaseLevel, from, to, context);
        }

        @Override
        public List<Diagnostic> diagnostics() {
            List<Diagnostic> found = new ArrayList<>();
            CompilationUnit resolved = unit;
            if (resolved == null) return found;
            for (IProblem problem : resolved.getProblems()) {
                DiagnosticSeverity severity = problem.isError() ? DiagnosticSeverity.ERROR
                        : problem.isWarning() ? DiagnosticSeverity.WARNING : DiagnosticSeverity.INFORMATION;
                // THE SAME ANSWER THE QUICK-FIX ROUTER READS. @see ProblemSpans -- a mark computed apart
                // from the range a fix is reachable over is how a squiggle ends up somewhere its own fix
                // cannot be asked for.
                int[] span = ProblemSpans.marked(resolved, problem);
                TextPoint start = pointOf(resolved, span[0]);
                TextPoint end = pointOf(resolved, span[1]);
                // TAGGED HERE, from the same table that decided the problem was worth reporting. A tag
                // is how the text is DRAWN rather than how bad it is -- unused code faded, deprecated
                // code struck through -- and without it every "nothing reads this" arrives as one more
                // underline indistinguishable from a real defect. @see EcjProblemPolicy
                found.add(new Diagnostic(start, end, severity, problem.getMessage(),
                        "java", Integer.toString(problem.getID()),
                        EcjProblemPolicy.tagsFor(problem.getID()), java.util.List.of()));
            }
            found.addAll(inspections(resolved));
            return found;
        }

        /**
         * What this engine reports that <b>ECJ does not</b> — findings of our own, beside the compiler's.
         *
         * <h3>Why there is such a thing at all</h3>
         *
         * <p>A compiler reports what is wrong. An <em>inspection</em> reports what could be better, and
         * every reference treats the two as one list: IntelliJ's Problems panel puts "Anonymous new
         * Comparator&lt;Message&gt;() can be replaced with lambda" directly beside "Class 'Inner' is never
         * used", both with a warning mark, even though one is a refactor by nature and the other a defect.
         * Ours shipped as an intention with no diagnostic — correct, and findable only by putting the caret
         * on the right nine characters.</p>
         *
         * <p>The codes are <b>not numeric</b>, which is how they stay apart from ECJ's: a problem id is
         * rendered as its integer, so any non-numeric code is unambiguously ours and no id can ever collide
         * with one. That also keeps the corrections' routing untouched — they key on {@code IProblem} ids
         * and simply never see these.</p>
         */
        private List<Diagnostic> inspections(CompilationUnit resolved) {
            List<Diagnostic> found = new ArrayList<>();
            for (Inspection inspection : Inspection.all()) {
                for (Inspection.Finding finding : inspection.reportIn(resolved, source)) {
                    // THE ONE PLACE AN OFFSET BECOMES A POSITION. An inspection says what it found and
                    // where in the source; a row and column mean something only against the document this
                    // analysis actually saw, which is why they are not an inspection's to produce.
                    found.add(new Diagnostic(pointOf(resolved, finding.from()),
                            pointOf(resolved, finding.to()), finding.severity(), finding.message(),
                            "java", inspection.code(), finding.tags(), List.of()));
                }
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
            // TRUE MEANS "VISIT DOC TAGS", and it is the whole of what was missing. `new ASTVisitor()`
            // is `new ASTVisitor(false)`, so `Javadoc.accept0` never offers its tags to the visitor and
            // every name inside a doc comment was invisible to this pass -- measured, zero tokens over
            // a comment containing `{@link List}` and `@see List`.
            //
            // Nothing else was needed. JDT resolves those names for real, because `EcjOptions` turns
            // doc-comment support on, so a `@see List` fragment is a `SimpleName` carrying an
            // `ITypeBinding` exactly as a name in code does -- and `captureFor` already turns a binding
            // into `type.interface`/`type.class`/`type.enum`. No second resolver, no doc-specific
            // machinery: the answer was already there and nobody was walking to it.
            resolved.accept(new ASTVisitor(true) {
                @Override
                public boolean visit(SimpleName name) {
                    // A `@param`'s SUBJECT NAMES A DECLARATION; it does not reference one. `@param n`
                    // resolves to the parameter binding and so came back `variable.parameter`, which is
                    // a true statement and the wrong colour: IntelliJ draws it as DOC_COMMENT_TAG_VALUE,
                    // and it is the lexer's `comment.doc.value` that should win the character. Every
                    // other tag's argument IS a reference -- `@throws IllegalStateException` and
                    // `@see java.util.List` are types, and `{@link #other()}` is a member -- so this is
                    // the one exclusion rather than a list of tags that may resolve.
                    if (isParamTagSubject(name)) return true;
                    // AND NOTHING THAT DID NOT REALLY RESOLVE. `setBindingsRecovery` is on -- it is what
                    // lets the rest of a broken file still resolve -- and for an unknown TYPE it
                    // synthesises a binding rather than answering null: `{@link no.such.Type}` comes back
                    // a CLASS named `Type` in a container `no.such`, indistinguishable from a real one.
                    //
                    // In CODE that is harmless, because `endVisit` marks the same range `unresolved` and
                    // the editor's merge takes the later token. In a doc comment it is not: the mark is
                    // deliberately suppressed there, so the recovered kind would be the only thing said
                    // about the name -- a broken reference drawn in the same confident colour as the
                    // working one three lines above it. Left to the lexer's `comment.doc.value` instead,
                    // which is what an unresolved reference should look like: ordinary.
                    if (inDocComment(name)) {
                        IBinding resolvedTo = bindingFor(name);
                        if (resolvedTo == null || resolvedTo.isRecovered()) return true;
                    }
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
                 * because they answer a different question — {@code count} being a field and
                 * {@code count} being unresolved are both worth saying, and a scheme styles them
                 * separately.</p>
                 *
                 * <p>The scheme draws this one as a <em>colour</em> and not as an underline. It used to be
                 * an underline, from before diagnostics were drawn inline; with a squiggle under the same
                 * characters that was two lines for one fact, and it read as a double underline because it
                 * was one. @see the {@code ::highlight(unresolved)} rule in {@code ua/editor.css}</p>
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
                    // A RECOVERED BINDING IS AN UNRESOLVED NAME WEARING A BINDING. `setBindingsRecovery`
                    // is on -- it is what lets the rest of a broken file still resolve -- and for an
                    // unresolved TYPE it synthesises one rather than answering null. So only types slipped
                    // through this check: `cont` and `lenght` came back null and went red, while `List`
                    // came back as a TypeBinding and was coloured a perfectly confident class colour, with
                    // the error squiggle under it the only sign anything was wrong.
                    if (binding == null || binding.isRecovered()) {
                        // ONLY WHERE A NAME WAS EXPECTED TO RESOLVE. A label, a package fragment and
                        // the name in a declaration position legitimately have no binding, and
                        // underlining those would mark correct code as broken on every file.
                        // AND NEVER INSIDE A DOC COMMENT. This mark says the name will not compile,
                        // which is not a thing a comment can do -- javadoc's own reference rules are
                        // stricter than the language's and JDT declines shapes that are perfectly legal
                        // to a reader, so a mark here would be red on working prose. The file's standing
                        // rule decides it: a missed underline is invisible, a false one is a red mark on
                        // correct code. `deprecated` below is left alone, because that IS a true
                        // statement about whatever the reference points at.
                        if (isResolvable(name) && !inDocComment(name)) {
                            tokens.add(new SyntaxToken(start, end, "unresolved"));
                        }
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
         * Whether a name is the one being declared by a record component.
         *
         * <p>Asked of the tree rather than of the binding, because {@code IVariableBinding} gained
         * {@code isRecordComponent()} in the JDT that shipped with Java 14 and this adapter is loaded by
         * the OLDEST band's classloader. A structural-property id is a string and an older AST simply
         * never reports it.</p>
         */
        private static boolean isRecordComponent(SimpleName name) {
            ASTNode parent = name.getParent();
            if (!(parent instanceof SingleVariableDeclaration)) return false;
            StructuralPropertyDescriptor slot = parent.getLocationInParent();
            return slot != null && "recordComponents".equals(slot.getId());
        }

        /**
         * Whether a name is what a {@code @param} tag names.
         *
         * <p>Asked of the PARENT rather than by walking for a tag, because that is exactly the shape:
         * JDT puts a block tag's argument at the head of its own {@code TagElement}'s fragments. An
         * inline {@code {@link}} inside a {@code @param}'s prose has the nested tag as its parent, so
         * it is unaffected and still colours as the reference it is.</p>
         */
        private static boolean isParamTagSubject(SimpleName name) {
            ASTNode parent = name.getParent();
            return parent instanceof TagElement
                    && TagElement.TAG_PARAM.equals(((TagElement) parent).getTagName());
        }

        /** Whether a node sits inside a doc comment rather than in code. */
        private static boolean inDocComment(ASTNode node) {
            for (ASTNode at = node; at != null; at = at.getParent()) {
                if (at.getNodeType() == ASTNode.JAVADOC) return true;
            }
            return false;
        }

        /** Whether a name sits inside a {@code package} or {@code import} path. */
        private static boolean inPackagePath(SimpleName name) {
            ASTNode node = name.getParent();
            while (node instanceof QualifiedName) node = node.getParent();
            if (node == null) return false;
            int type = node.getNodeType();
            return type == ASTNode.PACKAGE_DECLARATION || type == ASTNode.IMPORT_DECLARATION;
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
            // THE SAME QUESTION THE POPUP ASKS, through the same method. `new ArrayList<>(...)` resolves
            // its name to the TYPE, so the colour said "class" while the popup -- which had already been
            // corrected to ask the ClassInstanceCreation -- described the constructor being called. Both
            // references colour a constructor call as a call, and the divergence is the shape this file
            // has produced twice already: one rule with two homes drifts.
            IBinding binding = bindingFor(name);
            // A PACKAGE PATH SEGMENT WITH NO BINDING OF ITS OWN. JDT gives the package binding to
            // `java.util` and to the `util` inside it, but the leftmost `java` is a bare qualifier and
            // resolves to nothing -- so a binding-only rule coloured `util` and left `java` as body
            // text, drawing one import path in two colours. Positional, and only as a FALLBACK: the
            // last segment of an import is a TYPE and has a binding, so it never reaches here.
            if (binding == null) {
                return inPackagePath(name) ? SymbolKind.PACKAGE.captureName() : null;
            }
            if (binding instanceof IVariableBinding) {
                IVariableBinding variable = (IVariableBinding) binding;
                if (variable.isEnumConstant()) return SymbolKind.ENUM_MEMBER.captureName();
                // A RECORD COMPONENT IS A FIELD, and JDT calls it neither field nor parameter -- so it
                // fell through both tests to the local-variable catch-all, and a record's header drew
                // its components in the colour of a temporary inside a method body. They are exactly
                // what a field is: state the object carries, named once, readable from anywhere the
                // object is. Decided POSITIONALLY rather than through isRecordComponent(), which arrived
                // with Java 14 -- calling it would throw NoSuchMethodError on the oldest band, which is
                // the same trap naming RecordDeclaration sets and one an ordinary test cannot see.
                if (isRecordComponent(name)) return SymbolKind.FIELD.captureName();
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
                return JavaSignatures.typeCapture((ITypeBinding) binding);
            }
            // A CONSTRUCTOR IS NOT A SPECIAL CASE HERE -- methodCapture already draws the distinction that
            // matters, which is DECLARATION versus USE, and a constructor has both forms exactly as any
            // other method does. Naming CONSTRUCTOR outright gave both the declaration colour, so
            // `new ArrayList<>()` was drawn like `public ArrayList(...)`; the call site is a call.
            if (binding instanceof IMethodBinding) {
                return methodCapture(name, (IMethodBinding) binding);
            }
            // A PACKAGE SEGMENT. `java` and `util` in an import are package bindings, and nothing was
            // answering for them -- so every import read as body text with one coloured word at the end,
            // while IntelliJ tints the whole path. The grammar cannot help: its rule for a scoped
            // identifier is a CAPITALISATION heuristic, so `com.crystalgui` is invisible to it by
            // construction and `Foo.bar` is a false positive.
            if (binding instanceof IPackageBinding) return SymbolKind.PACKAGE.captureName();
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
        public SymbolInfo describe(String name) {
            // THIS FILE FIRST. The fallback builds a PROBE -- a separate compilation unit compiled
            // against the classpath -- and the classpath does not contain the file being edited, so a
            // reference to anything declared here resolved nowhere. That is most of a person's own
            // links: `{@link #helper()}`, `@see MyOtherClass`, a bare `#member` meaning "on this class".
            // In the fixture it was every See Also row that pointed inward, while the ones pointing at
            // the JDK worked, which reads as the link being broken at random.
            SymbolInfo here = describeInThisUnit(name);
            if (here != null) return here;
            return describer == null ? null : describer.apply(name);
        }

        /**
         * A reference to something declared in <b>this</b> file, or null.
         *
         * <p>Matched on the simple name, which is what a doc reference gives: a qualified reference is
         * cut to its last segment and a member reference to the member. That is enough here and would
         * not be on a classpath — one file declares few enough names that a collision is the author's
         * own doing, and the alternative is re-deriving a binding key from text.</p>
         *
         * <p>A MEMBER is answered as itself rather than as its owning type, which the probe path cannot
         * do: the declaration is right here, so there is a binding for it without needing a unit that
         * calls it. That makes {@code @see #parityBlockTags()} land on the method — the partial named in
         * the plan is about members reached through the CLASSPATH, and this is the other half.</p>
         */
        @Nullable
        private SymbolInfo describeInThisUnit(String reference) {
            final CompilationUnit resolved = unit;
            if (resolved == null || reference == null) return null;
            String bare = reference.trim();
            int hash = bare.indexOf('#');
            String member = hash < 0 ? "" : bare.substring(hash + 1);
            int bracket = member.indexOf('(');
            if (bracket >= 0) member = member.substring(0, bracket);
            String type = hash < 0 ? bare : bare.substring(0, hash);
            int lastDot = type.lastIndexOf('.');
            if (lastDot >= 0) type = type.substring(lastDot + 1);

            final String wanted = member.isEmpty() ? type : member;
            if (wanted.isEmpty()) return null;
            // A QUALIFIER IS A FILTER, NOT A TARGET. `Other#run` may not be answered by this file's own
            // `run`, so when a type was named and it is not one declared here, leave it to the probe.
            final String container = member.isEmpty() ? "" : type;

            final SimpleName[] found = new SimpleName[1];
            resolved.accept(new ASTVisitor() {
                private boolean take(SimpleName name) {
                    if (found[0] != null || name == null) return false;
                    if (!wanted.equals(name.getIdentifier())) return false;
                    if (!container.isEmpty() && !container.equals(enclosingTypeName(name))) return false;
                    found[0] = name;
                    return true;
                }

                @Override
                public boolean visit(TypeDeclaration node) {
                    take(node.getName());
                    return true;
                }

                @Override
                public boolean visit(EnumDeclaration node) {
                    take(node.getName());
                    return true;
                }

                @Override
                public boolean visit(MethodDeclaration node) {
                    take(node.getName());
                    return true;
                }

                @Override
                public boolean visit(VariableDeclarationFragment node) {
                    take(node.getName());
                    return true;
                }

                @Override
                public boolean visit(EnumConstantDeclaration node) {
                    take(node.getName());
                    return true;
                }
            });

            SimpleName name = found[0];
            if (name == null) return null;
            IBinding binding = name.resolveBinding();
            return binding == null ? null : describe(resolved, name, binding);
        }

        /** The simple name of the type a node is declared in, or {@code ""}. */
        private static String enclosingTypeName(ASTNode node) {
            for (ASTNode at = node.getParent(); at != null; at = at.getParent()) {
                if (at instanceof AbstractTypeDeclaration) {
                    return ((AbstractTypeDeclaration) at).getName().getIdentifier();
                }
            }
            return "";
        }

        @Override
        public SymbolInfo resolveAt(int offset) {
            CompilationUnit resolved = unit;
            if (resolved == null) return null;
            SimpleName name = nameAt(resolved, offset);
            if (name != null) {
                IBinding binding = bindingFor(name);
                return binding == null ? null : describe(resolved, name, binding);
            }
            return expressionAt(resolved, offset);
        }

        /**
         * An expression with no name of its own — most importantly, a <b>call</b>.
         *
         * <p>{@code list.get(0).} puts a {@code )} immediately before the dot, and completion resolves the
         * character before the dot to find the receiver. {@link #nameAt} walks <em>up</em> looking for a
         * {@code SimpleName} and a closing bracket has none above it, so this answered null and the popup
         * opened empty on one of the commonest shapes in Java — a chained call. Nothing failed; the popup
         * appeared, which is why it read as completion being unreliable in places.</p>
         *
         * <p>Found here because the JavaScript engine had the identical gap and a fixture caught it there
         * first. The test that pins this one is a deliberate copy of that fixture: "it works in the other
         * engine" is a claim, and this is what asking it of ECJ answered.</p>
         *
         * <p>No name and {@code UNKNOWN} kind, because a call is a value rather than a declaration — there
         * is nothing to point go-to-definition at. The <em>type</em> is the whole answer, and it is what a
         * member lookup and a hover each need.</p>
         */
        private SymbolInfo expressionAt(CompilationUnit resolved, int offset) {
            // LENGTH 1, NOT 0, AND THAT IS THE WHOLE OF IT. A zero-length range at offset N is "covered"
            // by any node whose extent ENDS at N -- JDT's own test is `start <= N && N <= end` -- so
            // asking at the `)` of `list.get(0)` answered the `0` literal, and the receiver resolved to
            // `int`. Worse than nothing: a non-null type made the caller stop falling through to its probe
            // re-parse, so the case this method was added to fix stayed broken with a different cause.
            // Asking about the character itself picks the node that CONTAINS it, which is the call.
            ASTNode node = NodeFinder.perform(resolved, offset, 1);
            while (node != null && !(node instanceof Expression)) node = node.getParent();
            if (node == null) return null;
            TypeRef type = typeRef(((Expression) node).resolveTypeBinding());
            return type == null ? null
                    : new SymbolInfo("", SymbolKind.UNKNOWN, type, null, null, Set.of(), null);
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
            IMethodBinding constructor = constructorInvokedBy(name);
            return constructor != null ? constructor : name.resolveBinding();
        }

        /**
         * The constructor {@code new Foo(...)} invokes, when {@code name} is the type being constructed —
         * null for every other name, including the ones sitting inside the same expression.
         *
         * <p>Climbs the <b>type position only</b>, tracking which child it came from. That is what
         * separates {@code new java.util.ArrayList<>()}, where every node up to the creation is the type,
         * from {@code new Message(text, Severity.INFO, 0L)}, where {@code Severity.INFO} is an argument
         * whose parent <em>is</em> the creation. An earlier version climbed any {@code QualifiedName} and
         * then tested the name's offsets against the type's — which worked, and stated the rule in
         * coordinates. The structural test says the same thing in the shape of the tree, and covers the
         * qualifier of a qualified name ({@code util} in the path above) for the same reason rather than a
         * second one.</p>
         */
        private static IMethodBinding constructorInvokedBy(SimpleName name) {
            ASTNode child = name;
            for (ASTNode node = name.getParent(); node != null; node = node.getParent()) {
                if (node instanceof ClassInstanceCreation) {
                    ClassInstanceCreation creation = (ClassInstanceCreation) node;
                    Type constructed = creation.getType();
                    return constructed == child ? creation.resolveConstructorBinding() : null;
                }
                if (node instanceof QualifiedName) {
                    if (((QualifiedName) node).getName() != child) return null;
                } else if (node instanceof QualifiedType) {
                    if (((QualifiedType) node).getName() != child) return null;
                } else if (node instanceof ParameterizedType) {
                    if (((ParameterizedType) node).getType() != child) return null;
                } else if (!(node instanceof SimpleType)) {
                    return null;
                }
                child = node;
            }
            return null;
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
                        .withContainerKind(declaring == null ? null : JavaSignatures.kindOf(declaring))
                        .withSignature(signatures.of(binding, kind, name.getIdentifier()))
                        .withDocumentation(signatures.documentationOf(binding));
            }
            if (binding instanceof IMethodBinding) {
                IMethodBinding method = (IMethodBinding) binding;
                ITypeBinding declaring = method.getDeclaringClass();
                SymbolKind kind = method.isConstructor()
                        ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;
                return new SymbolInfo(name.getIdentifier(), kind, typeRef(method.getReturnType()),
                        containerName(declaring), null, modifiers,
                        declarationOf(unit, binding), parameterTypesOf(method))
                        .withContainerKind(declaring == null ? null : JavaSignatures.kindOf(declaring))
                        .withSignature(signatures.of(binding, kind, name.getIdentifier()))
                        .withDocumentation(signatures.documentationOf(binding));
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
                        .withSignature(signatures.of(binding, kind, name.getIdentifier()))
                        .withDocumentation(signatures.documentationOf(binding));
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
         * Where a binding is declared — <b>this file first, then attached source</b>.
         *
         * <p>{@code findDeclaringNode} answers only for the unit it is asked, and for a long while that
         * was the whole of it: a classpath member got null, which {@link DeclarationSite} still
         * documents as the ordinary case. That was true when nothing could read a classpath type's
         * source. {@code AttachedSources} can, and has been quoting declarations out of it for the
         * documentation popup all along — so the second step asks it, and the answer is a
         * {@code library://} site the workbench opens in a viewer.</p>
         *
         * <p><b>Order matters and is not arbitrary.</b> A type declared in the file being edited must
         * answer as THIS document even when a class of the same name exists on the classpath, or
         * editing a class called {@code Main} would navigate into somebody else's {@code Main}. The
         * local unit is definitive about itself; the archive is a fallback.</p>
         *
         * <p>Still null when there is no attached source, which is a jar shipping none — a decompiler
         * answers those, and cannot answer here: it has no positions to give until it has run.</p>
         */
        private DeclarationSite declarationOf(CompilationUnit unit, IBinding binding) {
            ASTNode declaration = unit.findDeclaringNode(binding);
            if (declaration == null) {
                return signatures == null ? null : signatures.declarationInAttachedSource(binding);
            }
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
            return Scopes.enclosingMethodBinding(node);
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
                // STATIC AND FINAL IS A CONSTANT, which this list said and the semantic-token pass a
                // few hundred lines up has always said. **The engine was contradicting itself about one
                // declaration**, depending on which question was asked: `Collections.EMPTY_LIST` drew as
                // a constant under the caret in a .java file and listed as a plain field in the same
                // file's completion popup -- and, once JavaScript started colouring members through
                // `membersOf`, drew as a plain property in a .js file too. Same rule, one place to read
                // it from.
                into.add(new SymbolInfo(field.getName(), fieldKindOf(field),
                        typeRef(field.getType()), owner.getQualifiedName(), null,
                        modifiersOf(field), null));
            }
        }

        /**
         * What kind of thing a field is — the same order the semantic pass uses.
         *
         * <p>Enum constant first because it is the more specific answer: every enum constant is also
         * {@code static final}, and calling one a constant would lose which of the two it is.</p>
         */
        private static SymbolKind fieldKindOf(IVariableBinding field) {
            if (field.isEnumConstant()) return SymbolKind.ENUM_MEMBER;
            int flags = field.getModifiers();
            return Modifier.isStatic(flags) && Modifier.isFinal(flags)
                    ? SymbolKind.CONSTANT : SymbolKind.FIELD;
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
                if (walk instanceof MethodDeclaration) {
                    MethodDeclaration method =
                            (MethodDeclaration) walk;
                    for (Object parameter : method.parameters()) {
                        addVariable((SingleVariableDeclaration) parameter, SymbolKind.PARAMETER, seen, found);
                    }
                }
                if (walk instanceof AbstractTypeDeclaration) {
                    ITypeBinding type = ((AbstractTypeDeclaration) walk).resolveBinding();
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
            } else if (scope instanceof LambdaExpression) {
                for (Object parameter : ((LambdaExpression) scope).parameters()) {
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

        /**
         * The type a caret sits inside, which is what accessibility is judged from.
         *
         * <p>An anonymous class counts as that type, which this used to walk straight past — so a caret in
         * one judged what it could see from the class around it, and a private member of the anonymous
         * class it is standing in was not offered.</p>
         */
        private static ITypeBinding enclosingTypeAt(CompilationUnit unit, int offset) {
            return Scopes.enclosingTypeBinding(NodeFinder.perform(unit, offset, 0));
        }

        @Override
        public void close() {
            // The AST and every binding hanging off it, and THEN the environment they resolve through.
            //
            // In that order, and the environment is not merely tidiness: it holds an open ZipFile per
            // classpath entry, and a unit resolves its bindings lazily against it, so it may only be
            // released once nothing can ask another question. @see EcjSourceAnalyzer#live
            unit = null;
            cleanupQuietly(environment);
            environment = null;
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
    record EcjTypeRef(ITypeBinding binding) implements TypeRef {

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
