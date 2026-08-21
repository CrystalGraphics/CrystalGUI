package com.crystalgui.net.wire;

import com.crystalgui.net.UITransport;
import com.crystalgui.serialization.BinaryFormat;
import com.crystalgui.serialization.PlainOps;

import java.util.function.Consumer;

/**
 * A {@link UITransport} over a real connection — the swap {@code Mc1710Workspace} was built to accept.
 *
 * <p>That class already says what this is for:</p>
 *
 * <blockquote>
 * <i>Both halves of a real workspace, in the client process … every listing, read and write crosses
 * {@code InMemoryTransport} as a real packet. Shortcutting that would make the later phase — the same
 * client against a workspace on a dedicated server — <b>a rewrite rather than a transport swap</b>.</i>
 * </blockquote>
 *
 * <p>So this deliberately implements {@code UITransport<Object>} — the same parameterisation
 * {@code InMemoryTransport<Object>} has, over the same {@link PlainOps} trees. A session cannot tell the
 * difference, and the swap is a constructor call rather than a change to anything above it.</p>
 *
 * <h3>Where the encoding happens</h3>
 *
 * <p>{@code UITransport} takes {@code T} rather than a {@code UIPacket} precisely so that <i>"every
 * implementation — including the in-memory one used by tests — exercises the real codec on every
 * hop"</i>. That is upheld here and extended by one step: the session encodes its packet to a
 * {@code PlainOps} tree, and this encodes that tree to bytes through {@link BinaryFormat}. The tree
 * crossing an in-memory transport today and the bytes crossing a socket tomorrow describe the same
 * value, which is what keeps a headless test meaningful about production.</p>
 */
public final class WireTransport implements UITransport<Object> {

    private final FrameMultiplexer frames;
    private Consumer<Object> receiver = value -> { };

    public WireTransport(FrameMultiplexer frames) {
        this.frames = frames;
        // Decoding here rather than in the engine keeps FrameMultiplexer ignorant of what it carries: it
        // moves byte arrays, and every question about their meaning belongs on this side of the seam.
        frames.setMessageHandler(bytes -> receiver.accept(BinaryFormat.decode(bytes)));
    }

    @Override
    public void send(Object encodedPacket) {
        frames.send(BinaryFormat.encode(encodedPacket));
    }

    @Override
    public void setReceiver(Consumer<Object> receiver) {
        this.receiver = receiver == null ? value -> { } : receiver;
    }

    /**
     * Delivers what arrived and sends what is queued. <b>Call once per frame, on the thread that owns
     * the tree.</b>
     *
     * <p>The one thing an in-memory transport did not need and this does. Nothing here is delivered
     * spontaneously — see {@link FrameMultiplexer}'s threading note.</p>
     *
     * @return whole packets delivered this pump
     */
    public int pump() {
        return frames.pump();
    }

    public FrameMultiplexer frames() {
        return frames;
    }
}
