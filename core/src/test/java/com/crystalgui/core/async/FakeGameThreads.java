package com.crystalgui.core.async;

import com.crystalgraphics.job.CgHostThread;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgGameThreads;

/**
 * A game-threads host a test points at its own queues: provided once, since {@link CgGameThreads} keeps the host it is
 * first given, and rebound per test.
 *
 * <pre>{@code
 * FakeGameThreads.bind(CgHostThread.SERVER, new CgHostThread.Queue());
 * // ... in teardown
 * FakeGameThreads.bind(CgHostThread.SERVER, null);
 * }</pre>
 */
public final class FakeGameThreads implements CgGameThreads.Host {

    private static final FakeGameThreads INSTANCE = new FakeGameThreads();

    static {
        CgPlatform.provide(CgGameThreads.HOST, INSTANCE);
    }

    private volatile CgGameThreads.Binding client;
    private volatile CgGameThreads.Binding server;

    private FakeGameThreads() {
    }

    /** Points {@code thread} at {@code binding}; null withdraws it. Only the client and server threads are bound. */
    public static void bind(CgHostThread thread, CgGameThreads.Binding binding) {
        if (thread == CgHostThread.CLIENT) INSTANCE.client = binding;
        else if (thread == CgHostThread.SERVER) INSTANCE.server = binding;
    }

    @Override
    public CgGameThreads.Binding client() {
        return client;
    }

    @Override
    public CgGameThreads.Binding server() {
        return server;
    }
}
