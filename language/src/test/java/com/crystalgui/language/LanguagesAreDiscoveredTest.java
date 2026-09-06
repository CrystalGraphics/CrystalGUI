package com.crystalgui.language;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import com.crystalgui.text.syntax.LanguageRegistry;

/**
 * <b>The jar being on the classpath is what switches the languages on</b> — nothing calls anything.
 *
 * <p>It was {@code LanguageStack.registerAll()} in two hosts — the 1.7.10 loader and the harness — each
 * carrying a paragraph explaining why it had to be there. A third host got no grammars, no ECJ and no
 * Rhino, and there was nothing to see: an editor that colours from {@code core/}'s word-list lexers and
 * does not analyse is a <em>supported</em> configuration, so a forgotten call is indistinguishable from a
 * deployment that ships no engine band.</p>
 *
 * <p>{@link LanguageStack} <b>is</b> the {@code LanguageKinds} service rather than being named by one,
 * for the reason {@code ScriptWorkbench} is its own extension: one lifetime, one purpose, and a second
 * class whose only real content is the first one's name.</p>
 *
 * <p>Like {@code ScriptingIsDiscoveredTest}, this can only be written in {@code language/} — the
 * {@code META-INF/services} entry ships in <em>this</em> jar, so a core test would be asserting about a
 * classpath it does not have. That is the point rather than an inconvenience: it is exactly the file a
 * module can forget to ship, and forgetting it is silent.</p>
 */
public class LanguagesAreDiscoveredTest {

    @After
    public void forgetDiscovery() {
        // The registry is process-wide and discovery runs once; leaving the flag set would decide
        // another test's answer. The RULES are deliberately left alone -- see resetBootstrapForTesting.
        LanguageRegistry.resetBootstrapForTesting();
    }

    /** Nothing here registers anything: reading the registry is the whole mechanism. */
    @Test
    public void thisModulesLanguagesAreFoundWithoutAnyHostCall() {
        LanguageRegistry.resetBootstrapForTesting();
        assertTrue("nothing discovered LanguageStack, so every host would have to call registerAll() "
                        + "again -- which is the arrangement that made grammars a fact about which loader "
                        + "you launched. Contributors: " + LanguageRegistry.contributors(),
                LanguageRegistry.contributors().contains(LanguageStack.class.getName()));
    }

    /**
     * The counter-control: a read is what triggers discovery, and it reports what ran.
     *
     * <p>Without it a {@code contributors()} that answered some fixed list would satisfy the assertion
     * above whether or not the service was ever invoked. Asking a question about a FILE is the trigger —
     * which is also the property that keeps a dedicated server, which classifies nothing, from ever
     * loading a grammar.</p>
     */
    @Test
    public void aQuestionAboutAFileIsWhatRunsThem() {
        LanguageRegistry.resetBootstrapForTesting();
        assertTrue("nothing had run yet, so the list should have been empty",
                LanguageRegistry.contributorsSoFarForTesting().isEmpty());

        LanguageRegistry.forFileName("Anything.java");

        assertFalse("classifying a file did not run the contributors",
                LanguageRegistry.contributorsSoFarForTesting().isEmpty());
    }
}
