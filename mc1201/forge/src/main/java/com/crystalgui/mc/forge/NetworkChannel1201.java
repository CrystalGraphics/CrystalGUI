package com.crystalgui.mc.forge;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.crystalgui.net.wire.CgNetworkChannel;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import static com.crystalgui.mc.platform.CrystalGUI1201.MODID;

/** The MC 1.20.1 Forge transport: bytes in, bytes out. Framing and routing are {@code net.wire}'s. */
public final class NetworkChannel1201 implements CgNetworkChannel {

    private static final String VERSION = "1";

    /**
     * Forge splits a payload across partials above ~1 MB. Staying under it keeps one frame one packet,
     * which is what the multiplexer above assumes when it sizes its chunks.
     */
    private static final int MAX_FRAME_BYTES = 900_000;

    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(MODID, "wire"))
            .networkProtocolVersion(() -> VERSION)
            .clientAcceptedVersions(VERSION::equals)
            .serverAcceptedVersions(VERSION::equals)
            .simpleChannel();

    private static final NetworkChannel1201 INSTANCE = new NetworkChannel1201();

    private volatile BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

    private NetworkChannel1201() {}

    public static NetworkChannel1201 get() {
        return INSTANCE;
    }

    /** Called once from the mod constructor, before anything can send. */
    public static void register() {
        CHANNEL.registerMessage(0, byte[].class,
                (frame, buf) -> buf.writeByteArray(frame),
                FriendlyByteBuf::readByteArray,
                NetworkChannel1201::receive);
    }

    private static void receive(byte[] frame, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        // enqueueWork: the handler runs on the network thread, and the tree is the frame thread's.
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();   // null on the client
            INSTANCE.inbound.accept(sender, frame);
        });
        ctx.setPacketHandled(true);
    }

    @Override
    public int maxFrameBytes() {
        return MAX_FRAME_BYTES;
    }

    @Override
    public void sendToServer(byte[] frame) {
        CHANNEL.sendToServer(frame);
    }

    @Override
    public void sendToPlayer(Object player, byte[] frame) {
        if (!(player instanceof ServerPlayer)) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player), frame);
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
