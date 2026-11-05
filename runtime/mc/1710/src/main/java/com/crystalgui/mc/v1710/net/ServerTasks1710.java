package com.crystalgui.mc.v1710.net;

import com.crystalgui.core.async.HostThread;

/**
 * 1.7.10's server has no task queue of its own: work UI code sends the server thread waits here, drained once a
 * server tick ({@code CommonProxy} subscribes {@link #drain}).
 */
public final class ServerTasks1710 {

    /** The queue {@code Host1710.serverThread()} executes on. */
    public static final HostThread.Queue QUEUE = new HostThread.Queue();

    private ServerTasks1710() {
    }

    /** Runs what is queued; on the server thread. */
    public static void drain() {
        QUEUE.drain();
    }
}
