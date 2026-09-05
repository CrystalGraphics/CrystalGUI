package com.crystalgui.mc.neoforge;

import java.util.function.BiConsumer;

import com.crystalgui.net.wire.CgNetworkChannel;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.handling.PlayPayloadContext;

import static com.crystalgui.mc.platform.CrystalGUI1201.MODID;

/** The MC 1.20.4 NeoForge transport: bytes in, bytes out. Framing and routing are {@code net.wire}'s. */
public final class NetworkChannel1201 implements CgNetworkChannel {

    private static final String VERSION = "1";
    private static final ResourceLocation ID = new ResourceLocation(MODID, "wire");

    /** Under the payload split threshold, so one frame stays one packet. */
    private static final int MAX_FRAME_BYTES = 900_000;

    private static final NetworkChannel1201 INSTANCE = new NetworkChannel1201();

    private volatile BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

    private NetworkChannel1201() {}

    public static NetworkChannel1201 get() {
        return INSTANCE;
    }

    /** One payload carrying a frame. */
    public record Frame(byte[] bytes) implements CustomPacketPayload {

        public Frame(FriendlyByteBuf buf) {
            this(buf.readByteArray());
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByteArray(bytes);
        }

        @Override
        public ResourceLocation id() {
            return ID;
        }
    }

    /** Wired to RegisterPayloadHandlerEvent on the mod bus. */
    public static void register(RegisterPayloadHandlerEvent event) {
        event.registrar(MODID)
                .versioned(VERSION)
                .play(ID, Frame::new, handler -> handler
                        .client(NetworkChannel1201::receive)
                        .server(NetworkChannel1201::receive));
    }

    private static void receive(Frame frame, PlayPayloadContext context) {
        // enqueueWork: the handler runs on the network thread and the tree is the frame thread's.
        context.workHandler().submitAsync(() -> {
            ServerPlayer sender = context.player().filter(p -> p instanceof ServerPlayer)
                    .map(p -> (ServerPlayer) p).orElse(null);
            INSTANCE.inbound.accept(sender, frame.bytes());
        });
    }

    @Override
    public int maxFrameBytes() {
        return MAX_FRAME_BYTES;
    }

    @Override
    public void sendToServer(byte[] frame) {
        PacketDistributor.SERVER.noArg().send(new Frame(frame));
    }

    @Override
    public void sendToPlayer(Object player, byte[] frame) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        PacketDistributor.PLAYER.with(serverPlayer).send(new Frame(frame));
    }

    @Override
    public void setInboundHandler(BiConsumer<Object, byte[]> handler) {
        inbound = handler == null ? (sender, frame) -> { } : handler;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
