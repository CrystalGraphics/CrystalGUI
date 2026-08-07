package com.crystalgui.core.signal;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>What a signal must survive once signals stop being leaves.</b>
 *
 * <p>Every signal in the engine was a leaf until now: something emitted, listeners ran, nothing emitted
 * back. {@code plan.md} step 3 makes them <b>chain</b> on purpose — a dirty change fires a tab-title
 * refresh which fires a layout change — so re-entrancy stops being exotic and becomes the normal shape.</p>
 *
 * <p>These pin the two properties that has to rest on: an emission may nest, and a subscription may be
 * owned rather than remembered.</p>
 */
public class SignalLifecycleTest {

    @Before
    @After
    public void resetDisposer() {
        Disposer.resetForTesting();
    }

    // ── Re-entrancy ─────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The bug this test was written from.</b>
     *
     * <p>{@code emitting} was a {@code boolean}, and {@code endEmit()} cleared it unconditionally. So the
     * <em>inner</em> emission's cleanup flushed the pending-disconnect list — shrinking {@code slots} —
     * while the outer loop was still indexing against the size it cached before it started. The outer
     * loop then read past the end and threw {@code IndexOutOfBoundsException} from inside somebody's
     * listener, at a depth nobody was looking at.</p>
     *
     * <p><b>It has to be the same signal re-entered.</b> Each {@code SignalBase} owns its own pending
     * list, so a listener emitting a <em>different</em> signal cannot corrupt this one's walk — a first
     * draft of this test did exactly that and passed against the broken code. The real shape is a
     * listener that causes its own signal to fire again, which is what a chain of service events
     * produces the moment two of them point back at each other.</p>
     *
     * <p>Three listeners, because the failure needs the outer loop somewhere left to go: the nested
     * emission removes one slot, and a stale count of 3 against a list of 2 is what runs off the end.</p>
     */
    @Test
    public void aReEntrantEmissionMayDisconnectWithoutBreakingTheOuterLoop() {
        Signal.Action signal = new Signal.Action();
        List<String> ran = new ArrayList<>();
        boolean[] reentered = {false};

        Connection[] doomed = new Connection[1];
        signal.connect(() -> {
            ran.add("first");
            if (!reentered[0]) {
                reentered[0] = true;
                signal.emit();                 // same signal, one level down
            }
        });
        doomed[0] = signal.connect(() -> {
            ran.add("second");
            if (reentered[0]) doomed[0].disconnect();
        });
        signal.connect(() -> ran.add("third"));

        signal.emit();

        // The nested pass runs all three (the first re-entering listener sees its own guard and stops),
        // and the outer pass then continues over the ORIGINAL three slots -- skipping the one that
        // disconnected, because delivery checks `connected` even while removal is deferred.
        assertEquals(List.of("first", "first", "second", "third", "third"), ran);
    }

    /** The deferred removal still happens — once the outermost emission is finished, not before. */
    @Test
    public void aSlotDisconnectedDuringAReEntrantEmitIsRemovedAfterTheOutermostOne() {
        Signal.Action signal = new Signal.Action();
        boolean[] reentered = {false};

        Connection[] doomed = new Connection[1];
        signal.connect(() -> {
            if (!reentered[0]) {
                reentered[0] = true;
                signal.emit();
            }
        });
        doomed[0] = signal.connect(() -> {
            if (reentered[0]) doomed[0].disconnect();
        });

        assertEquals(2, signal.connectionCount());
        signal.emit();
        assertEquals("the slot should be gone once nothing is iterating", 1, signal.connectionCount());
    }

    /** A listener disconnected mid-emission does not receive the rest of that emission. */
    @Test
    public void aDisconnectDuringEmissionTakesEffectImmediatelyForDelivery() {
        Signal.Action signal = new Signal.Action();
        List<String> ran = new ArrayList<>();

        Connection[] second = new Connection[1];
        signal.connect(() -> {
            ran.add("first");
            second[0].disconnect();
        });
        second[0] = signal.connect(() -> ran.add("second"));
        signal.connect(() -> ran.add("third"));

        signal.emit();
        assertEquals(List.of("first", "third"), ran);
    }

    /** {@code disconnectAll} from inside a listener is the teardown case, and must not corrupt the walk. */
    @Test
    public void disconnectAllDuringEmissionIsSafe() {
        Signal.Action signal = new Signal.Action();
        List<String> ran = new ArrayList<>();

        signal.connect(() -> {
            ran.add("first");
            signal.disconnectAll();
        });
        signal.connect(() -> ran.add("second"));
        signal.connect(() -> ran.add("third"));

        signal.emit();

        assertEquals("everything after the teardown should be skipped", List.of("first"), ran);
        assertEquals(0, signal.connectionCount());
    }

