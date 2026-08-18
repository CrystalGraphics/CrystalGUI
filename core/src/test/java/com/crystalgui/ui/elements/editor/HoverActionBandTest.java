package com.crystalgui.ui.elements.editor;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>What earns a band in a HOVER, and what belongs to the bulb.</b>
 *
 * <p>{@code FixContext.intention} states the rule from the authoring end — a quick fix leaves its
 * description null because the compiler already said the useful thing, and an intention carries one
 * because otherwise the band "draws as a blank grey strip, which reads as a message that failed to load
 * rather than as a message that does not exist". This is the reading end: an action with neither a
 * diagnostic behind it nor a line about itself has nothing to put there, so it is not offered here.</p>
 *
 * <p>It was reported as clutter rather than as a blank header. The JavaScript catalog's
 * {@code refactor(id, title, edit)} helper takes no description where Java's requires one, and three of
 * its entries — {@code var}→{@code let}, {@code var}→{@code const}, and "Surround with try/catch" — match
 * very nearly every line, so a hover <em>anywhere</em> in a script grew an action bar with nothing above
 * it.</p>
 */
public class HoverActionBandTest {

    private static CodeAction bare(String id, String title) {
        return new CodeAction(id, title, CodeActionKind.REFACTOR, ChangeSet.empty(0), null, false, 1L);
    }

    private static CodeAction described(String id, String title, String description) {
        return bare(id, title).describedAs(description);
    }

    private static Diagnostic problem() {
        return new Diagnostic(new TextPoint(0, 0), new TextPoint(0, 4), DiagnosticSeverity.ERROR,
                "cannot find symbol", "engine", null);
    }

    @Test
    public void aSilentIntentionIsLeftToTheBulb() {
        List<CodeAction> kept = EditorLanguageFeatures.worthTheBand(List.of(),
                List.of(bare("var-to-let", "Change 'var' to 'let'"),
                        bare("wrap-try-catch", "Surround with try/catch")));
        assertTrue("an action with no diagnostic and no description has nothing to say in the band",
                kept.isEmpty());
    }

    @Test
    public void anIntentionThatExplainsItselfIsOffered() {
        List<CodeAction> kept = EditorLanguageFeatures.worthTheBand(List.of(),
                List.of(bare("wrap-try-catch", "Surround with try/catch"),
                        described("split", "Split into declaration and assignment",
                                "Separates the declaration from its initial value.")));
        assertEquals(1, kept.size());
        assertEquals("split", kept.get(0).id());
    }

    /**
     * <b>A diagnostic keeps everything.</b> The band's header is the compiler's message, so the strip is
     * never blank — and this is the case the gate was removed for: "Replace with lambda" is a
     * {@code QUICK_FIX} with the analyser reporting its site, and hiding it here would put the popup back
     * to offering nothing while the gutter bulb beside it said there was something.
     */
    @Test
    public void everyActionSurvivesBesideAProblem() {
        List<CodeAction> available = List.of(bare("to-lambda", "Replace with lambda"),
                bare("wrap-try-catch", "Surround with try/catch"));
        assertEquals(available, EditorLanguageFeatures.worthTheBand(List.of(problem()), available));
    }
}
