package com.crystalgui.language.js;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;

import org.junit.After;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>§10.3b — the compatibility band.</b>
 *
 * <p>A modder on Java 17 writes {@code a?.b ?? c}, their Rhino takes it, and the script fails to load
 * for every 1.7.10 player. Six constructs sit in that gap and the local engine cannot find them for
 * itself, because a deployment ships one band and the older parser is not there to ask. The answer
 * travels as data: band 8's measured probe output ships as a resource.</p>
 *
 * <p>Skipped unless this host is <em>newer</em> than band 8, since on band 8 these are already syntax
 * errors and the feature has nothing to say.</p>
 */
public class JsCompatibilityBandTest {

    @BeforeClass
    public static void openTheEngine() {
        Assume.assumeTrue(EngineHost.defaultSource() != EngineSource.NONE);
        Assume.assumeTrue(JsLanguage.register(null, EngineHost.defaultSource()));
        Assume.assumeTrue("this host IS band 8, so there is nothing to warn about",
                EngineBand.detect().minimumFeatureVersion() > 8);
    }

    @After
    public void clearTheBand() {
        JsLanguage.compatibleWith(null);
    }

    private static List<String> warningsIn(String source) {
        List<String> messages = new ArrayList<>();
        for (Diagnostic problem : JsLanguage.analyzer().analyze("Probe.js", source, 1L).diagnostics()) {
            if (problem.severity() == DiagnosticSeverity.WARNING) messages.add(problem.message());
        }
        return messages;
    }

    private static boolean anyMentions(List<String> messages, String fragment) {
        return messages.stream().anyMatch(message -> message.contains(fragment));
    }

    /**
     * <b>Off by default</b>, and that is the setting rather than an oversight.
     *
     * <p>Most scripts are written and run on one machine, so a warning about a deployment nobody named
     * is noise — and noise in the one channel that has to stay trustworthy.</p>
     */
    @Test
    public void nothingIsReportedUntilATargetIsNamed() {
        assertEquals(List.of(), warningsIn("var a = b?.c ?? 'x';\n"));
    }

    /** The two operators an author actually reaches for, and the reason this milestone exists. */
    @Test
    public void optionalChainingAndNullishCoalescingAreReportedForBandEight() {
        JsLanguage.compatibleWith(EngineBand.JAVA_8);
        List<String> warnings = warningsIn("var name = player?.name ?? 'nobody';\n");

        assertTrue("optional chaining went unreported: " + warnings,
                anyMentions(warnings, "optional chaining"));
        assertTrue("nullish coalescing went unreported: " + warnings,
                anyMentions(warnings, "nullish coalescing"));
        assertTrue("the message must say where it will not load: " + warnings,
                anyMentions(warnings, "Java 8"));
    }

    /** The four the AST has to locate — defaults, spread and a computed key. */
    @Test
    public void theOtherFourConstructsAreReportedToo() {
        JsLanguage.compatibleWith(EngineBand.JAVA_8);

        assertTrue(anyMentions(warningsIn("function f(a = 1) { return a; }\n"), "default value"));
        assertTrue(anyMentions(warningsIn("var xs = [...ys];\n"), "spread"));
        assertTrue(anyMentions(warningsIn("var o = { [k]: 1 };\n"), "computed property"));
    }

    /**
     * <b>A {@code ??} inside a string or a comment is not a use of the operator.</b>
     *
     * <p>Not a nicety. {@code "why??"} in a message is ordinary, and a warning on it teaches the author
     * that this channel is wrong about things — after which the real one goes unread too. The spans come
     * off Rhino's own lex rather than a scanner written here, because a second lexer would be a second
     * thing to get wrong about escapes and nesting.</p>
     */
    @Test
    public void punctuationInsideAStringOrCommentIsNotAnOperator() {
        JsLanguage.compatibleWith(EngineBand.JAVA_8);
        List<String> warnings = warningsIn("var m = 'why?? really';\n// and a comment?.\n");
        assertEquals("a string or a comment was read as syntax: " + warnings, List.of(), warnings);
    }

    /**
     * <b>Only ever downward.</b> A target at or above this host reports nothing: a construct the local
     * engine refuses is already a syntax error, and saying it twice in two severities is worse than
     * saying it once.
     */
    @Test
    public void aTargetAtOrAboveThisHostIsSilent() {
        JsLanguage.compatibleWith(EngineBand.detect());
        assertEquals(List.of(), warningsIn("var a = b?.c ?? 'x';\n"));
    }

    /**
     * And a file that does not parse says nothing, for a sharper reason than the other optional
     * problems have: this reports constructs <em>this</em> engine accepted, so with no tree the
     * question is not unreliable, it is unanswerable.
     */
    @Test
    public void abrokenFileIsNotGuessedAbout() {
        JsLanguage.compatibleWith(EngineBand.JAVA_8);
        List<String> warnings = warningsIn("var a = b?.c ?? 'x';\nfunction ( {\n");
        assertFalse("a warning was produced from a half-parsed tree: " + warnings,
                anyMentions(warnings, "optional chaining"));
    }
}
