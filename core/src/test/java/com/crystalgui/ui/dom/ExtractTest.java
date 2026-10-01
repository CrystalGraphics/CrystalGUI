package com.crystalgui.ui.dom;

import com.crystalgui.core.async.HostThread;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.render.UiFrame;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/** A node reads the game each frame: the read on the thread it names, the use on its document. */
public class ExtractTest {

    @After
    public void unbind() {
        HostThread.bind(HostThread.SERVER, null);
    }

    /** Frames the document and paints nothing: what a driver needs to run a frame with no GL. */
    private static DocumentDriver.Painter<Void> framesOnly(UIDocument document) {
        return new DocumentDriver.Painter<>() {
            @Override
            public void paint(float deltaSeconds, int width, int height) {
                document.frame(deltaSeconds, width, height);
            }

            @Override
            public UiFrame record(float deltaSeconds, int width, int height) {
                document.frame(deltaSeconds, width, height);
                return null;
            }
        };
    }

    @Test
    public void onTheFrameThreadItIsReadAndUsedInTheSameFrame() {
        UIDocument document = new UIDocument();
        AtomicInteger world = new AtomicInteger(7);
        List<Integer> used = new ArrayList<>();
        Connection extract = document.extract(world::get, used::add);

        document.frame(0.016f, 100f, 100f);
        assertEquals(List.of(7), used);
        document.frame(0.016f, 100f, 100f);
        assertEquals("an unchanged answer was used again", List.of(7), used);
        world.set(8);
        document.frame(0.016f, 100f, 100f);
        assertEquals(List.of(7, 8), used);

        extract.disconnect();
        world.set(9);
        document.frame(0.016f, 100f, 100f);
        assertEquals("a disconnected extract was read", List.of(7, 8), used);
    }

    @Test
    public void onAnotherThreadItIsReadThereAndUsedAtTheNextFrame() {
        HostThread.Queue server = new HostThread.Queue();
        HostThread.bind(HostThread.SERVER, server);
        UIDocument document = new UIDocument();
        AtomicInteger reads = new AtomicInteger();
        List<String> used = new ArrayList<>();
        document.extract(HostThread.SERVER, () -> "day " + reads.incrementAndGet(), used::add);

        document.frame(0.016f, 100f, 100f);
        document.frame(0.016f, 100f, 100f);
        assertEquals("read before its thread took the work", 0, reads.get());
        assertEquals(List.of(), used);

        drainOnItsOwnThread(server);
        assertEquals("one read in flight, not one a frame", 1, reads.get());
        document.frame(0.016f, 100f, 100f);
        assertEquals(List.of("day 1"), used);
    }

    /** A server thread is not the frame thread: drained here, the next frame would read in place. */
    private static void drainOnItsOwnThread(HostThread.Queue queue) {
        Thread thread = new Thread(queue::drain, "server");
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            throw new AssertionError(interrupted);
        }
    }

    @Test
    public void onASequenceTheReadIsTheHostsAndTheUseTheDocuments() {
        UIDocument document = new UIDocument();
        DocumentDriver<Void> driver = DocumentDriver.attach(document, DocumentDriver.Mode.LOCKSTEP, "extract");
        try {
            UiSequence sequence = driver.sequence();
            List<Boolean> readOnSequence = new ArrayList<>();
            List<Boolean> usedOnSequence = new ArrayList<>();
            driver.run(() -> document.extract(
                    () -> {
                        readOnSequence.add(UiSequence.current() == sequence);
                        return "state";
                    },
                    value -> usedOnSequence.add(sequence.isCurrent())));

            driver.frame(0.016f, 100, 100, framesOnly(document));

            assertEquals(List.of(false), readOnSequence);
            assertEquals(List.of(true), usedOnSequence);
        } finally {
            driver.close();
        }
    }
}
