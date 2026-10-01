package com.crystalgui.language.java;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.syntax.SyntaxToken;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertTrue;

/** Every semantic token sits on the text it names, including after a statement that does not compile. */
public class SemanticTokenAlignmentTest {

    private static final String CLEAN = """
            import java.util.List;
            public class Script {
                /** A doc comment — with a dash, as real ones have. */
                public int write(String name, List<String> rows) {
                    return rows.isEmpty() ? -1 : rows.size();
                }

                public int writeAll(String name, List<String> rows) {
                    return rows.size();
                }
            }
            """;

    private static final String BROKEN_LINE = "\n        System.";

    private JavaEngine engine;
    private JobScheduler scheduler;
    private final AtomicLong clock = new AtomicLong();

    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping", !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
        scheduler = new JobScheduler(Runnable::run, clock::get, 1);
    }

    @After
    public void closeEngine() throws IOException {
        if (scheduler != null) scheduler.dispose();
        if (engine != null) engine.close();
    }

    private void settle() {
        clock.addAndGet(1_000);
        for (int i = 0; i < 4; i++) scheduler.drain();
    }

    private static int brokenAt(String text) {
        String anchor = "rows.size();";
        return text.indexOf(anchor) + anchor.length();
    }

    @Test
    public void tokensSitOnTheirWordsInSourceThatDoesNotCompile() {
        String text = new StringBuilder(CLEAN).insert(brokenAt(CLEAN), BROKEN_LINE).toString();
        TextBuffer buffer = new TextBuffer(text);
        JavaLanguageServices services = new JavaLanguageServices(buffer, engine, scheduler, "Script", List.of());
        try {
            settle();
            assertAligned(buffer, services);
        } finally {
            services.close();
        }
    }

    @Test
    public void tokensSitOnTheirWordsAfterTypingABrokenLine() {
        TextBuffer buffer = new TextBuffer(CLEAN);
        JavaLanguageServices services = new JavaLanguageServices(buffer, engine, scheduler, "Script", List.of());
        try {
            settle();
            int at = brokenAt(CLEAN);
            for (int i = 0; i < BROKEN_LINE.length(); i++) {
                buffer.edit(ChangeSet.of(buffer.length(), new Change(at + i, at + i, String.valueOf(BROKEN_LINE.charAt(i)))));
            }
            settle();
            assertAligned(buffer, services);
        } finally {
            services.close();
        }
    }

    /** The editor reads tokens while the next analysis is still debouncing: they must already be in the new text's offsets. */
    @Test
    public void tokensFollowEditsTheAnalysisHasNotSeen() {
        TextBuffer buffer = new TextBuffer(CLEAN);
        JavaLanguageServices services = new JavaLanguageServices(buffer, engine, scheduler, "Script", List.of());
        try {
            settle();
            int at = brokenAt(CLEAN);
            for (int i = 0; i < BROKEN_LINE.length(); i++) {
                buffer.edit(ChangeSet.of(buffer.length(), new Change(at + i, at + i, String.valueOf(BROKEN_LINE.charAt(i)))));
            }
            assertAligned(buffer, services);
        } finally {
            services.close();
        }
    }

    private static void assertAligned(TextBuffer buffer, JavaLanguageServices services) {
        String text = buffer.document().toString();
        List<String> misplaced = new ArrayList<>();
        for (SyntaxToken token : services.semanticTokens().tokensIn(0, text.length())) {
            String word = text.substring(token.start(), token.end());
            boolean starts = token.start() == 0 || !Character.isJavaIdentifierPart(text.charAt(token.start() - 1));
            boolean ends = token.end() == text.length() || !Character.isJavaIdentifierPart(text.charAt(token.end()));
            if (!starts || !ends || !word.chars().allMatch(Character::isJavaIdentifierPart)) {
                misplaced.add(token.start() + ".." + token.end() + " '" + word + "' " + token.name());
            }
        }
        assertTrue("semantic tokens off their words (analysis v" + services.semanticTokens().version()
                + ", buffer v" + buffer.version() + "):\n" + String.join("\n", misplaced), misplaced.isEmpty());
    }
}
