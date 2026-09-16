package com.crystalgui.mc.v1710.net;

import java.util.Arrays;
import java.util.List;

import com.crystalgui.probe.ServerSmoke;

import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.server.MinecraftServer;

/**
 * The MC 1.7.10 half of the dedicated-server smoke: five facts and a way to stop the server.
 *
 * <p>Every check, the report and the exit code are {@link ServerSmoke}'s. This
 * host gained the enumerated client package and the JVM class-load reader with the move — both were
 * written for 1.20.x and are not era-specific, and the hand-written list they replace had already
 * rotted: it named a class W3 deleted, so it passed forever, while three that arrived later were never
 * checked at all.</p>
 */
public final class CgUiServerSmoke {

    private CgUiServerSmoke() {
    }

    /** Whether {@code -PcgServerSmoke} was passed. */
    public static boolean enabled() {
        return ServerSmoke.enabled();
    }

    /**
     * Called from {@code FMLServerStartedEvent} — the first moment the server is genuinely up, and late
     * enough that a mod which failed to load has already taken the process down with it.
     */
    public static void run() {
        ServerSmoke.run(new Host());
    }

    /**
     * In {@code .net} rather than {@code .client} deliberately: its own class is the anchor the client
     * package is enumerated from, so it must be in the same container and must be loadable on a server.
     */
    private static final class Host implements ServerSmoke.Host {

        @Override
        public String label() {
            return "1.7.10";
        }

        @Override
        public boolean isDedicatedServer() {
            MinecraftServer server = MinecraftServer.getServer();
            return FMLCommonHandler.instance().getSide().isServer()
                    && server != null && server.isDedicatedServer();
        }

        @Override
        public boolean connectionsRegistered() {
            return CgUiConnections.isRegistered();
        }

        @Override
        public String clientPackage() {
            return "com.crystalgui.mc.v1710.client";
        }

        @Override
        public List<String> alsoNeverLoaded() {
            return Arrays.asList(
                    // Client-side content outside that package. CommonProxy exists so both are
                    // unreachable from common code.
                    "com.crystalgui.mc.v1710.ClientProxy",
                    "com.crystalgui.mc.v1710.example.MachineExampleClient1710",
                    // Naming this from a common path is the commonest spelling of the bug.
                    "net.minecraft.client.Minecraft");
        }

        @Override
        public void halt() {
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) server.initiateShutdown();
        }
    }
}
