package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.map.ReadableView;
import com.crystalgui.language.platform.MappingCoordinates;
import com.crystalgui.language.platform.NamespaceProbe;
import com.crystalgui.language.platform.ScriptPlatform;
import com.crystalgui.language.platform.ScriptPlatforms;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.SymbolInfo;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>The EDITOR resolves a type that exists only in live bytes.</b>
 *
 * <p>This is the half that was missing, and it was missing invisibly: the compiler was handed an
 * {@code INameEnvironment} and the analyser was handed a list of files, so on an obfuscated Minecraft
 * host a script compiled and ran perfectly while the editor could not resolve a single Minecraft type —
 * red names, no completion, and a quick fix that could not offer the import because the type index had
 * never heard of the class.</p>
 *
 * <p>The fixture serves a class through {@link ScriptPlatform#liveBytes()} that is <b>on no classpath at
 * all</b>. Nothing but the live route can resolve it, so this fails outright if the analyser falls back
 * to {@code ASTParser} — which is precisely what it did before, and what it still does everywhere no
 * platform is registered.</p>
 */
public class LiveAnalysisTest {

    /** Deliberately not a real package on any classpath — only the fixture can produce it. */
    private static final String OWNER = "demo/live/OnlyInMemory";

    private JavaEngine engine;

    @Before
    @After
    public void forget() {
        ScriptPlatforms.reset();
        PlatformMappings.resetForTesting();
    }

    /** A class declaring one method, synthesized rather than compiled — it exists nowhere on disk. */
    private static byte[] onlyInMemory() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, OWNER, null,
                "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC, "greeting", "()Ljava/lang/String;", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void registerPlatform() {
        ScriptPlatforms.register(new ScriptPlatform() {
            @Override
            public ReadableView.ByteSource liveBytes() {
                return name -> OWNER.equals(name) ? onlyInMemory() : null;
            }

            @Override
            public Path cacheRoot() {
                return Paths.get("build", "crystalgui-test-cache").toAbsolutePath();
            }

            @Override
            public MappingCoordinates mappings() {
                return MappingCoordinates.NONE;
            }

            @Override
            public NamespaceProbe namespaceProbe() {
                return NamespaceProbe.NONE;
            }

            @Override
            public String runtimeClassName(String onDiskInternalName) {
                return onDiskInternalName;
            }
        });
    }

    private SourceAnalyzer analyzerOverBand() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        // The platform must be registered BEFORE the engine opens: JavaEngine installs the TypeBytes on
        // both adapters in its constructor, which is the one place they cannot end up different.
        registerPlatform();
        engine = JavaEngine.open(band, source);
        return engine.analyzer();
    }

    @After
    public void closeEngine() throws Exception {
        if (engine != null) engine.close();
        engine = null;
    }

    /**
     * A type only the live source has resolves, with no error and with its member typed.
     *
     * <p>Both halves matter. No error proves the name resolved; the member's return type proves it was
     * resolved from real <em>bindings</em> rather than recovered as an unknown — which is what makes
     * hover, documentation and completion follow from it rather than needing their own fix.</p>
     */
    @Test
    public void aTypeThatExistsOnlyInLiveBytesResolvesInTheEditor() throws Exception {
        SourceAnalyzer analyzer = analyzerOverBand();
        String script = ""
                + "public class Script {\n"
                + "    String run() { return new demo.live.OnlyInMemory().greeting(); }\n"
                + "}\n";

        SourceAnalyzer.Analysis analysis =
                analyzer.analyze("Script", script, HostClasspath.detect(), engine.releaseLevel(), 1L);
        assertNotNull("the analyser produced nothing at all", analysis);
        try {
            for (Diagnostic problem : analysis.diagnostics()) {
                if (problem.severity() == DiagnosticSeverity.ERROR) {
                    fail("a live-only type did not resolve in the editor: " + problem);
                }
            }

            SymbolInfo call = analysis.resolveAt(script.indexOf(".greeting") + 2);
            assertNotNull("the member resolved to nothing", call);
            assertNotNull(call.type());
            assertEquals("java.lang.String", call.type().qualifiedName());
        } finally {
            analysis.close();
        }
    }

    /**
     * <b>An INCOMPLETE expression still resolves its receiver.</b>
     *
     * <p>A trailing dot is the commonest shape a completion popup ever opens on — {@code System.out.},
     * {@code getMinecraft().} — and it does not parse. Recovery is what makes the rest of the file
     * resolve anyway, and through this entry point it is a pair of bits on {@code convert} rather than
     * the {@code ASTParser} setters, so it was silently off: the member list came back empty, or holding
     * only what a single interface contributed.</p>
     *
     * <p>Asserted on the RECEIVER rather than on a member list, because that is the thing recovery
     * saves: with it, the declaration above the broken line still has a type; without it, nothing in the
     * unit does.</p>
     */
    @Test
    public void aTrailingDotStillResolvesWhatCameBefore() throws Exception {
        SourceAnalyzer analyzer = analyzerOverBand();
        // The last line is deliberately unparseable -- exactly what a popup opens on.
        String script = ""
                + "public class Script {\n"
                + "    void run() {\n"
                + "        demo.live.OnlyInMemory held = new demo.live.OnlyInMemory();\n"
                + "        held.\n"
                + "    }\n"
                + "}\n";

        SourceAnalyzer.Analysis analysis =
                analyzer.analyze("Script", script, HostClasspath.detect(), engine.releaseLevel(), 1L);
        assertNotNull(analysis);
        try {
            SymbolInfo receiver = analysis.resolveAt(script.indexOf("held.") + 2);
            assertNotNull("the receiver before a trailing dot resolved to nothing", receiver);
            assertNotNull(receiver.type());
            assertEquals("demo.live.OnlyInMemory", receiver.type().qualifiedName());
        } finally {
            analysis.close();
        }
    }

    /**
     * <b>And the same source fails without a platform</b>, which is what makes the test above mean
     * something.
     *
     * <p>Without this, a build where the live route silently never ran would pass the first test for the
     * wrong reason — the type would have to be on the classpath, and if it ever were, neither test would
     * notice. This asserts the fixture really is unreachable by the file-based path.</p>
     */
    @Test
    public void theSameSourceDoesNotResolveWithoutTheLiveRoute() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());

        // No platform registered, so TypeBytes is NONE and the analyser takes the ASTParser path.
        engine = JavaEngine.open(band, source);
        String script = ""
                + "public class Script {\n"
                + "    String run() { return new demo.live.OnlyInMemory().greeting(); }\n"
                + "}\n";

        SourceAnalyzer.Analysis analysis = engine.analyzer()
                .analyze("Script", script, HostClasspath.detect(), engine.releaseLevel(), 1L);
        assertNotNull(analysis);
        try {
            boolean unresolved = false;
            for (Diagnostic problem : analysis.diagnostics()) {
                if (problem.severity() == DiagnosticSeverity.ERROR) unresolved = true;
            }
            assertTrue("the fixture type resolved WITHOUT a platform, so it is on the classpath "
                    + "somewhere and the other test proves nothing", unresolved);
        } finally {
            analysis.close();
        }
    }

    // ── The client's actual shape ───────────────────────────────────────────────────────────────

    /** Labels the provider offers after {@code upTo}, through the full services stack. */
    private List<String> completeAfter(String source, String upTo) {
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Script", HostClasspath.detect());
        try {
            int caret = source.indexOf(upTo) + upTo.length();
            final CompletionList[] got = {CompletionList.EMPTY};
            services.completion().complete(
                    CompletionProvider.Request.character(caret, "", "."),
                    (Versioned<CompletionList> v) -> got[0] = v.orElse(CompletionList.EMPTY));
            List<String> labels = new ArrayList<>();
            for (CompletionItem item : got[0].items()) labels.add(item.label());
            return labels;
        } finally {
            services.close();
        }
    }

    /**
     * <b>A member list, on a bare SNIPPET, with a platform registered.</b>
     *
     * <p>Three things have to hold at once here and each is covered alone: the {@code DomResolution}
     * entry point, which only runs when a platform supplies live bytes; the prelude wrap and the offset
     * translation a script body needs; and recovery over a trailing dot, which does not parse. Every test
     * that existed covered two of the three — the snippet tests run with no platform, so they take
     * {@code ASTParser}, and the live tests are compilation units.</p>
     *
     * <p>That gap is the reported defect exactly: {@code System.out.} in a script opened a popup with no
     * rows at all, in the client and nowhere else. An unresolved receiver makes the answer
     * {@link CompletionList#partial}, so the popup does not even close itself — it stays on screen showing
     * a hint strip over nothing.</p>
     */
    @Test
    public void aSnippetOffersMembersUnderTheLiveRoute() throws Exception {
        analyzerOverBand();
        List<String> labels = completeAfter("System.out.\n", "System.out.");
        assertFalse("a snippet under the live route offered nothing at all", labels.isEmpty());
        assertTrue("println is missing from " + labels.size() + " rows: " + labels,
                labels.toString().contains("println"));
    }

    /** The same, for a call receiver — the shape reported as offering three rows. */
    @Test
    public void aSnippetCallReceiverOffersMembersUnderTheLiveRoute() throws Exception {
        analyzerOverBand();
        String upTo = "new java.util.ArrayList<String>().";
        List<String> labels = completeAfter(upTo + "\n", upTo);
        assertFalse("a call receiver under the live route offered nothing at all", labels.isEmpty());
        assertTrue("size() is missing from " + labels.size() + " rows: " + labels,
                labels.toString().contains("size"));
    }
}
