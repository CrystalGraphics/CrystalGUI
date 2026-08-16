package com.crystalgui.language.run;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextBuffer;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * M9.5 §9.5.4 — {@code System.in} for a running script.
 *
 * <p>Every case here is a <b>hang</b> if it is wrong, which is why they are all under a timeout: a
 * blocking read that never returns produces no exception, no output and no failing assertion — it simply
 * stops, and a test without a deadline stops with it.</p>
 */
public class ScriptInputTest {

    private static final ScriptRef SCRIPT =
            ScriptRef.ofClass(Resource.of(CgPath.of("workspace", "src/Main.java")), "Main");

    private static RunConsole console() {
        return new RunConsole().attach(new TextBuffer());
    }

    /** Runs {@code body} on a thread that is marked as being inside a script, as ScriptHost does. */
    private static Thread inScript(Runnable body) {
        Thread thread = new Thread(() -> {
            ScriptRef previous = ScriptOutput.enter(SCRIPT);
            try {
                body.run();
            } finally {
                ScriptOutput.exit(previous);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void awaitAwaiting(RunConsole console) throws InterruptedException {
        for (int i = 0; i < 400 && !console.isAwaitingInput(); i++) Thread.sleep(5);
        assertTrue("the reader never blocked", console.isAwaitingInput());
    }

    /**
     * <b>Off a script thread, nothing is intercepted.</b>
     *
     * <p>The half that matters most: {@code System.in} is the game's and every other mod's too, and
     * routing it wholesale would park them on a text field in a panel that may not even be open.</p>
     */
    @Test(timeout = 10_000)
    public void aReadOutsideAScriptGoesStraightThrough() throws Exception {
        InputStream passthrough = new ByteArrayInputStream("from the real stream\n".getBytes(StandardCharsets.UTF_8));
        InputStream routed = ScriptInput.routed(passthrough, console());

        String line = new BufferedReader(new InputStreamReader(routed, StandardCharsets.UTF_8)).readLine();
        assertEquals("from the real stream", line);
    }

    /** Inside a script, a read waits for the panel and gets exactly what was submitted. */
    @Test(timeout = 10_000)
    public void aReadInsideAScriptWaitsForTheConsole() throws Exception {
        RunConsole console = console();
        InputStream routed = ScriptInput.routed(new ByteArrayInputStream(new byte[0]), console);
        AtomicReference<String> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        inScript(() -> {
            got.set(new Scanner(routed).nextLine());
            done.countDown();
        });

        awaitAwaiting(console);
        assertTrue("something must be waiting", console.submitInput("typed by the user"));
        assertTrue("the reader never woke", done.await(5, TimeUnit.SECONDS));
        assertEquals("typed by the user", got.get());
        assertFalse("and the wait is over", console.isAwaitingInput());
    }

    /**
     * <b>The echo is marked as typed, so the view can draw it apart from output.</b>
     *
     * <p>It is in the transcript because a terminal echoes — without it the exchange reads as the script
     * having answered its own question — but it is the one line in a console that did not come from the
     * program. It carries no origin either: nothing in the script printed it.</p>
     */
    @Test(timeout = 10_000)
    public void anEchoedLineIsMarkedAsTypedAndHasNoOrigin() throws Exception {
        RunConsole console = console();
        InputStream routed = ScriptInput.routed(new ByteArrayInputStream(new byte[0]), console);
        CountDownLatch done = new CountDownLatch(1);

        inScript(() -> {
            new Scanner(routed).nextLine();
            done.countDown();
        });

        awaitAwaiting(console);
        console.submitInput("what I typed");
        assertTrue(done.await(5, TimeUnit.SECONDS));

        console.drain();
        RunConsole.Line echo = console.lineAt(0);
        assertEquals("what I typed", echo.text());
        assertTrue("the echo is indistinguishable from output", echo.isTyped());
        assertNull("nothing in the script printed it", echo.origin());
        assertFalse("it is output in the transcript, not a boundary", echo.isDivider());
    }

    /**
     * <b>The prompt is shown before the read blocks.</b>
     *
     * <p>{@code System.out.print("Name? ")} followed by a read is the canonical shape of asking a
     * question, and it has no newline — so the transcript was still holding it when the thread parked.
     * The input row then appeared under a console that had asked nothing, which reads as the script
     * having hung rather than as it waiting for you. Nothing was lost, which is why it survived: the
     * prompt did eventually appear, after the answer.</p>
     */
    @Test(timeout = 10_000)
    public void aPromptWithNoNewlineIsShownBeforeTheReadBlocks() throws Exception {
        RunConsole console = console();
        PrintStream out = new PrintStream(
                ScriptOutput.routed(new ByteArrayOutputStream(), RunLevel.OUT, console), true,
                StandardCharsets.UTF_8);
        InputStream routed = ScriptInput.routed(new ByteArrayInputStream(new byte[0]), console);
        CountDownLatch done = new CountDownLatch(1);

        inScript(() -> {
            out.print("Name? ");
            new Scanner(routed).nextLine();
            done.countDown();
        });

        awaitAwaiting(console);
        console.drain();
        assertEquals("the question was still in the buffer when the script stopped to hear the answer",
                1, console.lineCount());
        assertEquals("Name? ", console.lineAt(0).text());

        console.submitInput("answered");
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    /**
     * <b>One script's leftover line is not handed to the next.</b>
     *
     * <p>The buffer used to be a plain field on the stream rather than a per-thread one, so a reader that
     * consumed part of a line and stopped left the rest — and its newline — in the stream. The next
     * script's first {@code read()} then answered out of it, without the input row ever appearing.
     * That is a script silently receiving input meant for another, which is worse than a hang: a hang is
     * obvious and this looks like it worked.</p>
     */
    @Test(timeout = 15_000)
    public void aPartlyReadLineDoesNotLeakToAnotherThread() throws Exception {
        RunConsole console = console();
        InputStream routed = ScriptInput.routed(new ByteArrayInputStream(new byte[0]), console);

        // The first reader takes ONE byte of "abc" and leaves "bc\n" behind.
        AtomicReference<Integer> first = new AtomicReference<>();
        CountDownLatch tookOne = new CountDownLatch(1);
        inScript(() -> {
            try {
                first.set(routed.read());
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
            tookOne.countDown();
        });
        awaitAwaiting(console);
        console.submitInput("abc");
        assertTrue(tookOne.await(5, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf('a'), first.get());

        // A DIFFERENT THREAD, and it must ask for its own line rather than being given "bc".
        AtomicReference<String> second = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        inScript(() -> {
            second.set(new Scanner(routed).nextLine());
            done.countDown();
        });

        awaitAwaiting(console);
        assertTrue("the second reader took the first one's leftovers instead of asking",
                console.submitInput("its own line"));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("its own line", second.get());
    }

    /**
     * <b>A Scanner must not block after a complete line.</b>
     *
     * <p>{@code InputStream}'s default array read keeps calling {@code read()} until the buffer is full,
     * and a decoder's buffer is kilobytes — so an inherited implementation reads the line, then waits for
     * thousands more bytes that are never coming. The line is typed, Enter is pressed, and nothing
     * happens. This is that test, and it hangs rather than fails without the override.</p>
     */
    @Test(timeout = 10_000)
    public void aSecondLineIsAnIndependentRequest() throws Exception {
        RunConsole console = console();
        InputStream routed = ScriptInput.routed(new ByteArrayInputStream(new byte[0]), console);
        AtomicReference<String> one = new AtomicReference<>();
        AtomicReference<String> two = new AtomicReference<>();
        CountDownLatch gotFirst = new CountDownLatch(1);
        CountDownLatch gotSecond = new CountDownLatch(1);

        inScript(() -> {
            Scanner scanner = new Scanner(routed);
            one.set(scanner.nextLine());
            gotFirst.countDown();
            two.set(scanner.nextLine());
            gotSecond.countDown();
        });

        awaitAwaiting(console);
        assertTrue(console.submitInput("first"));
        // GATED ON THE FIRST LINE ACTUALLY ARRIVING, which is what proves the read returned rather than
        // the Scanner having blocked mid-buffer. Polling isAwaitingInput here instead reads TRUE from the
        // request that has not finished unwinding yet, so it proves nothing and passes either way.
        assertTrue("the first line never arrived", gotFirst.await(5, TimeUnit.SECONDS));

        awaitAwaiting(console);
        assertTrue(console.submitInput("second"));
        assertTrue("the second line never arrived", gotSecond.await(5, TimeUnit.SECONDS));

        assertEquals("first", one.get());
        assertEquals("second", two.get());
    }

    /**
     * <b>What was typed is echoed, attributed to the WAITING script.</b>
     *
     * <p>A terminal shows what you typed; without it the transcript reads as the script having answered
     * its own question. Attributed to the waiting script rather than to whatever is on screen, so a
     * per-script filter keeps the question and the answer together.</p>
     */
    @Test(timeout = 10_000)
    public void aSubmittedLineIsEchoedUnderTheWaitingScript() throws Exception {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = new RunConsole().attach(buffer);
        InputStream routed = ScriptInput.routed(new ByteArrayInputStream(new byte[0]), console);
        CountDownLatch done = new CountDownLatch(1);

        inScript(() -> {
            new Scanner(routed).nextLine();
            done.countDown();
        });

        awaitAwaiting(console);
        console.submitInput("hello");
        assertTrue(done.await(5, TimeUnit.SECONDS));

        console.drain();
        assertEquals(1, console.lineCount());
        assertEquals("hello", console.lineAt(0).text());
        assertEquals("Main.java", console.lineAt(0).script());
    }

    /** Nothing waiting means nothing to submit to — and it must say so rather than queue it. */
    @Test(timeout = 10_000)
    public void submittingWithNothingWaitingIsRefused() {
        RunConsole console = console();
        assertFalse(console.submitInput("nobody asked"));
        console.drain();
        assertEquals("and nothing was echoed", 0, console.lineCount());
    }

    /**
     * <b>A stop reaches a script blocked on input.</b>
     *
     * <p>The interrupt IS the kill switch, so a read that swallowed it would make waiting for input the
     * one state a script cannot be stopped from — the exact state a script is most likely to be stuck in.
     * The flag is restored so the injected safepoint still fires, and the read reports end of input.</p>
     */
    @Test(timeout = 10_000)
    public void interruptingAWaitingReadEndsItAndKeepsTheFlag() throws Exception {
        RunConsole console = console();
        InputStream routed = ScriptInput.routed(new ByteArrayInputStream(new byte[0]), console);
        AtomicReference<Integer> result = new AtomicReference<>();
        AtomicReference<Boolean> stillInterrupted = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread reader = inScript(() -> {
            try {
                result.set(routed.read());
            } catch (Exception failed) {
                result.set(-2);
            }
            stillInterrupted.set(Thread.currentThread().isInterrupted());
            done.countDown();
        });

        awaitAwaiting(console);
        reader.interrupt();

        assertTrue("the read never returned", done.await(5, TimeUnit.SECONDS));
        assertEquals("end of input", Integer.valueOf(-1), result.get());
        assertTrue("the kill flag must survive", stillInterrupted.get());
        assertFalse(console.isAwaitingInput());
    }

    /**
     * <b>A line offered to an abandoned request is not served to the next one.</b>
     *
     * <p>The queue holds one, so a submit that lands after its reader has gone would otherwise sit there
     * and be consumed by a later read — input typed for a run that is over, answering a question nobody
     * has asked yet.</p>
     */
    @Test(timeout = 10_000)
    public void aStaleLineIsNotServedToTheNextRequest() throws Exception {
        RunConsole console = console();
        InputStream routed = ScriptInput.routed(new ByteArrayInputStream(new byte[0]), console);

        Thread first = inScript(() -> {
            try {
                routed.read();
            } catch (Exception ignored) {
                // the point is that it ends, not how
            }
        });
        awaitAwaiting(console);
        first.interrupt();
        first.join(5_000);

        // Land a line with nothing waiting. It is refused, so it cannot be sitting in the queue.
        assertFalse(console.submitInput("stale"));

        AtomicReference<String> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        inScript(() -> {
            got.set(new Scanner(routed).nextLine());
            done.countDown();
        });
        awaitAwaiting(console);
        console.submitInput("fresh");

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("fresh", got.get());
    }
}
