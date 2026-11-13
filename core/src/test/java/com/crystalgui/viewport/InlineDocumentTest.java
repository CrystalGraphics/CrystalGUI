package com.crystalgui.viewport;

import com.crystalgui.core.async.UiSequence;
import com.crystalgui.core.async.UiThread;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UIDocument;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * An inline document run from a shell on a sequence of its own: the shell's thread is never the document's, work it
 * sends waits for the host's drain, and what that work throws is the document's fault, not the host's.
 */
public class InlineDocumentTest {

    private UiSequence shell;
    private UIDocument document;
    private CgUiViewport viewport;

    @Before
    public void open() {
        shell = UiSequence.create("test-shell");
        document = new UIDocument();
        viewport = CgUiViewport.open(document, CgUiViewport.Threading.INLINE, "inline-app");
    }

    @After
    public void close() {
        UiSequence.serveWhileWaiting(null, null);
        viewport.close();
        shell.close();
        UiThread.forgetForTesting();
    }

    /** Runs {@code work} as a task of the shell's sequence, on a pool thread, and answers what it answered. */
    private <T> T onShell(java.util.function.Supplier<T> work) {
        CompletableFuture<T> done = new CompletableFuture<>();
        shell.execute(() -> {
            try {
                done.complete(work.get());
            } catch (Throwable failed) {
                done.completeExceptionally(failed);
            }
        });
        try {
            return done.get(5, TimeUnit.SECONDS);
        } catch (Exception failed) {
            throw new AssertionError("the shell's turn did not finish", failed);
        }
    }

    @Test
    public void aSequenceIsNotTheHostBeforeTheHostHasCome() {
        assertTrue("a plain thread builds the document until the host's first visit", document.isCurrent());
        assertFalse("the shell launching an application is not the host", onShell(document::isCurrent));
        viewport.drain();
        assertTrue(document.isCurrent());
        assertFalse(onShell(document::isCurrent));
    }

    @Test
    public void workSentFromTheShellRunsInTheHostsDrainOnTheHostsThread() {
        Thread[] ranOn = new Thread[1];
        onShell(() -> {
            document.execute(() -> ranOn[0] = Thread.currentThread());
            return null;
        });
        assertEquals("nothing runs until the host comes by", null, ranOn[0]);
        viewport.drain();
        assertSame(Thread.currentThread(), ranOn[0]);
    }

    /** The shell closing an application from the render thread: a sequence's task on the host's thread. */
    @Test
    public void aSequencesTaskOnTheHostsThreadSendsItsWorkAndItRunsAtOnceAsTheHosts() {
        viewport.drain();
        Signal.Action action = new Signal.Action();
        boolean[] hosting = {false};
        boolean[] ran = {false};
        shell.runNow(() -> {
            assertFalse("a sequence's task is not the host's work", document.isCurrent());
            document.execute(() -> {
                ran[0] = true;
                hosting[0] = UiThread.isHosting();
                action.emit();
            });
            assertTrue("run in place, not left waiting for a frame that is this thread's", ran[0]);
        });
        assertTrue(hosting[0]);
    }

    /** The host parked in a sequence's turn serves what that turn waits on, or the two wait for each other for good. */
    @Test
    public void theHostWaitingOnTheShellServesWhatTheShellWaitsOn() throws Exception {
        viewport.drain();
        Thread host = Thread.currentThread();
        UiSequence.serveWhileWaiting(host, viewport::drain);
        CompletableFuture<Void> shellStarted = new CompletableFuture<>();
        boolean[] answered = {false};
        shell.execute(() -> {
            shellStarted.complete(null);
            // A launch: sent to the document, and waited for.
            CompletableFuture<Void> done = new CompletableFuture<>();
            document.execute(() -> done.complete(null));
            try {
                done.get(5, TimeUnit.SECONDS);
                answered[0] = true;
            } catch (Exception ignored) {
            }
        });
        shellStarted.get(5, TimeUnit.SECONDS);
        // The host calls into the shell while its turn is running, parked on the host.
        shell.runNow(() -> { });
        assertTrue("the shell's turn was left waiting for the host", answered[0]);
    }

    /** The shell forwarding a click from its sequence: it is the host's to dispatch, in the host's drain. */
    @Test
    public void inputOfferedFromTheShellRunsInTheHostsDrain() {
        viewport.drain();
        Thread[] ranOn = new Thread[1];
        boolean taken = onShell(() -> viewport.offer(() -> {
            ranOn[0] = Thread.currentThread();
            return true;
        }));
        assertTrue(taken);
        assertEquals(null, ranOn[0]);
        viewport.drain();
        assertSame(Thread.currentThread(), ranOn[0]);
    }

    @Test
    public void aSignalDeclaredAnyThreadIsNeverRefused() throws Exception {
        UiThread.enterHost();
        Signal.Action reports = new Signal.Action().anyThread();
        int[] heard = {0};
        reports.connect(() -> heard[0]++);
        Thread script = new Thread(reports::emit, "script");
        script.start();
        script.join();
        onShell(() -> {
            reports.emit();
            return null;
        });
        assertEquals(2, heard[0]);
    }

    @Test
    public void workThatThrowsIsTheDocumentsFaultAndTheRestStillRunsAndTheRestStillRuns() {
        List<Throwable> faults = new ArrayList<>();
        viewport.onFault(faults::add);
        boolean[] later = {false};
        onShell(() -> {
            document.execute(() -> {
                throw new IllegalStateException("broken application");
            });
            document.execute(() -> later[0] = true);
            return null;
        });
        viewport.drain();
        assertEquals(1, faults.size());
        assertEquals("broken application", faults.get(0).getMessage());
        assertTrue("one task's fault must not drop the next", later[0]);
    }

    @Test
    public void aSignalUsedInsideTheDocumentBelongsToTheHostThreadAndOneBuiltOutsideStaysFree() {
        Signal.Action building = new Signal.Action();
        building.emit();
        onShell(() -> {
            building.emit();
            return null;
        });
        UiThread.enterHost();
        Signal.Action action = new Signal.Action();
        action.emit();
        Throwable[] refused = new Throwable[1];
        onShell(() -> {
            try {
                action.emit();
            } catch (IllegalStateException e) {
                refused[0] = e;
            }
            return null;
        });
        assertTrue("the shell's sequence must be refused", refused[0] != null);
        assertTrue(refused[0].getMessage(), refused[0].getMessage().contains("belongs to thread"));
        action.emit();
    }
}
