package com.crystalgui.language.java;

import com.crystalgui.fs.Resource;
import com.crystalgui.fs.ResourceRegistry;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.SymbolInfo;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Ctrl+B into a class nobody has the source for lands on the MEMBER, not on line 1.
 *
 * <h3>Why the position could not simply be computed where every other one is</h3>
 *
 * <p>A member of a class with an attached source already navigates correctly — measured,
 * {@code String.length} resolves to line 1517 of {@code src.zip}. A class with <em>no</em> source has no
 * line numbers at all until it has been <b>decompiled</b>, and decompiling inside a resolve, on the
 * analysis thread, to answer a hover that may never be acted on would put hundreds of milliseconds behind
 * every one. So {@code declarationWithoutSource} names the type and carries {@code (0,0)} — which is
 * correct for a type and was, for every member, the top of the file.</p>
 *
 * <p>The answer is deferred rather than computed: the site records <em>which member</em>, and the
 * provider that produced the text is asked where it is once the text exists.</p>
 */
public class DecompiledMemberSiteTest {

    /** A directory of class files — source discovery does not look beside one, so nothing is attached. */
    private static File sourcelessClasspath() {
        return new File("../core/build/classes/java/main").getAbsoluteFile();
    }

    @BeforeClass
    public static void requireAnEngine() {
        Assume.assumeTrue(EngineHost.defaultSource() != EngineSource.NONE);
        Assume.assumeTrue(sourcelessClasspath().isDirectory());
        // BOTH, and in this order. `locate` asks JavaLanguage for the engine, so without the first call
        // it answers null for every member and every assertion below passes vacuously -- which is what
        // the first version of this test did, behind an Assume that read as "no decompiler on this host".
        JavaLanguage.register();
        LibrarySources.register();
    }

    private static final String SOURCE = ""
            + "import com.crystalgui.text.TextBuffer;\n"
            + "public class Script {\n"
            + "    void run() {\n"
            + "        TextBuffer buffer = new TextBuffer(\"x\");\n"
            + "        long v = buffer.version();\n"
            + "    }\n"
            + "}\n";

    private static SymbolInfo resolve(String needle) {
        JavaEngineFixture fixture = JavaEngineFixture.open();
        try (SourceAnalyzer.Analysis analysis = fixture.analyzer().analyze(
                "Script", SOURCE, List.of(sourcelessClasspath().getPath()),
                fixture.releaseLevel(), 1L)) {
            return analysis.resolveAt(SOURCE.indexOf(needle));
        }
    }

    /**
     * <b>The site names the member</b>, since its position cannot exist yet.
     *
     * <p>Asserted together with the {@code (0,0)}, because the two are one statement: the position is a
     * placeholder <em>and</em> there is now something that says how to replace it.</p>
     */
    @Test
    public void aSourcelessMemberSiteCarriesTheMemberName() {
        SymbolInfo symbol = resolve("version()");
        assertNotNull("nothing resolved for a classpath member", symbol);
        DeclarationSite site = symbol.declaration();
        assertNotNull("a member of a sourceless class was given nowhere to go", site);
        assertTrue("it should point into a library", site.isLibrary());
        assertEquals("the member the reader asked for is not recorded", "version", site.member());
    }

    /** ...and a TYPE names none, because the top of the file is where a class declaration is. */
    @Test
    public void aSourcelessTypeSiteCarriesNoMember() {
        SymbolInfo symbol = resolve("TextBuffer buffer");
        assertNotNull(symbol);
        DeclarationSite site = symbol.declaration();
        assertNotNull(site);
        assertNull("a type does not need finding inside its own file", site.member());
    }

    /**
     * <b>The provider finds the member in the text it produced</b> — the half that actually moves the
     * caret.
     *
     * <p>This is the whole feature end to end: decompile, analyse the output, and report where the member
     * is <em>in that output</em>. A line past the first is the assertion, because line 0 is exactly the
     * answer this replaces and any implementation that quietly failed would return it.</p>
     */
    @Test
    public void theProviderLocatesTheMemberInTheTextItServes() {
        TextPoint at = locate("version");
        assertNotNull("the provider could not place the member at all", at);
        assertTrue("the member was placed at the top of the file, which is the bug: " + at,
                at.row() > 0);
    }

    /**
     * A name the class does not declare is not guessed at.
     *
     * <p>The counter-assertion, and it is the one that catches a null-returning implementation: without
     * it, a {@code locate} that never worked would satisfy nothing above and still look like a passing
     * suite if the positive case were ever weakened to an assumption. It was, once.</p>
     */
    @Test
    public void anUnknownMemberIsNotLocated() {
        assertNull("a member nothing declares was given a position", locate("noSuchMemberAnywhere"));
    }

    /** Through the registry, which is the instance the workbench asks. */
    private static TextPoint locate(String member) {
        Resource resource = Resource.of(Resource.SCHEME_LIBRARY, "com.crystalgui.text.TextBuffer");
        return ResourceRegistry.providerFor(resource).locate(resource, member);
    }

    /**
     * Opens the shared engine the way every other test in this package does.
     *
     * <p>BORROWED, so nothing closes it: {@code over} shares the process's one band loader and closing it
     * would take every other engine in the suite down with it.</p>
     */
    private static final class JavaEngineFixture {
        private final JavaEngine engine;

        private JavaEngineFixture(JavaEngine engine) {
            this.engine = engine;
        }

        static JavaEngineFixture open() {
            return new JavaEngineFixture(JavaEngine.over(EngineHost.shared(EngineHost.defaultSource())));
        }

        SourceAnalyzer analyzer() {
            return engine.analyzer();
        }

        int releaseLevel() {
            return engine.releaseLevel();
        }
    }
}
