package com.crystalgui.net.wire;

import com.crystalgui.serialization.CodecException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Many logical messages over one small-framed, ordered pipe.
 *
 * <p>The pipe is a Minecraft custom-payload channel, and it imposes two things this exists to absorb:
 * a frame ceiling that is <b>~32 KB client→server on every version</b> (a signed short in the vanilla
 * packet format, unchanged from 1.7.10 to 1.20.x), and an outbound queue —
 * {@code NetworkManager.outboundPacketsQueue} — that is an <b>unbounded</b>
 * {@code ConcurrentLinkedQueue} shared with the entire game.</p>
 *
 * <h3>Three mechanisms, none of them invented</h3>
 *
 * <ul>
 *   <li><b>Stream multiplexing</b>, from HTTP/2. Every message gets a stream id and frames from
 *       different streams interleave, so a five-megabyte file read cannot sit in front of a
 *       two-hundred-byte RPC. Without it, opening a large file visibly freezes the editor's own
 *       protocol behind it.</li>
 *   <li><b>FIN fragmentation</b>, from WebSocket. A message is N frames on one stream, the last flagged.
 *       See {@link FrameCodec} for why there is no sequence number.</li>
 *   <li><b>Credit flow control</b>, from HTTP/2's {@code WINDOW_UPDATE}. The receiver advertises a byte
 *       budget; the sender spends it and stops when it is gone.</li>
 * </ul>
 *
 * <p><b>Flow control is the one that is not optional here.</b> RFC 9113 gives the general reason —
 * <i>"a flow-control scheme ensures that streams on the same connection do not destructively interfere
 * with each other"</i> — and our case is worse than the one it describes: these streams contend not only
 * with each other but with chat, movement and chunk loading, on the same TCP connection. A sender that
 * pushes a large file as fast as it can encode does not degrade this feature, it degrades the game.</p>
 *
 * <h3>Threading — one concurrent queue and nothing else</h3>
 *
 * <p>{@link #onFrameReceived} is called from the network thread and does exactly one thing: enqueue.
 * Everything else — reassembly, delivery, credit accounting, scheduling — happens in {@link #pump} on the
 * thread that owns the tree. So credit is single-threaded state despite being replenished by the peer,
 * because a {@code WINDOW_UPDATE} is only ever <em>processed</em> during a pump.</p>
 *
 * <p>That is the contract {@code UITransport} already states: <i>"It is a mailbox, not a dispatcher."</i>
 * {@code Property} and {@code SignalBase} are single-threaded by documented contract, so delivering a
 * message from the network thread would not be a race to tune but a correctness bug.</p>
 */
public final class FrameMultiplexer {

    /** Where an encoded frame goes. Implemented by the platform adapter; ~one method of loader code. */
    public interface FrameSink {
        void send(byte[] frame);
    }

    /**
     * Bytes a peer may have in flight before it must wait for the receiver to catch up.
     *
     * <p>256 KB is eight client→server frames, which is enough to keep the pipe busy without letting one
     * transfer own the connection. HTTP/2 starts streams at 65,535 and treats the number as tunable
     * rather than derived; so is this. It wants measuring against a real server under load, and is
     * deliberately a constant in one place until then.</p>
     */
    public static final int DEFAULT_WINDOW_BYTES = 256 * 1024;

    /**
     * A ceiling on reassembly, across every open stream.
     *
     * <p>The receive window bounds what a well-behaved peer sends, and this bounds what a broken or
     * hostile one can pin: opening streams and never finishing them costs the sender nothing and would
     * otherwise cost the receiver memory without limit.</p>
     */
    public static final int MAX_REASSEMBLY_BYTES = 8 * 1024 * 1024;

    private final int maxPayloadBytes;
    private final FrameSink sink;

    /** The only field two threads touch. Network thread appends; {@link #pump} drains. */
    private final Queue<byte[]> arrived = new ConcurrentLinkedQueue<>();

    private final Map<Integer, ByteArrayOutputStream> reassembling = new HashMap<>();
    private int reassemblyBytes;

    /** Messages waiting to go out, each already assigned a stream. Round-robined by {@link #flush}. */
    private final Deque<Outbound> outbound = new ArrayDeque<>();

    private Consumer<byte[]> messageHandler = message -> { };

    /** Odd for the side that connects, even for the side that accepts — HTTP/2's trick for never colliding. */
    private int nextStreamId;

    /** What the peer has granted us. Spent by {@link #flush}, replenished by their WINDOW_UPDATE. */
    private long sendCredit = DEFAULT_WINDOW_BYTES;

    /** What we have granted the peer and not yet re-granted. */
    private int unacknowledgedBytes;

    private boolean closed;

    /**
     * @param maxFrameBytes the platform's own per-frame ceiling — <b>asked for, never assumed</b>. It
     *                      differs by direction and version (~32 KB client→server on both, 2 MB
     *                      server→client on 1.7.10, 1 MB on 1.20.1), and a constant here would be wrong
     *                      on three of those four.
     * @param initiator     true on the side that opened the connection; decides odd or even stream ids
     */
    public FrameMultiplexer(int maxFrameBytes, boolean initiator, FrameSink sink) {
        if (maxFrameBytes <= 64) throw new IllegalArgumentException("maxFrameBytes too small");
        // Sized against the widest header a stream id can produce, so a payload can never push a frame
        // one byte over the platform's limit -- which would throw from inside the loader, mid-send.
        this.maxPayloadBytes = maxFrameBytes - FrameCodec.headerSize(Integer.MAX_VALUE);
        this.sink = sink;
        this.nextStreamId = initiator ? 1 : 2;
    }

    /** Installs the sink for whole, reassembled messages. Called during {@link #pump}, on its thread. */
    public void setMessageHandler(Consumer<byte[]> handler) {
        this.messageHandler = handler == null ? message -> { } : handler;
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    /**
     * Queues one whole message. Fragmented and paced by {@link #flush}; nothing goes out here.
     *
     * <p>Deferring the write is what makes flow control possible at all — a {@code send} that emitted
     * immediately would have nowhere to put the bytes it is not yet allowed to send.</p>
     */
    public void send(byte[] message) {
        if (closed) throw new IllegalStateException("connection is closed");
        outbound.addLast(new Outbound(allocateStreamId(), message));
    }

    private int allocateStreamId() {
        int id = nextStreamId;
        // Skips 0, which is the connection itself. Wraps rather than overflowing into negatives: ids are
        // only ever live for the length of one message, so reuse after two billion is not a collision.
        nextStreamId += 2;
        if (nextStreamId < 0) nextStreamId = (id & 1) == 1 ? 1 : 2;
        return id;
    }

    /**
     * Emits as much as credit allows, round-robin across streams.
     *
     * <p>Round-robin rather than first-in-first-out is the entire anti-head-of-line-blocking measure: a
     * queued 5 MB message and a queued 200-byte one make progress together, and the small one finishes
     * almost immediately instead of after the large one.</p>
     */
    public void flush() {
        int spins = outbound.size();
        while (spins-- > 0 && sendCredit > 0 && !outbound.isEmpty()) {
            Outbound message = outbound.pollFirst();
            int remaining = message.remaining();
            int chunk = (int) Math.min(Math.min(remaining, maxPayloadBytes), sendCredit);
            boolean fin = chunk == remaining;

            sink.send(FrameCodec.data(message.streamId, message.bytes, message.sent, chunk, fin));
            message.sent += chunk;
            sendCredit -= chunk;

            // Back of the queue if there is more, so the next message gets a turn before this one does.
            if (!fin) outbound.addLast(message);
        }
    }

    // ── Receiving ───────────────────────────────────────────────────────────

    /** <b>Network thread.</b> Enqueues and returns; never parses, never delivers. */
    public void onFrameReceived(byte[] frame) {
        arrived.add(frame);
    }

    /**
     * <b>Game thread.</b> Processes what arrived, delivers whole messages, then sends what is queued.
     *
     * @return how many whole messages were delivered
     */
    public int pump() {
        int delivered = 0;
        byte[] frame;
        while ((frame = arrived.poll()) != null) {
            delivered += accept(frame);
        }
        replenish();
        flush();
        return delivered;
    }

    private int accept(byte[] frame) {
        int opcode = FrameCodec.opcode(frame);
        int streamId = FrameCodec.streamId(frame);

        if (opcode == FrameCodec.OP_WINDOW_UPDATE) {
            // Saturating: a peer that grants more than we can hold is a peer we simply believe less.
            sendCredit = Math.min(sendCredit + FrameCodec.credit(frame), Integer.MAX_VALUE);
            return 0;
        }
        if (opcode == FrameCodec.OP_RESET) {
            drop(streamId);
            return 0;
        }
        if (opcode != FrameCodec.OP_DATA) {
            throw new CodecException("unknown wire opcode 0x" + Integer.toHexString(opcode));
        }
        if (streamId == FrameCodec.CONNECTION_STREAM) {
            throw new CodecException("DATA on the connection stream");
        }

        int length = FrameCodec.payloadLength(frame);
        int offset = FrameCodec.payloadOffset(frame);
        unacknowledgedBytes += length;

        // The single-frame case, which is the overwhelming majority: no buffer, no map entry.
        if (FrameCodec.isFin(frame) && !reassembling.containsKey(streamId)) {
            byte[] message = new byte[length];
            System.arraycopy(frame, offset, message, 0, length);
            messageHandler.accept(message);
            return 1;
        }

        if (reassemblyBytes + length > MAX_REASSEMBLY_BYTES) {
            // Refuse the stream rather than the connection: one oversized transfer should not take down
            // an editor session that is otherwise fine.
            drop(streamId);
            sink.send(FrameCodec.reset(streamId));
            throw new CodecException("reassembly would exceed " + MAX_REASSEMBLY_BYTES + " bytes");
        }

        ByteArrayOutputStream buffer =
                reassembling.computeIfAbsent(streamId, ignored -> new ByteArrayOutputStream());
        buffer.write(frame, offset, length);
        reassemblyBytes += length;

        if (!FrameCodec.isFin(frame)) return 0;

        byte[] message = buffer.toByteArray();
        drop(streamId);
        messageHandler.accept(message);
        return 1;
    }

    private void drop(int streamId) {
        ByteArrayOutputStream buffer = reassembling.remove(streamId);
        if (buffer != null) reassemblyBytes -= buffer.size();
    }

    /**
     * Gives the peer back what we have consumed.
     *
     * <p>Batched rather than per-frame, and only past a threshold: a {@code WINDOW_UPDATE} per DATA frame
     * would double the packet count of every transfer to say something the next one would have said
     * anyway. Half the window is HTTP/2 implementations' usual point, and the spec explicitly leaves the
     * choice open — <i>"the document does not stipulate how a receiver decides when to send this
     * frame"</i>.</p>
     */
    private void replenish() {
        if (unacknowledgedBytes < DEFAULT_WINDOW_BYTES / 2) return;
        sink.send(FrameCodec.windowUpdate(FrameCodec.CONNECTION_STREAM, unacknowledgedBytes));
        unacknowledgedBytes = 0;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Abandons every partial transfer in both directions. Idempotent. */
    public void close() {
        closed = true;
        for (Iterator<Map.Entry<Integer, ByteArrayOutputStream>> it = reassembling.entrySet().iterator();
             it.hasNext(); ) {
            it.next();
            it.remove();
        }
        reassemblyBytes = 0;
        outbound.clear();
        arrived.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    /** Whole messages queued or partly sent. Zero means everything handed to the platform. */
    public int pendingOutboundMessages() {
        return outbound.size();
    }

    /** Bytes held in partial inbound messages — the number {@link #MAX_REASSEMBLY_BYTES} bounds. */
    public int reassemblyBytes() {
        return reassemblyBytes;
    }

    /** What the peer currently allows us to send. */
    public long sendCredit() {
        return sendCredit;
    }

    private static final class Outbound {
        final int streamId;
        final byte[] bytes;
        int sent;

        Outbound(int streamId, byte[] bytes) {
            this.streamId = streamId;
            this.bytes = bytes;
        }

        int remaining() {
            return bytes.length - sent;
        }
    }
}
