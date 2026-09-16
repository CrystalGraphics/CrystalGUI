package com.crystalgui.mc.modern.net;

import java.util.Arrays;
import java.util.List;

import com.crystalgui.probe.ServerSmoke;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;

/**
 * The MC 1.20.x half of the dedicated-server smoke: five facts and a way to stop the server.
 *
 * <p>Every check, the report and the exit code are {@link ServerSmoke}'s.</p>
 */
public final class ServerSmokeModern {

    private ServerSmokeModern() {}

    /** @see ServerSmoke#enabled() */
    public static boolean enabled() {
        return ServerSmoke.enabled();
    }

    /** Called from each loader's server-STARTED event. */
    public static void run(@Nullable MinecraftServer server) {
        ServerSmoke.run(new Host(server));
    }

    /**
     * In {@code .net} rather than {@code .client} deliberately: its own class is the anchor the client
     * package is enumerated from, so it must be in the same container and must be loadable on a server.
     */
    private static final class Host implements ServerSmoke.Host {

        @Nullable
        private final MinecraftServer server;

        Host(@Nullable MinecraftServer server) {
            this.server = server;
        }

        @Override
        public String label() {
            return "1.20.x";
        }

        @Override
        public boolean isDedicatedServer() {
            return server != null && server.isDedicatedServer();
        }

        @Override
        public boolean connectionsRegistered() {
            return Connections.isRegistered();
        }

        @Override
        public String clientPackage() {
            return "com.crystalgui.mc.modern.client";
        }

        @Override
        public List<String> alsoNeverLoaded() {
            return Arrays.asList(
                    // Client-side content that does not live in the client package.
                    "com.crystalgui.mc.modern.example.MachineExampleClientModern",
                    // Naming this from a common path is the commonest spelling of the bug.
                    "net.minecraft.client.Minecraft");
        }

        @Override
        public void halt() {
            if (server != null) server.halt(false);
        }
    }
}
