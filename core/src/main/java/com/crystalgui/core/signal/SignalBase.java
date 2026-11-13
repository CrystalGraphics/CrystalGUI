package com.crystalgui.core.signal;

import com.crystalgui.core.async.UiSequence;
import com.crystalgui.core.async.UiThread;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Shared connection machinery for all signal variants.
 *
 * <p>Manages slot storage, deferred disconnect-during-emit, connection count,
 * and bulk disconnect. Signal variants ({@link Signal.Action}, {@link Signal.Value},
 * {@link Signal.Pair}) extend this and add their type-safe {@code emit(...)}
 * and {@code connect(...)} methods.</p>
 *
 * <p><b>Local: one document's, on its sequence.</b> A signal binds to the sequence that first uses it, and a connect,
 * disconnect or emit from anywhere else throws, naming the signal and both threads (Chromium's
 * {@code SEQUENCE_CHECKER}). Use inside an inline document's own work (its frame, or what was sent to it, on the
 * host's thread) binds to that thread the same way; use anywhere else outside every sequence (a tree built on the
 * render thread before its document is handed to a sequence) neither binds nor is checked. Across sequences, use a
 * {@link com.crystalgui.core.async.Broadcast}.</p>
 *
 * @param <L> the listener type for this signal variant
 */
public abstract class SignalBase<L> {

    /**
     * What this signal belongs to, once something has used it: a {@link UiSequence}, or the thread that used it while
     * running a document of its own on no sequence (an inline document's host).
     */
    @Nullable
    private Object bound;

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
        checkSequence("connected");
        final SlotEntry<L> entry = new SlotEntry<>(listener);
        slots.add(entry);
        final String className = getClass().getSimpleName();
        DebugHook hook = debugHook;
        if (hook != null) hook.onConnect(className);
        return new Connection() {
            @Override
            public void disconnect() {
                if (!entry.connected) return;
                checkSequence("disconnected");
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
        checkSequence("emitted");
        emitDepth++;
    }

    /** Set by {@link #anyThread()}: the signal is not owned and is never checked. */
    private volatile boolean anyThread;

    /**
     * Declares this signal one that other threads emit by design (a script's thread reporting its state): it binds to
     * nothing and is never refused. Its listeners run on the emitting thread and must be safe there.
     *
     * <pre>{@code
     * public final Signal.Value<Resource> onDidChange = new Signal.Value<Resource>().anyThread();
     * }</pre>
     *
     * <p>Name the type argument: a diamond cannot infer through the call.</p>
     */
    @SuppressWarnings("unchecked")
    public final <S extends SignalBase<L>> S anyThread() {
        anyThread = true;
        return (S) this;
    }

    /** Binds to the first sequence that uses this signal; throws on use from anywhere else after. */
    private void checkSequence(String what) {
        if (anyThread) return;
        UiSequence sequence = UiSequence.current();
        // A thread running an inline document's own work is its owner, which a signal used inside it belongs to as it
        // would to a sequence. Any other use on the host's thread, building a tree to hand on, binds nothing.
        boolean hosting = UiThread.isHosting();
        Object mine = bound;
        if (mine == null) {
            Object first = sequence != null ? sequence : hosting ? Thread.currentThread() : null;
            if (first != null) bound = first;
            return;
        }
        // Both owners are current where a sequence's task runs on the host's thread (the shell closing an application
        // from the render thread): either one's signal is in reach.
        if (mine == sequence || mine == Thread.currentThread() && (hosting || sequence == null)) return;
        throw new IllegalStateException(getClass().getSimpleName() + " belongs to " + describe(mine) + " and was "
                + what + " from " + (sequence == null || hosting ? "" : "sequence '" + sequence.name() + "' on ")
                + "thread " + Thread.currentThread().getName() + ". Across sequences, use a Broadcast.");
    }

    private static String describe(Object owner) {
        return owner instanceof UiSequence ? "sequence '" + ((UiSequence) owner).name() + "'"
                : "thread '" + ((Thread) owner).getName() + "'";
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
        checkSequence("disconnected");
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