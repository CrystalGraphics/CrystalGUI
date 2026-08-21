package com.crystalgui.headless;

import com.crystalgui.net.wire.FrameMultiplexer;
import com.crystalgui.net.wire.WireTransport;
import com.crystalgui.serialization.CodecException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The wire engine, headless — no Minecraft, no GL, which is the whole point of putting it in {@code core}.
 *
 * <p>Two connections wired to each other's {@code onFrameReceived} stand in for a network. That is not a
 * simplification of the real thing: the platform's contribution is exactly "hand this {@code byte[]} to
 * the other side", so a direct hand-off exercises everything above that seam.</p>
 */
public class FrameMultiplexerTest {

    /** ~32 KB is the real client→server ceiling on every Minecraft version we target. */
    private static final int MC_CLIENT_FRAME = 32_766;

    /** A pair of connections, each sending into the other's inbox. */
    private static final class Pair {
        final FrameMultiplexer a;
        final FrameMultiplexer b;
        final List<byte[]> aReceived = new ArrayList<>();
        final List<byte[]> bReceived = new ArrayList<>();
        int aFrames;
        int bFrames;

        Pair(int frameBytes) {
            FrameMultiplexer[] slot = new FrameMultiplexer[2];
            slot[0] = new FrameMultiplexer(frameBytes, true, frame -> {
                aFrames++;
                slot[1].onFrameReceived(frame);
            });
            slot[1] = new FrameMultiplexer(frameBytes, false, frame -> {
                bFrames++;
                slot[0].onFrameReceived(frame);
            });
            a = slot[0];
            b = slot[1];
            a.setMessageHandler(aReceived::add);
            b.setMessageHandler(bReceived::add);
        }

        /** Pumps both until neither has anything left — a settled exchange. */
        void settle() {
            for (int i = 0; i < 2_000; i++) {
                int moved = a.pump() + b.pump();
                if (moved == 0 && a.pendingOutboundMessages() == 0 && b.pendingOutboundMessages() == 0) {
                    // One more each, so a trailing WINDOW_UPDATE is delivered.
                    a.pump();
                    b.pump();
                    return;
                }
            }
            fail("the exchange never settled");
        }
    }

    private static byte[] bytes(int length, int seed) {
        byte[] value = new byte[length];
        for (int i = 0; i < length; i++) value[i] = (byte) (i * 31 + seed);
        return value;
    }

    @Test
    public void aSmallMessageCrossesInOneFrame() {
        Pair pair = new Pair(MC_CLIENT_FRAME);
        pair.a.send(bytes(100, 1));
        pair.settle();

        assertEquals(1, pair.bReceived.size());
        assertArrayEquals(bytes(100, 1), pair.bReceived.get(0));
        assertEquals("one DATA frame and nothing else", 1, pair.aFrames);
    }

    @Test
    public void aMessageLargerThanTheFrameCeilingIsFragmentedAndReassembled() {
        Pair pair = new Pair(MC_CLIENT_FRAME);
        byte[] big = bytes(200_000, 7);
        pair.a.send(big);
        pair.settle();

        assertEquals(1, pair.bReceived.size());
        assertArrayEquals(big, pair.bReceived.get(0));
        assertTrue("must have taken several frames, took " + pair.aFrames, pair.aFrames >= 7);
    }

    @Test
    public void noFrameExceedsThePlatformCeiling() {
        // The failure this prevents is not ours: an oversized payload throws from inside the loader,
        // mid-send, with the connection already committed.
        List<Integer> sizes = new ArrayList<>();
        FrameMultiplexer[] slot = new FrameMultiplexer[1];
        slot[0] = new FrameMultiplexer(MC_CLIENT_FRAME, true, frame -> sizes.add(frame.length));
        slot[0].send(bytes(500_000, 3));
        slot[0].flush();

        assertTrue("emitted nothing", !sizes.isEmpty());
        for (int size : sizes) {
            assertTrue("frame of " + size + " exceeds " + MC_CLIENT_FRAME, size <= MC_CLIENT_FRAME);
        }
    }

    /**
     * The reason stream multiplexing is in here at all.
     *
     * <p>A large transfer and a small one queued behind it must both make progress. If the small one only
     * completes after the large one, opening a big file freezes every RPC and UI event behind it — which
     * reads as the editor hanging, not as a transport decision.</p>
     */
    @Test
    public void aSmallMessageIsNotBlockedBehindALargeOne() {
        Pair pair = new Pair(4096);
        pair.a.send(bytes(400_000, 1));   // many frames
        pair.a.send(bytes(10, 2));        // one frame, queued behind it

        // Deliberately only a few pumps: far too few to finish the large message.
        for (int i = 0; i < 4; i++) {
            pair.a.pump();
            pair.b.pump();
        }

        assertEquals("the small message should already be through", 1, pair.bReceived.size());
        assertArrayEquals(bytes(10, 2), pair.bReceived.get(0));
    }

