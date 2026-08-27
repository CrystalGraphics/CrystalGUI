package com.crystalgui.headless;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
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
 * subsystem registers once, globally — as a lambda, sided at the call site — and is bound onto every
 * connection afterwards without knowing one exists. Both halves matter and the second is the one that
 * was missing — the protocol was general while a router was reachable only by constructing a
 * {@code ServerUiSession}.</p>
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
        Protocols.contribute("workspace", connection ->
                connection.onRequest("workspace/read", (args, respond) -> {
                    StateMap<Object> out = new StateMap<>(connection.ops());
                    out.putString("body", "contents of " + args.getString("path", "?"));
                    respond.ok(out);
                }));
        Protocols.contribute("script", connection ->
                connection.onRequest("script/eval", (args, respond) -> {
                    StateMap<Object> out = new StateMap<>(connection.ops());
                    out.putInt("result", args.getInt("x", 0) * 2);
                    respond.ok(out);
                }));
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
     * A UI window and a non-UI subsystem on ONE wire — the whole point, end to end.
     *
     * <p>The sessions no longer build a router; they take the connection's. So a workspace request and a
     * window's description handshake interleave on the same connection, correlate independently, and
     * neither knows the other exists. That is what "general enough for everything, not just UI" means
     * concretely, and before this the sessions and {@link Protocols} were parallel rather than composed.</p>
     */
    @Test
    public void aUiSessionAndANonUiSubsystemShareOneWire() {
        Protocols.contribute("workspace", connection ->
                connection.onRequest("workspace/read", (args, respond) -> {
                    StateMap<Object> out = new StateMap<>(connection.ops());
                    out.putString("body", "contents of " + args.getString("path", "?"));
                    respond.ok(out);
                }));
        connect();

        UIElement root = new UIElement();
        root.addChild(new Button("Press me"));
        ServerUiSession<Object> server = new ServerUiSession<>(1, root, a);
        ClientUiSession<Object> client = new ClientUiSession<>(b);

        AtomicReference<UIElement> arrived = new AtomicReference<>();
        client.onWindowOpened(arrived::set);

        AtomicReference<String> body = new AtomicReference<>();
        StateMap<Object> read = new StateMap<>(PlainOps.INSTANCE);
        read.putString("path", "src/Main.java");
        b.call("workspace/read", read, result -> body.set(result.getString("body", "")), null);

        server.open();
        // Only the CONNECTIONS are ticked for dispatch -- a session riding one does not drain. The
        // server session is still ticked so it flushes what its tree changed.
        for (int i = 0; i < 8; i++) {
            pair[0].deliver();
            pair[1].deliver();
            a.tick();
            b.tick();
            server.tick();
        }

        assertEquals("the workspace answered on the shared wire",
                "contents of src/Main.java", body.get());
        assertEquals("and the window arrived on the same one",
                1, arrived.get() == null ? -1 : arrived.get().getChildren().size());
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
        Protocols.contribute("audit", connection -> boundTo.add(connection.peer()));

        Protocols.open(pair[0], PlainOps.INSTANCE, () -> { }, "player-one");
        Protocols.open(pair[1], PlainOps.INSTANCE, () -> { }, "player-two");

        assertEquals(List.of("player-one", "player-two"), boundTo);
    }

    /**
     * {@code server()} and {@code client()} put the side in the method name — the guard every server
     * contributor used to open with, now unwritable wrong.
     */
    @Test
    public void aSidedContributorBindsOnlyOnItsOwnEnd() {
        List<String> log = new ArrayList<>();
        Protocols.server("mymod", connection -> log.add("server:" + connection.peer()));
        Protocols.client("mymod", connection -> log.add("client:" + connection.peer()));
        connect();

        // One name, both sides: two halves of one protocol, and each end got exactly its own.
        assertEquals(List.of("server:peer-b", "client:null"), log);
        assertEquals("one subsystem, however many sides it registered",
                Set.of("mymod"), Protocols.contributors());
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
        Protocols.contribute("broken", connection -> {
            throw new IllegalStateException("misconfigured");
        });
        Protocols.contribute("working", connection ->
                connection.onRequest("working/ping", (args, respond) -> respond.ok(null)));
        connect();

        AtomicReference<Boolean> answered = new AtomicReference<>(false);
        b.call("working/ping", null, result -> answered.set(true), null);
        settle();

        assertTrue("the surviving contributor must still serve its methods", answered.get());
    }

    /** Two subsystems claiming one name ON ONE SIDE is a wiring mistake, refused rather than resolved. */
    @Test
    public void aDuplicateContributorNameIsRefused() {
        Protocols.Contributor noop = connection -> { };
        Protocols.contribute("workspace", noop);
        try {
            Protocols.contribute("workspace", noop);
            fail("a second contributor under one name must be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("workspace"));
        }
        try {
            Protocols.server("only", noop);
            Protocols.server("only", noop);
            fail("the sided registrations dedupe too");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("only"));
        }
        assertEquals(Set.of("workspace", "only"), Protocols.contributors());
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
        Protocols.contribute("noop", connection -> { });
        ProtocolConnection<Object> connection =
                Protocols.open(pair[0], PlainOps.INSTANCE, () -> pumps[0]++, null);

        connection.tick();
        connection.tick();

        assertEquals("the supplied pump runs once per tick", 2, pumps[0]);
    }
}
