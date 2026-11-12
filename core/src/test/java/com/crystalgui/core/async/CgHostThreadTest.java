package com.crystalgui.core.async;

import com.crystalgraphics.job.CgHostThread;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgClipboard;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** UI code reaches a game thread: the work runs there, and the answer comes back where it was asked. */
public class CgHostThreadTest {

    private final UiSequence sequence = UiSequence.create("ui");

    @After
    public void unbind() {
        FakeGameThreads.bind(CgHostThread.SERVER, null);
        sequence.close();
    }

    @Test
    public void aCallFromASequenceIsAnsweredOnIt() throws Exception {
        CgHostThread.Queue server = new CgHostThread.Queue();
        FakeGameThreads.bind(CgHostThread.SERVER, server);
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicReference<UiSequence> answeredOn = new AtomicReference<>();
        CountDownLatch answered = new CountDownLatch(1);

        CountDownLatch asked = new CountDownLatch(1);
        sequence.execute(() -> {
            CgHostThread.SERVER.call(() -> {
                ranOn.set(Thread.currentThread());
                return 42;
            }).then(value -> {
                answeredOn.set(UiSequence.current());
                answered.countDown();
            });
            asked.countDown();
        });
        assertTrue(asked.await(10, TimeUnit.SECONDS));

        server.drain();
        assertSame("the work ran somewhere other than its thread", Thread.currentThread(), ranOn.get());
        assertTrue("the answer never came back", answered.await(10, TimeUnit.SECONDS));
        assertSame(sequence, answeredOn.get());
    }

    /** Asked from the client thread with no document framing, the answer still comes back: to the client thread. */
    @Test
    public void aCallFromAGameThreadIsAnsweredOnIt() throws Exception {
        CgHostThread.Queue client = new CgHostThread.Queue();
        CgHostThread.Queue server = new CgHostThread.Queue();
        FakeGameThreads.bind(CgHostThread.CLIENT, client);
        FakeGameThreads.bind(CgHostThread.SERVER, server);
        try {
            AtomicReference<Thread> answeredOn = new AtomicReference<>();
            Thread clientThread = new Thread(() -> {
                client.drain();   // this thread is now the client's
                CgHostThread.SERVER.call(() -> 42).then(value -> answeredOn.set(Thread.currentThread()));
            }, "client");
            clientThread.start();
            clientThread.join();

            Thread serverThread = new Thread(server::drain, "server");
            serverThread.start();
            serverThread.join();

            Thread clientAgain = new Thread(client::drain, "client-again");
            clientAgain.start();
            clientAgain.join();
            assertSame("the answer went somewhere other than the client thread", clientAgain, answeredOn.get());
        } finally {
            FakeGameThreads.bind(CgHostThread.CLIENT, null);
        }
    }

    @Test
    public void onItsOwnThreadACallIsAnsweredBeforeItReturns() {
        CgHostThread.Queue server = new CgHostThread.Queue();
        FakeGameThreads.bind(CgHostThread.SERVER, server);
        server.drain();   // this thread is now the server's

        CgReply<Integer> reply = CgHostThread.SERVER.call(() -> 7);
        assertTrue(reply.isDone());
        assertEquals(Integer.valueOf(7), reply.result());
    }

    @Test
    public void withNoServerACallFailsAndNothingRuns() {
        assertFalse(CgHostThread.SERVER.isAvailable());
        boolean[] ran = {false};
        CgReply<Integer> reply = CgHostThread.SERVER.call(() -> {
            ran[0] = true;
            return 1;
        });
        assertTrue(reply.isDone());
        assertNotNull(reply.error());
        assertTrue(reply.error().is(CgHostThread.UNAVAILABLE));
        CgHostThread.SERVER.run(() -> ran[0] = true);
        assertFalse(ran[0]);
    }

    /** A copy and a paste from a document's sequence reach the clipboard on the client thread; the paste answers back. */
    @Test
    public void aDocumentsClipboardIsTheClientThreads() throws Exception {
        CgHostThread.Queue client = new CgHostThread.Queue();
        FakeGameThreads.bind(CgHostThread.CLIENT, client);
        AtomicReference<String> held = new AtomicReference<>("");
        AtomicReference<Thread> touchedOn = new AtomicReference<>();
        CgPlatform.provide(CgClipboard.HOST, new CgClipboard.Host() {
            @Override
            public String get() {
                touchedOn.set(Thread.currentThread());
                return held.get();
            }

            @Override
            public void set(String text) {
                touchedOn.set(Thread.currentThread());
                held.set(text);
            }
        });
        try {
            Thread clientThread = new Thread(client::drain, "client");
            clientThread.start();
            clientThread.join();   // the client thread is now named

            CountDownLatch asked = new CountDownLatch(1);
            AtomicReference<String> pasted = new AtomicReference<>();
            AtomicReference<UiSequence> pastedOn = new AtomicReference<>();
            CountDownLatch answered = new CountDownLatch(1);
            sequence.execute(() -> {
                CgHostThread.CLIENT.run(() -> CgClipboard.set("copied"));
                CgHostThread.CLIENT.call(CgClipboard::get).then(text -> {
                    pasted.set(text);
                    pastedOn.set(UiSequence.current());
                    answered.countDown();
                });
                asked.countDown();
            });
            assertTrue(asked.await(10, TimeUnit.SECONDS));
            assertEquals("the clipboard was touched before the client thread ran", "", held.get());

            Thread clientAgain = new Thread(client::drain, "client-again");
            clientAgain.start();
            clientAgain.join();
            assertSame("the clipboard was touched off the client thread", clientAgain, touchedOn.get());
            assertTrue("the paste never answered", answered.await(10, TimeUnit.SECONDS));
            assertEquals("copied", pasted.get());
            assertSame(sequence, pastedOn.get());
        } finally {
            CgPlatform.provide(CgClipboard.HOST, CgClipboard.Host.NONE);
            FakeGameThreads.bind(CgHostThread.CLIENT, null);
        }
    }
}
