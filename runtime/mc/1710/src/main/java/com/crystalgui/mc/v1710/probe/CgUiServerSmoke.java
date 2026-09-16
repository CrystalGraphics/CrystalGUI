package com.crystalgui.mc.v1710.probe;

import java.util.Arrays;
import java.util.List;

import com.crystalgui.mc.v1710.net.CgUiConnections;
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
     * <b>The one class in {@code .probe} that a dedicated server loads</b>, and the anchor every other
     * class in there is enumerated from — so it must ship in the same container as them, and it
     * excludes itself from its own never-loaded set.
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
        public List<String> clientPackages() {
            // .probe TOO, since the probes moved out of .client: every class in there is client-only
            // bar this smoke, which excludes itself. Without it the three probes were checked by
            // nothing at all.
            return Arrays.asList("com.crystalgui.mc.v1710.client", "com.crystalgui.mc.v1710.probe");
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
