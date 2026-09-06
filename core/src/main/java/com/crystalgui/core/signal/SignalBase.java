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

    /**
     * How many emissions are in flight on this signal — a <b>depth</b>, never a boolean.
     *
     * <h3>Why a count</h3>
     *
     * <p>Every {@code emit} caches {@code int n = slots.size()} and indexes up to it. A boolean flag is
     * cleared by the <em>first</em> {@link #endEmit()} to finish, so a re-entrant emission would flush
     * {@link #pendingDisconnect} — shrinking {@code slots} — while an outer loop still held the old
     * {@code n}, and the outer loop would then run off the end with an
     * {@code IndexOutOfBoundsException} raised inside somebody's listener.</p>
     *
     * <p>Re-entrancy is not exotic here: it is the intended shape of the service events in
     * {@code plan/shell-architecture-audit.md} step 3, where a dirty change fires a title refresh which fires a layout change.
     * Before those existed every signal was a leaf and the boolean was fine, which is exactly why this
     * would have surfaced as a crash in unrelated code months later.</p>
     */
    private int emitDepth;

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
                if (emitDepth > 0) {
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

    /** Whether anything is connected. Lets an emitter fall back to a local default when nobody is
     * listening, rather than emitting into the void. */
    public final boolean hasListeners() {
        return !slots.isEmpty();
    }

    /** Marks emission as started. Must be called before iterating slots. */
    protected final void beginEmit() {
        emitDepth++;
    }

    /**
     * Marks emission as ended, and flushes deferred disconnects <b>only once the outermost one
     * finishes</b> — see {@link #emitDepth}. Removing a slot while an enclosing emission is still
     * iterating is what the depth exists to prevent.
     */
    protected final void endEmit() {
        emitDepth--;
        if (emitDepth > 0) return;
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

    /**
     * Disconnects all listeners.
     *
     * <p>Safe during an emission, for the reason {@link #emitDepth} gives: clearing the list outright
     * would leave an enclosing loop indexing past the end. Mid-emit this marks every slot disconnected —
     * which stops them receiving the rest of <em>this</em> emission, since the loops test
     * {@code entry.connected} — and defers the removal.</p>
     */
    public final void disconnectAll() {
        for (int i = 0; i < slots.size(); i++) {
            SlotEntry<L> entry = slots.get(i);
            entry.connected = false;
            if (emitDepth > 0) pendingDisconnect.add(entry);
        }
        if (emitDepth > 0) return;
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