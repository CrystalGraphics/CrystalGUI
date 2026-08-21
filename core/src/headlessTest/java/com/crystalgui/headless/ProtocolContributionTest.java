package com.crystalgui.headless;

import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Can something that is not the UI speak this protocol? — the question the whole layering exists to
 * answer yes to.
 *
 * <p>{@code ProtocolTest} covers the envelope and the router. This covers the <b>wiring</b>: that a
 * subsystem registers once, globally, and is bound onto every connection afterwards without knowing one
 * exists. Both halves matter and the second is the one that was missing — the protocol was general while
 * a router was reachable only by constructing a {@code ServerUiSession}.</p>
 */
public class ProtocolContributionTest {

    private InMemoryTransport<Object>[] pair;
    private ProtocolConnection<Object> a;
    private ProtocolConnection<Object> b;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        pair = InMemoryTransport.pair();
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    /** Opens both ends after whatever the test contributed. */
    private void connect() {
        a = Protocols.open(pair[0], PlainOps.INSTANCE, () -> { }, "peer-b");
        b = Protocols.open(pair[1], PlainOps.INSTANCE, () -> { }, null);
    }

    private void settle() {
        for (int i = 0; i < 8; i++) {
            pair[0].deliver();
            pair[1].deliver();
            a.tick();
            b.tick();
        }
    }

    // ── The claim ───────────────────────────────────────────────────────────

    /**
     * Two unrelated subsystems, one connection, neither aware of the other.
     *
     * <p>This is the CustomNPC+ ergonomic with per-peer correctness: register once at init, and every
     * connection afterwards carries both. Namespaced methods are what make the coexistence safe —
     * {@code workspace/*} and {@code script/*} cannot collide unless someone picks the same prefix, and
     * the router refuses a duplicate outright rather than letting the second win.</p>
     */
    @Test
    public void twoUnrelatedSubsystemsShareOneConnection() {
        Protocols.contribute("workspace", new Protocols.Contributor() {
            @Override
            public <T> void bind(ProtocolConnection<T> connection) {
                connection.onRequest("workspace/read", (args, respond) -> {
                    StateMap<T> out = new StateMap<>(connection.ops());
                    out.putString("body", "contents of " + args.getString("path", "?"));
                    respond.ok(out);
                });
            }
        });
        Protocols.contribute("script", new Protocols.Contributor() {
            @Override
            public <T> void bind(ProtocolConnection<T> connection) {
                connection.onRequest("script/eval", (args, respond) -> {
                    StateMap<T> out = new StateMap<>(connection.ops());
                    out.putInt("result", args.getInt("x", 0) * 2);
                    respond.ok(out);
                });
            }
        });
        connect();

        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<Integer> doubled = new AtomicReference<>();

        StateMap<Object> read = new StateMap<>(PlainOps.INSTANCE);
        read.putString("path", "src/Main.java");
        b.call("workspace/read", read, result -> body.set(result.getString("body", "")), null);

        StateMap<Object> eval = new StateMap<>(PlainOps.INSTANCE);
        eval.putInt("x", 21);
        b.call("script/eval", eval, result -> doubled.set(result.getInt("result", -1)), null);

        settle();

        assertEquals("contents of src/Main.java", body.get());
        assertEquals(Integer.valueOf(42), doubled.get());
    }

    /**
     * A subsystem registers once and never sees a connection — the point of the split.
     *
     * <p>Contribution happens at init, when no peer exists. Every connection opened afterwards binds it,
     * which is what lets a dedicated server accept players for hours without the subsystem being involved
     * in any of it.</p>
     */
    @Test
    public void everyConnectionOpenedAfterwardsCarriesTheContribution() {
        List<Object> boundTo = new ArrayList<>();
        Protocols.contribute("audit", new Protocols.Contributor() {
            @Override
            public <T> void bind(ProtocolConnection<T> connection) {
                boundTo.add(connection.peer());
            }
        });

        Protocols.open(pair[0], PlainOps.INSTANCE, () -> { }, "player-one");
        Protocols.open(pair[1], PlainOps.INSTANCE, () -> { }, "player-two");

        assertEquals(List.of("player-one", "player-two"), boundTo);
    }

    /**
     * A connection knows which peer it belongs to, and the handle stays opaque.
     *
     * <p>{@code core} cannot name {@code EntityPlayerMP}, so a subsystem that needs one casts at its own
     * loader. What matters here is that two connections do not share it — an authorisation check reading
     * the wrong player is the failure this prevents.</p>
     */
    @Test
    public void eachConnectionCarriesItsOwnPeer() {
        connect();
        assertEquals("peer-b", a.peer());
        assertNull("a client has only one peer and does not name it", b.peer());
    }

    /** A contributor that throws costs the connection itself, never the other subsystems on it. */
    @Test
    public void oneBrokenContributorDoesNotTakeTheOthersWithIt() {
        Protocols.contribute("broken", new Protocols.Contributor() {
            @Override
            public <T> void bind(ProtocolConnection<T> connection) {
                throw new IllegalStateException("misconfigured");
            }
        });
        Protocols.contribute("working", new Protocols.Contributor() {
            @Override
            public <T> void bind(ProtocolConnection<T> connection) {
                connection.onRequest("working/ping", (args, respond) -> respond.ok(null));
            }
        });
        connect();

        AtomicReference<Boolean> answered = new AtomicReference<>(false);
        b.call("working/ping", null, result -> answered.set(true), null);
        settle();

        assertTrue("the surviving contributor must still serve its methods", answered.get());
    }

    /** Two subsystems claiming one name is a wiring mistake, and is refused rather than resolved. */
    @Test
    public void aDuplicateContributorNameIsRefused() {
        Protocols.Contributor noop = new Protocols.Contributor() {
            @Override
            public <T> void bind(ProtocolConnection<T> connection) {
            }
        };
        Protocols.contribute("workspace", noop);
        try {
            Protocols.contribute("workspace", noop);
            fail("a second contributor under one name must be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("workspace"));
        }
        assertEquals(Set.of("workspace"), Protocols.contributors());
    }

    /**
     * {@code tick()} pumps the wire as well as draining — one call, nothing to forget.
     *
     * <p>A subsystem that had to pump its transport separately would receive nothing, silently, which is
     * the failure shape this codebase keeps paying for. So the pump is supplied at open time and run by
     * the same tick that dispatches.</p>
     */
    @Test
    public void tickPumpsTheWireItself() {
        int[] pumps = {0};
        Protocols.contribute("noop", new Protocols.Contributor() {
            @Override
            public <T> void bind(ProtocolConnection<T> connection) {
            }
        });
        ProtocolConnection<Object> connection =
                Protocols.open(pair[0], PlainOps.INSTANCE, () -> pumps[0]++, null);

        connection.tick();
        connection.tick();

        assertEquals("the supplied pump runs once per tick", 2, pumps[0]);
    }
}