    @Test
    public void manyMessagesAllArriveIntactAndInterleaved() {
        Pair pair = new Pair(1024);
        for (int i = 0; i < 20; i++) pair.a.send(bytes(3000 + i, i));
        pair.settle();

        assertEquals(20, pair.bReceived.size());
        // Every message intact, regardless of the order they completed in.
        List<byte[]> got = pair.bReceived;
        for (int i = 0; i < 20; i++) {
            byte[] expected = bytes(3000 + i, i);
            boolean found = false;
            for (byte[] candidate : got) {
                if (Arrays.equals(expected, candidate)) { found = true; break; }
            }
            assertTrue("message " + i + " did not arrive intact", found);
        }
    }

    @Test
    public void bothDirectionsWorkAtOnce() {
        Pair pair = new Pair(2048);
        pair.a.send(bytes(50_000, 1));
        pair.b.send(bytes(50_000, 2));
        pair.settle();

        assertEquals(1, pair.bReceived.size());
        assertEquals(1, pair.aReceived.size());
        assertArrayEquals(bytes(50_000, 1), pair.bReceived.get(0));
        assertArrayEquals(bytes(50_000, 2), pair.aReceived.get(0));
    }

    /**
     * Stream ids must never collide, which is why one side is odd and the other even.
     *
     * <p>A collision would splice two unrelated messages into one buffer — and the result would still
     * decode as *something*, which is the worst available failure.</p>
     */
    @Test
    public void theTwoSidesNeverAllocateTheSameStreamId() {
        Pair pair = new Pair(64_000);
        for (int i = 0; i < 50; i++) {
            pair.a.send(bytes(8, i));
            pair.b.send(bytes(8, i));
        }
        pair.settle();
        assertEquals(50, pair.aReceived.size());
        assertEquals(50, pair.bReceived.size());
    }

    // ── Flow control ────────────────────────────────────────────────────────

    /**
     * The measure that protects Minecraft's unbounded {@code outboundPacketsQueue}.
     *
     * <p>A sender with no credit must stop, not keep encoding into a queue nobody is draining. Without
     * this a large transfer balloons heap and head-of-line blocks the whole game connection.</p>
     */
    @Test
    public void aSenderStopsWhenItRunsOutOfCredit() {
        AtomicInteger emitted = new AtomicInteger();
        // A sink that goes nowhere: the peer never pumps, so it never grants more credit.
        FrameMultiplexer sender = new FrameMultiplexer(4096, true, frame -> emitted.incrementAndGet());

        sender.send(bytes(4 * 1024 * 1024, 9));   // 4 MB, far past the initial window
        for (int i = 0; i < 100; i++) sender.flush();

        long sentBytes = (long) emitted.get() * 4096;
        assertTrue("sent " + sentBytes + " bytes with no acknowledgement — the window did not hold",
                sentBytes <= FrameMultiplexer.DEFAULT_WINDOW_BYTES + 4096);
        assertEquals("credit should be spent", 0, Math.max(0, sender.sendCredit()));
        assertTrue("the rest must still be queued", sender.pendingOutboundMessages() > 0);
    }

    @Test
    public void creditIsReplenishedSoALargeTransferStillCompletes() {
        Pair pair = new Pair(MC_CLIENT_FRAME);
        byte[] huge = bytes(3 * 1024 * 1024, 4);   // well past several windows
        pair.a.send(huge);
        pair.settle();

        assertEquals(1, pair.bReceived.size());
        assertArrayEquals(huge, pair.bReceived.get(0));
    }

    // ── Refusals ────────────────────────────────────────────────────────────

    @Test(expected = CodecException.class)
    public void anUnknownOpcodeIsRefused() {
        FrameMultiplexer connection = new FrameMultiplexer(4096, true, frame -> { });
        connection.onFrameReceived(new byte[] {0x7F, 0x00, 0x01});
        connection.pump();
    }

    @Test(expected = CodecException.class)
    public void aTruncatedFrameIsRefused() {
        FrameMultiplexer connection = new FrameMultiplexer(4096, true, frame -> { });
        connection.onFrameReceived(new byte[] {0x01});
        connection.pump();
    }

