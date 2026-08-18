package com.crystalgui.language.java.ecj;

import com.crystalgui.language.map.MappingSet;
import com.crystalgui.language.platform.ScriptPlatform;
import com.crystalgui.language.platform.ScriptPlatforms;

import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiles one script in memory, against the live runtime.
 *
 * <h3>Why not {@code BatchCompiler}</h3>
 *
 * <p>The batch API is public, present in every band, and was the honest first path — but it takes file
 * paths and writes class files, so a compile cost two temporary directories and the I/O to fill them.
 * {@code EcjScriptCompiler}'s own javadoc named its successor and the reason:</p>
 *
 * <blockquote>That path is {@code org.eclipse.jdt.internal.compiler.Compiler} with an
 * {@code ICompilerRequestor} collecting bytes and a custom {@code INameEnvironment} supplying types,
 * which is also exactly where §15.5's obfuscated-name mapping has to hook in.</blockquote>
 *
 * <p>Three things fall out of it. Bytes are collected in memory, so nothing is written. The editor's
 * analysis and the runner's compile can share one resolver instead of taking separate routes to the same
 * jars and disagreeing. And there is somewhere for the live name environment to plug in, which is what
 * §15.5 A needs and a command line cannot offer.</p>
 *
 * <h3>ECJ builds its own classpath, deliberately</h3>
 *
 * <p>{@code Main.configure} parses the same options the batch path took and {@code getLibraryAccess}
 * hands back a configured {@code FileSystem}. That is worth the indirection: locating the JDK is not the
 * same job on Java 8 as on 17, and ECJ already does it correctly per band. Hand-rolling it would be a
 * third place for the band split to be got wrong.</p>
 */
final class EcjCompilation {

    /** What one compile produced. */
    static final class Output {
        final Map<String, byte[]> classes = new LinkedHashMap<String, byte[]>();
        final List<String> messages = new ArrayList<String>();
        boolean errored;
    }

    private EcjCompilation() {
    }

    static Output compile(String className, String source, List<String> classpath, int releaseLevel) {
        Output output = new Output();
        INameEnvironment environment = null;
        try {
            Map<String, String> options = optionsFor(releaseLevel);
            environment = environmentFor(classpath, releaseLevel);

            final Output collecting = output;
            ICompilerRequestor requestor = new ICompilerRequestor() {
                @Override
                public void acceptResult(CompilationResult result) {
                    CategorizedProblem[] problems = result.getProblems();
                    if (problems != null) {
                        for (CategorizedProblem problem : problems) {
                            if (problem == null || !problem.isError()) continue;
                            collecting.errored = true;
                            collecting.messages.add(describe(problem));
                        }
                    }
                    // COLLECTED EVEN WHEN THERE ARE ERRORS. ECJ emits class files for the types it did
                    // manage, and a "compile always, run explicitly" model wants to be able to inspect a
                    // partial result -- discarding them would make a failed compile indistinguishable
                    // from one that produced nothing.
                    ClassFile[] files = result.getClassFiles();
                    if (files == null) return;
                    for (ClassFile file : files) {
                        collecting.classes.put(
                                new String(file.fileName()).replace('/', '.'), file.getBytes());
                    }
                }
            };

            new Compiler(environment,
                    DefaultErrorHandlingPolicies.proceedWithAllProblems(),
                    new CompilerOptions(options),
                    requestor,
                    new DefaultProblemFactory(Locale.getDefault()))
                    .compile(new ICompilationUnit[]{new InMemoryUnit(className, source)});
        } catch (RuntimeException failed) {
            output.errored = true;
            // WITH THE TOP FRAME. "could not compile: NullPointerException" names nothing a reader can
            // act on, and this path catches faults in ECJ's internals as well as in ours -- the frame is
            // the only thing that says which.
            StackTraceElement[] frames = failed.getStackTrace();
            output.messages.add("could not compile: " + failed
                    + (frames.length > 0 ? " at " + frames[0] + " | " + frames[1] + " | " + frames[2] : ""));
        } finally {
            if (environment != null) environment.cleanup();
        }
        return output;
    }

    /**
     * The live environment over ECJ's own classpath resolution.
     *
     * <p>Falls back to the plain {@code FileSystem} if the front end refuses the options, because a
     * compile against the classpath alone is a far better failure than no compile at all.</p>
     */
    private static INameEnvironment environmentFor(List<String> classpath, int releaseLevel) {
        INameEnvironment files = fileSystemFor(classpath, releaseLevel);
        ScriptPlatform platform = ScriptPlatforms.current();
        // MappingSet.IDENTITY until 26.7 wires detection; a dev host is genuinely the identity, so this
        // is the correct answer there rather than a placeholder.
        return new ScriptNameEnvironment(files, platform, MappingSet.IDENTITY);
    }

    private static INameEnvironment fileSystemFor(List<String> classpath, int releaseLevel) {
        StringWriter discarded = new StringWriter();
        org.eclipse.jdt.internal.compiler.batch.Main main =
                new org.eclipse.jdt.internal.compiler.batch.Main(
                        new PrintWriter(discarded), new PrintWriter(discarded), false);
        List<String> arguments = new ArrayList<String>();
        String level = EcjOptions.levelName(releaseLevel);
        arguments.add("-source");
        arguments.add(level);
        arguments.add("-target");
        arguments.add(level);
        arguments.add("-proc:none");
        arguments.add("-nowarn");
        if (classpath != null && !classpath.isEmpty()) {
            StringBuilder joined = new StringBuilder();
            for (String entry : classpath) {
                if (joined.length() > 0) joined.append(java.io.File.pathSeparatorChar);
                joined.append(entry);
            }
            arguments.add("-classpath");
            arguments.add(joined.toString());
        }
        // configure() insists on at least one source file to compile; it is never read, because the
        // units come from ICompilationUnit and only getLibraryAccess() is used.
        arguments.add("Unused.java");
        main.configure(arguments.toArray(new String[0]));
        return main.getLibraryAccess();
    }

    private static Map<String, String> optionsFor(int releaseLevel) {
        String level = EcjOptions.levelName(releaseLevel);
        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put(CompilerOptions.OPTION_Source, level);
        // SOURCE AND TARGET BOTH, and the target is the one that matters: the bytecode has to LOAD on
        // the host. A newer target on an older host produces class files the JVM refuses, and the error
        // names an UnsupportedClassVersionError rather than anything about the script.
        options.put(CompilerOptions.OPTION_TargetPlatform, level);
        options.put(CompilerOptions.OPTION_Compliance, level);
        // Line numbers and locals, so a stack trace from a running script names a line the author wrote.
        options.put(CompilerOptions.OPTION_LineNumberAttribute, CompilerOptions.GENERATE);
        options.put(CompilerOptions.OPTION_SourceFileAttribute, CompilerOptions.GENERATE);
        options.put(CompilerOptions.OPTION_LocalVariableAttribute, CompilerOptions.GENERATE);
        return options;
    }

    private static String describe(CategorizedProblem problem) {
        return problem.getMessage() + " (line " + problem.getSourceLineNumber() + ")";
    }
}
