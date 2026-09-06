package com.crystalgui.headless;

import com.crystalgui.core.async.Reply;
import com.crystalgui.core.async.UiBudget;
import com.crystalgui.core.async.UiThread;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.ContentProvider;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A slow provider call on the frame thread is named, and the guard is <b>wired</b>.
 *
 * <p>The wiring is the half worth a test. {@link UiBudget} is a static nothing forces anyone to call,
 * so it survives a refactor perfectly while catching nothing — which is exactly what happened when the
 * registry that used to hold the timing wrapper was deleted. This asserts through
 * {@link Workspace#providerFor}, the door every reader goes through, so a future move of that door
 * fails here rather than silently unhooking the guard.</p>
 */
public class ProviderCallsAreBudgetedTest {

    /** Longer than the 2ms budget, short enough that a test run does not notice. */
    private static final long SLOW_MILLIS = 12L;

    private static final Resource LIBRARY = Resource.of(Resource.SCHEME_LIBRARY, "java.util.ArrayList");

    private Workspace workspace;

    /** Answers a symbol, slowly — the shape that reads like a property getter and runs a compile. */
    private static final class Slow implements ContentProvider {
        @Override
        public Reply<byte[]> read(Resource resource) {
            return Reply.of(new byte[0]);
        }

        @Override
        public SymbolInfo symbolOf(Resource resource) {
            sleep();
            return SymbolInfo.of("ArrayList", SymbolKind.CLASS);
        }

        @Override
        public String displayName(Resource resource) {
            sleep();
            return "ArrayList.java";
        }

        private static void sleep() {
            try {
                Thread.sleep(SLOW_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Before
    public void setUp() {
        UiBudget.forgetForTesting();
        // THE FRAME THREAD IS WHOEVER DRAWS, and the budget only measures work done on it -- so a test
        // that did not claim it would time nothing and pass against an unwired guard.
        UiThread.markCurrent();
        workspace = Workspace.over((method, args, onResult, onError) -> { },
                (method, handler) -> { }, PlainOps.INSTANCE);
        workspace.registerScheme(Resource.SCHEME_LIBRARY, new Slow());
    }

    @After
    public void tearDown() {
        UiBudget.forgetForTesting();
    }

    @Test
    public void aSlowSymbolLookupIsNamed() {
        assertNotNull(workspace.providerFor(LIBRARY).symbolOf(LIBRARY));

        assertTrue("a 12ms call on the frame thread must be reported, and by name",
                UiBudget.hasReported("symbolOf " + LIBRARY));
    }

    /** The presentation path a dock re-reads on every strip rebuild goes through the same door. */
    @Test
    public void aSlowDisplayNameIsNamedToo() {
        workspace.providerFor(LIBRARY).displayName(LIBRARY);

        assertTrue(UiBudget.hasReported("displayName " + LIBRARY));
    }

    /**
     * The counter-control. Without it a guard written as "report everything" passes both tests above
     * and buries the report it exists to make.
     */
    @Test
    public void aFastCallIsNotReported() {
        ContentProvider quick = new ContentProvider() {
            @Override
            public Reply<byte[]> read(Resource resource) {
                return Reply.of(new byte[0]);
            }

            @Override
            public SymbolInfo symbolOf(Resource resource) {
                return SymbolInfo.of("Fast", SymbolKind.CLASS);
            }
        };
        Resource quickly = Resource.of("quick", "x");
        workspace.registerScheme("quick", quick2 -> Reply.of(new byte[0]));
        workspace.registerScheme("quick", quick);

        workspace.providerFor(quickly).symbolOf(quickly);

        assertFalse("an ordinary answer is not worth a line",
                UiBudget.hasReported("symbolOf " + quickly));
    }

    /**
     * The wrapper is kept per provider, so the door does not allocate on a path the dock takes per tab
     * per rebuild — and two answers about one scheme are the same object.
     */
    @Test
    public void theDoorAnswersTheSameViewEveryTime() {
        assertTrue(workspace.providerFor(LIBRARY) == workspace.providerFor(LIBRARY));
    }
}
