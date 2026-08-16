package com.crystalgui.language.run;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>§5.3's proof: execution never touches the grammar natives.</b>
 *
 * <p>A dedicated server runs scripts and has no editor. ECJ and Rhino are headless and GL-free, so
 * server-side execution is in scope — but tree-sitter is five platform natives, and a server that had
 * to load them to run a script would be paying for an editor it does not have. The plan states the rule
 * as a split <em>inside</em> this module: {@code .java}, {@code .js}, {@code .resolve} and {@code .run}
 * never touch {@code .grammar}, with lazy class-init as the mechanism.</p>
 *
 * <h3>Why this is a bytecode scan and not a runtime check</h3>
 *
 * <p>The obvious test is to delete the grammar jars and run a script. That proves it for the one path
 * the test happens to take, and it is exactly the kind of thing that passes for years while a
 * conditional branch nobody exercises reaches for a tokenizer. <b>A reference in the constant pool is
 * the real question</b>: if the class file names a tree-sitter type at all, some input can reach it.
 * Scanning our own compiled classes answers it for every path at once, needs no jar juggling, and
 * cannot be fooled by which fixture the test chose.</p>
 *
 * <p>It also fails at the right moment — the commit that adds the import — rather than on a server
 * months later with a {@code NoClassDefFoundError} naming a native library.</p>
 */
public class ExecutionNeedsNoGrammarTest {

    /** Packages that must be reachable without an editor. */
    private static final List<String> EXECUTION_PACKAGES = List.of(
            "com/crystalgui/language/run/",
            "com/crystalgui/language/java/",
            "com/crystalgui/language/map/",
            "com/crystalgui/language/engine/");

    /** What they must not name. */
    private static final List<String> FORBIDDEN = List.of(
            "org/treesitter/",
            "com/crystalgui/language/grammar/");

    private static Path classesRoot() {
        return ClassReferences.mainClassesRoot(ExecutionNeedsNoGrammarTest.class);
    }

    @Test
    public void theGrammarBackendIsReachableAtAll() throws IOException {
        // The precondition. If the main output cannot be found, every assertion below passes for the
        // wrong reason -- so the scan is proven to be looking at something first.
        Path root = classesRoot();
        assertTrue("cannot find the module's compiled classes at " + root, Files.isDirectory(root));
        assertTrue("the grammar package is missing, so the scan below proves nothing",
                Files.isDirectory(root.resolve("com/crystalgui/language/grammar")));
    }

    @Test
    public void noExecutionClassNamesTheGrammarModuleOrTheTreeSitterBinding() throws IOException {
        Path root = classesRoot();
        List<String> offences = new ArrayList<>();
        for (String executionPackage : EXECUTION_PACKAGES) {
            offences.addAll(ClassReferences.offences(root, executionPackage, FORBIDDEN));
        }
        assertTrue(String.join("\n", offences), offences.isEmpty());
    }

    @Test
    public void andTheGrammarPackageDOESNameTheBinding() throws IOException {
        // The negative control. If `referencesOf` returned nothing -- a broken scan, a changed layout --
        // the test above would pass unconditionally. The grammar package must trip the same detector.
        Path directory = classesRoot().resolve("com/crystalgui/language/grammar");
        Set<String> referenced = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toArray(Path[]::new)) {
                referenced.addAll(ClassReferences.referencesOf(file));
            }
        }
        boolean namesBinding = false;
        for (String name : referenced) {
            if (name.startsWith("org/treesitter/")) namesBinding = true;
        }
        assertTrue("the scan found no tree-sitter reference even in the grammar package — it is not "
                + "detecting anything", namesBinding);
        assertFalse(referenced.isEmpty());
    }
}
