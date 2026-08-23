package com.crystalgui.headless;

import com.crystalgui.net.wire.FrameMultiplexer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 5 <b>5.9</b> — many messages in flight at once, which had never been run.
 *
 * <h3>What was found</h3>
 *
 * <p>{@code flush} round-robins across every queued message, so <b>all of them fragment
 * simultaneously</b> and the receiver must buffer all of them at once: reassembly demand is the
 * <em>sum</em> of what is in flight, not the largest of it. Against
 * {@link FrameMultiplexer#MAX_REASSEMBLY_BYTES} that is not a large-file problem at all —
 * <b>forty 512 KB messages, none of them large, delivered zero</b> and the connection threw at the cap.
 * The plan expected several large transfers together to be the risk; the real one is many ordinary
 * ones, which is far likelier and reads as the connection dying under load.</p>
 *
 * <p>Fixed with byte-denominated admission — HTTP/2's {@code SETTINGS_MAX_CONCURRENT_STREAMS}, counted in
 * bytes rather than streams, because bytes is what the receiver's bound is denominated in and a count
 * cannot tell forty 512 KB messages from forty 5 MB ones.</p>
 *
 * <h3>What these tests pin</h3>
 *
 * <p>That the aggregate cases <b>complete</b> rather than merely not throwing, that a single message
 * over the cap is still <b>refused</b> (the bound is the documented one and must not have been widened
 * by accident), and that a one-frame message is <b>never gated</b> — the last being what keeps every
 * ordinary UI packet on this wire unaffected.</p>
 */
public class ConcurrentTransferAdmissionTest {

    /** The 1.7.10 client→server ceiling, which is the tightest of the four real ones. */
    private static final int MC_CLIENT_FRAME = 32_766;

    private static final class Pair {
        final FrameMultiplexer a;
        final FrameMultiplexer b;
        final List<byte[]> received = new ArrayList<>();
        int peakReassembly;

        Pair() {
            FrameMultiplexer[] slot = new FrameMultiplexer[2];
            slot[0] = new FrameMultiplexer(MC_CLIENT_FRAME, true, frame -> slot[1].onFrameReceived(frame));
            slot[1] = new FrameMultiplexer(MC_CLIENT_FRAME, false, frame -> slot[0].onFrameReceived(frame));
            a = slot[0];
            b = slot[1];
            b.setMessageHandler(received::add);
        }

        /** Pumps until the sender has nothing left, recording the receiver's high-water mark. */
        void settle() {
            for (int i = 0; i < 20_000; i++) {
                int moved = a.pump() + b.pump();
                peakReassembly = Math.max(peakReassembly, b.reassemblyBytes());
                if (moved == 0 && a.pendingOutboundMessages() == 0) {
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

    private Pair sendAll(int count, int sizeBytes) {
        Pair pair = new Pair();
        for (int i = 0; i < count; i++) pair.a.send(bytes(sizeBytes, i));
        pair.settle();
        return pair;
    }

    // ── The regression ──────────────────────────────────────────────────────────────────────────

    /**
     * Forty half-megabyte messages, which is a workspace listing rather than an attack.
     *
     * <p>Delivered <b>zero</b> before admission: 20 MB fragmenting at once against an 8 MB cap. This is
     * the case the whole change exists for, and the one most likely to be hit in production.</p>
     */
    @Test
    public void fortyOrdinaryMessagesAllArrive() {
        Pair pair = sendAll(40, 512 * 1024);

        assertEquals("every message must arrive", 40, pair.received.size());
        assertTrue("and the receiver must never have exceeded its own bound: " + pair.peakReassembly,
                pair.peakReassembly <= FrameMultiplexer.MAX_REASSEMBLY_BYTES);
        assertEquals("content must survive the interleaving",
                512 * 1024, pair.received.get(0).length);
    }

    /** Eight 2 MB messages — 16 MB in total, twice the cap, and all of it arrives. */
    @Test
    public void aggregateFarOverTheCapStillCompletes() {
        Pair pair = sendAll(8, 2 * 1024 * 1024);

        assertEquals(8, pair.received.size());
        assertTrue("peak " + pair.peakReassembly,
                pair.peakReassembly <= FrameMultiplexer.MAX_REASSEMBLY_BYTES);
    }

    /** Three 4 MB messages, each individually under the cap and jointly well over it. */
    @Test
    public void severalLargeTransfersTogetherComplete() {
        Pair pair = sendAll(3, 4 * 1024 * 1024);

        assertEquals(3, pair.received.size());
        assertTrue("peak " + pair.peakReassembly,
                pair.peakReassembly <= FrameMultiplexer.MAX_REASSEMBLY_BYTES);
    }

    /** One message under the cap is unaffected — admission must not have introduced a stall. */
    @Test
    public void oneLargeMessageUnderTheCapIsUnaffected() {
        Pair pair = sendAll(1, 7 * 1024 * 1024);
        assertEquals(1, pair.received.size());
    }

    /**
     * A single message over the cap is still refused.
     *
     * <p>The bound is real and admission must not have widened it by accident. The first message is
     * always admitted however large — otherwise one bigger than the budget would never be sent at all —
     * so it goes, and the receiver refuses it at the documented limit rather than stalling silently,
     * which is a far worse failure to diagnose.</p>
     *
     * <p><b>Asserted on the counter rather than on a throw.</b> It used to be the throw, because the
     * refusal escaped {@code pump} — which was itself the defect: it took the rest of the tick with it.
     * A refusal is now a stream error the connection absorbs, so what is observable is that it happened
     * and that nothing arrived. @see StreamErrorIsolationTest</p>
     */
    @Test
    public void oneMessageOverTheCapIsStillRefused() {
        Pair pair = new Pair();
        pair.a.send(bytes(9 * 1024 * 1024, 1));
        pair.settle();

        assertEquals("exactly one stream refused", 1, pair.b.refusedStreams());
        assertTrue("the reason must name the bound: " + pair.b.lastRefusal(),
                pair.b.lastRefusal().contains(String.valueOf(FrameMultiplexer.MAX_REASSEMBLY_BYTES)));
        assertEquals("and nothing must have been delivered from it", 0, pair.received.size());
    }

    /**
     * A message that fits in one frame is never gated.
     *
     * <p>Which is what keeps every UI packet, event and RPC on this wire completely unaffected: the
     * receiver delivers a single-frame message straight out of the frame without touching a reassembly
     * buffer, so there is nothing to ration. Asserted on {@code fragmentingBytes} rather than on
     * throughput, because throughput would pass whether or not the exemption exists.</p>
     */
    @Test
    public void aSingleFrameMessageIsNeverGated() {
        Pair pair = new Pair();
        for (int i = 0; i < 500; i++) pair.a.send(bytes(1_000, i));
        pair.settle();

        assertEquals("all five hundred", 500, pair.received.size());
        assertEquals("and not one of them was ever counted against the budget",
                0L, pair.a.fragmentingBytes());
    }

    /**
     * The budget is given back as messages finish.
     *
     * <p>A leak here would be invisible at first and then permanent: the sender would admit less and
     * less until it admitted nothing, and the connection would go quiet with no error anywhere.</p>
     */
    @Test
    public void theBudgetIsReleasedWhenAMessageCompletes() {
        Pair pair = sendAll(6, 1024 * 1024);

        assertEquals(6, pair.received.size());
        assertEquals("everything finished, so nothing may still be counted as in flight",
                0L, pair.a.fragmentingBytes());
    }
}
