package com.crystalgui.mc.fabric;

import java.util.function.BiConsumer;

import com.crystalgui.net.wire.CgNetworkChannel;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.crystalgui.mc.platform.CrystalGUI1201.MODID;

/** The MC 1.20.1 Fabric transport: bytes in, bytes out. Framing and routing are {@code net.wire}'s. */
public final class NetworkChannel1201 implements CgNetworkChannel {

    private static final ResourceLocation ID = new ResourceLocation(MODID, "wire");

    /** Fabric's custom-payload limit is ~1 MB; staying under it keeps one frame one packet. */
    private static final int MAX_FRAME_BYTES = 900_000;

    private static final NetworkChannel1201 INSTANCE = new NetworkChannel1201();

    private volatile BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

    private NetworkChannel1201() {}

    public static NetworkChannel1201 get() {
        return INSTANCE;
    }

    /** The server half. Safe on a dedicated server; names no client class. */
    public static void registerServerReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, responder) -> {
            byte[] frame = buf.readByteArray();
            // The tree is the frame thread's; the receiver runs on the netty thread.
            server.execute(() -> INSTANCE.inbound.accept(player, frame));
        });
    }

    /** The client half, called only from the client initialiser. */
    public static void registerClientReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(ID, (client, handler, buf, responder) -> {
            byte[] frame = buf.readByteArray();
            client.execute(() -> INSTANCE.inbound.accept(null, frame));
        });
    }

    @Override
    public int maxFrameBytes() {
        return MAX_FRAME_BYTES;
    }

    @Override
    public void sendToServer(byte[] frame) {
        ClientPlayNetworking.send(ID, PacketByteBufs.create().writeByteArray(frame));
    }

    @Override
    public void sendToPlayer(Object player, byte[] frame) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        ServerPlayNetworking.send(serverPlayer, ID, PacketByteBufs.create().writeByteArray(frame));
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
