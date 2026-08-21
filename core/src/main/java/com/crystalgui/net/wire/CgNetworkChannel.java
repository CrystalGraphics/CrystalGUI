package com.crystalgui.net.wire;

import com.crystalgraphics.platform.CgService;

import java.util.function.BiConsumer;

/**
 * The whole platform contribution to networking: move an opaque {@code byte[]}, and say how big one may be.
 *
 * <p><b>Four methods, and that is the design.</b> Every decision worth getting wrong — framing, stream
 * ids, fragmentation, flow control, cancellation, what a message even means — lives in {@code core} above
 * this line. A loader never sees an {@code Envelope}, never learns a stream id and never picks a chunk
 * size. It registers a channel and forwards arrays. That is what "packets are owned by core" reduces to
 * in practice, and it is the reason a second platform is an afternoon rather than a re-implementation.</p>
 *
 * <h3>Depend on the loader's networking, not on raw Netty</h3>
 *
 * <p>Implementations are expected to sit on {@code SimpleNetworkWrapper} (1.7.10) or
 * {@code PayloadRegistrar} / {@code SimpleChannel} / Fabric networking (1.20.x) rather than attaching
 * their own pipeline handlers. Those already solve channel registration, the login handshake, "is this
 * player's client running our mod", compression and the login/play phase split — the parts that are
 * genuinely painful and easy to get subtly wrong. Bypassing them costs <em>more</em> per-platform code,
 * not less, and it differs by version anyway.</p>
 *
 * <h3>{@link #maxFrameBytes()} is asked for, never assumed</h3>
 *
 * <p>Measured from the sources rather than documentation, and it is asymmetric in a way no constant
 * survives: <b>client→server is ~32 KB on every version</b> — 32,766 on 1.7.10 and 32,767 on 1.20.1,
 * because vanilla writes the payload length as a <em>signed short</em> in both eras — while server→client
 * is 2,097,050 on 1.7.10 (Forge widens it) and 1,048,576 on 1.20.1. A hardcoded number is wrong on three
 * of those four. Reporting it lets {@link FrameMultiplexer} size fragments correctly with no version check
 * anywhere in {@code core}.</p>
 */
public interface CgNetworkChannel {

    /**
     * The slot. Declared here because CrystalGUI owns this contract — it is not something the rendering
     * framework requires, so it does not belong in {@code CgPlatformService}'s closed bundle.
     *
     * <p>M12's audit settled the rule this follows: <b>closed for what the framework requires; slots for
     * what its consumers require.</b></p>
     */
    CgService<CgNetworkChannel> SERVICE = CgService.of("crystalgui:network", new CgNetworkChannel() {
        @Override
        public int maxFrameBytes() {
            return 32_766;
        }

        @Override
        public void sendToServer(byte[] frame) {
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
        }

        @Override
        public void setInboundHandler(BiConsumer<Object, byte[]> handler) {
        }

        @Override
        public boolean isAvailable() {
            return false;
        }
    });

    /**
     * The largest single frame this platform will carry, in bytes.
     *
     * <p>Answer the <em>smaller</em> of the two directions if they differ and one implementation serves
     * both. Over-reporting is the dangerous mistake: an oversized payload throws from inside the loader
     * mid-send, with the connection already committed.</p>
     */
    int maxFrameBytes();

    /** Client → server. */
    void sendToServer(byte[] frame);

    /**
     * Server → one client.
     *
     * @param player the platform's own player handle, opaque to {@code core} — an {@code EntityPlayerMP}
     *               on 1.7.10, a {@code ServerPlayer} on 1.20.x. It is used only as an identity to route
     *               by and is never inspected above this seam, which is what keeps {@code core} free of
     *               {@code net.minecraft} imports that its build guard would refuse anyway
     */
    void sendToPlayer(Object player, byte[] frame);

    /**
     * Installs the inbound sink. <b>Called on the network thread.</b>
     *
     * <p>The handler hands straight to {@link FrameMultiplexer#onFrameReceived}, which only enqueues — see
     * that class's threading note. An implementation must <em>not</em> schedule onto the game thread
     * itself: doing so would make the frame's arrival and the session's pump two different orderings, and
     * the engine already owns that hop.</p>
     *
     * <p>The first argument is the sender: the player handle on a server, and ignored on a client, where
     * there is only ever one peer.</p>
     */
    void setInboundHandler(BiConsumer<Object, byte[]> handler);

    /**
     * Whether a real channel is behind this.
     *
     * <p>The absent-value answers {@code false}, which is what a caller checks before offering a remote
     * workspace. It deliberately does not throw: a host with no networking is a legitimate deployment —
     * the harness is one — and it should degrade to the in-process transport rather than fail.</p>
     */
    boolean isAvailable();
}
