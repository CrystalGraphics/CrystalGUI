package com.crystalgui.language.java;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.fs.Resource;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.fs.client.ContentProvider;
import com.crystalgui.fs.client.ContentProviders;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.text.lang.SymbolInfo;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Working out what a library class IS never happens on the thread that draws frames.
 *
 * <h3>761 milliseconds behind a method that reads like a getter</h3>
 *
 * <p>{@code symbolOf(Resource)} is a presentation provider: the dock re-reads it every time a strip is
 * rebuilt, and the file tree asks it while binding a row. Both are on the frame thread. Answering meant
 * compiling a probe unit against the whole classpath — measured at <b>761ms</b> — so the first appearance
 * of any library class in the dock stopped the application for the better part of a second. Nothing at
 * either call site suggested it might.</p>
 *
 * <p>So the answer is deferred: null now, computed on {@link JobScheduler}, announced through
 * {@link ContentProvider#onDidResolveSymbol} when it lands. The glyph arrives a moment late, which is what
 * every IDE does with a decompiled tab and is the only alternative to a frozen frame.</p>
 */
public class LibrarySymbolDeferralTest {

    /**
     * A type per test, because the answer cache is a process-lived static.
     *
     * <p>Sharing one made the pair order-dependent and it failed on the first run: whichever test drained
     * first left the answer behind, so "the first ask returns nothing" got the cached symbol and read as
     * the deferral not working. JUnit does not promise an order, so this would have been intermittent
     * rather than merely wrong.</p>
     */
    private static final Resource DEFERRED =
            Resource.of(Resource.SCHEME_LIBRARY, "com.crystalgui.text.TextBuffer");

    private static final Resource DELIVERED =
            Resource.of(Resource.SCHEME_LIBRARY, "com.crystalgui.text.Rope");

    @BeforeClass
    public static void openTheStack() {
        Assume.assumeTrue(EngineHost.defaultSource() != EngineSource.NONE);
        JavaLanguage.register();
        LibrarySources.register();
    }

    private static SymbolInfo ask(Resource of) {
        return providerFor(of).symbolOf(of);
    }

    private static ContentProvider providerFor(Resource resource) {
        for (ContentProviders.Contribution contribution : ContentProviders.all()) {
            if (contribution.scheme().equals(resource.scheme())) return contribution.provider();
        }
        throw new AssertionError("nothing is contributed for " + resource.scheme());
    }

    /**
     * <b>The first ask answers "not yet"</b>, and that is the assertion that cannot pass against an
     * inline compile.
     *
     * <p>Deliberately not a timing assertion. "It returned in under N ms" is a statement about the
     * machine as much as the code and fails on a loaded CI box; "it returned nothing" is a statement
     * about the code alone, and an implementation that compiled inline could only return the symbol.</p>
     */
    @Test
    public void theFirstAskIsAnsweredWithoutCompiling() {
        assertNull("a compile ran on the calling thread -- @see LibrarySources#scheduleDescribe",
                ask(DEFERRED));
    }

    /**
     * ...and the answer arrives, is announced, and is then free.
     *
     * <p>Ordered as one test on purpose: "it defers" and "it eventually answers" are halves of one
     * contract, and a deferral that never lands is a worse bug than the stall it replaced — the glyph
     * would simply never appear.</p>
     */
    @Test
    public void theAnswerArrivesOnADrainAndIsAnnounced() {
        AtomicInteger announced = new AtomicInteger();
        Disposable watch = providerFor(DELIVERED)
                .onDidResolveSymbol(resource -> announced.incrementAndGet());
        try {
            ask(DELIVERED);
            SymbolInfo landed = null;
            for (int frame = 0; frame < 600 && landed == null; frame++) {
                // A FRAME IS WHAT DELIVERS IT: drain() is documented as "call once per frame, on the UI
                // thread", and onDone runs inside it. Stepping frames here is what the application does.
                JobScheduler.shared().drain();
                landed = ask(DELIVERED);
                if (landed == null) Thread.sleep(10L);
            }
            assertNotNull("the deferred answer never landed, so the glyph never appears", landed);
            assertTrue("nothing was told the answer had arrived, so no view re-reads it",
                    announced.get() > 0);
            assertNotNull("the answer was not kept, so every strip rebuild would schedule again",
                    ask(DELIVERED));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            watch.dispose();
        }
    }
}
