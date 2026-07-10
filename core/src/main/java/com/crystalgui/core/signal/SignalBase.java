package com.crystalgui.core.signal;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared connection machinery for all signal variants.
 *
 * <p>Manages slot storage, deferred disconnect-during-emit, connection count,
 * and bulk disconnect. Signal variants ({@link Signal.Action}, {@link Signal.Value},
 * {@link Signal.Pair}) extend this and add their type-safe {@code emit(...)}
 * and {@code connect(...)} methods.</p>
 *
 * <p><b>Single-thread only.</b> All connections, disconnections, and emissions
 * must happen on the same thread (typically the UI thread).</p>
 *
 * @param <L> the listener type for this signal variant
 */
public abstract class SignalBase<L> {

    /**
     * Optional debug hook for signal connect/disconnect logging.
     * Set by harness or debug tooling; null by default (no overhead).
     */
    public static volatile DebugHook debugHook;

    public interface DebugHook {
        void onConnect(String signalClass);
        void onDisconnect(String signalClass);
    }

    private final List<SlotEntry<L>> slots = new ArrayList<>();
    private final List<SlotEntry<L>> pendingDisconnect = new ArrayList<>();
    private boolean emitting;

    /**
     * Adds a listener slot and returns the disconnect handle.
     * Called by concrete signal variants' {@code connect()} methods.
     */
    protected final Connection addSlot(L listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        final SlotEntry<L> entry = new SlotEntry<>(listener);
        slots.add(entry);
        final String className = getClass().getSimpleName();
        DebugHook hook = debugHook;
        if (hook != null) hook.onConnect(className);
        return new Connection() {
            @Override
            public void disconnect() {
                if (!entry.connected) return;
                entry.connected = false;
                DebugHook dh = debugHook;
                if (dh != null) dh.onDisconnect(className);
                if (emitting) {
                    pendingDisconnect.add(entry);
                } else {
                    slots.remove(entry);
                }
            }

            @Override
            public boolean isConnected() {
                return entry.connected;
            }
        };
    }

    /** Returns the live slot list for emission iteration. */
    protected final List<SlotEntry<L>> slots() {
        return slots;
    }

    /** Marks emission as started. Must be called before iterating slots. */
    protected final void beginEmit() {
        emitting = true;
    }

    /** Marks emission as ended and flushes any deferred disconnects. */
    protected final void endEmit() {
        emitting = false;
        if (!pendingDisconnect.isEmpty()) {
            for (int i = 0; i < pendingDisconnect.size(); i++) {
                slots.remove(pendingDisconnect.get(i));
            }
            pendingDisconnect.clear();
        }
    }

    /** Returns the number of currently connected listeners. */
    public final int connectionCount() {
        return slots.size();
    }

    /** Disconnects all listeners. */
    public final void disconnectAll() {
        for (int i = 0; i < slots.size(); i++) {
            slots.get(i).connected = false;
        }
        slots.clear();
        pendingDisconnect.clear();
    }

    /**
     * Slot entry pairing a listener with a connected flag.
     * Package-visible so signal variants can iterate directly.
     */
    static final class SlotEntry<L> {
        final L listener;
        boolean connected = true;

        SlotEntry(L listener) {
            this.listener = listener;
        }
    }
}
