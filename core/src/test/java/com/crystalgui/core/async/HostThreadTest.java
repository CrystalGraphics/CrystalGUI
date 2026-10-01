package com.crystalgui.core.async;

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
public class HostThreadTest {

    private final UiSequence sequence = UiSequence.create("ui");

    @After
    public void unbind() {
        HostThread.bind(HostThread.SERVER, null);
        sequence.close();
    }

    @Test
    public void aCallFromASequenceIsAnsweredOnIt() throws Exception {
        HostThread.Queue server = new HostThread.Queue();
        HostThread.bind(HostThread.SERVER, server);
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicReference<UiSequence> answeredOn = new AtomicReference<>();
        CountDownLatch answered = new CountDownLatch(1);

        CountDownLatch asked = new CountDownLatch(1);
        sequence.execute(() -> {
            HostThread.SERVER.call(() -> {
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
        HostThread.Queue client = new HostThread.Queue();
        HostThread.Queue server = new HostThread.Queue();
        HostThread.bind(HostThread.CLIENT, client);
        HostThread.bind(HostThread.SERVER, server);
        try {
            AtomicReference<Thread> answeredOn = new AtomicReference<>();
            Thread clientThread = new Thread(() -> {
                client.drain();   // this thread is now the client's
                HostThread.SERVER.call(() -> 42).then(value -> answeredOn.set(Thread.currentThread()));
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
            HostThread.bind(HostThread.CLIENT, null);
        }
    }

    @Test
    public void onItsOwnThreadACallIsAnsweredBeforeItReturns() {
        HostThread.Queue server = new HostThread.Queue();
        HostThread.bind(HostThread.SERVER, server);
        server.drain();   // this thread is now the server's

        Reply<Integer> reply = HostThread.SERVER.call(() -> 7);
        assertTrue(reply.isDone());
        assertEquals(Integer.valueOf(7), reply.result());
    }

    @Test
    public void withNoServerACallFailsAndNothingRuns() {
        assertFalse(HostThread.SERVER.isAvailable());
        boolean[] ran = {false};
        Reply<Integer> reply = HostThread.SERVER.call(() -> {
            ran[0] = true;
            return 1;
        });
        assertTrue(reply.isDone());
        assertNotNull(reply.error());
        assertTrue(reply.error().is(HostThread.UNAVAILABLE));
        HostThread.SERVER.run(() -> ran[0] = true);
        assertFalse(ran[0]);
    }
}