    /**
     * A listener added during an emission does not receive it.
     *
     * <p>Documented rather than changed: the alternative is a listener receiving the very event that
     * caused it to subscribe, which is worse and is not what either reference does.</p>
     */
    @Test
    public void aListenerConnectedDuringAnEmissionSkipsThatEmission() {
        Signal.Action signal = new Signal.Action();
        List<String> ran = new ArrayList<>();

        signal.connect(() -> {
            ran.add("first");
            signal.connect(() -> ran.add("late"));
        });

        signal.emit();
        assertEquals(List.of("first"), ran);

        ran.clear();
        signal.emit();
        assertEquals("and receives every later one", List.of("first", "late"), ran);
    }

    /** Values, not just actions — the variant every service event in step 3 uses. */
    @Test
    public void theSameHoldsForValueSignals() {
        Signal.Value<String> signal = new Signal.Value<>();
        List<String> ran = new ArrayList<>();
        boolean[] reentered = {false};

        Connection[] doomed = new Connection[1];
        signal.connect(v -> {
            ran.add("first:" + v);
            if (!reentered[0]) {
                reentered[0] = true;
                signal.emit("inner");
            }
        });
        doomed[0] = signal.connect(v -> {
            ran.add("second:" + v);
            if (reentered[0]) doomed[0].disconnect();
        });
        signal.connect(v -> ran.add("third:" + v));

        signal.emit("outer");
        assertEquals(List.of("first:outer", "first:inner", "second:inner", "third:inner",
                "third:outer"), ran);
    }

    /**
     * <b>{@code Signal.Value} does not suppress equal values.</b>
     *
     * <p>Pinned because it was written down backwards once. {@code Property.set} suppresses; a signal
     * forwards. Every emitter in step 3 therefore decides for itself whether a no-op change is worth
     * announcing — nothing will decide it for them, and an existing equality guard must survive into the
     * emitter rather than being dropped as "the signal handles it".</p>
     */
    @Test
    public void valueSignalsDoNotSuppressEqualValues() {
        Signal.Value<String> signal = new Signal.Value<>();
        List<String> ran = new ArrayList<>();
        signal.connect(ran::add);

        signal.emit("same");
        signal.emit("same");

        assertEquals(List.of("same", "same"), ran);
    }

    // ── Ownership ───────────────────────────────────────────────────────────────────────────────

    /** A named owner, so the tree has something to hang a subscription on. */
    private static final class Owner implements Disposable {
        boolean disposed;

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    /**
     * <b>A subscription can be owned rather than remembered.</b>
     *
     * <p>The point of {@link Connection} being a {@link Disposable}. Without it every listener in the
     * engine needs a matching hand-written disconnect on a teardown path — which is the bookkeeping the
     * ownership tree exists to remove, and what leaks when a panel closes: the widget goes, the
     * subscription stays, and it keeps being called about a tree it is no longer in.</p>
     */
    @Test
    public void aConnectionRegisteredOnAnOwnerStopsFiringWhenTheOwnerIsDisposed() {
        Signal.Action signal = new Signal.Action();
        Owner owner = new Owner();
        List<String> ran = new ArrayList<>();

        Disposer.register(owner, signal.connect(() -> ran.add("tick")));

        signal.emit();
        assertEquals(1, ran.size());

        Disposer.dispose(owner);
        assertTrue(owner.disposed);

        signal.emit();
        assertEquals("the listener outlived its owner", 1, ran.size());
        assertEquals(0, signal.connectionCount());
    }

    /** The same for a whole group — VS Code's {@code DisposableStore}. */
    @Test
    public void aConnectionGroupIsDisposableAndReleasesEverythingInIt() {
        Signal.Action first = new Signal.Action();
        Signal.Value<String> second = new Signal.Value<>();
        Owner owner = new Owner();
        List<String> ran = new ArrayList<>();

        ConnectionGroup group = new ConnectionGroup();
        group.add(first.connect(() -> ran.add("first")));
        group.add(second.connect(ran::add));
        Disposer.register(owner, group);

        first.emit();
        second.emit("second");
        assertEquals(2, ran.size());

        Disposer.dispose(owner);

        first.emit();
        second.emit("again");
        assertEquals("a group registered on an owner must release with it", 2, ran.size());
    }

    /** Disposing a connection directly is disconnecting it — the same operation, not a second one. */
    @Test
    public void disposingAConnectionDisconnectsIt() {
        Signal.Action signal = new Signal.Action();
        Connection connection = signal.connect(() -> {
        });

        assertTrue(connection.isConnected());
        connection.dispose();
        assertFalse(connection.isConnected());
        assertEquals(0, signal.connectionCount());
    }
}
