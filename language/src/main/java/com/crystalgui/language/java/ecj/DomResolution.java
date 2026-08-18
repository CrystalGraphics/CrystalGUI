package com.crystalgui.language.java.ecj;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;

/**
 * A resolved DOM tree built against <b>our</b> name environment, not against a list of files.
 *
 * <h3>Why this exists, and why {@code ASTParser} cannot do it</h3>
 *
 * <p>{@code ASTParser}'s entire public surface for saying what a parse resolves against is
 * {@code setEnvironment(String[] classpath, …)}, {@code setProject(IJavaProject)} and
 * {@code setWorkingCopyOwner} — file paths, an Eclipse workspace, or nothing. On an obfuscated Minecraft
 * host the files are Notch-named, so <b>no Minecraft type resolves in the editor at all</b> while the
 * compiler — which is handed an {@code INameEnvironment} — resolves every one of them. A script
 * compiles and runs and the editor shows it as broken.</p>
 *
 * <h3>Why reflection here is not the band risk it looks like</h3>
 *
 * <p>{@code CompilationUnitResolver} is package-private, which is why §26.3 first recorded this route as
 * closed. That conclusion was half right: it blocks putting a class <em>beside</em> it in
 * {@code org.eclipse.jdt.core.dom}, because Eclipse's jars are signed and a JVM refuses a package split
 * across differently-signed sources. It never blocked reaching it reflectively.</p>
 *
 * <p>And the three members needed are <b>public</b> on that class — only the type is not — so this is
 * reflection for accessibility rather than reflection into internals. They are byte-identical between
 * {@code jdt.core} 3.26.0 (2021, band 8) and 3.46.0 (2026, band 17), twenty-five releases apart:</p>
 *
 * <pre>
 * public CompilationUnitResolver(INameEnvironment, IErrorHandlingPolicy, CompilerOptions,
 *                                ICompilerRequestor, IProblemFactory, IProgressMonitor, boolean)
 * public CompilationUnitDeclaration resolve(ICompilationUnit, boolean, boolean, boolean)
 * public static CompilationUnit convert(CompilationUnitDeclaration, char[], int, Map, boolean,
 *                                       WorkingCopyOwner, BindingTables, int, IProgressMonitor, boolean)
 * </pre>
 *
 * <p><b>{@code convert} is selected by signature, not by name.</b> 3.46 added an eleven-argument overload
 * taking an {@code IJavaProject}; picking "the method called convert" would find whichever the JVM
 * happened to return first and fail on one band only.</p>
 *
 * <h3>Every failure degrades to the file-based parse</h3>
 *
 * <p>Nothing here throws outward. A band whose shape differs, a security manager, an unexpected null —
 * all answer {@code null}, and {@code EcjSourceAnalyzer} falls back to {@code ASTParser} exactly as
 * before. That is the whole safety argument for using an internal type at all: the worst case is the
 * behaviour that shipped before this existed.</p>
 */
final class DomResolution {

    private static final String RESOLVER = "org.eclipse.jdt.core.dom.CompilationUnitResolver";

    /** Resolved once. Absent members mean "not this band", which is a supported answer. */
    private static volatile Handles handles;
    private static volatile boolean looked;

    private static final class Handles {
        Constructor<?> constructor;
        Method resolve;
        Method convert;
        Field bindingTables;
        Constructor<?> tables;
    }

    /**
     * A requestor that keeps nothing.
     *
     * <p>This path exists to produce a tree and its bindings; class files are the compiler's job, and
     * generating them on every keystroke would double the cost of typing. An explicit class rather than
     * a lambda because the constructor is invoked reflectively through {@code Object...}, where a lambda
     * has no target type to infer from.</p>
     */
    private static final ICompilerRequestor DISCARDING = new ICompilerRequestor() {
        @Override
        public void acceptResult(CompilationResult result) {
        }
    };

    private DomResolution() {
    }

