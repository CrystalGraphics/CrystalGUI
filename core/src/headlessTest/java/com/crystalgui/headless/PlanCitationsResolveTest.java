package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * <b>Every {@code plan/…} citation in this repository names a plan that exists.</b>
 *
 * <p>A comment that cites a plan is how this codebase says <em>why</em>, and there are several hundred of
 * them. Nothing checked one until this: delete or rename a plan and the citations quietly point at
 * nothing — no test, no build step, no symptom. One was already dead when the plans moved out, cited
 * three times and never written.</p>
 *
 * <h3>It shells out rather than reimplementing the check</h3>
 *
 * <p>{@code plan/tools/verify.py} is the one implementation, and it runs from both repositories. A second
 * copy of the rule written in Java is two mechanisms for one rule, which drift — so this test is a
 * wiring, not a checker.</p>
 *
 * <h3>Skipping is the normal state, and it gates on the ENVIRONMENT</h3>
 *
 * <p>The plans are a separate private repository, cloned by hand into {@code plan/}. Every public
 * checkout is without it, so absent means skip. That is the one legitimate use of an assumption — it
 * gates on whether the input exists, never on what the answer turned out to be.</p>
 */
public class PlanCitationsResolveTest {

    @Test
    public void everyCitedPlanExists() throws Exception {
        Path root = repositoryRoot();
        Path verifier = root.resolve("plan/tools/verify.py");
        assumeTrue("plan/ is not checked out — the plans are a separate private repository",
                Files.isRegularFile(verifier));

        String python = pythonOnPath();
        assumeTrue("no python on PATH", python != null);

        ProcessBuilder builder = new ProcessBuilder(
                python, verifier.toString(), "--repo", root.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = read(process.getInputStream());
        int exit = process.waitFor();

        assertEquals("a plan citation no longer resolves:\n" + output, 0, exit);
    }

    /** Walks up from the working directory to whichever ancestor holds {@code settings.gradle.kts}. */
    private static Path repositoryRoot() {
        Path at = Paths.get("").toAbsolutePath();
        while (at != null && !Files.isRegularFile(at.resolve("settings.gradle.kts"))) {
            at = at.getParent();
        }
        return at == null ? Paths.get("").toAbsolutePath() : at;
    }

    private static String pythonOnPath() {
        List<String> candidates = new ArrayList<>();
        candidates.add("python");
        candidates.add("python3");
        for (String candidate : candidates) {
            try {
                Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                if (p.waitFor() == 0) return candidate;
            } catch (IOException | InterruptedException ignored) {
                // Not this one. A missing interpreter is an environment fact, not a failure.
            }
        }
        return null;
    }

    private static String read(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