    @Test
    public void reassemblyIsBoundedRatherThanUnlimited() {
        // A peer that opens a stream and never finishes it costs the sender nothing; the receiver must
        // not pay for that without limit.
        FrameMultiplexer victim = new FrameMultiplexer(64_000, false, frame -> { });
        FrameMultiplexer attacker = new FrameMultiplexer(64_000, true, victim::onFrameReceived);

        try {
            for (int i = 0; i < 400; i++) {
                attacker.send(bytes(60_000, i));
                attacker.flush();
                // Grant unlimited credit so the attacker is never the thing that stops.
                victim.onFrameReceived(windowUpdateOf(1_000_000));
                victim.pump();
            }
        } catch (CodecException refused) {
            assertTrue(refused.getMessage().contains("reassembly"));
            return;
        }
        // Not reaching the cap is fine too, so long as we never held more than it allows.
        assertTrue("held " + victim.reassemblyBytes(),
                victim.reassemblyBytes() <= FrameMultiplexer.MAX_REASSEMBLY_BYTES);
    }

    private static byte[] windowUpdateOf(int credit) {
        // OP_WINDOW_UPDATE, no flags, stream 0, varint credit.
        List<Byte> out = new ArrayList<>(Arrays.asList((byte) 0x02, (byte) 0x00, (byte) 0x00));
        int remaining = credit;
        while ((remaining & ~0x7F) != 0) {
            out.add((byte) ((remaining & 0x7F) | 0x80));
            remaining >>>= 7;
        }
        out.add((byte) remaining);
        byte[] frame = new byte[out.size()];
        for (int i = 0; i < frame.length; i++) frame[i] = out.get(i);
        return frame;
    }

    // ── Threading ───────────────────────────────────────────────────────────

    /**
     * {@code onFrameReceived} is called from the network thread and must only enqueue.
     *
     * <p>Delivering from there would touch the tree off its own thread — which {@code UITransport}
     * describes as <i>"not a race to be tuned but a correctness bug"</i>.</p>
     */
    @Test
    public void deliveryHappensOnThePumpingThreadNotTheReceivingOne() throws Exception {
        FrameMultiplexer connection = new FrameMultiplexer(4096, false, frame -> { });
        List<String> deliveredOn = new ArrayList<>();
        connection.setMessageHandler(message -> deliveredOn.add(Thread.currentThread().getName()));

        FrameMultiplexer sender = new FrameMultiplexer(4096, true, connection::onFrameReceived);
        CountDownLatch handed = new CountDownLatch(1);
        Thread network = new Thread(() -> {
            sender.send(bytes(10, 1));
            sender.flush();
            handed.countDown();
        }, "fake-network-thread");
        network.start();

        assertTrue(handed.await(5, TimeUnit.SECONDS));
        network.join();
        assertTrue("nothing may be delivered before a pump", deliveredOn.isEmpty());

        connection.pump();
        assertEquals(1, deliveredOn.size());
        assertNotEquals("fake-network-thread", deliveredOn.get(0));
    }

    // ── The session-facing seam ─────────────────────────────────────────────

    /**
     * The whole point of the exercise: the same {@code UITransport<Object>} a session already takes,
     * carrying the same {@code PlainOps} trees, now over frames.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aPlainOpsTreeRoundTripsThroughTheRealTransport() {
        FrameMultiplexer[] slot = new FrameMultiplexer[2];
        slot[0] = new FrameMultiplexer(MC_CLIENT_FRAME, true, frame -> slot[1].onFrameReceived(frame));
        slot[1] = new FrameMultiplexer(MC_CLIENT_FRAME, false, frame -> slot[0].onFrameReceived(frame));

        WireTransport client = new WireTransport(slot[0]);
        WireTransport server = new WireTransport(slot[1]);

        List<Object> got = new ArrayList<>();
        server.setReceiver(got::add);

        Map<Object, Object> packet = new LinkedHashMap<>();
        packet.put("type", "OpenWindow");
        packet.put("windowId", 3);
        packet.put("elements", Arrays.asList("button", "text"));
        packet.put("blob", bytes(80_000, 5));   // forces fragmentation

        client.send(packet);
        for (int i = 0; i < 500 && got.isEmpty(); i++) {
            client.pump();
            server.pump();
        }

        assertEquals(1, got.size());
        Map<Object, Object> received = (Map<Object, Object>) got.get(0);
        assertEquals("OpenWindow", received.get("type"));
        assertEquals(3, received.get("windowId"));
        assertEquals(Arrays.asList("button", "text"), received.get("elements"));
        assertArrayEquals(bytes(80_000, 5), (byte[]) received.get("blob"));
    }
}