    /**
     * A DOM unit with bindings resolved against {@code environment}, or null to fall back.
     *
     * @param apiLevel the {@code AST.JLS*} level — the same one {@code ASTParser} would be given
     */
    static CompilationUnit resolve(ICompilationUnit unit, char[] source, INameEnvironment environment,
                                   Map<String, String> options, int apiLevel) {
        Handles found = lookUp();
        if (found == null) return null;
        try {
            Object resolver = found.constructor.newInstance(environment,
                    DefaultErrorHandlingPolicies.proceedWithAllProblems(),
                    new CompilerOptions(options),
                    DISCARDING,
                    new DefaultProblemFactory(Locale.getDefault()),
                    null, Boolean.FALSE);

            // THE BINDING TABLES HAVE TO BE CREATED HERE. JDT populates this field inside its own private
            // resolve() entry points -- the ones that take an IJavaProject -- so the public overload
            // leaves it null, and the failure arrives from deep inside the converter as
            // `Cannot read field "compilerBindingsToASTBindings" because "this.bindingTables" is null`,
            // naming neither this class nor the field that was never set.
            Object tables = found.tables.newInstance();
            found.bindingTables.set(resolver, tables);

            // verifyMethods, analyzeCode, generateCode. Method bodies ARE analysed, because that is where
            // unused locals and unreachable code come from -- the optional problems a file has to parse
            // to get at all. Nothing is generated.
            Object declaration = found.resolve.invoke(resolver, unit, true, true, false);
            if (declaration == null) return null;
            Object converted = found.convert.invoke(null, declaration, source, apiLevel, options,
                    true, null, tables, 0, null, true);
            return converted instanceof CompilationUnit ? (CompilationUnit) converted : null;
        } catch (Throwable unavailable) {
            // Includes the AssertionError JDT's binding layer raises on a record whose component types
            // do not resolve -- the same fault EcjSourceAnalyzer.parse documents. Falling back is the
            // right answer for all of them, and it is why using an internal type here is safe: the worst
            // case is the behaviour that shipped before this existed.
            return null;
        }
    }

    /** Whether this band exposes the shape. Cheap after the first call, and it never retries a miss. */
    static boolean isAvailable() {
        return lookUp() != null;
    }

    private static Handles lookUp() {
        if (looked) return handles;
        synchronized (DomResolution.class) {
            if (looked) return handles;
            looked = true;
            handles = find();
            if (handles == null) {
                System.err.println("[crystalgui] this engine band does not expose "
                        + RESOLVER + "; the editor will resolve against files, which on an obfuscated "
                        + "host means Minecraft types will not resolve");
            }
            return handles;
        }
    }

    private static Handles find() {
        try {
            Class<?> type = Class.forName(RESOLVER, false, DomResolution.class.getClassLoader());
            Handles found = new Handles();

            for (Constructor<?> candidate : type.getDeclaredConstructors()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length == 7 && INameEnvironment.class.isAssignableFrom(parameters[0])) {
                    candidate.setAccessible(true);
                    found.constructor = candidate;
                    break;
                }
            }
            for (Method candidate : type.getDeclaredMethods()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if ("resolve".equals(candidate.getName()) && parameters.length == 4
                        && ICompilationUnit.class.isAssignableFrom(parameters[0])) {
                    candidate.setAccessible(true);
                    found.resolve = candidate;
                }
                // BY ARITY, NOT BY NAME. 3.46 adds an eleven-argument overload taking an IJavaProject,
                // and "the method called convert" would pick whichever came back first.
                if ("convert".equals(candidate.getName()) && parameters.length == 10) {
                    candidate.setAccessible(true);
                    found.convert = candidate;
                }
            }
            found.bindingTables = type.getDeclaredField("bindingTables");
            found.bindingTables.setAccessible(true);
            // Reached through the FIELD's type rather than by name: BindingTables is a package-private
            // nested class, and naming it as a string is one more spelling to keep in step for nothing.
            found.tables = found.bindingTables.getType().getDeclaredConstructor();
            found.tables.setAccessible(true);

            return found.constructor == null || found.resolve == null || found.convert == null
                    ? null : found;
        } catch (Throwable absent) {
            return null;
        }
    }
}
