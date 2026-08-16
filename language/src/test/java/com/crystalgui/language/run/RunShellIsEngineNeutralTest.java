package com.crystalgui.language.run;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>The Run panel is written against {@link ScriptRuntime}, and never against a language.</b>
 *
 * <p>The commands, the console, the rail, the sessions, the indicator, the input row and the workbench
 * wiring in {@code .run} are about <em>a script running</em>. Which language it is written in reaches
 * them through two registries — {@link ScriptRuntimes} for how to run it and the {@code LanguageRegistry}
 * for which files are which — and through nothing else. Every class in this package must therefore be
 * loadable, and correct, on a build that ships JavaScript and no Java: it may not name the Java engine,
 * the Java adapters, ECJ, or Rhino.</p>
 *
 * <h3>Why a scan and not a code review</h3>
 *
 * <p>This package used to be written against the Java runtime's concrete class, and it did not read as
 * wrong: {@code ScriptHost} was the only runtime, so "the host" and "the Java host" were the same words.
 * The second language is where it would have been paid for — a second Run command, a second panel
 * wiring, or a rewrite of both. Same reasoning as {@link ExecutionNeedsNoGrammarTest}: a reference in
 * the constant pool is the fact, and the commit that adds it is the moment to fail.</p>
 *
 * <p>The scan is on this package only. {@code .java} is allowed to name {@code .run} — the Java runtime
 * implements the interface — and {@code .engine} holds the band loader both languages share.</p>
 */
public class RunShellIsEngineNeutralTest {

    private static final String RUN_PACKAGE = "com/crystalgui/language/run/";

    /** What the shell must not name. */
    private static final List<String> FORBIDDEN = List.of(
            "com/crystalgui/language/java/",
            "com/crystalgui/language/engine/JavaEngine",
            "com/crystalgui/language/engine/JlsLevel",
            "org/eclipse/",
            "org/mozilla/");

    @Test
    public void theShellIsReachableAtAll() {
        Path root = ClassReferences.mainClassesRoot(RunShellIsEngineNeutralTest.class);
        assertTrue("cannot find the module's compiled classes at " + root, Files.isDirectory(root));
        assertTrue(Files.isDirectory(root.resolve(RUN_PACKAGE)));
    }

    @Test
    public void nothingInTheRunPackageNamesAJavaType() throws IOException {
        Path root = ClassReferences.mainClassesRoot(RunShellIsEngineNeutralTest.class);
        List<String> offences = ClassReferences.offences(root, RUN_PACKAGE, FORBIDDEN);
        assertTrue(String.join("\n", offences), offences.isEmpty());
    }

    @Test
    public void andTheJavaPackageDOESNameTheEngine() throws IOException {
        // The negative control: the Java runtime lives in `.java` and reaches its engine, so the same
        // detector run there must fire. A scan that finds nothing anywhere is not a scan.
        Path root = ClassReferences.mainClassesRoot(RunShellIsEngineNeutralTest.class);
        List<String> offences = ClassReferences.offences(root, "com/crystalgui/language/java/",
                List.of("com/crystalgui/language/engine/JavaEngine"));
        assertFalse("the scan found no JavaEngine reference even in the java package — it is not "
                + "detecting anything", offences.isEmpty());
    }
}
