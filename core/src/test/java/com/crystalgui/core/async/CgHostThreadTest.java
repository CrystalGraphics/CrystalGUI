package com.crystalgui.core.async;

import com.crystalgraphics.job.CgHostThread;
import com.crystalgraphics.job.reply.CgReply;
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
}
