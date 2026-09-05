package com.crystalgui.language.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

import com.crystalgui.language.run.view.ScriptWorkbench;
import com.crystalgui.workbench.extension.WorkbenchExtension;
import com.crystalgui.workbench.extension.WorkbenchExtensions;

/**
 * <b>The jar being on the classpath is what offers scripting</b> — nothing calls anything.
 *
 * <p>It was {@code WorkbenchExtensions.contribute(ScriptWorkbench.extension())} from
 * {@code LanguageStack.registerAll}, the last {@code contribute} left in the tree, and the argument for
 * keeping it was that availability here is a question about an engine BAND rather than about a jar.
 * That does not distinguish the two: the band is asked at {@code activate}, which answers a no-op
 * handle when no runtime is present, so discovery degrades identically — and needs nobody to remember
 * a call.</p>
 *
 * <p>{@link ScriptWorkbench} <b>is</b> the extension rather than being named by one: one lifetime, one
 * id, and a {@code ScriptingExtension} beside it would have had the name of this class as its only real
 * content.</p>
 *
 * <p>This test can only be written in {@code language/}: the {@code META-INF/services} entry ships in
 * <em>this</em> jar, so a core test would be asserting about a classpath it does not have. That is the
 * point rather than an inconvenience — it is the same reason the harness and the 1.7.10 screen each had
 * to remember the old call, and one of them once did not.</p>
 */
public class ScriptingIsDiscoveredTest {

    @After
    public void resetRegistry() {
        // The registry is process-wide and bootstraps on first read; leaving it populated would decide
        // another test's answer.
        WorkbenchExtensions.resetForTesting();
    }

    /** Nothing in this test registers anything — the services entry is the whole mechanism. */
    @Test
    public void theRunShellIsOnTheClasspathAndThereforeAvailable() {
        WorkbenchExtension found = WorkbenchExtensions.byId(ScriptWorkbench.ID);
        assertNotNull("nothing discovered crystalgui:scripting, so a host would have to contribute it "
                + "again -- which is the arrangement that made the Run panel a fact about which loader "
                + "you launched", found);
        assertEquals("...and it is the shell ITSELF, not a wrapper naming it", ScriptWorkbench.class,
                found.getClass());
    }

    /**
     * The counter-control: the registry answers null for an id nothing ships.
     *
     * <p>Without it a {@code byId} that returned some default for every string would satisfy the
     * assertion above, and the whole test would be measuring nothing.</p>
     */
    @Test
    public void anIdNothingShipsIsNotFound() {
        assertNull("the registry answered for an id no jar declares",
                WorkbenchExtensions.byId("crystalgui:no-such-feature"));
    }
}
