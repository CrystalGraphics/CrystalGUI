package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.java.classpath.HostClasspath;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>CFR, through the bridge — what the viewer shows for a class nothing shipped source for.</b>
 *
 * <h3>Driven against a class this module compiles, on purpose</h3>
 *
 * <p>Not a JDK class: the JDK is exactly the thing that <em>has</em> source, so decompiling one would be
 * testing the fallback with the primary path standing right beside it, and would break the day
 * {@code src.zip} moved. A class out of our own output is on the classpath as bytes, is stable, and is
 * the shape a mod jar with no {@code -sources.jar} actually presents.</p>
 *
 * <h3>What is asserted, and what deliberately is not</h3>
 *
 * <p>That a declaration comes back — not that the body matches what was written. A decompiler
 * <em>reconstructs</em>: it renames locals it has no table for, rewrites control flow into equivalent
 * shapes, and drops every comment. Asserting on reconstructed statements would pin CFR's rendering
 * choices rather than our wiring, and would fail on a version bump that improved them.</p>
 */
public class DecompilerTest {

    private static JavaEngine engine;

    @BeforeClass
    public static void openTheEngine() throws Exception {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        engine = JavaEngine.over(EngineHost.shared(EngineHost.defaultSource()));
    }

    @AfterClass
    public static void closeTheEngine() {
        // Borrowed, so not closed here — the shared band loader serves every other engine in the process.
        engine = null;
    }

    /** <b>A class on the classpath comes back as readable Java.</b> */
    @Test
    public void aClasspathClassDecompilesToItsDeclaration() {
        String java = engine.decompile(EngineHost.class.getName(), HostClasspath.detect());
        Assume.assumeNotNull("this band has no decompiler staged", java);

        assertTrue("the package declaration is missing: " + head(java),
                java.contains("package com.crystalgui.language.engine"));
        assertTrue("the class declaration is missing: " + head(java),
                java.contains("class EngineHost"));
        assertTrue("a member the class certainly declares is missing", java.contains("adapter"));
    }

    /**
     * <b>A nested type renders inside the file that declares it.</b>
     *
     * <p>Which is the whole reason the byte source is asked for more than the class requested: CFR pulls
     * the outer class and every sibling it needs, and a source that answered only the exact name would
     * emit a shell with the nested type missing — plausible-looking, and wrong.</p>
     */
    @Test
    public void aNestedTypeComesBackInsideItsOuterClass() {
        String java = engine.decompile("com.crystalgui.language.engine.EngineBand", HostClasspath.detect());
        Assume.assumeNotNull("this band has no decompiler staged", java);
        assertTrue("the outer declaration is missing: " + head(java), java.contains("EngineBand"));
    }

    /**
     * <b>A class nothing has answers null</b> — never an exception, and never an empty document.
     *
     * <p>The interface promises this and the viewer depends on it: a hover that throws takes the editor's
     * frame with it, and an empty document is indistinguishable from a class whose body really is empty.
     * Null is what lets the caller say "there is nothing to show" once.</p>
     */
    @Test
    public void aClassNothingHasAnswersNull() {
        assertNull(engine.decompile("no.such.ClassAnywhere", HostClasspath.detect()));
    }

    /** And a nonsense request is refused rather than propagated. */
    @Test
    public void anEmptyNameIsRefused() {
        assertNull(engine.decompile("", HostClasspath.detect()));
        assertNull(engine.decompile(null, HostClasspath.detect()));
    }

    /**
     * <b>The band's own decompiler is reachable at all.</b>
     *
     * <p>A precondition rather than a feature: with the adapter absent every assertion above becomes an
     * assumption that quietly skips, and the suite would go green having tested nothing. This is what
     * says out loud which of the two happened.</p>
     */
    @Test
    public void theBandHasADecompilerStaged() {
        String java = engine.decompile(EngineHost.class.getName(), HostClasspath.detect());
        assertNotNull("no decompiler in this band -- run :language:stageEngines", java);
    }

    private static String head(String java) {
        int end = java.indexOf('\n', 200);
        return end < 0 ? java : java.substring(0, Math.min(end, 400));
    }
}
