package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.java.assist.JavaCompletionProvider;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.Versioned;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>The member list a trailing dot actually produces</b>, driven through the provider.
 *
 * <p>The analyser was never the problem: asked directly, {@code java.io.PrintStream} answers forty-six
 * members on both bands, with a trailing dot and with the completion probe's own inserted name. What was
 * reported — {@code System.out.} offering nothing, {@code getMinecraft().} offering three — happens a
 * layer up, so that is the layer this drives.</p>
 *
 * <p>No platform is registered, deliberately. The same shapes misbehave in the harness, which has none,
 * so anything that needed one would be the wrong suspect.</p>
 */
public class MemberCompletionShapeTest {

    private JavaEngine engine;

    @Before
    public void open() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
    }

    @After
    public void close() throws Exception {
        if (engine != null) engine.close();
        engine = null;
    }

    /** Everything the provider offers at {@code offset} in {@code source}, as filter keys. */
    private List<String> completionsAt(String source, int offset) {
        return completionsAt(source, offset, source);
    }

    /**
     * The same, but the held analysis is of {@code analysed} rather than of the buffer's own text.
     *
     * <p>Which is the editor's ordinary state, not a corner case: the analyser is debounced 300ms behind
     * the keystroke, so at the instant a member list opens the analysis in hand describes the text as it
     * was several characters ago. A test that analyses exactly what it asks about is testing the one
     * moment the editor is never in.</p>
     */
    private List<String> completionsAt(String source, int offset, String analysed) {
        TextBuffer buffer = new TextBuffer(source);
        AtomicReference<Analysis> held = new AtomicReference<>();
        held.set(engine.analyzer().analyze("Script", analysed, HostClasspath.detect(),
                engine.releaseLevel(), 1L));

        JavaCompletionProvider provider = new JavaCompletionProvider(buffer, held::get,
                JavaLanguageServices.typeIndexFor(HostClasspath.detect()),
                text -> engine.analyzer().analyze("Script", text, HostClasspath.detect(),
                        engine.releaseLevel(), 1L));

        List<String> keys = new ArrayList<>();
        provider.complete(CompletionProvider.Request.explicit(offset, ""), answer -> {
            CompletionList list = answer.value();
            for (CompletionItem item : list.items()) keys.add(item.filterKey());
        });
        Analysis analysis = held.get();
        if (analysis != null) analysis.close();
        return keys;
    }

    /**
     * <b>A field receiver offers its type's members.</b>
     *
     * <p>{@code System.out.} is the shape reported as offering nothing at all, and it is about as
     * ordinary as Java gets — the first line most people write.</p>
     */
    @Test
    public void aFieldReceiverOffersItsMembers() {
        String source = "public class Script {\n"
                + "    void run() {\n"
                + "        System.out.\n"
                + "    }\n"
                + "}\n";
        List<String> keys = completionsAt(source, source.indexOf("System.out.") + "System.out.".length());
        assertFalse("System.out. offered nothing", keys.isEmpty());
        assertTrue("println was not offered: " + keys, keys.contains("println"));
    }

    /**
     * <b>A CALL is a receiver too</b>, and its members are the return type's.
     *
     * <p>{@code getMinecraft().} is the shape reported as offering three rows. A {@code )} sits
     * immediately before the dot, where no identifier covers the offset — the case AGENTS.md already
     * records both engines getting wrong once.</p>
     */
    @Test
    public void aCallReceiverOffersTheReturnTypesMembers() {
        String source = "import java.util.ArrayList;\n"
                + "public class Script {\n"
                + "    void run() {\n"
                + "        new ArrayList<String>().\n"
                + "    }\n"
                + "}\n";
        String upTo = "new ArrayList<String>().";
        List<String> keys = completionsAt(source, source.indexOf(upTo) + upTo.length());
        assertFalse("a call receiver offered nothing", keys.isEmpty());
        assertTrue("add was not offered: " + keys, keys.contains("add"));
        assertTrue("size was not offered: " + keys, keys.contains("size"));
    }

    /**
     * <b>A member list is correct while the analyser is BEHIND</b>, which is every time it opens.
     *
     * <p>The analysis here describes {@code System} alone — the state the editor is genuinely in when the
     * {@code .} lands, since nothing has been recompiled since {@code Syste}. The offsets in that tree do
     * not describe the buffer any more, so the direct resolve is either nothing or, worse, something
     * plausible: a stale node that happens to cover the offset resolves to the wrong type and answers with
     * a handful of unrelated members, which reads as the member list being wrong rather than stale.</p>
     *
     * <p>The probe re-parse is what is supposed to catch both. This asserts it does.</p>
     */
    @Test
    public void aStaleAnalysisStillProducesTheRightMembers() {
        String typed = "public class Script {\n"
                + "    void run() {\n"
                + "        System.out.\n"
                + "    }\n"
                + "}\n";
        String behind = typed.replace("System.out.", "System");
        List<String> keys = completionsAt(typed, typed.indexOf("System.out.") + "System.out.".length(),
                behind);
        assertFalse("a stale analysis produced no members at all", keys.isEmpty());
        assertTrue("println was not offered from a stale analysis: " + keys, keys.contains("println"));
    }
}
