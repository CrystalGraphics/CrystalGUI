package com.crystalgui.language.java.ecj;

import com.crystalgui.language.engine.bridge.ScriptCompiler;

import org.eclipse.jdt.core.compiler.batch.BatchCompiler;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ECJ, driven through its public batch API — the first thing on the far side of the bridge.
 *
 * <h3>This class is loaded by {@code EngineClassLoader}, never by the host</h3>
 *
 * <p>It names {@code org.eclipse.jdt.*} directly, so only a loader that can see the band's jars can
 * load it. The host loads {@link ScriptCompiler} (parent, bridge package), asks the child loader for
 * this class by name, and casts. That the cast works is the entire point of the bridge carve-out.</p>
 *
 * <h3>Why {@code BatchCompiler} and not {@code javax.tools}</h3>
 *
 * <p>The obvious route is ECJ's {@code EclipseCompiler}, which implements {@code javax.tools.JavaCompiler}
 * and would give in-memory compilation through a standard API. <b>It is not in band 8.</b> Measured:
 * {@code org.eclipse.jdt.internal.compiler.tool.EclipseCompiler} and the
 * {@code META-INF/services/javax.tools.JavaCompiler} registration first appear alongside the separate
 * {@code ecj} artifact, and band 8's jdt.core carries neither. Writing the adapter against it would
 * have compiled, passed every test on a modern JVM, and failed on the one host the banding exists for.</p>
 *
 * <p>{@code BatchCompiler} is public API and present in all three bands, which makes it the honest
 * choice for a first working path.</p>
 *
 * <h3>What this deliberately is not</h3>
 *
 * <p><b>A temporary directory per compile is not the design</b>, it is the cheapest thing that is
 * genuinely correct. The batch API takes file paths and writes class files, so a compile costs two
 * directories and some I/O — fine for "run this script", far too slow for the per-keystroke analysis
 * M6 needs. That path is {@code org.eclipse.jdt.internal.compiler.Compiler} with an
 * {@code ICompilerRequestor} collecting bytes and a custom {@code INameEnvironment} supplying types,
 * which is also exactly where §15.5's obfuscated-name mapping has to hook in. Both are present in all
 * three bands; neither is driven yet.</p>
 *
 * <p>So: this proves the seam and runs scripts. It is not the compiler the editor will use.</p>
 */
public final class EcjScriptCompiler implements ScriptCompiler {

    @Override
    public Result compile(String className, String source, List<String> classpath, int releaseLevel) {
        Path work = null;
        try {
            work = Files.createTempDirectory("cgui-script");
            Path sources = Files.createDirectories(work.resolve("src"));
            Path output = Files.createDirectories(work.resolve("out"));

            // The declared package decides the directory, or javac's own rule -- which ECJ
            // enforces -- rejects a packaged file written at the root of the source tree.
            Path file = sources.resolve(SourcePackages.unitPath(className, source));
            Files.createDirectories(file.getParent());
            Files.write(file, source.getBytes(Charset.forName("UTF-8")));

            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            boolean ok = BatchCompiler.compile(
                    commandLine(file, output, classpath, releaseLevel),
                    new PrintWriter(out), new PrintWriter(err), null);

            List<String> messages = new ArrayList<>();
            addLines(messages, err.toString());
            addLines(messages, out.toString());

            // COLLECTED EVEN ON FAILURE. ECJ emits class files for the types it did manage, and a
            // partial result is what a "compile always, run explicitly" model wants to be able to
            // inspect -- discarding them here would make a failed compile indistinguishable from one
            // that produced nothing.
            Map<String, byte[]> classes = collectClasses(output);
            return new Result(ok && !classes.isEmpty(), classes, messages);
        } catch (IOException failed) {
            List<String> messages = new ArrayList<>();
            messages.add("could not compile: " + failed);
            return new Result(false, new LinkedHashMap<String, byte[]>(), messages);
        } finally {
            deleteQuietly(work);
        }
    }

    /**
     * The command line, spelled the way every band's ECJ accepts.
     *
     * <p>{@code -proc:none} because annotation processing would load processors off the script's
     * classpath — arbitrary code running at <em>compile</em> time, which the trust model (§19.1) has no
     * story for and does not need one, since nothing here uses processors.</p>
     *
     * <p>{@code -nowarn} because warnings are not this path's job. M6 reports diagnostics properly,
     * with ranges, from the structured API; text on stderr would be a second, worse channel for the
     * same information.</p>
     */
    private static String commandLine(Path file, Path output, List<String> classpath, int releaseLevel) {
        StringBuilder command = new StringBuilder();
        String level = EcjOptions.levelName(releaseLevel);
        command.append("-source ").append(level).append(' ');
        // SOURCE AND TARGET BOTH, and the target is the one that matters: bytecode has to LOAD on the
        // host. A newer target on an older host produces class files the JVM refuses, and the error
        // names an UnsupportedClassVersionError rather than anything about the script.
        command.append("-target ").append(level).append(' ');
        command.append("-proc:none -nowarn -g ");
        if (classpath != null && !classpath.isEmpty()) {
            command.append("-classpath ").append(quote(join(classpath))).append(' ');
        }
        command.append("-d ").append(quote(output.toString())).append(' ');
        command.append(quote(file.toString()));
        return command.toString();
    }

    private static String join(List<String> entries) {
        StringBuilder joined = new StringBuilder();
        for (String entry : entries) {
            if (joined.length() > 0) joined.append(java.io.File.pathSeparatorChar);
            joined.append(entry);
        }
        return joined.toString();
    }

    /** Quoted, because a Windows path contains spaces far more often than not. */
    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private static void addLines(List<String> messages, String text) {
        if (text == null) return;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) messages.add(trimmed);
        }
    }

    /** Every class file under {@code output}, keyed by binary name. */
    private static Map<String, byte[]> collectClasses(final Path output) throws IOException {
        final Map<String, byte[]> classes = new LinkedHashMap<>();
        if (!Files.isDirectory(output)) return classes;
        Files.walkFileTree(output, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (file.getFileName().toString().endsWith(".class")) {
                    String relative = output.relativize(file).toString()
                            .replace('\\', '/')
                            .replaceAll("\\.class$", "")
                            .replace('/', '.');
                    classes.put(relative, Files.readAllBytes(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return classes;
    }

    private static void deleteQuietly(Path root) {
        if (root == null) return;
        try {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temp directory that outlives one compile is not worth failing the compile over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
